// ============================================================================
// Tempo Stats — WebSocket Connection
// Persistent connection to the paired phone for real-time communication.
// Solves: discovery (phone announces on connect), heartbeat (built-in),
// immediate sync (phone pushes "sync now"), now-playing push, and instant
// connection health detection (WS close = immediate detection).
//
// Security:
//   • Token is NOT sent in the URL — authenticated via post-connect message
//   • All outgoing messages are HMAC-signed with timestamp (replay protection)
//   • All incoming messages are HMAC-verified before processing
//   • Messages older than ±120s are rejected
//
// Service worker hibernation: WebSocket connections die when the SW hibernates.
// The module uses chrome.alarms to periodically wake and reconnect. The phone
// side should also attempt reconnection when it detects the WS dropped.
//
// Firefox: WebSocket is NOT supported in Manifest V3 service workers.
// The connect() method will early-return on Firefox. HTTP polling is used instead.
// ============================================================================

import type { PairingInfo, NowPlaying, PhoneSocketMessage, SignedWsMessage, WsAuthMessage } from '../shared/types';
import { SocketState } from '../shared/types';
import * as storage from './storage';
import { signWsMessage, verifyWsMessage } from '../shared/security';

const IS_FIREFOX = typeof navigator !== 'undefined' && navigator.userAgent.includes('Firefox');

const HEARTBEAT_INTERVAL_MS = 30_000;
const RECONNECT_BASE_DELAY_MS = 1_000;
const RECONNECT_MAX_DELAY_MS = 60_000;
const RECONNECT_ALARM_NAME = 'tempo-ws-reconnect';
const KEEPALIVE_ALARM_NAME = 'tempo-ws-keepalive';
const KEEPALIVE_INTERVAL_MINUTES = 4;
const SEND_QUEUE_MAX = 10;

export type SocketMessageHandler = (msg: PhoneSocketMessage) => void;
export type SocketStateHandler = (state: SocketState) => void;

export class PhoneSocket {
  private ws: WebSocket | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectAttempts = 0;
  private _state: SocketState = SocketState.Disconnected;
  private intentionalClose = false;
  private authenticated = false;
  private sendQueue: string[] = [];

  constructor(
    private getPairing: () => Promise<PairingInfo | null>,
    private onMessage: SocketMessageHandler,
    private onStateChange: SocketStateHandler,
  ) {}

  get state(): SocketState {
    return this._state;
  }

  private setState(state: SocketState): void {
    if (this._state === state) return;
    this._state = state;
    this.onStateChange(state);
  }

  async connect(): Promise<void> {
    if (IS_FIREFOX) {
      console.log('[Tempo WS] WebSocket not supported in Firefox service workers — using HTTP polling');
      this.setState(SocketState.Disconnected);
      try {
        const notified = await chrome.storage.session.get('firefoxWsNotified');
        if (!notified.firefoxWsNotified) {
          await chrome.storage.session.set({ firefoxWsNotified: true });
          console.warn('[Tempo WS] Firefox: Real-time sync unavailable. HTTP polling is used instead. This may increase latency.');
        }
      } catch { /* non-critical */ }
      return;
    }

    if (this._state === SocketState.Connected || this._state === SocketState.Connecting) return;

    if (this.ws) {
      this.ws.onopen = null;
      this.ws.onclose = null;
      this.ws.onerror = null;
      this.ws.onmessage = null;
      if (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING) {
        this.ws.close();
      }
      this.ws = null;
    }

    const pairing = await this.getPairing();
    if (!pairing || !pairing.phoneIp || !pairing.authToken) {
      this.setState(SocketState.Disconnected);
      return;
    }

    this.intentionalClose = false;
    this.authenticated = false;
    this.setState(SocketState.Connecting);

    try {
      const url = `ws://${pairing.phoneIp}:${pairing.phonePort}/ws`;
      this.ws = new WebSocket(url);

      this.ws.onopen = async () => {
        console.log('[Tempo WS] Connected, sending auth');
        try {
          await this.sendAuth(pairing);
          this.reconnectAttempts = 0;
          this.authenticated = true;
          this.setState(SocketState.Connected);
          this.startHeartbeat();
          this.startKeepaliveAlarm();
          this.flushSendQueue();
          storage.recordHealthPing(true).catch(() => {});
          chrome.alarms.clear(RECONNECT_ALARM_NAME);
        } catch (err) {
          console.warn('[Tempo WS] Auth send failed:', err);
          try { this.ws?.close(4001, 'Auth failed'); } catch {}
        }
      };

      this.ws.onmessage = async (event) => {
        try {
          const parsed = JSON.parse(event.data);

          if (parsed.type === 'auth_error') {
            console.error('[Tempo WS] Auth rejected by phone');
            try { this.ws?.close(4003, 'Auth rejected'); } catch {}
            return;
          }

          if (!this.authenticated) {
            if (parsed.type === 'auth_ok') {
              console.log('[Tempo WS] Auth confirmed by phone');
              return;
            }
            console.warn('[Tempo WS] Received message before auth, ignoring');
            return;
          }

          let msg: PhoneSocketMessage;
          if (parsed.sig && parsed.ts && parsed.payload) {
            const signed = parsed as SignedWsMessage;
            const valid = await verifyWsMessage(pairing.authToken, signed.payload, signed.sig, signed.ts);
            if (!valid) {
              console.warn('[Tempo WS] Invalid signature on incoming message, dropping');
              return;
            }
            msg = signed.payload;
          } else {
            msg = parsed as PhoneSocketMessage;
          }

          if (msg.type === 'pong') return;
          this.onMessage(msg);
        } catch (err) {
          console.warn('[Tempo WS] Failed to parse message:', err);
        }
      };

      this.ws.onclose = (event) => {
        console.log(`[Tempo WS] Disconnected: code=${event.code}, reason=${event.reason}`);
        this.stopHeartbeat();
        this.ws = null;
        this.authenticated = false;

        if (!this.intentionalClose) {
          this.setState(SocketState.Reconnecting);
          this.scheduleReconnect();
        } else {
          this.setState(SocketState.Disconnected);
        }
      };

      this.ws.onerror = () => {
        try { this.ws?.close(); } catch {}
      };
    } catch (err) {
      console.warn('[Tempo WS] Connection failed:', err);
      this.setState(SocketState.Reconnecting);
      this.scheduleReconnect();
    }
  }

  private async sendAuth(pairing: PairingInfo): Promise<void> {
    const authPayload = { type: 'auth', device_id: pairing.deviceName };
    const { sig, ts } = await signWsMessage(pairing.authToken, authPayload);
    const authMsg: WsAuthMessage = {
      type: 'auth',
      token: pairing.authToken,
      device_id: pairing.deviceName,
      sig,
      ts,
    };
    this.ws!.send(JSON.stringify(authMsg));
  }

  private async sendSigned(payload: PhoneSocketMessage): Promise<boolean> {
    if (this.ws?.readyState !== WebSocket.OPEN || !this.authenticated) return false;

    const pairing = await this.getPairing();
    if (!pairing?.authToken) return false;

    try {
      const { sig, ts } = await signWsMessage(pairing.authToken, payload);
      const signed: SignedWsMessage = { payload, sig, ts };
      this.ws.send(JSON.stringify(signed));
      return true;
    } catch {
      return false;
    }
  }

  private scheduleReconnect(): void {
    const delay = Math.min(
      RECONNECT_BASE_DELAY_MS * Math.pow(2, this.reconnectAttempts),
      RECONNECT_MAX_DELAY_MS
    );
    this.reconnectAttempts++;

    console.log(`[Tempo WS] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);

    chrome.alarms.clear(RECONNECT_ALARM_NAME);
    chrome.alarms.create(RECONNECT_ALARM_NAME, {
      delayInMinutes: Math.max(delay / 60_000, 0.5),
    });
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN && this.authenticated) {
        this.sendSigned({ type: 'ping', ts: Date.now() }).catch(() => {});
      }
    }, HEARTBEAT_INTERVAL_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private startKeepaliveAlarm(): void {
    chrome.alarms.clear(KEEPALIVE_ALARM_NAME);
    chrome.alarms.create(KEEPALIVE_ALARM_NAME, {
      periodInMinutes: KEEPALIVE_INTERVAL_MINUTES,
    });
  }

  private flushSendQueue(): void {
    while (this.sendQueue.length > 0) {
      const raw = this.sendQueue.shift()!;
      try {
        this.ws?.send(raw);
      } catch {
        this.sendQueue.unshift(raw);
        break;
      }
    }
  }

  private enqueueOrSend(raw: string): void {
    if (this.ws?.readyState === WebSocket.OPEN && this.authenticated) {
      try {
        this.ws.send(raw);
        return;
      } catch {}
    }
    if (this.sendQueue.length < SEND_QUEUE_MAX) {
      this.sendQueue.push(raw);
    }
  }

  handleAlarm(alarmName: string): void {
    if (alarmName === RECONNECT_ALARM_NAME) {
      if (this._state !== SocketState.Connected && !this.intentionalClose) {
        this.connect();
      }
    } else if (alarmName === KEEPALIVE_ALARM_NAME) {
      if (this.intentionalClose) {
        chrome.alarms.clear(KEEPALIVE_ALARM_NAME);
        return;
      }
      if (this._state === SocketState.Connected && this.ws?.readyState === WebSocket.OPEN && this.authenticated) {
        this.sendSigned({ type: 'ping', ts: Date.now() }).catch(() => {
          this.setState(SocketState.Reconnecting);
          this.scheduleReconnect();
        });
      } else if (this._state !== SocketState.Connected && this._state !== SocketState.Connecting) {
        this.connect();
      }
    }
  }

  async sendNowPlaying(np: NowPlaying): Promise<void> {
    const payload: PhoneSocketMessage = { type: 'now_playing', data: np };
    const pairing = await this.getPairing();
    if (!pairing?.authToken) return;

    if (this.ws?.readyState === WebSocket.OPEN && this.authenticated) {
      try {
        const { sig, ts } = await signWsMessage(pairing.authToken, payload);
        const signed: SignedWsMessage = { payload, sig, ts };
        this.ws.send(JSON.stringify(signed));
      } catch {
        this.enqueueOrSend(JSON.stringify({ payload, sig: '', ts: 0 }));
      }
    } else {
      try {
        const { sig, ts } = await signWsMessage(pairing.authToken, payload);
        const signed: SignedWsMessage = { payload, sig, ts };
        this.enqueueOrSend(JSON.stringify(signed));
      } catch {}
    }
  }

  async requestSync(): Promise<void> {
    await this.sendSigned({ type: 'sync_now' });
  }

  disconnect(): void {
    this.intentionalClose = true;
    this.authenticated = false;
    this.sendQueue = [];
    this.stopHeartbeat();
    chrome.alarms.clear(RECONNECT_ALARM_NAME);
    chrome.alarms.clear(KEEPALIVE_ALARM_NAME);
    try { this.ws?.close(1000, 'Extension closing'); } catch {}
    this.ws = null;
    this.setState(SocketState.Disconnected);
  }

  async reconnect(): Promise<void> {
    this.disconnect();
    this.intentionalClose = false;
    this.reconnectAttempts = 0;
    await this.connect();
  }
}

export { RECONNECT_ALARM_NAME, KEEPALIVE_ALARM_NAME };

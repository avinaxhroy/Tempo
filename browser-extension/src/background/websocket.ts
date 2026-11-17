/**
 * Persistent WebSocket connection to the paired phone.
 * Handles heartbeats, immediate sync requests, now-playing push,
 * and HMAC authentication with replay protection.
 *
 * Power management (no functional loss):
 * - The 25s heartbeat stays under Chrome's 30s WebSocket-liveness window,
 *   so an established socket keeps the service worker alive.
 * - Once no real traffic (now-playing pushes, phone messages) has flowed for
 *   SOCKET_IDLE_SUSPEND_MS, the socket is intentionally suspended instead of
 *   pinging an idle link forever. Any media update or successful sync calls
 *   ensureConnected() to revive it within milliseconds on LAN.
 * - Reconnect backoff escalates past the 60s fast-retry cap after repeated
 *   failures (phone away from home network), instead of probing every minute
 *   all day.
 *
 * Firefox note: WebSocket is not supported in MV3 background workers,
 * so HTTP polling is used as a fallback.
 */
import type { PairingInfo, NowPlaying, PhoneSocketMessage, SignedWsMessage, WsAuthMessage } from '../shared/types';
import { SocketState } from '../shared/types';
import * as storage from './storage';
import { signWsMessage, verifyWsMessage } from '../shared/security';
const HEARTBEAT_INTERVAL_MS = 25_000;
const RECONNECT_BASE_DELAY_MS = 1_000;
const RECONNECT_MAX_DELAY_MS = 60_000;
/**
 * Fast-retry phase (1s → 60s doubling) covers transient blips and phone
 * reboots. After this many consecutive failures the cap itself escalates
 * (60s → 2m → 4m → … → 5m), so a phone that is simply off-network does not
 * absorb a WebSocket handshake + radio wakeup every minute all day. Any
 * successful connect resets attempts; ensureConnected() also resets them so
 * a successful HTTP sync immediately restores fast retries.
 */
const RECONNECT_ESCALATE_AFTER_ATTEMPTS = 10;
const RECONNECT_SUSTAINED_MAX_DELAY_MS = 5 * 60_000;
/**
 * Suspend an established socket after this long without REAL traffic (signed
 * now-playing pushes, phone-initiated messages — pings/pongs don't count).
 * Five minutes halves handshake churn against a dozing phone vs. a shorter
 * window while still cutting idle radio wakeups ~95% overnight. Revival is
 * instant on the next media update or successful sync (ensureConnected).
 */
const SOCKET_IDLE_SUSPEND_MS = 300_000;
const RECONNECT_ALARM_NAME = 'tempo-ws-reconnect';
const KEEPALIVE_ALARM_NAME = 'tempo-ws-keepalive';
const SEND_QUEUE_MAX = 10;
const IS_FIREFOX = typeof navigator !== 'undefined' && navigator.userAgent.includes('Firefox');

let _staleKeepaliveCleared = false;

/**
 * Clear the legacy periodic keepalive alarm left over from older builds.
 * The single-heartbeat design pings over the open WebSocket only; the
 * KEEPALIVE alarm is no longer scheduled by this class. Safe to call
 * repeatedly; intended to run once at service-worker boot.
 */
export async function clearStaleAlarms(): Promise<void> {
  _staleKeepaliveCleared = true;
  try {
    await chrome.alarms.clear(KEEPALIVE_ALARM_NAME);
  } catch { /* best-effort */ }
}

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
  /** Last time REAL traffic (non-ping send, non-pong receive) flowed. */
  private _lastRealTrafficAt = Date.now();
  /** True while the socket is parked by suspendForIdle() awaiting revival. */
  private _idleSuspended = false;

  constructor(
    private getPairing: () => Promise<PairingInfo | null>,
    private onMessage: SocketMessageHandler,
    private onStateChange: SocketStateHandler,
    /**
     * Policy gate consulted by connect()/ensureConnected(): true only when
     * phone contact is allowed (paired AND not offline mode). One choke point
     * so no caller — including stale reconnect alarms from previous sessions
     * or builds — can open a socket against the user's wishes.
     */
    private canConnect: () => Promise<boolean>,
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
    // Defensive: clear the legacy keepalive alarm on first connect in case
    // clearStaleAlarms() was not invoked at boot (e.g. older SW still cached).
    if (!_staleKeepaliveCleared) {
      _staleKeepaliveCleared = true;
      chrome.alarms.clear(KEEPALIVE_ALARM_NAME).catch(() => {});
    }

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

    // Policy gate — offline mode / unpaired must never open a socket, no
    // matter which caller (alarm revival, popup, media tick) asked for it.
    if (!(await this.canConnect())) {
      this.setState(SocketState.Disconnected);
      return;
    }
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
          this._lastRealTrafficAt = Date.now();
          this._idleSuspended = false;
          this.authenticated = true;
          this.setState(SocketState.Connected);
          this.startHeartbeat();
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
          this._lastRealTrafficAt = Date.now();
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
      // Pings are not real traffic. Refreshing the clock for them made the idle-suspend check
      // below unreachable, so the service worker was never released.
      if (payload.type !== 'ping') this._lastRealTrafficAt = Date.now();
      return true;
    } catch {
      return false;
    }
  }

  private scheduleReconnect(): void {
    let delay = Math.min(
      RECONNECT_BASE_DELAY_MS * Math.pow(2, this.reconnectAttempts),
      RECONNECT_MAX_DELAY_MS
    );
    if (this.reconnectAttempts >= RECONNECT_ESCALATE_AFTER_ATTEMPTS) {
      delay = Math.min(
        delay * Math.pow(2, this.reconnectAttempts - RECONNECT_ESCALATE_AFTER_ATTEMPTS + 1),
        RECONNECT_SUSTAINED_MAX_DELAY_MS
      );
    }
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
      if (this.ws?.readyState !== WebSocket.OPEN || !this.authenticated) return;
      if (Date.now() - this._lastRealTrafficAt >= SOCKET_IDLE_SUSPEND_MS) {
        console.log('[Tempo WS] No real traffic for 3m — suspending socket until next media update');
        this.suspendForIdle();
        return;
      }
      this.sendSigned({ type: 'ping', ts: Date.now() }).catch(() => {});
    }, HEARTBEAT_INTERVAL_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  /**
   * Park an idle socket: close it WITHOUT triggering the reconnect alarm
   * cascade. Nothing is lost — plays keep queuing in IndexedDB, and the next
   * media update / successful sync calls ensureConnected() to revive instantly.
   * The service worker then becomes idle-eligible ~30s later (Chrome's WS
   * liveness window), freeing its memory and ending radio wakeups.
   */
  private suspendForIdle(): void {
    this.stopHeartbeat();
    this._idleSuspended = true;
    this.intentionalClose = true; // suppress onclose auto-reconnect
    this.authenticated = false;
    try { this.ws?.close(1000, 'idle'); } catch {}
    this.ws = null;
    this.setState(SocketState.IdleSuspended);
  }

  /**
   * Revive a suspended socket, or fast-retry a disconnected one, when there
   * is something to do again (media tick, successful HTTP sync). Explicitly
   * disconnected sockets (unpair, pairing_invalidated) stay down, and the
   * canConnect policy gate keeps offline mode closed no matter who asks.
   */
  ensureConnected(): void {
    if (this._state === SocketState.Connected || this._state === SocketState.Connecting) return;
    if (this.intentionalClose && !this._idleSuspended) return;
    void this.canConnect().then(allowed => {
      if (!allowed) {
        this.setState(SocketState.Disconnected);
        return;
      }
      this.intentionalClose = false;
      this._idleSuspended = false;
      this.reconnectAttempts = 0;
      return this.connect();
    }).catch(() => {});
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
        this._lastRealTrafficAt = Date.now();
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
      // Legacy keepalive alarm — no longer scheduled by this class and cleared
      // via clearStaleAlarms(). Until it is cleared, repurpose any firing as a
      // recovery trigger ONLY (never as a heartbeat; the open WebSocket's
      // 25s ping interval is the single heartbeat mechanism).
      if (this.intentionalClose) return;
      if (this._state !== SocketState.Connected && this._state !== SocketState.Connecting) {
        void this.getPairing()
          .then(pairing => {
            if (pairing) void this.connect();
          })
          .catch(() => {});
      }
    }
  }

  async sendNowPlaying(np: NowPlaying): Promise<void> {
    const payload: PhoneSocketMessage = { type: 'now_playing', data: np };
    const pairing = await this.getPairing();
    if (!pairing?.authToken) return;

    try {
      const { sig, ts } = await signWsMessage(pairing.authToken, payload);
      const signed: SignedWsMessage = { payload, sig, ts };
      this.enqueueOrSend(JSON.stringify(signed));
    } catch {
      // Signing failed — drop rather than enqueue an unverifiable message.
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
    try { this.ws?.close(1000, 'Extension closing'); } catch {}
    this.ws = null;
    this._idleSuspended = false;
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

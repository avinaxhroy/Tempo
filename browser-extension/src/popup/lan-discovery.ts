/**
 * Stateless LAN discovery primitives. The popup owns UI state and pairing,
 * while this module handles probes and candidate network addresses.
 */
import { signRequest, validatePingResponse } from '../shared/security';

export const SERVER_PORT = 8765;

/** Hotspot seeds — only valid when the PHONE is the hotspot gateway itself */
export const HOTSPOT_SEEDS = [
  '192.168.43.1',  // Android hotspot (phone IS the gateway)
  '172.20.10.1',   // iOS hotspot (phone IS the gateway)
  '192.168.49.1',  // Android WiFi Direct
];

/**
 * Best-effort .local hostname. Android advertises a DNS-SD service; some
 * networks/OSes also make a stable host name reachable, but many do not.
 * Keep this as a fast path, not the only IP-change recovery strategy.
 */
export const MDNS_HOSTNAME = 'tempo-phone.local';

// WebRTC local IP discovery
//
// RTCPeerConnection exposes the machine's actual LAN IP via ICE candidates.
// This works in Chrome extensions with no extra permissions and takes ~200ms.
// Importantly it returns the REAL adapter IP (e.g. 192.168.1.50), not the
// gateway, so we can scan the exact /24 the phone is on.
//
export async function getLocalIPsViaWebRTC(): Promise<string[]> {
  return new Promise(resolve => {
    const ips = new Set<string>();
    let settled = false;

    const finish = (pc: RTCPeerConnection) => {
      if (settled) return;
      settled = true;
      try { pc.close(); } catch {}
      resolve([...ips]);
    };

    try {
      const pc = new RTCPeerConnection({
        iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
      });
      const timer = setTimeout(() => finish(pc), 2000);

      pc.createDataChannel('');
      pc.createOffer()
        .then(o => pc.setLocalDescription(o))
        .catch(() => { clearTimeout(timer); finish(pc); });

      pc.onicecandidate = ({ candidate }) => {
        if (!candidate) {
          clearTimeout(timer);
          finish(pc);
          return;
        }
        const m = /(?:^|\s)(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?:\s|$)/.exec(
          candidate.candidate
        );
        if (!m) return;
        const addr = m[1];
        const p = addr.split('.').map(Number);
        const isPrivate =
          p[0] === 10 ||
          (p[0] === 172 && p[1] >= 16 && p[1] <= 31) ||
          (p[0] === 192 && p[1] === 168);
        if (isPrivate) ips.add(addr);
      };
    } catch {
      resolve([]);
    }
  });
}

// Direct discovery ping
//
// For DISCOVERY we fetch directly from the popup (not via service worker IPC)
// with an 800ms abort timeout. Non-existent hosts fail in <10ms (TCP RST or
// ARP miss), so 254 parallel fetches typically complete in well under 1 second.
//
export async function discoveryPing(ip: string, token: string): Promise<string | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 800);
  try {
    // signRequest() sends HMAC(token, body) — matches Android verification
    const headers = await signRequest(token);

    const res = await fetch(`http://${ip}:${SERVER_PORT}/api/ping`, {
      signal: controller.signal,
      headers,
    });
    clearTimeout(timer);
    if (res.ok) {
      const data = await res.json().catch(() => ({}));
      return validatePingResponse(data) ?? (data.device_name ?? ip);
    }
    // Log non-ok responses from reachable hosts — helps diagnose auth failures
    if (res.status !== 0) {
      console.debug(`[Tempo] ping ${ip} → HTTP ${res.status}`);
    }
    return null;
  } catch {
    clearTimeout(timer);
    return null;
  }
}



// ============================================================================
// Tempo Stats — Popup Logic
// QR-based pairing that mirrors the desktop app experience:
//   1. Extension generates QR (same format as Tempo Desktop)
//   2. User scans with Tempo app → phone stores token, starts server
//   3. Extension auto-discovers phone (same hotspot/subnet IPs as sync.ts)
//   4. Done — no manual IP/port entry required
//
// Fallback: if auto-discovery fails within 30s, show a one-field IP input.
// ============================================================================

import { MessageType } from '../shared/types';
import type { NowPlaying, Play, Settings, PairingInfo } from '../shared/types';
import { generateQrDataUrl } from './qr';
import { signRequest, validatePingResponse, pairingAgeDays } from '../shared/security';

// ---- Constants -------------------------------------------------------------

const QR_TOKEN_KEY    = 'tempo_pairing_token';
const QR_EXPIRY_KEY   = 'tempo_pairing_token_expiry';
const QR_TOKEN_TTL_MS = 10 * 60 * 1000;   // 10 minutes
const SERVER_PORT     = 8765;
const MANUAL_FALLBACK_AFTER_SECONDS = 30;

/** Hotspot seeds — only valid when the PHONE is the hotspot gateway itself */
const HOTSPOT_SEEDS = [
  '192.168.43.1',  // Android hotspot (phone IS the gateway)
  '172.20.10.1',   // iOS hotspot (phone IS the gateway)
  '192.168.49.1',  // Android WiFi Direct
];

/**
 * Best-effort .local hostname. Android advertises a DNS-SD service; some
 * networks/OSes also make a stable host name reachable, but many do not.
 * Keep this as a fast path, not the only IP-change recovery strategy.
 */
const MDNS_HOSTNAME = 'tempo-phone.local';

// ---- WebRTC local IP discovery ---------------------------------------------
//
// RTCPeerConnection exposes the machine's actual LAN IP via ICE candidates.
// This works in Chrome extensions with no extra permissions and takes ~200ms.
// Importantly it returns the REAL adapter IP (e.g. 192.168.1.50), not the
// gateway, so we can scan the exact /24 the phone is on.
//
async function getLocalIPsViaWebRTC(): Promise<string[]> {
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
      const pc = new RTCPeerConnection({ iceServers: [] });
      const timer = setTimeout(() => finish(pc), 2000);

      pc.createDataChannel('');
      pc.createOffer()
        .then(o => pc.setLocalDescription(o))
        .catch(() => { clearTimeout(timer); finish(pc); });

      pc.onicecandidate = ({ candidate }) => {
        if (!candidate) {
          // null candidate means gathering is complete
          clearTimeout(timer);
          finish(pc);
          return;
        }
        // ICE candidate strings look like:
        //   "candidate:... 1 udp 2122260223 192.168.1.50 PORT typ host ..."
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

// ---- Direct discovery ping -------------------------------------------------
//
// For DISCOVERY we fetch directly from the popup (not via service worker IPC)
// with an 800ms abort timeout. Non-existent hosts fail in <10ms (TCP RST or
// ARP miss), so 254 parallel fetches typically complete in well under 1 second.
//
async function discoveryPing(ip: string, token: string): Promise<string | null> {
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

// ---- State -----------------------------------------------------------------

type DiscoveryState = 'qr' | 'scanning' | 'manual' | 'success';

let currentNowPlaying: NowPlaying | null = null;
let currentPairing: PairingInfo | null = null;
let currentQueueCount = 0;
let currentPairingToken: string | null = null;

// Tracks the current discovery UI screen so tab switches don't reset it.
// Only reset to 'qr' when explicitly starting fresh (unpair / refresh QR).
let discoveryUIState: DiscoveryState = 'qr';

let npPollTimer: ReturnType<typeof setInterval> | null = null;
let qrCountdownTimer: ReturnType<typeof setInterval> | null = null;
let discoveryTimer: ReturnType<typeof setInterval> | null = null;
let discoveryAborted = false;

// ---- Tab Switching ---------------------------------------------------------

const tabBtns   = document.querySelectorAll<HTMLButtonElement>('.tab-btn');
const tabPanels = document.querySelectorAll<HTMLElement>('.tab-panel');

function switchTab(tabId: string) {
  tabBtns.forEach(b  => b.classList.remove('active'));
  tabPanels.forEach(p => p.classList.remove('active'));
  document.querySelector<HTMLButtonElement>(`.tab-btn[data-tab="${tabId}"]`)?.classList.add('active');
  document.getElementById(`tab-${tabId}`)?.classList.add('active');
  switch (tabId) {
    case 'now-playing': refreshNowPlaying(); break;
    case 'queue':       refreshQueue();      break;
    case 'pairing':     refreshPairing();    break;
    case 'artists':     refreshArtists();    break;
    case 'settings':    refreshSettings();   break;
  }
}

// Permission to reach http://*:8765/* is declared as a required host_permission
// in manifest.json — granted at install, no runtime request needed.
tabBtns.forEach(btn => btn.addEventListener('click', () => switchTab(btn.dataset.tab!)));

// ---- Utility ---------------------------------------------------------------

function send(type: string, data?: any): Promise<any> {
  return chrome.runtime.sendMessage({ type, ...data });
}

function formatDuration(ms: number): string {
  if (ms <= 0) return '0s';
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60), rs = s % 60;
  if (m < 60) return `${m}m ${rs}s`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const m = Math.floor(diff / 60_000);
  if (m < 1)  return 'Just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

function escapeHtml(text: string): string {
  const d = document.createElement('div');
  d.textContent = text;
  return d.innerHTML;
}

const VALID_STATUSES = new Set(['queued', 'synced', 'failed']);
function sanitizeStatus(s: string): string {
  return VALID_STATUSES.has(s) && /^[a-z]+$/.test(s) ? s : 'unknown';
}

// ---- Home State ------------------------------------------------------------

function refreshHomeState() {
  const pill    = document.getElementById('home-status-pill')!;
  const title   = document.getElementById('home-title')!;
  const copy    = document.getElementById('home-copy')!;
  const primary = document.getElementById('home-primary-action') as HTMLButtonElement;
  const second  = document.getElementById('home-secondary-action') as HTMLButtonElement;
  const emTitle = document.getElementById('np-empty-title')!;
  const emCopy  = document.getElementById('np-empty-copy')!;
  const banner  = document.getElementById('queue-banner')!;

  banner.style.display = currentPairing ? 'none' : '';

  if (!currentPairing) {
    pill.textContent  = 'Setup required';
    title.textContent = 'Connect your Tempo app';
    copy.textContent  = 'Pair your phone — just scan the QR code in the Pair tab.';
    primary.textContent = 'Scan to connect';
    second.textContent  = currentQueueCount > 0 ? `View queue (${currentQueueCount})` : 'View queue';
    emTitle.textContent = 'Phone not connected';
    emCopy.textContent  = 'Open the Pair tab and scan the QR with Tempo on your phone.';
    return;
  }

  if (currentNowPlaying?.title) {
    pill.textContent  = 'Live tracking';
    title.textContent = 'Now tracking this session';
    copy.textContent  = `${currentNowPlaying.title} is being monitored.`;
    primary.textContent = currentQueueCount > 0 ? `View queue (${currentQueueCount})` : 'View queue';
    second.textContent  = 'Pairing details';
    emTitle.textContent = 'No music playing';
    emCopy.textContent  = 'Start playing from a supported site.';
    return;
  }

  pill.textContent  = 'Ready to track';
  title.textContent = currentQueueCount > 0
    ? `${currentQueueCount} play${currentQueueCount === 1 ? '' : 's'} waiting to sync`
    : 'Connected and ready';
  copy.textContent  = currentQueueCount > 0
    ? 'Open Queue to sync them to Tempo.'
    : 'Play music on a supported site and Tempo Stats will queue it automatically.';
  primary.textContent = currentQueueCount > 0 ? `View queue (${currentQueueCount})` : 'Open pairing';
  second.textContent  = currentQueueCount > 0 ? 'Open pairing' : 'View queue';
  emTitle.textContent = 'No music playing';
  emCopy.textContent  = 'Play on YouTube Music, Spotify Web, SoundCloud, Apple Music, or Tidal.';
}

// ---- Now Playing -----------------------------------------------------------

async function refreshNowPlaying() {
  try {
    const resp = await send(MessageType.GetNowPlaying);
    const np: NowPlaying | null = resp?.nowPlaying ?? null;
    currentNowPlaying = np;

    const emptyEl   = document.getElementById('np-empty')!;
    const contentEl = document.getElementById('np-content')!;

    if (!np?.title) {
      emptyEl.style.display   = '';
      contentEl.style.display = 'none';
      refreshHomeState();
      return;
    }

    emptyEl.style.display   = 'none';
    contentEl.style.display = '';

    document.getElementById('np-title')!.textContent       = np.title;
    document.getElementById('np-artist')!.textContent      = np.artist || 'Unknown Artist';
    document.getElementById('np-album')!.textContent       = np.album || '';
    document.getElementById('np-listened')!.textContent    = formatDuration(np.listenedMs);
    document.getElementById('np-completion')!.textContent  = `${Math.round(np.completionPercentage)}%`;
    document.getElementById('np-source')!.textContent      = np.site ?? np.sourceApp;
    (document.getElementById('np-progress') as HTMLElement).style.width = `${Math.min(np.completionPercentage, 100)}%`;
    document.getElementById('np-pause-count')!.textContent  = `⏸ ${np.pauseCount}`;
    document.getElementById('np-seek-count')!.textContent   = `⏭ ${np.seekCount}`;
    document.getElementById('np-replay-count')!.textContent = `🔁 ${np.replayCount}`;
    document.getElementById('np-mute-badge')!.style.display  = np.isMuted ? '' : 'none';

    refreshHomeState();
  } catch (err) {
    console.warn('[Tempo] refreshNowPlaying failed:', err);
  }
}

function startNpPolling() {
  if (npPollTimer) return;
  refreshNowPlaying();
  npPollTimer = setInterval(refreshNowPlaying, 2000);
}

// ---- Queue -----------------------------------------------------------------

const MAX_INPUT_LEN = 100;

async function refreshQueue() {
  try {
    const [countR, itemsR, statusR] = await Promise.all([
      send(MessageType.GetQueueCount),
      send(MessageType.GetQueueItems),
      send(MessageType.GetSyncStatus),
    ]);

    currentQueueCount = countR?.count ?? 0;
    document.getElementById('queue-count')!.textContent = String(currentQueueCount);

    const statusEl = document.getElementById('sync-status')!;
    statusEl.textContent = statusR?.lastSyncTime
      ? `Last sync: ${timeAgo(statusR.lastSyncTime)} — ${statusR.lastSyncResult ?? ''}`
      : (currentPairing ? 'Not synced yet' : 'Pair your phone to start syncing');

    const listEl = document.getElementById('queue-list')!;
    const plays: Play[] = itemsR?.plays ?? [];

    listEl.innerHTML = plays.length === 0
      ? '<div class="empty-state"><p class="text-muted">No plays in queue</p></div>'
      : plays.map(p => `
          <div class="queue-item" data-id="${p.id ?? ''}">
            <div class="queue-item-info">
              <div class="queue-item-title">${escapeHtml(p.title)}</div>
              <div class="queue-item-artist">${escapeHtml(p.artist || 'Unknown')} · ${formatDuration(p.listenedMs)}</div>
            </div>
            <span class="queue-item-status ${sanitizeStatus(p.status)}">${sanitizeStatus(p.status)}</span>
            <button class="queue-item-delete" data-id="${p.id ?? ''}" title="Remove this track" aria-label="Delete track">
              <svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>
            </button>
          </div>`).join('');

    // Event delegation — one listener per render handles all delete buttons
    listEl.querySelectorAll<HTMLButtonElement>('.queue-item-delete').forEach(btn => {
      btn.addEventListener('click', async (e) => {
        e.stopPropagation();
        const id = parseInt(btn.dataset.id ?? '', 10);
        if (!id) return;
        btn.disabled = true;
        await send(MessageType.DeletePlay, { id });
        refreshQueue();
      });
    });

    refreshHomeState();
  } catch (err) {
    console.warn('[Tempo] refreshQueue failed:', err);
  }
}

document.getElementById('btn-retry-failed')?.addEventListener('click', async () => {
  const btn = document.getElementById('btn-retry-failed') as HTMLButtonElement;
  btn.disabled = true;
  btn.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" style="animation:spin .7s linear infinite"><path d="M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z"/></svg> Retrying…`;
  try {
    const r = await send(MessageType.RetryFailedPlays);
    btn.textContent = (r?.retried > 0) ? `✓ ${r.retried} re-queued` : '✓ Nothing to retry';
  } catch { btn.textContent = '✗ Error'; }
  setTimeout(() => {
    btn.disabled = false;
    btn.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z"/></svg> Retry`;
    refreshQueue();
  }, 2000);
});

document.getElementById('btn-sync-now')?.addEventListener('click', async () => {
  const btn = document.getElementById('btn-sync-now') as HTMLButtonElement;
  btn.disabled = true;
  btn.textContent = 'Syncing…';
  try {
    const r = await send(MessageType.SyncNow);
    btn.textContent = r?.ok ? `✓ Synced ${r.synced ?? 0}` : `✗ ${r?.error ?? 'Failed'}`;
  } catch { btn.textContent = '✗ Error'; }
  setTimeout(() => {
    btn.disabled = false;
    btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46A7.93 7.93 0 0020 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74A7.93 7.93 0 004 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"/></svg> Sync Now';
    refreshQueue();
  }, 2500);
});

document.getElementById('btn-clear-queue')?.addEventListener('click', async () => {
  if (!confirm('Clear all queued plays? This cannot be undone.')) return;
  await send(MessageType.ClearQueue);
  refreshQueue();
});


// ============================================================================
//  PAIRING — WhatsApp Web-style: zero extra steps, auto-discovers on QR show
// ============================================================================
//
//  Flow:
//    1. User clicks Pair tab → permission requested (user gesture ✓)
//    2. QR renders → auto-discovery starts immediately in background
//    3. Status bar below QR updates live: "Looking for phone… attempt N"
//    4. Phone scans QR → phone starts HTTP server → extension pings and finds it
//    5. Automatically transitions to connected state — zero extra clicks
//    6. If not found after 30 s → manual IP fallback slides in below QR
//    7. QR expiry → auto-refreshes and restarts discovery
// ============================================================================

/** Generate a 32-byte cryptographically random hex token. */
function generateToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
}

/** Load cached token or mint a fresh one. */
async function ensureToken(): Promise<string> {
  const { [QR_TOKEN_KEY]: tok, [QR_EXPIRY_KEY]: exp } =
    await chrome.storage.local.get([QR_TOKEN_KEY, QR_EXPIRY_KEY]);

  const expiry = typeof exp === 'number' ? exp : 0;
  if (tok && expiry > 0 && Date.now() < expiry && !currentPairing) return tok as string;

  const token    = generateToken();
  const newExpiry = Date.now() + QR_TOKEN_TTL_MS;
  await chrome.storage.local.set({ [QR_TOKEN_KEY]: token, [QR_EXPIRY_KEY]: newExpiry });
  return token;
}

// ---- Status bar helpers ----------------------------------------------------

function setQrStatus(
  mode: 'idle' | 'searching' | 'found' | 'error',
  text: string,
  elapsed?: string
) {
  const dot      = document.getElementById('qr-status-dot')!;
  const textEl   = document.getElementById('qr-status-text')!;
  const elapsedEl = document.getElementById('qr-status-elapsed')!;
  const overlay  = document.getElementById('qr-scan-overlay');

  dot.className = `qr-status-dot ${mode}`;
  textEl.textContent = text;

  if (elapsed !== undefined) {
    elapsedEl.textContent = elapsed;
    elapsedEl.style.display = '';
  } else {
    elapsedEl.style.display = 'none';
  }

  // Toggle scan beam on the QR
  if (overlay) {
    overlay.className = mode === 'searching'
      ? 'qr-scan-overlay'          // beam animates
      : `qr-scan-overlay ${mode}`; // beam hidden
  }
}

// ---- QR rendering ----------------------------------------------------------

async function renderQr() {
  const loading = document.getElementById('qr-loading')!;
  const display = document.getElementById('qr-display')!;
  const img     = document.getElementById('qr-image') as HTMLImageElement;

  loading.style.display = 'flex';
  display.style.display = 'none';
  setQrStatus('idle', 'Generating QR…');

  try {
    const token = await ensureToken();
    currentPairingToken = token;

    const payload = JSON.stringify({ token, device_name: 'Tempo Stats (Browser)', v: 2 });
    img.src = generateQrDataUrl(payload, 180);

    loading.style.display = 'none';
    display.style.display = 'block';

    startQrCountdown();

    // Auto-start discovery — no button needed
    setQrStatus('idle', 'Scan the QR with Tempo on your phone…');
    startAutoDiscovery(token);

  } catch (err) {
    console.warn('[Tempo] QR generation failed:', err);
    loading.innerHTML =
      '<span style="color:var(--danger);font-size:11px;">QR failed. <button class="btn-link" id="qr-retry">Retry</button></span>';
    document.getElementById('qr-retry')?.addEventListener('click', renderQr);
  }
}

function startQrCountdown() {
  if (qrCountdownTimer) clearInterval(qrCountdownTimer);
  const expiryEl = document.getElementById('qr-expiry')!;

  const tick = async () => {
    const { [QR_EXPIRY_KEY]: exp } = await chrome.storage.local.get(QR_EXPIRY_KEY);
    const remaining = Math.max(0, (exp as number ?? 0) - Date.now());
    const m = Math.floor(remaining / 60000);
    const s = Math.floor((remaining % 60000) / 1000);

    if (remaining <= 0) {
      clearInterval(qrCountdownTimer!); qrCountdownTimer = null;
      expiryEl.textContent = 'QR expired — ';
      expiryEl.style.color = 'var(--danger)';
      // Auto-refresh: generate a new QR and restart discovery
      if (!currentPairing) {
        await chrome.storage.local.remove([QR_TOKEN_KEY, QR_EXPIRY_KEY]);
        currentPairingToken = null;
        stopDiscovery();
        discoveryUIState = 'qr';
        await renderQr();
      }
    } else {
      expiryEl.textContent = `Expires ${m}:${String(s).padStart(2, '0')} — `;
      expiryEl.style.color = remaining < 60000 ? 'var(--warning)' : 'var(--text-muted)';
    }
  };

  tick();
  qrCountdownTimer = setInterval(tick, 1000);
}

// "New QR" — reset and restart everything
document.getElementById('btn-refresh-qr')?.addEventListener('click', async () => {
  stopDiscovery();
  discoveryUIState = 'qr';
  hideManualSection();
  await chrome.storage.local.remove([QR_TOKEN_KEY, QR_EXPIRY_KEY]);
  currentPairingToken = null;
  await renderQr();
});

// ---- Discovery state helpers -----------------------------------------------

function setDiscoveryState(state: DiscoveryState, _detail?: string) {
  discoveryUIState = state;
  // In WhatsApp style we only toggle manual section; QR is always visible
  const manSection = document.getElementById('manual-section');
  if (manSection) {
    manSection.style.display = state === 'manual' ? '' : 'none';
  }
}

function hideManualSection() {
  const manSection = document.getElementById('manual-section');
  if (manSection) manSection.style.display = 'none';
}

// ---- Auto-discovery --------------------------------------------------------

/** Try to authenticate-ping a single IP. Returns device name on success. */
async function tryPing(ip: string, token: string): Promise<string | null> {
  try {
    const result = await send(MessageType.PingPhone, { ip, port: SERVER_PORT, token });

    // Service worker signals missing permission — request from here (popup = valid user gesture context)
    if (result?.needsPermission && result?.origin) {
      try {
        const granted = await chrome.permissions.request({ origins: [result.origin] });
        if (!granted) return null;
        const retry = await send(MessageType.PingPhone, { ip, port: SERVER_PORT, token });
        return retry?.ok ? (retry.device ?? ip) : null;
      } catch { return null; }
    }

    return result?.ok ? (result.device ?? ip) : null;
  } catch { return null; }
}

function stopDiscovery() {
  discoveryAborted = true;
  // discoveryTimer is now a setTimeout handle (not setInterval) — use clearTimeout
  if (discoveryTimer) { clearTimeout(discoveryTimer as unknown as ReturnType<typeof setTimeout>); discoveryTimer = null; }
  if ((window as any)._tempoStopElapsed) (window as any)._tempoStopElapsed();
}

// ---- mDNS fast-path probe --------------------------------------------------
//
// mDNS (.local) resolution requires an OS multicast DNS lookup which takes
// 500-2000ms — far longer than the 800ms abort used for subnet scanning.
// Bundling mDNS into the batch therefore always times it out silently.
// This dedicated probe uses a 3-second timeout and is tried FIRST at the
// start of every discovery pass, completely independent of the IP batch.
//
async function mdnsPing(token: string): Promise<string | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 3000); // 3 s — mDNS needs time
  try {
    const headers = await signRequest(token);
    const res = await fetch(`http://${MDNS_HOSTNAME}:${SERVER_PORT}/api/ping`, {
      signal: controller.signal,
      headers,
    });
    clearTimeout(timer);
    if (res.ok) {
      const data = await res.json().catch(() => ({}));
      console.log('[Tempo] mDNS probe succeeded:', data);
      return validatePingResponse(data) ?? (data.device_name ?? MDNS_HOSTNAME);
    }
    console.debug(`[Tempo] mDNS probe → HTTP ${res.status}`);
    return null;
  } catch {
    clearTimeout(timer);
    return null;
  }
}

async function startAutoDiscovery(token: string) {
  stopDiscovery();
  discoveryAborted = false;

  // No runtime permission request needed — http://*:8765/* is declared as a
  // required host_permission in manifest.json and is granted at install time.
  // This means all fetches to port 8765 bypass CORS unconditionally in every
  // extension context (popup, service worker, background).

  // Step 1: Get the machine's real LAN IP via WebRTC ICE candidates.
  //   This is the ONLY reliable way to find the local IP in a Chrome extension.
  //   No permissions needed. Returns in ~200ms.
  setQrStatus('idle', 'Detecting local network…');
  const localIPs = await getLocalIPsViaWebRTC();
  console.log('[Tempo] Local IPs via WebRTC:', localIPs);

  // Step 2: Build IP candidate list (mDNS handled separately — see mdnsPing())
  //   • Hotspot seeds — fast path when phone IS the WiFi gateway
  //   • All /24 hosts on each detected local subnet (covers regular WiFi)
  //
  // NOTE: MDNS_HOSTNAME is intentionally excluded from this set.
  //   It is probed first in each tryAll() pass via mdnsPing() with a 3s timeout
  //   so the OS has time to do the multicast DNS lookup.
  const candidateSet = new Set<string>();

  for (const seed of HOTSPOT_SEEDS) candidateSet.add(seed);

  if (localIPs.length > 0) {
    for (const localIP of localIPs) {
      const parts = localIP.split('.');
      const subnet = `${parts[0]}.${parts[1]}.${parts[2]}`;
      for (let i = 1; i <= 254; i++) {
        const ip = `${subnet}.${i}`;
        if (ip !== localIP) candidateSet.add(ip);
      }
    }
  } else {
    // WebRTC gave nothing (rare) — fall back to most common home subnets
    console.warn('[Tempo] WebRTC gave no local IPs, using fallback subnets');
    for (const sub of ['192.168.1', '192.168.0', '192.168.43']) {
      for (let i = 1; i <= 254; i++) candidateSet.add(`${sub}.${i}`);
    }
  }

  const allCandidates = Array.from(candidateSet);
  console.log(`[Tempo] Discovery: ${allCandidates.length} IP candidates across ${localIPs.length} subnet(s) + mDNS probe`);

  let secondsEl  = 0;
  let manualShown = false;
  let lastGuidance = '';

  setQrStatus('searching', 'Scanning network for your phone…', '0s');

  const elapsedTimer = setInterval(() => {
    secondsEl++;
    const el = document.getElementById('qr-status-elapsed');
    if (el) el.textContent = `${secondsEl}s`;

    let guidance = '';
    if (secondsEl >= 60) {
      guidance = 'Still searching. Hotspot is the most reliable workaround on restricted WiFi.';
    } else if (secondsEl >= MANUAL_FALLBACK_AFTER_SECONDS) {
      guidance = 'Still searching. Enter the phone IP below, or keep waiting.';
    } else if (secondsEl >= 15) {
      guidance = 'Still searching. Check same WiFi or use phone hotspot.';
    }

    if (guidance && guidance !== lastGuidance && !discoveryAborted) {
      lastGuidance = guidance;
      setQrStatus(secondsEl >= MANUAL_FALLBACK_AFTER_SECONDS ? 'error' : 'searching', guidance, `${secondsEl}s`);
    }

    if (secondsEl >= MANUAL_FALLBACK_AFTER_SECONDS && !manualShown && !discoveryAborted) {
      manualShown = true;
      setDiscoveryState('manual');
      setQrStatus('error',
        'Still searching. Enter IP from Tempo > Desktop Link, or keep waiting.',
        `${secondsEl}s`
      );
    }
  }, 1000);

  // Step 3: Scan candidates in parallel batches.
  //   800ms timeout per IP — unassigned hosts fail in <10ms (TCP RST / ARP miss),
  //   so each batch of 64 typically completes in well under 1 second.
  //   mDNS is tried on the first pass, then throttled so a slow .local lookup
  //   cannot block subnet scanning on networks where it will never resolve.
  const BATCH = 64;
  let pass = 0;
  let lastMdnsProbeAt = -30;

  const tryAll = async () => {
    if (discoveryAborted) return;
    pass++;

    if (!manualShown) {
      setQrStatus('searching',
        `Searching… pass ${pass}`,
        `${secondsEl}s`
      );
    }

    // ── Fast path: mDNS (dedicated 3s timeout — keeps it out of the 800ms batch) ──
    if (pass === 1 || secondsEl - lastMdnsProbeAt >= 30) {
      lastMdnsProbeAt = secondsEl;
      const mdnsDev = await mdnsPing(token);
      if (discoveryAborted) return;
      if (mdnsDev !== null) {
        clearInterval(elapsedTimer);
        stopDiscovery();
        setQrStatus('found', `Found ${mdnsDev} via mDNS — connecting…`);
        await completePairing(MDNS_HOSTNAME, SERVER_PORT, token, mdnsDev);
        return;
      }
    }

    // ── Hotspot seeds + subnet sweep ────────────────────────────────────────────
    for (let start = 0; start < allCandidates.length; start += BATCH) {
      if (discoveryAborted) return;
      const batch = allCandidates.slice(start, start + BATCH);

      const results = await Promise.all(
        batch.map(ip => discoveryPing(ip, token).then(dev => ({ ip, dev })))
      );

      if (discoveryAborted) return;

      const found = results.find(r => r.dev !== null);
      if (found) {
        clearInterval(elapsedTimer);
        stopDiscovery();
        setQrStatus('found', `Found ${found.dev ?? found.ip} — connecting…`);
        await completePairing(found.ip, SERVER_PORT, token, found.dev ?? 'Phone');
        return;
      }
    }

    // ── Schedule next pass: 3s for first 30s (tight window after phone scans QR),
    //    then 10s once the manual fallback is shown. ────────────────────────────
    if (!discoveryAborted) {
      const delay = secondsEl < MANUAL_FALLBACK_AFTER_SECONDS ? 3000 : 10000;
      discoveryTimer = setTimeout(tryAll, delay) as unknown as ReturnType<typeof setInterval>;
    }
  };

  // Delay the very first scan by 500ms so the QR image has finished rendering
  // and the user has a moment to see it before pings start.
  discoveryTimer = setTimeout(async () => {
    await tryAll();
  }, 500) as unknown as ReturnType<typeof setInterval>;

  (window as any)._tempoStopElapsed = () => clearInterval(elapsedTimer);
}

async function completePairing(ip: string, port: number, token: string, deviceName: string) {
  const pairing: PairingInfo = {
    phoneIp: ip,
    phonePort: port,
    authToken: token,
    deviceName,
    pairedAt: new Date().toISOString(),
  };

  try {
    await send(MessageType.SetPairing, { pairing });
    await chrome.storage.local.remove([QR_TOKEN_KEY, QR_EXPIRY_KEY]);
    currentPairingToken = null;
    discoveryUIState = 'success';
    await refreshPairing();
  } catch (err) {
    console.warn('[Tempo] completePairing failed:', err);
    setQrStatus('error', 'Connection failed — try entering IP manually');
    setDiscoveryState('manual');
  }
}

// Manual fallback form
document.getElementById('manual-form')?.addEventListener('submit', async (e) => {
  e.preventDefault();
  const ip    = (document.getElementById('manual-ip') as HTMLInputElement).value.trim();
  const token = currentPairingToken;
  const errEl = document.getElementById('manual-error')!;
  const btn   = document.getElementById('btn-manual-connect') as HTMLButtonElement;

  if (!ip || !token) {
    errEl.textContent = 'Please enter the phone\'s IP address.';
    return;
  }

  btn.disabled = true;
  btn.textContent = 'Connecting…';
  errEl.textContent = '';

  const dev = await tryPing(ip, token);
  if (dev !== null) {
    await completePairing(ip, SERVER_PORT, token!, dev);
  } else {
    errEl.textContent = 'Could not reach your phone. Make sure both devices are on the same WiFi and Tempo is open.';
    btn.disabled = false;
    btn.textContent = 'Connect';
  }
});

// "Back to QR" in manual section
document.getElementById('btn-back-to-qr')?.addEventListener('click', async () => {
  stopDiscovery();
  discoveryUIState = 'qr';
  hideManualSection();
  await chrome.storage.local.remove([QR_TOKEN_KEY, QR_EXPIRY_KEY]);
  currentPairingToken = null;
  await renderQr();
});

// ---- Pairing refresh -------------------------------------------------------

async function refreshPairing() {
  try {
    const resp = await send(MessageType.GetPairing);
    const pairing: PairingInfo | null = resp?.pairing ?? null;
    currentPairing = pairing;

    const connectedEl = document.getElementById('pairing-connected')!;
    const setupEl     = document.getElementById('pairing-setup')!;

    if (pairing) {
      stopDiscovery();
      if (qrCountdownTimer) { clearInterval(qrCountdownTimer); qrCountdownTimer = null; }
      connectedEl.style.display = '';
      setupEl.style.display     = 'none';
      document.getElementById('paired-device-name')!.textContent = pairing.deviceName || 'Phone';
      document.getElementById('paired-ip')!.textContent          = pairing.phoneIp;
      document.getElementById('paired-port')!.textContent        = String(pairing.phonePort);
      document.getElementById('paired-at')!.textContent          =
        pairing.pairedAt ? timeAgo(pairing.pairedAt) : 'Unknown';

      // Token age warning: after 30 days, suggest re-verifying the connection
      const ageDays = pairingAgeDays(pairing.pairedAt);
      const statusEl = document.getElementById('paired-status')!;
      if (ageDays !== null && ageDays >= 30) {
        statusEl.textContent  = `⚠ Paired ${ageDays}d ago — verify connection`;
        statusEl.style.color  = 'var(--warning)';
        statusEl.title        = 'This pairing is over 30 days old. Click Test Connection to verify it still works.';
      } else {
        statusEl.textContent = 'Ready';
        statusEl.style.color = 'var(--success)';
        statusEl.title       = '';
      }
    } else {
      connectedEl.style.display = 'none';
      setupEl.style.display     = '';

      // Only reset to QR screen if we're not mid-flow (scanning or manual).
      // Tab switches and home-screen "Pair" clicks must not interrupt discovery.
      if (discoveryUIState === 'qr' || discoveryUIState === 'success') {
        discoveryUIState = 'qr';
        await renderQr();
      } else {
        // Re-apply current sub-state (scanning → QR still visible, manual → manual shown)
        setDiscoveryState(discoveryUIState);
      }
    }
    refreshHomeState();
  } catch (err) {
    console.warn('[Tempo] refreshPairing failed:', err);
  }
}

// Test connection (already paired)
document.getElementById('btn-test-connected')?.addEventListener('click', async () => {
  if (!currentPairing) return;
  const btn = document.getElementById('btn-test-connected') as HTMLButtonElement;
  const resultEl = document.getElementById('test-result')!;
  btn.disabled = true;
  btn.textContent = 'Testing…';
  resultEl.style.display = 'none';

  let dev = await tryPing(currentPairing.phoneIp, currentPairing.authToken);
  const statusEl = document.getElementById('paired-status')!;

  // If stored address fails, silently try mDNS — the phone may have a new IP
  if (dev === null && currentPairing.phoneIp !== MDNS_HOSTNAME) {
    dev = await tryPing(MDNS_HOSTNAME, currentPairing.authToken);
    if (dev !== null) {
      // mDNS worked — update stored address so future syncs use it directly
      const updated = { ...currentPairing, phoneIp: MDNS_HOSTNAME };
      await send(MessageType.SetPairing, { pairing: updated });
      currentPairing = updated;
      document.getElementById('paired-ip')!.textContent = MDNS_HOSTNAME;
      console.log('[Tempo] Test Connection: phone found via mDNS, updated stored address');
    }
  }

  resultEl.style.display = '';
  if (dev !== null) {
    resultEl.className  = 'test-result success';
    resultEl.textContent = `✓ Phone reachable${dev && dev !== currentPairing.phoneIp ? ` — ${dev}` : ''}`;
    statusEl.textContent = '✓ Reachable';
    statusEl.style.color = 'var(--success)';
  } else {
    resultEl.className   = 'test-result error';
    resultEl.textContent = '✗ Cannot reach phone — is Tempo open and on the same WiFi?';
    statusEl.textContent = '✗ Unreachable';
    statusEl.style.color = 'var(--danger)';
  }

  btn.disabled = false;
  btn.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor">
    <path d="M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z"/>
  </svg> Test Connection`;
});

// ---- Rebuild Connection -------------------------------------------------------
//
// Runs a fresh, step-by-step discovery flow without requiring unpair:
//   Step 1 — mDNS  (tempo-phone.local)   ~1-2 s  — heals IP changes instantly
//   Step 2 — Hotspot seeds               ~2 s    — phone-as-gateway scenario
//   Step 3 — Subnet scan via WebRTC      up to ~15 s — full /24 sweep
//
// On success, the stored phoneIp is updated so future auto-syncs use the new address.
// ------------------------------------------------------------------------------

/** Helper: render a step row into the rebuild-steps container. */
function addRebuildStep(icon: string, text: string, state: 'active' | 'success' | 'failed' | 'pending'): HTMLElement {
  const stepsEl = document.getElementById('rebuild-steps')!;
  const div = document.createElement('div');
  div.className = `rebuild-step ${state}`;
  div.innerHTML = `<span class="rebuild-step-icon">${icon}</span><span>${text}</span>`;
  stepsEl.appendChild(div);
  return div;
}

function setRebuildStep(el: HTMLElement, icon: string, text: string, state: 'active' | 'success' | 'failed' | 'pending') {
  el.className = `rebuild-step ${state}`;
  el.innerHTML = `<span class="rebuild-step-icon">${icon}</span><span>${text}</span>`;
}

document.getElementById('btn-rebuild-connection')?.addEventListener('click', async () => {
  if (!currentPairing) return;

  const rebuildBtn  = document.getElementById('btn-rebuild-connection') as HTMLButtonElement;
  const testBtn     = document.getElementById('btn-test-connected') as HTMLButtonElement;
  const panel       = document.getElementById('rebuild-panel')!;
  const spinner     = document.getElementById('rebuild-spinner')!;
  const statusText  = document.getElementById('rebuild-status-text')!;
  const stepsEl     = document.getElementById('rebuild-steps')!;
  const testResultEl = document.getElementById('test-result')!;

  // Reset & show panel
  rebuildBtn.disabled = true;
  testBtn.disabled    = true;
  testResultEl.style.display = 'none';
  stepsEl.innerHTML   = '';
  spinner.className   = 'rebuild-spinner';
  statusText.textContent = 'Searching for your phone…';
  panel.style.display = '';

  const token = currentPairing.authToken;
  const port  = currentPairing.phonePort;

  // ── Step 1: mDNS ──────────────────────────────────────────────────────────
  const mdnsStep = addRebuildStep('📡', 'Trying mDNS (tempo-phone.local)…', 'active');
  let foundIp: string | null = null;

  const mdnsDev = await tryPing(MDNS_HOSTNAME, token);
  if (mdnsDev !== null) {
    foundIp = MDNS_HOSTNAME;
    setRebuildStep(mdnsStep, '✓', `Found via mDNS — tempo-phone.local`, 'success');
  } else {
    setRebuildStep(mdnsStep, '✗', 'mDNS: not found', 'failed');
  }

  // ── Step 2: Hotspot seeds ─────────────────────────────────────────────────
  if (!foundIp) {
    const hotspotIPs = ['192.168.43.1', '172.20.10.1', '192.168.49.1'];
    const hotspotStep = addRebuildStep('📶', 'Trying hotspot gateway IPs…', 'active');
    for (const ip of hotspotIPs) {
      const dev = await tryPing(ip, token);
      if (dev !== null) {
        foundIp = ip;
        setRebuildStep(hotspotStep, '✓', `Found at hotspot gateway ${ip}`, 'success');
        break;
      }
    }
    if (!foundIp) {
      setRebuildStep(hotspotStep, '✗', 'Hotspot seeds: not found', 'failed');
    }
  }

  // ── Step 3: WebRTC subnet scan ────────────────────────────────────────────
  if (!foundIp) {
    const webrtcStep = addRebuildStep('🔍', 'Detecting local subnet via WebRTC…', 'active');
    const localIPs   = await getLocalIPsViaWebRTC();

    if (localIPs.length === 0) {
      setRebuildStep(webrtcStep, '⚠', 'WebRTC returned no local IPs — scan skipped', 'failed');
    } else {
      const subnets = [...new Set(localIPs.map(ip => ip.split('.').slice(0, 3).join('.')))];
      const subnetStr = subnets.join(', ');
      setRebuildStep(webrtcStep, '🔍', `Scanning ${subnets.length * 254} addresses (${subnetStr}.x)…`, 'active');

      const scanStep = webrtcStep; // reuse same row to show progress
      const candidates: string[] = [];
      for (const sub of subnets) {
        for (let i = 1; i <= 254; i++) candidates.push(`${sub}.${i}`);
      }

      const BATCH = 64;
      let scanned = 0;
      outer: for (let start = 0; start < candidates.length; start += BATCH) {
        const batch = candidates.slice(start, start + BATCH);
        const results = await Promise.all(
          batch.map(ip => discoveryPing(ip, token).then(dev => ({ ip, dev })))
        );
        scanned += batch.length;

        const found = results.find(r => r.dev !== null);
        if (found) {
          foundIp = found.ip;
          setRebuildStep(scanStep, '✓', `Found at ${found.ip} (scanned ${scanned}/${candidates.length})`, 'success');
          break outer;
        }

        // Update progress on the step
        setRebuildStep(scanStep, '🔍', `Scanning… ${scanned}/${candidates.length} checked`, 'active');
      }

      if (!foundIp) {
        setRebuildStep(scanStep, '✗', `Full scan complete — phone not found`, 'failed');
      }
    }
  }

  // ── Result ────────────────────────────────────────────────────────────────
  if (foundIp) {
    spinner.className     = 'rebuild-spinner done';
    statusText.textContent = `✓ Reconnected — address updated`;
    statusText.style.color = 'var(--success)';

    // Persist the new address
    const updated = { ...currentPairing, phoneIp: foundIp };
    await send(MessageType.SetPairing, { pairing: updated });
    currentPairing = updated;

    // Update the info grid live
    document.getElementById('paired-ip')!.textContent = foundIp;
    const statusEl = document.getElementById('paired-status')!;
    statusEl.textContent = '✓ Reconnected';
    statusEl.style.color = 'var(--success)';

    console.log(`[Tempo] Rebuild Connection: phone found at ${foundIp}, pairing updated`);

    // Auto-hide panel after 4 s
    setTimeout(() => {
      panel.style.display = 'none';
      statusText.style.color = '';
    }, 4000);

  } else {
    spinner.className      = 'rebuild-spinner failed';
    statusText.textContent = '✗ Phone not found on this network';
    statusText.style.color = 'var(--danger)';
    setTimeout(() => { statusText.style.color = ''; }, 4000);
  }

  // Re-enable buttons
  rebuildBtn.disabled = false;
  testBtn.disabled    = false;
  rebuildBtn.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor">
    <path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/>
  </svg> Rebuild Connection`;
});

// Unpair
document.getElementById('btn-unpair')?.addEventListener('click', async () => {
  if (!confirm('Unpair from this device?')) return;
  await send(MessageType.RemovePairing);
  await chrome.storage.local.remove([QR_TOKEN_KEY, QR_EXPIRY_KEY]);
  currentPairingToken = null;
  refreshPairing();
});

// ---- Nav helpers -----------------------------------------------------------

document.getElementById('home-primary-action')?.addEventListener('click', () => {
  if (!currentPairing) { switchTab('pairing'); return; }
  switchTab(currentQueueCount > 0 || currentNowPlaying?.title ? 'queue' : 'pairing');
});
document.getElementById('home-secondary-action')?.addEventListener('click', () => {
  if (!currentPairing) { switchTab('queue'); return; }
  switchTab(currentQueueCount > 0 || currentNowPlaying?.title ? 'pairing' : 'queue');
});
document.getElementById('queue-open-pairing')?.addEventListener('click', () => switchTab('pairing'));

// ---- Known Artists ---------------------------------------------------------

async function refreshArtists() {
  try {
    const resp     = await send(MessageType.GetSettings);
    const settings: Settings = resp?.settings ?? { knownArtists: [], youtubeChannels: [] };
    renderTagList('artist-list', settings.knownArtists, async removed => {
      settings.knownArtists = settings.knownArtists.filter(a => a !== removed);
      await send(MessageType.SetSettings, { settings });
      refreshArtists();
    });
    renderTagList('channel-list', settings.youtubeChannels, async removed => {
      settings.youtubeChannels = settings.youtubeChannels.filter(c => c !== removed);
      await send(MessageType.SetSettings, { settings });
      refreshArtists();
    });
  } catch (err) { console.warn('[Tempo] refreshArtists failed:', err); }
}

function renderTagList(cid: string, items: string[], onRemove: (item: string) => void) {
  const c = document.getElementById(cid)!;
  if (items.length === 0) {
    c.innerHTML = '<span class="text-muted">None added yet</span>';
    return;
  }
  c.innerHTML = items.map(item =>
    `<span class="tag">${escapeHtml(item)}<span class="tag-remove" data-item="${escapeHtml(item)}">×</span></span>`
  ).join('');
  c.querySelectorAll('.tag-remove').forEach(el =>
    el.addEventListener('click', () => onRemove((el as HTMLElement).dataset.item!))
  );
}

['btn-add-artist', 'btn-add-channel'].forEach(btnId => {
  document.getElementById(btnId)?.addEventListener('click', async () => {
    const isArtist = btnId === 'btn-add-artist';
    const input    = document.getElementById(isArtist ? 'new-artist' : 'new-channel') as HTMLInputElement;
    const name     = input.value.trim().slice(0, MAX_INPUT_LEN);
    if (!name) return;
    const resp     = await send(MessageType.GetSettings);
    const settings: Settings = resp?.settings ?? { knownArtists: [], youtubeChannels: [] };
    const list     = isArtist ? settings.knownArtists : settings.youtubeChannels;
    if (!list.includes(name)) {
      list.push(name);
      await send(MessageType.SetSettings, { settings });
    }
    input.value = '';
    refreshArtists();
  });
});

['new-artist', 'new-channel'].forEach(id => {
  document.getElementById(id)?.addEventListener('keydown', e => {
    if ((e as KeyboardEvent).key === 'Enter') {
      e.preventDefault();
      document.getElementById(id === 'new-artist' ? 'btn-add-artist' : 'btn-add-channel')?.click();
    }
  });
});

// ---- Settings --------------------------------------------------------------

const DEFAULT_SETTINGS: Settings = {
  trackingEnabled: true,
  offlineMode: false,
  syncIntervalMinutes: 30,
  pollingIntervalSeconds: 2,
  knownArtists: [],
  youtubeChannels: [],
};

async function refreshSettings() {
  try {
    const resp     = await send(MessageType.GetSettings);
    const settings: Settings = resp?.settings ?? { ...DEFAULT_SETTINGS };
    (document.getElementById('setting-tracking') as HTMLInputElement).checked    = settings.trackingEnabled ?? true;
    (document.getElementById('setting-offline') as HTMLInputElement).checked     = settings.offlineMode ?? false;
    (document.getElementById('setting-sync-interval') as HTMLSelectElement).value = String(settings.syncIntervalMinutes ?? 30);
    (document.getElementById('setting-polling-interval') as HTMLSelectElement).value = String(settings.pollingIntervalSeconds ?? 2);

    const statsR = await send(MessageType.GetStats);
    const stats  = statsR?.stats ?? {};
    document.getElementById('stat-total')!.textContent  = String(stats.totalPlays ?? 0);
    document.getElementById('stat-queued')!.textContent = String(stats.queuedCount ?? 0);
    document.getElementById('stat-synced')!.textContent = String(stats.syncedCount ?? 0);

    const parts: string[] = [];
    if (stats.topArtist) parts.push(`Top artist: ${stats.topArtist}`);
    if (stats.topTrack)  parts.push(`Top track: ${stats.topTrack}`);
    document.getElementById('stats-detail')!.textContent = parts.join(' · ') || '';
  } catch (err) { console.warn('[Tempo] refreshSettings failed:', err); }
}

async function saveField(field: string, value: any) {
  const resp     = await send(MessageType.GetSettings);
  const settings: Settings = resp?.settings ?? { ...DEFAULT_SETTINGS };
  (settings as any)[field] = value;
  await send(MessageType.SetSettings, { settings });
}

document.getElementById('setting-tracking')?.addEventListener('change', e =>
  saveField('trackingEnabled', (e.target as HTMLInputElement).checked));
document.getElementById('setting-offline')?.addEventListener('change', e =>
  saveField('offlineMode', (e.target as HTMLInputElement).checked));
document.getElementById('setting-sync-interval')?.addEventListener('change', e =>
  saveField('syncIntervalMinutes', parseInt((e.target as HTMLSelectElement).value)));
document.getElementById('setting-polling-interval')?.addEventListener('change', e =>
  saveField('pollingIntervalSeconds', parseInt((e.target as HTMLSelectElement).value)));

document.getElementById('btn-export')?.addEventListener('click', async () => {
  try {
    const r = await send(MessageType.ExportPlays);
    const plays = r?.plays ?? [];
    if (!plays.length) { alert('No plays to export.'); return; }
    const url = URL.createObjectURL(new Blob([JSON.stringify(plays, null, 2)], { type: 'application/json' }));
    Object.assign(document.createElement('a'), {
      href: url,
      download: `tempo-plays-${new Date().toISOString().slice(0, 10)}.json`,
    }).click();
    URL.revokeObjectURL(url);
  } catch { alert('Export failed.'); }
});

// ---- Init ------------------------------------------------------------------

startNpPolling();
refreshPairing();
refreshQueue();

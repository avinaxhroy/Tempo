/**
 * Popup UI logic for Tempo Stats extension.
 * Manages QR-based pairing, network auto-discovery, live playback state,
 * play queue inspection, and artist filtering.
 */


import { MessageType } from '../shared/types';
import type { NowPlaying, Play, Settings, PairingInfo, YoutubeChannelSuggestion, ConnectionHealth } from '../shared/types';
import type { SocketState } from '../shared/types';
import { signRequest, validatePingResponse, pairingAgeDays } from '../shared/security';
import {
  SERVER_PORT, HOTSPOT_SEEDS, MDNS_HOSTNAME,
  getLocalIPsViaWebRTC, discoveryPing,
} from './lan-discovery';
import { renderHomeState } from './home-state';
import { renderNowPlayingView } from './now-playing-view';
import type { SyncStatus } from '../background/sync';


const QR_TOKEN_KEY    = 'tempo_pairing_token';
const QR_EXPIRY_KEY   = 'tempo_pairing_token_expiry';
const QR_TOKEN_TTL_MS = 10 * 60 * 1000;   // 10 minutes
const MANUAL_FALLBACK_AFTER_SECONDS = 30;

type DiscoveryState = 'qr' | 'scanning' | 'manual' | 'success';

let currentNowPlaying: NowPlaying | null = null;
let currentYoutubeSuggestion: YoutubeChannelSuggestion | null = null;
let currentPairing: PairingInfo | null = null;
let currentQueueCount = 0;
// A Google Drive connection is a complete history-sync destination by itself.
// Do not describe a missing LAN pairing as a setup failure when Drive works.
let cloudHistoryStatus: {
  configured: boolean;
  enabled: boolean;
  connected: boolean;
  needsInteractiveAuth: boolean;
  pendingUploads: number;
} | null = null;
document.addEventListener('tempo-drive-status', ((event: CustomEvent) => {
  cloudHistoryStatus = event.detail;
  refreshHomeState();
}) as EventListener);
let currentPairingToken: string | null = null;
let qrExpiryCache = 0;       // mirror of the stored QR expiry so the countdown tick needs no storage reads
const dismissedPopupYoutubeChannels = new Set<string>();

// Tracks the current discovery UI screen so tab switches don't reset it.
// Only reset to 'qr' when explicitly starting fresh (unpair / refresh QR).
let discoveryUIState: DiscoveryState = 'qr';

let npPollTimer: ReturnType<typeof setInterval> | null = null;
let qrCountdownTimer: ReturnType<typeof setInterval> | null = null;
let discoveryTimer: ReturnType<typeof setInterval> | null = null;
let discoveryAborted = false;

// Tab Switching

const tabBtns   = document.querySelectorAll<HTMLButtonElement>('.tab-btn');
const tabPanels = document.querySelectorAll<HTMLElement>('.tab-panel');

function isPairingTabActive(): boolean {
  return document.querySelector('.tab-btn[data-tab="pairing"]')?.classList.contains('active') ?? false;
}

function switchTab(tabId: string) {
  tabBtns.forEach(b => {
    b.classList.remove('active');
    b.setAttribute('aria-selected', 'false');
  });
  tabPanels.forEach(p => p.classList.remove('active'));
  const btn = document.querySelector<HTMLButtonElement>(`.tab-btn[data-tab="${tabId}"]`);
  btn?.classList.add('active');
  btn?.setAttribute('aria-selected', 'true');
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

// Arrow-key navigation across the tablist (WAI-ARIA tabs pattern).
document.querySelector('.tab-nav')?.addEventListener('keydown', (e) => {
  const key = (e as KeyboardEvent).key;
  if (key !== 'ArrowLeft' && key !== 'ArrowRight') return;
  e.preventDefault();
  const btns = Array.from(tabBtns);
  const idx  = btns.findIndex(b => b.classList.contains('active'));
  const next = key === 'ArrowRight'
    ? btns[(idx + 1) % btns.length]
    : btns[(idx - 1 + btns.length) % btns.length];
  switchTab(next.dataset.tab!);
  next.focus();
});

// Utility

function send(type: string, data?: any): Promise<any> {
  return chrome.runtime.sendMessage({ type, ...data });
}

// Toast feedback

let toastTimer: number | undefined;

function toast(message: string, kind: 'success' | 'error' | 'info' = 'info'): void {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = message;
  el.classList.remove('toast-success', 'toast-error');
  if (kind === 'success') el.classList.add('toast-success');
  if (kind === 'error')   el.classList.add('toast-error');
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2600);
}

// Two-step inline confirm: first click arms the button, second click fires.
// Replaces native confirm() which is unavailable in MV3 popups.
function armToConfirm(btn: HTMLButtonElement, armedLabel: string, onConfirm: () => void): void {
  if (btn.classList.contains('armed')) {
    btn.classList.remove('armed');
    onConfirm();
    return;
  }
  const original = btn.innerHTML;
  btn.classList.add('armed');
  btn.innerHTML = armedLabel;
  setTimeout(() => {
    if (btn.classList.contains('armed')) {
      btn.classList.remove('armed');
      btn.innerHTML = original;
    }
  }, 3000);
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
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const VALID_STATUSES = new Set(['queued', 'synced', 'failed']);
function sanitizeStatus(s: string): string {
  return VALID_STATUSES.has(s) && /^[a-z]+$/.test(s) ? s : 'unknown';
}

// Home State

function refreshHomeState() {
  renderHomeState({
    currentPairing, currentNowPlaying, currentYoutubeSuggestion,
    currentQueueCount, cloudHistoryStatus,
  });
}

// Now Playing

async function refreshNowPlaying() {
  try {
    // GetNowPlaying now carries `suggestion` too — one message instead of two.
    const resp = await send(MessageType.GetNowPlaying);
    const np: NowPlaying | null = resp?.nowPlaying ?? null;
    const suggestion: YoutubeChannelSuggestion | null = resp?.suggestion ?? null;
    currentNowPlaying = np;
    currentYoutubeSuggestion = suggestion &&
      !dismissedPopupYoutubeChannels.has(suggestion.channel.toLowerCase().trim())
        ? suggestion
        : null;
    renderNowPlaying(np, currentYoutubeSuggestion);
  } catch (err) {
    console.warn('[Tempo] refreshNowPlaying failed:', err);
  }
}

/**
 * Apply now-playing + suggestion state to the DOM. Identity-gated: the heavy
 * card writes (class, title/artist/album/source, playing-state classes) run
 * only when `${title}|${artist}|${album}|${isPlaying}|${site}` changes; cheap
 * scalars that move continuously (progress, counts, mute badge) update every
 * tick regardless.
 */
function renderNowPlaying(np: NowPlaying | null, suggestion: YoutubeChannelSuggestion | null) {
  renderNowPlayingView(np, suggestion, currentPairing, refreshHomeState, formatDuration);
}

function startNpPolling() {
  if (npPollTimer) return;
  // First paint comes from the bootstrap payload (see init()); the poll then
  // keeps state fresh every 2 s as before.
  npPollTimer = setInterval(refreshNowPlaying, 2000);
}

// Queue

const MAX_INPUT_LEN = 100;

// Human-readable labels for classified sync errors (mirrors SyncErrorKind).
const SYNC_ERROR_LABELS: Record<string, string> = {
  rate_limited: 'Phone asked us to slow down',
  auth: 'Phone rejected the connection (auth)',
  rejected: 'Phone rejected the data',
  server: 'Phone server error',
  network: 'Network error',
  unreachable: 'Phone unreachable',
  battery: 'Phone battery critically low',
  unknown: 'Sync failed',
};

function formatCountdown(ms: number): string {
  const s = Math.max(1, Math.ceil(ms / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  return `${m}m ${s % 60}s`;
}

/**
 * Render the sync status line with classified error detail, rate-limit
 * countdown, and next-retry time — instead of a raw error string.
 */
function renderSyncStatus(el: HTMLElement, status: SyncStatus | null | undefined): void {
  const now = Date.now();

  if (status?.isSyncing) {
    el.textContent = 'Syncing…';
    el.className = 'sync-status syncing';
    return;
  }

  const rateLimitedUntil = typeof status?.rateLimitedUntil === 'number' ? status.rateLimitedUntil : null;
  if (rateLimitedUntil !== null && rateLimitedUntil > now) {
    el.textContent = `Rate limited — retrying in ${formatCountdown(rateLimitedUntil - now)} (plays stay queued)`;
    el.className = 'sync-status warning';
    return;
  }

  const kind: string | null = status?.lastErrorKind ?? null;
  if (kind && status?.lastSyncResult?.startsWith('Failed')) {
    const label = SYNC_ERROR_LABELS[kind] ?? 'Sync failed';
    const http = typeof status.lastErrorStatus === 'number' ? ` (HTTP ${status.lastErrorStatus})` : '';
    const retry = typeof status.nextRetryAt === 'number' && status.nextRetryAt > now
      ? ` — retry in ${formatCountdown(status.nextRetryAt - now)}`
      : '';
    const tone = kind === 'rate_limited' || kind === 'battery' ? 'warning' : 'error';
    el.textContent = `${label}${http}${retry}`;
    el.className = `sync-status ${tone}`;
    return;
  }

  el.textContent = status?.lastSyncTime
    ? `Last sync: ${timeAgo(status.lastSyncTime)} — ${status.lastSyncResult ?? ''}`
    : (currentPairing ? 'Not synced yet' : 'Pair your phone to start syncing');
  el.className = 'sync-status';
}

function applyQueueCount(count: number) {
  currentQueueCount = count;
  document.getElementById('queue-count')!.textContent = String(currentQueueCount);
}

function renderQueueItems(plays: Play[]) {
  const listEl = document.getElementById('queue-list')!;

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
}

async function refreshQueue() {
  try {
    const [countR, itemsR, statusR] = await Promise.all([
      send(MessageType.GetQueueCount),
      send(MessageType.GetQueueItems),
      send(MessageType.GetSyncStatus),
    ]);

    applyQueueCount(countR?.count ?? 0);

    const statusEl = document.getElementById('sync-status')!;
    renderSyncStatus(statusEl, statusR);

    renderQueueItems(itemsR?.plays ?? []);

    refreshHomeState();
  } catch (err) {
    console.warn('[Tempo] refreshQueue failed:', err);
  }
}

// Event delegation — ONE listener on the list handles every delete button,
// no matter how many times the list re-renders.
let queueListDelegationBound = false;
const queueListEl = document.getElementById('queue-list');
if (queueListEl && !queueListDelegationBound) {
  queueListDelegationBound = true;
  queueListEl.addEventListener('click', async (e) => {
    const btn = (e.target as HTMLElement).closest('.queue-item-delete') as HTMLButtonElement | null;
    if (!btn) return;
    e.stopPropagation();
    const id = parseInt(btn.dataset.id ?? '', 10);
    if (!id) return;
    armToConfirm(btn, 'Delete local copy?', async () => {
      btn.disabled = true;
      try {
        await send(MessageType.DeletePlay, { id });
        toast('Local play deleted', 'info');
      } catch (error) {
        toast(`Could not delete local play: ${String(error)}`, 'error');
      } finally {
        void refreshQueue();
      }
    });
  });
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

document.getElementById('btn-sync-drive')?.addEventListener('click', () => {
  const ready = !!cloudHistoryStatus?.enabled && !!cloudHistoryStatus.connected
    && !cloudHistoryStatus.needsInteractiveAuth;
  if (!ready) {
    toast('Reconnect Google Drive in Settings before syncing.', 'error');
    switchTab('settings');
    return;
  }
  // Drive's connection and success/error UI lives in Settings. Display it
  // rather than issuing a misleading LAN-only SyncNow command.
  switchTab('settings');
  document.dispatchEvent(new CustomEvent('tempo-drive-sync-request'));
});

document.getElementById('btn-clear-queue')?.addEventListener('click', (e) => {
  const btn = e.currentTarget as HTMLButtonElement;
  armToConfirm(btn, 'Remove Drive-backed queued plays?', async () => {
    try {
      const result = await send(MessageType.ClearQueue);
      toast(`${result?.cleared ?? 0} safely removed; ${result?.protected ?? 0} unsaved local plays kept`, 'info');
      await refreshQueue();
    } catch (err) {
      toast(`Queue cleanup failed: ${String(err)}`, 'error');
    }
  });
});


// Pairing flow: generates QR token, starts discovery, falls back to manual IP if needed.

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
  if (tok && expiry > 0 && Date.now() < expiry && !currentPairing) {
    qrExpiryCache = expiry;
    return tok as string;
  }

  const token    = generateToken();
  const newExpiry = Date.now() + QR_TOKEN_TTL_MS;
  qrExpiryCache = newExpiry;
  await chrome.storage.local.set({ [QR_TOKEN_KEY]: token, [QR_EXPIRY_KEY]: newExpiry });
  return token;
}

// Status bar helpers

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

// QR rendering

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
    // Static import intentionally avoided: ./qr is code-split into its own lazy
    // chunk so it never lands in the popup's critical path (build.mjs enables
    // esbuild splitting for this exact dynamic import).
    const { generateQrDataUrl } = await import('./qr');

    const payload = JSON.stringify({ token, device_name: 'Tempo Stats (Browser)', v: 2 });
    img.src = generateQrDataUrl(payload, 180);

    loading.style.display = 'none';
    display.style.display = 'block';

    startQrCountdown();

    setQrStatus('idle', 'Scan the QR with Tempo on your phone…');

    const isFirefox = navigator.userAgent.includes('Firefox');
    if (isFirefox) {
      const scanBtn = document.getElementById('btn-start-scan');
      if (scanBtn) scanBtn.style.display = 'none';
      const notice = document.getElementById('firefox-notice');
      const manualCard = document.querySelector('#manual-section .manual-card');
      if (notice && manualCard) {
        notice.style.display = '';
        manualCard.insertBefore(notice, manualCard.firstChild);
      }
      const chromeExp = document.getElementById('chrome-expectation');
      if (chromeExp) chromeExp.style.display = 'none';
      const stepWait = document.getElementById('step-scan-wait');
      const stepIp = document.getElementById('step-enter-ip');
      if (stepWait) stepWait.style.display = 'none';
      if (stepIp) stepIp.style.display = '';

      const wrapper = document.getElementById('manual-section-wrapper');
      const qrFrame = document.getElementById('qr-frame');
      if (wrapper && qrFrame && qrFrame.nextElementSibling !== wrapper) {
        qrFrame.parentElement!.insertBefore(wrapper, qrFrame.nextElementSibling);
      }
      const summarySpan = wrapper?.querySelector('.drawer-header span');
      if (summarySpan) summarySpan.textContent = 'Connect Manually';

      setDiscoveryState('manual');
    }

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

  // Storage is read at most once per countdown; every subsequent tick derives
  // the remaining time purely from the in-memory mirror + Date.now().
  const loadExpiry = async () => {
    if (qrExpiryCache > 0) return;
    const { [QR_EXPIRY_KEY]: exp } = await chrome.storage.local.get(QR_EXPIRY_KEY);
    qrExpiryCache = typeof exp === 'number' ? exp : 0;
  };

  const tick = async () => {
    await loadExpiry();
    const remaining = Math.max(0, qrExpiryCache - Date.now());
    const m = Math.floor(remaining / 60000);
    const s = Math.floor((remaining % 60000) / 1000);

    if (remaining <= 0) {
      clearInterval(qrCountdownTimer!); qrCountdownTimer = null;
      qrExpiryCache = 0;
      if (expiryEl) {
        expiryEl.textContent = 'QR expired — ';
        expiryEl.style.color = 'var(--danger)';
      }
      if (!currentPairing) {
        await chrome.storage.local.remove([QR_TOKEN_KEY, QR_EXPIRY_KEY]);
        currentPairingToken = null;
        stopDiscovery();
        discoveryUIState = 'qr';
        await renderQr();
      }
    } else {
      if (expiryEl) {
        expiryEl.textContent = `Expires ${m}:${String(s).padStart(2, '0')} — `;
        expiryEl.style.color = remaining < 60000 ? 'var(--warning)' : 'var(--text-muted)';
      }
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
  qrExpiryCache = 0;
  currentPairingToken = null;
  await renderQr();
});

// "I've scanned the QR code" — manually trigger network discovery
document.getElementById('btn-start-scan')?.addEventListener('click', async () => {
  const btn = document.getElementById('btn-start-scan') as HTMLButtonElement;
  if (!btn) return;
  btn.disabled = true;
  btn.textContent = 'Searching…';
  
  const token = currentPairingToken;
  if (token) {
    startAutoDiscovery(token);
  }
});

// Discovery state helpers

function setDiscoveryState(state: DiscoveryState, _detail?: string) {
  discoveryUIState = state;
  const manSection = document.getElementById('manual-section');
  if (manSection) {
    manSection.style.display = state === 'manual' ? '' : 'none';
  }
  const manWrapper = document.getElementById('manual-section-wrapper') as HTMLDetailsElement | null;
  if (manWrapper) {
    manWrapper.open = (state === 'manual');
  }
}

function hideManualSection() {
  const manSection = document.getElementById('manual-section');
  if (manSection) manSection.style.display = 'none';
  const manWrapper = document.getElementById('manual-section-wrapper') as HTMLDetailsElement | null;
  if (manWrapper) manWrapper.open = false;
}

// Auto-discovery

/** Try to authenticate-ping a single IP. Returns { device, latencyMs } on success. */
async function tryPing(ip: string, token: string): Promise<{ device: string; latencyMs: number } | null> {
  try {
    const result = await send(MessageType.PingPhone, { ip, port: SERVER_PORT, token });

    // Service worker signals missing permission — request from here (popup = valid user gesture context)
    // Note: Firefox doesn't support optional_host_permissions, so skip permission request there
    const isFirefox = navigator.userAgent.includes('Firefox');
    if (result?.needsPermission && result?.origin && !isFirefox) {
      try {
        const granted = await chrome.permissions.request({ origins: [result.origin] });
        if (!granted) return null;
        const retry = await send(MessageType.PingPhone, { ip, port: SERVER_PORT, token });
        return retry?.ok ? { device: retry.device ?? ip, latencyMs: retry.latencyMs ?? 0 } : null;
      } catch { return null; }
    }

    return result?.ok ? { device: result.device ?? ip, latencyMs: result.latencyMs ?? 0 } : null;
  } catch { return null; }
}

function stopDiscovery() {
  discoveryAborted = true;
  // discoveryTimer is now a setTimeout handle (not setInterval) — use clearTimeout
  if (discoveryTimer) { clearTimeout(discoveryTimer as unknown as ReturnType<typeof setTimeout>); discoveryTimer = null; }
  if ((window as any)._tempoStopElapsed) (window as any)._tempoStopElapsed();
}

// mDNS fast-path probe
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

  // Step 1b: Try connection history IPs first — instant reconnect on known networks
  const historyResp = await send(MessageType.GetConnectionHistory);
  const historyEntries: Array<{ ip: string; port: number; successCount: number }> =
    historyResp?.history ?? [];

  if (historyEntries.length > 0) {
    setQrStatus('searching', 'Trying known addresses…', '0s');
    for (const entry of historyEntries.slice(0, 5)) {
      if (discoveryAborted) return;
      const dev = await discoveryPing(entry.ip, token);
      if (dev !== null) {
        stopDiscovery();
        setQrStatus('found', `Found ${dev} from history — connecting…`);
        await completePairing(entry.ip, SERVER_PORT, token, dev);
        return;
      }
    }
  }

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
        'Still searching. Enter IP from Tempo > Settings > Sync with Desktop, or keep waiting.',
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
  const origin = `http://${ip}:${port}/`;
  const isFirefox = navigator.userAgent.includes('Firefox');
  
  if (isFirefox) {
    try {
      const granted = await chrome.permissions.request({ origins: [origin] });
      if (!granted) {
        console.warn('[Tempo] Firefox permission request denied for', origin);
      }
    } catch (err) {
      console.warn('[Tempo] Firefox permission request failed:', err);
    }
  }

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
    qrExpiryCache = 0;
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
    await completePairing(ip, SERVER_PORT, token!, dev.device);
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
  qrExpiryCache = 0;
  currentPairingToken = null;
  await renderQr();
});

// Pairing refresh

async function refreshPairing(prefetched?: PairingInfo | null) {
  try {
    // Bootstrap passes the already-fetched pairing; other callers still fetch.
    const pairing: PairingInfo | null = prefetched !== undefined
      ? prefetched
      : (await send(MessageType.GetPairing))?.pairing ?? null;
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

      if (!isPairingTabActive()) {
        return;
      }

      if (discoveryUIState === 'qr' || discoveryUIState === 'success') {
        discoveryUIState = 'qr';
        await renderQr();
      } else {
        setDiscoveryState(discoveryUIState);
      }
    }
    refreshHomeState();
    refreshConnectionHealth();
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
    const latencyColor = dev.latencyMs < 100 ? 'var(--success)' : dev.latencyMs < 500 ? 'var(--warning)' : 'var(--danger)';
    resultEl.className  = 'test-result success';
    resultEl.textContent = `✓ Phone reachable${dev.device && dev.device !== currentPairing.phoneIp ? ` — ${dev.device}` : ''} (${dev.latencyMs}ms)`;
    statusEl.textContent = '✓ Reachable';
    statusEl.style.color = latencyColor;
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

// Rebuild Connection
//
// Runs a fresh, step-by-step discovery flow without requiring unpair:
//   Step 1 — mDNS  (tempo-phone.local)   ~1-2 s  — heals IP changes instantly
//   Step 2 — Hotspot seeds               ~2 s    — phone-as-gateway scenario
//   Step 3 — Subnet scan via WebRTC      up to ~15 s — full /24 sweep
//
// On success, the stored phoneIp is updated so future auto-syncs use the new address.


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
document.getElementById('btn-unpair')?.addEventListener('click', (e) => {
  const btn = e.currentTarget as HTMLButtonElement;
  armToConfirm(btn, 'Confirm unpair?', async () => {
    await send(MessageType.RemovePairing);
    await chrome.storage.local.remove([QR_TOKEN_KEY, QR_EXPIRY_KEY]);
    qrExpiryCache = 0;
    currentPairingToken = null;
    toast('Unpaired from device', 'success');
    refreshPairing();
  });
});

// Nav helpers

document.getElementById('home-primary-action')?.addEventListener('click', () => {
  if (currentYoutubeSuggestion) { allowCurrentYoutubeChannel(); return; }
  if (!currentPairing) {
    const ready = !!cloudHistoryStatus?.enabled && !!cloudHistoryStatus.connected
      && !cloudHistoryStatus.needsInteractiveAuth;
    switchTab(ready && (cloudHistoryStatus?.pendingUploads ?? 0) > 0 ? 'queue' :
      cloudHistoryStatus?.configured ? 'settings' : 'pairing');
    return;
  }
  switchTab(currentQueueCount > 0 || currentNowPlaying?.title ? 'queue' : 'pairing');
});
document.getElementById('home-secondary-action')?.addEventListener('click', () => {
  if (currentYoutubeSuggestion) { blockCurrentYoutubeChannel(); return; }
  if (!currentPairing) {
    const ready = !!cloudHistoryStatus?.enabled && !!cloudHistoryStatus.connected
      && !cloudHistoryStatus.needsInteractiveAuth;
    switchTab(ready && (cloudHistoryStatus?.pendingUploads ?? 0) === 0 || !cloudHistoryStatus?.configured ?
      'queue' : ready ? 'settings' : 'pairing');
    return;
  }
  switchTab(currentQueueCount > 0 || currentNowPlaying?.title ? 'pairing' : 'queue');
});
document.getElementById('queue-open-pairing')?.addEventListener('click', () =>
  switchTab(cloudHistoryStatus?.configured ? 'settings' : 'pairing'));

async function allowCurrentYoutubeChannel() {
  if (!currentYoutubeSuggestion) return;
  const channel = currentYoutubeSuggestion.channel;
  await send(MessageType.AddYoutubeChannel, { channel });
  currentYoutubeSuggestion = null;
  await refreshNowPlaying();
  await refreshArtists();
}

function dismissCurrentYoutubeSuggestion() {
  if (currentYoutubeSuggestion) {
    dismissedPopupYoutubeChannels.add(currentYoutubeSuggestion.channel.toLowerCase().trim());
  }
  currentYoutubeSuggestion = null;
  const youtubeEl = document.getElementById('youtube-suggestion');
  if (youtubeEl) youtubeEl.style.display = 'none';
  refreshHomeState();
}

async function blockCurrentYoutubeChannel() {
  if (!currentYoutubeSuggestion) return;
  const channel = currentYoutubeSuggestion.channel;
  dismissedPopupYoutubeChannels.add(channel.toLowerCase().trim());
  await send(MessageType.BlockYoutubeChannel, { channel });
  currentYoutubeSuggestion = null;
  await refreshNowPlaying();
  await refreshArtists();
}

document.getElementById('btn-youtube-allow')?.addEventListener('click', allowCurrentYoutubeChannel);
document.getElementById('btn-youtube-not-now')?.addEventListener('click', dismissCurrentYoutubeSuggestion);
document.getElementById('btn-youtube-never')?.addEventListener('click', blockCurrentYoutubeChannel);

// Known Artists

async function refreshArtists() {
  try {
    const resp     = await send(MessageType.GetSettings);
    const settings: Settings = resp?.settings ?? { ...DEFAULT_SETTINGS };
    renderTagList('artist-list', settings.knownArtists, async removed => {
      settings.knownArtists = settings.knownArtists.filter(a => a !== removed);
      await send(MessageType.SetSettings, { settings });
      toast(`Removed ${removed}`, 'success');
      refreshArtists();
    });
    renderTagList('channel-list', settings.youtubeChannels, async removed => {
      settings.youtubeChannels = settings.youtubeChannels.filter(c => c !== removed);
      await send(MessageType.SetSettings, { settings });
      toast(`Removed ${removed}`, 'success');
      refreshArtists();
    });
    renderTagList('blocked-channel-list', settings.blockedYoutubeChannels, async removed => {
      settings.blockedYoutubeChannels = settings.blockedYoutubeChannels.filter(c => c !== removed);
      await send(MessageType.SetSettings, { settings });
      toast(`Removed ${removed}`, 'success');
      refreshArtists();
    });
  } catch (err) { console.warn('[Tempo] refreshArtists failed:', err); }
}

// One delegated click listener per tag-list container, bound once; renderTagList
// swaps the active onRemove handler per container instead of rebinding every row.
const tagRemoveHandlers = new Map<string, (item: string) => void>();

function bindTagListDelegation(cid: string) {
  const c = document.getElementById(cid);
  if (!c || tagRemoveHandlers.has(cid)) return;
  tagRemoveHandlers.set(cid, () => {});
  c.addEventListener('click', (e) => {
    const target = (e.target as HTMLElement).closest('.tag-remove') as HTMLElement | null;
    if (!target) return;
    const item = target.dataset.item;
    if (item == null) return;
    tagRemoveHandlers.get(cid)?.(item);
  });
}

function renderTagList(cid: string, items: string[], onRemove: (item: string) => void) {
  bindTagListDelegation(cid);
  tagRemoveHandlers.set(cid, onRemove);
  const c = document.getElementById(cid)!;
  if (items.length === 0) {
    c.innerHTML = '<span class="text-muted">None added yet</span>';
    return;
  }
  c.innerHTML = items.map(item =>
    `<span class="tag">${escapeHtml(item)}<button type="button" class="tag-remove" data-item="${escapeHtml(item)}" aria-label="Remove ${escapeHtml(item)}">×</button></span>`
  ).join('');
}

['btn-add-artist', 'btn-add-channel'].forEach(btnId => {
  document.getElementById(btnId)?.addEventListener('click', async () => {
    const isArtist = btnId === 'btn-add-artist';
    const input    = document.getElementById(isArtist ? 'new-artist' : 'new-channel') as HTMLInputElement;
    const name     = input.value.trim().slice(0, MAX_INPUT_LEN);
    if (!name) return;
    const resp     = await send(MessageType.GetSettings);
    const settings: Settings = resp?.settings ?? { ...DEFAULT_SETTINGS };
    const list     = isArtist ? settings.knownArtists : settings.youtubeChannels;
    if (list.includes(name)) {
      toast(`${name} is already added`, 'info');
    } else {
      list.push(name);
      await send(MessageType.SetSettings, { settings });
      toast(`Added ${name}`, 'success');
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

// Settings

const DEFAULT_SETTINGS: Settings = {
  trackingEnabled: true,
  offlineMode: false,
  syncIntervalMinutes: 30,
  pollingIntervalSeconds: 2,
  knownArtists: [],
  youtubeChannels: [],
  blockedYoutubeChannels: [],
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
    if (!plays.length) { toast('No plays to export', 'info'); return; }
    const url = URL.createObjectURL(new Blob([JSON.stringify(plays, null, 2)], { type: 'application/json' }));
    Object.assign(document.createElement('a'), {
      href: url,
      download: `tempo-plays-${new Date().toISOString().slice(0, 10)}.json`,
    }).click();
    URL.revokeObjectURL(url);
  } catch { toast('Export failed', 'error'); }
});

// Background message listener

chrome.runtime.onMessage.addListener((message) => {
  if (message?.type === MessageType.PairingInvalidated) {
    currentPairing = null;
    discoveryUIState = 'qr';
    refreshPairing();
    switchTab('pairing');
  }

  if (message?.type === MessageType.SocketStateChanged) {
    updateSocketIndicator(message.state);
  }
});

function updateSocketIndicator(state: string): void {
  const el = document.getElementById('socket-indicator');
  if (!el) return;

  const labels: Record<string, string> = {
    connected: 'Live',
    connecting: 'Connecting…',
    reconnecting: 'Reconnecting…',
    disconnected: 'Offline',
    idle: 'Idle · connects on play',
  };

  el.textContent = labels[state] ?? state;
  el.className = `socket-indicator socket-${state}`;
  el.style.display = state === 'disconnected' ? 'none' : '';
}

/**
 * Paint connection health + socket state. Split from refreshConnectionHealth so
 * the bootstrap payload can feed the same renderer without extra messages.
 */
function applyConnectionHealth(health: ConnectionHealth | null | undefined, socketState: SocketState | string | null | undefined): void {
  const statusEl = document.getElementById('paired-status');
  if (!statusEl || !currentPairing) return;

  if (socketState === 'connected') {
    statusEl.textContent = '✓ Live connection';
    statusEl.style.color = 'var(--success)';
  } else if (health?.healthy) {
    statusEl.textContent = '✓ Reachable';
    statusEl.style.color = 'var(--success)';
  } else if ((health?.consecutiveFailures ?? 0) > 0) {
    statusEl.textContent = `⚠ ${health!.consecutiveFailures} failed pings`;
    statusEl.style.color = 'var(--warning)';
  }

  updateSocketIndicator(socketState ?? 'disconnected');
}

async function refreshConnectionHealth(): Promise<void> {
  try {
    const [healthResp, socketResp] = await Promise.all([
      send(MessageType.GetConnectionHealth),
      send(MessageType.GetSocketState),
    ]);
    applyConnectionHealth(healthResp?.health, socketResp?.state);
  } catch {}
}

// Init

/** Shape of the GET_POPUP_STATE bootstrap response (see background/service-worker). */
interface PopupBootstrapState {
  pairing: PairingInfo | null;
  nowPlaying: NowPlaying | null;
  ytSuggestion: YoutubeChannelSuggestion | null;
  queueCount: number;
  syncStatus: SyncStatus | null;
  connectionHealth: ConnectionHealth | null;
  socketState: SocketState | null;
}

/**
 * Single-round-trip bootstrap: one GET_POPUP_STATE message returns everything
 * the popup needs for its first paint. Per-tab detail (queue items, settings,
 * artists) stays lazy until its tab is actually opened.
 */
async function init(): Promise<void> {
  let state: PopupBootstrapState | null = null;
  try {
    state = await send(MessageType.GetPopupState);
  } catch (err) {
    console.warn('[Tempo] popup bootstrap failed:', err);
  }

  applyQueueCount(state?.queueCount ?? 0);
  renderSyncStatus(document.getElementById('sync-status')!, state?.syncStatus ?? null);

  // Seed now-playing so the first paint needs no extra round trip; the 2 s poll
  // takes over from here.
  currentNowPlaying = state?.nowPlaying ?? null;
  const suggestion: YoutubeChannelSuggestion | null = state?.ytSuggestion ?? null;
  currentYoutubeSuggestion = suggestion &&
    !dismissedPopupYoutubeChannels.has(suggestion.channel.toLowerCase().trim())
      ? suggestion
      : null;

  // On bootstrap failure pass undefined so refreshPairing still fetches
  // (null would wrongly render the unpaired state).
  await refreshPairing(state ? state.pairing : undefined);
  applyConnectionHealth(state?.connectionHealth, state?.socketState);
  renderNowPlaying(currentNowPlaying, currentYoutubeSuggestion);
  startNpPolling();

  if (!state) await refreshNowPlaying(); // bootstrap failed — fall back to the per-message path
  // Show the real manifest version instead of a hardcoded string.
  const versionEl = document.getElementById('version-info');
  if (versionEl) {
    versionEl.textContent = `Tempo Stats v${chrome.runtime.getManifest().version}`;
  }
}

void init();

/**
 * Sync engine for transmitting queued plays to the Tempo Android app.
 * Handles HMAC signing, payload encryption, exponential backoff,
 * rate-limit cooldowns, and alarm-based retries across worker hibernations.
 */

import type { SyncPayload, SyncResponse, PairingInfo } from '../shared/types';
import * as storage from './storage';
import { getDriveLanOrigin } from './drive-history';
import { signRequest, encryptBody, decryptBody } from '../shared/security';

const IS_FIREFOX = typeof navigator !== 'undefined' && navigator.userAgent.includes('Firefox');

// Constants (match desktop/src-tauri/src/network/mod.rs)

const MAX_RETRIES = 3;
const INITIAL_RETRY_DELAY_MS = 1_000;
const REQUEST_TIMEOUT_MS = 10_000;
const MAX_BATCH_SIZE = 50;
const DISCOVERY_PING_TIMEOUT_MS = 900;
const SUBNET_SCAN_BATCH_SIZE = 48;
const SUBNET_RESCAN_COOLDOWN_MS = 30 * 60 * 1000;
/** Minimum spacing between full phone-address recovery attempts. */
const FULL_RECOVERY_COOLDOWN_MS = 5 * 60 * 1000;

const SYNC_ALARM_NAME = 'tempo-stats-auto-sync';
const RETRY_ALARM_NAME = 'tempo-stats-retry-sync';
const HEARTBEAT_ALARM_NAME = 'tempo-pairing-heartbeat';
const HEARTBEAT_INTERVAL_MINUTES = 5;
const TOKEN_REFRESH_ALARM_NAME = 'tempo-token-refresh';
const TOKEN_REFRESH_INTERVAL_MINUTES = 60;
const AUTH_FAILURE_THRESHOLD = 3;

/** Default cooldown when the phone rate-limits us and gives no Retry-After. */
const DEFAULT_RATE_LIMIT_COOLDOWN_MS = 60_000;
/** Never cool down for less than this, even with a tiny Retry-After. */
const MIN_RATE_LIMIT_COOLDOWN_MS = 30_000;
/** Exponential retry-alarm cap. */
const MAX_RETRY_DELAY_MINUTES = 60;

// Error taxonomy
//
// User-reported failure modes (401, 502, "Phone Unreachable", RateLimited)
// need different reactions:
//   • rate_limited / server / network / unreachable / battery are TRANSIENT —
//     plays stay queued and we back off exponentially instead of hammering.
//   • auth / rejected are FATAL for the current batch — plays are marked
//     failed so the user sees them and can retry deliberately.

export type SyncErrorKind =
  | 'rate_limited'
  | 'auth'
  | 'rejected'
  | 'server'
  | 'network'
  | 'unreachable'
  | 'battery'
  | 'unknown';

/** Only an explicit JSON ok=true is a valid LAN batch acknowledgment.
 * Neither an empty HTTP 200 nor a partially received response may empty the
 * persistent queue. Kept pure so the regression cases can run without Chrome.
 */
export function parseLanAcknowledgment(body: string): SyncResponse {
  let payload: unknown;
  try {
    payload = JSON.parse(body);
  } catch {
    throw new SyncError('Phone LAN acknowledgment was not valid JSON', 'network');
  }
  if (!payload || typeof payload !== 'object' || (payload as SyncResponse).ok !== true) {
    throw new SyncError('Phone did not confirm the LAN batch', 'network');
  }
  return payload as SyncResponse;
}

export class SyncError extends Error {
  constructor(
    message: string,
    readonly kind: SyncErrorKind,
    readonly httpStatus?: number,
    readonly retryAfterMs?: number,
  ) {
    super(message);
    this.name = 'SyncError';
  }
}

/** Transient errors keep plays queued; fatal errors mark them failed. */
export function isTransientSyncError(kind: SyncErrorKind): boolean {
  return kind === 'rate_limited' || kind === 'server' || kind === 'network' ||
         kind === 'unreachable' || kind === 'battery';
}

/** Exponential backoff for the retry alarm: 0.5, 1, 2, 4, 8, … capped at 60 min. */
export function backoffDelayMinutes(consecutiveFailures: number): number {
  const exp = Math.max(consecutiveFailures - 1, 0);
  return Math.min(0.5 * Math.pow(2, exp), MAX_RETRY_DELAY_MINUTES);
}

/** Parse a Retry-After header (delta-seconds or HTTP-date) into milliseconds. */
function parseRetryAfterMs(headerValue: string | null): number | undefined {
  if (!headerValue) return undefined;
  const seconds = Number(headerValue);
  if (Number.isFinite(seconds) && seconds >= 0) return seconds * 1000;
  const dateMs = Date.parse(headerValue);
  if (!Number.isNaN(dateMs)) return Math.max(dateMs - Date.now(), 0);
  return undefined;
}

// Adaptive sync

function getAdaptiveSyncInterval(queueSize: number, baseIntervalMinutes: number): number {
  if (queueSize === 0) return baseIntervalMinutes;
  if (queueSize < 5) return Math.max(5, Math.floor(baseIntervalMinutes / 2));
  if (queueSize < 20) return Math.max(2, Math.floor(baseIntervalMinutes / 4));
  return 2;
}

function getAdaptiveBatchSize(): number {
  const conn = (navigator as any).connection;
  if (!conn) return MAX_BATCH_SIZE;

  if (conn.effectiveType === '4g' && conn.downlink > 5) return 100;
  if (conn.effectiveType === '4g') return MAX_BATCH_SIZE;
  if (conn.effectiveType === '3g') return 20;
  return 10;
}

function getNetworkFingerprint(): string {
  const conn = (navigator as any).connection;
  if (!conn) return 'unknown';
  return `${conn.effectiveType || 'unknown'}-${conn.type || 'unknown'}`;
}


// Token lock (prevents concurrent token read/write races)

let _tokenLockPromise: Promise<void> | null = null;

async function withTokenLock<T>(fn: () => Promise<T>): Promise<T> {
  while (_tokenLockPromise) {
    await _tokenLockPromise;
  }
  let release!: () => void;
  _tokenLockPromise = new Promise<void>(r => { release = r; });
  try {
    return await fn();
  } finally {
    _tokenLockPromise = null;
    release();
  }
}

// Sync Status

export interface SyncStatus {
  lastSyncTime: string | null;
  lastSyncResult: string | null;
  isSyncing: boolean;
  queueCount: number;
  /** Classified kind of the last failure, if any. */
  lastErrorKind: SyncErrorKind | null;
  /** HTTP status of the last failure, if any. */
  lastErrorStatus: number | null;
  /** Consecutive failed sync attempts (resets on success). */
  consecutiveFailures: number;
  /** Epoch ms until which we are cooling down after a rate limit, if any. */
  rateLimitedUntil: number | null;
  /** Epoch ms of the next scheduled retry, if one is pending. */
  nextRetryAt: number | null;
}

let _isSyncing = false;
let _lastSyncTime: string | null = null;
let _lastSyncResult: string | null = null;
let _lastSubnetScanAt = 0;
/** Epoch ms of the last full multi-path recovery attempt (history/gateway/subnet/mDNS). */
let _lastFullRecoveryAt = 0;
let _lastErrorKind: SyncErrorKind | null = null;
let _lastErrorStatus: number | null = null;
let _consecutiveFailures = 0;
let _rateLimitedUntil: number | null = null;
let _nextRetryAt: number | null = null;

/** Persist backoff state so a service-worker restart doesn't reset cooldowns. */
async function persistBackoffState(): Promise<void> {
  try {
    await storage.saveSyncBackoff({
      lastErrorKind: _lastErrorKind,
      lastErrorStatus: _lastErrorStatus,
      consecutiveFailures: _consecutiveFailures,
      rateLimitedUntil: _rateLimitedUntil,
      nextRetryAt: _nextRetryAt,
      lastSyncTime: _lastSyncTime,
      lastSyncResult: _lastSyncResult,
    });
  } catch { /* best-effort */ }
}

/**
 * Restore backoff state after service-worker hibernation. Called once at
 * startup; without it a worker restart would forget an active rate-limit
 * cooldown and immediately hammer the phone again.
 */
export async function restoreBackoffState(): Promise<void> {
  try {
    const saved = await storage.getSyncBackoff();
    if (!saved) return;
    _lastErrorKind = (saved.lastErrorKind as SyncErrorKind | null) ?? null;
    _lastErrorStatus = saved.lastErrorStatus ?? null;
    _consecutiveFailures = saved.consecutiveFailures ?? 0;
    _rateLimitedUntil = saved.rateLimitedUntil ?? null;
    _nextRetryAt = saved.nextRetryAt ?? null;
    _lastSyncTime = saved.lastSyncTime ?? null;
    _lastSyncResult = saved.lastSyncResult ?? null;
    console.log(
      `[Tempo] Restored sync backoff state (failures=${_consecutiveFailures}, ` +
      `rateLimited=${_rateLimitedUntil ? 'yes' : 'no'})`,
    );
  } catch { /* best-effort */ }
}

export interface SyncOptions {
  forceDiscovery?: boolean;
}

export function getSyncStatus(queueCount: number): SyncStatus {
  return {
    lastSyncTime: _lastSyncTime,
    lastSyncResult: _lastSyncResult,
    isSyncing: _isSyncing,
    queueCount,
    lastErrorKind: _lastErrorKind,
    lastErrorStatus: _lastErrorStatus,
    consecutiveFailures: _consecutiveFailures,
    rateLimitedUntil: _rateLimitedUntil,
    nextRetryAt: _nextRetryAt,
  };
}

// Host permission helpers
//
// IMPORTANT: chrome.permissions.request() MUST be called from a foreground
// context (popup / options page) in direct response to a user gesture.
// It CANNOT be called from the service worker — doing so causes the browser
// to hang on the permission dialog with no way to click Allow or Deny.
// The service worker may only CHECK permissions (permissions.contains).
//

/**
 * Check whether the extension already has host permission for `origin`.
 * Safe to call from the service worker.
 */
export async function hasHostPermission(origin: string): Promise<boolean> {
  try {
    // Firefox: <all_urls> grants access to all origins, but permissions.contains()
    // doesn't recognize this. Skip the check on Firefox.
    if (IS_FIREFOX) {
      return true;
    }
    return await chrome.permissions.contains({
      origins: [origin]
    });
  } catch {
    return false;
  }
}

/**
 * Remove a previously-granted host permission.
 * Safe to call from the service worker.
 */
export async function removeHostPermission(origin: string): Promise<void> {
  try {
    await chrome.permissions.remove({
      origins: [origin]
    });
  } catch { /* Non-critical */ }
}

/**
 * @deprecated Do NOT call from the service worker.
 * Use chrome.permissions.request() directly in the popup/options page
 * in response to a user gesture. This stub is kept only so that any
 * remaining import sites produce a compile-time reminder.
 */
export async function requestHostPermission(_origin: string): Promise<boolean> {
  console.error(
    '[Tempo] requestHostPermission() must not be called from the service worker. '
    + 'Call chrome.permissions.request() from the popup instead.'
  );
  return false;
}

// Phone discovery helpers

async function pingPhone(
  ip: string,
  port: number,
  authToken?: string,
  timeoutMs = 3_000,
): Promise<{ ok: boolean; authFailed: boolean }> {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    const headers: Record<string, string> = authToken
      ? await signRequest(authToken)
      : {};
    const response = await fetch(`http://${ip}:${port}/api/ping`, {
      signal: controller.signal,
      headers,
    });
    clearTimeout(timeout);
    return { ok: response.ok, authFailed: response.status === 401 || response.status === 403 };
  } catch {
    return { ok: false, authFailed: false };
  }
}

function getPrivateIpv4Subnet(ip: string): string | null {
  const parts = ip.split('.').map(Number);
  if (
    parts.length !== 4 ||
    parts.some(part => !Number.isInteger(part) || part < 0 || part > 255)
  ) {
    return null;
  }

  const isPrivate =
    parts[0] === 10 ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168);

  return isPrivate ? `${parts[0]}.${parts[1]}.${parts[2]}` : null;
}

function buildPriorityOrderedCandidates(subnet: string, excludeIp?: string): string[] {
  const priorityRanges = [
    [100, 200],
    [2, 100],
    [200, 255],
  ];

  const candidates: string[] = [];
  const seen = new Set<string>();

  for (const [start, end] of priorityRanges) {
    for (let i = start; i <= end; i++) {
      const ip = `${subnet}.${i}`;
      if (ip !== excludeIp && !seen.has(ip)) {
        seen.add(ip);
        candidates.push(ip);
      }
    }
  }

  return candidates;
}

async function findPhoneOnStoredSubnet(pairing: PairingInfo, forceDiscovery = false): Promise<string | null> {
  const subnet = getPrivateIpv4Subnet(pairing.phoneIp);
  if (!subnet) return null;

  const now = Date.now();
  if (!forceDiscovery && now - _lastSubnetScanAt < SUBNET_RESCAN_COOLDOWN_MS) {
    console.log('[Tempo] Skipping same-subnet phone scan; recent recovery scan already ran');
    return null;
  }
  _lastSubnetScanAt = now;

  const candidates = buildPriorityOrderedCandidates(subnet, pairing.phoneIp);

  console.log(`[Tempo] Scanning ${subnet}.x for phone after stored IP failed (priority-ordered)`);

  for (let start = 0; start < candidates.length; start += SUBNET_SCAN_BATCH_SIZE) {
    const batch = candidates.slice(start, start + SUBNET_SCAN_BATCH_SIZE);
    const results = await Promise.all(
      batch.map(async ip => ({
        ip,
        reachable: (await pingPhone(ip, pairing.phonePort, pairing.authToken, DISCOVERY_PING_TIMEOUT_MS)).ok,
      }))
    );
    const found = results.find(result => result.reachable);
    if (found) return found.ip;
  }

  return null;
}


async function checkPhoneBattery(ip: string, port: number, authToken?: string): Promise<boolean> {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 3_000);
    const headers: Record<string, string> = authToken
      ? await signRequest(authToken)
      : {};
    const response = await fetch(`http://${ip}:${port}/api/battery`, {
      signal: controller.signal,
      headers,
    });
    clearTimeout(timeout);

    if (!response.ok) return true;

    const data = await response.json();
    if (data.critical) {
      console.warn('[Tempo] Phone battery is critical, skipping sync');
      return false;
    }
    return true;
  } catch {
    return true;
  }
}

/** Fast-path IPs for when the phone IS the hotspot gateway */
const HOTSPOT_GATEWAY_IPS = [
  '192.168.43.1',   // Android hotspot
  '172.20.10.1',    // iOS hotspot
  '192.168.49.1',   // Android WiFi Direct
];

/**
 * Best-effort .local hostname. Android advertises a DNS-SD service; some
 * networks/OSes also make a stable host name reachable, but many do not.
 */
const MDNS_HOSTNAME = 'tempo-phone.local';

/**
 * Try to reach the phone via its best-effort mDNS hostname.
 * Returns the hostname if reachable.
 */
async function pingPhoneViaMdns(port: number, authToken?: string): Promise<string | null> {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 4_000);
    const headers: Record<string, string> = authToken
      ? await signRequest(authToken)
      : {};
    const response = await fetch(`http://${MDNS_HOSTNAME}:${port}/api/ping`, {
      signal: controller.signal,
      headers,
    });
    clearTimeout(timeout);
    if (!response.ok) return null;

    // Chrome resolves the hostname transparently — we can't get the resolved IP
    // from the response itself, but returning the hostname is enough for callers
    // to verify reachability. The stored IP will be updated to the hostname so
    // subsequent attempts also try mDNS first.
    return MDNS_HOSTNAME;
  } catch {
    return null;
  }
}

/**
 * Probe the top connection-history IPs concurrently (≤5 pings in parallel).
 * Returns the highest-priority reachable IP plus every miss entry so the
 * failure bookkeeping can be coalesced into a single batched history rewrite.
 */
async function probeConnectionHistory(pairing: PairingInfo): Promise<{ winner: string | null; misses: Array<{ ip: string; port: number }> }> {
  const history = await storage.getConnectionHistory();
  if (history.length === 0) return { winner: null, misses: [] };

  const sorted = [...history].sort((a, b) => b.successCount - a.successCount || b.lastSeen - a.lastSeen);
  const candidates = sorted.slice(0, 5).filter(entry => entry.ip !== pairing.phoneIp);
  if (candidates.length === 0) return { winner: null, misses: [] };

  console.log(`[Tempo] Trying ${candidates.length} IPs from connection history`);

  const results = await Promise.all(
    candidates.map(async entry => ({
      ip: entry.ip,
      port: entry.port || pairing.phonePort,
      reachable: (await pingPhone(entry.ip, entry.port || pairing.phonePort, pairing.authToken, 2_000)).ok,
    })),
  );

  const winner = results.find(result => result.reachable) ?? null;
  if (winner) {
    console.log(`[Tempo] Found phone via connection history: ${winner.ip}`);
  }
  return { winner: winner ? winner.ip : null, misses: results.filter(r => !r.reachable) };
}

/** Probe the well-known hotspot gateway IPs concurrently, priority order preserved. */
async function findPhoneViaHotspotGateway(pairing: PairingInfo): Promise<string | null> {
  const candidates = HOTSPOT_GATEWAY_IPS.filter(ip => ip !== pairing.phoneIp);

  const results = await Promise.all(
    candidates.map(async ip => ({
      ip,
      reachable: (await pingPhone(ip, pairing.phonePort, pairing.authToken)).ok,
    })),
  );

  const found = results.find(result => result.reachable);
  if (found) {
    console.log(`[Tempo] Found phone at hotspot gateway ${found.ip}`);
  }
  return found ? found.ip : null;
}

async function resolvePhoneAddress(pairing: PairingInfo, options: SyncOptions = {}): Promise<{ ip: string; port: number } | null> {
  const fingerprint = getNetworkFingerprint();

  // Fast path — stored address stays first and unchanged.
  if (pairing.phoneIp) {
    const reachable = (await pingPhone(pairing.phoneIp, pairing.phonePort, pairing.authToken)).ok;
    if (reachable) {
      await storage.recordConnectionSuccess(pairing.phoneIp, pairing.phonePort, fingerprint);
      return { ip: pairing.phoneIp, port: pairing.phonePort };
    }
    await storage.recordConnectionFailure(pairing.phoneIp, pairing.phonePort);
    console.log(`[Tempo] Stored address ${pairing.phoneIp} unreachable, trying recovery paths...`);
  }

  // Gate full recovery to once per cooldown window; manual sync paths pass
  // forceDiscovery:true to bypass it. The subnet scan keeps its own separate
  // cooldown inside findPhoneOnStoredSubnet().
  const forceDiscovery = options.forceDiscovery === true;
  if (!forceDiscovery && Date.now() - _lastFullRecoveryAt < FULL_RECOVERY_COOLDOWN_MS) {
    console.log('[Tempo] Skipping full address recovery; recent attempt already ran');
    return null;
  }
  _lastFullRecoveryAt = Date.now();

  // Run every recovery path concurrently, then pick the winner AFTER all
  // settle, in the existing priority order:
  //   connection history > hotspot gateway > same-subnet scan > mDNS.
  const [historyProbe, gatewayIp, subnetIp, mdnsResult] = await Promise.all([
    probeConnectionHistory(pairing),
    findPhoneViaHotspotGateway(pairing),
    findPhoneOnStoredSubnet(pairing, forceDiscovery),
    pingPhoneViaMdns(pairing.phonePort, pairing.authToken),
  ]);

  // Coalesce miss-side history bookkeeping into ONE array rewrite.
  if (historyProbe.misses.length > 0) {
    await storage.recordConnectionFailures(historyProbe.misses);
  }

  if (historyProbe.winner) {
    await storage.savePairing({ ...pairing, phoneIp: historyProbe.winner });
    await storage.recordConnectionSuccess(historyProbe.winner, pairing.phonePort, fingerprint);
    return { ip: historyProbe.winner, port: pairing.phonePort };
  }

  if (gatewayIp) {
    await storage.savePairing({ ...pairing, phoneIp: gatewayIp });
    await storage.recordConnectionSuccess(gatewayIp, pairing.phonePort, fingerprint);
    return { ip: gatewayIp, port: pairing.phonePort };
  }

  if (subnetIp) {
    console.log(`[Tempo] Found phone at new same-subnet address ${subnetIp}`);
    await storage.savePairing({ ...pairing, phoneIp: subnetIp });
    await storage.recordConnectionSuccess(subnetIp, pairing.phonePort, fingerprint);
    return { ip: subnetIp, port: pairing.phonePort };
  }

  if (mdnsResult) {
    console.log(`[Tempo] Found phone via mDNS (${MDNS_HOSTNAME}) — updating stored address`);
    await storage.savePairing({ ...pairing, phoneIp: MDNS_HOSTNAME });
    await storage.recordConnectionSuccess(MDNS_HOSTNAME, pairing.phonePort, fingerprint);
    return { ip: MDNS_HOSTNAME, port: pairing.phonePort };
  }

  console.warn('[Tempo] Phone not reachable via stored IP, connection history, mDNS, hotspot seeds, or stored subnet scan. Open popup to re-discover.');
  return null;
}


// Sync engine

/**
 * Sync queued plays to the paired phone.
 * Also retries previously failed plays.
 * Returns the number of plays synced, or throws on error.
 */
export async function syncToPhone(options: SyncOptions = {}): Promise<number> {
  if (_isSyncing) {
    console.log('[Tempo] Sync already in progress, skipping');
    return 0;
  }

  _isSyncing = true;

  // Snapshot of queued play ids taken right after the initial fetch. Reused by
  // the fatal-error path so a third full getQueuedPlays() collection read is
  // not needed; ids of batches that complete successfully are removed as we
  // go so already-synced plays are never marked failed.
  let queuedIdsForFailure: number[] = [];

  try {
    // 0. Hard gates — offline mode or no pairing means ZERO phone traffic.
    //    One choke point covers every entry path (auto-sync/retry alarms,
    //    socket requests, manual sync) so callers can't leak network work.
    const gateSettings = await storage.getSettings();
    if (gateSettings.offlineMode) {
      console.log('[Tempo] Sync skipped — offline mode');
      _lastSyncResult = 'Offline mode';
      _lastSyncTime = new Date().toISOString();
      return 0;
    }

    let pairing = await storage.getPairing();
    if (!pairing) {
      throw new SyncError('Not paired with any device', 'unknown');
    }

    // Rate-limit cooldown — if the phone recently told us to slow down,
    // don't even try. Hammering during cooldown is what causes repeated
    // RateLimited errors.
    if (_rateLimitedUntil && Date.now() < _rateLimitedUntil) {
      const waitS = Math.ceil((_rateLimitedUntil - Date.now()) / 1000);
      console.log(`[Tempo] Sync skipped — rate-limit cooldown active (${waitS}s remaining)`);
      _lastSyncResult = `Waiting ${waitS}s before retrying (phone rate limit)`;
      _lastSyncTime = new Date().toISOString();
      return 0;
    }

    if (!pairing.authToken) {
      throw new SyncError('Auth token not available — please re-pair from the popup (session may have expired)', 'auth');
    }

    // 2. Retry any previously failed plays first
    const retried = await storage.retryFailedPlays();
    if (retried > 0) {
      console.log(`[Tempo] Retrying ${retried} previously failed plays`);
    }

    // 3. Get queued plays (includes retried ones)
    const allPlays = await storage.getQueuedPlays();
    queuedIdsForFailure = allPlays.filter(p => p.id != null).map(p => p.id!);
    if (allPlays.length === 0) {
      _lastSyncResult = 'No plays to sync';
      _lastSyncTime = new Date().toISOString();
      return 0;
    }

    // 4. Resolve phone address
    const address = await resolvePhoneAddress(pairing, options);
    if (!address) {
      throw new SyncError(
        `Cannot reach phone at ${pairing.phoneIp}:${pairing.phonePort}`,
        'unreachable',
      );
    }

    // 4a. Sync resolved address back into local pairing so any subsequent
    //     next_token write doesn't overwrite a gateway-resolved IP with the old one.
    if (address.ip !== pairing.phoneIp || address.port !== pairing.phonePort) {
      pairing = { ...pairing, phoneIp: address.ip, phonePort: address.port };
    }

    // 4b. Verify host permission exists for this origin
    const origin = `http://${address.ip}:${address.port}/`;
    const hasPermission = await hasHostPermission(origin);
    if (!hasPermission) {
      console.warn(`[Tempo] Missing host permission for ${origin} — re-pair from popup to grant it`);
      throw new SyncError(
        `Missing network permission for ${address.ip}. Open the extension popup and re-pair to grant access.`,
        'unknown',
      );
    }

    // 5. Check phone battery
    const batteryOk = await checkPhoneBattery(address.ip, address.port, pairing.authToken);
    if (!batteryOk) {
      throw new SyncError('Phone battery is critically low, sync postponed', 'battery');
    }

    // 6. Check for stale checkpoint from previous hibernation
    const checkpoint = await storage.getSyncCheckpoint();
    if (checkpoint && Date.now() - checkpoint.lastAttempt < 120_000) {
      console.warn(
        `[Tempo] Resuming from sync checkpoint (batch ${checkpoint.batchIndex + 1}/${checkpoint.totalBatches}). ` +
        `${checkpoint.batchIds.length} plays from previous batch may duplicate — phone dedup handles this.`,
      );
      await storage.clearSyncCheckpoint();
    }

    // 7. Batch sync — adaptive batch size based on network conditions
    const batchSize = getAdaptiveBatchSize();
    let totalSynced = 0;
    const batches = Math.ceil(allPlays.length / batchSize);

    for (let i = 0; i < batches; i++) {
      const batch = allPlays.slice(i * batchSize, (i + 1) * batchSize);

      const deviceName = 'Tempo Stats (Browser)';
      const payload: SyncPayload = {
        auth_token: pairing.authToken,
        device_name: deviceName,
        plays: await Promise.all(batch.map(async p => ({
          ...await getDriveLanOrigin(p),
          origin_source: `browser:${p.sourceApp}`,
          title: p.title,
          artist: p.artist,
          album: p.album,
          timestamp_utc: p.timestampUtc,
          duration_ms: p.durationMs,
          source_app: p.sourceApp,
          listened_ms: p.listenedMs,
          skipped: p.skipped,
          replay_count: p.replayCount,
          is_muted: p.isMuted,
          completion_percentage: p.completionPercentage,
          pause_count: p.pauseCount,
          seek_count: p.seekCount,
          session_id: p.sessionId,
          site: p.site,
          content_type: p.contentType,
          volume_level: p.volumeLevel,
          // Anomaly detection data
          anomalies: p.anomalies ?? [],
          total_pause_duration_ms: p.totalPauseDurationMs ?? 0,
          position_updates_count: p.positionUpdatesCount ?? 0,
        }))),
      };

      const url = `http://${address.ip}:${address.port}/api/plays`;
      console.log(`[Tempo] Syncing batch ${i + 1}/${batches} (${batch.length} plays) to ${url}`);

      const batchIds = batch.filter(p => p.id != null).map(p => p.id!);
      await storage.saveSyncCheckpoint({
        batchIds,
        batchIndex: i,
        totalBatches: batches,
        lastAttempt: Date.now(),
        retryCount: 0,
      });

      const response = await sendWithRetry(url, payload, pairing.authToken);

      // Token rotation: if the phone sent a next_token, update stored pairing
      // Uses token lock to prevent race with concurrent refreshToken()
      const nextToken = response?.next_token;
      if (nextToken && pairing) {
        const currentPairing = pairing;
        await withTokenLock(async () => {
          console.log('[Tempo] Auth token rotated by phone');
          const updated = { ...currentPairing, authToken: nextToken };
          await storage.savePairing(updated);
        });
        pairing = { ...pairing, authToken: nextToken };
      }

      if (response && response.ok !== false) {
        await storage.clearSyncCheckpoint();
        await storage.markPlaysSynced(batchIds);
        const syncedSet = new Set(batchIds);
        queuedIdsForFailure = queuedIdsForFailure.filter(id => !syncedSet.has(id));
        totalSynced += batch.length;
      } else {
        throw new SyncError(`Phone rejected batch ${i + 1}: server returned ok=false`, 'rejected');
      }
    }

    await storage.clearSyncCheckpoint();

    // 7. Record success — reset all backoff state
    await storage.recordSync(totalSynced, 'success', null);
    _lastSyncResult = `Synced ${totalSynced} plays`;
    _lastSyncTime = new Date().toISOString();
    _lastErrorKind = null;
    _lastErrorStatus = null;
    _consecutiveFailures = 0;
    _rateLimitedUntil = null;
    _nextRetryAt = null;
    await chrome.alarms.clear(RETRY_ALARM_NAME);
    await persistBackoffState();

    // 8. Cleanup old records
    await storage.cleanupOldRecords();
    await storage.enforceMaxRecords();

    console.log(`[Tempo] Sync successful: ${totalSynced} plays`);
    return totalSynced;

  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    const kind: SyncErrorKind = error instanceof SyncError ? error.kind : 'unknown';
    const httpStatus = error instanceof SyncError ? error.httpStatus : undefined;
    const transient = isTransientSyncError(kind);

    _lastSyncResult = `Failed: ${msg}`;
    _lastSyncTime = new Date().toISOString();
    _lastErrorKind = kind;
    _lastErrorStatus = httpStatus ?? null;
    _consecutiveFailures++;

    try {
      if (kind === 'rate_limited') {
        // Honor Retry-After; default to 60s. Plays stay queued — this is a
        // "slow down" signal, not a failure.
        const cooldownMs = Math.max(
          (error instanceof SyncError ? error.retryAfterMs : undefined) ?? DEFAULT_RATE_LIMIT_COOLDOWN_MS,
          MIN_RATE_LIMIT_COOLDOWN_MS,
        );
        _rateLimitedUntil = Date.now() + cooldownMs;
        const delayMin = Math.max(cooldownMs / 60_000, 0.5);
        await scheduleRetryAlarm(delayMin);
        console.warn(`[Tempo] Rate limited — cooling down ${Math.round(cooldownMs / 1000)}s, plays stay queued`);
      } else if (kind === 'auth') {
        // Stale token: try refreshing once, then retry soon. Plays stay queued.
        await refreshToken();
        await scheduleRetryAlarm(2);
        console.warn('[Tempo] Auth failure — token refreshed, retry scheduled');
      } else if (transient) {
        // Network/server/unreachable/battery: plays stay queued, exponential backoff.
        const delayMin = backoffDelayMinutes(_consecutiveFailures);
        await scheduleRetryAlarm(delayMin);
        console.warn(`[Tempo] Transient sync failure (${kind}) — retry in ${delayMin} min, plays stay queued`);
      } else {
        // Fatal (rejected/unknown): mark plays failed so the user can act.
        // Reuse the pre-batch id snapshot (already-synced ids removed); only
        // fall back to a fresh collection read when the snapshot is empty.
        const ids = queuedIdsForFailure.length > 0
          ? queuedIdsForFailure
          : (await storage.getQueuedPlays()).filter(p => p.id != null).map(p => p.id!);
        await storage.markPlaysFailed(ids);
        await scheduleRetryAlarm(10);
      }
    } catch { /* ignore — status already recorded */ }

    await persistBackoffState();

    await storage.recordSync(0, 'failed', msg);
    console.error(`[Tempo] Sync failed (${kind}): ${msg}`);
    throw error;
  } finally {
    _isSyncing = false;
  }
}

async function sendWithRetry(url: string, payload: SyncPayload, authToken: string): Promise<SyncResponse | null> {
  const payloadJson = JSON.stringify(payload);
  const encryptedBody = await encryptBody(payloadJson, authToken);

  let delayMs = INITIAL_RETRY_DELAY_MS;
  let lastError: SyncError | null = null;

  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

      const signatureHeaders = await signRequest(authToken, encryptedBody);

      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        ...signatureHeaders,
        'X-Tempo-Encrypted': '1',
        'X-Tempo-Compressed': '1',
      };

      const response = await fetch(url, {
        method: 'POST',
        headers,
        body: encryptedBody,
        signal: controller.signal,
      });

      clearTimeout(timeout);

      if (response.ok) {
        // An HTTP 200 is NOT a durable acknowledgment unless the phone
        // explicitly confirms the batch. A truncated/encrypted/invalid body
        // must keep every queued play eligible for retry.
        let data: SyncResponse;
        try {
          const isEncrypted = response.headers.get('X-Tempo-Encrypted') === '1';
          const responseText = await response.text();
          const decryptedText = isEncrypted
            ? await decryptBody(responseText, authToken)
            : responseText;
          data = parseLanAcknowledgment(decryptedText);
        } catch (error) {
          throw new SyncError(
            'Phone returned an unreadable LAN acknowledgment; keeping plays queued',
            'network',
            response.status,
          );
        }
        return data;
      }

      const body = await response.text();
      const bodyLower = body.toLowerCase();

      // Classify the failure

      if (bodyLower.includes('battery_critical')) {
        throw new SyncError('Phone battery is critically low', 'battery', response.status);
      }

      // Rate limiting (HTTP 429 or RateLimited in body). Transient — honor
      // Retry-After and back off at the sync level; do NOT retry immediately,
      // that is exactly what triggers repeated rate limiting.
      if (response.status === 429 || bodyLower.includes('ratelimited') || bodyLower.includes('rate_limited')) {
        const retryAfterMs = parseRetryAfterMs(response.headers.get('Retry-After'));
        throw new SyncError(
          `Rate limited by phone (HTTP ${response.status})`,
          'rate_limited',
          response.status,
          retryAfterMs,
        );
      }

      // Auth failure — token is stale/rotated. Retrying with the same token is
      // pointless; the sync layer refreshes the token and reschedules.
      if (response.status === 401 || response.status === 403) {
        throw new SyncError(
          `Phone rejected auth (HTTP ${response.status}) — token may be stale`,
          'auth',
          response.status,
        );
      }

      // Other 4xx — the phone actively rejected the payload. Fatal for this batch.
      if (response.status >= 400 && response.status < 500) {
        throw new SyncError(
          `Phone rejected payload: HTTP ${response.status} - ${body.slice(0, 200)}`,
          'rejected',
          response.status,
        );
      }

      // 5xx — transient server-side failure (e.g. 502 Bad Gateway). Retry.
      lastError = new SyncError(`HTTP ${response.status}: ${body.slice(0, 200)}`, 'server', response.status);
    } catch (e) {
      // Classified errors propagate immediately (no point retrying the same way).
      if (e instanceof SyncError) throw e;

      const msg = e instanceof Error ? e.message : String(e);
      const isAbort = msg.includes('abort') || msg.includes('TimeoutError');
      lastError = new SyncError(
        isAbort ? 'Request timed out' : msg,
        isAbort ? 'unreachable' : 'network',
      );
    }

    if (attempt < MAX_RETRIES) {
      console.log(`[Tempo] Attempt ${attempt}/${MAX_RETRIES} failed (${lastError?.kind}), retrying in ${delayMs}ms...`);
      await new Promise(resolve => setTimeout(resolve, delayMs));
      delayMs *= 2;
    }
  }

  throw new SyncError(
    `All ${MAX_RETRIES} attempts failed: ${lastError?.message ?? 'unknown error'}`,
    lastError?.kind ?? 'unknown',
    lastError?.httpStatus,
  );
}

// Alarm-based retry scheduling (hibernation-safe)

/**
 * Schedule a retry sync alarm. Uses chrome.alarms which survive
 * service worker hibernation instead of setTimeout.
 */
export async function scheduleRetryAlarm(delayMinutes: number): Promise<void> {
  // Clear any existing retry alarm
  await chrome.alarms.clear(RETRY_ALARM_NAME);

  const clamped = Math.max(delayMinutes, 0.5); // Minimum 30 seconds
  chrome.alarms.create(RETRY_ALARM_NAME, {
    delayInMinutes: clamped,
  });
  _nextRetryAt = Date.now() + clamped * 60_000;
  console.log(`[Tempo] Retry sync alarm scheduled in ${clamped} minutes`);
}

/**
 * Recreate a periodic alarm only when its period actually changed. The old
 * clear+create churn reset the alarm on every sync; an unchanged period is a
 * no-op.
 */
async function ensurePeriodicAlarm(alarmName: string, intervalMinutes: number): Promise<void> {
  const existing = await chrome.alarms.get(alarmName);
  if (
    existing &&
    typeof existing.periodInMinutes === 'number' &&
    Math.abs(existing.periodInMinutes - intervalMinutes) < 0.01
  ) {
    return;
  }

  await chrome.alarms.clear(alarmName);
  chrome.alarms.create(alarmName, { periodInMinutes: intervalMinutes });
}

/**
 * Set up the auto-sync alarm with adaptive interval. Skipped entirely when
 * offline or unpaired — an unpaired install must never arm phone-contacting
 * alarms that wake the service worker just to no-op.
 */
export async function initAutoSync(): Promise<void> {
  const settings = await storage.getSettings();

  if (settings.offlineMode) {
    await chrome.alarms.clear(SYNC_ALARM_NAME);
    console.log('[Tempo] Offline mode enabled, auto-sync disabled');
    return;
  }

  if (!(await storage.getPairing())) {
    await chrome.alarms.clear(SYNC_ALARM_NAME);
    console.log('[Tempo] Auto-sync disabled — no paired device');
    return;
  }


  const queueCount = await storage.getQueueCount();
  const interval = getAdaptiveSyncInterval(queueCount, settings.syncIntervalMinutes);

  await ensurePeriodicAlarm(SYNC_ALARM_NAME, interval);

  console.log(`[Tempo] Auto-sync alarm set: every ${interval} minutes (queue=${queueCount}, base=${settings.syncIntervalMinutes})`);
}

/**
 * Re-adjust the sync alarm interval based on current queue size.
 * Called after each sync to adapt to changing activity levels.
 */
export async function adjustSyncInterval(): Promise<void> {
  const settings = await storage.getSettings();
  if (settings.offlineMode || !(await storage.getPairing())) return;
  const queueCount = await storage.getQueueCount();
  const interval = getAdaptiveSyncInterval(queueCount, settings.syncIntervalMinutes);

  await ensurePeriodicAlarm(SYNC_ALARM_NAME, interval);
}

/**
 * Set up the pairing heartbeat alarm.
 */
export async function initHeartbeat(): Promise<void> {
  await chrome.alarms.clear(HEARTBEAT_ALARM_NAME);

  const settings = await storage.getSettings();
  if (settings.offlineMode) {
    console.log('[Tempo] Pairing heartbeat disabled — offline mode');
    return;
  }

  const pairing = await storage.getPairing();
  if (!pairing) return;

  console.log(`[Tempo] Pairing heartbeat alarm set: every ${HEARTBEAT_INTERVAL_MINUTES} minutes`);
}

/**
 * Set up the token refresh alarm.
 */
export async function initTokenRefresh(): Promise<void> {
  await chrome.alarms.clear(TOKEN_REFRESH_ALARM_NAME);

  const settings = await storage.getSettings();
  if (settings.offlineMode) {
    console.log('[Tempo] Token refresh disabled — offline mode');
    return;
  }

  const pairing = await storage.getPairing();
  if (!pairing) return;

  chrome.alarms.create(TOKEN_REFRESH_ALARM_NAME, {
    periodInMinutes: TOKEN_REFRESH_INTERVAL_MINUTES,
  });

  console.log(`[Tempo] Token refresh alarm set: every ${TOKEN_REFRESH_INTERVAL_MINUTES} minutes`);
}

/**
 * Execute a pairing heartbeat — lightweight ping independent of sync.
 *
 * When the WebSocket is Connected, its 25s signed pings already prove both
 * reachability and auth (the phone rejects bad tokens at the handshake), so
 * the HTTP round-trip — a full service-worker radio wakeup, 288/day — is
 * skipped and only health bookkeeping runs. Disconnected states (Firefox,
 * suspended idle socket) keep the classic HTTP ping. Offline mode is a hard
 * no-op — zero phone traffic, period.
 */
export async function executeHeartbeat(socketConnected = false): Promise<{ invalidated: boolean }> {
  const pairing = await storage.getPairing();
  if (!pairing || !pairing.phoneIp) return { invalidated: false };

  if ((await storage.getSettings()).offlineMode) return { invalidated: false };

  if (socketConnected) {
    await storage.recordHealthPing(true);
    return { invalidated: false };
  }

  const result = await pingPhone(pairing.phoneIp, pairing.phonePort, pairing.authToken, 3_000);

  if (result.authFailed) {
    const health = await storage.recordAuthFailure();
    console.warn(`[Tempo] Heartbeat auth failure (${health.consecutiveAuthFailures} consecutive)`);

    if (health.consecutiveAuthFailures >= AUTH_FAILURE_THRESHOLD) {
      console.error('[Tempo] Repeated auth failures — pairing invalidated');
      await storage.removePairing();
      await storage.clearConnectionHistory();
      return { invalidated: true };
    }
    return { invalidated: false };
  }

  const health = await storage.recordHealthPing(result.ok);

  if (!result.ok) {
    console.warn(`[Tempo] Heartbeat failed (${health.consecutiveFailures} consecutive)`);
  }

  return { invalidated: false };
}

/**
 * Refresh the auth token independent of sync.
 * Uses token lock to prevent race with concurrent sync token rotation.
 */
export async function refreshToken(): Promise<void> {
  return withTokenLock(async () => {
    const pairing = await storage.getPairing();
    if (!pairing || !pairing.phoneIp || !pairing.authToken) return;
    if ((await storage.getSettings()).offlineMode) return;

    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 5_000);
      const headers = await signRequest(pairing.authToken);

      const response = await fetch(`http://${pairing.phoneIp}:${pairing.phonePort}/api/auth/refresh`, {
        method: 'POST',
        headers,
        signal: controller.signal,
      });
      clearTimeout(timeout);

      if (response.ok) {
        const data = await response.json();
        if (data.next_token) {
          console.log('[Tempo] Auth token refreshed via dedicated endpoint');
          await storage.savePairing({ ...pairing, authToken: data.next_token });
        }
      }
    } catch {
      console.warn('[Tempo] Token refresh failed — will retry on next alarm');
    }
  });
}

// Export alarm names for service worker listener setup

export { SYNC_ALARM_NAME, RETRY_ALARM_NAME, HEARTBEAT_ALARM_NAME, TOKEN_REFRESH_ALARM_NAME };

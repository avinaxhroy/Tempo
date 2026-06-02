// ============================================================================
// Tempo Stats — Sync Engine
// Sends batched plays to the Tempo Android app over local WiFi / hotspot.
// Uses HMAC-SHA256 exactly like the desktop Rust implementation.
// Includes retry for failed plays and alarm-based retry scheduling
// to survive service worker hibernation.
// ============================================================================

import type { Play, SyncPayload, SyncPlay, SyncResponse, PairingInfo, ConnectionHistoryEntry } from '../shared/types';
import * as storage from './storage';
import { signRequest, buildJsonHeaders, encryptBody, decryptBody } from '../shared/security';

const IS_FIREFOX = typeof navigator !== 'undefined' && navigator.userAgent.includes('Firefox');

// ---- Constants (match desktop/src-tauri/src/network/mod.rs) ----------------

const MAX_RETRIES = 3;
const INITIAL_RETRY_DELAY_MS = 1_000;
const REQUEST_TIMEOUT_MS = 10_000;
const MAX_BATCH_SIZE = 50;
const DISCOVERY_PING_TIMEOUT_MS = 900;
const SUBNET_SCAN_BATCH_SIZE = 48;
const SUBNET_RESCAN_COOLDOWN_MS = 30 * 60 * 1000;

const SYNC_ALARM_NAME = 'tempo-stats-auto-sync';
const RETRY_ALARM_NAME = 'tempo-stats-retry-sync';
const HEARTBEAT_ALARM_NAME = 'tempo-pairing-heartbeat';
const HEARTBEAT_INTERVAL_MINUTES = 5;
const TOKEN_REFRESH_ALARM_NAME = 'tempo-token-refresh';
const TOKEN_REFRESH_INTERVAL_MINUTES = 60;
const AUTH_FAILURE_THRESHOLD = 3;

// ---- Adaptive sync --------------------------------------------------------

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


// ---- Token lock (prevents concurrent token read/write races) ---------------

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

// ---- Sync Status -----------------------------------------------------------

export interface SyncStatus {
  lastSyncTime: string | null;
  lastSyncResult: string | null;
  isSyncing: boolean;
  queueCount: number;
}

let _isSyncing = false;
let _lastSyncTime: string | null = null;
let _lastSyncResult: string | null = null;
let _lastSubnetScanAt = 0;

export interface SyncOptions {
  forceDiscovery?: boolean;
}

export function getSyncStatus(queueCount: number): SyncStatus {
  return {
    lastSyncTime: _lastSyncTime,
    lastSyncResult: _lastSyncResult,
    isSyncing: _isSyncing,
    queueCount,
  };
}

// ---- Host permission helpers -----------------------------------------------
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

// ---- Phone discovery helpers -----------------------------------------------

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

async function findPhoneViaConnectionHistory(pairing: PairingInfo): Promise<string | null> {
  const history = await storage.getConnectionHistory();
  if (history.length === 0) return null;

  const sorted = [...history].sort((a, b) => b.successCount - a.successCount || b.lastSeen - a.lastSeen);

  console.log(`[Tempo] Trying ${sorted.length} IPs from connection history`);

  for (const entry of sorted.slice(0, 5)) {
    if (entry.ip === pairing.phoneIp) continue;
    const reachable = (await pingPhone(entry.ip, entry.port || pairing.phonePort, pairing.authToken, 2_000)).ok;
    if (reachable) {
      console.log(`[Tempo] Found phone via connection history: ${entry.ip}`);
      return entry.ip;
    }
    await storage.recordConnectionFailure(entry.ip, entry.port || pairing.phonePort);
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

async function resolvePhoneAddress(pairing: PairingInfo, options: SyncOptions = {}): Promise<{ ip: string; port: number } | null> {
  const fingerprint = getNetworkFingerprint();

  if (pairing.phoneIp) {
    const reachable = (await pingPhone(pairing.phoneIp, pairing.phonePort, pairing.authToken)).ok;
    if (reachable) {
      await storage.recordConnectionSuccess(pairing.phoneIp, pairing.phonePort, fingerprint);
      return { ip: pairing.phoneIp, port: pairing.phonePort };
    }
    await storage.recordConnectionFailure(pairing.phoneIp, pairing.phonePort);
    console.log(`[Tempo] Stored address ${pairing.phoneIp} unreachable, trying recovery paths...`);
  }

  const historyIp = await findPhoneViaConnectionHistory(pairing);
  if (historyIp) {
    await storage.savePairing({ ...pairing, phoneIp: historyIp });
    await storage.recordConnectionSuccess(historyIp, pairing.phonePort, fingerprint);
    return { ip: historyIp, port: pairing.phonePort };
  }

  for (const gatewayIp of HOTSPOT_GATEWAY_IPS) {
    if (gatewayIp === pairing.phoneIp) continue;
    const reachable = (await pingPhone(gatewayIp, pairing.phonePort, pairing.authToken)).ok;
    if (reachable) {
      console.log(`[Tempo] Found phone at hotspot gateway ${gatewayIp}`);
      await storage.savePairing({ ...pairing, phoneIp: gatewayIp });
      await storage.recordConnectionSuccess(gatewayIp, pairing.phonePort, fingerprint);
      return { ip: gatewayIp, port: pairing.phonePort };
    }
  }

  const sameSubnetIp = await findPhoneOnStoredSubnet(pairing, options.forceDiscovery === true);
  if (sameSubnetIp) {
    console.log(`[Tempo] Found phone at new same-subnet address ${sameSubnetIp}`);
    await storage.savePairing({ ...pairing, phoneIp: sameSubnetIp });
    await storage.recordConnectionSuccess(sameSubnetIp, pairing.phonePort, fingerprint);
    return { ip: sameSubnetIp, port: pairing.phonePort };
  }

  const mdnsResult = await pingPhoneViaMdns(pairing.phonePort, pairing.authToken);
  if (mdnsResult) {
    console.log(`[Tempo] Found phone via mDNS (${MDNS_HOSTNAME}) — updating stored address`);
    await storage.savePairing({ ...pairing, phoneIp: MDNS_HOSTNAME });
    await storage.recordConnectionSuccess(MDNS_HOSTNAME, pairing.phonePort, fingerprint);
    return { ip: MDNS_HOSTNAME, port: pairing.phonePort };
  }

  console.warn('[Tempo] Phone not reachable via stored IP, connection history, mDNS, hotspot seeds, or stored subnet scan. Open popup to re-discover.');
  return null;
}


// ---- Sync engine -----------------------------------------------------------

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

  try {
    // 1. Get pairing info
    let pairing = await storage.getPairing();
    if (!pairing) {
      throw new Error('Not paired with any device');
    }

    if (!pairing.authToken) {
      throw new Error('Auth token not available — please re-pair from the popup (session may have expired)');
    }

    // 2. Retry any previously failed plays first
    const retried = await storage.retryFailedPlays();
    if (retried > 0) {
      console.log(`[Tempo] Retrying ${retried} previously failed plays`);
    }

    // 3. Get queued plays (includes retried ones)
    const allPlays = await storage.getQueuedPlays();
    if (allPlays.length === 0) {
      _lastSyncResult = 'No plays to sync';
      _lastSyncTime = new Date().toISOString();
      return 0;
    }

    // 4. Resolve phone address
    const address = await resolvePhoneAddress(pairing, options);
    if (!address) {
      throw new Error(`Cannot reach phone at ${pairing.phoneIp}:${pairing.phonePort}`);
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
      throw new Error(`Missing network permission for ${address.ip}. Open the extension popup and re-pair to grant access.`);
    }

    // 5. Check phone battery
    const batteryOk = await checkPhoneBattery(address.ip, address.port, pairing.authToken);
    if (!batteryOk) {
      throw new Error('Phone battery is critically low, sync postponed');
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
        plays: batch.map(p => ({
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
        })),
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
        totalSynced += batch.length;
      } else {
        throw new Error(`Phone rejected batch ${i + 1}: server returned ok=false`);
      }
    }

    await storage.clearSyncCheckpoint();

    // 7. Record success
    await storage.recordSync(totalSynced, 'success', null);
    _lastSyncResult = `Synced ${totalSynced} plays`;
    _lastSyncTime = new Date().toISOString();

    // 8. Cleanup old records
    await storage.cleanupOldRecords();
    await storage.enforceMaxRecords();

    console.log(`[Tempo] Sync successful: ${totalSynced} plays`);
    return totalSynced;

  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    _lastSyncResult = `Failed: ${msg}`;
    _lastSyncTime = new Date().toISOString();

    // Mark all remaining queued plays as failed
    try {
      const plays = await storage.getQueuedPlays();
      const ids = plays.filter(p => p.id != null).map(p => p.id!);
      await storage.markPlaysFailed(ids);

      // Schedule a retry alarm for 5 minutes from now
      await scheduleRetryAlarm(5);
    } catch { /* ignore */ }

    await storage.recordSync(0, 'failed', msg);
    console.error(`[Tempo] Sync failed: ${msg}`);
    throw error;
  } finally {
    _isSyncing = false;
  }
}

async function sendWithRetry(url: string, payload: SyncPayload, authToken: string): Promise<SyncResponse | null> {
  const payloadJson = JSON.stringify(payload);
  const encryptedBody = await encryptBody(payloadJson, authToken);

  let delayMs = INITIAL_RETRY_DELAY_MS;
  let lastError = '';

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
        try {
          const isEncrypted = response.headers.get('X-Tempo-Encrypted') === '1';
          const responseText = await response.text();
          const decryptedText = isEncrypted
            ? await decryptBody(responseText, authToken)
            : responseText;
          const data: SyncResponse = JSON.parse(decryptedText);
          return data;
        } catch {
          return { ok: true };
        }
      }

      const body = await response.text();

      if (body.includes('battery_critical')) {
        throw new Error('Phone battery is critically low');
      }

      if (response.status >= 400 && response.status < 500) {
        throw new Error(`Phone rejected payload: HTTP ${response.status} - ${body}`);
      }

      lastError = `HTTP ${response.status}: ${body}`;
    } catch (e) {
      if (e instanceof Error && e.message.includes('rejected')) throw e;
      if (e instanceof Error && e.message.includes('battery')) throw e;
      lastError = e instanceof Error ? e.message : String(e);
    }

    if (attempt < MAX_RETRIES) {
      console.log(`[Tempo] Attempt ${attempt}/${MAX_RETRIES} failed, retrying in ${delayMs}ms...`);
      await new Promise(resolve => setTimeout(resolve, delayMs));
      delayMs *= 2;
    }
  }

  throw new Error(`All ${MAX_RETRIES} attempts failed: ${lastError}`);
}

// ---- Alarm-based retry scheduling (hibernation-safe) -----------------------

/**
 * Schedule a retry sync alarm. Uses chrome.alarms which survive
 * service worker hibernation instead of setTimeout.
 */
export async function scheduleRetryAlarm(delayMinutes: number): Promise<void> {
  // Clear any existing retry alarm
  await chrome.alarms.clear(RETRY_ALARM_NAME);

  chrome.alarms.create(RETRY_ALARM_NAME, {
    delayInMinutes: Math.max(delayMinutes, 0.5), // Minimum 30 seconds
  });
  console.log(`[Tempo] Retry sync alarm scheduled in ${delayMinutes} minutes`);
}

/**
 * Set up the auto-sync alarm with adaptive interval.
 */
export async function initAutoSync(): Promise<void> {
  const settings = await storage.getSettings();

  await chrome.alarms.clear(SYNC_ALARM_NAME);

  if (settings.offlineMode) {
    console.log('[Tempo] Offline mode enabled, auto-sync disabled');
    return;
  }

  const queueCount = await storage.getQueueCount();
  const interval = getAdaptiveSyncInterval(queueCount, settings.syncIntervalMinutes);

  chrome.alarms.create(SYNC_ALARM_NAME, {
    periodInMinutes: interval,
  });

  console.log(`[Tempo] Auto-sync alarm set: every ${interval} minutes (queue=${queueCount}, base=${settings.syncIntervalMinutes})`);
}

/**
 * Re-adjust the sync alarm interval based on current queue size.
 * Called after each sync to adapt to changing activity levels.
 */
export async function adjustSyncInterval(): Promise<void> {
  const settings = await storage.getSettings();
  if (settings.offlineMode) return;

  const queueCount = await storage.getQueueCount();
  const interval = getAdaptiveSyncInterval(queueCount, settings.syncIntervalMinutes);

  await chrome.alarms.clear(SYNC_ALARM_NAME);
  chrome.alarms.create(SYNC_ALARM_NAME, {
    periodInMinutes: interval,
  });
}

/**
 * Set up the pairing heartbeat alarm.
 */
export async function initHeartbeat(): Promise<void> {
  await chrome.alarms.clear(HEARTBEAT_ALARM_NAME);

  const pairing = await storage.getPairing();
  if (!pairing) return;

  chrome.alarms.create(HEARTBEAT_ALARM_NAME, {
    periodInMinutes: HEARTBEAT_INTERVAL_MINUTES,
  });

  console.log(`[Tempo] Pairing heartbeat alarm set: every ${HEARTBEAT_INTERVAL_MINUTES} minutes`);
}

/**
 * Set up the token refresh alarm.
 */
export async function initTokenRefresh(): Promise<void> {
  await chrome.alarms.clear(TOKEN_REFRESH_ALARM_NAME);

  const pairing = await storage.getPairing();
  if (!pairing) return;

  chrome.alarms.create(TOKEN_REFRESH_ALARM_NAME, {
    periodInMinutes: TOKEN_REFRESH_INTERVAL_MINUTES,
  });

  console.log(`[Tempo] Token refresh alarm set: every ${TOKEN_REFRESH_INTERVAL_MINUTES} minutes`);
}

/**
 * Execute a pairing heartbeat — lightweight ping independent of sync.
 */
export async function executeHeartbeat(): Promise<{ invalidated: boolean }> {
  const pairing = await storage.getPairing();
  if (!pairing || !pairing.phoneIp) return { invalidated: false };

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

// ---- Export alarm names for service worker listener setup -------------------

export { SYNC_ALARM_NAME, RETRY_ALARM_NAME, HEARTBEAT_ALARM_NAME, TOKEN_REFRESH_ALARM_NAME };

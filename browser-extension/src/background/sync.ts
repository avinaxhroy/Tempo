// ============================================================================
// Tempo Stats — Sync Engine
// Sends batched plays to the Tempo Android app over local WiFi / hotspot.
// Uses HMAC-SHA256 exactly like the desktop Rust implementation.
// Includes retry for failed plays and alarm-based retry scheduling
// to survive service worker hibernation.
// ============================================================================

import type { Play, SyncPayload, SyncPlay, SyncResponse, PairingInfo } from '../shared/types';
import * as storage from './storage';
import { signRequest, buildJsonHeaders } from '../shared/security';

// ---- Constants (match desktop/src-tauri/src/network/mod.rs) ----------------

const MAX_RETRIES = 3;
const INITIAL_RETRY_DELAY_MS = 1_000;
const REQUEST_TIMEOUT_MS = 10_000;
const MAX_BATCH_SIZE = 50;
const MAX_PLAYS_PER_BATCH = 100;
const DISCOVERY_PING_TIMEOUT_MS = 900;
const SUBNET_SCAN_BATCH_SIZE = 48;
const SUBNET_RESCAN_COOLDOWN_MS = 30 * 60 * 1000;

const SYNC_ALARM_NAME = 'tempo-stats-auto-sync';
const RETRY_ALARM_NAME = 'tempo-stats-retry-sync';


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

async function pingPhone(ip: string, port: number, authToken?: string, timeoutMs = 3_000): Promise<boolean> {
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
    return response.ok;
  } catch {
    return false;
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

async function findPhoneOnStoredSubnet(pairing: PairingInfo, forceDiscovery = false): Promise<string | null> {
  const subnet = getPrivateIpv4Subnet(pairing.phoneIp);
  if (!subnet) return null;

  const now = Date.now();
  if (!forceDiscovery && now - _lastSubnetScanAt < SUBNET_RESCAN_COOLDOWN_MS) {
    console.log('[Tempo] Skipping same-subnet phone scan; recent recovery scan already ran');
    return null;
  }
  _lastSubnetScanAt = now;

  const candidates: string[] = [];
  for (let i = 1; i <= 254; i++) {
    const ip = `${subnet}.${i}`;
    if (ip !== pairing.phoneIp) candidates.push(ip);
  }

  console.log(`[Tempo] Scanning ${subnet}.x for phone after stored IP failed`);

  for (let start = 0; start < candidates.length; start += SUBNET_SCAN_BATCH_SIZE) {
    const batch = candidates.slice(start, start + SUBNET_SCAN_BATCH_SIZE);
    const results = await Promise.all(
      batch.map(async ip => ({
        ip,
        reachable: await pingPhone(ip, pairing.phonePort, pairing.authToken, DISCOVERY_PING_TIMEOUT_MS),
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

async function resolvePhoneAddress(pairing: PairingInfo, options: SyncOptions = {}): Promise<{ ip: string; port: number } | null> {
  // Strategy 1: Stored IP (or hostname) — fastest path, works when IP is stable
  if (pairing.phoneIp) {
    const reachable = await pingPhone(pairing.phoneIp, pairing.phonePort, pairing.authToken);
    if (reachable) {
      return { ip: pairing.phoneIp, port: pairing.phonePort };
    }
    console.log(`[Tempo] Stored address ${pairing.phoneIp} unreachable, trying recovery paths...`);
  }

  // Strategy 2: Hotspot seeds — works when phone IS the WiFi gateway
  for (const gatewayIp of HOTSPOT_GATEWAY_IPS) {
    if (gatewayIp === pairing.phoneIp) continue;
    const reachable = await pingPhone(gatewayIp, pairing.phonePort, pairing.authToken);
    if (reachable) {
      console.log(`[Tempo] Found phone at hotspot gateway ${gatewayIp}`);
      await storage.savePairing({ ...pairing, phoneIp: gatewayIp });
      return { ip: gatewayIp, port: pairing.phonePort };
    }
  }

  // Strategy 3: Same-subnet repair — handles the common DHCP case where the
  // phone IP changed but both devices are still on the same WiFi.
  const sameSubnetIp = await findPhoneOnStoredSubnet(pairing, options.forceDiscovery === true);
  if (sameSubnetIp) {
    console.log(`[Tempo] Found phone at new same-subnet address ${sameSubnetIp}`);
    await storage.savePairing({ ...pairing, phoneIp: sameSubnetIp });
    return { ip: sameSubnetIp, port: pairing.phonePort };
  }

  // Strategy 4: Best-effort hostname — useful on networks that expose it, but
  // not reliable enough to be the only IP-change recovery path.
  const mdnsResult = await pingPhoneViaMdns(pairing.phonePort, pairing.authToken);
  if (mdnsResult) {
    console.log(`[Tempo] Found phone via mDNS (${MDNS_HOSTNAME}) — updating stored address`);
    await storage.savePairing({ ...pairing, phoneIp: MDNS_HOSTNAME });
    return { ip: MDNS_HOSTNAME, port: pairing.phonePort };
  }

  // Strategy 5: Different network/subnet. A service worker has no WebRTC local
  // adapter discovery, so the popup must run the full current-subnet scan.
  console.warn('[Tempo] Phone not reachable via stored IP, mDNS, hotspot seeds, or stored subnet scan. Open popup to re-discover.');
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

    // 6. Batch sync — send in batches of MAX_BATCH_SIZE
    let totalSynced = 0;
    const batches = Math.ceil(allPlays.length / MAX_BATCH_SIZE);

    for (let i = 0; i < batches; i++) {
      const batch = allPlays.slice(i * MAX_BATCH_SIZE, (i + 1) * MAX_BATCH_SIZE);

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

      const response = await sendWithRetry(url, payload, pairing.authToken);

      // Token rotation: if the phone sent a next_token, update stored pairing
      if (response?.next_token) {
        console.log('[Tempo] Auth token rotated by phone');
        pairing = { ...pairing, authToken: response.next_token };
        await storage.savePairing(pairing);
      }

      // Only mark as synced if the server confirmed success
      if (response && response.ok !== false) {
        const ids = batch.filter(p => p.id != null).map(p => p.id!);
        await storage.markPlaysSynced(ids);
        totalSynced += batch.length;
      } else {
        throw new Error(`Phone rejected batch ${i + 1}: server returned ok=false`);
      }
    }

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

  let delayMs = INITIAL_RETRY_DELAY_MS;
  let lastError = '';

  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

      // buildJsonHeaders signs with timestamp + nonce — unique per attempt,
      // so even retries have distinct signatures.
      const headers = await buildJsonHeaders(authToken, payloadJson);

      const response = await fetch(url, {
        method: 'POST',
        headers,
        body: payloadJson,
        signal: controller.signal,
      });

      clearTimeout(timeout);

      if (response.ok) {
        try {
          const data: SyncResponse = await response.json();
          return data;
        } catch {
          // Response wasn't JSON, return success without next_token
          return { ok: true };
        }
      }

      const body = await response.text();

      // Battery critical — don't retry
      if (body.includes('battery_critical')) {
        throw new Error('Phone battery is critically low');
      }

      // Auth error — don't retry
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
 * Set up the auto-sync alarm.
 */
export async function initAutoSync(): Promise<void> {
  const settings = await storage.getSettings();

  // Clear existing alarms
  await chrome.alarms.clear(SYNC_ALARM_NAME);

  // Don't set alarm if offline mode is on
  if (settings.offlineMode) {
    console.log('[Tempo] Offline mode enabled, auto-sync disabled');
    return;
  }

  // Create periodic alarm
  chrome.alarms.create(SYNC_ALARM_NAME, {
    periodInMinutes: settings.syncIntervalMinutes,
  });

  console.log(`[Tempo] Auto-sync alarm set: every ${settings.syncIntervalMinutes} minutes`);
}

// ---- Export alarm names for service worker listener setup -------------------

export { SYNC_ALARM_NAME, RETRY_ALARM_NAME };

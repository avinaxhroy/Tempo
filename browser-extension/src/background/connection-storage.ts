/** Settings, LAN pairing and connection state stored in chrome.storage. */
import type { PairingInfo, Settings, TabTrackState, ConnectionHistoryEntry, ConnectionHealth, SyncCheckpoint, SyncBackoffState } from '../shared/types';
import { DEFAULT_SETTINGS } from '../shared/types';

interface SettingsStorageResult {
  settings?: Settings;
}

interface PairingStorageRecord {
  phoneIp?: string;
  phonePort?: number;
  authToken?: string;
  deviceName?: string;
  pairedAt?: string | null;
}

interface PairingStorageResult {
  pairing?: PairingStorageRecord;
}

interface SessionTokenStorageResult {
  authToken?: string;
}

interface TrackerStateStorageResult {
  trackerStates?: TabTrackState[];
}

// Settings (chrome.storage.local)

// In-memory settings cache — chrome.storage.local reads are async and relatively
// slow (~1-5ms). Most hot paths only need the settings object, so we cache it
// and invalidate on write.
let _settingsCache: Settings | null = null;
let _settingsCacheTime = 0;
const SETTINGS_CACHE_TTL_MS = 5_000;

export async function getSettings(): Promise<Settings> {
  if (_settingsCache && (Date.now() - _settingsCacheTime) < SETTINGS_CACHE_TTL_MS) {
    return _settingsCache;
  }

  return new Promise<Settings>((resolve) => {
    chrome.storage.local.get('settings', (result) => {
      const storageResult = result as SettingsStorageResult;
      const raw = (storageResult.settings ?? {}) as any;
      const sanitizeArray = (val: any): string[] => {
        if (!Array.isArray(val)) return [];
        return val.filter((item): item is string => typeof item === 'string');
      };
      _settingsCache = {
        ...DEFAULT_SETTINGS,
        ...raw,
        // Cloud transmission is opt-in: only the literal boolean true enables
        // it. Corrupt/legacy/string values must fail closed.
        driveSyncEnabled: raw.driveSyncEnabled === true,
        knownArtists: sanitizeArray(raw.knownArtists),
        youtubeChannels: sanitizeArray(raw.youtubeChannels),
        blockedYoutubeChannels: sanitizeArray(raw.blockedYoutubeChannels),
      };
      _settingsCacheTime = Date.now();
      resolve(_settingsCache as Settings);
    });
  });
}

export async function saveSettings(settings: Settings): Promise<void> {
  _settingsCache = settings;
  _settingsCacheTime = Date.now();
  return new Promise<void>((resolve) => {
    chrome.storage.local.set({ settings }, resolve);
  });
}

// Pairing (chrome.storage.local with session mirroring for compatibility)

export async function getPairing(): Promise<PairingInfo | null> {
  ensurePairingInvalidationListeners();
  if (_pairingCache !== undefined) return _pairingCache;

  const resolved = await new Promise<PairingInfo | null>((resolve) => {
    chrome.storage.local.get('pairing', async (localResult) => {
      const localPairing = (localResult as PairingStorageResult).pairing;
      if (!localPairing) {
        resolve(null);
        return;
      }

      const sessionResult = await chrome.storage.session.get('authToken') as SessionTokenStorageResult;
      const authToken = (localPairing.authToken ?? sessionResult.authToken ?? '').trim();
      if (!authToken) {
        resolve(null);
        return;
      }

      if (!localPairing.authToken && authToken) {
        await chrome.storage.local.set({ pairing: { ...localPairing, authToken } });
      }

      resolve({
        phoneIp: localPairing.phoneIp ?? '',
        phonePort: localPairing.phonePort ?? 8765,
        authToken,
        deviceName: localPairing.deviceName ?? 'Phone',
        pairedAt: localPairing.pairedAt ?? null,
      });
    });
  });

  _pairingCache = resolved;
  return resolved;
}

export async function savePairing(pairing: PairingInfo): Promise<void> {
  const { authToken, ...localData } = pairing;
  await chrome.storage.session.set({ authToken });
  await new Promise<void>((resolve) => {
    chrome.storage.local.set({ pairing: { ...localData, authToken } }, resolve);
  });
  _pairingCache = pairing;
}

export async function removePairing(): Promise<void> {
  _pairingCache = null;
  await chrome.storage.session.remove('authToken');
  await new Promise<void>((resolve) => {
    chrome.storage.local.remove('pairing', resolve);
  });
}

// In-memory pairing cache — getPairing() is on the hot path (every socket
// send, heartbeat, and sync) and normally costs a chrome.storage.local read
// plus a chrome.storage.session read. We cache the resolved value and
// invalidate on every write path plus chrome.storage.onChanged, so external
// writers (other extension contexts) never see stale pairing.

let _pairingCache: PairingInfo | null | undefined; // undefined = uncached
let _pairingListenersRegistered = false;

function ensurePairingInvalidationListeners(): void {
  if (_pairingListenersRegistered) return;
  _pairingListenersRegistered = true;
  try {
    chrome.storage.onChanged.addListener((changes, area) => {
      if (area === 'local' && changes['pairing']) {
        _pairingCache = undefined;
      } else if (area === 'session' && changes['authToken']) {
        _pairingCache = undefined;
      }
    });
  } catch { /* non-critical */ }
}

// Session State (chrome.storage.session — survives hibernation)

export async function saveSessionState(states: TabTrackState[]): Promise<void> {
  return new Promise<void>((resolve) => {
    chrome.storage.session.set({ trackerStates: states }, resolve);
  });
}

export async function loadSessionState(): Promise<TabTrackState[]> {
  return new Promise<TabTrackState[]>((resolve) => {
    chrome.storage.session.get('trackerStates', (result) => {
      const storageResult = result as TrackerStateStorageResult;
      resolve(storageResult.trackerStates ?? []);
    });
  });
}

// Connection History (chrome.storage.local)

interface ConnectionHistoryStorageResult {
  connectionHistory?: ConnectionHistoryEntry[];
}

interface ConnectionHealthStorageResult {
  connectionHealth?: ConnectionHealth;
}

interface SyncCheckpointStorageResult {
  syncCheckpoint?: SyncCheckpoint;
}

const MAX_CONNECTION_HISTORY = 20;

export async function getConnectionHistory(): Promise<ConnectionHistoryEntry[]> {
  return new Promise<ConnectionHistoryEntry[]>((resolve) => {
    chrome.storage.local.get('connectionHistory', (result) => {
      const entries = (result as ConnectionHistoryStorageResult).connectionHistory;
      resolve(Array.isArray(entries) ? entries : []);
    });
  });
}

export async function recordConnectionSuccess(ip: string, port: number, networkFingerprint: string): Promise<void> {
  const entries = await getConnectionHistory();
  const now = Date.now();

  const existing = entries.find(e => e.ip === ip && e.port === port);
  if (existing) {
    existing.lastSeen = now;
    existing.successCount++;
    existing.failCount = 0;
    existing.networkFingerprint = networkFingerprint;
  } else {
    entries.push({ ip, port, lastSeen: now, successCount: 1, failCount: 0, networkFingerprint });
  }

  entries.sort((a, b) => b.successCount - a.successCount || b.lastSeen - a.lastSeen);
  if (entries.length > MAX_CONNECTION_HISTORY) entries.length = MAX_CONNECTION_HISTORY;

  return new Promise<void>((resolve) => {
    chrome.storage.local.set({ connectionHistory: entries }, resolve);
  });
}

export async function recordConnectionFailure(ip: string, port: number): Promise<void> {
  const entries = await getConnectionHistory();
  const existing = entries.find(e => e.ip === ip && e.port === port);
  if (!existing) return; // Nothing to increment — skip the chrome.storage write entirely.

  existing.failCount++;
  if (existing.failCount > 10 && existing.successCount === 0) {
    entries.splice(entries.indexOf(existing), 1);
  }

  return new Promise<void>((resolve) => {
    chrome.storage.local.set({ connectionHistory: entries }, resolve);
  });
}

/**
 * Batch variant of recordConnectionFailure(): applies the same failCount
 * increments (and eviction of dead never-succeeded entries) for many IPs but
 * performs exactly ONE connectionHistory array rewrite. Entries that do not
 * exist are skipped, matching the single-IP semantics.
 */
export async function recordConnectionFailures(failures: Array<{ ip: string; port: number }>): Promise<void> {
  const pending = failures.filter(f => f.ip);
  if (pending.length === 0) return;

  const entries = await getConnectionHistory();
  let changed = false;

  for (const failure of pending) {
    const existing = entries.find(e => e.ip === failure.ip && e.port === failure.port);
    if (!existing) continue;

    existing.failCount++;
    if (existing.failCount > 10 && existing.successCount === 0) {
      entries.splice(entries.indexOf(existing), 1);
    }
    changed = true;
  }

  if (!changed) return;

  return new Promise<void>((resolve) => {
    chrome.storage.local.set({ connectionHistory: entries }, resolve);
  });
}

export async function clearConnectionHistory(): Promise<void> {
  return new Promise<void>((resolve) => {
    chrome.storage.local.remove('connectionHistory', resolve);
  });
}

// Connection Health (chrome.storage.local)

const DEFAULT_CONNECTION_HEALTH: ConnectionHealth = {
  lastPing: 0,
  healthy: false,
  consecutiveFailures: 0,
  consecutiveAuthFailures: 0,
  lastSuccessAt: null,
};

export async function getConnectionHealth(): Promise<ConnectionHealth> {
  return new Promise<ConnectionHealth>((resolve) => {
    chrome.storage.local.get('connectionHealth', (result) => {
      const health = (result as ConnectionHealthStorageResult).connectionHealth;
      resolve(health ? { ...DEFAULT_CONNECTION_HEALTH, ...health } : { ...DEFAULT_CONNECTION_HEALTH });
    });
  });
}

export async function saveConnectionHealth(health: ConnectionHealth): Promise<void> {
  return new Promise<void>((resolve) => {
    chrome.storage.local.set({ connectionHealth: health }, resolve);
  });
}

export async function recordHealthPing(success: boolean): Promise<ConnectionHealth> {
  const health = await getConnectionHealth();
  const now = Date.now();
  health.lastPing = now;

  if (success) {
    health.healthy = true;
    health.consecutiveFailures = 0;
    health.consecutiveAuthFailures = 0;
    health.lastSuccessAt = now;
  } else {
    health.consecutiveFailures++;
    if (health.consecutiveFailures >= 3) {
      health.healthy = false;
    }
  }

  await saveConnectionHealth(health);
  return health;
}

export async function recordAuthFailure(): Promise<ConnectionHealth> {
  const health = await getConnectionHealth();
  health.consecutiveAuthFailures++;
  await saveConnectionHealth(health);
  return health;
}

// Sync Checkpoint (chrome.storage.session — survives hibernation)

export async function getSyncCheckpoint(): Promise<SyncCheckpoint | null> {
  return new Promise<SyncCheckpoint | null>((resolve) => {
    chrome.storage.session.get('syncCheckpoint', (result) => {
      const checkpoint = (result as SyncCheckpointStorageResult).syncCheckpoint;
      resolve(checkpoint ?? null);
    });
  });
}

export async function saveSyncCheckpoint(checkpoint: SyncCheckpoint): Promise<void> {
  return new Promise<void>((resolve) => {
    chrome.storage.session.set({ syncCheckpoint: checkpoint }, resolve);
  });
}

export async function clearSyncCheckpoint(): Promise<void> {
  return new Promise<void>((resolve) => {
    chrome.storage.session.remove('syncCheckpoint', resolve);
  });
}

// Sync Backoff State (chrome.storage.session — survives hibernation)

interface SyncBackoffStorageResult {
  syncBackoff?: SyncBackoffState;
}

export async function getSyncBackoff(): Promise<SyncBackoffState | null> {
  return new Promise<SyncBackoffState | null>((resolve) => {
    chrome.storage.session.get('syncBackoff', (result) => {
      const backoff = (result as SyncBackoffStorageResult).syncBackoff;
      resolve(backoff ?? null);
    });
  });
}

export async function saveSyncBackoff(backoff: SyncBackoffState): Promise<void> {
  return new Promise<void>((resolve) => {
    chrome.storage.session.set({ syncBackoff: backoff }, resolve);
  });
}


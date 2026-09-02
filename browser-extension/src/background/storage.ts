/**
 * Storage layer combining IndexedDB (for play queues and sync history)
 * with chrome.storage (for pairing, settings, and session state).
 * Keeps an open IndexedDB connection cached to minimize disk I/O overhead.
 */

import type { Play, PairingInfo, Settings, SyncRecord, TabTrackState, ConnectionHistoryEntry, ConnectionHealth, SyncCheckpoint, SyncBackoffState } from '../shared/types';
import { DEFAULT_SETTINGS } from '../shared/types';

const DB_NAME = 'TempoStatsDB';
const DB_VERSION = 2;

// Store names
const PLAYS_STORE = 'plays';
const SYNC_HISTORY_STORE = 'syncHistory';

// Maximum age for synced/failed records before auto-cleanup (7 days)
const MAX_RECORD_AGE_MS = 7 * 24 * 60 * 60 * 1000;
// Maximum number of play records to keep
const MAX_PLAY_RECORDS = 5000;

// Cheap in-memory lower-bound-ish estimate of total plays, used by
// enforceMaxRecords() to skip the store.count() + cursor scan entirely while
// the collection is far below the cap. Starts unknown (+Inf) so the first
// run measures the real value.
let _playCountEstimate = Number.POSITIVE_INFINITY;

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

// Cached IndexedDB Connection
//
// Opening IndexedDB is expensive (disk I/O). We keep a single connection alive
// and reuse it. If the connection is closed (e.g., after quota eviction), we
// transparently reopen.

let _db: IDBDatabase | null = null;
let _dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  // Return cached connection if still open
  if (_db) {
    try {
      // Test if connection is still alive by starting a transaction
      _db.transaction(PLAYS_STORE, 'readonly');
      return Promise.resolve(_db);
    } catch {
      _db = null;
    }
  }

  // Dedup concurrent open requests
  if (_dbPromise) return _dbPromise;

  _dbPromise = new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = () => {
      const db = request.result;

      if (!db.objectStoreNames.contains(PLAYS_STORE)) {
        const store = db.createObjectStore(PLAYS_STORE, { keyPath: 'id', autoIncrement: true });
        store.createIndex('status', 'status', { unique: false });
        store.createIndex('timestampUtc', 'timestampUtc', { unique: false });
      }

      if (!db.objectStoreNames.contains(SYNC_HISTORY_STORE)) {
        const store = db.createObjectStore(SYNC_HISTORY_STORE, { keyPath: 'id', autoIncrement: true });
        store.createIndex('syncedAt', 'syncedAt', { unique: false });
      }
    };

    request.onsuccess = () => {
      const db = request.result;
      // Handle connection being closed externally (e.g., Clear browsing data)
      db.onclose = () => { _db = null; };
      _db = db;
      _dbPromise = null;
      resolve(db);
    };

    request.onerror = () => {
      _dbPromise = null;
      reject(request.error);
    };

    request.onblocked = () => {
      console.warn('[Tempo] IndexedDB upgrade blocked — close other tabs using this database');
      _dbPromise = null;
      reject(new Error('IndexedDB upgrade blocked'));
    };
  });

  return _dbPromise;
}

// In-memory queue count cache (avoids DB read on every badge update)

let _cachedQueueCount: number | null = null;
let _queueCountCacheTime = 0;
const QUEUE_COUNT_CACHE_TTL_MS = 2_000;

function invalidateQueueCountCache(): void {
  _cachedQueueCount = null;
}

// In-memory stats cache — getStats() scans up to 5000 plays; the popup only
// needs fresh-ish numbers, so we memoize the result briefly and clear it on
// every mutation of the underlying stores.

let _statsCache: ExtensionStats | null = null;
let _statsCacheTime = 0;
const STATS_CACHE_TTL_MS = 30_000;

function invalidateStatsCache(): void {
  _statsCache = null;
}

export async function insertPlay(play: Omit<Play, 'id'>): Promise<number> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    const request = store.add(play);
    request.onsuccess = () => {
      invalidateQueueCountCache();
      invalidateStatsCache();
      _playCountEstimate++;
      resolve(request.result as number);
    };
    request.onerror = () => reject(request.error);
  });
}

export async function insertPlaysBatch(plays: Array<Omit<Play, 'id'>>): Promise<number[]> {
  if (plays.length === 0) return [];
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    const ids: number[] = [];
    for (const play of plays) {
      const request = store.add(play);
      request.onsuccess = () => {
        ids.push(request.result as number);
      };
    }
    tx.oncomplete = () => {
      invalidateQueueCountCache();
      invalidateStatsCache();
      _playCountEstimate += ids.length;
      resolve(ids);
    };
    tx.onerror = () => reject(tx.error);
  });
}

export async function getQueuedPlays(): Promise<Play[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const store = tx.objectStore(PLAYS_STORE);
    const index = store.index('status');
    const request = index.getAll('queued');
    request.onsuccess = () => resolve(request.result as Play[]);
    request.onerror = () => reject(request.error);
  });
}

export async function getFailedPlays(): Promise<Play[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const store = tx.objectStore(PLAYS_STORE);
    const index = store.index('status');
    const request = index.getAll('failed');
    request.onsuccess = () => resolve(request.result as Play[]);
    request.onerror = () => reject(request.error);
  });
}

export async function getAllPlays(limit = 100): Promise<Play[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const store = tx.objectStore(PLAYS_STORE);
    const index = store.index('timestampUtc');
    const plays: Play[] = [];
    const request = index.openCursor(null, 'prev');

    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor && plays.length < limit) {
        plays.push(cursor.value as Play);
        cursor.continue();
      } else {
        resolve(plays);
      }
    };
    request.onerror = () => reject(request.error);
  });
}

/**
 * Return locally-owned plays that still need their first Drive upload.
 * Scan oldest-first across the whole store: when Drive has been unavailable
 * for a long time the database may temporarily exceed the normal 5k cap,
 * and those older pending rows must not become unreachable.
 */
export async function getDrivePendingPlays(limit = Number.MAX_SAFE_INTEGER): Promise<Play[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const store = tx.objectStore(PLAYS_STORE);
    const index = store.index('timestampUtc');
    const plays: Play[] = [];
    const request = index.openCursor();

    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor && plays.length < limit) {
        const play = cursor.value as Play;
        if (play.id != null && !play.driveImported && !play.driveUploadedAt) {
          plays.push(play);
        }
        cursor.continue();
      } else {
        resolve(plays);
      }
    };
    request.onerror = () => reject(request.error);
  });
}

/** Mark locally-owned play rows as safely uploaded to Drive. */
export async function markDriveUploaded(ids: number[]): Promise<void> {
  if (ids.length === 0) return;
  const idSet = new Set(ids);
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    const request = store.openCursor();
    const uploadedAt = Date.now();
    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) {
        if (idSet.has(cursor.key as number)) {
          const play = cursor.value as Play;
          play.driveUploadedAt = uploadedAt;
          cursor.update(play);
        }
        cursor.continue();
      }
    };
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Drive upload flag transaction aborted'));
  });
}

/** Clear Drive-upload bookkeeping while preserving all local listening history. */
export async function clearDriveUploadedFlags(): Promise<void> {
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    const request = store.openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) {
        const play = cursor.value as Play;
        if (!play.driveImported && play.driveUploadedAt) {
          delete play.driveUploadedAt;
          cursor.update(play);
        }
        cursor.continue();
      }
    };
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Drive flag reset transaction aborted'));
  });
}

export async function getQueueCount(): Promise<number> {
  // Return cached value if fresh
  if (_cachedQueueCount !== null && (Date.now() - _queueCountCacheTime) < QUEUE_COUNT_CACHE_TTL_MS) {
    return _cachedQueueCount;
  }

  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const store = tx.objectStore(PLAYS_STORE);
    const index = store.index('status');
    const request = index.count('queued');
    request.onsuccess = () => {
      _cachedQueueCount = request.result;
      _queueCountCacheTime = Date.now();
      resolve(request.result);
    };
    request.onerror = () => reject(request.error);
  });
}

/**
 * Flip the status of a set of play records in ONE readwrite cursor pass over
 * the [min(ids), max(ids)] key range instead of N individual get+put pairs.
 */
async function setPlaysStatus(ids: number[], status: Play['status']): Promise<void> {
  if (ids.length === 0) return;

  let minId = Infinity;
  let maxId = -Infinity;
  for (const id of ids) {
    if (id < minId) minId = id;
    if (id > maxId) maxId = id;
  }
  const idSet = new Set(ids);

  const db = await openDb();
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    const request = store.openCursor(IDBKeyRange.bound(minId, maxId));

    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) {
        if (idSet.has(cursor.key as number)) {
          const play = cursor.value as Play;
          play.status = status;
          cursor.update(play);
        }
        cursor.continue();
      }
    };

    tx.oncomplete = () => { invalidateQueueCountCache(); invalidateStatsCache(); resolve(); };
    tx.onerror = () => reject(tx.error);
  });
}

export function markPlaysSynced(ids: number[]): Promise<void> {
  return setPlaysStatus(ids, 'synced');
}

export function markPlaysFailed(ids: number[]): Promise<void> {
  return setPlaysStatus(ids, 'failed');
}

/** Reset failed plays back to queued so they can be retried on next sync. */
export async function retryFailedPlays(): Promise<number> {
  // Reuse the full records from getFailedPlays() — flip the status field in
  // place and put the SAME objects back; no second read inside the transaction.
  const failed = await getFailedPlays();
  const toRetry = failed.filter(p => p.id != null);
  if (toRetry.length === 0) return 0;

  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);

    for (const play of toRetry) {
      play.status = 'queued';
      store.put(play);
    }

    tx.oncomplete = () => { invalidateQueueCountCache(); invalidateStatsCache(); resolve(toRetry.length); };
    tx.onerror = () => reject(tx.error);
  });
}

export async function clearQueue(): Promise<number> {
  const queued = await getQueuedPlays();

  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);

    for (const play of queued) {
      if (play.id) store.delete(play.id);
    }

    tx.oncomplete = () => { invalidateQueueCountCache(); invalidateStatsCache(); _playCountEstimate -= queued.length; resolve(queued.length); };
    tx.onerror = () => reject(tx.error);
  });
}

/** Hard-delete a single play record (any status) by its IndexedDB id. */
export async function deletePlay(id: number): Promise<void> {
  const db = await openDb();
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    store.delete(id);
    tx.oncomplete = () => { invalidateQueueCountCache(); invalidateStatsCache(); _playCountEstimate--; resolve(); };
    tx.onerror = () => reject(tx.error);
  });
}

/**
 * Check whether another capture origin already recorded the same title+artist
 * within ±60s. Exact Drive event IDs are the idempotency key for the same
 * originating device, so a different event from that same device must remain a
 * distinct replay even when it happens inside this wider temporal window.
 */
export async function hasRecentPlay(
  title: string,
  artist: string,
  timestampUtc: number,
  incomingOriginDeviceId?: string,
): Promise<boolean> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const store = tx.objectStore(PLAYS_STORE);
    const index = store.index('timestampUtc');
    const windowStart = timestampUtc - 60_000;
    const windowEnd = timestampUtc + 60_000;
    const range = IDBKeyRange.bound(windowStart, windowEnd);
    const request = index.openCursor(range);
    let found = false;

    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor && !found) {
        const play = cursor.value as Play;
        const sameOriginDevice = !!incomingOriginDeviceId &&
          play.originDeviceId === incomingOriginDeviceId;
        if (!sameOriginDevice &&
          play.title.trim().toLowerCase() === title.trim().toLowerCase() &&
          play.artist.trim().toLowerCase() === artist.trim().toLowerCase()
        ) {
          found = true;
        } else {
          cursor.continue();
        }
      } else {
        resolve(found);
      }
    };
    request.onerror = () => reject(request.error);
  });
}

/**
 * Clean up old synced/failed records and enforce max record count.
 * Called periodically to prevent unbounded DB growth.
 * Optimized: uses the timestampUtc index to skip non-matching records.
 */
export async function cleanupOldRecords(): Promise<number> {
  const cutoff = Date.now() - MAX_RECORD_AGE_MS;
  const driveSyncEnabled = (await getSettings()).driveSyncEnabled;
  let deleted = 0;

  const db = await openDb();
  deleted = await new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    const index = store.index('timestampUtc');
    // Only scan records older than cutoff (fast — uses index)
    const range = IDBKeyRange.upperBound(cutoff);
    const request = index.openCursor(range);

    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) {
        const play = cursor.value as Play;
        const driveSafe = !driveSyncEnabled || play.driveImported || !!play.driveUploadedAt;
        if ((play.status === 'synced' || play.status === 'failed') && driveSafe) {
          cursor.delete();
          deleted++;
        }
        cursor.continue();
      }
    };

    tx.oncomplete = () => {
      if (deleted > 0) {
        console.log(`[Tempo] Cleaned up ${deleted} old play records`);
      }
      invalidateQueueCountCache();
      invalidateStatsCache();
      _playCountEstimate -= deleted;
      resolve(deleted);
    };
    tx.onerror = () => reject(tx.error);
  });

  // Same housekeeping pass also prunes sync history (age + hard cap).
  await pruneSyncHistory();
  return deleted;
}

/**
 * Enforce maximum record count by deleting oldest entries.
 * Optimized: uses reverse cursor to find excess records directly
 * instead of loading all records into memory.
 */
export async function enforceMaxRecords(): Promise<void> {
  // Skip the store.count() + cursor scan entirely while our estimate says the
  // collection is comfortably below the cap.
  if (_playCountEstimate < MAX_PLAY_RECORDS) return;

  const driveSyncEnabled = (await getSettings()).driveSyncEnabled;
  const db = await openDb();

  // First, count total records
  const totalCount = await new Promise<number>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const store = tx.objectStore(PLAYS_STORE);
    const request = store.count();
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });

  // Refresh the estimate with the measured value on every actual run.
  _playCountEstimate = totalCount;

  if (totalCount <= MAX_PLAY_RECORDS) return;

  const excess = totalCount - MAX_PLAY_RECORDS;
  const toDelete: number[] = [];

  // Collect oldest record IDs (ascending order = oldest first)
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const store = tx.objectStore(PLAYS_STORE);
    const index = store.index('timestampUtc');
    const request = index.openCursor();
    let collected = 0;

    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor && collected < excess) {
        const play = cursor.value as Play;
        const driveSafe = !driveSyncEnabled || play.driveImported || !!play.driveUploadedAt;
        if (play.id != null && driveSafe) {
          toDelete.push(play.id);
          collected++;
        }
        cursor.continue();
      } else {
        resolve();
      }
    };
    request.onerror = () => reject(request.error);
  });

  if (toDelete.length === 0) {
    if (driveSyncEnabled) {
      console.warn(`[Tempo] Keeping ${excess} excess play records until Drive upload completes`);
    }
    return;
  }

  // Delete in a single transaction
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    for (const id of toDelete) {
      store.delete(id);
    }
    tx.oncomplete = () => { _playCountEstimate = totalCount - toDelete.length; resolve(); };
    tx.onerror = () => reject(tx.error);
  });

  invalidateQueueCountCache();
  invalidateStatsCache();
  console.log(`[Tempo] Pruned ${toDelete.length} excess play records`);
  const remaining = totalCount - toDelete.length;
  if (driveSyncEnabled && remaining > MAX_PLAY_RECORDS) {
    console.warn(`[Tempo] Keeping ${remaining - MAX_PLAY_RECORDS} excess play records until Drive upload completes`);
  }
}

// Sync History

export async function recordSync(count: number, status: string, errorMessage: string | null): Promise<void> {
  const db = await openDb();
  const record: Omit<SyncRecord, 'id'> = {
    syncedCount: count,
    status: status as 'success' | 'failed',
    errorMessage,
    syncedAt: new Date().toISOString(),
  };

  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction(SYNC_HISTORY_STORE, 'readwrite');
    const store = tx.objectStore(SYNC_HISTORY_STORE);
    store.add(record);
    tx.oncomplete = () => { invalidateStatsCache(); resolve(); };
    tx.onerror = () => reject(tx.error);
  });
}

export async function getSyncHistory(limit = 10): Promise<SyncRecord[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(SYNC_HISTORY_STORE, 'readonly');
    const store = tx.objectStore(SYNC_HISTORY_STORE);
    const index = store.index('syncedAt');
    const records: SyncRecord[] = [];
    const request = index.openCursor(null, 'prev');

    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor && records.length < limit) {
        records.push(cursor.value as SyncRecord);
        cursor.continue();
      } else {
        resolve(records);
      }
    };
    request.onerror = () => reject(request.error);
  });
}

// Sync-history pruning bounds: drop records older than 30 days and keep at
// most 500 records regardless of age.

const MAX_SYNC_RECORD_AGE_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_SYNC_HISTORY_RECORDS = 500;

/**
 * Prune sync history: delete records older than 30 days via a syncedAt-index
 * cursor, then enforce the hard cap with a reverse cursor (newest kept).
 * Runs in the same housekeeping pass as cleanupOldRecords().
 */
export async function pruneSyncHistory(): Promise<void> {
  const db = await openDb();
  const cutoffIso = new Date(Date.now() - MAX_SYNC_RECORD_AGE_MS).toISOString();

  // Age-based deletion — only scans records older than the cutoff.
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(SYNC_HISTORY_STORE, 'readwrite');
    const index = tx.objectStore(SYNC_HISTORY_STORE).index('syncedAt');
    const request = index.openCursor(IDBKeyRange.upperBound(cutoffIso));

    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) {
        cursor.delete();
        cursor.continue();
      }
    };

    tx.oncomplete = () => { invalidateStatsCache(); resolve(); };
    tx.onerror = () => reject(tx.error);
  });

  // Hard cap — walk newest-first and delete everything past record 500.
  let pruned = 0;
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(SYNC_HISTORY_STORE, 'readwrite');
    const index = tx.objectStore(SYNC_HISTORY_STORE).index('syncedAt');
    const request = index.openCursor(null, 'prev');
    let kept = 0;

    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) {
        if (kept < MAX_SYNC_HISTORY_RECORDS) {
          kept++;
        } else {
          cursor.delete();
          pruned++;
        }
        cursor.continue();
      }
    };

    tx.oncomplete = () => {
      if (pruned > 0) {
        console.log(`[Tempo] Pruned ${pruned} excess sync history records`);
      }
      invalidateStatsCache();
      resolve();
    };
    tx.onerror = () => reject(tx.error);
  });
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

export async function clearSyncBackoff(): Promise<void> {
  return new Promise<void>((resolve) => {
    chrome.storage.session.remove('syncBackoff', resolve);
  });
}

// Stats

export interface ExtensionStats {
  totalPlays: number;
  queuedCount: number;
  syncedCount: number;
  totalSyncs: number;
  topArtist: string | null;
  topTrack: string | null;
}

/**
 * Compute extension stats.
 * Optimized: uses index-based counting where possible instead of loading all records.
 */
export async function getStats(): Promise<ExtensionStats> {
  // Brief TTL cache — the popup polls stats; recomputing on every tick means
  // a 5000-record cursor walk each time. Cleared by every store mutation.
  if (_statsCache && (Date.now() - (_statsCacheTime ?? 0)) < STATS_CACHE_TTL_MS) {
    return _statsCache;
  }

  const db = await openDb();

  // Counts via index/store counters; totalSyncs via count() instead of
  // materializing getSyncHistory(1000).
  const [queuedCount, syncedCount, totalPlays, totalSyncs] = await Promise.all([
    new Promise<number>((resolve, reject) => {
      const tx = db.transaction(PLAYS_STORE, 'readonly');
      const req = tx.objectStore(PLAYS_STORE).index('status').count('queued');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }),
    new Promise<number>((resolve, reject) => {
      const tx = db.transaction(PLAYS_STORE, 'readonly');
      const req = tx.objectStore(PLAYS_STORE).index('status').count('synced');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }),
    new Promise<number>((resolve, reject) => {
      const tx = db.transaction(PLAYS_STORE, 'readonly');
      const req = tx.objectStore(PLAYS_STORE).count();
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }),
    new Promise<number>((resolve, reject) => {
      const tx = db.transaction(SYNC_HISTORY_STORE, 'readonly');
      const req = tx.objectStore(SYNC_HISTORY_STORE).count();
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }),
  ]);

  // For top artist/track, stream the up-to-5000 most recent plays through
  // readonly cursors and build the frequency maps incrementally instead of
  // materializing a getAllPlays(5000) array.
  const STATS_PLAY_SCAN_LIMIT = 5000;
  const { artistCounts, trackCounts } = await new Promise<{
    artistCounts: Map<string, number>;
    trackCounts: Map<string, number>;
  }>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const index = tx.objectStore(PLAYS_STORE).index('timestampUtc');
    const artistCounts = new Map<string, number>();
    const trackCounts = new Map<string, number>();
    let seen = 0;
    const request = index.openCursor(null, 'prev');

    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor && seen < STATS_PLAY_SCAN_LIMIT) {
        const play = cursor.value as Play;
        if (play.artist) {
          artistCounts.set(play.artist, (artistCounts.get(play.artist) ?? 0) + 1);
        }
        const key = `${play.title} - ${play.artist}`;
        trackCounts.set(key, (trackCounts.get(key) ?? 0) + 1);
        seen++;
        cursor.continue();
      } else {
        resolve({ artistCounts, trackCounts });
      }
    };
    request.onerror = () => reject(request.error);
  });

  let topArtist: string | null = null;
  let topArtistCount = 0;
  for (const [artist, count] of artistCounts) {
    if (count > topArtistCount) {
      topArtist = artist;
      topArtistCount = count;
    }
  }

  let topTrack: string | null = null;
  let topTrackCount = 0;
  for (const [track, count] of trackCounts) {
    if (count > topTrackCount) {
      topTrack = track;
      topTrackCount = count;
    }
  }

  _statsCache = {
    totalPlays,
    queuedCount,
    syncedCount,
    totalSyncs,
    topArtist,
    topTrack,
  };
  _statsCacheTime = Date.now();
  return _statsCache;
}

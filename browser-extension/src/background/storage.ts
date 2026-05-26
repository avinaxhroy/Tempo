// ============================================================================
// Tempo Stats — IndexedDB + chrome.storage wrapper
// Provides persistent storage for plays queue, pairing, settings, sync history,
// known artists, and YouTube channels.
// Uses a cached DB connection to avoid expensive open/close per operation.
// ============================================================================

import type { Play, PairingInfo, Settings, SyncRecord, TabTrackState } from '../shared/types';
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

// ---- Cached IndexedDB Connection -------------------------------------------
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

// ---- In-memory queue count cache (avoids DB read on every badge update) ----

let _cachedQueueCount: number | null = null;
let _queueCountCacheTime = 0;
const QUEUE_COUNT_CACHE_TTL_MS = 2_000;

function invalidateQueueCountCache(): void {
  _cachedQueueCount = null;
}

// ---- Play Queue ------------------------------------------------------------

export async function insertPlay(play: Omit<Play, 'id'>): Promise<number> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    const request = store.add(play);
    request.onsuccess = () => {
      invalidateQueueCountCache();
      resolve(request.result as number);
    };
    request.onerror = () => reject(request.error);
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

export async function markPlaysSynced(ids: number[]): Promise<void> {
  if (ids.length === 0) return;

  const db = await openDb();
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);

    for (const id of ids) {
      const getReq = store.get(id);
      getReq.onsuccess = () => {
        const play = getReq.result as Play | undefined;
        if (play) {
          play.status = 'synced';
          store.put(play);
        }
      };
    }

    tx.oncomplete = () => { invalidateQueueCountCache(); resolve(); };
    tx.onerror = () => reject(tx.error);
  });
}

export async function markPlaysFailed(ids: number[]): Promise<void> {
  if (ids.length === 0) return;

  const db = await openDb();
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);

    for (const id of ids) {
      const getReq = store.get(id);
      getReq.onsuccess = () => {
        const play = getReq.result as Play | undefined;
        if (play) {
          play.status = 'failed';
          store.put(play);
        }
      };
    }

    tx.oncomplete = () => { invalidateQueueCountCache(); resolve(); };
    tx.onerror = () => reject(tx.error);
  });
}

/** Reset failed plays back to queued so they can be retried on next sync. */
export async function retryFailedPlays(): Promise<number> {
  const failed = await getFailedPlays();
  if (failed.length === 0) return 0;

  const ids = failed.filter(p => p.id != null).map(p => p.id!);

  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);

    for (const id of ids) {
      const getReq = store.get(id);
      getReq.onsuccess = () => {
        const play = getReq.result as Play | undefined;
        if (play) {
          play.status = 'queued';
          store.put(play);
        }
      };
    }

    tx.oncomplete = () => { invalidateQueueCountCache(); resolve(ids.length); };
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

    tx.oncomplete = () => { invalidateQueueCountCache(); resolve(queued.length); };
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
    tx.oncomplete = () => { invalidateQueueCountCache(); resolve(); };
    tx.onerror = () => reject(tx.error);
  });
}

/**
 * Check if a recent play exists with same title+artist within ±60s window.
 * Optimized: uses a bounded cursor range on the timestampUtc index.
 */
export async function hasRecentPlay(title: string, artist: string, timestampUtc: number): Promise<boolean> {
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
        if (play.title === title && play.artist === artist) {
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
  let deleted = 0;

  const db = await openDb();
  return new Promise((resolve, reject) => {
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
        if (play.status === 'synced' || play.status === 'failed') {
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
      resolve(deleted);
    };
    tx.onerror = () => reject(tx.error);
  });
}

/**
 * Enforce maximum record count by deleting oldest entries.
 * Optimized: uses reverse cursor to find excess records directly
 * instead of loading all records into memory.
 */
export async function enforceMaxRecords(): Promise<void> {
  const db = await openDb();

  // First, count total records
  const totalCount = await new Promise<number>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const store = tx.objectStore(PLAYS_STORE);
    const request = store.count();
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });

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
        if (play.id != null) {
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

  if (toDelete.length === 0) return;

  // Delete in a single transaction
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    for (const id of toDelete) {
      store.delete(id);
    }
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });

  invalidateQueueCountCache();
  console.log(`[Tempo] Pruned ${toDelete.length} excess play records`);
}

// ---- Sync History ----------------------------------------------------------

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
    tx.oncomplete = () => resolve();
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

// ---- Settings (chrome.storage.local) ---------------------------------------

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

// ---- Pairing (chrome.storage.local with session mirroring for compatibility) ---

export async function getPairing(): Promise<PairingInfo | null> {
  return new Promise<PairingInfo | null>((resolve) => {
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
}

export async function savePairing(pairing: PairingInfo): Promise<void> {
  const { authToken, ...localData } = pairing;
  await chrome.storage.session.set({ authToken });
  return new Promise<void>((resolve) => {
    chrome.storage.local.set({ pairing: { ...localData, authToken } }, resolve);
  });
}

export async function removePairing(): Promise<void> {
  await chrome.storage.session.remove('authToken');
  return new Promise<void>((resolve) => {
    chrome.storage.local.remove('pairing', resolve);
  });
}

// ---- Session State (chrome.storage.session — survives hibernation) --------

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

// ---- Stats -----------------------------------------------------------------

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
  const db = await openDb();

  // Count by status using index (fast, no full scan)
  const [queuedCount, syncedCount, totalPlays] = await Promise.all([
    new Promise<number>((resolve, reject) => {
      const tx = db.transaction(PLAYS_STORE, 'readonly');
      const store = tx.objectStore(PLAYS_STORE);
      const req = store.index('status').count('queued');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }),
    new Promise<number>((resolve, reject) => {
      const tx = db.transaction(PLAYS_STORE, 'readonly');
      const store = tx.objectStore(PLAYS_STORE);
      const req = store.index('status').count('synced');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }),
    new Promise<number>((resolve, reject) => {
      const tx = db.transaction(PLAYS_STORE, 'readonly');
      const store = tx.objectStore(PLAYS_STORE);
      const req = store.count();
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }),
  ]);

  // For top artist/track, load up to 5000 most recent plays
  const allPlays = await getAllPlays(5000);
  const syncHistory = await getSyncHistory(1000);

  const artistCounts = new Map<string, number>();
  const trackCounts = new Map<string, number>();
  for (const play of allPlays) {
    if (play.artist) {
      artistCounts.set(play.artist, (artistCounts.get(play.artist) ?? 0) + 1);
    }
    const key = `${play.title} - ${play.artist}`;
    trackCounts.set(key, (trackCounts.get(key) ?? 0) + 1);
  }

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

  return {
    totalPlays,
    queuedCount,
    syncedCount,
    totalSyncs: syncHistory.length,
    topArtist,
    topTrack,
  };
}

/**
 * Storage layer combining IndexedDB (for play queues and sync history)
 * with chrome.storage (for pairing, settings, and session state).
 * Keeps an open IndexedDB connection cached to minimize disk I/O overhead.
 */

import type { Play, SyncRecord } from '../shared/types';
export { getSettings, saveSettings, getPairing, savePairing, removePairing, saveSessionState, loadSessionState, getConnectionHistory, recordConnectionSuccess, recordConnectionFailure, recordConnectionFailures, clearConnectionHistory, getConnectionHealth, saveConnectionHealth, recordHealthPing, recordAuthFailure, getSyncCheckpoint, saveSyncCheckpoint, clearSyncCheckpoint, getSyncBackoff, saveSyncBackoff } from './connection-storage';

const DB_NAME = 'TempoStatsDB';
const DB_VERSION = 4;
const UNOWNED_DRIVE_QUEUE = 'tempo-unowned';
function pendingDriveKey(play: Play | Omit<Play, 'id'>): [string, number] | undefined {
  if (play.driveImported || play.driveUploadedAt || play.cloudSuppressed) return undefined;
  return [play.driveAccountSubject ?? UNOWNED_DRIVE_QUEUE,
    Number.isFinite(play.timestampUtc) ? play.timestampUtc : 0];
}
function withDrivePendingKey<T extends Play | Omit<Play, 'id'>>(play: T): T {
  const key = pendingDriveKey(play);
  const record = { ...play };
  if (key) record.drivePendingIndexKey = key;
  else delete record.drivePendingIndexKey;
  return record as T;
}


// Store names
const PLAYS_STORE = 'plays';
const SYNC_HISTORY_STORE = 'syncHistory';


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

      const plays = request.transaction!.objectStore(PLAYS_STORE);
      const needsOrigins = !plays.indexNames.contains('driveOriginLookupIds');
      const needsPending = !plays.indexNames.contains('drivePendingIndexKey');
      if (needsOrigins) {
        plays.createIndex('driveOriginLookupIds', 'driveOriginLookupIds',
          { unique: false, multiEntry: true });
      }
      if (needsPending) {
        plays.createIndex('drivePendingIndexKey', 'drivePendingIndexKey');
      }
      // One atomic pass for v1/v2 -> v4 upgrades. Two independent cursor
      // updaters could overwrite one another's fields, losing existing source
      // aliases when both the origin and pending indexes are introduced.
      if (needsOrigins || needsPending) {
        const scan = plays.openCursor();
        scan.onsuccess = () => {
          const cursor = scan.result;
          if (!cursor) return;
          const play = cursor.value as Play;
          let record: Play = { ...play };
          if (needsOrigins) {
            const ids = [play.originEventId,
              ...(play.reconciledOrigins ?? []).map(alias => alias.eventId)]
              .filter((id): id is string => typeof id === 'string' && id.length > 0);
            if (ids.length) record.driveOriginLookupIds = [...new Set(ids)];
          }
          if (needsPending) record = withDrivePendingKey(record);
          cursor.update(record);
          cursor.continue();
        };
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

// In-memory stats cache — getStats() streams the full local archive; the popup
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
    const record = play.originEventId ? {
      ...play,
      driveOriginLookupIds: [...new Set([play.originEventId,
        ...(play.reconciledOrigins ?? []).map(alias => alias.eventId)])],
    } : play;
    const request = store.add(withDrivePendingKey(record));
    // An add request can succeed before its transaction is committed. Drive
    // must not advance its cursor until this row is durably stored.
    tx.oncomplete = () => {
      invalidateQueueCountCache();
      invalidateStatsCache();
      resolve(request.result as number);
    };
    request.onerror = () => reject(request.error);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Play insertion transaction aborted'));
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

/** Durable indexed lookup rather than a full event-history scan per sync. */
export async function hasDriveOriginEventId(eventId: string, accountSubject: string): Promise<boolean> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const req = tx.objectStore(PLAYS_STORE).index('driveOriginLookupIds')
      .openCursor(IDBKeyRange.only(eventId));
    req.onsuccess = () => {
      const cursor = req.result;
      if (!cursor) { resolve(false); return; }
      const play = cursor.value as Play;
      if (play.driveAccountSubject === accountSubject) { resolve(true); return; }
      cursor.continue();
    };
    req.onerror = () => reject(req.error);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Drive provenance lookup aborted'));
  });
}

/** Minimal local-origin inputs for an explicit own-device history restore.
 * Existing locally-owned rows have no originEventId: their identity is derived
 * from device ID plus IndexedDB row ID and track metadata. Never await hashing
 * inside an active IndexedDB transaction; collect only these four small fields.
 */
export async function getOwnPlayIdentityInputs(accountSubject?: string): Promise<Array<
  Pick<Play, 'id' | 'timestampUtc' | 'title' | 'artist'>
>> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const inputs: Array<Pick<Play, 'id' | 'timestampUtc' | 'title' | 'artist'>> = [];
    const request = tx.objectStore(PLAYS_STORE).openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      const play = cursor.value as Play;
      if (!play.driveImported && !play.originEventId && play.id != null &&
          (!accountSubject || !play.driveAccountSubject ||
            play.driveAccountSubject === accountSubject)) {
        inputs.push({
          id: play.id,
          timestampUtc: play.timestampUtc,
          title: play.title,
          artist: play.artist,
        });
      }
      cursor.continue();
    };
    tx.oncomplete = () => resolve(inputs);
    request.onerror = () => reject(request.error);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Local identity scan aborted'));
  });
}

/**
 * Return locally-owned plays that still need their first Drive upload.
 * Scan oldest-first across the whole store: when Drive has been unavailable
 * for a long time the database may temporarily exceed the normal 5k cap,
 * and those older pending rows must not become unreachable.
 */
/**
 * Return only owner-scoped Drive upload candidates; never fall back to a
 * global timestamp scan that could mix previous Google identities.
 */
export async function getDrivePendingPlays(
  limit: number,
  accountSubject: string,
  after?: { timestampUtc: number; id: number },
): Promise<Play[]> {
  if (!accountSubject.trim()) throw new Error('Verified Google account is required for Drive upload scan');
  const db = await openDb();

  const readOwner = (owner: string) => new Promise<Play[]>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const result: Play[] = [];
    const floor = after && Number.isFinite(after.timestampUtc) ? after.timestampUtc : 0;
    const request = tx.objectStore(PLAYS_STORE).index('drivePendingIndexKey')
      // IndexedDB accepts any finite numeric key, not only safe integers.
      // An out-of-range legacy timestamp must be surfaced for repair rather
      // than silently excluded from the Drive upload queue.
      .openCursor(IDBKeyRange.bound([owner, after ? floor : -Number.MAX_VALUE],
                                    [owner, Number.MAX_VALUE]));
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor || result.length >= limit) { resolve(result); return; }
      const play = cursor.value as Play;
      const ts = Number.isFinite(play.timestampUtc) ? play.timestampUtc : 0;
      if (play.id != null && !play.driveImported && !play.driveUploadedAt &&
          (!after || ts > floor || (ts === floor && play.id > after.id))) result.push(play);
      cursor.continue();
    };
    request.onerror = () => reject(request.error);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Indexed Drive queue read failed'));
  });
  // Unowned plays captured after sign-in belong to the current account until
  // their first Drive operation persists that association. Previously owned
  // plays have a distinct owner key and are never searched for this account.
  const [owned, unowned] = await Promise.all([
    readOwner(accountSubject), readOwner(UNOWNED_DRIVE_QUEUE),
  ]);
  return [...owned, ...unowned]
    .sort((a, b) => {
      const at = Number.isFinite(a.timestampUtc) ? a.timestampUtc : 0;
      const bt = Number.isFinite(b.timestampUtc) ? b.timestampUtc : 0;
      return at - bt || (a.id ?? 0) - (b.id ?? 0);
    })
    .slice(0, limit);
}

/** Pending cloud uploads for the verified account, including unowned recent plays.
 * Uses the existing compound IndexedDB index instead of scanning all history.
 */
export async function getDrivePendingCount(accountSubject: string): Promise<number> {
  if (!accountSubject.trim()) return 0;
  const db = await openDb();
  const countOwner = (owner: string): Promise<number> => new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const request = tx.objectStore(PLAYS_STORE).index('drivePendingIndexKey')
      .count(IDBKeyRange.bound([owner, -Number.MAX_VALUE], [owner, Number.MAX_VALUE]));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    tx.onabort = () => reject(tx.error ?? new Error('Drive count read aborted'));
  });
  const [owned, unowned] = await Promise.all([
    countOwner(accountSubject), countOwner(UNOWNED_DRIVE_QUEUE),
  ]);
  return owned + unowned;
}

/**
 * Preserve ownership of every existing play before switching Google accounts.
 * This transaction commits BEFORE the new account can sync. Rows recorded after
 * the switch remain unowned until their first Drive operation on that account.
 * A legacy installation with unverifiable prior identity is quarantined.
 */
export async function claimUnownedDrivePlays(accountSubject: string): Promise<void> {
  if (!accountSubject.trim()) throw new Error('Missing verified Google account subject');
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const request = tx.objectStore(PLAYS_STORE).openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      const play = cursor.value as Play;
      if (!play.driveAccountSubject) {
        // Previously imported Drive records without a verified account subject
        // cannot be proven to belong to the account now connecting. Quarantine
        // their original history rather than relabeling it and allowing a
        // temporal match from a different Google account to claim its origin.
        // Genuine locally captured plays may belong to the newly confirmed
        // account and retain their usual pending-upload behavior.
        const verifiedOwner = play.driveImported ? 'legacy-unverified' : accountSubject;
        cursor.update(withDrivePendingKey({ ...play, driveAccountSubject: verifiedOwner }));
      }
      cursor.continue();
    };
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Google owner migration aborted'));
  });
}

/** Persist the first canonical local event ID before either LAN or Drive
 * transmits it. The transaction must commit before the caller uses the ID;
 * retries and title/artist edits then reuse exactly the same identity.
 */
export async function ensureLocalOriginEventId(
  playId: number, candidateId: string, accountSubject?: string,
): Promise<string> {
  if (!/^[0-9a-f]{64}$/.test(candidateId)) throw new Error('Invalid local origin identity');
  const db = await openDb();
  return new Promise<string>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    let pinnedId: string | null = null;
    const request = store.get(playId);
    request.onsuccess = () => {
      const play = request.result as Play | undefined;
      if (!play || play.driveImported ||
          (accountSubject && play.driveAccountSubject && play.driveAccountSubject !== accountSubject)) {
        tx.abort();
        return;
      }
      if (play.originEventId && !/^[0-9a-f]{64}$/.test(play.originEventId)) {
        tx.abort();
        return;
      }
      pinnedId = play.originEventId ?? candidateId;
      if (!play.originEventId || (accountSubject && !play.driveAccountSubject)) {
        play.originEventId = pinnedId;
        if (accountSubject) play.driveAccountSubject = accountSubject;
        play.driveOriginLookupIds = [...new Set([...(play.driveOriginLookupIds ?? []), pinnedId])];
        store.put(withDrivePendingKey(play));
      }
    };
    tx.oncomplete = () => {
      if (pinnedId) resolve(pinnedId);
      else reject(new Error('Could not pin local event identity'));
    };
    tx.onerror = () => reject(tx.error ?? new Error('Failed to persist event identity'));
    tx.onabort = () => reject(tx.error ?? new Error('Cannot pin an absent, imported, or invalid play'));
  });
}

/** Mark uploaded rows by primary key, not by scanning every historic play.
 * Persist the origin identity alongside the verified upload in one IndexedDB
 * transaction: a later full restore can then recognize locally-owned events
 * even if their display metadata has changed since first synchronization.
 */
export async function markDriveUploaded(
  entries: Array<{ id: number; originEventId: string }>,
): Promise<void> {
  if (entries.length === 0) return;
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    const uploadedAt = Date.now();
    for (const entry of entries) {
      const request = store.get(entry.id);
      request.onsuccess = () => {
        const play = request.result as Play | undefined;
        if (!play || play.driveImported) return;
        play.originEventId = entry.originEventId;
        play.driveOriginLookupIds = [...new Set([
          ...(play.driveOriginLookupIds ?? []), entry.originEventId,
        ])];
        play.driveUploadedAt = uploadedAt;
        store.put(withDrivePendingKey(play));
      };
    }
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Drive upload flag transaction aborted'));
  });
}

/** Apply a cloud-deletion marker durably, including locally captured plays
 * that never reached Drive. Account B's history is never touched by A's marker.
 */
export async function suppressDeletedDriveHistory(accountSubject: string, markerVersion: number): Promise<void> {
  if (!accountSubject.trim()) throw new Error('Verified Google account required');
  if (!Number.isSafeInteger(markerVersion) || markerVersion <= 0) {
    throw new Error('Verified Drive deletion marker is required');
  }
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const request = tx.objectStore(PLAYS_STORE).openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      const play = cursor.value as Play;
      // A play recorded offline AFTER deletion belongs to the new archive,
      // even if its IndexedDB row predates discovery of the remote marker.
      if (!play.driveImported &&
          (play.driveUploadedAt != null || play.timestampUtc <= markerVersion) &&
          (!play.driveAccountSubject || play.driveAccountSubject === accountSubject)) {
        cursor.update(withDrivePendingKey({
          ...play, driveAccountSubject: accountSubject, cloudSuppressed: true,
        }));
      }
      cursor.continue();
    };
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Drive deletion suppression failed'));
  });
}

/** Explicit, user-confirmed republishing for this same Google account only. */
export async function authorizeOlderDriveHistory(accountSubject: string): Promise<void> {
  if (!accountSubject.trim()) throw new Error('Verified Google account required');
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const request = tx.objectStore(PLAYS_STORE).openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      const play = cursor.value as Play;
      if (!play.driveImported && play.driveAccountSubject === accountSubject) {
        const updated: Play = { ...play, cloudSuppressed: false };
        delete updated.driveUploadedAt;
        cursor.update(withDrivePendingKey(updated));
      }
      cursor.continue();
    };
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Older Drive history authorization failed'));
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

/**
 * Only delete locally queued records that already have a confirmed Google
 * Drive copy. Queued is a LAN-only flag; it does NOT mean the cloud upload
 * finished. Keep the sole copy of any unsent recording on this device.
 * Check and delete each row in the SAME transaction to avoid racing an upload.
 */
export async function clearQueue(): Promise<{ cleared: number; protected: number }> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const index = tx.objectStore(PLAYS_STORE).index('status');
    const request = index.openCursor(IDBKeyRange.only('queued'));
    let cleared = 0;
    let protectedCount = 0;
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return;
      const play = cursor.value as Play;
      if (!play.cloudSuppressed && (play.driveUploadedAt || play.driveImported)) {
        cursor.delete();
        cleared++;
      } else {
        protectedCount++;
      }
      cursor.continue();
    };
    tx.oncomplete = () => {
      invalidateQueueCountCache();
      invalidateStatsCache();
      resolve({ cleared, protected: protectedCount });
    };
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Queue cleanup was cancelled'));
  });
}

/** Hard-delete a single play record (any status) by its IndexedDB id. */
export async function deletePlay(id: number): Promise<void> {
  const db = await openDb();
  return new Promise<void>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readwrite');
    const store = tx.objectStore(PLAYS_STORE);
    store.delete(id);
    tx.oncomplete = () => { invalidateQueueCountCache(); invalidateStatsCache(); resolve(); };
    tx.onerror = () => reject(tx.error);
  });
}

/**
 * Local retries use a short 5s window: a second short-track replay must not be
 * discarded just because it occurred within a minute of the first play.
 * Drive imports use a bounded 2s reconciliation window shared with
 * Android. A full minute could conflate a real second short-track play from
 * another device with the previous one. Two different IDs from the SAME
 * originating device are always distinct, regardless of timestamp drift.
 */
export function recentPlayWindowMs(incomingOriginDeviceId?: string): number {
  return incomingOriginDeviceId ? 2_000 : 5_000;
}
export async function hasRecentPlay(
  title: string,
  artist: string,
  timestampUtc: number,
  incomingOriginDeviceId?: string,
  incomingOriginEventId?: string,
  accountSubject?: string,
  durationMs?: number,
  listenedMs?: number,
): Promise<boolean> {
  const db = await openDb();
  const rememberRemoteOrigin = !!incomingOriginDeviceId && !!incomingOriginEventId;
  return new Promise((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, rememberRemoteOrigin ? 'readwrite' : 'readonly');
    const store = tx.objectStore(PLAYS_STORE);
    const index = store.index('timestampUtc');
    const windowMs = recentPlayWindowMs(incomingOriginDeviceId);
    const range = IDBKeyRange.bound(timestampUtc - windowMs, timestampUtc + windowMs);
    const request = index.openCursor(range);
    let candidate: Play | null = null;
    let eligibleCount = 0;

    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) {
        // Multiple eligible rows may be two real rapid replays. A first-hit
        // merge would silently erase one event: refuse ambiguous fuzzy matches.
        if (eligibleCount === 1 && candidate && rememberRemoteOrigin) {
          const play = candidate;
          const aliases = play.reconciledOrigins ?? [];
          store.put(withDrivePendingKey({
            ...play,
            driveAccountSubject: accountSubject ?? play.driveAccountSubject,
            reconciledOrigins: [...aliases, {
              deviceId: incomingOriginDeviceId!,
              eventId: incomingOriginEventId!,
            }],
            driveOriginLookupIds: [...new Set([
              ...(play.driveOriginLookupIds ?? []),
              ...(play.originEventId ? [play.originEventId] : []),
              ...aliases.map(alias => alias.eventId),
              incomingOriginEventId!,
            ])],
          }));
        }
        return;
      }
      const play = cursor.value as Play;
      const aliases = play.reconciledOrigins ?? [];
      const representedByOrigin =
        (!!incomingOriginDeviceId && play.originDeviceId === incomingOriginDeviceId) ||
        aliases.some(alias => alias.deviceId === incomingOriginDeviceId);
      // Different versions of a same-titled track can start within two
      // seconds. If both devices know the duration and it differs by
      // more than ten seconds, this cannot be the same playback.
      const durationMatches =
        typeof durationMs === 'number' && durationMs > 0 &&
        typeof play.durationMs === 'number' && play.durationMs > 0
          ? Math.abs(play.durationMs - durationMs) <= 10_000
          : true;
      // A markedly different listened interval is independent evidence that
      // this is a different playback (even if track and timestamp match).
      const listeningMatches = !(typeof listenedMs === 'number' && listenedMs > 0 &&
        typeof play.listenedMs === 'number' && play.listenedMs > 0 &&
        Math.abs(play.listenedMs - listenedMs) > 10_000);
      // Legacy cloud imports with unknown account provenance must stay
      // quarantined: a near-identical song in B cannot silently claim an
      // unverified imported row that may have come from Google account A.
      // New locally detected plays can still reconcile while unowned.
      const belongsToAccount = !accountSubject ||
        play.driveAccountSubject === accountSubject ||
        (!play.driveAccountSubject && !play.driveImported);
      if (belongsToAccount &&
          !representedByOrigin &&
          durationMatches && listeningMatches &&
          play.title.trim().toLowerCase() === title.trim().toLowerCase() &&
          play.artist.trim().toLowerCase() === artist.trim().toLowerCase()) {
        eligibleCount++;
        if (eligibleCount === 1) candidate = play;
        else candidate = null;
      }
      cursor.continue();
    };
    tx.oncomplete = () => resolve(eligibleCount === 1);
    request.onerror = () => reject(request.error);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('Recent-play lookup transaction aborted'));
  });
}
/**
 * Routine maintenance preserves all listening events.
 *
 * Drive is an optional transport, not proof of recoverable backup. A browser
 * extension may be the user's ONLY Tempo client, so neither the previous
 * seven-day expiry nor a 5,000-row cap may silently erase listening history.
 * Sync diagnostic records are separate disposable metadata.
 */
export async function cleanupOldRecords(): Promise<number> {
  await pruneSyncHistory();
  return 0;
}

/** No automatic play deletion: preserve multi-year local history. */
export async function enforceMaxRecords(): Promise<void> {
  // Intentionally empty. Leave explicit user-requested deletion paths alone.
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
  // Brief TTL cache — the popup polls stats; an archive-wide cursor walk
  // should not be repeated on every tick. Cleared by every store mutation.
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

  // Include every locally retained play, not only the newest 5000, or the
  // lifetime leaders can be wrong. Stream via a cursor to bound memory to
  // frequency maps instead of materializing the complete listening archive.
  const { artistCounts, trackCounts } = await new Promise<{
    artistCounts: Map<string, number>;
    trackCounts: Map<string, number>;
  }>((resolve, reject) => {
    const tx = db.transaction(PLAYS_STORE, 'readonly');
    const index = tx.objectStore(PLAYS_STORE).index('timestampUtc');
    const artistCounts = new Map<string, number>();
    const trackCounts = new Map<string, number>();
    const request = index.openCursor(null, 'prev');

    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) {
        const play = cursor.value as Play;
        if (play.artist) {
          artistCounts.set(play.artist, (artistCounts.get(play.artist) ?? 0) + 1);
        }
        const key = `${play.title} - ${play.artist}`;
        trackCounts.set(key, (trackCounts.get(key) ?? 0) + 1);
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

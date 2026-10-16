// Deliver IndexedDB success and transaction events separately to test durability.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const out = join(mkdtempSync(join(tmpdir(), 'tempo-storage-')), 'storage.mjs');
await esbuild.build({ entryPoints: ['src/background/storage.ts'], bundle: true, format: 'esm',
  platform: 'browser', target: 'es2022', outfile: out, logLevel: 'silent' });
let transaction;
let request;
const database = { transaction() {
  request = { result: 42 };
  transaction = { objectStore: () => ({ add: () => request }) };
  return transaction;
} };
globalThis.indexedDB = { open() {
  const opening = { result: database };
  queueMicrotask(() => opening.onsuccess());
  return opening;
} };
const storage = await import(pathToFileURL(out).href);
assert.equal(storage.recentPlayWindowMs(), 5_000,
  'local detector callbacks must use a short tolerance so real replays survive');
assert.equal(storage.recentPlayWindowMs(''), 5_000);
assert.equal(storage.recentPlayWindowMs('other-device'), 2_000,
  'cross-device imports use a 2-second window to preserve independent rapid replays');

for (const abort of [false, true]) {
  let settled = false;
  const insertion = storage.insertPlay({ title: 'Song', artist: 'Artist' });
  const observed = insertion.then(value => { settled = true; return { value }; },
    error => { settled = true; return { error }; });
  // Let the asynchronous openDb continuation create its write transaction.
  await new Promise(resolve => setImmediate(resolve));
  request.onsuccess?.();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(settled, false, 'a successful add request is not a durable commit');
  if (abort) {
    transaction.error = new DOMException('Storage commit failed', 'AbortError');
    transaction.onabort();
    assert.equal((await observed).error.name, 'AbortError');
  } else {
    transaction.oncomplete();
    assert.equal((await observed).value, 42);
  }
}

// Exercise the actual IndexedDB query: local callbacks and remote overlap use
// different windows, while two distinct IDs from one remote device survive.
globalThis.IDBKeyRange = {
  bound: (lower, upper) => ({ lower, upper }),
  only: value => ({ only: value }),
};
const baseTime = 1_700_000_000_000;
let localRecords = [{
  title: 'Song', artist: 'Artist', timestampUtc: baseTime,
  originDeviceId: 'remote-A',
}];
database.transaction = () => {
  const tx = {
    objectStore: () => ({
      put(updated) {
        const position = localRecords.findIndex(row => row.id === updated.id &&
          (row.id != null || row === updated));
        if (position >= 0) localRecords[position] = updated;
        else if (localRecords.length === 1 && localRecords[0].id == null) localRecords[0] = updated;
      },
      index: () => ({
        openCursor(range) {
          const entries = localRecords.filter(item =>
            item.timestampUtc >= range.lower && item.timestampUtc <= range.upper);
          const lookup = {};
          let index = 0;
          const next = () => {
            const item = entries[index++];
            lookup.result = item ? {
              value: item,
              continue: () => queueMicrotask(next),
            } : null;
            lookup.onsuccess?.();
            if (!item) queueMicrotask(() => tx.oncomplete?.());
          };
          queueMicrotask(next);
          return lookup;
        },
      }),
    }),
  };
  return tx;
};
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 4_000), true);
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 25_000), false,
  'a real local short-track replay must not be dropped');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 7_000, 'remote-B', 'event-B1'), false,
  'an independent cross-device replay seven seconds later must survive');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 1_000, 'remote-B', 'event-B1'), true,
  'captures within two seconds can reconcile across sources');
assert.deepEqual(localRecords[0].reconciledOrigins, [{ deviceId: 'remote-B', eventId: 'event-B1' }],
  'matched producer/event ID must be durable before acknowledging the duplicate');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 27_000, 'remote-B', 'event-B2'), false,
  'another replay from the same producer must not be swallowed by the first alias');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 25_000, 'remote-C', 'event-C1'), false,
  'a different device replaying the same song 25s later must remain a separate play');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 25_000, 'remote-A'), false,
  'distinct quick replays from one origin must be preserved');
assert.equal(await storage.hasRecentPlay('Different Song', 'Artist', baseTime + 2_000, 'remote-B'), false);

// If the same title appears twice around an incoming cloud event, choosing
// the first row would swallow an independent rapid replay. Preserve both.
localRecords = [
  { id: 101, title: 'Song', artist: 'Artist', timestampUtc: baseTime, listenedMs: 120_000 },
  { id: 102, title: 'Song', artist: 'Artist', timestampUtc: baseTime + 1_000, listenedMs: 120_000 },
];
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 500,
  'remote-E', 'event-E1', undefined, 180_000, 120_000), false,
  'two plausible local replays must never be merged by choosing the first');
assert.ok(localRecords.every(play => !play.reconciledOrigins?.length),
  'ambiguous candidates must not persist a fabricated provenance alias');

localRecords = [{
  id: 103, title: 'Song', artist: 'Artist', timestampUtc: baseTime,
  durationMs: 180_000, listenedMs: 20_000,
}];
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 500,
  'remote-E', 'event-E2', undefined, 180_000, 120_000), false,
  'a substantially different listened duration is evidence of an independent play');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 500,
  'remote-E', 'event-E2', undefined, 180_000, 25_000), true,
  'compatible listen duration remains eligible for temporal reconciliation');

localRecords = [{
  id: 104, title: 'Song', artist: 'Artist', timestampUtc: baseTime,
  originDeviceId: 'remote-A',
}];
localRecords[0].durationMs = 180_000;
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 1_000, 'remote-D', 'event-D1', undefined, 200_000), false,
  'track duration difference > 10s must not match same-title playback');
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 1_000, 'remote-D', 'event-D1', undefined, 185_000), true,
  'track duration difference <= 10s must match same-title playback');

// Unattributed legacy CLOUD rows must not be claimed by temporal matching to
// a newly linked Google account. Unknown fresh local captures remain eligible.
localRecords = [{
  title: 'Song', artist: 'Artist', timestampUtc: baseTime,
  driveImported: true, originDeviceId: 'old-cloud-origin',
}];
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 1_000,
  'incoming-from-B', 'B-event-1', 'google-b'), false,
  'unknown-owner imported history must not be assigned to another account');
assert.equal(localRecords[0].driveAccountSubject, undefined);
localRecords = [{
  title: 'Song', artist: 'Artist', timestampUtc: baseTime, driveImported: false,
}];
assert.equal(await storage.hasRecentPlay('Song', 'Artist', baseTime + 1_000,
  'incoming-from-B', 'B-event-2', 'google-b'), true,
  'a fresh local-only capture can reconcile with the active verified account');
assert.equal(localRecords[0].driveAccountSubject, 'google-b');

// Full-history restore must recover OWN local IDs before importing cloud data.
// A previously uploaded local play has no originEventId field, so checking
// only stored remote aliases used to duplicate the same device's whole archive.
localRecords = [
  { id: 14, title: ' Local ', artist: ' Artist ', timestampUtc: baseTime, driveAccountSubject: 'google-a' },
  { id: 15, title: 'Cloud', artist: 'Artist', timestampUtc: baseTime, driveImported: true, originEventId: 'external' },
  { id: 16, title: 'Own with alias', artist: 'Artist', timestampUtc: baseTime, originEventId: 'known' },
  { id: 17, title: 'Second local', artist: 'Band', timestampUtc: baseTime + 25_000, driveAccountSubject: 'google-b' },
];
database.transaction = () => {
  const tx = {
    objectStore: () => ({
      openCursor() {
        const req = {};
        let i = 0;
        const next = () => {
          const value = localRecords[i++];
          req.result = value ? { value, continue: () => queueMicrotask(next) } : null;
          req.onsuccess?.();
          if (!value) queueMicrotask(() => tx.oncomplete?.());
        };
        queueMicrotask(next);
        return req;
      },
    }),
  };
  return tx;
};
const ownInputs = await storage.getOwnPlayIdentityInputs();
assert.deepEqual(ownInputs, [
  { id: 14, title: ' Local ', artist: ' Artist ', timestampUtc: baseTime },
  { id: 17, title: 'Second local', artist: 'Band', timestampUtc: baseTime + 25_000 },
], 'restore recovers original locally-owned IDs, not imported or already-aliased rows');
assert.deepEqual((await storage.getOwnPlayIdentityInputs('google-a')).map(p => p.id),
  [14], 'full restore in A must not treat B-owned local plays as its own history');


// A cloud-upload acknowledgement must set the stable event ID on the exact
// IndexedDB rows in one write transaction, without scanning the entire history.
database.transaction = () => {
  let pendingReads = 0;
  const tx = {};
  const store = {
    get(id) {
      pendingReads++;
      const request = { result: localRecords.find(play => play.id === id) };
      queueMicrotask(() => {
        request.onsuccess?.();
        if (--pendingReads === 0) queueMicrotask(() => tx.oncomplete?.());
      });
      return request;
    },
    put(play) {
      const index = localRecords.findIndex(row => row.id === play.id);
      if (index >= 0) localRecords[index] = play;
    },
  };
  tx.objectStore = () => store;
  return tx;
};
await storage.markDriveUploaded([
  { id: 14, originEventId: 'owned-14' },
  { id: 15, originEventId: 'should-not-overwrite-imported' },
  { id: 17, originEventId: 'owned-17' },
]);
assert.equal(localRecords[0].originEventId, 'owned-14');
assert.equal(localRecords[1].originEventId, 'external',
  'imported plays must never be reclassified as locally-owned uploads');
assert.equal(localRecords[3].originEventId, 'owned-17');
assert.ok(localRecords[0].driveUploadedAt > 0 && localRecords[3].driveUploadedAt > 0,
  'verified cloud upload sets its acknowledgement together with stable identity');

// LAN-first delivery must persist its origin before returning a payload, so
// later title edits, retries and Drive sync cannot advertise a second ID.
localRecords.push({ id: 99, title: 'Original', artist: 'Artist', timestampUtc: baseTime });
const firstOrigin = 'a'.repeat(64);
const pinned = await storage.ensureLocalOriginEventId(99, firstOrigin);
assert.equal(pinned, firstOrigin);
assert.equal(localRecords.find(play => play.id === 99)?.originEventId, firstOrigin);
localRecords.find(play => play.id === 99).title = 'Corrected title';
const retryId = await storage.ensureLocalOriginEventId(99, 'b'.repeat(64));
assert.equal(retryId, firstOrigin, 'LAN-first origin survives later metadata changes');
assert.equal(localRecords.find(play => play.id === 99)?.originEventId, firstOrigin);


// Legacy cloud imports without an account subject must never be claimed by
// the next Google account to sign in. Truly local pending plays may be claimed.
const legacyRows = [
  { id: 51, title: 'Cloud', timestampUtc: baseTime, driveImported: true },
  { id: 52, title: 'Local', timestampUtc: baseTime + 1000, driveImported: false },
  { id: 53, title: 'Already A', timestampUtc: baseTime + 2000,
    driveAccountSubject: 'google-a' },
];
database.transaction = () => {
  const tx = {};
  tx.objectStore = () => ({
    openCursor() {
      const req = {};
      let index = 0;
      const next = () => {
        const position = index++;
        const row = legacyRows[position];
        req.result = row ? {
          value: row,
          update(updated) { legacyRows[position] = updated; },
          continue: () => queueMicrotask(next),
        } : null;
        req.onsuccess?.();
        if (!row) queueMicrotask(() => tx.oncomplete?.());
      };
      queueMicrotask(next);
      return req;
    },
  });
  return tx;
};
await storage.claimUnownedDrivePlays('google-b');
assert.equal(legacyRows[0].driveAccountSubject, 'legacy-unverified',
  'unknown-owner imported cloud history must remain quarantined');
assert.equal(legacyRows[1].driveAccountSubject, 'google-b',
  'genuine local pending history can be claimed on first sign-in');
assert.equal(legacyRows[2].driveAccountSubject, 'google-a',
  'switching accounts cannot steal previously attributed events');
assert.equal(legacyRows[0].drivePendingIndexKey, undefined,
  'legacy cloud imports are not eligible for re-upload');

// The regular Drive upload lookup must use the IndexedDB owner/time index,
// not read every already-uploaded play in a multi-year local archive.
const pendingRows = [
  { id: 1, title: 'A', timestampUtc: baseTime, driveAccountSubject: 'google-a',
    drivePendingIndexKey: ['google-a', baseTime] },
  { id: 2, title: 'B', timestampUtc: baseTime + 1000, driveAccountSubject: 'google-b',
    drivePendingIndexKey: ['google-b', baseTime + 1000] },
  { id: 3, title: 'New', timestampUtc: baseTime + 2000,
    drivePendingIndexKey: ['tempo-unowned', baseTime + 2000] },
  { id: 4, title: 'Done', timestampUtc: baseTime - 1000,
    driveAccountSubject: 'google-a', driveUploadedAt: 123 },
  { id: 5, title: 'A2', timestampUtc: baseTime + 3000,
    driveAccountSubject: 'google-a', drivePendingIndexKey: ['google-a', baseTime + 3000] },
  { id: 6, title: 'Legacy negative', timestampUtc: -1e16,
    driveAccountSubject: 'google-a', drivePendingIndexKey: ['google-a', -1e16] },
  { id: 7, title: 'Legacy huge', timestampUtc: 1e16,
    driveAccountSubject: 'google-a', drivePendingIndexKey: ['google-a', 1e16] },
];
let indexedReads = 0;
database.transaction = () => {
  const tx = {};
  tx.objectStore = () => ({
    index(name) {
      assert.equal(name, 'drivePendingIndexKey', 'pending sync must not scan timestamps globally');
      return {
        openCursor(range) {
          const owner = range.lower[0];
          const entries = pendingRows.filter(row => row.drivePendingIndexKey &&
            row.drivePendingIndexKey[0] === owner &&
            row.drivePendingIndexKey[1] >= range.lower[1] &&
            row.drivePendingIndexKey[1] <= range.upper[1])
            .sort((a, b) => a.timestampUtc - b.timestampUtc || a.id - b.id);
          const req = {};
          let at = 0;
          const next = () => {
            const value = entries[at++];
            if (value) indexedReads++;
            req.result = value ? { value, continue: () => queueMicrotask(next) } : null;
            req.onsuccess?.();
            if (!value) queueMicrotask(() => tx.oncomplete?.());
          };
          queueMicrotask(next);
          return req;
        },
      };
    },
  });
  return tx;
};
const pendingA = await storage.getDrivePendingPlays(10, 'google-a');
assert.deepEqual(pendingA.map(row => row.id), [6, 1, 3, 5, 7],
  'Google A receives all finite pending timestamps, including malformed legacy extremes, never B');
assert.ok(indexedReads <= 5, 'already-synced archive must not be scanned');
assert.deepEqual((await storage.getDrivePendingPlays(10, 'google-a',
  { timestampUtc: baseTime + 2000, id: 3 })).map(row => row.id), [5, 7],
  'composite indexed pagination must resume after the last submitted row');

console.log('\n17 IndexedDB durability, replay, account isolation and indexed queue scenarios passed');


// Cloud deletion must fence all prior local captures, including never-uploaded
// rows, while preserving other Google accounts and actual imported plays.
localRecords = [
  { id: 1, title: 'Owned A', artist: 'Artist', timestampUtc: baseTime,
    driveAccountSubject: 'google-a', driveUploadedAt: 999 },
  { id: 2, title: 'Unsent', artist: 'Artist', timestampUtc: baseTime + 1 },
  { id: 3, title: 'Owned B', artist: 'Artist', timestampUtc: baseTime + 2,
    driveAccountSubject: 'google-b' },
  { id: 4, title: 'Imported', artist: 'Artist', timestampUtc: baseTime + 3,
    driveImported: true, driveAccountSubject: 'google-a' },
  { id: 5, title: 'Offline after deletion', artist: 'Artist', timestampUtc: baseTime + 2,
    driveAccountSubject: 'google-a' },
  { id: 6, title: 'Already uploaded with skewed clock', artist: 'Artist',
    timestampUtc: baseTime + 5_000, driveAccountSubject: 'google-a', driveUploadedAt: 999 },
];
database.transaction = () => {
  const tx = {};
  tx.objectStore = () => ({
    openCursor() {
      const req = {};
      let i = 0;
      const next = () => {
        const at = i++;
        req.result = at < localRecords.length ? {
          value: localRecords[at],
          update(value) { localRecords[at] = value; },
          continue: () => queueMicrotask(next),
        } : null;
        req.onsuccess?.();
        if (!req.result) queueMicrotask(() => tx.oncomplete?.());
      };
      queueMicrotask(next);
      return req;
    },
  });
  return tx;
};
await storage.suppressDeletedDriveHistory('google-a', baseTime + 1);
assert.equal(localRecords[0].cloudSuppressed, true);
assert.equal(localRecords[1].cloudSuppressed, true);
assert.equal(localRecords[1].driveAccountSubject, 'google-a');
assert.equal(localRecords[2].cloudSuppressed, undefined);
assert.equal(localRecords[3].cloudSuppressed, undefined);
assert.equal(localRecords[4].cloudSuppressed, undefined,
  'offline play captured after the deletion marker belongs to the new Drive history');
assert.equal(localRecords[5].cloudSuppressed, true,
  'a previously uploaded track must never reappear even if its clock is ahead');
assert.equal(localRecords[0].driveUploadedAt, 999,
  'deletion must not clear existing Drive acknowledgements');
await storage.authorizeOlderDriveHistory('google-a');
assert.equal(localRecords[0].cloudSuppressed, false);
assert.equal(localRecords[1].cloudSuppressed, false);
assert.equal(localRecords[0].driveUploadedAt, undefined);
assert.equal(localRecords[2].driveAccountSubject, 'google-b');
console.log('  ✓ cloud deletion blocks earlier browser plays until explicit account-scoped authorization');


// Destructive Queue cleanup must never erase the only local copy. The LAN
// 'queued' flag does not imply that Google Drive has uploaded this play.
let queueRows = [
  { id: 501, status: 'queued', title: 'Not uploaded', timestampUtc: baseTime + 1 },
  { id: 502, status: 'queued', title: 'On Drive', timestampUtc: baseTime + 2,
    driveUploadedAt: 456 },
  { id: 503, status: 'queued', title: 'Imported', timestampUtc: baseTime + 3,
    driveImported: true },
  { id: 504, status: 'synced', title: 'LAN delivered', timestampUtc: baseTime + 4 },
  { id: 505, status: 'queued', title: 'Deleted cloud batch', timestampUtc: baseTime + 5,
    cloudSuppressed: true, driveUploadedAt: 456 },
];
database.transaction = () => {
  const tx = {};
  tx.objectStore = () => ({
    index(name) {
      assert.equal(name, 'status');
      return { openCursor() {
        const req = {};
        let pos = 0;
        const entries = queueRows.filter(row => row.status === 'queued');
        const next = () => {
          const row = entries[pos++];
          req.result = row ? {
            value: row,
            delete() { queueRows = queueRows.filter(item => item.id !== row.id); },
            continue() { queueMicrotask(next); },
          } : null;
          req.onsuccess?.();
          if (!row) queueMicrotask(() => tx.oncomplete?.());
        };
        queueMicrotask(next);
        return req;
      } };
    },
  });
  return tx;
};
const cleanup = await storage.clearQueue();
assert.deepEqual(cleanup, { cleared: 2, protected: 2 });
assert.deepEqual(queueRows.map(row => row.id), [501, 504, 505],
  'both unuploaded queued history and already LAN-delivered history remain intact');
console.log('  ✓ Clear Queue keeps unsent local recordings while removing Drive-backed queued rows');

// Pending cloud totals are owner-bound and include genuinely unowned recordings,
// but must never disclose another Google account's backlog.
const countFor = owner => ({
  'google-a': 3,
  'tempo-unowned': 2,
  'google-b': 7,
}[owner] ?? 0);
database.transaction = () => {
  const tx = {};
  tx.objectStore = () => ({ index(name) {
    assert.equal(name, 'drivePendingIndexKey');
    return { count(range) {
      const req = { result: countFor(range.lower[0]) };
      queueMicrotask(() => req.onsuccess?.());
      return req;
    } };
  } });
  return tx;
};
assert.equal(await storage.getDrivePendingCount('google-a'), 5);
assert.equal(await storage.getDrivePendingCount('google-b'), 9);
console.log('  ✓ Drive upload counters are scoped to each verified account');

// Verify streaming lifetime statistics on an archive with over 100,000 retained plays.
const longArchive = [
  ...Array.from({ length: 50_001 }, (_, i) => ({
    title: 'Recent song', artist: 'Recent artist', timestampUtc: baseTime + 20_000 + i,
  })),
  ...Array.from({ length: 50_002 }, (_, i) => ({
    title: 'Older song', artist: 'Archive artist', timestampUtc: baseTime - 20_000 - i,
  })),
].sort((a, b) => b.timestampUtc - a.timestampUtc);
database.transaction = storeName => {
  const tx = {};
  tx.objectStore = () => ({
    count() {
      const req = { result: storeName === 'plays' ? longArchive.length : 0 };
      queueMicrotask(() => req.onsuccess?.());
      return req;
    },
    index(indexName) {
      if (indexName === 'status') return {
        count() {
          const req = { result: 0 };
          queueMicrotask(() => req.onsuccess?.());
          return req;
        },
      };
      assert.equal(indexName, 'timestampUtc');
      return {
        openCursor() {
          const req = {};
          let i = 0;
          const next = () => {
            const value = longArchive[i++];
            req.result = value ? { value, continue: () => queueMicrotask(next) } : null;
            req.onsuccess?.();
          };
          queueMicrotask(next);
          return req;
        },
      };
    },
  });
  return tx;
};
const lifetimeStats = await storage.getStats();
assert.equal(lifetimeStats.totalPlays, 100_003);
assert.equal(lifetimeStats.topArtist, 'Archive artist',
  'the lifetime top artist must count records older than the 5000 newest');
assert.equal(lifetimeStats.topTrack, 'Older song - Archive artist',
  'the lifetime top track must count records older than the 5000 newest');
console.log('  ✓ lifetime statistics stream over 100,000 retained plays');

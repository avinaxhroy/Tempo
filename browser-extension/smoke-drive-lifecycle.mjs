// Exercise public Drive operations with deterministic storage/auth/network fixtures.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';

const out = join(mkdtempSync(join(tmpdir(), 'tempo-drive-lifecycle-')), 'drive.mjs');
await esbuild.build({
  entryPoints: ['src/background/drive-history.ts'], bundle: true, format: 'esm', platform: 'browser', target: 'es2022',
  outfile: out, logLevel: 'silent',
  plugins: [{ name: 'drive-fixtures', setup(build) {
    build.onResolve({ filter: /^\.\/(storage|drive-auth)$/ }, args => ({ path: args.path, namespace: 'fixture' }));
    build.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({ contents: args.path === './storage' ? `
      export const getSettings = async () => ({...globalThis.fixture.settings});
      export const saveSettings = async value => { globalThis.fixture.settings = {...value}; };
      export const suppressDeletedDriveHistory = async subject => {
        globalThis.fixture.suppressed.push(subject);
      };
      export const authorizeOlderDriveHistory = async subject => {
        globalThis.fixture.authorized.push(subject);
      };
      export const getDrivePendingPlays = async () => [];
      export const getDrivePendingCount = async () => globalThis.fixture.pendingCount ?? 0;
       export const hasDriveOriginEventId = async id => globalThis.fixture.importedIds?.has(id) ?? false;
       export const claimUnownedDrivePlays = async subject => { globalThis.fixture.claimed.push(subject); };
      export const getOwnPlayIdentityInputs = async () => globalThis.fixture.ownLocalPlays ?? [];
      export const markDriveUploaded = async () => {};
      export const insertPlay = async value => {
        if (globalThis.fixture.failAfterImported != null &&
            globalThis.fixture.imported.length >= globalThis.fixture.failAfterImported) {
          globalThis.fixture.failAfterImported = null;
          throw new Error('Simulated IndexedDB interruption during archive recovery');
        }
        globalThis.fixture.imported.push(value);
        globalThis.fixture.importedIds?.add(value.originEventId);
      };
      export const hasRecentPlay = async () => false;
    ` : `
      export const getDriveAuthSession = async () => globalThis.fixture.session;
      export const isDriveOAuthConfigured = () => true;
      export const isFirefoxBuild = () => false;
      export const hasFirefoxDriveDataConsent = async () => true;
      export const invalidateDriveAccessToken = async () => {};
      export const disconnectDriveAuth = async () => {};
    ` }));
  } }],
});
const drive = await import(pathToFileURL(out).href);
const stateKey = 'tempoDriveHistoryState';
function reset() {
  globalThis.fixture = {
    settings: { driveSyncEnabled: true, syncIntervalMinutes: 30 },
    session: { accessToken: 'token-a', accountEmail: 'a@example.com', accountSubject: 'google-a' }, cleared: 0, marker: 100,
    writes: 0, listCalls: 0, paginated: false, imported: [], claimed: [],
    suppressed: [], authorized: [],
    stored: { [stateKey]: { acceptedDisableVersion: 100, downloadCreatedCursor: 90,
      lastUploaded: 5, lastImported: 6, lastAuthorizedAccountEmail: 'a@example.com', lastAuthorizedAccountSubject: 'google-a', accountEmail: 'a@example.com' } },
  };
}
globalThis.chrome = {
  storage: { local: {
    get: async key => ({ [key]: globalThis.fixture.stored[key] }),
    set: async values => Object.assign(globalThis.fixture.stored, values),
  } },
  alarms: { clear: async () => true, create: () => {} },
};
globalThis.fetch = async (rawUrl, options = {}) => {
  const url = new URL(rawUrl);
  if (options.method === 'PATCH') {
    fixture.writes++; fixture.marker = 200;
    return Response.json({ id: 'marker', name: 'tempo_history_control_v1.json', modifiedTime: new Date(200).toISOString() });
  }
  if (url.searchParams.get('q')?.startsWith('name =')) {
    fixture.listCalls++;
    const isSecondPage = url.searchParams.has('pageToken');
    return Response.json({
      files: [{ id: isSecondPage ? 'newer' : 'marker', name: 'tempo_history_control_v1.json',
        modifiedTime: new Date(fixture.paginated && isSecondPage ? 300 : fixture.marker).toISOString() }],
      ...(fixture.paginated && !isSecondPage ? { nextPageToken: 'page-2' } : {}),
    });
  }
  // A permanent cleanup failure must not re-enable sync or advance upload state.
  return Response.json({ error: { message: 'Cleanup forbidden' } }, { status: 403 });
};

reset();
fixture.marker = 200;
await assert.rejects(drive.syncDriveHistory(), /Cleanup forbidden/);
assert.equal(fixture.settings.driveSyncEnabled, false);
assert.equal(fixture.stored[stateKey].acceptedDisableVersion, 200);
assert.equal(fixture.stored[stateKey].downloadCreatedCursor, 0);
assert.deepEqual(fixture.suppressed, ['google-a'],
  'a deletion must suppress local history rather than clear upload confirmations');
console.log('  ✓ remote deletion stays disabled after cleanup failure');

reset();
await assert.rejects(drive.deleteDriveHistory(), /Cleanup forbidden/);
assert.equal(fixture.settings.driveSyncEnabled, false);
assert.equal(fixture.stored[stateKey].acceptedDisableVersion, 200);
assert.equal(fixture.stored[stateKey].lastUploaded, 0);
assert.match(fixture.stored[stateKey].lastError, /Cleanup forbidden/);
assert.ok(fixture.writes > 0);
console.log('  ✓ explicit deletion persists the stop and surfaces cleanup errors');

reset();
fixture.session.accountEmail = 'b@example.com';
fixture.session.accountSubject = 'google-b';
await assert.rejects(drive.deleteDriveHistory(), /account changed/);
assert.equal(fixture.writes, 0);
assert.equal(fixture.listCalls, 0);
assert.equal(fixture.settings.driveSyncEnabled, false);
assert.deepEqual(fixture.claimed, ['google-a'], 'old unowned plays must be claimed by A');
assert.deepEqual(fixture.suppressed, [], 'switching accounts must preserve prior upload bookkeeping');
console.log('  ✓ account switching prevents destructive cloud requests');

reset();
fixture.paginated = true;
await assert.rejects(drive.syncDriveHistory(), /Cleanup forbidden/);
assert.equal(fixture.stored[stateKey].acceptedDisableVersion, 300);
assert.equal(fixture.listCalls, 2);
console.log('  ✓ newest control marker is read across all list pages');

for (const marker of [0, -1]) {
  reset();
  fixture.marker = marker;
  await assert.rejects(drive.syncDriveHistory(), /valid deletion marker/);
  assert.equal(fixture.writes, 0);
  assert.equal(fixture.stored[stateKey].acceptedDisableVersion, 100);
  assert.deepEqual(fixture.suppressed, []);
}
console.log('  ✓ invalid server marker timestamps cannot authorize uploads or alter accepted state');

reset();
fixture.stored.tempoDriveDeviceId = 'local-device';
const event = {
  event_id: 'a'.repeat(64), title: 'Valid song', artist: 'Artist', album: null,
  timestamp_utc: 1_700_000_000_000, duration_ms: 180_000, listened_ms: 170_000,
  source_app: 'test', source: 'browser:test', skipped: false, replay_count: 0,
  completion_percentage: 94, pause_count: 0, seek_count: 0, session_id: null,
  site: null, content_type: 'MUSIC', volume_level: 50, total_pause_duration_ms: 0,
  position_updates_count: 10,
};
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const batchId = sha256(`tempo-batch-v1|${event.event_id}`);
const payloads = [null, {
  schema_version: 1, batch_id: batchId, source_device_id: 'remote-device',
  source_device_name: 'Remote browser', source_platform: 'chrome_extension',
  created_at_utc: 1_700_000_000_000, events: [event],
}].map(value => gzipSync(JSON.stringify(value)));
const files = payloads.map((bytes, index) => ({
  id: `batch-${index}`, name: `tempo_history_v1_g100_remote-device_${batchId}.json.gz`,
  size: String(bytes.length), createdTime: new Date(1_700_000_000_000 + index).toISOString(),
  appProperties: { tempo_kind: 'history_batch', tempo_schema: '1', source_device_id: 'remote-device',
    source_platform: 'chrome_extension', tempo_generation: '100', tempo_sha256: sha256(bytes) },
}));
globalThis.fetch = async rawUrl => {
  const url = new URL(rawUrl);
  if (url.searchParams.get('alt') === 'media') {
    return new Response(payloads[Number(url.pathname.split('-').at(-1))]);
  }
  if (url.searchParams.get('q')?.startsWith('name =')) {
    return Response.json({
      files: fixture.marker > 0 ? [{ id: 'marker', modifiedTime: new Date(fixture.marker).toISOString() }] : [],
    });
  }
  return Response.json({ files });
};
const result = await drive.syncDriveHistory();
assert.equal(result.imported, 1);
assert.equal(fixture.imported[0].title, 'Valid song');
assert.equal(fixture.stored[stateKey].downloadCreatedCursor, 1_700_000_000_001);
assert.equal(fixture.stored[stateKey].lastError, null);
assert.equal(fixture.stored[stateKey].rejectedBatches['google-a'].length, 1);
const statusWithRejected = await drive.getDriveSyncStatus();
assert.match(statusWithRejected.lastError, /Google Drive history file\(s\) need attention/);
console.log('  ✓ a JSON null batch is consumed, tracked in rejected batches, and surfaces warning');
reset();
// The module caches a stable local device ID. Make the fixture batch belong to
// that SAME device, rather than pretending the device identity changed mid-run.
fixture.stored.tempoDriveDeviceId = 'local-device';
const ownBytes = gzipSync(JSON.stringify({
  schema_version: 1, batch_id: batchId, source_device_id: 'local-device',
  source_device_name: 'This browser', source_platform: 'chrome_extension',
  created_at_utc: 1_700_000_000_000, events: [event],
}));
payloads[1] = ownBytes;
files[1] = {
  ...files[1],
  name: `tempo_history_v1_g100_local-device_${batchId}.json.gz`,
  size: String(ownBytes.length),
  appProperties: {
    ...files[1].appProperties,
    source_device_id: 'local-device',
    tempo_sha256: sha256(ownBytes),
  },
};
const normalOwn = await drive.syncDriveHistory();
assert.equal(normalOwn.imported, 0, 'normal sync does not replay our own Drive uploads');
const restoredOwn = await drive.restoreDriveHistory();
assert.equal(restoredOwn.imported, 1, 'explicit restoration recovers own deleted local records');
console.log('  ✓ explicit restore recovers own producer batches while incremental sync skips them');

// Account switching A -> B -> A preserves deletion marker and download cursor
reset();
files.length = 0;
fixture.marker = 150;
fixture.stored[stateKey].acceptedDisableVersion = 150;
fixture.stored[stateKey].downloadCreatedCursor = 12345;
fixture.session = { accessToken: 'token-a', accountEmail: 'a@example.com', accountSubject: 'google-a' };
await drive.connectDrive();
assert.equal(fixture.stored[stateKey].acceptedDisableVersion, 150);
assert.equal(fixture.stored[stateKey].downloadCreatedCursor, 12345);

// Switch to B
fixture.marker = 0;
fixture.session = { accessToken: 'token-b', accountEmail: 'b@example.com', accountSubject: 'google-b' };
await drive.connectDrive();
assert.equal(fixture.stored[stateKey].acceptedDisableVersion, 0);
assert.equal(fixture.stored[stateKey].downloadCreatedCursor, 0);

// Switch back to A
fixture.marker = 150;
fixture.session = { accessToken: 'token-a', accountEmail: 'a@example.com', accountSubject: 'google-a' };
fixture.suppressed = [];
await drive.connectDrive();
assert.equal(fixture.stored[stateKey].acceptedDisableVersion, 150);
assert.equal(fixture.stored[stateKey].downloadCreatedCursor, 12345);
assert.deepEqual(fixture.suppressed, [], 'returning to account A must not falsely re-suppress plays');
console.log('  ✓ account switching A -> B -> A restores cursor and does not falsely trigger deletion');

// Incremental results may omit a previously rejected file that is still on
// Drive. Its warning must survive until a complete listing proves deletion.
reset();
files.length = 0;
fixture.stored[stateKey].downloadCreatedCursor = 1_700_500_000_000;
fixture.stored[stateKey].rejectedBatches = {
  'google-a': [{ fileId: 'old-corrupt-file', fileName: 'tempo_history_v1_old.json.gz',
    reason: 'Invalid metadata', recordedAt: 1_700_000_000_000 }],
};
await drive.syncDriveHistory();
assert.equal(fixture.stored[stateKey].rejectedBatches['google-a'].length, 1,
  'a 24h incremental window must not delete old rejection warnings');
await drive.restoreDriveHistory();
assert.deepEqual(fixture.stored[stateKey].rejectedBatches['google-a'], [],
  'a complete empty Drive listing can retire warnings for removed files');
console.log('  ✓ rejected-batch warnings survive incremental scans and clear after full scan');

// A missing tempo_kind appProperty must not hide a Tempo-named batch from
// enumeration; client-side validation must explicitly report the bad file.
reset();
files.length = 0;
files.push({
  id: 'missing-kind', name: `tempo_history_v1_g100_remote-device_${'b'.repeat(64)}.json.gz`,
  size: '20', createdTime: new Date(1_700_000_000_000).toISOString(),
  appProperties: { tempo_schema: '1', source_device_id: 'remote-device',
    source_platform: 'chrome_extension', tempo_generation: '100',
    tempo_sha256: 'c'.repeat(64) },
});
const originalFetch = globalThis.fetch;
let checkedHistoryQuery = false;
globalThis.fetch = async (...args) => {
  const url = new URL(args[0]);
  const query = url.searchParams.get('q') ?? '';
  if (query.startsWith('name contains')) {
    checkedHistoryQuery = true;
    assert.doesNotMatch(query, /appProperties has/,
      'history discovery must not exclude malformed metadata in Drive itself');
  }
  return originalFetch(...args);
};
await drive.syncDriveHistory();
assert.equal(checkedHistoryQuery, true);
assert.equal(fixture.stored[stateKey].rejectedBatches['google-a'][0].fileId, 'missing-kind');
console.log('  ✓ malformed Drive metadata is visible and retained as a rejected batch');

// A transient failure to delete an obsolete Drive generation must not block
// current-generation imports or silently hide incomplete cloud cleanup.
reset();
fixture.marker = 200;
fixture.stored[stateKey].acceptedDisableVersion = 200;
fixture.stored.tempoDriveDeviceId = 'local-device';
const currentBatch = {
  schema_version: 1, batch_id: batchId, source_device_id: 'remote-device',
  source_device_name: 'Remote browser', source_platform: 'chrome_extension',
  created_at_utc: 1_700_000_000_000, events: [event],
};
const currentBytes = gzipSync(JSON.stringify(currentBatch));
files.length = 0;
files.push(
  {
    id: 'obsolete-cleanup-file',
    name: `tempo_history_v1_g100_remote-device_${batchId}.json.gz`,
    createdTime: new Date(1_700_000_000_000).toISOString(),
    appProperties: { tempo_generation: '100' },
  },
  {
    id: 'current-history-file',
    name: `tempo_history_v1_g200_remote-device_${batchId}.json.gz`,
    createdTime: new Date(1_700_000_000_001).toISOString(),
    size: String(currentBytes.length),
    appProperties: {
      tempo_kind: 'history_batch', tempo_schema: '1',
      source_device_id: 'remote-device', source_platform: 'chrome_extension',
      tempo_generation: '200', tempo_sha256: sha256(currentBytes),
    },
  },
);
let staleDeletionFails = true;
globalThis.fetch = async (rawUrl, options = {}) => {
  const url = new URL(rawUrl);
  if (options.method === 'DELETE') {
    if (staleDeletionFails) return Response.json({ error: 'Drive DELETE unavailable' }, { status: 403 });
    files.splice(files.findIndex(file => file.id === 'obsolete-cleanup-file'), 1);
    return new Response(null, { status: 204 });
  }
  if (url.searchParams.get('q')?.startsWith('name =')) {
    return Response.json({ files: [{ id: 'marker', modifiedTime: new Date(200).toISOString() }] });
  }
  if (url.searchParams.get('q')?.startsWith('name contains')) return Response.json({ files });
  if (url.searchParams.get('alt') === 'media' &&
      url.pathname.endsWith('/current-history-file')) return new Response(currentBytes);
  throw new Error(`Unexpected fixture request: ${url}`);
};
const suppressedWarnings = console.warn;
console.warn = () => {};
try {
  const completedDespiteFailedCleanup = await drive.syncDriveHistory();
  assert.equal(completedDespiteFailedCleanup.imported, 1,
    'a failed stale DELETE must not block importing valid current-generation history');
  assert.equal(fixture.stored[stateKey].rejectedBatches['google-a'][0].fileId,
    'obsolete-cleanup-file', 'unfinished cloud cleanup must remain visible');
  assert.match((await drive.getDriveSyncStatus()).lastError, /could not be removed/,
    'the sync status must expose incomplete old-history deletion');

  staleDeletionFails = false;
  await drive.restoreDriveHistory();
  assert.deepEqual(fixture.stored[stateKey].rejectedBatches['google-a'], [],
    'a successful full-history retry must clear the stale cleanup warning');
} finally {
  console.warn = suppressedWarnings;
}
console.log('  ✓ stale cloud cleanup failures remain visible without blocking valid imports');


// The codec-only 100k test cannot prove that the real restore loop survives
// interruption. Exercise the public Restore operation with 100 Drive batches,
// 100,000 persisted origins, out-of-order listing pages, a mid-batch crash,
// and repeated recovery without duplicated listening rows.
reset();
fixture.stored.tempoDriveDeviceId = 'local-device';
fixture.importedIds = new Set();
fixture.failAfterImported = 70_123;
const archiveFirstTimestamp = 1_451_606_400_000;
const archiveStepMs = 3_153_600; // ~52.56 min/track over ten years
const archiveFiles = [];
const archivePayloads = new Map();
const archiveTotal = 100_000;
const archiveBatchSize = 1_000;
for (let offset = 0; offset < archiveTotal; offset += archiveBatchSize) {
  const page = offset / archiveBatchSize;
  const producer = page % 2 === 0 ? 'history-producer-a' : 'history-producer-b';
  const historyEvents = Array.from({ length: Math.min(archiveBatchSize, archiveTotal - offset) }, (_, i) => {
    const index = offset + i;
    return {
      ...event,
      event_id: sha256(`history-ten-years-${index}`),
      title: `Historical song ${index % 57}`,
      timestamp_utc: archiveFirstTimestamp + index * archiveStepMs,
    };
  });
  const historyBatchId = sha256(`tempo-batch-v1|${historyEvents.map(e => e.event_id).join('|')}`);
  const bytes = gzipSync(JSON.stringify({
    schema_version: 1, batch_id: historyBatchId, source_device_id: producer,
    source_device_name: 'Ten-year fixture', source_platform: 'chrome_extension',
    created_at_utc: historyEvents.at(-1).timestamp_utc, events: historyEvents,
  }));
  const id = `history-${page}`;
  archivePayloads.set(id, bytes);
  archiveFiles.push({
    id,
    name: `tempo_history_v1_g100_${producer}_${historyBatchId}.json.gz`,
    size: String(bytes.byteLength),
    createdTime: new Date(1_700_000_000_000 + page * 1000).toISOString(),
    appProperties: {
      tempo_kind: 'history_batch', tempo_schema: '1',
      source_device_id: producer, source_platform: 'chrome_extension',
      tempo_generation: '100', tempo_sha256: sha256(bytes),
    },
  });
}
// Reverse the listing so the client must sort across Drive listing pages.
archiveFiles.reverse();
let archiveListPages = 0;
let archiveDownloads = 0;
globalThis.fetch = async rawUrl => {
  const url = new URL(rawUrl);
  const query = url.searchParams.get('q') ?? '';
  if (query.startsWith('name =')) {
    return Response.json({ files: [{ id: 'marker', modifiedTime: new Date(100).toISOString() }] });
  }
  if (query.startsWith('name contains')) {
    archiveListPages++;
    const start = Number(url.searchParams.get('pageToken') ?? '0');
    const next = Math.min(start + 17, archiveFiles.length);
    return Response.json({
      files: archiveFiles.slice(start, next),
      ...(next < archiveFiles.length ? { nextPageToken: String(next) } : {}),
    });
  }
  if (url.searchParams.get('alt') === 'media') {
    archiveDownloads++;
    const fileId = decodeURIComponent(url.pathname.split('/').at(-1));
    const bytes = archivePayloads.get(fileId);
    assert.ok(bytes, 'only known history files can be downloaded');
    return new Response(bytes);
  }
  throw new Error(`Unexpected ten-year archive request: ${url}`);
};
await assert.rejects(drive.restoreDriveHistory(),
  /Simulated IndexedDB interruption during archive recovery/,
  'a crash after a partial batch must not be silently acknowledged');
assert.equal(fixture.imported.length, 70_123, 'crash stops in the middle of a 1000-event batch');
assert.equal(fixture.importedIds.size, 70_123);
assert.ok(fixture.stored[stateKey].downloadCreatedCursor > 0,
  'large-history restoration checkpoints only previously completed batches');
const resumedArchive = await drive.restoreDriveHistory();
assert.equal(resumedArchive.imported, archiveTotal - 70_123,
  'restart recovers exactly the uncommitted remainder');
assert.equal(resumedArchive.duplicates, 70_123,
  'previously persisted Drive identities are not imported again');
assert.equal(fixture.importedIds.size, archiveTotal, '100,000 distinct playback IDs survive');
assert.equal(fixture.imported.length, archiveTotal, 'no duplicate rows created by interrupted restore');
assert.equal(fixture.imported[0].timestampUtc, archiveFirstTimestamp);
assert.equal(fixture.imported.at(-1).timestampUtc,
  archiveFirstTimestamp + (archiveTotal - 1) * archiveStepMs);
assert.ok(archiveListPages >= 12, 'both restorations paginate a shuffled Drive listing');
assert.ok(archiveDownloads >= 100, 'restore downloads the whole ten-year archive');
assert.equal(fixture.stored[stateKey].lastError, null);
const repeatArchive = await drive.restoreDriveHistory();
assert.equal(repeatArchive.imported, 0);
assert.equal(repeatArchive.duplicates, archiveTotal);
assert.equal(fixture.imported.length, archiveTotal,
  'another full restore remains idempotent');
console.log('  ✓ 100,000-play restore loop resumes after partial commit with no loss or duplicates');

console.log('\n12 Drive lifecycle scenarios passed');


// Stale clients cannot delete current-generation uploads solely because Drive
// appProperties were stripped; a deliberate owner delete can remove malformed old files.
const historyName = (generation) =>
  `tempo_history_v1_g${generation}_device-a_${'a'.repeat(64)}.json.gz`;
const historyFile = (generation, property) => ({
  name: historyName(generation),
  appProperties: property == null ? {} : { tempo_generation: property },
});
for (const ownerDelete of [false, true]) {
  assert.equal(drive.shouldDeleteHistoryBatch(historyFile(200), 200, ownerDelete), false);
  assert.equal(drive.shouldDeleteHistoryBatch(historyFile(201, 'invalid'), 200, ownerDelete), false);
}
assert.equal(drive.shouldDeleteHistoryBatch(historyFile(100, 'invalid'), 200, false), false);
assert.equal(drive.shouldDeleteHistoryBatch(historyFile(100, 'invalid'), 200, true), true);
assert.equal(drive.shouldDeleteHistoryBatch(historyFile(100), 200, false), true);
assert.equal(drive.shouldDeleteHistoryBatch({
  name: 'tempo_history_v1_malformed', appProperties: { tempo_generation: 'invalid' },
}, 200, true), true);
console.log('  ✓ cleanup preserves newer generations despite corrupt/missing properties');

// The Drive uploader must not loop unboundedly or starve valid rows when a
// ten-year queue starts with more than 5,000 unuploadable local events.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const out = join(mkdtempSync(join(tmpdir(), 'tempo-upload-scan-')), 'drive.mjs');
await esbuild.build({
  entryPoints: ['src/background/drive-history.ts'],
  bundle: true, format: 'esm', platform: 'browser', target: 'es2022',
  outfile: out, logLevel: 'silent',
  plugins: [{ name: 'upload-scan-fixtures', setup(build) {
    build.onResolve({ filter: /^\.\/(storage|drive-auth|drive-files)$/ },
      args => ({ path: args.path, namespace: 'fixture' }));
    build.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({
      contents: args.path === './storage' ? `
        export const getSettings = async () => ({ ...globalThis.fixture.settings });
        export const saveSettings = async value => { globalThis.fixture.settings = value; };
        export const getDrivePendingCount = async () => globalThis.fixture.pendingCount ?? 0;
      export const getDrivePendingPlays = async (limit, owner, after) => {
          globalThis.fixture.scanCalls.push({ limit, owner, after });
          return globalThis.fixture.plays
            .filter(play => !globalThis.fixture.marked.some(entry => entry.id === play.id))
            .filter(play => {
              const indexedTime = Number.isFinite(play.timestampUtc) ? play.timestampUtc : 0;
              return !after || indexedTime > after.timestampUtc ||
                (indexedTime === after.timestampUtc && play.id > after.id);
            })
            .slice(0, limit);
        };
        export const ensureLocalOriginEventId = async (id, candidate) => {
          if (id <= 5000) throw new Error('Malformed legacy play');
          return candidate;
        };
        export const markDriveUploaded = async entries => { globalThis.fixture.marked.push(...entries); };
        export const claimUnownedDrivePlays = async () => {};
        export const hasDriveOriginEventId = async () => false;
        export const hasRecentPlay = async () => false;
        export const insertPlay = async () => {};
        export const suppressDeletedDriveHistory = async () => {};
        export const authorizeOlderDriveHistory = async () => {};
        export const getOwnPlayIdentityInputs = async () => [];
      ` : args.path === './drive-auth' ? `
        export const getDriveAuthSession = async () => globalThis.fixture.session;
        export const isDriveOAuthConfigured = () => true;
        export const isFirefoxBuild = () => false;
        export const hasFirefoxDriveDataConsent = async () => true;
        export const invalidateDriveAccessToken = async () => {};
        export const disconnectDriveAuth = async () => {};
      ` : `
        export const FILE_PREFIX = 'tempo_history_v1_';
        export const APP_PROPERTY_GENERATION = 'tempo_generation';
        export const APP_PROPERTY_SHA256 = 'tempo_sha256';
        export const getDisableMarkerVersion = async () => 100;
        export const listBatches = async () => [];
        export const uploadBatch = async (...args) => { globalThis.fixture.uploads.push(args); };
        export const verifiedUpload = () => true;
        export const bumpDisableMarker = async () => 200;
        export const batchGeneration = () => 100;
        export const shouldDeleteHistoryBatch = () => false;
        export const deleteDriveFileStrict = async () => {};
        export const deleteBatchesBeforeGeneration = async () => 0;
        export const driveRequestBytes = async () => new Uint8Array();
      `,
    }));
  } }],
});

const drive = await import(pathToFileURL(out).href);
const stateKey = 'tempoDriveHistoryState';
globalThis.fixture = {
  settings: { driveSyncEnabled: true, syncIntervalMinutes: 30 },
  session: { accessToken: 'token-a', accountEmail: 'a@example.com', accountSubject: 'google-a' },
  stored: { [stateKey]: { downloadCreatedCursor: 0, acceptedDisableVersion: 100,
    lastAuthorizedAccountSubject: 'google-a', lastAuthorizedAccountEmail: 'a@example.com' } },
  scanCalls: [], marked: [], uploads: [],
  plays: Array.from({ length: 5000 }, (_, index) => ({
    id: index + 1, timestampUtc: NaN,
    title: 'Unverified', artist: 'Unknown', originEventId: 'invalid',
    status: 'queued',
  })),
};
fixture.plays.push({
  id: 5001, timestampUtc: 5001, title: 'Later good song', artist: 'Artist',
  sourceApp: 'browser', status: 'queued', durationMs: 180000, listenedMs: 120000,
  replayCount: 0, skipped: false, completionPercentage: 67, pauseCount: 0,
  seekCount: 0, sessionId: '', site: '', contentType: 'MUSIC',
  volumeLevel: 0.6, totalPauseDurationMs: 0, positionUpdatesCount: 0,
});
globalThis.chrome = {
  storage: { local: {
    get: async key => ({ [key]: fixture.stored[key] }),
    set: async value => Object.assign(fixture.stored, value),
  } },
  alarms: { clear: async () => true, create: () => {} },
};

// Suppress expected per-row warnings in this intentionally adversarial case.
const originalWarn = console.warn;
let warnings = 0;
console.warn = () => { warnings++; };
try {
  const first = await drive.syncDriveHistory();
  assert.equal(first.uploaded, 0, '5,000 invalid rows must not trigger an unbounded scan');
  assert.equal(warnings, 5001, 'all invalid rows should be retained and reported');
  assert.equal(fixture.uploads.length, 0);
  assert.match((await drive.getDriveSyncStatus()).lastError, /5000 local play\(s\).*first local ID 1/,
    'malformed local history must be visible in sync status, not just console warnings');
  assert.deepEqual(fixture.stored[stateKey].uploadScanCursors['google-a'],
    { timestampUtc: 0, id: 5000 }, 'malformed timestamps must use the IndexedDB zero-time cursor');
  assert.equal(fixture.scanCalls.reduce((n, call) => n + call.limit, 0), 5000,
    'the scan budget counts examined plays, not only successfully uploaded plays');

  fixture.scanCalls = [];
  warnings = 0;
  const second = await drive.syncDriveHistory();
  assert.equal(second.uploaded, 1, 'next sync must reach valid history past invalid prefix');
  assert.equal(fixture.uploads.length, 1);
  assert.deepEqual(fixture.marked.map(entry => entry.id), [5001]);
  assert.equal(fixture.stored[stateKey].uploadScanCursors['google-a'], undefined,
    'an exhausted queue must wrap on a later sync to detect repaired older plays');
  assert.equal(fixture.scanCalls[0].after.id, 5000);
  assert.match((await drive.getDriveSyncStatus()).lastError, /local play\(s\) could not be uploaded/,
    'a later valid-only resumed page must not hide older invalid records');

  // Simulate the user deleting or repairing the malformed local test rows.
  // Only a complete clean pass can clear the sticky account-scoped warning.
  fixture.plays = fixture.plays.filter(play => play.id === 5001);
  const third = await drive.syncDriveHistory();
  assert.equal(third.uploaded, 0);
  assert.equal((await drive.getDriveSyncStatus()).lastError, null);
  assert.equal(fixture.stored[stateKey].rejectedLocalPlays['google-a'], undefined);
} finally {
  console.warn = originalWarn;
}

// Fractional/negative legacy timestamps are valid IndexedDB index keys but
// invalid wire timestamps. The resumption key must survive state parsing and
// advance beyond all 5k malformed captures on the next sync.
fixture.settings.driveSyncEnabled = true;
fixture.stored[stateKey] = {
  downloadCreatedCursor: 0, acceptedDisableVersion: 100,
  lastAuthorizedAccountSubject: 'google-a',
};
fixture.scanCalls = [];
fixture.marked = [];
fixture.uploads = [];
fixture.plays = Array.from({ length: 5000 }, (_, index) => ({
  id: index + 1, timestampUtc: -0.5,
  title: 'Legacy bad time', artist: 'Artist', status: 'queued',
}));
fixture.plays.push({
  id: 5001, timestampUtc: 5001, title: 'Recovered song', artist: 'Artist',
  sourceApp: 'browser', status: 'queued', durationMs: 180000, listenedMs: 120000,
  replayCount: 0, skipped: false, completionPercentage: 67, pauseCount: 0,
  seekCount: 0, sessionId: '', site: '', contentType: 'MUSIC',
  volumeLevel: 0.6, totalPauseDurationMs: 0, positionUpdatesCount: 0,
});
console.warn = () => {};
try {
  const first = await drive.syncDriveHistory();
  assert.equal(first.uploaded, 0);
  assert.deepEqual(fixture.stored[stateKey].uploadScanCursors['google-a'],
    { timestampUtc: -0.5, id: 5000 },
    'the IndexedDB key must be persisted without rounding');
  const second = await drive.syncDriveHistory();
  assert.equal(second.uploaded, 1,
    'the fractional negative key must be restored rather than restarting at row one');
  assert.equal(fixture.scanCalls[20].after.timestampUtc, -0.5);
  assert.equal(fixture.marked.length, 1);
} finally {
  console.warn = originalWarn;
}
console.log('  ✓ 5,000 malformed events cannot starve valid uploads or hide local-history warnings');
console.log('  ✓ legacy fractional timestamp cursors survive reloading and reach valid history');

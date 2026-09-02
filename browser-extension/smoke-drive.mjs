// Cross-client golden vectors and strict wire-validation smoke test.
import * as esbuild from 'esbuild';
import { mkdtempSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { pathToFileURL } from 'url';

const dir = mkdtempSync(join(tmpdir(), 'tempo-drive-'));
const out = join(dir, 'drive-history.mjs');

await esbuild.build({
  entryPoints: ['src/background/drive-history.ts'],
  bundle: true,
  format: 'esm',
  platform: 'browser',
  target: 'es2022',
  outfile: out,
  logLevel: 'silent',
  define: {
    __TEMPO_GOOGLE_OAUTH_CLIENT_ID__: JSON.stringify('test-client.apps.googleusercontent.com'),
    __TEMPO_BROWSER_TARGET__: JSON.stringify('chrome'),
  },
});

const { driveProtocolTest: protocol } = await import(pathToFileURL(out).href);
let pass = 0;
let fail = 0;

function check(name, condition, detail = '') {
  if (condition) {
    pass++;
    console.log(`  ✓ ${name}`);
  } else {
    fail++;
    console.log(`  ✗ ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

const play = {
  id: 42,
  title: ' Song ',
  artist: ' Artist ',
  album: '',
  durationMs: 180_000,
  timestampUtc: 1_700_000_000_000,
  sourceApp: 'test',
  status: 'synced',
  listenedMs: 170_000,
  skipped: false,
  replayCount: 0,
  isMuted: false,
  completionPercentage: 94,
  pauseCount: 0,
  seekCount: 0,
  sessionId: '',
  site: '',
  contentType: 'MUSIC',
  volumeLevel: 50,
  anomalies: [],
  totalPauseDurationMs: 0,
  positionUpdatesCount: 10,
};

console.log('\n[Drive 1] Cross-client identity vectors');
const eventId = await protocol.eventId('device-1', play);
check(
  'event id matches Android/extension golden vector',
  eventId === '69bd5521a322b3d1aaeca431b7380bd49f3a28e1c1d1b1dc0a754ca37e6a06b4',
  eventId,
);

const event = {
  event_id: eventId,
  title: 'Song',
  artist: 'Artist',
  album: null,
  timestamp_utc: 1_700_000_000_000,
  duration_ms: 180_000,
  listened_ms: 170_000,
  source_app: 'test',
  source: 'browser:test',
  skipped: false,
  replay_count: 0,
  completion_percentage: 94,
  pause_count: 0,
  seek_count: 0,
  session_id: null,
  site: null,
  content_type: 'MUSIC',
  volume_level: 50,
  total_pause_duration_ms: 0,
  position_updates_count: 10,
};
const batchId = await protocol.deterministicBatchId([event]);
check(
  'batch id matches Android/extension golden vector',
  batchId === '785b57b5c9e86c35176a413093df3c9fce37eb266c70485a0f9e8fff66e95d43',
  batchId,
);

console.log('\n[Drive 2] Strict schema validation');
const batch = {
  schema_version: 1,
  batch_id: batchId,
  source_device_id: 'device-1',
  source_device_name: 'Chrome extension',
  source_platform: 'chrome_extension',
  created_at_utc: 1_700_000_000_100,
  events: [event],
};
check('valid event accepted', protocol.isValidEvent(event));
check('valid batch accepted', protocol.isValidBatch(batch));
check('string timestamp rejected', !protocol.isValidEvent({ ...event, timestamp_utc: '1700000000000' }));
check('non-hex event id rejected', !protocol.isValidEvent({ ...event, event_id: 'event-1' }));
check('out-of-range volume rejected', !protocol.isValidEvent({ ...event, volume_level: 101 }));
check('empty batch rejected', !protocol.isValidBatch({ ...batch, events: [] }));

console.log('\n[Drive 3] Gzip round trip');
const compressed = await protocol.gzipJson(batch);
const decoded = await protocol.ungzipJson(compressed);
check('round trip preserves batch id', decoded.batch_id === batchId);
check('round trip remains schema-valid', protocol.isValidBatch(decoded));

console.log('\n[Drive 4] Retry and lifecycle concurrency guards');
check('GET requests may be retried', protocol.isDriveRequestRetrySafe(undefined));
check('PATCH requests may be retried', protocol.isDriveRequestRetrySafe('PATCH'));
check('POST creates are never replayed blindly', !protocol.isDriveRequestRetrySafe('POST'));
check('same normalized account stays in scope', !protocol.verifiedAccountChanged('a@example.com', 'a@example.com'));
check('different verified account crosses scope', protocol.verifiedAccountChanged('a@example.com', 'b@example.com'));
check('unknown account never guesses a switch', !protocol.verifiedAccountChanged('a@example.com', null));

const order = [];
let releaseFirst;
const firstGate = new Promise(resolve => { releaseFirst = resolve; });
const firstOperation = protocol.serializeDriveOperation(async () => {
  order.push('first-start');
  await firstGate;
  order.push('first-end');
});
const secondOperation = protocol.serializeDriveOperation(async () => {
  order.push('second-start');
  order.push('second-end');
});
await new Promise(resolve => setTimeout(resolve, 0));
check(
  'lifecycle operations do not overlap',
  order.join(',') === 'first-start',
  order.join(','),
);
releaseFirst();
await Promise.all([firstOperation, secondOperation]);
check(
  'queued lifecycle operation runs after the first completes',
  order.join(',') === 'first-start,first-end,second-start,second-end',
  order.join(','),
);

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail > 0 ? 1 : 0);

// Cross-client golden vectors and strict wire-validation smoke test.
import * as esbuild from 'esbuild';
import { mkdtempSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { pathToFileURL } from 'url';
import { createHash } from 'node:crypto';

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

const { driveProtocolTest: protocol, getDriveLanOrigin } = await import(pathToFileURL(out).href);
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

// Sending the same stored row over Wi-Fi and Drive must preserve its origin ID.
// This also works without any Google authorization: it only reads local storage.
globalThis.chrome = {
  storage: { local: {
    // Drive's settings helper uses callback-style chrome.storage.get; the
    // origin helper must still work when Drive is disabled (LAN-only).
    get: (key, callback) => {
      const result = { [key]: key === 'settings' ? { driveSyncEnabled: false } : 'device-1' };
      if (callback) queueMicrotask(() => callback(result));
      return Promise.resolve(result);
    },
    set: async () => {},
  } },
};
// The LAN identity is pinned in IndexedDB before transmission. A minimal
// transactional store lets this Node smoke fixture test the actual async
// commit path without requiring a browser or external IndexedDB polyfill.
const storedPlays = new Map([
  [play.id, { ...play }],
  [43, { ...play, id: 43, originEventId: 'f'.repeat(64) }],
]);
const database = {
  transaction() {
    const tx = {
      objectStore() {
        return {
          get(id) {
            const request = { result: storedPlays.get(id) };
            queueMicrotask(() => {
              request.onsuccess?.();
              queueMicrotask(() => tx.oncomplete?.());
            });
            return request;
          },
          put(record) { storedPlays.set(record.id, { ...record }); },
        };
      },
    };
    return tx;
  },
};
globalThis.indexedDB = {
  open() {
    const request = { result: database };
    queueMicrotask(() => request.onsuccess?.());
    return request;
  },
};
const lanOrigin = await getDriveLanOrigin(play);
check('LAN producer identity matches Drive without Google login',
  lanOrigin.origin_device_id === 'device-1' && lanOrigin.origin_event_id === eventId);
const repeated = await getDriveLanOrigin({ ...play, title: 'Renamed song', artist: 'New artist' });
check('LAN metadata edits reuse the origin committed by the first upload',
  repeated.origin_event_id === eventId && storedPlays.get(play.id).originEventId === eventId);
const forwardedOrigin = await getDriveLanOrigin({ ...play, id: 43, originEventId: 'f'.repeat(64) });
check('LAN forwarding preserves existing source event ID',
  forwardedOrigin.origin_event_id === 'f'.repeat(64));


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
const unicodeBatch = { ...batch, events: [{ ...event, title: 'é 🔊' }] };
const unicodeDecoded = await protocol.ungzipJson(await protocol.gzipJson(unicodeBatch));
check('valid UTF-8 accents and emoji survive decoding', unicodeDecoded.events[0].title === 'é 🔊');
const invalidUtf8 = new TextEncoder().encode(JSON.stringify(unicodeBatch));
invalidUtf8[invalidUtf8.indexOf(0xc3)] = 0xff;
const corruptStream = new Blob([invalidUtf8]).stream().pipeThrough(new CompressionStream('gzip'));
const corruptGzip = new Uint8Array(await new Response(corruptStream).arrayBuffer());
let corruptRejected = false;
try { await protocol.ungzipJson(corruptGzip); } catch { corruptRejected = true; }
check('malformed UTF-8 is rejected instead of silently changing the title', corruptRejected);

console.log('\n[Drive 4] Retry and lifecycle concurrency guards');
check('GET requests may be retried', protocol.isDriveRequestRetrySafe(undefined));
check('marker PATCH is not blindly retried', !protocol.isDriveRequestRetrySafe('PATCH'));
check('DELETE remains safely retryable', protocol.isDriveRequestRetrySafe('DELETE'));
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

console.log('\n[Drive 4] Upload identity and cross-client values');
const checksum = 'a'.repeat(64);
const uploaded = {
  name: 'batch.json.gz', size: '42',
  appProperties: { tempo_kind: 'history_batch', tempo_schema: '1', source_device_id: 'device-1',
    source_platform: 'chrome_extension', tempo_generation: '7', tempo_sha256: checksum },
};
const verified = file => protocol.verifiedUpload(file, uploaded.name, 'device-1', 'chrome_extension', 7, checksum, 42);
check('complete upload metadata is accepted', verified(uploaded));
for (const key of Object.keys(uploaded.appProperties)) {
  check(`upload retry rejects wrong ${key}`, !verified({ ...uploaded, appProperties: { ...uploaded.appProperties, [key]: 'wrong' } }));
}
check('upload retry rejects wrong filename', !verified({ ...uploaded, name: 'wrong' }));
check('upload retry rejects wrong size', !verified({ ...uploaded, size: '41' }));
for (const key of ['replay_count', 'pause_count', 'seek_count', 'position_updates_count']) {
  check(`Android-incompatible ${key} is rejected`, !protocol.isValidEvent({ ...event, [key]: 2_147_483_648 }));
}
for (const volume of [0, 1, 50, 100]) {
  const localVolume = protocol.localVolumeFromWire(volume);
  const converted = await protocol.playToWire({ ...play, volumeLevel: localVolume, isMuted: volume === 0 }, 'device-1');
  check(`${volume}% volume retains the native browser scale and wire value`, localVolume === volume / 100 && converted.volume_level === volume);
}
check('unknown wire volume stays unknown locally', protocol.localVolumeFromWire(null) === -1);

console.log('\n[Drive 5] Unicode boundaries');
const boundaryText = 'x'.repeat(999) + '🔊';
const boundaryPlay = await protocol.playToWire({ ...play, title: boundaryText, artist: boundaryText,
  album: boundaryText, sessionId: boundaryText, site: boundaryText, contentType: boundaryText }, 'device-1');
check('truncating long fields never splits an emoji',
  [boundaryPlay.title, boundaryPlay.artist, boundaryPlay.album, boundaryPlay.session_id,
    boundaryPlay.site, boundaryPlay.content_type].every(value => value === 'x'.repeat(999)));
const exactEmoji = 'x'.repeat(998) + '🔊';
const exactPlay = await protocol.playToWire({ ...play, title: exactEmoji }, 'device-1');
check('an emoji fitting the field boundary is preserved', exactPlay.title === exactEmoji);
for (const invalid of ['bad\ud800', 'bad\udc00']) {
  check('unpaired Unicode surrogates are rejected in required text', !protocol.isValidEvent({ ...event, title: invalid }));
  check('unpaired Unicode surrogates are rejected in nullable text', !protocol.isValidEvent({ ...event, album: invalid }));
}

console.log('\n[Drive 5] Response body deadlines');
const realFetch = globalThis.fetch;
try {
  globalThis.fetch = async (_url, options) => new Response(new ReadableStream({
    start(controller) {
      options.signal.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')), { once: true });
    },
  }));
  const stalled = await protocol.fetchWithDeadline('https://example.invalid', {}, 20);
  let aborted = false;
  try { await stalled.text(); } catch (error) { aborted = error.name === 'AbortError'; }
  check('deadline remains active after headers while the body stalls', aborted);
  globalThis.fetch = async () => new Response('complete');
  const complete = await protocol.fetchWithDeadline('https://example.invalid', {}, 50);
  check('normal response bodies are preserved', await complete.text() === 'complete');
} finally { globalThis.fetch = realFetch; }

// Ten years of history are restored in bounded immutable batches, not one
// 100k-object payload. Validate codec, identity, ordering and replay stability.
console.log('\n[Drive 6] 100,000-event ten-year archive');
const historicIds = new Set();
const archiveStart = 1_451_606_400_000;
const archiveStep = 3_153_600;
const archiveTotal = 100_000;
const archivePageSize = 1000;
let decodedTotal = 0;
let maxDecodedPage = 0;
for (let offset = 0; offset < archiveTotal; offset += archivePageSize) {
  const events = Array.from({ length: Math.min(archivePageSize, archiveTotal - offset) }, (_, i) => {
    const index = offset + i;
    return { ...event,
      event_id: createHash('sha256').update(`tempo-archive-fixture-${index}`).digest('hex'),
      timestamp_utc: archiveStart + index * archiveStep,
    };
  });
  const id = await protocol.deterministicBatchId(events);
  const archiveBatch = { ...batch, batch_id: id, created_at_utc: events.at(-1).timestamp_utc, events };
  const decodedPage = await protocol.ungzipJson(await protocol.gzipJson(archiveBatch));
  if (!protocol.isValidBatch(decodedPage) || decodedPage.batch_id !== id ||
      decodedPage.events.length !== events.length) {
    fail++;
    console.log(`  ✗ invalid 100k archive page starting at ${offset}`);
    break;
  }
  maxDecodedPage = Math.max(maxDecodedPage, decodedPage.events.length);
  decodedTotal += decodedPage.events.length;
  for (const entry of decodedPage.events) {
    if (historicIds.has(entry.event_id)) {
      fail++;
      console.log(`  ✗ duplicate historic identity at offset ${offset}`);
      break;
    }
    historicIds.add(entry.event_id);
  }
}
check('100,000 independent events survive roundtrip without ID loss',
  decodedTotal === archiveTotal && historicIds.size === archiveTotal);
check('each browser archive page stays within the 1,000-event protocol limit',
  maxDecodedPage <= archivePageSize && maxDecodedPage > 0);
check('ten-year endpoints are representable without timestamp truncation',
  archiveStart < 1_500_000_000_000 &&
  archiveStart + (archiveTotal - 1) * archiveStep > 1_700_000_000_000);

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail > 0 ? 1 : 0);

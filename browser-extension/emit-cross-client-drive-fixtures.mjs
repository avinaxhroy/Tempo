// Generate real browser-v1 gzip batches consumed by Android and Desktop CI.
// No Google access or credentials required: this tests cross-branch wire compatibility.
import * as esbuild from 'esbuild';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

const outputDirectory = resolve(process.argv[2] || '../interop-fixtures');
mkdirSync(outputDirectory, { recursive: true });
const bundle = join(mkdtempSync(join(tmpdir(), 'tempo-drive-interop-')), 'protocol.mjs');
await esbuild.build({
  entryPoints: ['src/background/drive-protocol.ts'],
  bundle: true, format: 'esm', platform: 'browser', target: 'es2022',
  outfile: bundle, logLevel: 'silent',
});
const { eventId, deterministicBatchId, gzipJson, isValidBatch } =
  await import(pathToFileURL(bundle).href);

const sourceDevice = 'interop-browser';
const baseTimestamp = 1_700_000_000_000;
const events = await Promise.all([42, 43].map(async (id, index) => ({
  event_id: await eventId(sourceDevice, {
    id, timestampUtc: baseTimestamp + index * 800,
    title: 'Été 🔊', artist: 'Björk',
  }),
  title: 'Été 🔊', artist: 'Björk', album: 'Compilation',
  timestamp_utc: baseTimestamp + index * 800,
  duration_ms: 180_000, listened_ms: 170_000,
  source_app: 'Spotify Web', source: 'browser:Spotify Web',
  skipped: false, replay_count: 0, completion_percentage: 94,
  pause_count: 0, seek_count: 0,
  session_id: null, site: 'open.spotify.com', content_type: 'MUSIC',
  volume_level: 50, total_pause_duration_ms: 0, position_updates_count: 10,
})));
if (events[0].event_id === events[1].event_id) throw new Error('Replay identity collision');
const id = await deterministicBatchId(events);
for (const platform of ['chrome_extension', 'firefox_extension']) {
  const batch = {
    schema_version: 1, batch_id: id,
    source_device_id: sourceDevice, source_device_name: 'Tempo interop fixture',
    source_platform: platform, created_at_utc: baseTimestamp + 800,
    events,
  };
  if (!isValidBatch(batch)) throw new Error('Invalid generated browser ' + platform + ' batch');
  const bytes = await gzipJson(batch);
  writeFileSync(join(outputDirectory, platform + '.json.gz'), bytes);
  console.log('Generated ' + platform + ': ' + events.length + ' events / ' + bytes.length + ' gzip bytes');
}

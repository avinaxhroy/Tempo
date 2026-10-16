// Smoke test for sync error taxonomy + backoff helpers (pure functions).
import * as esbuild from 'esbuild';
import { mkdtempSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { pathToFileURL } from 'url';

const dir = mkdtempSync(join(tmpdir(), 'tempo-sync-'));
const out = join(dir, 'sync.mjs');

await esbuild.build({
  entryPoints: ['src/background/sync.ts'],
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node18',
  outfile: out,
  logLevel: 'silent',
});

const mod = await import(pathToFileURL(out).href);
const { isTransientSyncError, backoffDelayMinutes, SyncError, parseLanAcknowledgment } = mod;

let pass = 0, fail = 0;
function check(name, cond, detail = '') {
  if (cond) { pass++; console.log(`  ✓ ${name}`); }
  else { fail++; console.log(`  ✗ ${name}${detail ? ' — ' + detail : ''}`); }
}

console.log('\n[1] Error taxonomy: transient vs fatal');
{
  check('rate_limited is transient', isTransientSyncError('rate_limited'));
  check('server is transient', isTransientSyncError('server'));
  check('network is transient', isTransientSyncError('network'));
  check('unreachable is transient', isTransientSyncError('unreachable'));
  check('battery is transient', isTransientSyncError('battery'));
  check('auth is NOT transient', !isTransientSyncError('auth'));
  check('rejected is NOT transient', !isTransientSyncError('rejected'));
  check('unknown is NOT transient', !isTransientSyncError('unknown'));
}

console.log('\n[2] Exponential backoff: 0.5,1,2,4,8,… capped at 60');
{
  check('1 failure → 0.5 min', backoffDelayMinutes(1) === 0.5, `got ${backoffDelayMinutes(1)}`);
  check('2 failures → 1 min', backoffDelayMinutes(2) === 1, `got ${backoffDelayMinutes(2)}`);
  check('3 failures → 2 min', backoffDelayMinutes(3) === 2, `got ${backoffDelayMinutes(3)}`);
  check('4 failures → 4 min', backoffDelayMinutes(4) === 4, `got ${backoffDelayMinutes(4)}`);
  check('5 failures → 8 min', backoffDelayMinutes(5) === 8, `got ${backoffDelayMinutes(5)}`);
  check('10 failures → capped at 60', backoffDelayMinutes(10) === 60, `got ${backoffDelayMinutes(10)}`);
  check('0 failures → 0.5 min floor', backoffDelayMinutes(0) === 0.5, `got ${backoffDelayMinutes(0)}`);
}

console.log('\n[3] SyncError carries kind, status, retryAfter');
{
  const e = new SyncError('boom', 'rate_limited', 429, 30_000);
  check('kind preserved', e.kind === 'rate_limited');
  check('httpStatus preserved', e.httpStatus === 429);
  check('retryAfterMs preserved', e.retryAfterMs === 30_000);
  check('is an Error', e instanceof Error);
  const auth = new SyncError('denied', 'auth', 401);
  check('auth error has no retryAfter', auth.retryAfterMs === undefined);
}

console.log('\n[4] LAN response must explicitly confirm receipt');
for (const bad of ['', 'ok', '{}', 'null', '{"ok":false}', '{"ok":"true"}']) {
  let rejectedTransiently = false;
  try { parseLanAcknowledgment(bad); }
  catch (error) {
    rejectedTransiently = error instanceof SyncError && isTransientSyncError(error.kind);
  }
  check('unconfirmed HTTP 200 retains queue: ' + JSON.stringify(bad), rejectedTransiently);
}
const acknowledged = parseLanAcknowledgment('{"ok":true,"accepted":1,"duplicates":0}');
check('explicitly acknowledged LAN batch is accepted', acknowledged.ok === true);

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail > 0 ? 1 : 0);

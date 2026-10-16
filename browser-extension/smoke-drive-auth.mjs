// Exercise public account authorization through a stalled and a normal userinfo response.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { createHash, webcrypto } from 'node:crypto';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const out = join(mkdtempSync(join(tmpdir(), 'tempo-drive-auth-')), 'auth.mjs');
await esbuild.build({
  entryPoints: ['src/background/drive-auth.ts'], bundle: true, format: 'esm', platform: 'browser',
  target: 'es2022', outfile: out, logLevel: 'silent', define: {
    __TEMPO_BROWSER_TARGET__: JSON.stringify('chrome'),
    __TEMPO_GOOGLE_OAUTH_CLIENT_ID__: JSON.stringify('test-client.apps.googleusercontent.com'),
  },
});
const auth = await import(pathToFileURL(out).href);
let invalidated = 0;
globalThis.chrome = {
  runtime: {}, identity: {
    getAuthToken(_options, callback) { callback('test-token'); },
    removeCachedAuthToken(_options, callback) { invalidated++; callback(); },
  },
};
const nativeTimeout = globalThis.setTimeout;
globalThis.setTimeout = (callback, milliseconds, ...args) =>
  nativeTimeout(callback, milliseconds === 30_000 ? 20 : milliseconds, ...args);
globalThis.fetch = async (_url, options) => new Response(new ReadableStream({
  start(stream) {
    options.signal.addEventListener('abort', () => stream.error(new DOMException('Aborted', 'AbortError')));
  },
}));
assert.equal(await auth.getDriveAuthSession(false), null);
assert.equal(invalidated, 1);
console.log('  ✓ stalled identity response times out and cannot authorize an unknown account');

globalThis.fetch = async () => Response.json({ email: ' verified@example.com ', sub: 'verified-uid' });
assert.deepEqual(await auth.getDriveAuthSession(false), {
  accessToken: 'test-token', accountEmail: 'verified@example.com', accountSubject: 'verified-uid',
});
assert.equal(invalidated, 1);
globalThis.setTimeout = nativeTimeout;
console.log('  ✓ successful identity verification retains the authorized token and normalized email');
// Firefox must use OAuth authorization-code + PKCE rather than exposing a
// bearer token in a redirect URL fragment. Test the REAL auth module with a
// deterministic simulated provider, including the verifier/challenge pairing.
globalThis.crypto ??= webcrypto;
const firefoxBundle = join(mkdtempSync(join(tmpdir(), 'tempo-firefox-auth-')), 'auth.mjs');
await esbuild.build({
  entryPoints: ['src/background/drive-auth.ts'], bundle: true, format: 'esm', platform: 'browser',
  target: 'es2022', outfile: firefoxBundle, logLevel: 'silent', define: {
    __TEMPO_BROWSER_TARGET__: JSON.stringify('firefox'),
    __TEMPO_GOOGLE_OAUTH_CLIENT_ID__: JSON.stringify('firefox-test-client.apps.googleusercontent.com'),
  },
});
const firefoxAuth = await import(pathToFileURL(firefoxBundle).href);
const saved = {};
let tokenExchanges = 0;
let mode = 'valid';
globalThis.chrome = {
  runtime: {},
  storage: {
    local: {
      get: async (key) => ({ [key]: saved[key] }),
      set: async (obj) => Object.assign(saved, obj),
      remove: async (key) => { delete saved[key]; },
    },
  },
  permissions: { getAll: async () => ({
    data_collection: ['personallyIdentifyingInfo', 'browsingActivity', 'websiteContent'],
  }) },
  identity: {
    getRedirectURL: () => 'https://test-subdomain.extensions.allizom.org/',
    async launchWebAuthFlow({ url, interactive }) {
      if (!interactive) throw new Error('Requires user gesture');
      const parsed = new URL(url);
      assert.equal(parsed.searchParams.get('response_type'), 'code',
        'Firefox must not use implicit response_type=token');
      assert.equal(parsed.searchParams.get('code_challenge_method'), 'S256');
      assert.equal(parsed.searchParams.get('redirect_uri'),
        'http://127.0.0.1/mozoauth2/test-subdomain');
      const state = parsed.searchParams.get('state');
      globalThis.expectedChallenge = parsed.searchParams.get('code_challenge');
      const callbackBase = mode === 'wrong-origin'
        ? 'http://evil.example/mozoauth2/test-subdomain'
        : 'http://127.0.0.1/mozoauth2/test-subdomain';
      return `${callbackBase}?state=${mode === 'wrong-state' ? 'attacker' : state}&code=test-authorization-code`;
    },
  },
};
globalThis.fetch = async (url, options) => {
  if (url === 'https://oauth2.googleapis.com/token') {
    tokenExchanges++;
    const body = new URLSearchParams(options.body);
    assert.equal(options.method, 'POST');
    assert.equal(body.get('grant_type'), 'authorization_code');
    assert.equal(body.get('code'), 'test-authorization-code');
    assert.equal(body.get('client_secret'), null);
    const verifier = body.get('code_verifier');
    assert.match(verifier, /^[A-Za-z0-9_-]{43,128}$/);
    const challenge = createHash('sha256').update(verifier).digest('base64url');
    assert.equal(challenge, globalThis.expectedChallenge);
    return Response.json({ access_token: 'firefox-test-token', expires_in: 3600 });
  }
  if (url === 'https://openidconnect.googleapis.com/v1/userinfo') {
    return Response.json({ email: ' firefox@example.com ', sub: 'firefox-uid' });
  }
  throw new Error('Unexpected auth URL ' + url);
};
assert.deepEqual(await firefoxAuth.getDriveAuthSession(true), {
  accessToken: 'firefox-test-token', accountEmail: 'firefox@example.com', accountSubject: 'firefox-uid',
});
assert.equal(tokenExchanges, 1);
assert.ok(saved.tempoDriveFirefoxAuth.expiresAt > Date.now());
console.log('  ✓ Firefox PKCE exchange and verified Google account identity');

for (const invalid of ['wrong-state', 'wrong-origin']) {
  mode = invalid;
  await firefoxAuth.invalidateDriveAccessToken('firefox-test-token');
  await assert.rejects(firefoxAuth.getDriveAuthSession(true), /state validation|callback location/);
  assert.equal(tokenExchanges, 1, 'invalid callback must never reach token endpoint');
}
console.log('  ✓ Firefox rejects forged callback state and redirect origin');

console.log('\n4 Drive authorization scenarios passed');

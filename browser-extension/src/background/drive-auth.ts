declare const __TEMPO_GOOGLE_OAUTH_CLIENT_ID__: string;
declare const __TEMPO_BROWSER_TARGET__: 'chrome' | 'firefox';

const GOOGLE_USERINFO_URL = 'https://openidconnect.googleapis.com/v1/userinfo';
const GOOGLE_AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const GOOGLE_TOKEN_URL = 'https://oauth2.googleapis.com/token';
const GOOGLE_TOKEN_TIMEOUT_MS = 30_000;
const FIREFOX_AUTH_STORAGE_KEY = 'tempoDriveFirefoxAuth';
const TOKEN_EXPIRY_SAFETY_MS = 60_000;
const GOOGLE_USERINFO_TIMEOUT_MS = 30_000;

export const FIREFOX_DRIVE_DATA_COLLECTION = [
  'personallyIdentifyingInfo',
  'browsingActivity',
  'websiteContent',
] as const;

interface StoredFirefoxAuth {
  accessToken: string;
  expiresAt: number;
  accountEmail: string | null;
  accountSubject?: string;
}

export interface DriveAuthSession {
  accessToken: string;
  accountEmail: string | null;
  accountSubject: string;
}

export function isDriveOAuthConfigured(): boolean {
  return __TEMPO_GOOGLE_OAUTH_CLIENT_ID__.trim().length > 0;
}

export function isFirefoxBuild(): boolean {
  return __TEMPO_BROWSER_TARGET__ === 'firefox';
}

/**
 * Firefox 140+ has a separate built-in consent channel for data that leaves the
 * add-on. Drive sync is optional, so the popup requests these categories only
 * when the user explicitly turns the feature on.
 */
export async function hasFirefoxDriveDataConsent(): Promise<boolean> {
  if (!isFirefoxBuild()) return true;
  try {
    const granted = await (chrome.permissions as any).getAll();
    const dataCollection: string[] = granted?.data_collection ?? [];
    return FIREFOX_DRIVE_DATA_COLLECTION.every(type => dataCollection.includes(type));
  } catch {
    // Firefox versions supported by Tempo expose the built-in consent API. If a
    // custom build does not, fail closed rather than silently transmitting data.
    return false;
  }
}

export async function getDriveAuthSession(interactive = false): Promise<DriveAuthSession | null> {
  if (!isDriveOAuthConfigured()) return null;
  return isFirefoxBuild()
    ? getFirefoxSession(interactive)
    : getChromeSession(interactive);
}

export async function disconnectDriveAuth(): Promise<void> {
  if (isFirefoxBuild()) {
    await chrome.storage.local.remove(FIREFOX_AUTH_STORAGE_KEY);
    return;
  }

  const token = await getChromeAuthToken(false).catch(() => null);
  if (token) await invalidateDriveAccessToken(token);
}

/** Remove only the unusable short-lived token; account-bound sync state stays intact. */
export async function invalidateDriveAccessToken(accessToken: string): Promise<void> {
  if (isFirefoxBuild()) {
    const stored = await loadFirefoxAuth().catch(() => null);
    if (!stored || stored.accessToken === accessToken) {
      await chrome.storage.local.remove(FIREFOX_AUTH_STORAGE_KEY);
    }
    return;
  }

  try {
    await new Promise<void>((resolve) => {
      const api = chrome.identity as any;
      if (typeof api.removeCachedAuthToken !== 'function') {
        resolve();
        return;
      }
      let settled = false;
      const done = () => {
        if (settled) return;
        settled = true;
        resolve();
      };
      const result = api.removeCachedAuthToken({ token: accessToken }, done);
      if (result && typeof result.then === 'function') result.then(done).catch(done);
    });
  } catch { /* best effort */ }
}

async function getChromeSession(interactive: boolean): Promise<DriveAuthSession | null> {
  const token = await getChromeAuthToken(interactive);
  if (!token) return null;

  // Drive cursors and uploaded flags are scoped to one Google account. Never
  // accept a token whose account identity cannot be verified, otherwise a
  // transient userinfo failure could make a later account switch look safe.
  const identity = await fetchGoogleIdentity(token);
  if (!identity) {
    await invalidateDriveAccessToken(token);
    if (!interactive) return null;
    throw new Error('Google account identity could not be verified. Try connecting again.');
  }
  return { accessToken: token, ...identity };
}

async function getChromeAuthToken(interactive: boolean): Promise<string | null> {
  try {
    const identity = chrome.identity as any;
    if (typeof identity.getAuthToken !== 'function') return null;

    return await new Promise<string | null>((resolve, reject) => {
      let settled = false;
      const done = (value: any) => {
        if (settled) return;
        settled = true;
        const err = chrome.runtime.lastError;
        if (err) {
          if (!interactive) resolve(null);
          else reject(new Error(err.message));
          return;
        }
        if (typeof value === 'string') resolve(value);
        else if (value && typeof value.token === 'string') resolve(value.token);
        else resolve(null);
      };

      try {
        const maybePromise = identity.getAuthToken({ interactive }, done);
        if (maybePromise && typeof maybePromise.then === 'function') {
          maybePromise.then(done).catch((err: Error) => {
            if (settled) return;
            settled = true;
            if (!interactive) resolve(null);
            else reject(err);
          });
        }
      } catch (err) {
        if (!interactive) resolve(null);
        else reject(err);
      }
    });
  } catch (err) {
    if (!interactive) return null;
    throw err;
  }
}

async function getFirefoxSession(interactive: boolean): Promise<DriveAuthSession | null> {
  if (!(await hasFirefoxDriveDataConsent())) return null;

  const stored = await loadFirefoxAuth();
  if (stored && stored.expiresAt - TOKEN_EXPIRY_SAFETY_MS > Date.now() && stored.accountEmail && stored.accountSubject) {
    // Reverify even cached access tokens. A previously cached email is not an
    // immutable account identity, and explicit account switching must fail closed.
    const identity = await fetchGoogleIdentity(stored.accessToken);
    if (identity && identity.accountSubject === stored.accountSubject) {
      return { accessToken: stored.accessToken, ...identity };
    }
    await chrome.storage.local.remove(FIREFOX_AUTH_STORAGE_KEY);
  }
  if (stored && (!stored.accountEmail || !stored.accountSubject)) {
    // Migrate old state that could persist a token without a verified identity.
    await chrome.storage.local.remove(FIREFOX_AUTH_STORAGE_KEY);
  }

  // Firefox does not expose Chrome's managed getAuthToken cache. Once the
  // short-lived Google token expires, first try launchWebAuthFlow non-interactively
  // with prompt=none. If Google still has an authorized browser session, this
  // renews the token without interrupting playback or requiring the popup.
  const silent = await authorizeFirefox(false).catch(() => null);
  if (silent) return silent;
  if (!interactive) return null;

  return authorizeFirefox(true);
}

/**
 * Google requires OAuth redirect domains to be owned/registrable by the app.
 * Firefox's normal identity.getRedirectURL() lives on Mozilla's extension
 * domain, which extension authors don't own. Firefox 86+ explicitly supports a
 * loopback alias derived from that generated redirect for OAuth providers such
 * as Google. Tempo's minimum Firefox version is 140, so always use that alias.
 */
function getFirefoxGoogleRedirectUri(): string {
  const generated = new URL(chrome.identity.getRedirectURL());
  const subdomain = generated.hostname.split('.')[0]?.trim();
  if (!subdomain) throw new Error('Firefox OAuth redirect identity is unavailable');
  return `http://127.0.0.1/mozoauth2/${subdomain}`;
}

/** Base64url without padding, as required for an RFC 7636 PKCE challenge. */
function base64Url(bytes: Uint8Array): string {
  let raw = '';
  for (const byte of bytes) raw += String.fromCharCode(byte);
  return btoa(raw).replace(/[+]/g, '-').replace(/[/]/g, '_').replace(/=+$/g, '');
}

async function newPkcePair(): Promise<{ verifier: string; challenge: string }> {
  const random = crypto.getRandomValues(new Uint8Array(32));
  const verifier = base64Url(random);
  const challenge = base64Url(new Uint8Array(await crypto.subtle.digest(
    'SHA-256', new TextEncoder().encode(verifier),
  )));
  return { verifier, challenge };
}

async function exchangeFirefoxAuthorizationCode(
  code: string, verifier: string, redirectUri: string,
): Promise<{ accessToken: string; expiresIn: number }> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), GOOGLE_TOKEN_TIMEOUT_MS);
  try {
    // The public Firefox extension MUST NOT embed a client secret. Google
    // exchanges a one-time authorization code using its PKCE verifier.
    const response = await fetch(GOOGLE_TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        code,
        client_id: __TEMPO_GOOGLE_OAUTH_CLIENT_ID__,
        code_verifier: verifier,
        redirect_uri: redirectUri,
        grant_type: 'authorization_code',
      }).toString(),
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`Google token exchange failed (HTTP ${response.status})`);
    const result = await response.json() as {
      access_token?: unknown; expires_in?: unknown;
    };
    if (typeof result.access_token !== 'string' || !result.access_token) {
      throw new Error('Google token exchange did not return an access token');
    }
    const expiresIn = Number(result.expires_in);
    if (!Number.isFinite(expiresIn) || expiresIn <= 0) {
      throw new Error('Google returned an invalid token expiry');
    }
    return { accessToken: result.access_token, expiresIn };
  } finally {
    // Includes response body reading, not only the initial HTTP headers.
    clearTimeout(timeout);
  }
}

async function authorizeFirefox(interactive: boolean): Promise<DriveAuthSession | null> {
  const state = randomState();
  const redirectUri = getFirefoxGoogleRedirectUri();
  const { verifier, challenge } = await newPkcePair();
  const params = new URLSearchParams({
    client_id: __TEMPO_GOOGLE_OAUTH_CLIENT_ID__,
    redirect_uri: redirectUri,
    response_type: 'code',
    code_challenge: challenge,
    code_challenge_method: 'S256',
    scope: [
      'openid',
      'email',
      'https://www.googleapis.com/auth/drive.appdata',
    ].join(' '),
    include_granted_scopes: 'true',
    prompt: interactive ? 'select_account' : 'none',
    state,
  });

  let responseUrl: string | undefined;
  try {
    responseUrl = await chrome.identity.launchWebAuthFlow({
      url: `${GOOGLE_AUTH_URL}?${params.toString()}`,
      interactive,
    });
  } catch (err) {
    if (!interactive) return null;
    throw err;
  }
  if (!responseUrl) return null;

  const parsed = new URL(responseUrl);
  const expectedRedirect = new URL(redirectUri);
  if (parsed.origin !== expectedRedirect.origin ||
      parsed.pathname !== expectedRedirect.pathname ||
      parsed.hash || parsed.searchParams.getAll('state').length !== 1
  ) {
    throw new Error('Google OAuth callback location or parameters are invalid');
  }
  if (parsed.searchParams.get('state') !== state) {
    throw new Error('Google OAuth state validation failed');
  }
  const error = parsed.searchParams.get('error');
  if (error) {
    if (!interactive) return null;
    throw new Error(`Google authorization failed: ${error}`);
  }
  if (parsed.searchParams.getAll('code').length !== 1) {
    if (!interactive) return null;
    throw new Error('Google authorization returned an invalid code');
  }
  const code = parsed.searchParams.get('code');
  if (!code) {
    if (!interactive) return null;
    throw new Error('Google authorization did not return an authorization code');
  }
  const { accessToken, expiresIn } = await exchangeFirefoxAuthorizationCode(
    code, verifier, redirectUri,
  );
  const identity = await fetchGoogleIdentity(accessToken);
  if (!identity) {
    if (!interactive) return null;
    throw new Error('Google account identity could not be verified. Try connecting again.');
  }
  const auth: StoredFirefoxAuth = {
    accessToken,
    expiresAt: Date.now() + Math.floor(expiresIn) * 1000,
    accountEmail: identity.accountEmail,
    accountSubject: identity.accountSubject,
  };
  await chrome.storage.local.set({ [FIREFOX_AUTH_STORAGE_KEY]: auth });
  return { accessToken, ...identity };
}

async function loadFirefoxAuth(): Promise<StoredFirefoxAuth | null> {
  const value = await chrome.storage.local.get(FIREFOX_AUTH_STORAGE_KEY);
  const auth = value[FIREFOX_AUTH_STORAGE_KEY] as StoredFirefoxAuth | undefined;
  if (!auth || typeof auth.accessToken !== 'string' || typeof auth.expiresAt !== 'number') return null;
  return auth;
}

async function fetchGoogleIdentity(accessToken: string): Promise<{ accountEmail: string; accountSubject: string } | null> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), GOOGLE_USERINFO_TIMEOUT_MS);
  try {
    const response = await fetch(GOOGLE_USERINFO_URL, {
      headers: { Authorization: `Bearer ${accessToken}` },
      signal: controller.signal,
    });
    if (!response.ok) return null;
    const data = await response.json() as { email?: unknown; sub?: unknown };
    if (typeof data.email !== 'string' || !data.email.trim() ||
        typeof data.sub !== 'string' || !data.sub.trim()) return null;
    return { accountEmail: data.email.trim(), accountSubject: data.sub.trim() };
  } catch {
    return null;
  } finally {
    // Keep the deadline through response.json(), not just the HTTP headers.
    clearTimeout(timeout);
  }
}

function randomState(): string {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
}

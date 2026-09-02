declare const __TEMPO_GOOGLE_OAUTH_CLIENT_ID__: string;
declare const __TEMPO_BROWSER_TARGET__: 'chrome' | 'firefox';

const GOOGLE_USERINFO_URL = 'https://openidconnect.googleapis.com/v1/userinfo';
const GOOGLE_AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const FIREFOX_AUTH_STORAGE_KEY = 'tempoDriveFirefoxAuth';
const TOKEN_EXPIRY_SAFETY_MS = 60_000;

export const FIREFOX_DRIVE_DATA_COLLECTION = [
  'personallyIdentifyingInfo',
  'browsingActivity',
  'websiteContent',
] as const;

interface StoredFirefoxAuth {
  accessToken: string;
  expiresAt: number;
  accountEmail: string | null;
}

export interface DriveAuthSession {
  accessToken: string;
  accountEmail: string | null;
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
  const accountEmail = await fetchGoogleEmail(token);
  if (!accountEmail) {
    await invalidateDriveAccessToken(token);
    if (!interactive) return null;
    throw new Error('Google account identity could not be verified. Try connecting again.');
  }
  return { accessToken: token, accountEmail };
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
  if (stored && stored.expiresAt - TOKEN_EXPIRY_SAFETY_MS > Date.now() && stored.accountEmail) {
    return { accessToken: stored.accessToken, accountEmail: stored.accountEmail };
  }
  if (stored && !stored.accountEmail) {
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

async function authorizeFirefox(interactive: boolean): Promise<DriveAuthSession | null> {
  const state = randomState();
  const redirectUri = getFirefoxGoogleRedirectUri();
  const params = new URLSearchParams({
    client_id: __TEMPO_GOOGLE_OAUTH_CLIENT_ID__,
    redirect_uri: redirectUri,
    response_type: 'token',
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
    // A silent flow is expected to fail when Google needs UI (login_required,
    // interaction_required, etc.). Treat that as "needs interactive auth" rather
    // than surfacing it as an auto-sync error.
    if (!interactive) return null;
    throw err;
  }
  if (!responseUrl) return null;

  const parsed = new URL(responseUrl);
  const hash = new URLSearchParams(parsed.hash.replace(/^#/, ''));
  if (hash.get('state') !== state) throw new Error('Google OAuth state validation failed');
  const error = hash.get('error');
  if (error) {
    if (!interactive) return null;
    throw new Error(`Google authorization failed: ${error}`);
  }

  const accessToken = hash.get('access_token');
  if (!accessToken) {
    if (!interactive) return null;
    throw new Error('Google authorization did not return an access token');
  }

  const expiresInRaw = Number(hash.get('expires_in') ?? '3600');
  const expiresIn = Number.isFinite(expiresInRaw) ? Math.max(60, expiresInRaw) : 3600;
  const accountEmail = await fetchGoogleEmail(accessToken);
  if (!accountEmail) {
    if (!interactive) return null;
    throw new Error('Google account identity could not be verified. Try connecting again.');
  }
  const auth: StoredFirefoxAuth = {
    accessToken,
    expiresAt: Date.now() + expiresIn * 1000,
    accountEmail,
  };
  await chrome.storage.local.set({ [FIREFOX_AUTH_STORAGE_KEY]: auth });
  return { accessToken, accountEmail };
}

async function loadFirefoxAuth(): Promise<StoredFirefoxAuth | null> {
  const value = await chrome.storage.local.get(FIREFOX_AUTH_STORAGE_KEY);
  const auth = value[FIREFOX_AUTH_STORAGE_KEY] as StoredFirefoxAuth | undefined;
  if (!auth || typeof auth.accessToken !== 'string' || typeof auth.expiresAt !== 'number') return null;
  return auth;
}

async function fetchGoogleEmail(accessToken: string): Promise<string | null> {
  try {
    const response = await fetch(GOOGLE_USERINFO_URL, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!response.ok) return null;
    const data = await response.json() as { email?: unknown };
    return typeof data.email === 'string' && data.email.trim() ? data.email.trim() : null;
  } catch {
    return null;
  }
}

function randomState(): string {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
}

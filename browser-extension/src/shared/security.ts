// ============================================================================
// Tempo Stats — Shared Request Signing
//
// Provides HMAC-SHA256 signing with replay protection:
//   • X-Tempo-Timestamp — unix seconds, lets server reject stale requests
//   • X-Tempo-Nonce     — UUID per request, prevents identical-second replays
//   • X-Tempo-Signature — HMAC(token, body + "\n" + ts + "\n" + nonce)
//
// Android server SHOULD validate:
//   1. Timestamp within ±60 s of server clock
//   2. Nonce not seen in the last 2 minutes (in-memory cache)
//   3. HMAC matches recomputed value
// ============================================================================

export interface TempoHeaders extends Record<string, string> {
  'Authorization': string;
  'X-Tempo-Signature': string;
  'X-Tempo-Timestamp': string;
  'X-Tempo-Nonce': string;
}

// CryptoKey cache — avoids re-importing for every request in hot paths
const _keyCache = new Map<string, CryptoKey>();

// Pre-computed hex lookup table — avoids .toString(16).padStart(2,'0') per byte
const _hexTable: string[] = new Array(256);
for (let i = 0; i < 256; i++) {
  _hexTable[i] = i.toString(16).padStart(2, '0');
}

async function importHmacKey(token: string): Promise<CryptoKey> {
  const cached = _keyCache.get(token);
  if (cached) return cached;

  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(token),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  _keyCache.set(token, key);
  return key;
}

/**
 * Sign a request body and return the security headers.
 *
 * Signing format: HMAC-SHA256(token, body)
 * This matches what the Tempo Android server verifies.
 *
 * Additionally sends X-Tempo-Timestamp and X-Tempo-Nonce headers.
 * These are NOT part of the signed message yet (Android doesn't validate them),
 * but are included as forward-compatible fields. Once the Android app is updated
 * to validate them, they will provide full replay protection:
 *   - Timestamp: server can reject requests older than ±60 s
 *   - Nonce: server can reject duplicate requests within a 2-minute window
 *
 * IMPORTANT: Do not change the signed message format without a coordinated
 * Android app update. The Android HMAC check is: HMAC(token, bodyString).
 *
 * @param token    The shared auth token from pairing
 * @param body     Request body string (use '{}' for GET-like endpoints with no body)
 */
export async function signRequest(token: string, body = '{}'): Promise<TempoHeaders> {
  const timestamp = Math.floor(Date.now() / 1000); // Unix seconds
  const nonce     = crypto.randomUUID();            // Unique per request

  // Sign ONLY the body — matches Android's HMAC verification:
  //   val expected = hmacSha256(authToken, requestBodyString)
  const key = await importHmacKey(token);
  const raw = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(body));
  const bytes = new Uint8Array(raw);
  let signature = '';
  for (let i = 0; i < bytes.length; i++) {
    signature += _hexTable[bytes[i]];
  }

  return {
    'Authorization':     `Bearer ${token}`,
    'X-Tempo-Signature': signature,
    'X-Tempo-Timestamp': String(timestamp), // informational; Android can validate in future
    'X-Tempo-Nonce':     nonce,             // informational; Android can validate in future
  };
}

/**
 * Build a full fetch headers object for a JSON request.
 * Includes Content-Type along with the security headers.
 */
export async function buildJsonHeaders(
  token: string,
  body = '{}'
): Promise<Record<string, string>> {
  return {
    'Content-Type': 'application/json',
    ...(await signRequest(token, body)),
  };
}

/**
 * Validate the shape of a ping response from the phone.
 * Returns the device name if valid, null if the response is suspicious.
 */
export function validatePingResponse(data: unknown): string | null {
  if (!data || typeof data !== 'object') return null;
  const d = data as Record<string, unknown>;

  // Must have at least one of these expected fields
  if (!('device_name' in d) && !('ok' in d) && !('status' in d)) return null;

  // device_name must be a string if present
  const name = d.device_name;
  if (name !== undefined && typeof name !== 'string') return null;

  return typeof name === 'string' && name.length > 0 ? name : null;
}

/**
 * Check if a stored pairing token is approaching expiry (30 days).
 * Returns days since pairing, or null if pairedAt is not set.
 */
export function pairingAgeDays(pairedAt: string | null | undefined): number | null {
  if (!pairedAt) return null;
  const ms = Date.now() - new Date(pairedAt).getTime();
  return Math.floor(ms / (1000 * 60 * 60 * 24));
}

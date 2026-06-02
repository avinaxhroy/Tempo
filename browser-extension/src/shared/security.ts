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

export async function signRequest(token: string, body = '{}'): Promise<TempoHeaders> {
  const timestamp = Math.floor(Date.now() / 1000);
  const nonce     = crypto.randomUUID();

  const signedMessage = body + '\n' + timestamp + '\n' + nonce;

  const key = await importHmacKey(token);
  const raw = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(signedMessage));
  const bytes = new Uint8Array(raw);
  let signature = '';
  for (let i = 0; i < bytes.length; i++) {
    signature += _hexTable[bytes[i]];
  }

  return {
    'Authorization':     `Bearer ${token}`,
    'X-Tempo-Signature': signature,
    'X-Tempo-Timestamp': String(timestamp),
    'X-Tempo-Nonce':     nonce,
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
 * Sign a WebSocket message payload and return the signature envelope.
 * Signing format: HMAC-SHA256(token, JSON.stringify(payload) + "\n" + ts)
 */
export async function signWsMessage(token: string, payload: unknown): Promise<{ sig: string; ts: number }> {
  const ts = Math.floor(Date.now() / 1000);
  const message = JSON.stringify(payload) + '\n' + ts;
  const key = await importHmacKey(token);
  const raw = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(message));
  const bytes = new Uint8Array(raw);
  let sig = '';
  for (let i = 0; i < bytes.length; i++) {
    sig += _hexTable[bytes[i]];
  }
  return { sig, ts };
}

/**
 * Verify a signed WebSocket message.
 * Returns true if the signature is valid and the message is within ±120s of current time.
 */
export async function verifyWsMessage(token: string, payload: unknown, sig: string, ts: number): Promise<boolean> {
  if (typeof sig !== 'string' || sig.length !== 64) return false;
  if (typeof ts !== 'number' || ts <= 0) return false;

  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - ts) > 120) return false;

  const expected = await computeWsSignature(token, payload, ts);
  return constantTimeEqual(sig, expected);
}

function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let result = 0;
  for (let i = 0; i < a.length; i++) {
    result |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return result === 0;
}

async function computeWsSignature(token: string, payload: unknown, ts: number): Promise<string> {
  const message = JSON.stringify(payload) + '\n' + ts;
  const key = await importHmacKey(token);
  const raw = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(message));
  const bytes = new Uint8Array(raw);
  let sig = '';
  for (let i = 0; i < bytes.length; i++) {
    sig += _hexTable[bytes[i]];
  }
  return sig;
}

/**
 * Validate the shape of a ping response from the phone.
 * Returns the device name if valid, null if the response is suspicious.
 */
export function validatePingResponse(data: unknown): string | null {
  if (!data || typeof data !== 'object') return null;
  const d = data as Record<string, unknown>;

  if (!('device_name' in d) && !('ok' in d) && !('status' in d)) return null;

  const name = d.device_name;
  if (name !== undefined && typeof name !== 'string') return null;

  if ('ok' in d && typeof d.ok !== 'boolean' && typeof d.ok !== 'number') return null;
  if ('status' in d && typeof d.status !== 'string') return null;

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

// ============================================================================
// AES-256-GCM Body Encryption
//
// Mirrors the desktop Rust implementation:
//   Key = HMAC-SHA256("tempo-body-encrypt-v1", token)
//   IV  = 12 random bytes
//   Body = base64url(IV || AES-256-GCM(gzip(plaintext), key, IV))
//
// The Android server already supports decryption with this format.
// ============================================================================

const _aesKeyCache = new Map<string, CryptoKey>();

async function deriveEncryptionKey(token: string): Promise<CryptoKey> {
  const cached = _aesKeyCache.get(token);
  if (cached) return cached;

  const hmacKey = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode('tempo-body-encrypt-v1'),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const rawKey = await crypto.subtle.sign('HMAC', hmacKey, new TextEncoder().encode(token));

  const aesKey = await crypto.subtle.importKey(
    'raw',
    rawKey,
    { name: 'AES-GCM' },
    false,
    ['encrypt', 'decrypt']
  );
  _aesKeyCache.set(token, aesKey);
  return aesKey;
}

async function gzipCompress(data: Uint8Array): Promise<Uint8Array> {
  const stream = new Blob([data])
    .stream()
    .pipeThrough(new CompressionStream('gzip'));
  const compressed = await new Response(stream).arrayBuffer();
  return new Uint8Array(compressed);
}

async function gzipDecompress(data: Uint8Array): Promise<Uint8Array> {
  const stream = new Blob([data])
    .stream()
    .pipeThrough(new DecompressionStream('gzip'));
  const decompressed = await new Response(stream).arrayBuffer();
  return new Uint8Array(decompressed);
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64UrlToBytes(str: string): Uint8Array {
  const padded = str.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

export async function encryptBody(plaintext: string, token: string): Promise<string> {
  const key = await deriveEncryptionKey(token);
  const iv = crypto.getRandomValues(new Uint8Array(12));

  const compressed = await gzipCompress(new TextEncoder().encode(plaintext));
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    key,
    compressed
  );

  const ciphertextBytes = new Uint8Array(ciphertext);
  const combined = new Uint8Array(iv.length + ciphertextBytes.length);
  combined.set(iv, 0);
  combined.set(ciphertextBytes, iv.length);

  return bytesToBase64Url(combined);
}

export async function decryptBody(encryptedB64: string, token: string): Promise<string> {
  const key = await deriveEncryptionKey(token);
  const combined = base64UrlToBytes(encryptedB64);

  if (combined.length < 13) throw new Error('ciphertext too short');

  const iv = combined.slice(0, 12);
  const ciphertext = combined.slice(12);

  const compressed = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv },
    key,
    ciphertext
  );

  const decompressed = await gzipDecompress(new Uint8Array(compressed));
  return new TextDecoder().decode(decompressed);
}

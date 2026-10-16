/** Bounded Google Drive transport. Mutations are never blindly replayed. */
import { invalidateDriveAccessToken } from './drive-auth';

const MAX_DRIVE_RETRIES = 3;
const DRIVE_REQUEST_TIMEOUT_MS = 30_000;

export class PermanentBatchError extends Error {}

export async function readStreamBytesWithLimit(
  stream: ReadableStream<Uint8Array> | null,
  maxBytes: number,
  label: string,
): Promise<Uint8Array> {
  if (!stream) throw new PermanentBatchError(`Google Drive returned an empty ${label} history stream`);

  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (!value) continue;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel().catch(() => undefined);
        throw new PermanentBatchError(`Tempo Drive history batch exceeds the ${label} size limit`);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

export async function assertDriveResponse(response: Response): Promise<void> {
  if (response.ok) return;
  let detail = '';
  let reasons: string[] = [];
  try {
    const json = await response.json() as any;
    detail = json?.error?.message ?? '';
    reasons = Array.isArray(json?.error?.errors)
      ? json.error.errors.map((error: any) => error?.reason).filter((reason: unknown): reason is string => typeof reason === 'string')
      : [];
  } catch { /* response body may not be JSON */ }

  if (response.status === 401 ||
    (response.status === 403 && reasons.some(reason => reason === 'authError' || reason === 'insufficientPermissions'))
  ) {
    throw new Error(`Google Drive authorization failed${detail ? `: ${detail}` : ''}`);
  }
  if (response.status === 429 || response.status >= 500 ||
    (response.status === 403 && reasons.some(reason => /rateLimit/i.test(reason)))
  ) {
    throw new Error(`Google Drive is temporarily unavailable (HTTP ${response.status})`);
  }
  if (response.status === 403) {
    throw new Error(`Google Drive rejected the request${detail ? `: ${detail}` : ''}`);
  }
  throw new Error(`Google Drive request failed (HTTP ${response.status})${detail ? `: ${detail}` : ''}`);
}

export async function driveFetch(
  accessToken: string,
  url: string,
  init: RequestInit = {},
  attempt = 0,
): Promise<Response> {
  const retrySafe = isDriveRequestRetrySafe(init.method);
  let response: Response;
  try {
    response = await fetchWithDeadline(url, init);
  } catch (err) {
    // Only retry safe requests, and never replay a caller-cancelled operation.
    if (!retrySafe || init.signal?.aborted || attempt >= MAX_DRIVE_RETRIES) throw err;
    await delayMs(750 * (2 ** attempt));
    return driveFetch(accessToken, url, init, attempt + 1);
  }

  if (response.status === 401) {
    await invalidateDriveAccessToken(accessToken);
    return response;
  }

  let transient = response.status === 429 || response.status >= 500;
  if (response.status === 403) {
    try {
      const json = await response.clone().json() as any;
      const errors = Array.isArray(json?.error?.errors) ? json.error.errors : [];
      transient = errors.some((error: any) => typeof error?.reason === 'string' && /rateLimit/i.test(error.reason));
    } catch { /* leave as non-transient forbidden */ }
  }
  if (retrySafe && transient && attempt < MAX_DRIVE_RETRIES) {
    const retryAfterSeconds = Number(response.headers.get('Retry-After') ?? '0');
    const retryAfterMs = Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0
      ? Math.min(retryAfterSeconds * 1000, 30_000)
      : 750 * (2 ** attempt);
    await delayMs(retryAfterMs);
    return driveFetch(accessToken, url, init, attempt + 1);
  }
  return response;
}

export async function fetchWithDeadline(url: string, init: RequestInit, timeoutMs = DRIVE_REQUEST_TIMEOUT_MS): Promise<Response> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  const abortParent = () => controller.abort();
  if (init.signal?.aborted) controller.abort();
  else init.signal?.addEventListener('abort', abortParent, { once: true });
  const cleanup = () => {
    clearTimeout(timeout);
    init.signal?.removeEventListener('abort', abortParent);
  };
  try {
    const response = await fetch(url, { ...init, signal: controller.signal });
    if (!response.body) { cleanup(); return response; }
    const reader = response.body.getReader();
    // Fetch resolves at the headers. Keep its deadline until the body is read,
    // so a stalled gzip/JSON response cannot hold the lifecycle queue forever.
    return new Response(new ReadableStream<Uint8Array>({
      async pull(stream) {
        try {
          const next = await reader.read();
          if (next.done) { cleanup(); stream.close(); }
          else stream.enqueue(next.value);
        } catch (error) { cleanup(); stream.error(error); }
      },
      async cancel(reason) { cleanup(); await reader.cancel(reason); },
    }), { status: response.status, statusText: response.statusText, headers: response.headers });
  } catch (error) { cleanup(); throw error; }
}

export function isDriveRequestRetrySafe(method: string | undefined): boolean {
  // A marker PATCH changes Drive modifiedTime, which is our deletion generation.
  // Blindly replaying a timed-out PATCH can invalidate newly restored history.
  // GET and DELETE are safe to repeat; POST/PATCH must be reconciled by a later
  // operation after querying the remote marker or deterministic batch name.
  return ['GET', 'HEAD', 'DELETE'].includes((method ?? 'GET').toUpperCase());
}

export function delayMs(milliseconds: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}


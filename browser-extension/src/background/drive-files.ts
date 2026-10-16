/** Google Drive appDataFolder file operations, generations and integrity. */
import { isFirefoxBuild } from './drive-auth';
import { SCHEMA_VERSION, MAX_BATCH_BYTES, sha256Bytes } from './drive-protocol';
import { PermanentBatchError, assertDriveResponse, driveFetch, delayMs, readStreamBytesWithLimit } from './drive-transport';

const DRIVE_API = 'https://www.googleapis.com/drive/v3';
const DRIVE_UPLOAD_API = 'https://www.googleapis.com/upload/drive/v3';
export const FILE_PREFIX = 'tempo_history_v1_';
const DISABLE_MARKER_NAME = 'tempo_history_control_v1.json';
export const APP_PROPERTY_GENERATION = 'tempo_generation';
export const APP_PROPERTY_SHA256 = 'tempo_sha256';

export interface DriveFileRecord {
  id: string;
  name: string;
  size?: string;
  createdTime?: string;
  modifiedTime?: string;
  md5Checksum?: string;
  appProperties?: Record<string, string>;
}


export async function uploadBatch(
  accessToken: string,
  fileName: string,
  gzip: Uint8Array,
  deviceId: string,
  generation: number,
): Promise<void> {
  const checksum = await sha256Bytes(gzip);
  const existing = await findFilesByExactName(
    accessToken,
    fileName,
    'files(id,name,size,md5Checksum,modifiedTime,appProperties)',
  );
  const platform = isFirefoxBuild() ? 'firefox_extension' : 'chrome_extension';
  const verified = existing.find(file => verifiedUpload(file, fileName, deviceId, platform, generation, checksum, gzip.byteLength));
  if (verified) return;
  // Preserve pre-existing same-name files until a replacement has been
  // validated. Unverified legacy payloads may still hold the only cloud copy;
  // deleting them first would risk permanent data loss on upload failure.
  // Origin event IDs make an eventual second copy harmless on import.

  const boundary = `tempo_${crypto.randomUUID().replace(/-/g, '')}`;
  const metadata = JSON.stringify({
    name: fileName,
    parents: ['appDataFolder'],
    appProperties: {
      tempo_kind: 'history_batch',
      tempo_schema: String(SCHEMA_VERSION),
      source_device_id: deviceId,
      source_platform: isFirefoxBuild() ? 'firefox_extension' : 'chrome_extension',
      [APP_PROPERTY_GENERATION]: String(generation),
      [APP_PROPERTY_SHA256]: checksum,
    },
  });
  const body = new Blob([
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata}\r\n`,
    `--${boundary}\r\nContent-Type: application/gzip\r\n\r\n`,
    gzip as BlobPart,
    `\r\n--${boundary}--\r\n`,
  ]);
  const response = await driveFetch(
    accessToken,
    `${DRIVE_UPLOAD_API}/files?uploadType=multipart&fields=id,name,size,md5Checksum,createdTime,appProperties`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': `multipart/related; boundary=${boundary}`,
      },
      body,
    },
  );
  await assertDriveResponse(response);
  const uploaded = await response.json() as DriveFileRecord;
  if (!verifiedUpload(uploaded, fileName, deviceId, platform, generation, checksum, gzip.byteLength)) {
    throw new Error('Google Drive returned mismatched metadata for the uploaded history batch');
  }
}

export function verifiedUpload(file: DriveFileRecord, name: string, device: string, platform: string,
  generation: number, checksum: string, size: number): boolean {
  const properties = file.appProperties;
  return file.name === name && Number(file.size) === size && properties?.tempo_sha256 === checksum &&
    properties?.tempo_kind === 'history_batch' && properties?.tempo_schema === '1' &&
    properties?.source_device_id === device && properties?.source_platform === platform &&
    batchGeneration(file) === generation;
}

async function findFilesByExactName(
  accessToken: string,
  fileName: string,
  fields = 'files(id,name,size,md5Checksum,modifiedTime,appProperties)',
): Promise<DriveFileRecord[]> {
  const escaped = fileName.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
  const params = new URLSearchParams({
    spaces: 'appDataFolder',
    q: `name = '${escaped}' and trashed = false`,
    pageSize: '1000',
    fields,
  });
  params.set('fields', `nextPageToken,${fields}`);
  const files: DriveFileRecord[] = [];
  let pageToken: string | undefined;
  do {
    if (pageToken) params.set('pageToken', pageToken);
    const response = await driveFetch(accessToken, `${DRIVE_API}/files?${params.toString()}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    await assertDriveResponse(response);
    const data = await response.json() as { files?: DriveFileRecord[]; nextPageToken?: string };
    files.push(...(data.files ?? []));
    pageToken = data.nextPageToken;
  } while (pageToken);
  return files;
}

export async function getDisableMarkerVersion(accessToken: string): Promise<number> {
  const files = await findFilesByExactName(accessToken, DISABLE_MARKER_NAME);
  if (files.length === 0) return 0;
  const versions = files.map(file => file.modifiedTime ? Date.parse(file.modifiedTime) : NaN);
  if (versions.some(version => !Number.isSafeInteger(version) || version <= 0)) {
    throw new Error('Google Drive did not return a valid deletion marker version');
  }
  return Math.max(...versions);
}

export async function bumpDisableMarker(accessToken: string): Promise<number> {
  const previousVersion = await getDisableMarkerVersion(accessToken);
  let markers = await findFilesByExactName(accessToken, DISABLE_MARKER_NAME);
  if (markers.length === 0) {
    const response = await driveFetch(accessToken, `${DRIVE_API}/files?fields=id,name,modifiedTime,appProperties`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        name: DISABLE_MARKER_NAME,
        parents: ['appDataFolder'],
        appProperties: {
          tempo_kind: 'history_sync_control',
          revision: crypto.randomUUID(),
        },
      }),
    });
    await assertDriveResponse(response);
  }

  let markerVersion = 0;
  for (let attempt = 0; attempt < 3; attempt++) {
    markers = await findFilesByExactName(accessToken, DISABLE_MARKER_NAME);
    for (const marker of markers) {
      const response = await driveFetch(
        accessToken,
        `${DRIVE_API}/files/${encodeURIComponent(marker.id)}?fields=id,name,modifiedTime,appProperties`,
        {
          method: 'PATCH',
          headers: {
            Authorization: `Bearer ${accessToken}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            appProperties: {
              tempo_kind: 'history_sync_control',
              revision: crypto.randomUUID(),
            },
          }),
        },
      );
      await assertDriveResponse(response);
      const updated = await response.json() as DriveFileRecord;
      const parsed = updated.modifiedTime ? Date.parse(updated.modifiedTime) : NaN;
      if (!Number.isSafeInteger(parsed) || parsed <= 0) {
        throw new Error('Google Drive did not return a valid deletion marker version');
      }
      markerVersion = Math.max(markerVersion, parsed);
    }
    if (markerVersion > previousVersion) return markerVersion;
    if (attempt < 2) await delayMs(10);
  }
  throw new Error('Google Drive did not return a newer deletion marker version');
}

export async function listBatches(accessToken: string, createdAfter: number | null): Promise<DriveFileRecord[]> {
  // Search by name, not appProperties: invalid metadata must still be visible
  // so recovery can report the file instead of silently overlooking it.
  const clauses = [
    `name contains '${FILE_PREFIX}'`,
    'trashed = false',
  ];
  if (createdAfter != null && createdAfter > 0) {
    clauses.push(`createdTime > '${new Date(createdAfter).toISOString()}'`);
  }

  const files: DriveFileRecord[] = [];
  let pageToken: string | null = null;
  do {
    const params = new URLSearchParams({
      spaces: 'appDataFolder',
      q: clauses.join(' and '),
      pageSize: '1000',
      fields: 'nextPageToken,files(id,name,size,md5Checksum,createdTime,modifiedTime,appProperties)',
    });
    if (pageToken) params.set('pageToken', pageToken);
    const response = await driveFetch(accessToken, `${DRIVE_API}/files?${params.toString()}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    await assertDriveResponse(response);
    const data = await response.json() as { nextPageToken?: string; files?: DriveFileRecord[] };
    files.push(...(data.files ?? []));
    pageToken = data.nextPageToken ?? null;
  } while (pageToken);
  // Server-side createdTime sorting can time out on very large Drive archives.
  // Locally sort all enumerated pages before advancing an import checkpoint.
  files.sort((a, b) => {
    const at = a.createdTime ? Date.parse(a.createdTime) : 0;
    const bt = b.createdTime ? Date.parse(b.createdTime) : 0;
    return (Number.isFinite(at) ? at : 0) - (Number.isFinite(bt) ? bt : 0) ||
      a.id.localeCompare(b.id);
  });
  return files;
}

export function batchGeneration(file: DriveFileRecord): number | null {
  const value = file.appProperties?.[APP_PROPERTY_GENERATION];
  if (value != null && !/^\d+$/.test(value)) return null;
  const raw = Number(value ?? '0');
  return Number.isSafeInteger(raw) && raw >= 0 ? raw : null;
}

/** Conservative cleanup decision shared by owner deletion and stale clients.
 * Never remove current-generation files if their filename is trustworthy but
 * their Drive appProperties have been stripped or corrupted.
 */
export function shouldDeleteHistoryBatch(
  file: DriveFileRecord, markerGeneration: number, ownerDelete: boolean,
): boolean {
  if (!file.name.startsWith(FILE_PREFIX)) return false;
  if (!file.name.endsWith('.json.gz')) return ownerDelete;
  const match = /^g(\d+)_/.exec(file.name.slice(FILE_PREFIX.length));
  const namedGeneration = match ? Number(match[1]) : null;
  if (namedGeneration != null && Number.isSafeInteger(namedGeneration) &&
      namedGeneration >= markerGeneration) return false;
  const declaredGeneration = batchGeneration(file);
  return declaredGeneration == null ? ownerDelete : declaredGeneration < markerGeneration;
}

export async function deleteDriveFileStrict(accessToken: string, fileId: string): Promise<void> {
  const response = await driveFetch(accessToken, `${DRIVE_API}/files/${encodeURIComponent(fileId)}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (response.status !== 404) await assertDriveResponse(response);
}

export async function deleteBatchesBeforeGeneration(
  accessToken: string, generation: number, ownerDelete = false,
): Promise<number> {
  const files = await listBatches(accessToken, null);
  let deleted = 0;
  for (const file of files) {
    if (!shouldDeleteHistoryBatch(file, generation, ownerDelete)) continue;
    await deleteDriveFileStrict(accessToken, file.id);
    deleted++;
  }
  return deleted;
}

export async function driveRequestBytes(accessToken: string, file: DriveFileRecord): Promise<Uint8Array> {
  const response = await driveFetch(
    accessToken,
    `${DRIVE_API}/files/${encodeURIComponent(file.id)}?alt=media`,
    { headers: { Authorization: `Bearer ${accessToken}` } },
  );
  await assertDriveResponse(response);
  const bytes = await readStreamBytesWithLimit(response.body, MAX_BATCH_BYTES, 'compressed');
  const declaredSize = Number(file.size ?? '0');
  const expectedChecksum = file.appProperties?.[APP_PROPERTY_SHA256];
  if (bytes.byteLength !== declaredSize || !expectedChecksum || await sha256Bytes(bytes) !== expectedChecksum) {
    throw new PermanentBatchError('Tempo Drive history batch does not match its integrity metadata');
  }
  return bytes;
}


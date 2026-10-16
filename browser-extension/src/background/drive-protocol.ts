/** Immutable multi-client listening history wire codec and validation. */
import type { Play } from '../shared/types';
import { readStreamBytesWithLimit } from './drive-transport';

export const SCHEMA_VERSION = 1;
export const MAX_BATCH_BYTES = 10 * 1024 * 1024;
export const SHA256_PATTERN = /^[0-9a-f]{64}$/;
export const DEVICE_ID_PATTERN = /^[A-Za-z0-9._-]{1,200}$/;
export const PLATFORM_PATTERN = /^[A-Za-z0-9._-]{1,100}$/;
const MAX_SAFE_WIRE_INTEGER = Number.MAX_SAFE_INTEGER;
const MAX_WIRE_COUNTER = 2_147_483_647;
export interface WireEvent {
  event_id: string;
  title: string;
  artist: string;
  album: string | null;
  timestamp_utc: number;
  duration_ms: number;
  listened_ms: number;
  source_app: string;
  source: string;
  skipped: boolean;
  replay_count: number;
  completion_percentage: number;
  pause_count: number;
  seek_count: number;
  session_id: string | null;
  site: string | null;
  content_type: string;
  /** 0 = muted, positive = audible, null = unknown. */
  volume_level: number | null;
  total_pause_duration_ms: number;
  position_updates_count: number;
}

export interface WireBatch {
  schema_version: number;
  batch_id: string;
  source_device_id: string;
  source_device_name: string;
  source_platform: string;
  created_at_utc: number;
  events: WireEvent[];
}


export async function playToWire(play: Play, deviceId: string): Promise<WireEvent> {
  // Pure wire conversion. The upload caller persists the event's first ID
  // before networking; pure protocol/golden-vector tests need no IndexedDB.
  const id = play.originEventId ?? await eventId(deviceId, play);
  const title = truncateWireText(play.title.trim());
  const artist = truncateWireText(play.artist.trim());
  if (!title || !artist || !isPositiveWireInteger(play.timestampUtc)) {
    throw new Error('A local browser play has an invalid title, artist, or timestamp');
  }
  const sourceApp = truncateWireText((play.sourceApp || 'browser').trim(), 900) || 'browser';
  const event: WireEvent = {
    event_id: id,
    title,
    artist,
    album: play.album ? truncateWireText(play.album) : null,
    timestamp_utc: play.timestampUtc,
    duration_ms: safeWireInteger(play.durationMs),
    listened_ms: safeWireInteger(play.listenedMs),
    source_app: sourceApp,
    source: `browser:${sourceApp}`,
    skipped: !!play.skipped,
    replay_count: Math.min(MAX_WIRE_COUNTER, safeWireInteger(play.replayCount)),
    completion_percentage: Math.round(clamp(Number.isFinite(play.completionPercentage) ? play.completionPercentage : 0, 0, 100)),
    pause_count: Math.min(MAX_WIRE_COUNTER, safeWireInteger(play.pauseCount)),
    seek_count: Math.min(MAX_WIRE_COUNTER, safeWireInteger(play.seekCount)),
    session_id: play.sessionId ? truncateWireText(play.sessionId) : null,
    site: play.site ? truncateWireText(play.site) : null,
    content_type: truncateWireText((play.contentType || 'MUSIC').trim()) || 'MUSIC',
    volume_level: protocolVolumeLevel(play),
    total_pause_duration_ms: safeWireInteger(play.totalPauseDurationMs),
    position_updates_count: Math.min(MAX_WIRE_COUNTER, safeWireInteger(play.positionUpdatesCount)),
  };
  if (!isValidEvent(event)) throw new Error('A local browser play cannot be represented safely');
  return event;
}

function protocolVolumeLevel(play: Play): number | null {
  if (play.isMuted) return 0;
  const value = play.volumeLevel;
  if (!Number.isFinite(value) || value < 0) return null;
  if (value <= 1) return value <= 0 ? 0 : Math.max(1, Math.round(value * 100));
  return Math.min(100, Math.max(1, Math.round(value)));
}

export function localVolumeFromWire(volume: number | null): number {
  return volume == null ? -1 : volume / 100;
}


export async function eventId(deviceId: string, play: Pick<Play, 'id' | 'timestampUtc' | 'title' | 'artist'>): Promise<string> {
  const canonical = [
    'tempo-history-v1',
    deviceId,
    String(play.id ?? 0),
    String(play.timestampUtc),
    play.title.trim().toLowerCase(),
    play.artist.trim().toLowerCase(),
  ].join('|');
  return sha256(canonical);
}

export async function deterministicBatchId(events: WireEvent[]): Promise<string> {
  if (events.length === 0) throw new Error('A history batch cannot be empty');
  return sha256(`tempo-batch-v1|${events.map(event => event.event_id).join('|')}`);
}

async function sha256(value: string): Promise<string> {
  return sha256Bytes(new TextEncoder().encode(value));
}

export async function sha256Bytes(value: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', value as BufferSource);
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('');
}

export async function gzipJson(value: unknown): Promise<Uint8Array> {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  if (bytes.byteLength > MAX_BATCH_BYTES) {
    throw new Error('Tempo Drive history batch exceeds the decompressed size limit');
  }
  const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new CompressionStream('gzip'));
  const compressed = new Uint8Array(await new Response(stream).arrayBuffer());
  if (compressed.byteLength > MAX_BATCH_BYTES) {
    throw new Error('Tempo Drive history batch exceeds the compressed size limit');
  }
  return compressed;
}

export async function ungzipJson(bytes: Uint8Array): Promise<unknown> {
  const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip'));
  const decoded = await readStreamBytesWithLimit(stream, MAX_BATCH_BYTES, 'decompressed');
  const text = new TextDecoder('utf-8', { fatal: true }).decode(decoded);
  return JSON.parse(text) as unknown;
}

export function isValidBatch(value: unknown): value is WireBatch {
  if (!value || typeof value !== 'object') return false;
  const batch = value as Partial<WireBatch>;
  return batch.schema_version === SCHEMA_VERSION &&
    typeof batch.batch_id === 'string' && SHA256_PATTERN.test(batch.batch_id) &&
    typeof batch.source_device_id === 'string' && DEVICE_ID_PATTERN.test(batch.source_device_id) &&
    typeof batch.source_device_name === 'string' && isBoundedText(batch.source_device_name) &&
    typeof batch.source_platform === 'string' && PLATFORM_PATTERN.test(batch.source_platform) &&
    isPositiveWireInteger(batch.created_at_utc) &&
    Array.isArray(batch.events) && batch.events.length > 0 && batch.events.length <= 1000 &&
    batch.events.every(isValidEvent);
}

export function isValidEvent(value: unknown): value is WireEvent {
  if (!value || typeof value !== 'object') return false;
  const event = value as Partial<WireEvent>;
  return typeof event.event_id === 'string' && SHA256_PATTERN.test(event.event_id) &&
    typeof event.title === 'string' && isBoundedText(event.title) &&
    typeof event.artist === 'string' && isBoundedText(event.artist) &&
    isOptionalBoundedText(event.album) &&
    isPositiveWireInteger(event.timestamp_utc) &&
    isNonNegativeWireInteger(event.duration_ms) &&
    isNonNegativeWireInteger(event.listened_ms) &&
    typeof event.source_app === 'string' && isBoundedText(event.source_app) &&
    typeof event.source === 'string' && isBoundedText(event.source) &&
    typeof event.skipped === 'boolean' &&
    isIntegerInRange(event.replay_count, 0, MAX_WIRE_COUNTER) &&
    isIntegerInRange(event.completion_percentage, 0, 100) &&
    isIntegerInRange(event.pause_count, 0, MAX_WIRE_COUNTER) &&
    isIntegerInRange(event.seek_count, 0, MAX_WIRE_COUNTER) &&
    isOptionalBoundedText(event.session_id) &&
    isOptionalBoundedText(event.site) &&
    typeof event.content_type === 'string' && isBoundedText(event.content_type) &&
    (event.volume_level === null || isIntegerInRange(event.volume_level, 0, 100)) &&
    isNonNegativeWireInteger(event.total_pause_duration_ms) &&
    isIntegerInRange(event.position_updates_count, 0, MAX_WIRE_COUNTER);
}


function isBoundedText(value: string): boolean {
  return value.trim().length > 0 && value.length <= 1000 && isWellFormedText(value);
}

function isOptionalBoundedText(value: unknown): boolean {
  return value === null || (typeof value === 'string' && value.length <= 1000 && isWellFormedText(value));
}

function isWellFormedText(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const unit = value.charCodeAt(index);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = value.charCodeAt(++index);
      if (!(next >= 0xdc00 && next <= 0xdfff)) return false;
    } else if (unit >= 0xdc00 && unit <= 0xdfff) return false;
  }
  return true;
}

/** The wire limit counts UTF-16 units; a cut must leave a complete code point. */
function truncateWireText(value: string, limit = 1000): string {
  let end = Math.min(value.length, limit);
  if (end > 0 && end < value.length &&
    value.charCodeAt(end - 1) >= 0xd800 && value.charCodeAt(end - 1) <= 0xdbff &&
    value.charCodeAt(end) >= 0xdc00 && value.charCodeAt(end) <= 0xdfff
  ) end--;
  return value.slice(0, end);
}

function isPositiveWireInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 && value <= MAX_SAFE_WIRE_INTEGER;
}

function isNonNegativeWireInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= MAX_SAFE_WIRE_INTEGER;
}

function isIntegerInRange(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max;
}

function safeWireInteger(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return 0;
  return Math.min(MAX_SAFE_WIRE_INTEGER, Math.max(0, Math.round(value)));
}


export function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}


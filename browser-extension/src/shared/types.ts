// ============================================================================
// Tempo Stats — Shared TypeScript interfaces
// Mirrors the desktop Rust models (db/models.rs) and mobile Kotlin entities.
// ============================================================================

/** Raw media state extracted by the content script from a music tab. */
export interface RawMediaState {
  /** Tab URL (always available — major advantage over desktop app). */
  url: string;
  /** Track title from MediaSession metadata or page title. */
  title: string;
  /** Artist from MediaSession metadata or meta tags. */
  artist: string;
  /** Album from MediaSession metadata. */
  album: string;
  /** Track duration in seconds (from <audio>/<video>.duration). NaN/Infinity = unknown. */
  duration: number;
  /** Current playback position in seconds (from <audio>/<video>.currentTime). */
  position: number;
  /** Whether audio is currently playing (not paused/ended). */
  isPlaying: boolean;
  /** Volume level 0.0–1.0 (from <audio>/<video>.volume). */
  volume: number;
  /** Whether audio is muted (from <audio>/<video>.muted or volume==0). */
  isMuted: boolean;
  /** Playback rate (1.0 = normal). */
  playbackRate: number;
  /** Tab ID where this media is playing. */
  tabId: number;
  /** Timestamp when this sample was taken (Date.now()). */
  timestamp: number;
}

/** Classified now-playing info after normalization. */
export interface NowPlaying {
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  sourceApp: string;
  isPlaying: boolean;
  listenedMs: number;
  site: string | null;
  skipped: boolean;
  replayCount: number;
  isMuted: boolean;
  completionPercentage: number;
  pauseCount: number;
  seekCount: number;
  contentType: string;
  sessionId: string;
  volumeLevel: number;
  // Anomaly detection fields
  anomalies: string[];
  totalPauseDurationMs: number;
  positionUpdatesCount: number;
}

/** A queued play (stored in IndexedDB, synced to phone). */
export interface Play {
  id?: number;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  timestampUtc: number;
  sourceApp: string;
  status: 'queued' | 'synced' | 'failed';
  listenedMs: number;
  skipped: boolean;
  replayCount: number;
  isMuted: boolean;
  completionPercentage: number;
  pauseCount: number;
  seekCount: number;
  sessionId: string;
  site: string;
  contentType: string;
  volumeLevel: number;
  // Anomaly detection data (stored locally, synced to phone)
  anomalies: string[];
  totalPauseDurationMs: number;
  positionUpdatesCount: number;
}

/** Payload sent to the phone's /api/plays endpoint. */
export interface SyncPayload {
  auth_token: string;
  device_name: string;
  plays: SyncPlay[];
}

/** Response from the phone's /api/plays endpoint. */
export interface SyncResponse {
  ok: boolean;
  accepted?: number;
  duplicates?: number;
  next_token?: string;
  error?: string;
}

/** Individual play within a sync payload (snake_case to match Rust/phone API). */
export interface SyncPlay {
  title: string;
  artist: string;
  album: string;
  timestamp_utc: number;
  duration_ms: number;
  source_app: string;
  listened_ms: number;
  skipped: boolean;
  replay_count: number;
  is_muted: boolean;
  completion_percentage: number;
  pause_count: number;
  seek_count: number;
  session_id: string;
  site: string;
  content_type: string;
  volume_level: number;
  // Anomaly detection fields (synced to phone)
  anomalies: string[];
  total_pause_duration_ms: number;
  position_updates_count: number;
}

/** Extension settings. */
export interface Settings {
  /** Sync interval in minutes (default 30). */
  syncIntervalMinutes: number;
  /** How often content script polls media state (seconds, default 2). */
  pollingIntervalSeconds: number;
  /** Whether tracking is enabled. */
  trackingEnabled: boolean;
  /** Offline mode — disables auto-sync, queues plays locally. */
  offlineMode: boolean;
  /** User-defined known artist names for better matching. */
  knownArtists: string[];
  /** User-defined YouTube channel names to opt-in YouTube.com tracking. */
  youtubeChannels: string[];
}

export const DEFAULT_SETTINGS: Settings = {
  syncIntervalMinutes: 30,
  pollingIntervalSeconds: 2,
  trackingEnabled: true,
  offlineMode: false,
  knownArtists: [],
  youtubeChannels: [],
};

/** Pairing info for connecting to the Tempo Android app. */
export interface PairingInfo {
  phoneIp: string;
  phonePort: number;
  authToken: string;
  deviceName: string;
  pairedAt: string | null;
}

/** Sync history record. */
export interface SyncRecord {
  id?: number;
  syncedCount: number;
  status: 'success' | 'failed';
  errorMessage: string | null;
  syncedAt: string;
}

/** Events emitted by the PlaybackTracker. */
export enum TrackEventType {
  /** Track accumulated enough listen time — ready to log as a play. */
  ReadyToLog = 'ReadyToLog',
  /** Previous track ended/changed with its final stats. */
  TrackEnded = 'TrackEnded',
  /** Same track still playing, not ready to log yet. */
  StillPlaying = 'StillPlaying',
  /** No previous track / already logged — nothing to do. */
  NoAction = 'NoAction',
}

export interface TrackEventReadyToLog {
  type: TrackEventType.ReadyToLog;
  nowPlaying: NowPlaying;
}

export interface TrackEventEnded {
  type: TrackEventType.TrackEnded;
  listenedMs: number;
  skipped: boolean;
  replayCount: number;
  completionPercentage: number;
  pauseCount: number;
  seekCount: number;
  sessionId: string;
}

export interface TrackEventStillPlaying {
  type: TrackEventType.StillPlaying;
}

export interface TrackEventNoAction {
  type: TrackEventType.NoAction;
}

export type TrackEvent =
  | TrackEventReadyToLog
  | TrackEventEnded
  | TrackEventStillPlaying
  | TrackEventNoAction;

/** Message types for communication between content script ↔ background. */
export enum MessageType {
  /** Content script → Background: media state update. */
  MediaStateUpdate = 'MEDIA_STATE_UPDATE',
  /** Content script → Background: tab unloading / media stopped. */
  MediaStopped = 'MEDIA_STOPPED',
  /** Popup → Background: request current now-playing. */
  GetNowPlaying = 'GET_NOW_PLAYING',
  /** Popup → Background: request queue count. */
  GetQueueCount = 'GET_QUEUE_COUNT',
  /** Popup → Background: request queue items. */
  GetQueueItems = 'GET_QUEUE_ITEMS',
  /** Popup → Background: trigger manual sync. */
  SyncNow = 'SYNC_NOW',
  /** Popup → Background: get sync status. */
  GetSyncStatus = 'GET_SYNC_STATUS',
  /** Popup → Background: get/set pairing info. */
  GetPairing = 'GET_PAIRING',
  SetPairing = 'SET_PAIRING',
  RemovePairing = 'REMOVE_PAIRING',
  /** Popup → Background: get/set settings. */
  GetSettings = 'GET_SETTINGS',
  SetSettings = 'SET_SETTINGS',
  /** Popup → Background: get stats. */
  GetStats = 'GET_STATS',
  /** Popup → Background: clear queue. */
  ClearQueue = 'CLEAR_QUEUE',
  /** Popup → Background: delete a single play by id. */
  DeletePlay = 'DELETE_PLAY',
  /** Popup → Background: retry all failed plays (reset to queued). */
  RetryFailedPlays = 'RETRY_FAILED_PLAYS',
  /** Popup → Background: get now-playing for a specific tab. */
  GetNowPlayingForTab = 'GET_NOW_PLAYING_FOR_TAB',
  /** Popup → Background: export plays data. */
  ExportPlays = 'EXPORT_PLAYS',
  /** Content script → Background: request polling interval setting. */
  GetPollingInterval = 'GET_POLLING_INTERVAL',
  /** Popup → Background: request host permission for a pairing origin. */
  RequestHostPermission = 'REQUEST_HOST_PERMISSION',
  /** Popup → Background: test phone connectivity (ping). */
  PingPhone = 'PING_PHONE',
}

/** Per-tab tracking state persisted to session storage to survive hibernation. */
export interface TabTrackState {
  tabId: number;
  trackKey: string;
  accumulatedListenMs: number;
  lastPositionMs: number;
  lastPollTime: number;
  logged: boolean;
  replayCount: number;
  isMuted: boolean;
  trackDurationMs: number;
  site: string | null;
  pauseCount: number;
  seekCount: number;
  wasPlaying: boolean;
  sessionId: string;
  lastVolume: number;
  hasPositionData: boolean;
  consecutiveStuckPolls: number;
  sourceApp: string;
  title: string;
  artist: string;
  album: string;
  // Anomaly detection tracking
  totalPauseDurationMs: number;
  positionUpdatesCount: number;
  lastStateChangeTime: number;
  /** Whether the track has accumulated enough listen time to be eligible for logging. */
  eligible: boolean;
}
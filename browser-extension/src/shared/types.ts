/**
 * Shared data structures and message types for Tempo Stats extension.
 */

/** Raw media state extracted by the content script from a music tab. */
export interface RawMediaState {
  url: string;
  title: string;
  artist: string;
  album: string;
  duration: number;
  position: number;
  isPlaying: boolean;
  volume: number;
  isMuted: boolean;
  playbackRate: number;
  tabId: number;
  timestamp: number;
  ytDescriptionMetadata?: { title?: string; artist?: string; album?: string; label?: string };
  ytMusicTagMetadata?: { title?: string; artist?: string; album?: string; label?: string };
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
  anomalies: string[];
  totalPauseDurationMs: number;
  positionUpdatesCount: number;
}

export interface YoutubeChannelSuggestion {
  channel: string;
  title: string;
  url: string;
  tabId: number;
  timestamp: number;
}

/** A queued or historical play stored in IndexedDB. */
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
  anomalies: string[];
  totalPauseDurationMs: number;
  positionUpdatesCount: number;

  /** Cross-device Drive metadata. Imported events are never uploaded again. */
  driveImported?: boolean;
  /** Stable event id supplied by the originating device. */
  originEventId?: string;
  /** Stable random Tempo device id that originally created the play. */
  originDeviceId?: string;
  /** Epoch ms when this locally-owned play was safely uploaded to appDataFolder. */
  driveUploadedAt?: number;
}

export interface SyncPayload {
  auth_token: string;
  device_name: string;
  plays: SyncPlay[];
}

export interface SyncResponse {
  ok: boolean;
  accepted?: number;
  duplicates?: number;
  next_token?: string;
  error?: string;
}

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
  anomalies: string[];
  total_pause_duration_ms: number;
  position_updates_count: number;
}

/** Optional Google Drive cross-device history sync state. */
export interface DriveSyncStatus {
  enabled: boolean;
  configured: boolean;
  connected: boolean;
  accountEmail: string | null;
  lastSyncTime: number | null;
  lastError: string | null;
  lastUploaded: number;
  lastImported: number;
  needsInteractiveAuth: boolean;
}

export interface Settings {
  syncIntervalMinutes: number;
  pollingIntervalSeconds: number;
  trackingEnabled: boolean;
  offlineMode: boolean;
  knownArtists: string[];
  youtubeChannels: string[];
  blockedYoutubeChannels: string[];
  /**
   * Explicit Drive opt-in. Optional in the type so legacy settings objects and
   * older popup fallbacks remain valid during extension upgrades; storage always
   * overlays DEFAULT_SETTINGS and therefore exposes false when it is absent.
   */
  driveSyncEnabled?: boolean;
}

export const DEFAULT_SETTINGS: Settings = {
  syncIntervalMinutes: 30,
  pollingIntervalSeconds: 2,
  trackingEnabled: true,
  offlineMode: false,
  knownArtists: [],
  youtubeChannels: [],
  blockedYoutubeChannels: [],
  driveSyncEnabled: false,
};

export interface PairingInfo {
  phoneIp: string;
  phonePort: number;
  authToken: string;
  deviceName: string;
  pairedAt: string | null;
}

export interface SyncRecord {
  id?: number;
  syncedCount: number;
  status: 'success' | 'failed';
  errorMessage: string | null;
  syncedAt: string;
}

export enum TrackEventType {
  ReadyToLog = 'ReadyToLog',
  TrackEnded = 'TrackEnded',
  StillPlaying = 'StillPlaying',
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

export interface TrackEventStillPlaying { type: TrackEventType.StillPlaying; }
export interface TrackEventNoAction { type: TrackEventType.NoAction; }

export type TrackEvent =
  | TrackEventReadyToLog
  | TrackEventEnded
  | TrackEventStillPlaying
  | TrackEventNoAction;

export enum MessageType {
  MediaStateUpdate = 'MEDIA_STATE_UPDATE',
  MediaStopped = 'MEDIA_STOPPED',
  GetNowPlaying = 'GET_NOW_PLAYING',
  GetYoutubeChannelSuggestion = 'GET_YOUTUBE_CHANNEL_SUGGESTION',
  GetQueueCount = 'GET_QUEUE_COUNT',
  GetQueueItems = 'GET_QUEUE_ITEMS',
  SyncNow = 'SYNC_NOW',
  GetSyncStatus = 'GET_SYNC_STATUS',
  GetPairing = 'GET_PAIRING',
  SetPairing = 'SET_PAIRING',
  RemovePairing = 'REMOVE_PAIRING',
  GetSettings = 'GET_SETTINGS',
  SetSettings = 'SET_SETTINGS',
  AddYoutubeChannel = 'ADD_YOUTUBE_CHANNEL',
  BlockYoutubeChannel = 'BLOCK_YOUTUBE_CHANNEL',
  GetStats = 'GET_STATS',
  ClearQueue = 'CLEAR_QUEUE',
  DeletePlay = 'DELETE_PLAY',
  RetryFailedPlays = 'RETRY_FAILED_PLAYS',
  GetNowPlayingForTab = 'GET_NOW_PLAYING_FOR_TAB',
  GetPopupState = 'GET_POPUP_STATE',
  ExportPlays = 'EXPORT_PLAYS',
  GetPollingInterval = 'GET_POLLING_INTERVAL',
  RequestHostPermission = 'REQUEST_HOST_PERMISSION',
  PingPhone = 'PING_PHONE',
  GetConnectionHealth = 'GET_CONNECTION_HEALTH',
  GetConnectionHistory = 'GET_CONNECTION_HISTORY',
  PairingInvalidated = 'PAIRING_INVALIDATED',
  SocketStateChanged = 'SOCKET_STATE_CHANGED',
  GetSocketState = 'GET_SOCKET_STATE',

  /** Google Drive cross-device history sync. */
  GetDriveSyncStatus = 'GET_DRIVE_SYNC_STATUS',
  ConnectDrive = 'CONNECT_DRIVE',
  DisconnectDrive = 'DISCONNECT_DRIVE',
  DriveSyncNow = 'DRIVE_SYNC_NOW',
  DeleteDriveHistory = 'DELETE_DRIVE_HISTORY',
}

export interface ConnectionHistoryEntry {
  ip: string;
  port: number;
  lastSeen: number;
  successCount: number;
  failCount: number;
  networkFingerprint: string;
}

export interface ConnectionHealth {
  lastPing: number;
  healthy: boolean;
  consecutiveFailures: number;
  consecutiveAuthFailures: number;
  lastSuccessAt: number | null;
}

export interface SyncCheckpoint {
  batchIds: number[];
  batchIndex: number;
  totalBatches: number;
  lastAttempt: number;
  retryCount: number;
}

export interface SyncBackoffState {
  lastErrorKind: string | null;
  lastErrorStatus: number | null;
  consecutiveFailures: number;
  rateLimitedUntil: number | null;
  nextRetryAt: number | null;
  lastSyncTime: string | null;
  lastSyncResult: string | null;
}

export type PhoneSocketMessage =
  | { type: 'ping'; ts: number }
  | { type: 'pong'; ts: number }
  | { type: 'sync_now' }
  | { type: 'ip_changed'; newIp: string }
  | { type: 'now_playing'; data: NowPlaying }
  | { type: 'token_refresh'; next_token: string }
  | { type: 'pairing_invalidated' };

export interface SignedWsMessage {
  payload: PhoneSocketMessage;
  sig: string;
  ts: number;
}

export interface WsAuthMessage {
  type: 'auth';
  token: string;
  device_id: string;
  sig: string;
  ts: number;
}

export enum SocketState {
  Disconnected = 'disconnected',
  Connecting = 'connecting',
  Connected = 'connected',
  Reconnecting = 'reconnecting',
  IdleSuspended = 'idle',
}

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
  totalPauseDurationMs: number;
  positionUpdatesCount: number;
  lastStateChangeTime: number;
  eligible: boolean;
}

export interface NowPlaying {
  title: string;
  artist: string;
  album: string;
  duration_ms: number;
  source_app: string;
  is_playing: boolean;
  listened_ms: number;
  site: string | null;
  skipped: boolean;
  replay_count: number;
  is_muted: boolean;
}

export interface Play {
  id: number | null;
  title: string;
  artist: string;
  album: string;
  duration_ms: number;
  timestamp_utc: number;
  source_app: string;
  status: "queued" | "synced" | "failed";
  listened_ms: number;
  skipped: boolean;
}

export interface PairingStatus {
  is_paired: boolean;
  phone_ip: string | null;
  phone_port: number | null;
  device_name: string | null;
  paired_at: string | null;
}

export interface QrCodeData {
  qr_base64: string;
  token: string;
  device_name: string;
}

export interface Settings {
  sync_interval_minutes: number;
  auto_detect_enabled: boolean;
  polling_interval_seconds: number;
  minimize_to_tray: boolean;
  start_on_boot: boolean;
  theme: string;
  low_battery_threshold: number;
}

export interface BatteryStatus {
  level: number;
  charging: boolean;
  has_battery: boolean;
}

export interface SyncStatus {
  in_progress: boolean;
  last_sync: SyncRecord | null;
  queue_size: number;
}

export interface SyncRecord {
  id: number;
  synced_count: number;
  status: string;
  error_message: string | null;
  synced_at: string;
}

export interface PlayCount {
  queued: number;
  total: number;
}

export interface AppStats {
  total_plays: number;
  queued: number;
  synced: number;
  total_syncs: number;
  top_artist: string | null;
  top_track: string | null;
}

export interface ConnectionStatus {
  is_reachable: boolean;
  resolved_ip: string | null;
  method: string;
}

export interface ExtendedStats {
  app: AppStats;
  sync_success_count: number;
  sync_fail_count: number;
  total_tracks_synced: number;
  recent_syncs: SyncRecord[];
  source_breakdown: SourceBreakdown[];
}

export interface SourceBreakdown {
  source_app: string;
  count: number;
}

/** Structured error from the backend */
export interface AppError {
  kind: string;
  detail?: string;
}

export type Page = "dashboard" | "pairing" | "queue" | "history" | "settings";

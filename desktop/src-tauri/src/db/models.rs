use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Play {
    pub id: Option<i64>,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: i64,
    pub timestamp_utc: i64,
    pub source_app: String,
    pub status: PlayStatus,
    /// Actual accumulated listen time in ms (excludes muted/paused/seeked portions).
    #[serde(default)]
    pub listened_ms: i64,
    /// Whether the user skipped this track (listened < 30% of duration).
    #[serde(default)]
    pub skipped: bool,
    /// Number of times the track was replayed in this session.
    #[serde(default)]
    pub replay_count: u32,
    /// Whether audio was muted when the scrobble was recorded.
    #[serde(default)]
    pub is_muted: bool,
    /// Completion percentage (0.0–100.0).
    #[serde(default)]
    pub completion_percentage: f64,
    /// Number of pause events detected during playback.
    #[serde(default)]
    pub pause_count: u32,
    /// Number of seek/skip events detected during playback.
    #[serde(default)]
    pub seek_count: u32,
    /// Unique session identifier (UUID) for this listening session.
    #[serde(default)]
    pub session_id: String,
    /// Browser source domain (e.g., "music.youtube.com") or empty for native apps.
    #[serde(default)]
    pub site: String,
    /// Content type: MUSIC, PODCAST, or AUDIOBOOK.
    #[serde(default = "default_content_type")]
    pub content_type: String,
    /// Volume level (0.0–1.0) when scrobbled. -1.0 = unknown.
    #[serde(default = "default_volume")]
    pub volume_level: f64,
}

fn default_content_type() -> String {
    "MUSIC".to_string()
}

fn default_volume() -> f64 {
    -1.0
}

/// Content type classification matching Android's enum.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum ContentType {
    Music,
    Podcast,
    Audiobook,
}

impl ContentType {
    pub fn as_str(&self) -> &str {
        match self {
            Self::Music => "MUSIC",
            Self::Podcast => "PODCAST",
            Self::Audiobook => "AUDIOBOOK",
        }
    }
}

/// Generate a new session ID (UUID v4).
pub fn new_session_id() -> String {
    Uuid::new_v4().to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum PlayStatus {
    Queued,
    Synced,
    Failed,
}

impl PlayStatus {
    pub fn as_str(&self) -> &str {
        match self {
            Self::Queued => "queued",
            Self::Synced => "synced",
            Self::Failed => "failed",
        }
    }

    pub fn from_str(s: &str) -> Self {
        match s {
            "synced" => Self::Synced,
            "failed" => Self::Failed,
            _ => Self::Queued,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairingInfo {
    pub phone_ip: String,
    pub phone_port: u16,
    pub auth_token: String,
    pub device_name: String,
    pub paired_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Settings {
    pub sync_interval_minutes: i32,
    pub auto_detect_enabled: bool,
    pub polling_interval_seconds: i32,
    pub minimize_to_tray: bool,
    pub start_on_boot: bool,
    pub theme: String,
    /// Battery level (%) below which tracking pauses. 0 = disabled.
    #[serde(default = "default_battery_threshold")]
    pub low_battery_threshold: i32,
}

fn default_battery_threshold() -> i32 {
    15
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            sync_interval_minutes: 30,
            auto_detect_enabled: true,
            polling_interval_seconds: 5,
            minimize_to_tray: true,
            start_on_boot: false,
            theme: "dark".to_string(),
            low_battery_threshold: 15,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncRecord {
    pub id: i64,
    pub synced_count: i64,
    pub status: String,
    pub error_message: Option<String>,
    pub synced_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NowPlaying {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: i64,
    pub source_app: String,
    pub is_playing: bool,
    /// Actual accumulated listen time in ms (excludes muted/paused/skipped time).
    #[serde(default)]
    pub listened_ms: i64,
    /// Detected site domain for browser sources (e.g., "music.youtube.com", "open.spotify.com").
    #[serde(default)]
    pub site: Option<String>,
    /// Whether the track was skipped (listened < 30% of duration).
    #[serde(default)]
    pub skipped: bool,
    /// Number of times the track was replayed in the current session.
    #[serde(default)]
    pub replay_count: u32,
    /// Whether audio was muted when last polled.
    #[serde(default)]
    pub is_muted: bool,
    /// Completion percentage (0.0–100.0).
    #[serde(default)]
    pub completion_percentage: f64,
    /// Number of pause events detected.
    #[serde(default)]
    pub pause_count: u32,
    /// Number of seek events detected.
    #[serde(default)]
    pub seek_count: u32,
    /// Content type: MUSIC, PODCAST, or AUDIOBOOK.
    #[serde(default = "default_content_type")]
    pub content_type: String,
    /// Unique session identifier.
    #[serde(default)]
    pub session_id: String,
    /// Volume level (0.0–1.0). -1.0 = unknown.
    #[serde(default = "default_volume")]
    pub volume_level: f64,
}

/// Raw media information gathered from OS-level APIs.
/// Richer than NowPlaying — carries position, volume, and URL data
/// that the PlaybackTracker uses to derive skip/replay/mute state.
#[derive(Debug, Clone)]
pub struct RawMediaInfo {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: i64,
    pub position_ms: i64,
    pub source_app: String,
    pub is_playing: bool,
    /// 0.0–1.0 range. -1.0 means unknown.
    pub volume: f64,
    /// URL reported by the media session (Linux MPRIS, or inferred).
    pub url: Option<String>,
    /// Playback rate (1.0 = normal, 0 = paused). -1.0 means unknown.
    pub playback_rate: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppStats {
    pub total_plays: i64,
    pub queued: i64,
    pub synced: i64,
    pub total_syncs: i64,
    pub top_artist: Option<String>,
    pub top_track: Option<String>,
}

/// Payload sent to the Android phone
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncPayload {
    pub auth_token: String,
    pub device_name: String,
    pub plays: Vec<SyncPlay>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncPlay {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub timestamp_utc: i64,
    pub duration_ms: i64,
    pub source_app: String,
    #[serde(default)]
    pub listened_ms: i64,
    #[serde(default)]
    pub skipped: bool,
    #[serde(default)]
    pub replay_count: u32,
    #[serde(default)]
    pub is_muted: bool,
    #[serde(default)]
    pub completion_percentage: f64,
    #[serde(default)]
    pub pause_count: u32,
    #[serde(default)]
    pub seek_count: u32,
    #[serde(default)]
    pub session_id: String,
    #[serde(default)]
    pub site: String,
    #[serde(default = "default_content_type")]
    pub content_type: String,
    #[serde(default = "default_volume")]
    pub volume_level: f64,
}

/// QR code payload for pairing
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QrPayload {
    pub token: String,
    pub device_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ip: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
    pub v: u8,
}

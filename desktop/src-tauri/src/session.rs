use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// Persisted playback session state for crash recovery.
/// Saved periodically to a JSON file so that an in-progress listening session
/// can be resumed if the app crashes or restarts.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PersistedSession {
    pub track_key: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: i64,
    pub accumulated_listen_ms: i64,
    pub source_app: String,
    pub session_id: String,
    pub replay_count: u32,
    pub pause_count: u32,
    pub seek_count: u32,
    pub site: Option<String>,
    pub content_type: String,
    pub volume_level: f64,
    pub saved_at: i64,
}

/// Get the path to the persisted session file.
fn session_file_path(app_data_dir: &std::path::Path) -> PathBuf {
    app_data_dir.join("playback_session.json")
}

/// Save the current playback session to disk for crash recovery.
pub fn save_session(app_data_dir: &std::path::Path, session: &PersistedSession) {
    let path = session_file_path(app_data_dir);
    match serde_json::to_string(session) {
        Ok(json) => {
            if let Err(e) = std::fs::write(&path, json) {
                log::debug!("Failed to persist session: {}", e);
            }
        }
        Err(e) => log::debug!("Failed to serialize session: {}", e),
    }
}

/// Load a previously saved playback session from disk.
/// Returns None if no session exists or it's too old (>2 hours).
pub fn load_session(app_data_dir: &std::path::Path) -> Option<PersistedSession> {
    let path = session_file_path(app_data_dir);
    let data = std::fs::read_to_string(&path).ok()?;
    let session: PersistedSession = serde_json::from_str(&data).ok()?;

    // Discard sessions older than 2 hours — stale data isn't useful
    let now = chrono::Utc::now().timestamp_millis();
    let age_ms = now - session.saved_at;
    if age_ms > 2 * 3600 * 1000 {
        clear_session(app_data_dir);
        return None;
    }

    Some(session)
}

/// Remove the persisted session file (called after successful scrobble).
pub fn clear_session(app_data_dir: &std::path::Path) {
    let path = session_file_path(app_data_dir);
    let _ = std::fs::remove_file(path);
}

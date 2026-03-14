#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "macos")]
mod media_remote;
#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "windows")]
mod windows_media;

pub mod normalize;
pub mod site_detect;
pub mod tracker;
#[allow(dead_code)]
pub mod artist_parser;

use crate::db::models::{NowPlaying, RawMediaInfo};
use crate::AppState;
use log::{debug, error, info};
use tauri::{Emitter, Manager};
use tracker::{PlaybackTracker, TrackEvent};

pub struct MediaDetector {
    tracker: PlaybackTracker,
}

impl MediaDetector {
    pub fn new() -> Self {
        Self {
            tracker: PlaybackTracker::new(),
        }
    }

    /// Full detection pipeline: OS query → site filter → normalize → position-track.
    ///
    /// Returns a `DetectResult` describing what happened this poll cycle.
    pub async fn poll(&mut self) -> DetectResult {
        let raw = match self.detect_raw().await {
            Some(r) => r,
            None => {
                // Nothing playing — finalize any in-progress track
                let event = self.tracker.on_playback_stopped();

                return match event {
                    TrackEvent::TrackEnded { listened_ms, skipped, replay_count, completion_percentage, pause_count, seek_count, session_id } => {
                        DetectResult::TrackFinished { listened_ms, skipped, replay_count, completion_percentage, pause_count, seek_count, session_id }
                    }
                    TrackEvent::ReadyToLog(np) => {
                        match normalize::normalize(np) {
                            Some(normalized) => DetectResult::PlayReady(normalized),
                            None => DetectResult::Filtered,
                        }
                    }
                    _ => DetectResult::NothingPlaying,
                };
            }
        };

        // Site-level filtering: drop blocked sites and non-music content
        if !site_detect::should_track(&raw) {
            debug!("Site filter rejected: '{}' from '{}'", raw.title, raw.source_app);
            return DetectResult::Filtered;
        }

        // Feed the raw sample into the playback tracker (position-based state machine)
        let event = self.tracker.process(&raw);

        match event {
            TrackEvent::ReadyToLog(np) => {
                // Normalize the metadata before logging
                match normalize::normalize(np) {
                    Some(normalized) => DetectResult::PlayReady(normalized),
                    None => DetectResult::Filtered, // normalizer rejected it
                }
            }
            TrackEvent::TrackEnded { listened_ms, skipped, replay_count, completion_percentage, pause_count, seek_count, session_id } => {
                DetectResult::TrackFinished { listened_ms, skipped, replay_count, completion_percentage, pause_count, seek_count, session_id }
            }
            TrackEvent::StillPlaying => {
                // Build a live NowPlaying snapshot for the UI
                let np = NowPlaying {
                    title: raw.title.clone(),
                    artist: raw.artist.clone(),
                    album: raw.album.clone(),
                    duration_ms: raw.duration_ms,
                    source_app: raw.source_app.clone(),
                    is_playing: raw.is_playing,
                    listened_ms: self.tracker.current_listen_ms(),
                    site: self.tracker.current_site().map(|s| s.to_string()),
                    skipped: false,
                    replay_count: self.tracker.current_replay_count(),
                    is_muted: self.tracker.current_muted(),
                    completion_percentage: self.tracker.current_completion(),
                    pause_count: self.tracker.current_pause_count(),
                    seek_count: self.tracker.current_seek_count(),
                    content_type: "MUSIC".to_string(),
                    session_id: self.tracker.current_session_id().to_string(),
                    volume_level: self.tracker.current_volume(),
                };
                // Apply normalization for display
                match normalize::normalize(np) {
                    Some(np) => DetectResult::NowPlaying(np),
                    None => DetectResult::Filtered,
                }
            }
            TrackEvent::NoAction => {
                // New track just appeared, still accumulating listen time.
                // Show as "now playing" in UI.
                let np = NowPlaying {
                    title: raw.title.clone(),
                    artist: raw.artist.clone(),
                    album: raw.album.clone(),
                    duration_ms: raw.duration_ms,
                    source_app: raw.source_app.clone(),
                    is_playing: raw.is_playing,
                    listened_ms: 0,
                    site: self.tracker.current_site().map(|s| s.to_string()),
                    skipped: false,
                    replay_count: 0,
                    is_muted: self.tracker.current_muted(),
                    completion_percentage: 0.0,
                    pause_count: 0,
                    seek_count: 0,
                    content_type: "MUSIC".to_string(),
                    session_id: self.tracker.current_session_id().to_string(),
                    volume_level: self.tracker.current_volume(),
                };
                match normalize::normalize(np) {
                    Some(np) => DetectResult::NowPlaying(np),
                    None => DetectResult::Filtered,
                }
            }
        }
    }

    /// Detect now playing from the UI command (on-demand, bypasses tracker).
    pub async fn detect_now_playing(&self) -> Option<NowPlaying> {
        let raw = self.detect_raw().await?;
        if !site_detect::should_track(&raw) {
            return None;
        }
        let np = NowPlaying {
            title: raw.title,
            artist: raw.artist,
            album: raw.album,
            duration_ms: raw.duration_ms,
            source_app: raw.source_app,
            is_playing: raw.is_playing,
            listened_ms: self.tracker.current_listen_ms(),
            site: self.tracker.current_site().map(|s| s.to_string()),
            skipped: false,
            replay_count: self.tracker.current_replay_count(),
            is_muted: self.tracker.current_muted(),
            completion_percentage: self.tracker.current_completion(),
            pause_count: self.tracker.current_pause_count(),
            seek_count: self.tracker.current_seek_count(),
            content_type: "MUSIC".to_string(),
            session_id: self.tracker.current_session_id().to_string(),
            volume_level: self.tracker.current_volume(),
        };
        normalize::normalize(np)
    }

    /// Raw OS-level detection without normalization.
    async fn detect_raw(&self) -> Option<RawMediaInfo> {
        #[cfg(target_os = "macos")]
        {
            macos::get_now_playing().await
        }
        #[cfg(target_os = "linux")]
        {
            return linux::get_now_playing().await;
        }
        #[cfg(target_os = "windows")]
        {
            return windows_media::get_now_playing().await;
        }
        #[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
        {
            None
        }
    }

}

/// Result of a single poll cycle.
#[derive(Debug)]
pub enum DetectResult {
    /// A track has accumulated enough listen time — ready to log.
    PlayReady(NowPlaying),
    /// A previously-tracked track ended or changed. Contains its final stats.
    TrackFinished {
        listened_ms: i64,
        skipped: bool,
        replay_count: u32,
        completion_percentage: f64,
        pause_count: u32,
        seek_count: u32,
        session_id: String,
    },
    /// Currently playing, not yet ready to log. Carry the live snapshot.
    NowPlaying(NowPlaying),
    /// Content was filtered out (blocked site, non-music, etc.).
    Filtered,
    /// Nothing is playing.
    NothingPlaying,
}

/// Background polling loop that detects media changes and enqueues plays.
pub async fn start_polling(app_handle: tauri::AppHandle) {
    info!("Starting media polling loop");

    // Load user-defined known artists and YouTube channels from DB at startup
    {
        let state = app_handle.state::<AppState>();
        let db = state.db.lock().await;

        if let Ok(names) = db.get_user_known_artist_names() {
            artist_parser::load_user_known_bands(&names);
            info!("Loaded {} user-known artist names", names.len());
        }
        if let Ok(channels) = db.get_user_youtube_channel_names() {
            artist_parser::load_user_youtube_channels(&channels);
            info!("Loaded {} user YouTube channels", channels.len());
        }
    }

    loop {
        let poll_secs = {
            let state = app_handle.state::<AppState>();
            let db = state.db.lock().await;
            db.get_settings()
                .map(|s| s.polling_interval_seconds.max(1) as u64)
                .unwrap_or(5)
        };

        tokio::time::sleep(tokio::time::Duration::from_secs(poll_secs)).await;

        let state = app_handle.state::<AppState>();

        let settings = {
            let db = state.db.lock().await;
            db.get_settings().unwrap_or_default()
        };

        if !settings.auto_detect_enabled {
            continue;
        }

        // Battery saver: skip tracking when battery is low and unplugged
        if settings.low_battery_threshold > 0
            && crate::battery::should_pause_for_battery(settings.low_battery_threshold)
        {
            debug!(
                "Battery saver active (threshold {}%): skipping media poll",
                settings.low_battery_threshold
            );
            let _ = app_handle.emit("battery-saver-active", true);
            continue;
        }

        let mut media = state.media.lock().await;
        let result = media.poll().await;

        // Surface browser JS-disabled diagnostic to the frontend
        #[cfg(target_os = "macos")]
        if let Some(browser_name) = macos::take_browser_js_disabled() {
            let _ = app_handle.emit("browser-js-disabled", &browser_name);
        }

        // Surface missing playerctl to the frontend (Linux only, fires once)
        #[cfg(target_os = "linux")]
        if linux::take_playerctl_missing_ui() {
            let _ = app_handle.emit("playerctl-missing", ());
        }

        match result {
            DetectResult::PlayReady(now_playing) => {
                let timestamp = chrono::Utc::now().timestamp_millis();
                let db = state.db.lock().await;

                // Dedup check
                if db
                    .has_recent_play(&now_playing.title, &now_playing.artist, timestamp)
                    .unwrap_or(false)
                {
                    continue;
                }

                let play = crate::db::models::Play {
                    id: None,
                    title: now_playing.title.clone(),
                    artist: now_playing.artist.clone(),
                    album: now_playing.album.clone(),
                    duration_ms: now_playing.duration_ms,
                    timestamp_utc: timestamp,
                    source_app: now_playing.source_app.clone(),
                    status: crate::db::models::PlayStatus::Queued,
                    listened_ms: now_playing.listened_ms,
                    skipped: now_playing.skipped,
                    replay_count: now_playing.replay_count,
                    is_muted: now_playing.is_muted,
                    completion_percentage: now_playing.completion_percentage,
                    pause_count: now_playing.pause_count,
                    seek_count: now_playing.seek_count,
                    session_id: now_playing.session_id.clone(),
                    site: now_playing.site.clone().unwrap_or_default(),
                    content_type: now_playing.content_type.clone(),
                    volume_level: now_playing.volume_level,
                };

                match db.insert_play(&play) {
                    Ok(_) => {
                        info!(
                            "Logged: {} - {} (listened {}ms, completion {:.1}%, site: {:?}, muted: {}, replays: {}, pauses: {}, seeks: {}, type: {})",
                            play.title,
                            play.artist,
                            play.listened_ms,
                            play.completion_percentage,
                            now_playing.site,
                            now_playing.is_muted,
                            now_playing.replay_count,
                            now_playing.pause_count,
                            now_playing.seek_count,
                            play.content_type,
                        );
                        let _ = app_handle.emit("play-added", &play);
                        let _ = app_handle.emit("now-playing-changed", &now_playing);
                    }
                    Err(e) => error!("Failed to insert play: {}", e),
                }
            }
            DetectResult::NowPlaying(np) => {
                let _ = app_handle.emit("now-playing-changed", &np);
            }
            DetectResult::TrackFinished { listened_ms, skipped, replay_count, completion_percentage, pause_count, seek_count, session_id: _ } => {
                debug!(
                    "Track finished: listened={}ms skipped={} replays={} completion={:.1}% pauses={} seeks={}",
                    listened_ms, skipped, replay_count, completion_percentage, pause_count, seek_count
                );
            }
            DetectResult::Filtered | DetectResult::NothingPlaying => {}
        }
    }
}

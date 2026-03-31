use crate::db::models::{new_session_id, NowPlaying, RawMediaInfo};
use log::{debug, info, warn};

/// Minimum listen time (ms) before a track is eligible for scrobbling.
const MIN_LISTEN_TIME_MS: i64 = 15_000; // 15 seconds

/// Minimum play duration to even record (filters accidental plays).
/// Matches Android's MIN_PLAY_DURATION_MS.
const MIN_PLAY_DURATION_MS: i64 = 5_000; // 5 seconds

/// If position jumps forward by more than this (ms) in a single poll, it's a seek/skip.
const SKIP_THRESHOLD_MS: i64 = 5_000;

/// If position resets to near 0 while the same track is playing, it's a replay.
const REPLAY_POSITION_THRESHOLD_MS: i64 = 3_000;

/// Volume below this is "effectively muted".
const MUTE_VOLUME_THRESHOLD: f64 = 0.01;

/// A track is considered "skipped" if the user listened to less than this fraction.
/// Matches Android's 30% threshold.
const SKIP_FRACTION: f64 = 0.30;

/// Full play threshold — listened to >= 80% of duration.
/// Matches Android's FULL_PLAY_THRESHOLD.
const FULL_PLAY_FRACTION: f64 = 0.80;

/// Maximum listen time cap per session (1 hour), matching Android.
/// Prevents runaway accumulation from stuck sessions.
const MAX_LISTEN_CAP_MS: i64 = 3_600_000; // 1 hour

/// Maximum multiplier of estimated duration. If listened > 3x duration,
/// cap it. Handles stuck/looping detection.
const MAX_DURATION_MULTIPLIER: f64 = 3.0;

/// Tighter duration multiplier when position data is unavailable.
/// Without position tracking we cannot detect replays, so cap at ~1.1x
/// to allow a small tolerance for polling jitter.
const WALL_CLOCK_DURATION_MULTIPLIER: f64 = 1.1;



/// Tracks per-track playback state derived from raw media position samples.
/// Matches Android's position-based tracking with pause/seek/replay awareness.
#[derive(Debug)]
pub struct PlaybackTracker {
    /// Unique key for the current track.
    current_track_key: Option<String>,
    /// Total accumulated listen time for the current track (ms).
    /// Only increments when playing, not muted, at normal rate.
    accumulated_listen_ms: i64,
    /// Last observed playback position (ms).
    last_position_ms: i64,
    /// Wall-clock time of the last poll (ms since epoch).
    last_poll_time: i64,
    /// Whether the track was already logged (emitted as a play).
    logged: bool,
    /// Number of detected replays for this track.
    replay_count: u32,
    /// Whether audio was muted at last check.
    is_muted: bool,
    /// Track duration from metadata (ms); 0 = unknown.
    track_duration_ms: i64,
    /// Site extracted from the media source URL.
    detected_site: Option<String>,
    /// Number of pause events (transitions from playing → paused).
    pause_count: u32,
    /// Number of seek events (position jumps beyond threshold).
    seek_count: u32,
    /// Whether the previous poll was in a playing state.
    was_playing: bool,
    /// Unique session ID for this track play (UUID v4).
    session_id: String,
    /// Volume level at last poll (0.0–1.0, -1.0 = unknown).
    last_volume: f64,
    /// Timestamp of last state persistence (for crash recovery).
    last_persist_time: i64,
    /// Number of position updates received (for data quality tracking).
    position_updates_count: u32,
    /// Whether the session was interrupted (e.g., another app took focus).
    was_interrupted: bool,
    /// Whether valid position data was ever received for this track.
    /// When false, tighter duration caps are applied since we can't
    /// distinguish replays from stuck tracking.
    has_position_data: bool,
    /// Cached raw info for the current track (for finalize when track changes).
    last_raw: Option<RawMediaInfo>,
    /// Number of consecutive polls where position did not advance.
    /// After this exceeds a threshold, fall back to wall-clock accumulation
    /// to handle WinRT position-stuck bugs on Windows.
    consecutive_stuck_polls: u32,
}

impl PlaybackTracker {
    pub fn new() -> Self {
        Self {
            current_track_key: None,
            accumulated_listen_ms: 0,
            last_position_ms: -1,
            last_poll_time: 0,
            logged: false,
            replay_count: 0,
            is_muted: false,
            track_duration_ms: 0,
            detected_site: None,
            pause_count: 0,
            seek_count: 0,
            was_playing: false,
            session_id: String::new(),
            last_volume: -1.0,
            last_persist_time: 0,
            position_updates_count: 0,
            was_interrupted: false,
            has_position_data: false,
            last_raw: None,
            consecutive_stuck_polls: 0,
        }
    }

    /// Process a raw media sample. Returns a TrackEvent describing what happened.
    ///
    /// Call this every poll interval. The tracker maintains all state internally.
    /// Implements Android-parity position-based tracking with full state machine.
    pub fn process(&mut self, raw: &RawMediaInfo) -> TrackEvent {
        let now = chrono::Utc::now().timestamp_millis();
        let track_key = format!("{}|{}|{}", raw.title, raw.artist, raw.album);

        // --- Track change detection ---
        if self.current_track_key.as_deref() != Some(&track_key) {
            // Finalize the previous track
            let prev_event = self.finalize_previous_track();

            // Start tracking new track
            debug!(
                "PlaybackTracker: new track '{}' by '{}' (source: {})",
                raw.title, raw.artist, raw.source_app
            );
            self.current_track_key = Some(track_key);
            self.accumulated_listen_ms = 0;
            self.last_position_ms = raw.position_ms;
            self.last_poll_time = now;
            self.logged = false;
            self.replay_count = 0;
            self.track_duration_ms = raw.duration_ms;
            self.detected_site = super::site_detect::extract_site(raw);
            self.is_muted = is_muted(raw);
            self.pause_count = 0;
            self.seek_count = 0;
            self.was_playing = raw.is_playing;
            self.session_id = new_session_id();
            self.last_volume = raw.volume;
            self.last_persist_time = now;
            self.position_updates_count = 0;
            self.was_interrupted = false;
            self.has_position_data = raw.position_ms >= 0;
            self.last_raw = Some(raw.clone());
            self.consecutive_stuck_polls = 0;

            return prev_event;
        }

        // --- Same track, update state ---
        let wall_delta = (now - self.last_poll_time).max(0);
        let pos_delta = raw.position_ms - self.last_position_ms;

        // Track position updates for data quality
        if raw.position_ms >= 0 {
            self.position_updates_count += 1;
            self.has_position_data = true;
        }

        // Detect pause: was playing → now not playing
        if self.was_playing && !raw.is_playing {
            self.pause_count += 1;
            debug!(
                "PlaybackTracker: pause #{} detected for '{}'",
                self.pause_count, raw.title
            );
        }

        // Detect replay: position jumped back to near 0 while still same track
        if self.last_position_ms > REPLAY_POSITION_THRESHOLD_MS
            && raw.position_ms >= 0
            && raw.position_ms < REPLAY_POSITION_THRESHOLD_MS
            && raw.is_playing
        {
            self.replay_count += 1;
            info!(
                "PlaybackTracker: replay #{} detected for '{}'",
                self.replay_count, raw.title
            );
        }

        // Detect seek: position jumped forward significantly
        // (more than expected from normal playback)
        let expected_advance = wall_delta;
        if pos_delta > expected_advance + SKIP_THRESHOLD_MS && raw.is_playing {
            self.seek_count += 1;
            debug!(
                "PlaybackTracker: seek #{} detected in '{}' (jumped {}ms, expected ~{}ms)",
                self.seek_count, raw.title, pos_delta, expected_advance
            );
            // Don't count the skipped portion as listen time
        }

        // Detect backward seek (not replay — position went back but not to start)
        if pos_delta < -SKIP_THRESHOLD_MS
            && raw.position_ms >= REPLAY_POSITION_THRESHOLD_MS
            && raw.is_playing
        {
            self.seek_count += 1;
            debug!(
                "PlaybackTracker: backward seek #{} in '{}' (jumped {}ms)",
                self.seek_count, raw.title, pos_delta
            );
        }

        // Update mute state
        self.is_muted = is_muted(raw);
        self.last_volume = raw.volume;

        // Accumulate listen time only when:
        // 1. Actively playing
        // 2. Not muted
        // 3. Playback rate is normal-ish (0.5–2.0)
        // 4. Haven't exceeded duration caps
        let rate_ok = raw.playback_rate < 0.0
            || (raw.playback_rate >= 0.5 && raw.playback_rate <= 2.0);
        if raw.is_playing && !self.is_muted && rate_ok {
            // Use the smaller of wall-clock delta and position delta
            // to avoid over-counting during seeks
            let listen_increment = if raw.position_ms >= 0 && self.last_position_ms >= 0 {
                // Position data available on both sides — trust it
                if pos_delta > 0 {
                    // Normal playback: position is advancing — use min of position
                    // and wall-clock to avoid over-counting during seeks.
                    self.consecutive_stuck_polls = 0;
                    pos_delta.min(wall_delta).max(0)
                } else {
                    // pos_delta == 0: position not advancing this poll.
                    // This can happen legitimately (very short poll interval, or
                    // position updates lag behind playback) or as a WinRT bug
                    // where GSMTC freezes the reported position.
                    //
                    // Strategy: tolerate up to 2 consecutive stuck polls (noise),
                    // then fall back to wall-clock. Reset as soon as position moves.
                    self.consecutive_stuck_polls += 1;
                    if self.consecutive_stuck_polls > 2 {
                        // Position is genuinely stuck — WinRT not updating.
                        // Use wall-clock so the track still accumulates.
                        debug!(
                            "PlaybackTracker: position stuck for {} polls (pos={}ms), using wall-clock for '{}'",
                            self.consecutive_stuck_polls, raw.position_ms, raw.title
                        );
                        wall_delta
                    } else {
                        // First couple of stuck polls — could be polling jitter.
                        // Don't accumulate yet, wait for next poll.
                        0
                    }
                }
            } else {
                // Position truly unavailable (pos < 0) — fall back to wall-clock
                self.consecutive_stuck_polls = 0;
                wall_delta
            };

            // Apply duration cap: don't accumulate beyond caps
            let capped_increment = self.apply_duration_cap(listen_increment);
            self.accumulated_listen_ms += capped_increment;
        }

        // Update state
        self.was_playing = raw.is_playing;
        self.last_position_ms = raw.position_ms;
        self.last_poll_time = now;
        self.last_raw = Some(raw.clone());

        // Check if ready to log
        if !self.logged && self.accumulated_listen_ms >= MIN_LISTEN_TIME_MS {
            self.logged = true;
            return TrackEvent::ReadyToLog(self.build_now_playing(raw));
        }

        TrackEvent::StillPlaying
    }

    /// Called when playback stops or track becomes `None`.
    /// Finalizes the current track.
    pub fn on_playback_stopped(&mut self) -> TrackEvent {
        self.finalize_previous_track()
    }

    /// Apply duration caps matching Android:
    /// - Max 1 hour per session
    /// - Max 3x estimated duration (if known)
    fn apply_duration_cap(&self, increment: i64) -> i64 {
        let new_total = self.accumulated_listen_ms + increment;

        // Cap 1: absolute maximum of 1 hour
        if new_total > MAX_LISTEN_CAP_MS {
            let remaining = (MAX_LISTEN_CAP_MS - self.accumulated_listen_ms).max(0);
            if remaining == 0 {
                warn!(
                    "PlaybackTracker: hit 1-hour cap for current track ({}ms accumulated)",
                    self.accumulated_listen_ms
                );
            }
            return remaining;
        }

        // Cap 2: duration-based cap.
        // When we have real position data, allow up to 3x (accounts for replays).
        // When position is unavailable (wall-clock only), cap at ~1.1x to prevent
        // runaway accumulation — without position we can't detect replays.
        if self.track_duration_ms > 0 {
            let multiplier = if self.has_position_data {
                MAX_DURATION_MULTIPLIER
            } else {
                WALL_CLOCK_DURATION_MULTIPLIER
            };
            let max_by_duration = (self.track_duration_ms as f64 * multiplier) as i64;
            if new_total > max_by_duration {
                let remaining = (max_by_duration - self.accumulated_listen_ms).max(0);
                if remaining == 0 {
                    debug!(
                        "PlaybackTracker: hit {:.1}x duration cap ({}ms / {}ms, has_position={})",
                        multiplier, self.accumulated_listen_ms, self.track_duration_ms, self.has_position_data
                    );
                }
                return remaining;
            }
        }

        increment
    }

    /// Build a `NowPlaying` snapshot from the current tracker state.
    fn build_now_playing(&self, raw: &RawMediaInfo) -> NowPlaying {
        let completion = self.compute_completion_percentage();
        let is_skipped = self.is_track_skipped();
        NowPlaying {
            title: raw.title.clone(),
            artist: raw.artist.clone(),
            album: raw.album.clone(),
            duration_ms: raw.duration_ms,
            source_app: raw.source_app.clone(),
            is_playing: raw.is_playing,
            listened_ms: self.accumulated_listen_ms,
            site: self.detected_site.clone(),
            skipped: is_skipped,
            replay_count: self.replay_count,
            is_muted: self.is_muted,
            completion_percentage: completion,
            pause_count: self.pause_count,
            seek_count: self.seek_count,
            content_type: "MUSIC".to_string(), // will be updated by normalize
            session_id: self.session_id.clone(),
            volume_level: self.last_volume,
        }
    }

    /// Finalize the previous track when a track change occurs.
    fn finalize_previous_track(&mut self) -> TrackEvent {
        if self.current_track_key.is_some()
            && !self.logged
            && self.accumulated_listen_ms >= MIN_PLAY_DURATION_MS
        {
            let is_skipped = self.is_track_skipped();
            let completion = self.compute_completion_percentage();
            debug!(
                "PlaybackTracker: finalizing previous track (listened={}ms, completion={:.1}%, skipped={}, pauses={}, seeks={})",
                self.accumulated_listen_ms, completion, is_skipped, self.pause_count, self.seek_count
            );

            // If it met the minimum listen time, emit as a play
            if self.accumulated_listen_ms >= MIN_LISTEN_TIME_MS {
                if let Some(ref raw) = self.last_raw {
                    return TrackEvent::ReadyToLog(self.build_now_playing(raw));
                }
            }

            return TrackEvent::TrackEnded {
                listened_ms: self.accumulated_listen_ms,
                skipped: is_skipped,
                replay_count: self.replay_count,
                completion_percentage: completion,
                pause_count: self.pause_count,
                seek_count: self.seek_count,
                session_id: self.session_id.clone(),
            };
        }
        TrackEvent::NoAction
    }

    /// Compute completion percentage matching Android's calculation:
    /// (accumulated_listen_ms / estimated_duration) * 100
    fn compute_completion_percentage(&self) -> f64 {
        if self.track_duration_ms > 0 {
            let pct = (self.accumulated_listen_ms as f64 / self.track_duration_ms as f64) * 100.0;
            // Cap at 100% for display (replays can push it higher internally)
            pct.clamp(0.0, 100.0)
        } else {
            // Unknown duration — if we listened at least MIN_LISTEN_TIME, assume 90%
            // (matches Android's desktop play sync handling)
            if self.accumulated_listen_ms >= MIN_LISTEN_TIME_MS {
                90.0
            } else {
                0.0
            }
        }
    }

    /// Determine if the current track was skipped.
    /// Matches Android: <30% completion = skip (was 50%, now aligned with Android).
    fn is_track_skipped(&self) -> bool {
        if self.track_duration_ms > 0 {
            (self.accumulated_listen_ms as f64)
                < (self.track_duration_ms as f64 * SKIP_FRACTION)
        } else {
            false
        }
    }

    /// Check if the current track qualifies as a "full play" (>=80% completion).
    #[allow(dead_code)]
    pub fn is_full_play(&self) -> bool {
        if self.track_duration_ms > 0 {
            (self.accumulated_listen_ms as f64)
                >= (self.track_duration_ms as f64 * FULL_PLAY_FRACTION)
        } else {
            self.accumulated_listen_ms >= MIN_LISTEN_TIME_MS
        }
    }

    /// Get accumulated listen time for the current track.
    pub fn current_listen_ms(&self) -> i64 {
        self.accumulated_listen_ms
    }

    /// Get current muted state.
    pub fn current_muted(&self) -> bool {
        self.is_muted
    }

    /// Get current replay count.
    pub fn current_replay_count(&self) -> u32 {
        self.replay_count
    }

    /// Get detected site.
    pub fn current_site(&self) -> Option<&str> {
        self.detected_site.as_deref()
    }

    /// Get current pause count.
    pub fn current_pause_count(&self) -> u32 {
        self.pause_count
    }

    /// Get current seek count.
    pub fn current_seek_count(&self) -> u32 {
        self.seek_count
    }

    /// Get current session ID.
    pub fn current_session_id(&self) -> &str {
        &self.session_id
    }

    /// Get current volume level.
    pub fn current_volume(&self) -> f64 {
        self.last_volume
    }

    /// Get current completion percentage.
    pub fn current_completion(&self) -> f64 {
        self.compute_completion_percentage()
    }
}

/// Events emitted by the PlaybackTracker.
#[derive(Debug)]
pub enum TrackEvent {
    /// Track has accumulated enough listen time — log it as a play.
    ReadyToLog(NowPlaying),
    /// Previous track ended/changed. Contains its final listen stats.
    TrackEnded {
        listened_ms: i64,
        skipped: bool,
        replay_count: u32,
        completion_percentage: f64,
        pause_count: u32,
        seek_count: u32,
        session_id: String,
    },
    /// Still playing the same track, not yet ready to log.
    StillPlaying,
    /// Nothing to do (no previous track, or was already logged).
    NoAction,
}

fn is_muted(raw: &RawMediaInfo) -> bool {
    raw.volume >= 0.0 && raw.volume < MUTE_VOLUME_THRESHOLD
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::models::RawMediaInfo;

    fn make_raw(title: &str, artist: &str, pos_ms: i64, dur_ms: i64, source: &str) -> RawMediaInfo {
        RawMediaInfo {
            title: title.to_string(),
            artist: artist.to_string(),
            album: String::new(),
            duration_ms: dur_ms,
            position_ms: pos_ms,
            source_app: source.to_string(),
            is_playing: true,
            volume: 0.8,
            url: None,
            playback_rate: 1.0,
        }
    }

    #[test]
    fn test_new_track_does_not_log_immediately() {
        let mut tracker = PlaybackTracker::new();
        let raw = make_raw("Song", "Artist", 0, 200_000, "Spotify");
        let event = tracker.process(&raw);
        assert!(matches!(event, TrackEvent::NoAction));
    }

    #[test]
    fn test_log_after_min_listen_time() {
        let mut tracker = PlaybackTracker::new();

        // Start track
        let raw = make_raw("Song", "Artist", 0, 200_000, "Spotify");
        tracker.process(&raw);

        // Simulate passage of time by manipulating internal state
        tracker.last_poll_time -= 16_000; // pretend 16s of wall-clock passed

        // Next poll with advanced position
        let raw2 = make_raw("Song", "Artist", 16_000, 200_000, "Spotify");
        let event = tracker.process(&raw2);
        assert!(matches!(event, TrackEvent::ReadyToLog(_)));
    }

    #[test]
    fn test_muted_does_not_accumulate() {
        let mut tracker = PlaybackTracker::new();

        let mut raw = make_raw("Song", "Artist", 0, 200_000, "Spotify");
        raw.volume = 0.0; // muted
        tracker.process(&raw);

        tracker.last_poll_time -= 20_000;

        let mut raw2 = make_raw("Song", "Artist", 20_000, 200_000, "Spotify");
        raw2.volume = 0.0; // still muted
        let event = tracker.process(&raw2);

        // Should NOT log because muted
        assert!(matches!(event, TrackEvent::StillPlaying));
        assert_eq!(tracker.accumulated_listen_ms, 0);
    }

    #[test]
    fn test_replay_detection() {
        let mut tracker = PlaybackTracker::new();

        // Start track
        let raw = make_raw("Song", "Artist", 100_000, 200_000, "Spotify");
        tracker.process(&raw);

        // Simulate time passing
        tracker.last_poll_time -= 5_000;

        // Position resets to near 0 (replay)
        let raw2 = make_raw("Song", "Artist", 1_000, 200_000, "Spotify");
        tracker.process(&raw2);

        assert_eq!(tracker.replay_count, 1);
    }

    #[test]
    fn test_pause_counting() {
        let mut tracker = PlaybackTracker::new();

        // Start playing
        let raw = make_raw("Song", "Artist", 0, 200_000, "Spotify");
        tracker.process(&raw);

        // Simulate time passing  
        tracker.last_poll_time -= 5_000;

        // Now paused
        let mut raw2 = make_raw("Song", "Artist", 5_000, 200_000, "Spotify");
        raw2.is_playing = false;
        tracker.process(&raw2);

        assert_eq!(tracker.pause_count, 1);

        // Resume
        tracker.last_poll_time -= 3_000;
        let raw3 = make_raw("Song", "Artist", 5_000, 200_000, "Spotify");
        tracker.process(&raw3);

        // Pause again
        tracker.last_poll_time -= 5_000;
        let mut raw4 = make_raw("Song", "Artist", 10_000, 200_000, "Spotify");
        raw4.is_playing = false;
        tracker.process(&raw4);

        assert_eq!(tracker.pause_count, 2);
    }

    #[test]
    fn test_seek_counting() {
        let mut tracker = PlaybackTracker::new();

        // Start playing at position 0
        let raw = make_raw("Song", "Artist", 0, 200_000, "Spotify");
        tracker.process(&raw);

        // Small wall-clock delta but huge position jump = seek
        tracker.last_poll_time -= 5_000;
        let raw2 = make_raw("Song", "Artist", 120_000, 200_000, "Spotify");
        tracker.process(&raw2);

        assert_eq!(tracker.seek_count, 1);
    }

    #[test]
    fn test_duration_cap_1_hour() {
        let mut tracker = PlaybackTracker::new();

        // Start a very long track
        let raw = make_raw("Song", "Artist", 0, 7_200_000, "Spotify"); // 2 hour track
        tracker.process(&raw);

        // Force accumulated time near the cap
        tracker.accumulated_listen_ms = MAX_LISTEN_CAP_MS - 1000;
        tracker.last_poll_time -= 5_000;

        let raw2 = make_raw("Song", "Artist", 3_595_000, 7_200_000, "Spotify");
        tracker.process(&raw2);

        // Should be capped at MAX_LISTEN_CAP_MS
        assert!(tracker.accumulated_listen_ms <= MAX_LISTEN_CAP_MS);
    }

    #[test]
    fn test_duration_cap_3x_multiplier() {
        let mut tracker = PlaybackTracker::new();

        // Start a 1-minute track
        let raw = make_raw("Song", "Artist", 0, 60_000, "Spotify");
        tracker.process(&raw);

        // Force accumulated time near 3x the duration (180s)
        tracker.accumulated_listen_ms = 175_000;
        tracker.last_poll_time -= 10_000;

        let raw2 = make_raw("Song", "Artist", 10_000, 60_000, "Spotify");
        tracker.process(&raw2);

        // Should be capped at 3x duration (180,000ms)
        assert!(tracker.accumulated_listen_ms <= 180_000);
    }

    #[test]
    fn test_completion_percentage() {
        let mut tracker = PlaybackTracker::new();

        let raw = make_raw("Song", "Artist", 0, 200_000, "Spotify");
        tracker.process(&raw);

        // Listen for 100s of a 200s track = 50%
        tracker.accumulated_listen_ms = 100_000;
        let pct = tracker.compute_completion_percentage();
        assert!((pct - 50.0).abs() < 0.01);
    }

    #[test]
    fn test_skip_at_30_percent() {
        let mut tracker = PlaybackTracker::new();

        let raw = make_raw("Song", "Artist", 0, 100_000, "Spotify");
        tracker.process(&raw);

        // 29% of 100s = 29s — should be a skip
        tracker.accumulated_listen_ms = 29_000;
        assert!(tracker.is_track_skipped());

        // 31% = not a skip
        tracker.accumulated_listen_ms = 31_000;
        assert!(!tracker.is_track_skipped());
    }

    #[test]
    fn test_session_id_changes_per_track() {
        let mut tracker = PlaybackTracker::new();

        let raw1 = make_raw("Song A", "Artist", 0, 200_000, "Spotify");
        tracker.process(&raw1);
        let session1 = tracker.session_id.clone();

        let raw2 = make_raw("Song B", "Artist", 0, 200_000, "Spotify");
        tracker.process(&raw2);
        let session2 = tracker.session_id.clone();

        assert_ne!(session1, session2);
        assert!(!session1.is_empty());
        assert!(!session2.is_empty());
    }

    #[test]
    fn test_skip_detection() {
        let mut tracker = PlaybackTracker::new();

        // Start at position 0 of a 4-minute song
        let raw = make_raw("Song", "Artist", 0, 240_000, "Spotify");
        tracker.process(&raw);

        // Listen for only 10 seconds (below 30% threshold)
        tracker.last_poll_time -= 10_000;
        let raw2 = make_raw("Song", "Artist", 10_000, 240_000, "Spotify");
        tracker.process(&raw2);

        // Track changes — 10s < 15s MIN_LISTEN_TIME but >= 5s MIN_PLAY_DURATION
        let raw3 = make_raw("New Song", "New Artist", 0, 180_000, "Spotify");
        let event = tracker.process(&raw3);

        match event {
            TrackEvent::TrackEnded { listened_ms, skipped, .. } => {
                assert_eq!(listened_ms, 10_000);
                assert!(skipped); // 10s / 240s = 4.2% < 30%
            }
            _ => panic!("Expected TrackEnded, got {:?}", event),
        }
    }

    #[test]
    fn test_track_change_finalizes_previous() {
        let mut tracker = PlaybackTracker::new();

        // Start track
        let raw = make_raw("Song A", "Artist", 0, 200_000, "Spotify");
        tracker.process(&raw);

        // Listen for 20s
        tracker.last_poll_time -= 20_000;
        let raw2 = make_raw("Song A", "Artist", 20_000, 200_000, "Spotify");
        tracker.process(&raw2); // This triggers scrobble

        // Change track
        let raw3 = make_raw("Song B", "Artist", 0, 180_000, "Spotify");
        let event = tracker.process(&raw3);

        // Previous was already scrobbled, so no finalize needed
        assert!(matches!(event, TrackEvent::NoAction));
    }
}

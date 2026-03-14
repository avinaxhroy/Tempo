use crate::db::models::RawMediaInfo;
use log::{debug, warn};
use once_cell::sync::Lazy;
use serde::Deserialize;
use std::collections::HashMap;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::RwLock;
use std::time::{Duration, Instant};

/// Set when a Chromium browser has "Allow JavaScript from Apple Events" disabled.
static BROWSER_JS_DISABLED: AtomicBool = AtomicBool::new(false);
static BROWSER_JS_DISABLED_NAME: Lazy<RwLock<String>> = Lazy::new(|| RwLock::new(String::new()));

/// Returns `Some(browser_name)` if JavaScript from Apple Events was detected as
/// disabled in a browser with a known music tab during the last polling cycle.
/// Calling this clears the flag so the event is only emitted once per detection.
pub fn take_browser_js_disabled() -> Option<String> {
    if BROWSER_JS_DISABLED.swap(false, Ordering::SeqCst) {
        BROWSER_JS_DISABLED_NAME.read().ok().map(|n| n.clone()).filter(|n| !n.is_empty())
    } else {
        None
    }
}

const CHROMIUM_BROWSERS: &[(&str, &str)] = &[
    ("Brave Browser", "Brave (macOS)"),
    ("Google Chrome", "Chrome (macOS)"),
    ("Chromium", "Chromium (macOS)"),
    ("Microsoft Edge", "Edge (macOS)"),
    ("Arc", "Arc (macOS)"),
    ("Opera", "Opera (macOS)"),
    ("Vivaldi", "Vivaldi (macOS)"),
];

const SAFARI_BROWSER_SOURCE: &str = "Safari (macOS)";

const MUSIC_TAB_PATTERNS: &[(&str, i32)] = &[
    ("music.youtube.com", 120),
    ("open.spotify.com", 115),
    ("music.apple.com", 110),
    ("soundcloud.com", 105),
    ("tidal.com", 100),
    ("deezer.com", 95),
    ("bandcamp.com", 90),
];

const YOUTUBE_MUSIC_TITLE_SUFFIXES: &[&str] = &["| YouTube Music", "- YouTube Music"];
const OEMBED_CACHE_TTL: Duration = Duration::from_secs(60 * 30);

static YOUTUBE_OEMBED_CACHE: Lazy<RwLock<HashMap<String, YouTubeOEmbedCacheEntry>>> =
    Lazy::new(|| RwLock::new(HashMap::new()));

#[derive(Clone)]
struct YouTubeOEmbedCacheEntry {
    artist: String,
    title: String,
    fetched_at: Instant,
}

struct BrowserTabMetadata {
    url: String,
    title: String,
    artist: String,
    album: String,
    is_playing: bool,
    duration_ms: i64,
    position_ms: i64,
    playback_rate: f64,
    volume: f64,
    js_disabled: bool,
}

#[derive(Deserialize)]
struct YouTubeOEmbedResponse {
    title: String,
    author_name: String,
}

/// Detect now playing on macOS.
///
/// Native players that expose stable AppleScript dictionaries are queried first because
/// that path is App-Store-safe and remains reliable even when the system Now Playing
/// APIs are incomplete or gated on newer macOS releases. The global Now Playing center
/// remains as a fallback for broader app coverage.
pub async fn get_now_playing() -> Option<RawMediaInfo> {
    if let Some(np) = query_spotify() {
        return Some(np);
    }
    if let Some(np) = query_apple_music() {
        return Some(np);
    }

    let now_playing = query_now_playing_center();
    let browser = get_browser_tab_snapshot().await;

    select_best_macos_candidate(now_playing, browser)
}

/// Best-effort browser snapshot when the macOS media session API exposes nothing.
///
/// This path does not provide native position or duration metadata, so playback
/// tracking falls back to wall-clock listen time. It is good enough for browser
/// scrobbling on known music sites, but pause/seek accuracy is lower than native
/// app or media-session backed detection.
async fn get_browser_tab_snapshot() -> Option<RawMediaInfo> {
    let mut best_match: Option<(i32, RawMediaInfo)> = None;

    for (app_name, source_name) in CHROMIUM_BROWSERS {
        let output = match Command::new("osascript")
            .arg("-e")
            .arg(build_chromium_tab_script(app_name))
            .output()
        {
            Ok(output) => output,
            Err(err) => {
                debug!("Failed to query macOS browser tabs for {}: {}", app_name, err);
                continue;
            }
        };

        let result = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if let Some(candidate) = extract_best_browser_candidate(&result, source_name).await {
            update_best_browser_candidate(&mut best_match, candidate);
        }
    }

    let safari_output = match Command::new("osascript")
        .arg("-e")
        .arg(build_safari_tab_script())
        .output()
    {
        Ok(output) => output,
        Err(err) => {
            debug!("Failed to query macOS Safari tabs: {}", err);
            return best_match.map(|(_, raw)| raw);
        }
    };

    let safari_result = String::from_utf8_lossy(&safari_output.stdout).trim().to_string();
    if let Some(candidate) = extract_best_browser_candidate(&safari_result, SAFARI_BROWSER_SOURCE).await {
        update_best_browser_candidate(&mut best_match, candidate);
    }

    best_match.map(|(_, raw)| raw)
}

async fn extract_best_browser_candidate(
    result: &str,
    source_name: &str,
) -> Option<(i32, RawMediaInfo)> {
    if result.is_empty() {
        return None;
    }

    let mut best_match: Option<(i32, RawMediaInfo)> = None;
    let mut saw_js_disabled_music_tab = false;

    for line in result.lines() {
        let tab = match parse_browser_tab_metadata_row(line) {
            Some(tab) => tab,
            None => continue,
        };

        // When JS is disabled and we find a known music URL, try to use
        // MediaRemote system data to populate the metadata
        if tab.js_disabled && is_known_music_url(&tab.url) {
            saw_js_disabled_music_tab = true;

            if let Some(mr) = super::media_remote::get_now_playing_info() {
                // MediaRemote has data — use it as the browser candidate
                // but classify the source based on the music URL, not the system source
                let source = classify_browser_source(&tab.url, &tab.title, source_name);
                let is_playing = mr.playback_rate != 0.0;
                let score = if is_playing { 300 } else { 50 };

                let candidate = RawMediaInfo {
                    title: mr.title,
                    artist: mr.artist,
                    album: mr.album,
                    duration_ms: (mr.duration_secs * 1000.0) as i64,
                    position_ms: if mr.elapsed_secs >= 0.0 { (mr.elapsed_secs * 1000.0) as i64 } else { -1 },
                    source_app: source,
                    is_playing,
                    volume: -1.0,
                    url: Some(tab.url.trim().to_string()),
                    playback_rate: mr.playback_rate,
                };

                update_best_browser_candidate(&mut best_match, (score, candidate));
            }
            continue;
        }

        let cleaned_title = clean_browser_tab_title(&tab.title, &tab.url);
        if cleaned_title.is_empty() {
            continue;
        }

        let (mut track_title, mut artist) =
            parse_browser_tab_metadata(&cleaned_title, &tab.url, &tab.artist);

        if artist.is_empty() {
            if let Some((fallback_title, fallback_artist)) =
                enrich_youtube_music_metadata(&tab.url, &tab.title).await
            {
                if track_title.is_empty() {
                    track_title = fallback_title;
                }
                artist = fallback_artist;
            }
        }

        // Last resort: try to extract artist from "Artist - Title" format
        if artist.is_empty() {
            if let Some((extracted_artist, extracted_title)) = try_split_artist_title(&track_title) {
                artist = extracted_artist;
                track_title = extracted_title;
            }
        }

        // For known music streaming sites, allow candidates through even without artist
        // rather than losing the track entirely
        if artist.is_empty() && !is_known_music_url(&tab.url) {
            debug!(
                "macOS browser candidate rejected: missing artist for '{}' ({})",
                cleaned_title, tab.url
            );
            continue;
        }

        let score = match score_browser_candidate(&tab, &track_title, &artist) {
            Some(score) => score,
            None => continue,
        };

        let candidate = RawMediaInfo {
            title: track_title,
            artist,
            album: tab.album,
            duration_ms: tab.duration_ms,
            position_ms: tab.position_ms,
            source_app: classify_browser_source(&tab.url, &tab.title, source_name),
            is_playing: tab.is_playing,
            volume: tab.volume,
            url: Some(tab.url.trim().to_string()),
            playback_rate: tab.playback_rate,
        };

        update_best_browser_candidate(&mut best_match, (score, candidate));
    }

    if saw_js_disabled_music_tab {
        warn!(
            "Browser has JavaScript from Apple Events disabled. \
             To enable full music tracking in {}, go to: \
             View > Developer > Allow JavaScript from Apple Events",
            source_name
        );
        BROWSER_JS_DISABLED.store(true, Ordering::SeqCst);
        if let Ok(mut name) = BROWSER_JS_DISABLED_NAME.write() {
            *name = source_name.to_string();
        }
    }

    best_match
}

fn update_best_browser_candidate(
    best_match: &mut Option<(i32, RawMediaInfo)>,
    candidate: (i32, RawMediaInfo),
) {
    let replace = best_match
        .as_ref()
        .map(|(best_score, _)| candidate.0 > *best_score)
        .unwrap_or(true);
    if replace {
        *best_match = Some(candidate);
    }
}

fn select_best_macos_candidate(
    now_playing: Option<RawMediaInfo>,
    browser: Option<RawMediaInfo>,
) -> Option<RawMediaInfo> {
    match (now_playing, browser) {
        (Some(system), Some(browser)) => {
            if should_enrich_browser_now_playing(&system)
                && browser_metadata_matches(&system, &browser)
            {
                return Some(merge_browser_now_playing(system, browser));
            }

            let system_score = candidate_quality_score(&system);
            let browser_score = candidate_quality_score(&browser);
            debug!(
                "macOS candidate arbitration: system='{}' ({}) score={}, browser='{}' ({}) score={}",
                system.title,
                system.source_app,
                system_score,
                browser.title,
                browser.source_app,
                browser_score
            );

            if browser_score > system_score {
                Some(browser)
            } else {
                Some(system)
            }
        }
        (Some(system), None) => Some(system),
        (None, Some(browser)) => Some(browser),
        (None, None) => None,
    }
}

fn classify_browser_source(url: &str, raw_title: &str, fallback_source: &str) -> String {
    if is_youtube_music_tab(url, raw_title) {
        return format!("YouTube Music ({})", browser_name_from_source(fallback_source));
    }
    if url.to_lowercase().contains("open.spotify.com") {
        return format!("Spotify Web ({})", browser_name_from_source(fallback_source));
    }
    if url.to_lowercase().contains("music.apple.com") {
        return format!("Apple Music Web ({})", browser_name_from_source(fallback_source));
    }
    fallback_source.to_string()
}

fn browser_name_from_source(source: &str) -> &str {
    if source.contains("Brave") {
        "Brave"
    } else if source.contains("Chrome") {
        "Chrome"
    } else if source.contains("Edge") {
        "Edge"
    } else if source.contains("Firefox") {
        "Firefox"
    } else if source.contains("Safari") {
        "Safari"
    } else if source.contains("Arc") {
        "Arc"
    } else if source.contains("Opera") {
        "Opera"
    } else if source.contains("Vivaldi") {
        "Vivaldi"
    } else {
        "Browser"
    }
}

fn candidate_quality_score(raw: &RawMediaInfo) -> i32 {
    let mut score = 0;

    if !raw.title.trim().is_empty() {
        score += 40;
    }
    if !is_missing_artist(&raw.artist) {
        score += 50;
    }
    if !raw.album.trim().is_empty() {
        score += 10;
    }
    if raw.duration_ms > 0 {
        score += 15;
    }
    if raw.position_ms >= 0 {
        score += 15;
    }
    if raw.is_playing {
        score += 60;
    }
    if raw.url.is_some() {
        score += 10;
    }
    if !is_browser_source_name(&raw.source_app) {
        score += 20;
    }
    if let Some(url) = raw.url.as_deref() {
        score += score_music_tab(url, &raw.title).unwrap_or_default();
    }

    score
}

async fn enrich_youtube_music_metadata(url: &str, raw_title: &str) -> Option<(String, String)> {
    if !is_youtube_music_tab(url, raw_title) {
        return None;
    }

    let video_id = extract_youtube_video_id(url)?;
    if let Some((cached_title, cached_artist)) = get_cached_youtube_oembed(&video_id) {
        return Some((cached_title, cached_artist));
    }

    let endpoint = format!(
        "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v={video_id}&format=json"
    );
    let response = reqwest::get(endpoint).await.ok()?;
    let payload: YouTubeOEmbedResponse = response.json().await.ok()?;
    let artist = payload.author_name.trim().to_string();
    if artist.is_empty() {
        return None;
    }

    let title = clean_browser_tab_title(&payload.title, "https://music.youtube.com/watch");
    put_cached_youtube_oembed(&video_id, &title, &artist);
    Some((title, artist))
}

fn get_cached_youtube_oembed(video_id: &str) -> Option<(String, String)> {
    let cache = YOUTUBE_OEMBED_CACHE.read().ok()?;
    let entry = cache.get(video_id)?;
    if entry.fetched_at.elapsed() > OEMBED_CACHE_TTL {
        return None;
    }
    Some((entry.title.clone(), entry.artist.clone()))
}

fn put_cached_youtube_oembed(video_id: &str, title: &str, artist: &str) {
    if let Ok(mut cache) = YOUTUBE_OEMBED_CACHE.write() {
        cache.insert(
            video_id.to_string(),
            YouTubeOEmbedCacheEntry {
                artist: artist.to_string(),
                title: title.to_string(),
                fetched_at: Instant::now(),
            },
        );
    }
}

fn extract_youtube_video_id(url: &str) -> Option<String> {
    let lower = url.trim();
    if let Some(id) = lower.split("watch?v=").nth(1) {
        return Some(id.split('&').next()?.to_string());
    }
    if let Some(id) = lower.split("youtu.be/").nth(1) {
        return Some(id.split('?').next()?.to_string());
    }
    None
}

fn is_youtube_music_tab(url: &str, raw_title: &str) -> bool {
    let lower_url = url.to_lowercase();
    if lower_url.contains("music.youtube.com") {
        return true;
    }

    let trimmed = raw_title.trim();
    YOUTUBE_MUSIC_TITLE_SUFFIXES
        .iter()
        .any(|suffix| trimmed.ends_with(suffix))
}

fn query_spotify() -> Option<RawMediaInfo> {
    // Check the bundle identifier via System Events before using `tell application`.
    // This ensures we only target the native Spotify desktop app (com.spotify.client)
    // and never accidentally activate a Spotify PWA/web-app wrapper.
    let script = r#"
        set spotifyRunning to false
        tell application "System Events"
            set spList to (processes where name is "Spotify")
            if (count of spList) > 0 then
                try
                    set spBundle to (bundle identifier of first item of spList)
                    if spBundle is "com.spotify.client" then
                        set spotifyRunning to true
                    end if
                end try
            end if
        end tell
        if spotifyRunning then
            -- Target the native bundle ID directly so Launch Services never resolves
            -- a browser-installed Spotify web-player wrapper named "Spotify".
            tell application id "com.spotify.client"
                if player state is playing then
                    set trackName to name of current track
                    set trackArtist to artist of current track
                    set trackAlbum to album of current track
                    set trackDuration to duration of current track
                    set trackPosition to player position
                    set trackVolume to sound volume
                    return trackName & "||" & trackArtist & "||" & trackAlbum & "||" & trackDuration & "||" & trackPosition & "||" & trackVolume
                end if
            end tell
        end if
        return "NOT_RUNNING"
    "#;
    parse_osascript_result(script, "Spotify (macOS)")
}

fn query_apple_music() -> Option<RawMediaInfo> {
    // Same bundle ID check to ensure we only target the native Music.app (com.apple.Music).
    let script = r#"
        set musicRunning to false
        tell application "System Events"
            set mList to (processes where name is "Music")
            if (count of mList) > 0 then
                try
                    set mBundle to (bundle identifier of first item of mList)
                    if mBundle is "com.apple.Music" then
                        set musicRunning to true
                    end if
                end try
            end if
        end tell
        if musicRunning then
            tell application id "com.apple.Music"
                if player state is playing then
                    set trackName to name of current track
                    set trackArtist to artist of current track
                    set trackAlbum to album of current track
                    set trackDuration to duration of current track
                    set trackPosition to player position
                    set trackVolume to sound volume
                    return trackName & "||" & trackArtist & "||" & trackAlbum & "||" & (trackDuration * 1000 as integer) & "||" & trackPosition & "||" & trackVolume
                end if
            end tell
        end if
        return "NOT_RUNNING"
    "#;
    parse_osascript_result(script, "Apple Music (macOS)")
}

fn query_now_playing_center() -> Option<RawMediaInfo> {
    // Use the MediaRemote private framework directly — this is reliable on all
    // macOS versions and doesn't depend on nowplaying-cli being installed.
    if let Some(np) = query_media_remote() {
        return Some(np);
    }

    // Fall back to nowplaying-cli only if the framework call failed to load.
    query_nowplaying_cli()
}

fn query_media_remote() -> Option<RawMediaInfo> {
    let info = super::media_remote::get_now_playing_info()?;

    debug!(
        "MediaRemote: title='{}', artist='{}', album='{}', rate={}, bundle='{}'",
        info.title, info.artist, info.album, info.playback_rate, info.bundle_id
    );

    let source_app = if !info.bundle_id.is_empty() {
        identify_macos_source(&info.bundle_id)
    } else {
        "Desktop Player (macOS)".to_string()
    };

    Some(RawMediaInfo {
        title: info.title,
        artist: info.artist,
        album: info.album,
        duration_ms: (info.duration_secs * 1000.0) as i64,
        position_ms: if info.elapsed_secs >= 0.0 {
            (info.elapsed_secs * 1000.0) as i64
        } else {
            -1
        },
        source_app,
        is_playing: info.playback_rate != 0.0,
        volume: -1.0,
        url: None,
        playback_rate: info.playback_rate,
    })
}

fn query_nowplaying_cli() -> Option<RawMediaInfo> {
    let output = Command::new("sh")
        .arg("-c")
        .arg("which nowplaying-cli >/dev/null 2>&1 && nowplaying-cli get title artist album duration elapsedTime playbackRate clientPropertiesData 2>/dev/null || echo NOT_RUNNING")
        .output()
        .ok()?;

    let result = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if result == "NOT_RUNNING" || result.is_empty() {
        return None;
    }

    debug!("NowPlaying CLI result: {}", result);
    let lines: Vec<&str> = result.lines().collect();
    if lines.len() >= 2 {
        let title = lines.first().unwrap_or(&"").trim();
        let artist = lines.get(1).unwrap_or(&"").trim();
        let album = lines.get(2).unwrap_or(&"").trim();
        let duration: i64 = lines
            .get(3)
            .and_then(|d| d.trim().parse::<f64>().ok())
            .map(|d| (d * 1000.0) as i64)
            .unwrap_or(0);
        let elapsed: i64 = lines
            .get(4)
            .and_then(|d| d.trim().parse::<f64>().ok())
            .map(|d| (d * 1000.0) as i64)
            .unwrap_or(-1);
        let playback_rate: f64 = lines
            .get(5)
            .and_then(|d| d.trim().parse::<f64>().ok())
            .unwrap_or(-1.0);
        let client_info = lines.get(6).unwrap_or(&"").trim();

        if !title.is_empty() && title != "null" {
            let source_app = identify_macos_source(client_info);
            return Some(RawMediaInfo {
                title: title.to_string(),
                artist: artist.to_string(),
                album: album.to_string(),
                duration_ms: duration,
                position_ms: elapsed.max(-1),
                source_app,
                is_playing: playback_rate != 0.0,
                volume: -1.0,
                url: None,
                playback_rate,
            });
        }
    }

    None
}

fn should_enrich_browser_now_playing(raw: &RawMediaInfo) -> bool {
    is_browser_source_name(&raw.source_app) && is_missing_artist(&raw.artist)
}

fn is_browser_source_name(source: &str) -> bool {
    let lower = source.to_lowercase();
    ["chrome", "brave", "edge", "firefox", "safari", "opera", "vivaldi", "arc", "browser"]
        .iter()
        .any(|pattern| lower.contains(pattern))
}

fn is_missing_artist(artist: &str) -> bool {
    let trimmed = artist.trim();
    trimmed.is_empty() || trimmed.eq_ignore_ascii_case("null") || trimmed.eq_ignore_ascii_case("unknown")
}

fn browser_metadata_matches(now_playing: &RawMediaInfo, browser: &RawMediaInfo) -> bool {
    let left = comparable_title(&now_playing.title);
    let right = comparable_title(&browser.title);

    !left.is_empty() && left == right
}

fn comparable_title(title: &str) -> String {
    title
        .trim()
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn merge_browser_now_playing(now_playing: RawMediaInfo, browser: RawMediaInfo) -> RawMediaInfo {
    RawMediaInfo {
        title: if browser.title.trim().is_empty() { now_playing.title } else { browser.title },
        artist: browser.artist,
        album: if browser.album.trim().is_empty() { now_playing.album } else { browser.album },
        duration_ms: if browser.duration_ms > 0 { browser.duration_ms } else { now_playing.duration_ms },
        position_ms: if browser.position_ms >= 0 { browser.position_ms } else { now_playing.position_ms },
        source_app: browser.source_app,
        is_playing: browser.is_playing || now_playing.is_playing,
        volume: if browser.volume >= 0.0 { browser.volume } else { now_playing.volume },
        url: browser.url.or(now_playing.url),
        playback_rate: if browser.playback_rate >= 0.0 { browser.playback_rate } else { now_playing.playback_rate },
    }
}

fn parse_browser_tab_metadata_row(line: &str) -> Option<BrowserTabMetadata> {
    let parts: Vec<&str> = line.split("||").collect();
    if parts.len() < 9 {
        return None;
    }

    let url = parts[0].trim();
    if url.is_empty() {
        return None;
    }

    let playback_state = parts[4].trim();
    let js_disabled = playback_state == "__JS_DISABLED__";

    Some(BrowserTabMetadata {
        url: url.to_string(),
        title: parts[1].trim().to_string(),
        artist: parts[2].trim().to_string(),
        album: parts[3].trim().to_string(),
        is_playing: matches!(playback_state, "playing" | "true" | "1"),
        duration_ms: parse_browser_duration_ms(parts[5]),
        position_ms: parse_browser_position_ms(parts[6]),
        playback_rate: parse_browser_f64(parts[7]).unwrap_or(-1.0),
        volume: parse_browser_f64(parts[8]).unwrap_or(-1.0),
        js_disabled,
    })
}

fn parse_browser_duration_ms(value: &str) -> i64 {
    parse_browser_f64(value)
        .filter(|duration| duration.is_finite() && *duration > 0.0)
        .map(|duration| (duration * 1000.0) as i64)
        .unwrap_or(0)
}

fn parse_browser_position_ms(value: &str) -> i64 {
    parse_browser_f64(value)
        .filter(|position| position.is_finite() && *position >= 0.0)
        .map(|position| (position * 1000.0) as i64)
        .unwrap_or(-1)
}

fn parse_browser_f64(value: &str) -> Option<f64> {
    value.trim().parse::<f64>().ok()
}

fn score_browser_candidate(tab: &BrowserTabMetadata, cleaned_title: &str, artist: &str) -> Option<i32> {
    let mut score = score_music_tab(&tab.url, &tab.title)?;

    if tab.is_playing {
        score += 200;
    }
    if tab.duration_ms > 0 {
        score += 40;
    }
    if tab.position_ms >= 0 {
        score += 35;
    }
    if tab.playback_rate > 0.0 {
        score += 20;
    }
    if tab.volume >= 0.0 {
        score += 10;
    }
    if !artist.trim().is_empty() {
        score += 25;
    }
    if !cleaned_title.trim().is_empty() {
        score += 10;
    }

    if !tab.is_playing && tab.duration_ms <= 0 && tab.position_ms < 0 {
        return None;
    }

    Some(score)
}

/// Returns the browser media probe JavaScript.
///
/// IMPORTANT: This script is embedded inside an AppleScript `execute ... javascript "..."` string.
/// It MUST NOT contain unescaped double-quote characters (`"`), because those terminate the
/// AppleScript string literal and cause a parse error that silently kills all browser detection.
/// All backslashes must be doubled (`\\` → `\\\\`) because AppleScript treats `\` as an escape
/// character inside `"..."` strings. Use single quotes for JS strings.
fn browser_media_probe_script() -> &'static str {
    // Every `\` in the JS regex must be `\\` in the AppleScript string.
    // Rust raw string r#"..."# treats `\` as literal, so we write `\\` to get `\\` in output.
    r#"(() => {
        const sanitize = (value) => String(value || '').replace(/\\|\\|/g, ' ').replace(/\\s+/g, ' ').trim();
        const metadata = navigator.mediaSession && navigator.mediaSession.metadata;
        let artist = sanitize(metadata && metadata.artist);
        if (!artist) {
            const sel = 'meta[name=\\'music:musician\\'],meta[property=\\'music:musician\\'],meta[name=\\'og:audio:artist\\'],meta[property=\\'og:audio:artist\\']';
            const metaEl = document.querySelector(sel);
            if (metaEl && metaEl.content) artist = sanitize(metaEl.content);
        }
        const mediaElements = Array.from(document.querySelectorAll('audio,video'));
        const bestMedia = mediaElements.find((element) => !element.paused && !element.ended)
            || mediaElements.find((element) => (element.currentTime || 0) > 0 || Number.isFinite(element.duration))
            || null;
        const playbackState = (navigator.mediaSession && navigator.mediaSession.playbackState)
            || (bestMedia ? (bestMedia.paused ? 'paused' : 'playing') : 'none');
        const payload = [
            sanitize(metadata && metadata.title),
            artist,
            sanitize(metadata && metadata.album),
            sanitize(playbackState),
            bestMedia && Number.isFinite(bestMedia.duration) ? String(bestMedia.duration) : '',
            bestMedia && Number.isFinite(bestMedia.currentTime) ? String(bestMedia.currentTime) : '',
            bestMedia && Number.isFinite(bestMedia.playbackRate) ? String(bestMedia.playbackRate) : '',
            bestMedia && Number.isFinite(bestMedia.volume) ? String(bestMedia.volume) : ''
        ];
        return payload.join('||');
    })();"#
}

fn build_chromium_tab_script(app_name: &str) -> String {
    format!(
        r#"
            set tabRows to {{}}
            tell application "{app_name}"
                if it is not running then
                    return ""
                end if
                set jsEnabled to true
                try
                    set firstTab to tab 1 of window 1
                    execute firstTab javascript "1"
                on error
                    set jsEnabled to false
                end try
                repeat with w in every window
                    repeat with t in every tab of w
                        try
                            set tabUrl to (URL of t as text)
                            set tabTitle to (title of t as text)
                            set tabArtist to ""
                            set tabAlbum to ""
                            set tabPlaybackState to "none"
                            set tabDuration to ""
                            set tabPosition to ""
                            set tabPlaybackRate to ""
                            set tabVolume to ""
                            if jsEnabled then
                                try
                                    set mediaMetadata to execute t javascript "{browser_media_probe}"
                                    if mediaMetadata is not "" then
                                        set oldDelimiters to AppleScript's text item delimiters
                                        set AppleScript's text item delimiters to "||"
                                        set metadataParts to every text item of mediaMetadata
                                        set AppleScript's text item delimiters to oldDelimiters
                                        if (count of metadataParts) > 0 then
                                            set metadataTitle to item 1 of metadataParts
                                            if metadataTitle is not "" then set tabTitle to metadataTitle
                                        end if
                                        if (count of metadataParts) > 1 then
                                            set metadataArtist to item 2 of metadataParts
                                            if metadataArtist is not "" then set tabArtist to metadataArtist
                                        end if
                                        if (count of metadataParts) > 2 then
                                            set metadataAlbum to item 3 of metadataParts
                                            if metadataAlbum is not "" then set tabAlbum to metadataAlbum
                                        end if
                                        if (count of metadataParts) > 3 then
                                            set metadataPlaybackState to item 4 of metadataParts
                                            if metadataPlaybackState is not "" then set tabPlaybackState to metadataPlaybackState
                                        end if
                                        if (count of metadataParts) > 4 then
                                            set metadataDuration to item 5 of metadataParts
                                            if metadataDuration is not "" then set tabDuration to metadataDuration
                                        end if
                                        if (count of metadataParts) > 5 then
                                            set metadataPosition to item 6 of metadataParts
                                            if metadataPosition is not "" then set tabPosition to metadataPosition
                                        end if
                                        if (count of metadataParts) > 6 then
                                            set metadataPlaybackRate to item 7 of metadataParts
                                            if metadataPlaybackRate is not "" then set tabPlaybackRate to metadataPlaybackRate
                                        end if
                                        if (count of metadataParts) > 7 then
                                            set metadataVolume to item 8 of metadataParts
                                            if metadataVolume is not "" then set tabVolume to metadataVolume
                                        end if
                                    end if
                                end try
                                if tabUrl contains "music.youtube.com" then
                                    try
                                        if tabTitle is "" then set tabTitle to execute t javascript "(() => {{ const titleNode = document.querySelector('ytmusic-player-bar .title') || document.querySelector('ytmusic-player-bar .content-info-wrapper .title'); return titleNode ? (titleNode.textContent || '').replace(/\\s+/g, ' ').trim() : document.title; }})();"
                                        -- Always extract artist from the byline so featured artists are included.
                                        -- navigator.mediaSession.metadata.artist only returns the primary artist on
                                        -- YouTube Music; the byline text (e.g. "Artist • feat. Featured • Album")
                                        -- is the authoritative source for the full artist string.
                                        set ytmBylineArtist to execute t javascript "(() => {{ const n = (s) => (s || '').replace(/\\s+/g, ' ').trim(); const sels = ['ytmusic-player-bar .byline', 'ytmusic-player-bar .subtitle', 'ytmusic-player-bar yt-formatted-string.byline', 'ytmusic-player-bar .content-info-wrapper .subtitle']; for (const sel of sels) {{ const el = document.querySelector(sel); if (!el) continue; const full = n(el.textContent); if (!full) continue; const parts = full.split(/[\\u2022\\u00B7]/).map(n).filter(Boolean); if (!parts.length) continue; const primary = parts[0]; const featured = parts.slice(1).filter(p => /^feat/i.test(p)).map(p => n(p.replace(/^feat\\w*\\.?\\s*/i, ''))); return featured.length ? primary + ' feat. ' + featured.join(', ') : primary; }} return ''; }})();"
                                        if ytmBylineArtist is not "" then set tabArtist to ytmBylineArtist
                                        if tabAlbum is "" then set tabAlbum to execute t javascript "(() => {{ const albumNodes = Array.from(document.querySelectorAll('ytmusic-player-bar .byline a')).map((node) => (node.textContent || '').replace(/\\s+/g, ' ').trim()).filter(Boolean).filter((value) => value !== '\\u2022'); return albumNodes.length > 1 ? albumNodes[albumNodes.length - 1] : ''; }})();"
                                    end try
                                else if tabUrl contains "open.spotify.com" then
                                    try
                                        if tabTitle is "" then set tabTitle to execute t javascript "(() => {{ const titleNode = document.querySelector('[data-testid=nowplaying-track-link]') || document.querySelector('[data-testid=entityTitle]'); return titleNode ? (titleNode.textContent || '').replace(/\\s+/g, ' ').trim() : document.title; }})();"
                                        if tabArtist is "" then set tabArtist to execute t javascript "(() => {{ const c = (el) => el ? (el.textContent || '').replace(/\\s+/g, ' ').trim() : ''; const sels = ['[data-testid=context-item-info-artist] a', '[data-testid=track-info-artists] a']; for (const sel of sels) {{ const nodes = Array.from(document.querySelectorAll(sel)).map(n => c(n)).filter(Boolean); if (nodes.length) return Array.from(new Set(nodes)).join(', '); }} const footer = document.querySelector('footer, [data-testid=now-playing-bar], .Root__now-playing-bar'); if (footer) {{ const links = Array.from(footer.querySelectorAll('a')).filter(a => (a.href || '').includes('/artist/')); const names = links.map(a => c(a)).filter(Boolean); if (names.length) return Array.from(new Set(names)).join(', '); }} return ''; }})();"
                                        if tabAlbum is "" then set tabAlbum to execute t javascript "(() => {{ const albumNode = document.querySelector('[data-testid=context-item-info-album]') || document.querySelector('[data-testid=album-link]'); return albumNode ? (albumNode.textContent || '').replace(/\\s+/g, ' ').trim() : ''; }})();"
                                    end try
                                else if tabUrl contains "music.apple.com" then
                                    try
                                        if tabTitle is "" then set tabTitle to execute t javascript "(() => {{ const titleNode = document.querySelector('.web-chrome-playback-lcd__song-name-scroll-inner-text') || document.querySelector('.web-chrome-playback-lcd__song-name-wrapper a'); return titleNode ? (titleNode.textContent || '').replace(/\\s+/g, ' ').trim() : document.title; }})();"
                                        if tabArtist is "" then set tabArtist to execute t javascript "(() => {{ const artistNodes = Array.from(document.querySelectorAll('.web-chrome-playback-lcd__sub-copy-scroll-inner-text a')).map((node) => (node.textContent || '').replace(/\\s+/g, ' ').trim()).filter(Boolean); return artistNodes.length ? Array.from(new Set(artistNodes)).join(', ') : ''; }})();"
                                        if tabAlbum is "" then set tabAlbum to execute t javascript "(() => {{ const albumNodes = Array.from(document.querySelectorAll('.web-chrome-playback-lcd__sub-copy-scroll-inner-text a')).map((node) => (node.textContent || '').replace(/\\s+/g, ' ').trim()).filter(Boolean); return albumNodes.length > 1 ? albumNodes[albumNodes.length - 1] : ''; }})();"
                                    end try
                                end if
                            else
                                set tabPlaybackState to "__JS_DISABLED__"
                            end if
                            set end of tabRows to (tabUrl & "||" & tabTitle & "||" & tabArtist & "||" & tabAlbum & "||" & tabPlaybackState & "||" & tabDuration & "||" & tabPosition & "||" & tabPlaybackRate & "||" & tabVolume)
                        end try
                    end repeat
                end repeat
            end tell
            set AppleScript's text item delimiters to linefeed
            return tabRows as text
        "#,
        browser_media_probe = browser_media_probe_script()
    )
}

fn build_safari_tab_script() -> &'static str {
    r#"
        set tabRows to {}
        tell application "Safari"
            if it is not running then
                return ""
            end if
            repeat with w in every window
                repeat with t in every tab of w
                    try
                        set tabUrl to (URL of t as text)
                        set tabTitle to (name of t as text)
                        set tabArtist to ""
                        set tabAlbum to ""
                        set tabPlaybackState to "none"
                        set tabDuration to ""
                        set tabPosition to ""
                        set tabPlaybackRate to ""
                        set tabVolume to ""
                        try
                            set mediaMetadata to do JavaScript "(() => { const sanitize = (value) => String(value || '').replace(/\\|\\|/g, ' ').replace(/\\s+/g, ' ').trim(); const metadata = navigator.mediaSession && navigator.mediaSession.metadata; const mediaElements = Array.from(document.querySelectorAll('audio,video')); const bestMedia = mediaElements.find((element) => !element.paused && !element.ended) || mediaElements.find((element) => (element.currentTime || 0) > 0 || Number.isFinite(element.duration)) || null; const playbackState = (navigator.mediaSession && navigator.mediaSession.playbackState) || (bestMedia ? (bestMedia.paused ? 'paused' : 'playing') : 'none'); const payload = [sanitize(metadata && metadata.title), sanitize(metadata && metadata.artist), sanitize(metadata && metadata.album), sanitize(playbackState), bestMedia && Number.isFinite(bestMedia.duration) ? String(bestMedia.duration) : '', bestMedia && Number.isFinite(bestMedia.currentTime) ? String(bestMedia.currentTime) : '', bestMedia && Number.isFinite(bestMedia.playbackRate) ? String(bestMedia.playbackRate) : '', bestMedia && Number.isFinite(bestMedia.volume) ? String(bestMedia.volume) : '']; return payload.join('||'); })();" in t
                            if mediaMetadata is not "" then
                                set oldDelimiters to AppleScript's text item delimiters
                                set AppleScript's text item delimiters to "||"
                                set metadataParts to every text item of mediaMetadata
                                set AppleScript's text item delimiters to oldDelimiters
                                if (count of metadataParts) > 0 then
                                    set metadataTitle to item 1 of metadataParts
                                    if metadataTitle is not "" then set tabTitle to metadataTitle
                                end if
                                if (count of metadataParts) > 1 then
                                    set metadataArtist to item 2 of metadataParts
                                    if metadataArtist is not "" then set tabArtist to metadataArtist
                                end if
                                if (count of metadataParts) > 2 then
                                    set metadataAlbum to item 3 of metadataParts
                                    if metadataAlbum is not "" then set tabAlbum to metadataAlbum
                                end if
                                if (count of metadataParts) > 3 then
                                    set metadataPlaybackState to item 4 of metadataParts
                                    if metadataPlaybackState is not "" then set tabPlaybackState to metadataPlaybackState
                                end if
                                if (count of metadataParts) > 4 then
                                    set metadataDuration to item 5 of metadataParts
                                    if metadataDuration is not "" then set tabDuration to metadataDuration
                                end if
                                if (count of metadataParts) > 5 then
                                    set metadataPosition to item 6 of metadataParts
                                    if metadataPosition is not "" then set tabPosition to metadataPosition
                                end if
                                if (count of metadataParts) > 6 then
                                    set metadataPlaybackRate to item 7 of metadataParts
                                    if metadataPlaybackRate is not "" then set tabPlaybackRate to metadataPlaybackRate
                                end if
                                if (count of metadataParts) > 7 then
                                    set metadataVolume to item 8 of metadataParts
                                    if metadataVolume is not "" then set tabVolume to metadataVolume
                                end if
                            end if
                        end try
                        if tabUrl contains "music.youtube.com" then
                            try
                                if tabTitle is "" then set tabTitle to do JavaScript "(() => { const titleNode = document.querySelector('ytmusic-player-bar .title') || document.querySelector('ytmusic-player-bar .content-info-wrapper .title'); return titleNode ? (titleNode.textContent || '').replace(/\\s+/g, ' ').trim() : document.title; })();" in t
                                if tabArtist is "" then set tabArtist to do JavaScript "(() => { const c = (el) => el ? (el.textContent || '').replace(/\\s+/g, ' ').trim() : ''; const linkSels = ['ytmusic-player-bar .byline a', 'ytmusic-player-bar .subtitle a', 'ytmusic-player-bar yt-formatted-string.byline a', 'ytmusic-player-bar .content-info-wrapper .subtitle a']; for (const sel of linkSels) { const t = c(document.querySelector(sel)); if (t && t !== '\\u2022' && t !== '\\u00B7') return t; } const textSels = ['ytmusic-player-bar .byline', 'ytmusic-player-bar .subtitle', 'ytmusic-player-bar yt-formatted-string.byline']; for (const sel of textSels) { const el = document.querySelector(sel); if (el) { const p = c(el).split(/[\\u2022\\u00B7]/); if (p[0] && p[0].trim()) return p[0].trim(); } } return ''; })();" in t
                                if tabAlbum is "" then set tabAlbum to do JavaScript "(() => { const albumNodes = Array.from(document.querySelectorAll('ytmusic-player-bar .byline a')).map((node) => (node.textContent || '').replace(/\\s+/g, ' ').trim()).filter(Boolean).filter((value) => value !== '\\u2022'); return albumNodes.length > 1 ? albumNodes[albumNodes.length - 1] : ''; })();" in t
                            end try
                        else if tabUrl contains "open.spotify.com" then
                            try
                                if tabTitle is "" then set tabTitle to do JavaScript "(() => { const titleNode = document.querySelector('[data-testid=nowplaying-track-link]') || document.querySelector('[data-testid=entityTitle]'); return titleNode ? (titleNode.textContent || '').replace(/\\s+/g, ' ').trim() : document.title; })();" in t
                                if tabArtist is "" then set tabArtist to do JavaScript "(() => { const c = (el) => el ? (el.textContent || '').replace(/\\s+/g, ' ').trim() : ''; const sels = ['[data-testid=context-item-info-artist] a', '[data-testid=track-info-artists] a']; for (const sel of sels) { const nodes = Array.from(document.querySelectorAll(sel)).map(n => c(n)).filter(Boolean); if (nodes.length) return Array.from(new Set(nodes)).join(', '); } const footer = document.querySelector('footer, [data-testid=now-playing-bar], .Root__now-playing-bar'); if (footer) { const links = Array.from(footer.querySelectorAll('a')).filter(a => (a.href || '').includes('/artist/')); const names = links.map(a => c(a)).filter(Boolean); if (names.length) return Array.from(new Set(names)).join(', '); } return ''; })();" in t
                                if tabAlbum is "" then set tabAlbum to do JavaScript "(() => { const albumNode = document.querySelector('[data-testid=context-item-info-album]') || document.querySelector('[data-testid=album-link]'); return albumNode ? (albumNode.textContent || '').replace(/\\s+/g, ' ').trim() : ''; })();" in t
                            end try
                        else if tabUrl contains "music.apple.com" then
                            try
                                if tabTitle is "" then set tabTitle to do JavaScript "(() => { const titleNode = document.querySelector('.web-chrome-playback-lcd__song-name-scroll-inner-text') || document.querySelector('.web-chrome-playback-lcd__song-name-wrapper a'); return titleNode ? (titleNode.textContent || '').replace(/\\s+/g, ' ').trim() : document.title; })();" in t
                                if tabArtist is "" then set tabArtist to do JavaScript "(() => { const artistNodes = Array.from(document.querySelectorAll('.web-chrome-playback-lcd__sub-copy-scroll-inner-text a')).map((node) => (node.textContent || '').replace(/\\s+/g, ' ').trim()).filter(Boolean); return artistNodes.length ? Array.from(new Set(artistNodes)).join(', ') : ''; })();" in t
                                if tabAlbum is "" then set tabAlbum to do JavaScript "(() => { const albumNodes = Array.from(document.querySelectorAll('.web-chrome-playback-lcd__sub-copy-scroll-inner-text a')).map((node) => (node.textContent || '').replace(/\\s+/g, ' ').trim()).filter(Boolean); return albumNodes.length > 1 ? albumNodes[albumNodes.length - 1] : ''; })();" in t
                            end try
                        end if
                        set end of tabRows to (tabUrl & "||" & tabTitle & "||" & tabArtist & "||" & tabAlbum & "||" & tabPlaybackState & "||" & tabDuration & "||" & tabPosition & "||" & tabPlaybackRate & "||" & tabVolume)
                    end try
                end repeat
            end repeat
        end tell
        set AppleScript's text item delimiters to linefeed
        return tabRows as text
    "#
}

fn score_music_tab(url: &str, raw_title: &str) -> Option<i32> {
    if is_youtube_music_tab(url, raw_title) {
        return Some(120);
    }

    let lower = url.trim().to_lowercase();
    MUSIC_TAB_PATTERNS
        .iter()
        .find_map(|(pattern, score)| lower.contains(pattern).then_some(*score))
}

fn clean_browser_tab_title(title: &str, url: &str) -> String {
    let mut cleaned = title.trim().to_string();
    let lower_url = url.to_lowercase();

    let suffixes: &[&str] = if is_youtube_music_tab(url, title) {
        YOUTUBE_MUSIC_TITLE_SUFFIXES
    } else if lower_url.contains("open.spotify.com") {
        &["| Spotify", "- Spotify"]
    } else if lower_url.contains("soundcloud.com") {
        &["| Listen online for free on SoundCloud", "| SoundCloud"]
    } else if lower_url.contains("youtube.com") || lower_url.contains("youtu.be") {
        &["- YouTube", "| YouTube"]
    } else {
        &[]
    };

    for suffix in suffixes {
        if cleaned.ends_with(suffix) {
            cleaned.truncate(cleaned.len().saturating_sub(suffix.len()));
            cleaned = cleaned.trim().to_string();
            break;
        }
    }

    let lower_cleaned = cleaned.to_lowercase();
    if lower_cleaned.is_empty()
        || lower_cleaned == "youtube music"
        || lower_cleaned == "spotify"
        || lower_cleaned == "soundcloud"
        || lower_cleaned == "youtube"
    {
        return String::new();
    }

    cleaned
}

fn clean_browser_artist(artist: &str, url: &str) -> String {
    let normalized = artist.split_whitespace().collect::<Vec<_>>().join(" ");
    if normalized.is_empty() {
        return String::new();
    }

    if url.to_lowercase().contains("music.youtube.com") {
        return normalized
            .split(['•', '·'])
            .next()
            .unwrap_or("")
            .trim()
            .to_string();
    }

    normalized.trim().to_string()
}

fn parse_browser_tab_metadata(title: &str, url: &str, hinted_artist: &str) -> (String, String) {
    let hinted_artist = clean_browser_artist(hinted_artist, url);

    if !hinted_artist.is_empty() {
        return (title.trim().to_string(), hinted_artist);
    }

    (title.trim().to_string(), String::new())
}

/// Try to split "Artist - Title" or "Artist – Title" format commonly found in browser tab titles.
/// Only splits on the FIRST separator to handle titles like "Artist - Song - Remix".
/// Returns (artist, track_title) or None if no split was possible.
fn try_split_artist_title(title: &str) -> Option<(String, String)> {
    if title.len() < 5 {
        return None;
    }

    for sep in [" - ", " \u{2013} ", " \u{2014} "] {
        if let Some(pos) = title.find(sep) {
            let left = title[..pos].trim();
            let right = title[pos + sep.len()..].trim();

            if !left.is_empty() && !right.is_empty() && left.len() <= 80 {
                return Some((left.to_string(), right.to_string()));
            }
        }
    }

    None
}

/// Check if a URL belongs to a known music streaming site.
fn is_known_music_url(url: &str) -> bool {
    let lower = url.to_lowercase();
    [
        "music.youtube.com",
        "open.spotify.com",
        "music.apple.com",
        "soundcloud.com",
        "tidal.com",
        "deezer.com",
        "bandcamp.com",
        "jiosaavn.com",
        "gaana.com",
    ]
    .iter()
    .any(|domain| lower.contains(domain))
}

/// Identify the source application on macOS from nowplaying-cli client data.
/// Attempts to distinguish browsers, Tidal, and other players.
fn identify_macos_source(client_info: &str) -> String {
    let lower = client_info.to_lowercase();

    if lower.contains("chrome") || lower.contains("chromium") {
        "Chrome (macOS)".to_string()
    } else if lower.contains("brave") {
        "Brave (macOS)".to_string()
    } else if lower.contains("firefox") {
        "Firefox (macOS)".to_string()
    } else if lower.contains("safari") || lower.contains("webkit") {
        "Safari (macOS)".to_string()
    } else if lower.contains("edge") {
        "Edge (macOS)".to_string()
    } else if lower.contains("arc") {
        "Arc (macOS)".to_string()
    } else if lower.contains("opera") {
        "Opera (macOS)".to_string()
    } else if lower.contains("cider") {
        "Cider (macOS)".to_string()
    } else if lower.contains("tidal") {
        "Tidal (macOS)".to_string()
    } else if lower.contains("deezer") {
        "Deezer (macOS)".to_string()
    } else if lower.contains("amazon") {
        "Amazon Music (macOS)".to_string()
    } else if !lower.is_empty() && lower != "null" {
        format!("{} (macOS)", client_info)
    } else {
        "Desktop Player (macOS)".to_string()
    }
}

fn parse_osascript_result(script: &str, source: &str) -> Option<RawMediaInfo> {
    let output = Command::new("osascript")
        .arg("-e")
        .arg(script)
        .output()
        .ok()?;

    let result = String::from_utf8_lossy(&output.stdout).trim().to_string();

    if result.is_empty() || result == "NOT_RUNNING" {
        return None;
    }

    let parts: Vec<&str> = result.split("||").collect();
    if parts.len() >= 3 {
        let title = parts[0].trim().to_string();
        let artist = parts[1].trim().to_string();
        let album = parts[2].trim().to_string();
        let duration_ms: i64 = parts
            .get(3)
            .and_then(|d| d.trim().parse().ok())
            .unwrap_or(0);
        // Position in seconds from AppleScript
        let position_ms: i64 = parts
            .get(4)
            .and_then(|d| d.trim().parse::<f64>().ok())
            .map(|s| (s * 1000.0) as i64)
            .unwrap_or(-1);
        // Volume 0-100 from AppleScript → normalize to 0.0-1.0
        let volume: f64 = parts
            .get(5)
            .and_then(|d| d.trim().parse::<f64>().ok())
            .map(|v| v / 100.0)
            .unwrap_or(-1.0);

        if !title.is_empty() {
            return Some(RawMediaInfo {
                title,
                artist,
                album,
                duration_ms,
                position_ms,
                source_app: source.to_string(),
                is_playing: true,
                volume,
                url: None,
                playback_rate: 1.0,
            });
        }
    }

    None
}

#[cfg(test)]
mod tests {
    use super::{
        browser_metadata_matches, classify_browser_source, clean_browser_artist,
        clean_browser_tab_title, comparable_title, extract_youtube_video_id, is_known_music_url,
        is_youtube_music_tab, merge_browser_now_playing, parse_browser_tab_metadata,
        parse_browser_tab_metadata_row, score_browser_candidate, score_music_tab,
        should_enrich_browser_now_playing, try_split_artist_title,
    };
    use crate::db::models::RawMediaInfo;

    fn make_raw(title: &str, artist: &str, source_app: &str) -> RawMediaInfo {
        RawMediaInfo {
            title: title.to_string(),
            artist: artist.to_string(),
            album: String::new(),
            duration_ms: 123_000,
            position_ms: 45_000,
            source_app: source_app.to_string(),
            is_playing: true,
            volume: -1.0,
            url: None,
            playback_rate: 1.0,
        }
    }

    #[test]
    fn scores_known_music_tabs() {
        assert_eq!(score_music_tab("https://music.youtube.com/watch?v=123", "Song | YouTube Music"), Some(120));
        assert_eq!(score_music_tab("https://www.youtube.com/watch?v=123", "Song | YouTube Music"), Some(120));
        assert_eq!(score_music_tab("https://example.com", "Example"), None);
    }

    #[test]
    fn cleans_youtube_music_titles() {
        let cleaned = clean_browser_tab_title(
            "Namastute | YouTube Music",
            "https://music.youtube.com/watch?v=123",
        );
        assert_eq!(cleaned, "Namastute");
    }

    #[test]
    fn detects_youtube_music_tab_from_title_suffix() {
        assert!(is_youtube_music_tab(
            "https://www.youtube.com/watch?v=abc",
            "Pomfret Fry | YouTube Music"
        ));
    }

    #[test]
    fn extracts_youtube_video_id_from_watch_url() {
        assert_eq!(
            extract_youtube_video_id("https://www.youtube.com/watch?v=Hf7fasXWtBc&t=2s"),
            Some("Hf7fasXWtBc".to_string())
        );
    }

    #[test]
    fn preserves_spotify_title_without_artist_hint() {
        let (title, artist) = parse_browser_tab_metadata(
            "Feather - song by Nujabes, Cise Starr, Akin",
            "https://open.spotify.com/track/abc",
            "",
        );
        assert_eq!(title, "Feather - song by Nujabes, Cise Starr, Akin");
        assert_eq!(artist, "");
    }

    #[test]
    fn preserves_youtube_style_title_when_artist_is_unknown() {
        let (title, artist) = parse_browser_tab_metadata(
            "Nujabes - Feather",
            "https://www.youtube.com/watch?v=abc",
            "",
        );
        assert_eq!(title, "Nujabes - Feather");
        assert_eq!(artist, "");
    }

    #[test]
    fn enriches_browser_now_playing_when_artist_missing() {
        let raw = make_raw("Bag (feat. KR$NA)", "", "Brave (macOS)");
        assert!(should_enrich_browser_now_playing(&raw));
    }

    #[test]
    fn matches_browser_titles_after_normalization() {
        let now_playing = make_raw("Bag   (feat. KR$NA)", "", "Brave (macOS)");
        let browser = make_raw("Bag (feat. KR$NA)", "Karan Aujla", "YouTube Music (Brave)");
        assert!(browser_metadata_matches(&now_playing, &browser));
        assert_eq!(comparable_title(&now_playing.title), comparable_title(&browser.title));
    }

    #[test]
    fn merges_browser_artist_into_system_now_playing() {
        let now_playing = make_raw("Bag (feat. KR$NA)", "", "Brave (macOS)");
        let mut browser = make_raw("Bag (feat. KR$NA)", "Karan Aujla", "YouTube Music (Brave)");
        browser.album = "Single".to_string();
        browser.url = Some("https://music.youtube.com/watch?v=abc".to_string());

        let merged = merge_browser_now_playing(now_playing, browser);
        assert_eq!(merged.artist, "Karan Aujla");
        assert_eq!(merged.album, "Single");
        assert_eq!(merged.source_app, "YouTube Music (Brave)");
        assert_eq!(merged.duration_ms, 123_000);
        assert_eq!(merged.position_ms, 45_000);
    }

    #[test]
    fn parses_youtube_music_artist_hint() {
        let (title, artist) = parse_browser_tab_metadata(
            "BAWE MAIN CHECK",
            "https://music.youtube.com/watch?v=abc",
            "Jani • Single • 2026",
        );
        assert_eq!(title, "BAWE MAIN CHECK");
        assert_eq!(artist, "Jani");
    }

    #[test]
    fn cleans_youtube_music_artist_hint() {
        let artist = clean_browser_artist(
            "Jani   •  Single   •  2026",
            "https://music.youtube.com/watch?v=abc",
        );
        assert_eq!(artist, "Jani");
    }

    #[test]
    fn parses_browser_media_probe_row() {
        let row = "https://open.spotify.com/track/abc||Feather||Nujabes, Cise Starr||Modal Soul||playing||203.5||44.2||1||0.75";
        let parsed = parse_browser_tab_metadata_row(row).expect("row should parse");

        assert_eq!(parsed.url, "https://open.spotify.com/track/abc");
        assert_eq!(parsed.title, "Feather");
        assert_eq!(parsed.artist, "Nujabes, Cise Starr");
        assert!(parsed.is_playing);
        assert_eq!(parsed.duration_ms, 203_500);
        assert_eq!(parsed.position_ms, 44_200);
        assert_eq!(parsed.playback_rate, 1.0);
        assert_eq!(parsed.volume, 0.75);
        assert!(!parsed.js_disabled);
    }

    #[test]
    fn parses_browser_row_with_js_disabled_marker() {
        let row = "https://music.youtube.com/||YouTube Music||placeholder-artist||placeholder-album||__JS_DISABLED__||0||0||0||0";
        let parsed = parse_browser_tab_metadata_row(row).expect("row should parse");

        assert_eq!(parsed.url, "https://music.youtube.com/");
        assert_eq!(parsed.title, "YouTube Music");
        assert!(parsed.js_disabled);
        assert!(!parsed.is_playing);
    }

    #[test]
    fn prefers_playing_browser_tab_with_media_state() {
        let paused = parse_browser_tab_metadata_row(
            "https://music.youtube.com/watch?v=1||Song A||Artist A||Album A||paused||200||15||0||0.8",
        )
        .expect("paused row should parse");
        let playing = parse_browser_tab_metadata_row(
            "https://music.youtube.com/watch?v=2||Song B||Artist B||Album B||playing||210||52||1||0.8",
        )
        .expect("playing row should parse");

        let paused_score = score_browser_candidate(&paused, "Song A", "Artist A").expect("paused score");
        let playing_score = score_browser_candidate(&playing, "Song B", "Artist B").expect("playing score");

        assert!(playing_score > paused_score);
    }

    #[test]
    fn classifies_safari_music_tabs_with_browser_name() {
        assert_eq!(
            classify_browser_source(
                "https://music.youtube.com/watch?v=abc",
                "Song | YouTube Music",
                "Safari (macOS)"
            ),
            "YouTube Music (Safari)"
        );
    }

    #[test]
    fn splits_artist_title_format() {
        let result = try_split_artist_title("Nujabes - Feather");
        assert_eq!(result, Some(("Nujabes".to_string(), "Feather".to_string())));
    }

    #[test]
    fn splits_artist_title_with_en_dash() {
        let result = try_split_artist_title("The Weeknd \u{2013} Blinding Lights");
        assert_eq!(
            result,
            Some(("The Weeknd".to_string(), "Blinding Lights".to_string()))
        );
    }

    #[test]
    fn does_not_split_short_titles() {
        assert_eq!(try_split_artist_title("A-B"), None);
        assert_eq!(try_split_artist_title(""), None);
    }

    #[test]
    fn splits_only_first_separator() {
        let result = try_split_artist_title("Nujabes - Feather - Remix");
        assert_eq!(
            result,
            Some(("Nujabes".to_string(), "Feather - Remix".to_string()))
        );
    }

    #[test]
    fn recognizes_known_music_urls() {
        assert!(is_known_music_url("https://music.youtube.com/watch?v=abc"));
        assert!(is_known_music_url("https://open.spotify.com/track/abc"));
        assert!(is_known_music_url("https://soundcloud.com/artist/track"));
        assert!(!is_known_music_url("https://www.youtube.com/watch?v=abc"));
        assert!(!is_known_music_url("https://reddit.com/something"));
    }

    #[test]
    fn does_not_split_youtube_music_song_titles() {
        // YouTube Music titles are just the song name, no dash-separated artist
        assert_eq!(try_split_artist_title("Namastute"), None);
        assert_eq!(try_split_artist_title("BAWE MAIN CHECK"), None);
    }
}

use crate::db::models::RawMediaInfo;
use log::{debug, warn};
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::process::Command;

/// Set once at startup if `playerctl` is not found on the system.
static PLAYERCTL_MISSING_WARNED: AtomicBool = AtomicBool::new(false);
/// Set to true the first time playerctl is detected as missing; cleared by `take_playerctl_missing_ui`.
static PLAYERCTL_MISSING_UI: AtomicBool = AtomicBool::new(false);

/// Returns `true` (exactly once) when playerctl has been detected as missing for the
/// first time this session. Calling this clears the flag so the event fires only once.
pub fn take_playerctl_missing_ui() -> bool {
    PLAYERCTL_MISSING_UI.swap(false, Ordering::SeqCst)
}

/// Detect now playing on Linux using MPRIS D-Bus interface.
///
/// Queries all active players, scores each candidate, and picks the best.
/// Uses async `tokio::process::Command` to avoid blocking the runtime.
/// Falls back to D-Bus queries if `playerctl` is not installed.
pub async fn get_now_playing() -> Option<RawMediaInfo> {
    // Check playerctl availability once
    if !is_playerctl_available().await {
        if !PLAYERCTL_MISSING_WARNED.swap(true, Ordering::SeqCst) {
            warn!(
                "playerctl is not installed. Install it for better music tracking: \
                 sudo apt install playerctl (Debian/Ubuntu) or sudo dnf install playerctl (Fedora)"
            );
            // Signal the UI once so a notification banner can be shown
            PLAYERCTL_MISSING_UI.store(true, Ordering::SeqCst);
        }
        // Fall back to D-Bus
        return query_dbus_all_players().await;
    }

    // List all active MPRIS players and query each
    let candidates = query_all_playerctl_players().await;
    if candidates.is_empty() {
        // playerctl found no players — try D-Bus as fallback
        return query_dbus_all_players().await;
    }

    // Score and pick the best candidate
    Some(select_best_candidate(candidates))
}

/// Check if playerctl is available on the system.
async fn is_playerctl_available() -> bool {
    Command::new("which")
        .arg("playerctl")
        .output()
        .await
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// A parsed player candidate before scoring.
struct PlayerCandidate {
    title: String,
    artist: String,
    album: String,
    duration_ms: i64,
    position_ms: i64,
    player_name: String,
    is_playing: bool,
    volume: f64,
    url: Option<String>,
    playback_rate: f64,
}

/// Query all active players via `playerctl --list-all`, then get metadata from each.
async fn query_all_playerctl_players() -> Vec<PlayerCandidate> {
    let list_output = match Command::new("playerctl")
        .args(["--list-all"])
        .output()
        .await
    {
        Ok(o) if o.status.success() => o,
        _ => return Vec::new(),
    };

    let players_str = String::from_utf8_lossy(&list_output.stdout)
        .trim()
        .to_string();
    if players_str.is_empty() {
        return Vec::new();
    }

    let mut candidates = Vec::new();
    for player in players_str.lines() {
        let player = player.trim();
        if player.is_empty() {
            continue;
        }
        if let Some(c) = query_single_playerctl(player).await {
            candidates.push(c);
        }
    }

    candidates
}

/// Query metadata for a specific player instance.
async fn query_single_playerctl(player: &str) -> Option<PlayerCandidate> {
    let metadata_output = Command::new("playerctl")
        .args([
            "--player", player,
            "metadata", "--format",
            "{{title}}||{{artist}}||{{album}}||{{mpris:length}}||{{playerName}}||{{status}}||{{volume}}||{{xesam:url}}",
        ])
        .output()
        .await
        .ok()?;

    let metadata_result = String::from_utf8_lossy(&metadata_output.stdout)
        .trim()
        .to_string();
    if metadata_result.is_empty() {
        return None;
    }

    // Get position separately (it's a player property, not metadata)
    let pos_output = Command::new("playerctl")
        .args(["--player", player, "position"])
        .output()
        .await
        .ok();

    let position_ms: i64 = pos_output
        .and_then(|o| {
            let s = String::from_utf8_lossy(&o.stdout).trim().to_string();
            s.parse::<f64>().ok()
        })
        .map(|secs| (secs * 1000.0) as i64)
        .unwrap_or(-1);

    // Query playback rate (MPRIS Rate property)
    let rate_output = Command::new("playerctl")
        .args(["--player", player, "rate"])
        .output()
        .await
        .ok();

    let playback_rate: f64 = rate_output
        .and_then(|o| {
            if !o.status.success() {
                return None;
            }
            let s = String::from_utf8_lossy(&o.stdout).trim().to_string();
            s.parse::<f64>().ok()
        })
        .unwrap_or(-1.0); // -1 = unknown (tracker treats this as valid)

    debug!("playerctl [{}] result: {}", player, metadata_result);
    let parts: Vec<&str> = metadata_result.split("||").collect();
    if parts.len() < 4 {
        return None;
    }

    let title = parts[0].trim().to_string();
    if title.is_empty() {
        return None;
    }

    let artist = parts[1].trim().to_string();
    let album = parts[2].trim().to_string();
    // MPRIS length is in microseconds
    let duration_ms: i64 = parts[3]
        .trim()
        .parse::<i64>()
        .ok()
        .map(|us| us / 1000)
        .unwrap_or(0);
    let player_name = parts.get(4).unwrap_or(&"Unknown").trim().to_string();
    let status = parts.get(5).unwrap_or(&"").trim().to_string();
    let volume: f64 = parts
        .get(6)
        .and_then(|v| v.trim().parse::<f64>().ok())
        .unwrap_or(-1.0);
    let url: Option<String> = parts
        .get(7)
        .map(|u| u.trim().to_string())
        .filter(|u| !u.is_empty());

    let is_playing = status == "Playing";

    // Use actual playback rate when available, otherwise infer from status
    let effective_rate = if playback_rate >= 0.0 {
        playback_rate
    } else if is_playing {
        1.0
    } else {
        0.0
    };

    Some(PlayerCandidate {
        title,
        artist,
        album,
        duration_ms,
        position_ms,
        player_name,
        is_playing,
        volume,
        url,
        playback_rate: effective_rate,
    })
}

/// Score a player candidate (higher = better music tracking quality).
fn score_candidate(c: &PlayerCandidate) -> i32 {
    let mut score: i32 = 0;

    if !c.title.is_empty() {
        score += 40;
    }
    if !c.artist.is_empty() {
        score += 50;
    }
    if !c.album.is_empty() {
        score += 10;
    }
    if c.duration_ms > 0 {
        score += 15;
    }
    if c.position_ms >= 0 {
        score += 15;
    }
    if c.is_playing {
        score += 60;
    }
    if c.url.is_some() {
        score += 10;
    }

    // Prefer known music players over browsers
    let name_lower = c.player_name.to_lowercase();
    if is_known_linux_music_player(&name_lower) {
        score += 30;
    }

    // Bonus for music-site URLs
    if let Some(ref url) = c.url {
        score += url_music_site_bonus(url);
    }

    score
}

/// Select the best candidate from all players.
fn select_best_candidate(candidates: Vec<PlayerCandidate>) -> RawMediaInfo {
    let best = candidates
        .into_iter()
        .max_by_key(|c| score_candidate(c))
        .expect("select_best_candidate called with empty vec");

    RawMediaInfo {
        title: best.title,
        artist: best.artist,
        album: best.album,
        duration_ms: best.duration_ms,
        position_ms: best.position_ms,
        source_app: format!("{} (Linux)", best.player_name),
        is_playing: best.is_playing,
        volume: best.volume,
        url: best.url,
        playback_rate: best.playback_rate,
    }
}

fn is_known_linux_music_player(name_lower: &str) -> bool {
    const PLAYERS: &[&str] = &[
        "spotify",
        "rhythmbox",
        "clementine",
        "lollypop",
        "elisa",
        "strawberry",
        "audacious",
        "deadbeef",
        "cmus",
        "quodlibet",
        "amarok",
        "banshee",
        "mpd",
        "cantata",
        "gnome-music",
    ];
    PLAYERS.iter().any(|p| name_lower.contains(p))
}

fn url_music_site_bonus(url: &str) -> i32 {
    let lower = url.to_lowercase();
    if lower.contains("music.youtube.com") {
        return 120;
    }
    if lower.contains("open.spotify.com") {
        return 115;
    }
    if lower.contains("music.apple.com") {
        return 110;
    }
    if lower.contains("soundcloud.com") {
        return 105;
    }
    if lower.contains("tidal.com") {
        return 100;
    }
    if lower.contains("deezer.com") {
        return 95;
    }
    if lower.contains("bandcamp.com") {
        return 90;
    }
    0
}

/// D-Bus fallback: discover all MPRIS players on the session bus and query each.
async fn query_dbus_all_players() -> Option<RawMediaInfo> {
    // List all org.mpris.MediaPlayer2.* bus names
    let output = Command::new("dbus-send")
        .args([
            "--session",
            "--print-reply",
            "--dest=org.freedesktop.DBus",
            "/org/freedesktop/DBus",
            "org.freedesktop.DBus.ListNames",
        ])
        .output()
        .await
        .ok()?;

    let result = String::from_utf8_lossy(&output.stdout);
    if result.is_empty() || !output.status.success() {
        return None;
    }

    // Extract all MPRIS player bus names
    let mut best: Option<(i32, RawMediaInfo)> = None;
    for line in result.lines() {
        let trimmed = line.trim();
        // D-Bus ListNames output contains quoted strings
        if let Some(name) = extract_dbus_quoted_string(trimmed) {
            if name.starts_with("org.mpris.MediaPlayer2.") {
                if let Some(raw) = query_single_dbus_player(&name).await {
                    let score = score_dbus_candidate(&raw);
                    let dominated = best.as_ref().map(|(s, _)| score > *s).unwrap_or(true);
                    if dominated {
                        best = Some((score, raw));
                    }
                }
            }
        }
    }

    best.map(|(_, raw)| raw)
}

/// Query metadata from a single D-Bus MPRIS player.
async fn query_single_dbus_player(bus_name: &str) -> Option<RawMediaInfo> {
    let metadata_output = Command::new("dbus-send")
        .args([
            "--print-reply",
            &format!("--dest={}", bus_name),
            "/org/mpris/MediaPlayer2",
            "org.freedesktop.DBus.Properties.Get",
            "string:org.mpris.MediaPlayer2.Player",
            "string:Metadata",
        ])
        .output()
        .await
        .ok()?;

    let metadata_result = String::from_utf8_lossy(&metadata_output.stdout);
    if metadata_result.is_empty() || !metadata_output.status.success() {
        return None;
    }

    let title = extract_dbus_string(&metadata_result, "xesam:title")?;
    let artist = extract_dbus_array_first(&metadata_result, "xesam:artist");
    let album = extract_dbus_string(&metadata_result, "xesam:album");
    let url = extract_dbus_string(&metadata_result, "xesam:url");
    let length_us = extract_dbus_int64(&metadata_result, "mpris:length").unwrap_or(0);

    // Query playback status
    let status_output = Command::new("dbus-send")
        .args([
            "--print-reply",
            &format!("--dest={}", bus_name),
            "/org/mpris/MediaPlayer2",
            "org.freedesktop.DBus.Properties.Get",
            "string:org.mpris.MediaPlayer2.Player",
            "string:PlaybackStatus",
        ])
        .output()
        .await
        .ok();

    let is_playing = status_output
        .map(|o| String::from_utf8_lossy(&o.stdout).contains("\"Playing\""))
        .unwrap_or(false);

    // Query position
    let pos_output = Command::new("dbus-send")
        .args([
            "--print-reply",
            &format!("--dest={}", bus_name),
            "/org/mpris/MediaPlayer2",
            "org.freedesktop.DBus.Properties.Get",
            "string:org.mpris.MediaPlayer2.Player",
            "string:Position",
        ])
        .output()
        .await
        .ok();

    let position_ms = pos_output
        .and_then(|o| {
            let s = String::from_utf8_lossy(&o.stdout).to_string();
            extract_dbus_raw_int64(&s)
        })
        .map(|us| us / 1000)
        .unwrap_or(-1);

    // Query volume
    let vol_output = Command::new("dbus-send")
        .args([
            "--print-reply",
            &format!("--dest={}", bus_name),
            "/org/mpris/MediaPlayer2",
            "org.freedesktop.DBus.Properties.Get",
            "string:org.mpris.MediaPlayer2.Player",
            "string:Volume",
        ])
        .output()
        .await
        .ok();

    let volume = vol_output
        .and_then(|o| {
            let s = String::from_utf8_lossy(&o.stdout).to_string();
            extract_dbus_raw_double(&s)
        })
        .unwrap_or(-1.0);

    // Derive player name from bus name: org.mpris.MediaPlayer2.spotify → spotify
    let player_name = bus_name
        .strip_prefix("org.mpris.MediaPlayer2.")
        .unwrap_or("Unknown")
        .split('.')
        .next()
        .unwrap_or("Unknown");

    // Capitalize first letter
    let display_name = capitalize_first(player_name);

    Some(RawMediaInfo {
        title,
        artist: artist.unwrap_or_default(),
        album: album.unwrap_or_default(),
        duration_ms: length_us / 1000,
        position_ms,
        source_app: format!("{} (Linux)", display_name),
        is_playing,
        volume,
        url,
        playback_rate: if is_playing { 1.0 } else { 0.0 },
    })
}

fn score_dbus_candidate(raw: &RawMediaInfo) -> i32 {
    let mut score: i32 = 0;
    if !raw.title.is_empty() {
        score += 40;
    }
    if !raw.artist.is_empty() {
        score += 50;
    }
    if !raw.album.is_empty() {
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
    let source_lower = raw.source_app.to_lowercase();
    if is_known_linux_music_player(&source_lower) {
        score += 30;
    }
    if let Some(ref url) = raw.url {
        score += url_music_site_bonus(url);
    }
    score
}

fn extract_dbus_string(output: &str, key: &str) -> Option<String> {
    let search = format!("\"{}\"", key);
    if let Some(pos) = output.find(&search) {
        let after = &output[pos + search.len()..];
        if let Some(start) = after.find("string \"") {
            let value_start = start + 8;
            if let Some(end) = after[value_start..].find('"') {
                return Some(after[value_start..value_start + end].to_string());
            }
        }
    }
    None
}

fn extract_dbus_array_first(output: &str, key: &str) -> Option<String> {
    // For array types in D-Bus output, get the first string element
    extract_dbus_string(output, key)
}

fn extract_dbus_int64(output: &str, key: &str) -> Option<i64> {
    let search = format!("\"{}\"", key);
    if let Some(pos) = output.find(&search) {
        let after = &output[pos + search.len()..];
        // Look for "int64 <number>" or "uint64 <number>"
        for prefix in &["int64 ", "uint64 "] {
            if let Some(start) = after.find(prefix) {
                let value_start = start + prefix.len();
                let num_str: String = after[value_start..]
                    .chars()
                    .take_while(|c| c.is_ascii_digit() || *c == '-')
                    .collect();
                return num_str.parse::<i64>().ok();
            }
        }
    }
    None
}

/// Extract a raw int64 value from a D-Bus Properties.Get response (no key prefix).
fn extract_dbus_raw_int64(output: &str) -> Option<i64> {
    for prefix in &["int64 ", "uint64 "] {
        if let Some(start) = output.find(prefix) {
            let value_start = start + prefix.len();
            let num_str: String = output[value_start..]
                .chars()
                .take_while(|c| c.is_ascii_digit() || *c == '-')
                .collect();
            return num_str.parse::<i64>().ok();
        }
    }
    None
}

/// Extract a raw double value from a D-Bus Properties.Get response.
fn extract_dbus_raw_double(output: &str) -> Option<f64> {
    if let Some(start) = output.find("double ") {
        let value_start = start + 7;
        let num_str: String = output[value_start..]
            .chars()
            .take_while(|c| c.is_ascii_digit() || *c == '.' || *c == '-')
            .collect();
        return num_str.parse::<f64>().ok();
    }
    None
}

/// Extract a quoted string from a D-Bus ListNames output line.
fn extract_dbus_quoted_string(line: &str) -> Option<String> {
    let start = line.find('"')?;
    let rest = &line[start + 1..];
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}

fn capitalize_first(s: &str) -> String {
    let mut chars = s.chars();
    match chars.next() {
        None => String::new(),
        Some(c) => c.to_uppercase().to_string() + chars.as_str(),
    }
}

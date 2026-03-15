use crate::db::models::RawMediaInfo;
use log::{debug, warn};
use std::os::windows::process::CommandExt;
use std::process::Command;

/// CREATE_NO_WINDOW prevents a visible console window from appearing each time
/// PowerShell is spawned for media/volume queries during the polling cycle.
const CREATE_NO_WINDOW: u32 = 0x08000000;

/// Build a PowerShell `Command` that never creates a visible console window.
fn powershell_cmd() -> Command {
    let mut cmd = Command::new("powershell");
    cmd.creation_flags(CREATE_NO_WINDOW);
    cmd
}

/// Detect now playing on Windows using PowerShell and GlobalSystemMediaTransportControls.
///
/// Queries ALL active media sessions and picks the best one using candidate scoring.
/// Also retrieves system volume and infers browser URLs from window titles.
///
/// All PowerShell sub-calls are blocking (`std::process::Command::output`), so we offload
/// the entire detection to a dedicated blocking thread via `spawn_blocking` and enforce a
/// 6-second hard timeout to prevent a stalled PowerShell from freezing the async runtime.
pub async fn get_now_playing() -> Option<RawMediaInfo> {
    // Offload blocking PowerShell work off the async thread pool.
    let task = tokio::task::spawn_blocking(detect_now_playing_blocking);

    // 6-second ceiling: if PowerShell hangs (e.g., GSMTC unresponsive), abort gracefully.
    match tokio::time::timeout(std::time::Duration::from_secs(6), task).await {
        Ok(Ok(result)) => result,
        Ok(Err(_)) => {
            warn!("Windows media detection task panicked");
            None
        }
        Err(_) => {
            warn!("Windows media detection timed out (>6s) – skipping this poll cycle");
            None
        }
    }
}

/// Synchronous inner body for `get_now_playing`, run on a blocking thread.
fn detect_now_playing_blocking() -> Option<RawMediaInfo> {
    // Primary: query all GSMTC sessions, score and pick the best
    let sessions = query_all_media_sessions();
    if !sessions.is_empty() {
        let volume = query_system_volume().unwrap_or(-1.0);
        return Some(select_best_session(sessions, volume));
    }
    // Fallback 1: Try Spotify window title parsing
    if let Some(np) = query_spotify_window_title() {
        return Some(np);
    }
    // Fallback 2: Try detecting music from browser window titles.
    // This catches cases where GSMTC doesn't return browser sessions
    // (e.g., TryGetMediaPropertiesAsync throws for sandboxed browser processes).
    query_browser_music_window()
}

/// A parsed GSMTC session before scoring.
struct MediaSessionCandidate {
    title: String,
    artist: String,
    album: String,
    duration_ms: i64,
    position_ms: i64,
    source_id: String,
    is_playing: bool,
    playback_rate: f64,
    /// Browser window title (only set for browser sessions).
    /// Used to infer music service URL when the GSMTC media title is clean
    /// (e.g., "Shape of You" vs "Shape of You | Spotify").
    window_title: Option<String>,
}

fn query_all_media_sessions() -> Vec<MediaSessionCandidate> {
    // PowerShell script that iterates ALL sessions (not just the single "current" one),
    // returns JSON array so we can score each candidate in Rust.
    //
    // For browser sessions, also queries the browser's main window title which often
    // contains the music service name (e.g., "Song | Spotify") — this is needed because
    // GSMTC returns the Media Session API title (clean song name) for modern music
    // services that implement navigator.mediaSession.metadata.
    let ps_script = r#"
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asyncOp = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]::RequestAsync()
$typeName = 'System.WindowsRuntimeSystemExtensions'
$null = [System.Reflection.Assembly]::LoadWithPartialName('System.Runtime.WindowsRuntime')
$getAwaiterMethod = $typeName::GetAwaiter
$awaiter = $getAwaiterMethod.Invoke($null, @($asyncOp))
$manager = $awaiter.GetResult()
$sessions = $manager.GetSessions()
$browserNames = @{'chrome'='chrome';'chromium'='chromium';'msedge'='msedge';'edge'='msedge';'firefox'='firefox';'brave'='brave';'opera'='opera';'vivaldi'='vivaldi'}
$results = @()
foreach ($session in $sessions) {
    try {
        $mediaProps = $session.TryGetMediaPropertiesAsync()
        $awaiter2 = $getAwaiterMethod.Invoke($null, @($mediaProps))
        $props = $awaiter2.GetResult()
        $info = $session.GetPlaybackInfo()
        $timeline = $session.GetTimelineProperties()
        $playing = $info.PlaybackStatus -eq 'Playing'
        $sourceId = $session.SourceAppUserModelId
        $entry = @{
            title = $props.Title
            artist = $props.Artist
            album = $props.AlbumTitle
            duration = [int64]$timeline.EndTime.TotalMilliseconds
            position = [int64]$timeline.Position.TotalMilliseconds
            source = $sourceId
            playing = $playing
            rate = if ($info.PlaybackRate) { $info.PlaybackRate } else { -1 }
        }
        $srcLower = $sourceId.ToLower()
        foreach ($bk in $browserNames.Keys) {
            if ($srcLower -match $bk) {
                $pn = $browserNames[$bk]
                try {
                    $wt = Get-Process -Name $pn -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -ne '' } | Select-Object -First 1 -ExpandProperty MainWindowTitle
                    if ($wt) { $entry.windowTitle = $wt }
                } catch {}
                break
            }
        }
        $results += $entry
    } catch { continue }
}
if ($results.Count -gt 0) {
    $results | ConvertTo-Json -Compress
} else {
    Write-Output 'NO_SESSION'
}
"#;

    let output = match powershell_cmd()
        .args(["-NoProfile", "-NonInteractive", "-Command", ps_script])
        .output()
    {
        Ok(o) => o,
        Err(e) => {
            debug!("Failed to run PowerShell for media sessions: {}", e);
            return Vec::new();
        }
    };

    let result = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if result.is_empty() || result == "NO_SESSION" {
        return Vec::new();
    }

    debug!("Windows media sessions result: {}", result);

    // PowerShell returns a single object (not array) when there's exactly one session
    let entries: Vec<serde_json::Value> = if result.starts_with('[') {
        serde_json::from_str(&result).unwrap_or_default()
    } else {
        serde_json::from_str::<serde_json::Value>(&result)
            .ok()
            .map(|v| vec![v])
            .unwrap_or_default()
    };

    entries
        .into_iter()
        .filter_map(|parsed| {
            let title = parsed
                .get("title")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            if title.is_empty() {
                return None;
            }
            Some(MediaSessionCandidate {
                title,
                artist: parsed
                    .get("artist")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                album: parsed
                    .get("album")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                duration_ms: parsed.get("duration").and_then(|p| p.as_i64()).unwrap_or(0),
                position_ms: parsed
                    .get("position")
                    .and_then(|p| p.as_i64())
                    .unwrap_or(-1),
                source_id: parsed
                    .get("source")
                    .and_then(|s| s.as_str())
                    .unwrap_or("Unknown")
                    .to_string(),
                is_playing: parsed
                    .get("playing")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false),
                playback_rate: parsed.get("rate").and_then(|r| r.as_f64()).unwrap_or(-1.0),
                window_title: parsed
                    .get("windowTitle")
                    .and_then(|v| v.as_str())
                    .filter(|s| !s.is_empty())
                    .map(|s| s.to_string()),
            })
        })
        .collect()
}

/// Score a candidate session for music quality (higher = better).
/// Mirrors macOS `candidate_quality_score` logic.
fn score_session(c: &MediaSessionCandidate) -> i32 {
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

    // Prefer known music players over browsers
    let source_lower = c.source_id.to_lowercase();
    if is_known_music_player(&source_lower) {
        score += 30;
    } else if !is_browser_source_id(&source_lower) {
        score += 10; // unknown desktop app — mild preference
    }

    // Bonus for music-site title patterns (browser sessions).
    // Check both the GSMTC media title AND the browser window title,
    // because modern music services set Media Session metadata with clean
    // titles (no service suffix), but the window title retains the suffix.
    if is_browser_source_id(&source_lower) {
        let bonus_from_media = title_music_site_bonus(&c.title);
        let bonus_from_window = c
            .window_title
            .as_deref()
            .and_then(title_music_site_bonus);
        // Take the higher bonus from either source
        let best_bonus = bonus_from_media
            .into_iter()
            .chain(bonus_from_window)
            .max();
        if let Some(bonus) = best_bonus {
            score += bonus;
        }
    }

    score
}

/// Select the best session from all candidates using scoring.
fn select_best_session(sessions: Vec<MediaSessionCandidate>, volume: f64) -> RawMediaInfo {
    let best = sessions
        .into_iter()
        .max_by_key(|c| score_session(c))
        .expect("select_best_session called with empty vec");

    let source_lower = best.source_id.to_lowercase();
    let is_browser = is_browser_source_id(&source_lower);
    let (source_app, url) = if is_browser {
        let browser_name = friendly_browser_name(&source_lower);
        // Try inferring URL from the GSMTC media title first, then fall back
        // to the browser window title. Modern music services set Media Session
        // metadata with clean titles (e.g., "Shape of You"), but the browser
        // window title retains the service suffix (e.g., "Shape of You | Spotify").
        let inferred_url = infer_url_from_title(&best.title).or_else(|| {
            best.window_title
                .as_deref()
                .and_then(infer_url_from_title)
        });
        let source = if let Some(ref url) = inferred_url {
            classify_browser_source(url, browser_name)
        } else {
            format!("{} (Windows)", browser_name)
        };
        (source, inferred_url)
    } else {
        (
            format!("{} (Windows)", friendly_source_name(&best.source_id)),
            None,
        )
    };

    RawMediaInfo {
        title: best.title,
        artist: best.artist,
        album: best.album,
        duration_ms: best.duration_ms,
        position_ms: best.position_ms,
        source_app,
        is_playing: best.is_playing,
        volume,
        url,
        playback_rate: best.playback_rate,
    }
}

/// Query system master volume on Windows via PowerShell.
fn query_system_volume() -> Option<f64> {
    let ps = r#"
Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
[Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioEndpointVolume {
    int _0(); int _1(); int _2(); int _3(); int _4(); int _5(); int _6(); int _7(); int _8(); int _9(); int _10(); int _11();
    int GetMasterVolumeLevelScalar(out float pfLevel);
    int _13();
    int GetMute(out bool pbMute);
}
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppDevice);
}
[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
    int Activate([MarshalAs(UnmanagedType.LPStruct)] Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
}
public static class Vol {
    public static string Get() {
        var type1 = Type.GetTypeFromCLSID(new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E"));
        var enumerator = (IMMDeviceEnumerator)System.Activator.CreateInstance(type1);
        IMMDevice dev;
        enumerator.GetDefaultAudioEndpoint(0, 1, out dev);
        object o;
        dev.Activate(new Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), 1, System.IntPtr.Zero, out o);
        var vol = (IAudioEndpointVolume)o;
        float level; vol.GetMasterVolumeLevelScalar(out level);
        bool muted; vol.GetMute(out muted);
        return (muted ? 0 : level).ToString("F4");
    }
}
"@ -ErrorAction SilentlyContinue
try { [Vol]::Get() } catch { Write-Output "-1" }
"#;
    let output = powershell_cmd()
        .args(["-NoProfile", "-NonInteractive", "-Command", ps])
        .output()
        .ok()?;
    let val = String::from_utf8_lossy(&output.stdout).trim().to_string();
    val.parse::<f64>().ok()
}

fn query_spotify_window_title() -> Option<RawMediaInfo> {
    let output = powershell_cmd()
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "Get-Process spotify -ErrorAction SilentlyContinue | Where-Object {$_.MainWindowTitle -ne ''} | Select-Object -ExpandProperty MainWindowTitle",
        ])
        .output()
        .ok()?;

    let title_str = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if title_str.is_empty() || title_str == "Spotify" || title_str == "Spotify Premium" {
        return None;
    }

    // Spotify window title format: "Artist - Song Title"
    let parts: Vec<&str> = title_str.splitn(2, " - ").collect();
    if parts.len() == 2 {
        Some(RawMediaInfo {
            title: parts[1].trim().to_string(),
            artist: parts[0].trim().to_string(),
            album: String::new(),
            duration_ms: 0,
            position_ms: -1,
            source_app: "Spotify (Windows)".to_string(),
            is_playing: true,
            volume: query_system_volume().unwrap_or(-1.0),
            url: None,
            playback_rate: 1.0,
        })
    } else {
        None
    }
}

/// Map GSMTC SourceAppUserModelId to a friendly name for native music apps.
fn friendly_source_name(app_id: &str) -> &str {
    let lower = app_id.to_lowercase();
    if lower.contains("spotify") {
        "Spotify"
    } else if lower.contains("musicbee") {
        "MusicBee"
    } else if lower.contains("foobar") {
        "foobar2000"
    } else if lower.contains("itunes") || lower.contains("apple.music") {
        "iTunes"
    } else if lower.contains("vlc") {
        "VLC"
    } else if lower.contains("winamp") {
        "Winamp"
    } else if lower.contains("groove") || lower.contains("zune") {
        "Groove Music"
    } else if lower.contains("mediaplayer") || lower.contains("wmplayer") {
        "Windows Media Player"
    } else if lower.contains("tidal") {
        "Tidal"
    } else if lower.contains("deezer") {
        "Deezer"
    } else if lower.contains("amazon") && lower.contains("music") {
        "Amazon Music"
    } else if lower.contains("plex") {
        "Plex"
    } else {
        "Desktop Player"
    }
}

/// Map GSMTC source ID to a browser display name.
fn friendly_browser_name(source_lower: &str) -> &str {
    if source_lower.contains("firefox") {
        "Firefox"
    } else if source_lower.contains("msedge") || source_lower.contains("edge") {
        "Edge"
    } else if source_lower.contains("brave") {
        "Brave"
    } else if source_lower.contains("opera") {
        "Opera"
    } else if source_lower.contains("vivaldi") {
        "Vivaldi"
    } else if source_lower.contains("chrome") || source_lower.contains("chromium") {
        "Chrome"
    } else {
        "Browser"
    }
}

/// Check if the source ID belongs to a browser.
fn is_browser_source_id(source_lower: &str) -> bool {
    const BROWSER_IDS: &[&str] = &[
        "chrome", "chromium", "brave", "msedge", "edge", "firefox", "opera", "vivaldi",
    ];
    BROWSER_IDS.iter().any(|id| source_lower.contains(id))
}

/// Check if the source ID belongs to a known native music player.
fn is_known_music_player(source_lower: &str) -> bool {
    const PLAYER_IDS: &[&str] = &[
        "spotify",
        "musicbee",
        "foobar",
        "itunes",
        "apple.music",
        "vlc",
        "winamp",
        "groove",
        "zune",
        "mediaplayer",
        "wmplayer",
        "tidal",
        "deezer",
        "amazon",
        "plex",
    ];
    PLAYER_IDS.iter().any(|id| source_lower.contains(id))
}

/// Infer a URL from browser media session titles.
///
/// GSMTC doesn't provide URLs, but browser media session titles often contain
/// suffixes like "- YouTube Music", "- YouTube", "- Spotify" etc. We can use
/// these to construct synthetic URLs for site detection.
fn infer_url_from_title(title: &str) -> Option<String> {
    let lower = title.to_lowercase();
    let trimmed = lower.trim();

    // YouTube Music titles end with "- YouTube Music" or "| YouTube Music"
    if trimmed.ends_with("- youtube music") || trimmed.ends_with("| youtube music") {
        return Some("https://music.youtube.com/watch".to_string());
    }
    // YouTube titles end with "- YouTube" or "| YouTube"
    if trimmed.ends_with("- youtube") || trimmed.ends_with("| youtube") {
        return Some("https://www.youtube.com/watch".to_string());
    }
    // Spotify Web player
    if trimmed.ends_with("- spotify") || trimmed.ends_with("| spotify") {
        return Some("https://open.spotify.com/track".to_string());
    }
    // SoundCloud
    if trimmed.ends_with("- soundcloud") || trimmed.ends_with("| soundcloud") {
        return Some("https://soundcloud.com/".to_string());
    }
    // Apple Music Web
    if trimmed.ends_with("- apple music") || trimmed.ends_with("| apple music") {
        return Some("https://music.apple.com/".to_string());
    }
    // Tidal
    if trimmed.ends_with("- tidal") || trimmed.ends_with("| tidal") {
        return Some("https://listen.tidal.com/".to_string());
    }
    // Deezer
    if trimmed.ends_with("- deezer") || trimmed.ends_with("| deezer") {
        return Some("https://www.deezer.com/".to_string());
    }
    // Bandcamp — titles are "Track, by Artist | site"
    if trimmed.contains("bandcamp") {
        return Some("https://bandcamp.com/".to_string());
    }

    None
}

/// Classify browser source with a music-site-aware label.
fn classify_browser_source(url: &str, browser: &str) -> String {
    let lower = url.to_lowercase();
    if lower.contains("music.youtube.com") {
        return format!("YouTube Music ({})", browser);
    }
    if lower.contains("open.spotify.com") {
        return format!("Spotify Web ({})", browser);
    }
    if lower.contains("music.apple.com") {
        return format!("Apple Music Web ({})", browser);
    }
    if lower.contains("soundcloud.com") {
        return format!("SoundCloud ({})", browser);
    }
    if lower.contains("tidal.com") {
        return format!("Tidal Web ({})", browser);
    }
    if lower.contains("deezer.com") {
        return format!("Deezer ({})", browser);
    }
    if lower.contains("bandcamp.com") {
        return format!("Bandcamp ({})", browser);
    }
    format!("{} (Windows)", browser)
}

/// Score bonus for browser sessions whose title hints at a music site.
fn title_music_site_bonus(title: &str) -> Option<i32> {
    let lower = title.to_lowercase();
    let trimmed = lower.trim();

    if trimmed.ends_with("- youtube music") || trimmed.ends_with("| youtube music") {
        return Some(120);
    }
    if trimmed.ends_with("- spotify") || trimmed.ends_with("| spotify") {
        return Some(115);
    }
    if trimmed.ends_with("- apple music") || trimmed.ends_with("| apple music") {
        return Some(110);
    }
    if trimmed.ends_with("- soundcloud") || trimmed.ends_with("| soundcloud") {
        return Some(105);
    }
    if trimmed.ends_with("- tidal") || trimmed.ends_with("| tidal") {
        return Some(100);
    }
    if trimmed.ends_with("- deezer") || trimmed.ends_with("| deezer") {
        return Some(95);
    }
    if trimmed.contains("bandcamp") {
        return Some(90);
    }
    // Plain YouTube — lower priority, needs further filtering
    if trimmed.ends_with("- youtube") || trimmed.ends_with("| youtube") {
        return Some(30);
    }

    None
}

// ---------------------------------------------------------------------------
//  Fallback: detect music from browser window titles when GSMTC misses them
// ---------------------------------------------------------------------------

/// Known music service patterns in browser window titles.
/// Each entry: (suffix_pattern, synthetic_url, service_name).
const BROWSER_TITLE_SERVICES: &[(&str, &str, &str)] = &[
    ("| spotify", "https://open.spotify.com/track", "Spotify Web"),
    ("- spotify", "https://open.spotify.com/track", "Spotify Web"),
    ("- youtube music", "https://music.youtube.com/watch", "YouTube Music"),
    ("| youtube music", "https://music.youtube.com/watch", "YouTube Music"),
    ("- apple music", "https://music.apple.com/", "Apple Music Web"),
    ("| apple music", "https://music.apple.com/", "Apple Music Web"),
    ("- soundcloud", "https://soundcloud.com/", "SoundCloud"),
    ("| soundcloud", "https://soundcloud.com/", "SoundCloud"),
    ("- tidal", "https://listen.tidal.com/", "Tidal Web"),
    ("| tidal", "https://listen.tidal.com/", "Tidal Web"),
    ("- deezer", "https://www.deezer.com/", "Deezer"),
    ("| deezer", "https://www.deezer.com/", "Deezer"),
];

/// Try to detect music playback by scanning browser window titles directly.
///
/// This is a last-resort fallback for when GSMTC doesn't return browser media
/// sessions (e.g., TryGetMediaPropertiesAsync throws due to browser sandboxing,
/// or the browser's media key handling flag is disabled).
///
/// Parses window titles like "Song - Artist | Spotify" into metadata.
fn query_browser_music_window() -> Option<RawMediaInfo> {
    let ps_script = r#"
$browsers = @('chrome','msedge','firefox','brave','opera','vivaldi')
$out = ''
foreach ($b in $browsers) {
    try {
        $procs = Get-Process -Name $b -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -ne '' }
        foreach ($p in $procs) {
            $wt = $p.MainWindowTitle
            if ($wt -and $wt -ne '') {
                $out = "$b::$wt"
                break
            }
        }
        if ($out -ne '') { break }
    } catch { continue }
}
if ($out -ne '') { Write-Output $out } else { Write-Output 'NONE' }
"#;

    let output = powershell_cmd()
        .args(["-NoProfile", "-NonInteractive", "-Command", ps_script])
        .output()
        .ok()?;

    let result = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if result.is_empty() || result == "NONE" || !result.contains("::") {
        return None;
    }

    let (browser_id, window_title) = result.split_once("::").unwrap();
    if window_title.is_empty() {
        return None;
    }

    let lower_title = window_title.to_lowercase();

    // Match the window title against known music service patterns
    for &(suffix, url, service_name) in BROWSER_TITLE_SERVICES {
        if lower_title.ends_with(suffix) {
            let content_before_suffix = &window_title[..window_title.len() - suffix.len()];
            let content = content_before_suffix.trim().trim_end_matches(&['-', '|'][..]).trim();

            if content.is_empty() {
                continue;
            }

            // Try to split "Artist - Title" or "Title - Artist"
            let (title, artist) = if let Some((a, b)) = split_dash(content) {
                // For Spotify: title is "Song - lyrics and song by Artist"
                // or "Song · Artist"
                // For YouTube Music: "Song - Artist"
                // We can't know the order, so use as-is
                (a.to_string(), b.to_string())
            } else {
                (content.to_string(), String::new())
            };

            let browser_name = friendly_browser_name(&browser_id.to_lowercase());

            debug!(
                "Browser fallback: detected '{}' by '{}' from {} via {} window title",
                title, artist, service_name, browser_name
            );

            return Some(RawMediaInfo {
                title,
                artist,
                album: String::new(),
                duration_ms: 0,
                position_ms: -1,
                source_app: format!("{} ({})", service_name, browser_name),
                is_playing: true, // if the tab is open with music title, assume playing
                volume: query_system_volume().unwrap_or(-1.0),
                url: Some(url.to_string()),
                playback_rate: 1.0,
            });
        }
    }

    None
}

/// Split a string on the first " - ", " — ", " – " or " · " separator.
fn split_dash(s: &str) -> Option<(&str, &str)> {
    for sep in &[" - ", " — ", " – ", " · "] {
        if let Some(pos) = s.find(sep) {
            let left = s[..pos].trim();
            let right = s[pos + sep.len()..].trim();
            if !left.is_empty() && !right.is_empty() {
                return Some((left, right));
            }
        }
    }
    None
}

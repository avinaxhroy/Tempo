use crate::db::models::RawMediaInfo;
use log::{debug, warn};
use std::os::windows::process::CommandExt;
use std::process::Command;

// Native WinRT GSMTC (GlobalSystemMediaTransportControls) imports.
// Using native APIs is ~100x faster than PowerShell (~5ms vs ~500-2000ms).
use windows::Media::Control::{
    GlobalSystemMediaTransportControlsSessionManager,
    GlobalSystemMediaTransportControlsSessionPlaybackStatus,
};

/// CREATE_NO_WINDOW prevents a visible console window from appearing each time
/// PowerShell is spawned for media/volume queries during the polling cycle.
const CREATE_NO_WINDOW: u32 = 0x08000000;

/// Build a PowerShell `Command` that never creates a visible console window.
fn powershell_cmd() -> Command {
    let mut cmd = Command::new("powershell");
    cmd.creation_flags(CREATE_NO_WINDOW);
    cmd
}

/// Detect now playing on Windows using native WinRT GSMTC API with PowerShell fallback.
///
/// Pipeline:
/// 1. Native WinRT GSMTC (primary — ~5ms, no PowerShell overhead)
/// 2. PowerShell GSMTC (fallback — if WinRT fails on older Windows)
/// 3. Spotify window title (fallback)
/// 4. Browser window title scan (last resort)
///
/// All blocking work is offloaded to `spawn_blocking` and bounded by a
/// 6-second hard timeout to prevent stalls from freezing the async runtime.
pub async fn get_now_playing() -> Option<RawMediaInfo> {
    // Offload blocking detection work off the async thread pool.
    let task = tokio::task::spawn_blocking(detect_now_playing_blocking);

    // 6-second ceiling: if detection hangs (e.g., WinRT or PowerShell unresponsive), abort.
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
    // Primary: native WinRT GSMTC — fast (~5ms) and reliable
    match native_detect_sessions() {
        Ok(sessions) if !sessions.is_empty() => {
            let volume = query_system_volume().unwrap_or(-1.0);
            // Only fetch browser window titles if we actually have a browser session
            let needs_browser_titles = sessions
                .iter()
                .any(|s| is_browser_source_id(&s.source_id.to_lowercase()));
            let browser_titles = if needs_browser_titles {
                get_all_browser_window_titles()
            } else {
                Vec::new()
            };
            return Some(select_best_session(sessions, volume, &browser_titles));
        }
        Ok(_) => {
            debug!("Native GSMTC: no active sessions");
        }
        Err(e) => {
            warn!("Native GSMTC failed: {} — trying PowerShell fallback", e);
            // PowerShell GSMTC fallback
            let sessions = ps_query_all_media_sessions();
            if !sessions.is_empty() {
                let volume = query_system_volume().unwrap_or(-1.0);
                let browser_titles = get_all_browser_window_titles();
                return Some(select_best_session(sessions, volume, &browser_titles));
            }
        }
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

/// A browser window title from the system-wide scan.
struct BrowserWindowTitle {
    /// Browser process name (e.g., "chrome", "msedge").
    browser: String,
    /// Full window title (e.g., "Shape of You | Spotify - Google Chrome").
    title: String,
}

/// Detect media sessions using the native WinRT GSMTC API.
///
/// This replaces the previous PowerShell-based approach and is dramatically
/// faster (~5ms vs ~500-2000ms) and more reliable since it doesn't depend
/// on PowerShell script execution or .NET assembly loading.
fn native_detect_sessions() -> windows::core::Result<Vec<MediaSessionCandidate>> {
    let manager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync()?.get()?;
    let sessions = manager.GetSessions()?;
    let count = sessions.Size()?;

    if count == 0 {
        return Ok(Vec::new());
    }

    let mut candidates = Vec::new();

    for i in 0..count {
        let session = match sessions.GetAt(i) {
            Ok(s) => s,
            Err(e) => {
                debug!("Failed to get GSMTC session at index {}: {}", i, e);
                continue;
            }
        };

        let source_id = session
            .SourceAppUserModelId()
            .map(|s| s.to_string())
            .unwrap_or_else(|_| "Unknown".to_string());

        // Playback info (status, rate)
        let (is_playing, playback_rate) = match session.GetPlaybackInfo() {
            Ok(info) => {
                let playing = info
                    .PlaybackStatus()
                    .map(|s| s == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing)
                    .unwrap_or(false);
                let rate = info.PlaybackRate().and_then(|r| r.Value()).unwrap_or(-1.0);
                (playing, rate)
            }
            Err(_) => (false, -1.0),
        };

        // Timeline (duration, position) — TimeSpan.Duration is in 100ns units
        let (duration_ms, position_ms) = match session.GetTimelineProperties() {
            Ok(tl) => {
                let dur = tl.EndTime().map(|t| t.Duration / 10_000).unwrap_or(0);
                let pos = tl.Position().map(|t| t.Duration / 10_000).unwrap_or(-1);
                (dur, pos)
            }
            Err(_) => (0i64, -1i64),
        };

        // Media properties — CAN fail for sandboxed browser processes.
        // Retry once after a short sleep if the first attempt fails for a
        // playing session, because COM apartment initialization for WinRT
        // media queries can transiently fail on the first call.
        let (title, artist, album) = {
            let try_get_props = || -> (String, String, String) {
                match session.TryGetMediaPropertiesAsync() {
                    Ok(async_op) => match async_op.get() {
                        Ok(props) => {
                            let t = props.Title().map(|s| s.to_string()).unwrap_or_default();
                            let a = props.Artist().map(|s| s.to_string()).unwrap_or_default();
                            let al = props
                                .AlbumTitle()
                                .map(|s| s.to_string())
                                .unwrap_or_default();
                            (t, a, al)
                        }
                        Err(_) => (String::new(), String::new(), String::new()),
                    },
                    Err(_) => (String::new(), String::new(), String::new()),
                }
            };
            let result = try_get_props();
            if result.0.is_empty() && is_playing {
                // Retry once after 50ms for playing sessions
                std::thread::sleep(std::time::Duration::from_millis(50));
                let retry = try_get_props();
                if !retry.0.is_empty() {
                    debug!("TryGetMediaPropertiesAsync succeeded on retry for '{}'", source_id);
                }
                retry
            } else {
                result
            }
        };

        // Skip sessions with no title that aren't playing — nothing useful.
        // Playing browser sessions with failed props can still be enriched later.
        if title.is_empty() && !is_playing {
            continue;
        }

        candidates.push(MediaSessionCandidate {
            title,
            artist,
            album,
            duration_ms,
            position_ms,
            source_id,
            is_playing,
            playback_rate,
            window_title: None,
        });
    }

    debug!("Native GSMTC: found {} candidate(s)", candidates.len());
    Ok(candidates)
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

fn ps_query_all_media_sessions() -> Vec<MediaSessionCandidate> {
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

/// Get ALL window titles from ALL browser processes.
///
/// Unlike the previous approach which grabbed only `Select-Object -First 1`
/// per browser (potentially missing the music tab), this scans every browser
/// window. Returns a flat list of all browser windows for scoring in Rust.
fn get_all_browser_window_titles() -> Vec<BrowserWindowTitle> {
    let ps_script = r#"
$browsers = @('chrome','msedge','firefox','brave','opera','vivaldi')
$results = @()
foreach ($b in $browsers) {
    try {
        Get-Process -Name $b -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -ne '' } | ForEach-Object {
            $results += @{b=$b;t=$_.MainWindowTitle}
        }
    } catch { continue }
}
if ($results.Count -gt 0) { $results | ConvertTo-Json -Compress } else { Write-Output 'NONE' }
"#;

    let output = match powershell_cmd()
        .args(["-NoProfile", "-NonInteractive", "-Command", ps_script])
        .output()
    {
        Ok(o) => o,
        Err(e) => {
            debug!("Failed to scan browser window titles: {}", e);
            return Vec::new();
        }
    };

    let result = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if result.is_empty() || result == "NONE" {
        return Vec::new();
    }

    // PowerShell returns single object (not array) for exactly one result
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
        .filter_map(|v| {
            let browser = v.get("b")?.as_str()?.to_string();
            let title = v.get("t")?.as_str()?.to_string();
            if title.is_empty() {
                None
            } else {
                Some(BrowserWindowTitle { browser, title })
            }
        })
        .collect()
}

/// Map a GSMTC SourceAppUserModelId to a browser process name for matching
/// against `BrowserWindowTitle` entries.
fn browser_process_name(source_id_lower: &str) -> &str {
    if source_id_lower.contains("chrome") || source_id_lower.contains("chromium") {
        "chrome"
    } else if source_id_lower.contains("msedge") || source_id_lower.contains("edge") {
        "msedge"
    } else if source_id_lower.contains("firefox") {
        "firefox"
    } else if source_id_lower.contains("brave") {
        "brave"
    } else if source_id_lower.contains("opera") {
        "opera"
    } else if source_id_lower.contains("vivaldi") {
        "vivaldi"
    } else {
        ""
    }
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
///
/// For browser sessions, enriches candidates with metadata from multiple sources
/// in order of reliability:
///   1. GSMTC Media Session API (already populated — most reliable)
///   2. GSMTC title embedded artist ("Artist - Song" in the title field)
///   3. Service-specific window title parsing (knows exact format per service)
///   4. Generic window title artist extraction (least reliable — last resort)
fn select_best_session(
    mut sessions: Vec<MediaSessionCandidate>,
    volume: f64,
    browser_titles: &[BrowserWindowTitle],
) -> RawMediaInfo {
    // Enrich browser sessions with window titles and artist recovery
    for session in &mut sessions {
        let source_lower = session.source_id.to_lowercase();
        if !is_browser_source_id(&source_lower) {
            continue;
        }

        let proc_name = browser_process_name(&source_lower);
        // Find the best matching window title from this browser — prefer
        // titles that match a known music service pattern
        let best_title = browser_titles
            .iter()
            .filter(|bt| bt.browser.eq_ignore_ascii_case(proc_name))
            .max_by_key(|bt| title_music_site_bonus(&bt.title).unwrap_or(0));

        if let Some(bt) = best_title {
            session.window_title = Some(bt.title.clone());
        }

        // --- Artist recovery pipeline (ordered by reliability) ---

        // If GSMTC has no title at all (TryGetMediaPropertiesAsync failed
        // for a sandboxed browser), recover both title and artist from
        // the window title first.
        if session.title.is_empty() {
            if let Some(wt) = session.window_title.as_deref() {
                if let Some((title, artist)) = extract_metadata_from_window_title(wt) {
                    debug!(
                        "Recovered metadata from window title: '{}' by '{}'",
                        title, artist
                    );
                    session.title = title;
                    if session.artist.is_empty() {
                        session.artist = artist;
                    }
                }
            }
        }

        // Strategy 1: GSMTC title may embed artist ("Artist - Song" or "Song · Artist")
        // This is more reliable than window title because it comes from the Media
        // Session API data that the website explicitly set.
        if session.artist.is_empty() && !session.title.is_empty() {
            if let Some((artist, cleaned_title)) = try_split_artist_from_gsmtc_title(&session.title) {
                debug!(
                    "Extracted artist '{}' from GSMTC title '{}' → clean title '{}'",
                    artist, session.title, cleaned_title
                );
                session.artist = artist;
                session.title = cleaned_title;
            }
        }

        // Strategy 2: Service-specific window title parsing (when we know the service)
        if session.artist.is_empty() && !session.title.is_empty() {
            if let Some(wt) = session.window_title.as_deref() {
                let service = detect_service_from_window_title(wt);
                if let Some(artist) = service_specific_artist_extraction(
                    service, &session.title, wt,
                ) {
                    debug!(
                        "Service-specific ({:?}) artist extraction: '{}' for '{}'",
                        service, artist, session.title
                    );
                    session.artist = artist;
                }
            }
        }

        // Strategy 3 (last resort): Generic window title cross-reference.
        // Only used when all above strategies failed.
        if session.artist.is_empty() && !session.title.is_empty() {
            if let Some(wt) = session.window_title.as_deref() {
                if let Some(artist) =
                    extract_artist_from_window_title(&session.title, wt)
                {
                    debug!(
                        "Generic window title artist extraction: '{}' for '{}'",
                        artist, session.title
                    );
                    session.artist = artist;
                }
            }
        }
    }

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
    if trimmed.ends_with("- spotify") || trimmed.ends_with("| spotify")
        || trimmed.ends_with("- spotify - premium")
    {
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
    // Amazon Music
    if trimmed.ends_with("- amazon music") || trimmed.ends_with("| amazon music") {
        return Some("https://music.amazon.com/".to_string());
    }
    // Pandora
    if trimmed.ends_with("- pandora") || trimmed.ends_with("| pandora") {
        return Some("https://www.pandora.com/".to_string());
    }
    // JioSaavn
    if trimmed.ends_with("- jiosaavn") || trimmed.ends_with("| jiosaavn") {
        return Some("https://www.jiosaavn.com/".to_string());
    }
    // Gaana
    if trimmed.ends_with("- gaana") || trimmed.ends_with("| gaana") {
        return Some("https://gaana.com/".to_string());
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
    if lower.contains("music.amazon") {
        return format!("Amazon Music ({})", browser);
    }
    if lower.contains("pandora.com") {
        return format!("Pandora ({})", browser);
    }
    if lower.contains("jiosaavn.com") {
        return format!("JioSaavn ({})", browser);
    }
    if lower.contains("gaana.com") {
        return format!("Gaana ({})", browser);
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
    // Amazon Music
    if trimmed.ends_with("- amazon music") || trimmed.ends_with("| amazon music") {
        return Some(85);
    }
    // Pandora
    if trimmed.ends_with("- pandora") || trimmed.ends_with("| pandora") {
        return Some(80);
    }
    // JioSaavn
    if trimmed.ends_with("- jiosaavn") || trimmed.ends_with("| jiosaavn") {
        return Some(75);
    }
    // Gaana
    if trimmed.ends_with("- gaana") || trimmed.ends_with("| gaana") {
        return Some(75);
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
    ("- amazon music", "https://music.amazon.com/", "Amazon Music"),
    ("| amazon music", "https://music.amazon.com/", "Amazon Music"),
    ("- pandora", "https://www.pandora.com/", "Pandora"),
    ("| pandora", "https://www.pandora.com/", "Pandora"),
    ("- jiosaavn", "https://www.jiosaavn.com/", "JioSaavn"),
    ("| jiosaavn", "https://www.jiosaavn.com/", "JioSaavn"),
    ("- gaana", "https://gaana.com/", "Gaana"),
    ("| gaana", "https://gaana.com/", "Gaana"),
];

/// Try to detect music playback by scanning browser window titles directly.
///
/// This is a last-resort fallback for when GSMTC doesn't return browser media
/// sessions (e.g., TryGetMediaPropertiesAsync throws due to browser sandboxing,
/// or the browser's media key handling flag is disabled).
///
/// Unlike the previous version which only checked the FIRST window title from
/// the FIRST browser, this scans ALL windows from ALL browsers, scores each
/// against known music service patterns, and picks the best match.
fn query_browser_music_window() -> Option<RawMediaInfo> {
    let titles = get_all_browser_window_titles();
    if titles.is_empty() {
        return None;
    }

    // Score each browser window title against known music service patterns
    // and pick the best match
    let mut best_match: Option<(i32, &BrowserWindowTitle, &str, &str, &str)> = None;

    for bt in &titles {
        let lower_title = bt.title.to_lowercase();
        for &(suffix, url, service_name) in BROWSER_TITLE_SERVICES {
            if lower_title.ends_with(suffix) {
                let score = title_music_site_bonus(&bt.title).unwrap_or(0);
                match &best_match {
                    Some((best_score, ..)) if score <= *best_score => {}
                    _ => best_match = Some((score, bt, suffix, url, service_name)),
                }
            }
        }
    }

    let (_, bt, suffix, url, service_name) = best_match?;

    let content_before_suffix = &bt.title[..bt.title.len() - suffix.len()];
    let content = strip_browser_suffix(
        content_before_suffix.trim().trim_end_matches(&['-', '|'][..]).trim(),
    );

    if content.is_empty() {
        return None;
    }

    let (title, artist) = if let Some((a, b)) = split_dash(&content) {
        (a.to_string(), b.to_string())
    } else {
        (content, String::new())
    };

    let browser_name = friendly_browser_name(&bt.browser.to_lowercase());

    debug!(
        "Browser fallback: detected '{}' by '{}' from {} via {} window title",
        title, artist, service_name, browser_name
    );

    Some(RawMediaInfo {
        title,
        artist,
        album: String::new(),
        duration_ms: 0,
        position_ms: -1,
        source_app: format!("{} ({})", service_name, browser_name),
        is_playing: true,
        volume: query_system_volume().unwrap_or(-1.0),
        url: Some(url.to_string()),
        playback_rate: 1.0,
    })
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

// ---------------------------------------------------------------------------
//  Artist recovery strategies (ordered by reliability)
// ---------------------------------------------------------------------------

/// Identifies which music service a browser window title belongs to.
#[derive(Debug, Clone, Copy, PartialEq)]
enum MusicService {
    YouTubeMusic,
    SpotifyWeb,
    AppleMusic,
    SoundCloud,
    Tidal,
    Deezer,
    AmazonMusic,
    Pandora,
    Bandcamp,
    YouTube,
    Unknown,
}

/// Detect the music service from a browser window title string.
fn detect_service_from_window_title(title: &str) -> MusicService {
    let lower = title.to_lowercase();
    // Order matters — check YouTube Music before YouTube
    if lower.contains("youtube music") {
        MusicService::YouTubeMusic
    } else if lower.contains("spotify") {
        MusicService::SpotifyWeb
    } else if lower.contains("apple music") {
        MusicService::AppleMusic
    } else if lower.contains("soundcloud") {
        MusicService::SoundCloud
    } else if lower.contains("tidal") {
        MusicService::Tidal
    } else if lower.contains("deezer") {
        MusicService::Deezer
    } else if lower.contains("amazon music") {
        MusicService::AmazonMusic
    } else if lower.contains("pandora") {
        MusicService::Pandora
    } else if lower.contains("bandcamp") {
        MusicService::Bandcamp
    } else if lower.contains("youtube") {
        MusicService::YouTube
    } else {
        MusicService::Unknown
    }
}

/// Try to extract the artist from the GSMTC title field itself.
///
/// Some browsers/services embed "Artist - Song" or "Song · Artist" in the
/// GSMTC title when the Media Session metadata doesn't include a separate
/// artist field. This is MORE reliable than window title parsing because
/// the data is what the website explicitly provided to the Media Session API.
///
/// Returns (artist, cleaned_title) if a split was found that looks like
/// an artist-title pair, None otherwise.
fn try_split_artist_from_gsmtc_title(title: &str) -> Option<(String, String)> {
    if title.len() < 5 {
        return None;
    }

    // Try separators in order of specificity
    for sep in &[" · ", " - ", " – ", " — "] {
        if let Some(pos) = title.find(sep) {
            let left = title[..pos].trim();
            let right = title[pos + sep.len()..].trim();

            if left.is_empty() || right.is_empty() {
                continue;
            }

            // Sanity checks: artist names are typically short-ish
            // Reject if both sides are very long (probably not artist/title)
            if left.len() > 80 && right.len() > 80 {
                continue;
            }

            // Heuristic: shorter side is likely the artist.
            // But for "Artist - Song (feat. X) [Official Video]" the right
            // side may be long. Use the left as artist if it's reasonable.
            if left.len() <= 80 {
                return Some((left.to_string(), right.to_string()));
            }
        }
    }
    None
}

/// Service-specific artist extraction from browser window titles.
///
/// Each music service uses a distinct window title format. By knowing which
/// service is active, we can parse the window title with high accuracy instead
/// of guessing the structure.
///
/// Known formats:
///   YouTube Music: "Song - Artist - YouTube Music"  or  "Song · Artist - YouTube Music"
///   Spotify Web:   "Song · Artist - listener | Spotify"  or  "Spotify - Song"
///   SoundCloud:    "Artist - Song | SoundCloud"
///   Bandcamp:      "Song | ArtistName"
///   Apple Music:   "Song - Artist - Apple Music"
///   Tidal:         "Song - Artist - Tidal"
///   Deezer:        "Song - Artist | Deezer"
fn service_specific_artist_extraction(
    service: MusicService,
    gsmtc_title: &str,
    window_title: &str,
) -> Option<String> {
    let stripped = strip_browser_suffix(window_title);
    let content = strip_service_suffix_str(&stripped);
    if content.is_empty() {
        return None;
    }

    let gsmtc_lower = gsmtc_title.to_lowercase();

    match service {
        MusicService::YouTubeMusic => {
            // YouTube Music uses "Song - Artist" or "Song · Artist"
            // The GSMTC title is usually just "Song", so the part after
            // the separator in the content (after stripping service suffix)
            // is the artist.
            for sep in &[" · ", " - ", " – ", " — "] {
                if let Some(pos) = content.find(sep) {
                    let left = content[..pos].trim();
                    let right = content[pos + sep.len()..].trim();
                    if left.is_empty() || right.is_empty() {
                        continue;
                    }
                    // If left matches GSMTC title → right is artist
                    if fuzzy_title_match(&left.to_lowercase(), &gsmtc_lower) {
                        return Some(right.to_string());
                    }
                    // If right matches GSMTC title → left is artist
                    if fuzzy_title_match(&right.to_lowercase(), &gsmtc_lower) {
                        return Some(left.to_string());
                    }
                }
            }
            // Multi-separator: "Song - Artist - Album" — try taking middle part
            let parts: Vec<&str> = content.split(" - ").collect();
            if parts.len() >= 3 {
                let first = parts[0].trim();
                if fuzzy_title_match(&first.to_lowercase(), &gsmtc_lower) {
                    return Some(parts[1].trim().to_string());
                }
            }
            None
        }
        MusicService::SpotifyWeb => {
            // Spotify uses "Song · Artist" or "Song - Artist"
            // Also: "Song · Artist, Artist2"
            for sep in &[" · ", " - ", " – "] {
                if let Some(pos) = content.find(sep) {
                    let left = content[..pos].trim();
                    let right = content[pos + sep.len()..].trim();
                    if left.is_empty() || right.is_empty() {
                        continue;
                    }
                    if fuzzy_title_match(&left.to_lowercase(), &gsmtc_lower) {
                        return Some(right.to_string());
                    }
                    if fuzzy_title_match(&right.to_lowercase(), &gsmtc_lower) {
                        return Some(left.to_string());
                    }
                }
            }
            None
        }
        MusicService::SoundCloud => {
            // SoundCloud: "Artist - Song"
            for sep in &[" - ", " – ", " — "] {
                if let Some(pos) = content.find(sep) {
                    let left = content[..pos].trim();
                    let right = content[pos + sep.len()..].trim();
                    if left.is_empty() || right.is_empty() {
                        continue;
                    }
                    // SoundCloud format is "Artist - Song", so if right ≈ GSMTC title
                    if fuzzy_title_match(&right.to_lowercase(), &gsmtc_lower) {
                        return Some(left.to_string());
                    }
                    if fuzzy_title_match(&left.to_lowercase(), &gsmtc_lower) {
                        return Some(right.to_string());
                    }
                }
            }
            None
        }
        MusicService::Bandcamp => {
            // Bandcamp: "Song, by Artist" or "Song | Artist"
            if let Some(pos) = content.find(", by ") {
                let right = content[pos + 5..].trim();
                if !right.is_empty() {
                    return Some(right.to_string());
                }
            }
            // Fallback to generic split
            for sep in &[" | ", " - "] {
                if let Some(pos) = content.find(sep) {
                    let left = content[..pos].trim();
                    let right = content[pos + sep.len()..].trim();
                    if left.is_empty() || right.is_empty() {
                        continue;
                    }
                    if fuzzy_title_match(&left.to_lowercase(), &gsmtc_lower) {
                        return Some(right.to_string());
                    }
                    if fuzzy_title_match(&right.to_lowercase(), &gsmtc_lower) {
                        return Some(left.to_string());
                    }
                }
            }
            None
        }
        MusicService::AppleMusic | MusicService::Tidal | MusicService::Deezer
        | MusicService::AmazonMusic | MusicService::Pandora => {
            // These services commonly use "Song - Artist" format
            for sep in &[" - ", " · ", " – ", " — "] {
                if let Some(pos) = content.find(sep) {
                    let left = content[..pos].trim();
                    let right = content[pos + sep.len()..].trim();
                    if left.is_empty() || right.is_empty() {
                        continue;
                    }
                    if fuzzy_title_match(&left.to_lowercase(), &gsmtc_lower) {
                        return Some(right.to_string());
                    }
                    if fuzzy_title_match(&right.to_lowercase(), &gsmtc_lower) {
                        return Some(left.to_string());
                    }
                }
            }
            // Multi-part: "Song - Artist - Album"
            let parts: Vec<&str> = content.split(" - ").collect();
            if parts.len() >= 3 {
                let first = parts[0].trim();
                if fuzzy_title_match(&first.to_lowercase(), &gsmtc_lower) {
                    return Some(parts[1].trim().to_string());
                }
            }
            None
        }
        MusicService::YouTube | MusicService::Unknown => {
            // For YouTube / unknown: try generic "Artist - Title" splitting
            // but only if we can match the GSMTC title to one side
            for sep in &[" - ", " · ", " – ", " — "] {
                if let Some(pos) = content.find(sep) {
                    let left = content[..pos].trim();
                    let right = content[pos + sep.len()..].trim();
                    if left.is_empty() || right.is_empty() {
                        continue;
                    }
                    if fuzzy_title_match(&left.to_lowercase(), &gsmtc_lower) {
                        return Some(right.to_string());
                    }
                    if fuzzy_title_match(&right.to_lowercase(), &gsmtc_lower) {
                        return Some(left.to_string());
                    }
                }
            }
            None
        }
    }
}

/// Strip known browser suffixes from a window title.
///
/// Browser window titles typically end with " - Google Chrome", " - Brave", etc.
/// Stripping these lets us work with just the page title content.
fn strip_browser_suffix(title: &str) -> String {
    const BROWSER_SUFFIXES: &[&str] = &[
        " - Google Chrome",
        " - Chrome",
        " - Brave",
        " - Brave Browser",
        " - Microsoft\u{200b} Edge", // Edge sometimes uses zero-width space
        " - Microsoft Edge",
        " - Edge",
        " - Mozilla Firefox",
        " - Firefox",
        " - Opera",
        " - Vivaldi",
    ];
    let lower = title.to_lowercase();
    for suffix in BROWSER_SUFFIXES {
        if lower.ends_with(&suffix.to_lowercase()) {
            return title[..title.len() - suffix.len()].to_string();
        }
    }
    title.to_string()
}

/// Extract the artist from a browser window title when we know the GSMTC title.
///
/// When GSMTC gives us a clean title (e.g., "Shape of You") but no artist,
/// and the browser window title is "Shape of You - Ed Sheeran | Spotify - Google Chrome",
/// this function matches the GSMTC title against the window title content to
/// identify which part is the artist.
fn extract_artist_from_window_title(gsmtc_title: &str, window_title: &str) -> Option<String> {
    // Strip browser and service suffixes to get just the content
    let stripped = strip_browser_suffix(window_title);
    let lower_gsmtc = gsmtc_title.to_lowercase();

    // Find and strip the music service suffix
    let content = strip_service_suffix_str(&stripped);
    if content.is_empty() {
        return None;
    }

    // Try each separator to split the content
    for sep in &[" - ", " · ", " — ", " – "] {
        if let Some(pos) = content.find(sep) {
            let left = content[..pos].trim();
            let right = content[pos + sep.len()..].trim();
            if left.is_empty() || right.is_empty() {
                continue;
            }
            let left_lower = left.to_lowercase();
            let right_lower = right.to_lowercase();

            // If GSMTC title matches one side, the other side is the artist
            if fuzzy_title_match(&lower_gsmtc, &left_lower) {
                return Some(right.to_string());
            }
            if fuzzy_title_match(&lower_gsmtc, &right_lower) {
                return Some(left.to_string());
            }
        }
    }
    None
}

/// Extract both title and artist from a browser window title.
/// Used when GSMTC provides no metadata at all (TryGetMediaPropertiesAsync failed).
fn extract_metadata_from_window_title(window_title: &str) -> Option<(String, String)> {
    let stripped = strip_browser_suffix(window_title);
    let content = strip_service_suffix_str(&stripped);
    if content.is_empty() {
        return None;
    }

    if let Some((a, b)) = split_dash(&content) {
        Some((a.to_string(), b.to_string()))
    } else {
        Some((content, String::new()))
    }
}

/// Strip a known music service suffix from a title and return just the content.
fn strip_service_suffix_str(title: &str) -> String {
    let lower = title.to_lowercase();
    const SUFFIXES: &[&str] = &[
        "| spotify", "- spotify", "| spotify - premium", "- spotify - premium",
        "| youtube music", "- youtube music",
        "| apple music", "- apple music",
        "| soundcloud", "- soundcloud",
        "| tidal", "- tidal",
        "| deezer", "- deezer",
        "| amazon music", "- amazon music",
        "| pandora", "- pandora",
        "| jiosaavn", "- jiosaavn",
        "| gaana", "- gaana",
        "| bandcamp", "- bandcamp",
        "| youtube", "- youtube",
    ];
    for suffix in SUFFIXES {
        if lower.ends_with(suffix) {
            return title[..title.len() - suffix.len()]
                .trim()
                .trim_end_matches(&['-', '|'][..])
                .trim()
                .to_string();
        }
    }
    title.trim().to_string()
}

/// Fuzzy match two title strings — accounts for truncation and minor differences.
fn fuzzy_title_match(a: &str, b: &str) -> bool {
    if a == b {
        return true;
    }
    // One contains the other (handles truncated titles)
    if a.len() >= 4 && b.len() >= 4 {
        if a.contains(b) || b.contains(a) {
            return true;
        }
    }
    false
}

use crate::db::models::{ContentType, NowPlaying};
use crate::media::artist_parser;
use log::debug;

/// Minimum track duration (in ms) to consider it scrobble-worthy.
/// Filters out ads, notification sounds, short clips.
const MIN_DURATION_MS: i64 = 30_000; // 30 seconds

/// Patterns in titles that strongly indicate non-music YouTube content.
const NON_MUSIC_KEYWORDS: &[&str] = &[
    "gameplay",
    "walkthrough",
    "let's play",
    "lets play",
    "tutorial",
    "unboxing",
    "review",
    "reaction",
    "vlog",
    "podcast",
    "interview",
    "how to",
    "cooking",
    "recipe",
    "news",
    "trailer",
    "teaser",
    "highlights",
    "full match",
    "compilation",
    "funny moments",
    "top 10",
    "explained",
    "documentary",
    "asmr",
    "mukbang",
    "haul",
];

/// Keywords indicating podcast content (matched case-insensitively).
const PODCAST_INDICATORS: &[&str] = &[
    "podcast",
    "episode",
    " ep.",
    " ep ",
    "hosted by",
    "with host",
    "talk show",
    "radio show",
    "weekly show",
    "daily show",
    "morning show",
];

/// Keywords indicating audiobook content.
const AUDIOBOOK_INDICATORS: &[&str] = &[
    "audiobook",
    "audio book",
    "chapter ",
    "narrated by",
    "unabridged",
    "abridged",
    "read by",
];

/// Patterns indicating advertisements/sponsored content to reject.
const AD_PATTERNS: &[&str] = &[
    "advertisement",
    "sponsored",
    "ad break",
    "spotify ad",
    "commercial",
    "promo:",
    "brought to you by",
];

/// Suffixes/tags to strip from titles when normalizing.
const TITLE_STRIP_PATTERNS: &[&str] = &[
    "(official music video)",
    "(official video)",
    "(official audio)",
    "(official lyric video)",
    "(official lyrics video)",
    "(official hd video)",
    "(official visualizer)",
    "(music video)",
    "(lyric video)",
    "(lyrics video)",
    "(lyrics)",
    "(audio)",
    "(visualizer)",
    "(live)",
    "(acoustic)",
    "[official music video]",
    "[official video]",
    "[official audio]",
    "[official lyric video]",
    "[official lyrics video]",
    "[official visualizer]",
    "[music video]",
    "[lyric video]",
    "[lyrics video]",
    "[lyrics]",
    "[audio]",
    "[visualizer]",
    "[live]",
    "[acoustic]",
    "[mv]",
    "[m/v]",
    "(mv)",
    "(m/v)",
    "| official music video",
    "| official video",
    "| official audio",
    "// official music video",
    "// official video",
    "(prod.",
    "( prod.",
    // Additional patterns matching Android's more thorough cleanup
    "(slowed + reverb)",
    "(slowed)",
    "(sped up)",
    "(nightcore)",
    "[slowed + reverb]",
    "[slowed]",
    "[sped up]",
    "[nightcore]",
    "(clean)",
    "(explicit)",
    "[clean]",
    "[explicit]",
    "(hq)",
    "[hq]",
    "(hd)",
    "[hd]",
];

/// Browser identifiers found in source app IDs across platforms.
const BROWSER_IDENTIFIERS: &[&str] = &[
    "chrome",
    "chromium",
    "google-chrome",
    "brave",
    "edge",
    "msedge",
    "firefox",
    "safari",
    "opera",
    "vivaldi",
    "arc",
];

/// YouTube Music typically provides proper metadata via media session API.
/// These source IDs indicate YouTube Music specifically.
const YOUTUBE_MUSIC_INDICATORS: &[&str] = &[
    "music.youtube.com",
    "youtube music",
    "com.google.android.apps.youtube.music",
];

/// Check if the source app string indicates a web browser.
pub fn is_browser_source(source: &str) -> bool {
    let lower = source.to_lowercase();
    BROWSER_IDENTIFIERS.iter().any(|id| lower.contains(id))
}

/// Check if the source likely originates from YouTube Music (as opposed to plain YouTube).
fn is_youtube_music_source(source: &str) -> bool {
    let lower = source.to_lowercase();
    YOUTUBE_MUSIC_INDICATORS
        .iter()
        .any(|id| lower.contains(id))
}

/// Normalize and filter a NowPlaying entry from any source.
///
/// Returns `None` if the content should be filtered out (non-music, too short, ads, etc.).
/// Returns a cleaned-up `NowPlaying` otherwise with content_type classification.
pub fn normalize(mut np: NowPlaying) -> Option<NowPlaying> {
    // Reject advertisements and sponsored content (matching Android's ad detection)
    if is_ad_content(&np.title, &np.artist) {
        debug!(
            "Filtered ad content: {} - {}",
            np.title, np.artist
        );
        return None;
    }

    // Skip tracks with very short duration (likely ads or notification sounds).
    // Duration of 0 means unknown — let those through since many browser sources
    // don't report duration.
    if np.duration_ms > 0 && np.duration_ms < MIN_DURATION_MS {
        debug!(
            "Filtered out short track ({} ms): {} - {}",
            np.duration_ms, np.title, np.artist
        );
        return None;
    }

    // Detect content type (matching Android's content classification)
    np.content_type = detect_content_type(&np.title, &np.artist, &np.album, &np.source_app)
        .as_str()
        .to_string();

    // Determine if this is a browser source
    let from_browser = is_browser_source(&np.source_app);

    // Check if site indicates YouTube Music
    let site_is_yt_music = np
        .site
        .as_deref()
        .map(|s| s.to_lowercase().contains("music.youtube.com") || s.to_lowercase().contains("youtube music"))
        .unwrap_or(false);

    // If from a browser, try to improve the metadata
    if from_browser {
        // Classify the browser source more specifically
        np.source_app = classify_browser_source(&np.source_app, np.site.as_deref());

        // Validate artist name — reject video descriptions masquerading as artist names
        // (ported from Android's isLikelyArtistName)
        if !np.artist.is_empty()
            && np.artist != "Unknown"
            && !artist_parser::is_likely_artist_name(&np.artist)
        {
            debug!(
                "Artist name looks like video description, clearing: '{}'",
                np.artist
            );
            np.artist = String::new();
        }

        // If artist looks like a known YouTube music channel, mark as trusted
        if !np.artist.is_empty() && artist_parser::is_known_youtube_channel(&np.artist) {
            debug!(
                "Artist is known YouTube channel: '{}'",
                np.artist
            );
            // Keep the artist as-is — it's a confirmed music channel
        }

        // Filter out non-music content from regular YouTube (not YouTube Music)
        if !is_youtube_music_source(&np.source_app) && !site_is_yt_music && is_non_music_content(&np.title) {
            debug!(
                "Filtered non-music browser content: {} - {}",
                np.title, np.artist
            );
            return None;
        }
    }

    // Clean up the title regardless of source
    np.title = clean_title(&np.title);

    // Clean up artist name (including feat. extraction matching Android)
    np.artist = clean_artist(&np.artist);

    // Final validation — must have at minimum a non-empty title
    if np.title.is_empty() {
        return None;
    }

    Some(np)
}

/// Detect if content is an advertisement or sponsored content.
/// Matches Android's ad detection logic.
fn is_ad_content(title: &str, artist: &str) -> bool {
    let lower_title = title.to_lowercase();
    let lower_artist = artist.to_lowercase();

    for pattern in AD_PATTERNS {
        if lower_title.contains(pattern) || lower_artist.contains(pattern) {
            return true;
        }
    }

    // Very short title with no artist often indicates interstitial ads
    if title.len() <= 3 && artist.is_empty() {
        return true;
    }

    false
}

/// Detect content type: MUSIC, PODCAST, or AUDIOBOOK.
/// Mirrors Android's LocalMediaMetadata content type detection.
fn detect_content_type(title: &str, artist: &str, album: &str, source_app: &str) -> ContentType {
    let lower_title = title.to_lowercase();
    let lower_artist = artist.to_lowercase();
    let lower_album = album.to_lowercase();
    let lower_source = source_app.to_lowercase();

    // Check for podcast indicators
    for pattern in PODCAST_INDICATORS {
        if lower_title.contains(pattern)
            || lower_album.contains(pattern)
            || lower_source.contains(pattern)
        {
            return ContentType::Podcast;
        }
    }

    // Check podcast-specific sources
    if lower_source.contains("podcast")
        || lower_source.contains("overcast")
        || lower_source.contains("pocket casts")
        || lower_source.contains("castbox")
        || lower_source.contains("stitcher")
    {
        return ContentType::Podcast;
    }

    // Check for audiobook indicators
    for pattern in AUDIOBOOK_INDICATORS {
        if lower_title.contains(pattern)
            || lower_album.contains(pattern)
            || lower_artist.contains(pattern)
        {
            return ContentType::Audiobook;
        }
    }

    // Check audiobook-specific sources
    if lower_source.contains("audible")
        || lower_source.contains("libby")
        || lower_source.contains("librivox")
    {
        return ContentType::Audiobook;
    }

    // Genre-based detection: if album contains "podcast" tag
    if lower_album.contains("podcast") || lower_album.contains("talk") {
        return ContentType::Podcast;
    }

    ContentType::Music
}

/// Classify browser source into a more descriptive name.
/// E.g., "chrome.exe (Windows)" → "YouTube Music (Chrome)" or "Web Browser (Chrome)"
fn browser_name_from_source(source: &str) -> &'static str {
    let lower = source.to_lowercase();

    if lower.contains("chrome") || lower.contains("chromium") {
        "Chrome"
    } else if lower.contains("brave") {
        "Brave"
    } else if lower.contains("edge") || lower.contains("msedge") {
        "Edge"
    } else if lower.contains("firefox") {
        "Firefox"
    } else if lower.contains("safari") {
        "Safari"
    } else if lower.contains("opera") {
        "Opera"
    } else if lower.contains("vivaldi") {
        "Vivaldi"
    } else if lower.contains("arc") {
        "Arc"
    } else {
        "Browser"
    }
}

fn classify_browser_source(source: &str, site: Option<&str>) -> String {
    let browser_name = browser_name_from_source(source);
    let lower_site = site.unwrap_or_default().to_lowercase();

    if is_youtube_music_source(source)
        || lower_site.contains("music.youtube.com")
        || lower_site.contains("youtube music")
    {
        format!("YouTube Music ({})", browser_name)
    } else if lower_site.contains("open.spotify.com") || lower_site.contains("spotify web") {
        format!("Spotify Web ({})", browser_name)
    } else if lower_site.contains("music.apple.com") || lower_site.contains("apple music web") {
        format!("Apple Music Web ({})", browser_name)
    } else if lower_site.contains("soundcloud") {
        format!("SoundCloud ({})", browser_name)
    } else {
        format!("Web Browser ({})", browser_name)
    }
}

/// Check if the content title suggests non-music content.
fn is_non_music_content(title: &str) -> bool {
    let lower = title.to_lowercase();
    NON_MUSIC_KEYWORDS.iter().any(|kw| lower.contains(kw))
}

/// Clean up a track title by removing common video-related suffixes.
fn clean_title(title: &str) -> String {
    let mut cleaned = title.to_string();

    // Remove known suffixes (case-insensitive)
    for pattern in TITLE_STRIP_PATTERNS {
        if let Some(pos) = cleaned.to_lowercase().find(pattern) {
            cleaned = cleaned[..pos].to_string();
        }
    }

    // Remove trailing "(prod. XYZ)" or "(prod by XYZ)" patterns
    // The TITLE_STRIP_PATTERNS already handles "(prod." prefix — this catches the whole thing
    let lower = cleaned.to_lowercase();
    if let Some(pos) = lower.find("(prod") {
        if let Some(end) = cleaned[pos..].find(')') {
            cleaned = format!("{}{}", &cleaned[..pos], &cleaned[pos + end + 1..]);
        }
    }

    // Remove year patterns like "(2024)" at the end
    let trimmed = cleaned.trim();
    if trimmed.len() > 7 {
        let last_7 = &trimmed[trimmed.len() - 7..];
        if last_7.starts_with(" (")
            && last_7.ends_with(')')
            && last_7[2..6].chars().all(|c| c.is_ascii_digit())
        {
            cleaned = trimmed[..trimmed.len() - 7].to_string();
        }
    }

    cleaned.trim().to_string()
}

/// Clean up an artist name.
/// Uses the ArtistParser for proper handling of complex band names,
/// featuring extraction, and multi-artist normalization.
/// Matches Android's artist normalization pipeline.
fn clean_artist(artist: &str) -> String {
    if artist.trim().is_empty() {
        return String::new();
    }

    let mut cleaned = artist.to_string();

    // Remove " - Topic" suffix (YouTube auto-generated channels)
    if let Some(pos) = cleaned.to_lowercase().find(" - topic") {
        cleaned = cleaned[..pos].to_string();
    }

    // Remove "VEVO" suffix (e.g., "ArianaGrandeVevo" → "ArianaGrande")
    // But only if it's a suffix, not part of the name
    if cleaned.to_lowercase().ends_with("vevo") && cleaned.len() > 4 {
        cleaned = cleaned[..cleaned.len() - 4].to_string();
    }

    // Use ArtistParser to normalize the artist string while preserving featured artists.
    // This handles complex cases like "Artist feat. Other" and known bands like "Simon & Garfunkel".
    let parsed = artist_parser::parse(&cleaned);

    // Validate primary artist before proceeding.
    if artist_parser::is_unknown_artist(parsed.primary_artist()) {
        return String::new();
    }

    // Reconstruct as a plain comma-separated list of ALL artists (primary + featured).
    // This matches the format that iOS/Apple Music exposes and that MusicTrackingService
    // stores on the phone (e.g. "Farhan Khan, Mujtaba Aziz Naza, Mr. Doss").
    // Keeping a "feat." keyword in the sent string would cause the phone's findOrCreate
    // lookup to miss the existing track row and create a duplicate.
    cleaned = if parsed.all_artists.is_empty() {
        parsed.original.clone()
    } else {
        parsed.all_artists.join(", ")
    };

    cleaned.trim().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_np(title: &str, artist: &str, source: &str, duration_ms: i64) -> NowPlaying {
        NowPlaying {
            title: title.to_string(),
            artist: artist.to_string(),
            album: String::new(),
            duration_ms,
            source_app: source.to_string(),
            is_playing: true,
            listened_ms: 0,
            site: None,
            skipped: false,
            replay_count: 0,
            is_muted: false,
            completion_percentage: 0.0,
            pause_count: 0,
            seek_count: 0,
            content_type: "MUSIC".to_string(),
            session_id: String::new(),
            volume_level: 0.8,
        }
    }

    #[test]
    fn test_clean_title_strips_official_video() {
        assert_eq!(
            clean_title("Blinding Lights (Official Music Video)"),
            "Blinding Lights"
        );
        assert_eq!(
            clean_title("Shape of You [Official Video]"),
            "Shape of You"
        );
        assert_eq!(clean_title("Hello (Lyrics)"), "Hello");
        assert_eq!(
            clean_title("Dynamite (Official Audio)"),
            "Dynamite"
        );
    }

    #[test]
    fn test_clean_title_strips_mv_tags() {
        assert_eq!(clean_title("Song Name [MV]"), "Song Name");
        assert_eq!(clean_title("Song Name [M/V]"), "Song Name");
        assert_eq!(clean_title("Song Name (MV)"), "Song Name");
    }

    #[test]
    fn test_clean_artist_removes_topic() {
        assert_eq!(clean_artist("The Weeknd - Topic"), "The Weeknd");
        assert_eq!(clean_artist("Adele - Topic"), "Adele");
    }

    #[test]
    fn test_clean_artist_removes_vevo() {
        assert_eq!(clean_artist("ArianaGrandeVevo"), "ArianaGrande");
        assert_eq!(clean_artist("VEVO"), "VEVO"); // Don't strip if the whole name is VEVO
    }

    #[test]
    fn test_normalize_filters_short_tracks() {
        let np = make_np("Short", "Artist", "Spotify", 5000);
        assert!(normalize(np).is_none());
    }

    #[test]
    fn test_normalize_allows_unknown_duration() {
        let np = make_np("Song", "Artist", "chrome.exe", 0);
        assert!(normalize(np).is_some());
    }

    #[test]
    fn test_normalize_filters_non_music_from_browser() {
        let np = make_np("Epic Gameplay Walkthrough Part 1", "", "chrome.exe", 0);
        assert!(normalize(np).is_none());
    }

    #[test]
    fn test_normalize_allows_music_from_browser() {
        let np = make_np(
            "The Weeknd - Blinding Lights (Official Music Video)",
            "",
            "chrome.exe",
            0,
        );
        let result = normalize(np).unwrap();
        assert_eq!(result.title, "The Weeknd - Blinding Lights");
        assert_eq!(result.artist, "");
    }

    #[test]
    fn test_normalize_preserves_youtube_music_metadata() {
        // YouTube Music provides proper metadata via media session API
        let np = make_np(
            "Blinding Lights",
            "The Weeknd",
            "music.youtube.com - Chrome",
            214000,
        );
        let result = normalize(np).unwrap();
        assert_eq!(result.title, "Blinding Lights");
        assert_eq!(result.artist, "The Weeknd");
        assert!(result.source_app.contains("YouTube Music"));
    }

    #[test]
    fn test_clean_artist_keeps_missing_artist_empty() {
        assert_eq!(clean_artist(""), "");
        assert_eq!(clean_artist("Unknown Artist"), "");
    }

    #[test]
    fn test_classify_browser_source_uses_site() {
        assert_eq!(
            classify_browser_source("Brave (macOS)", Some("YouTube Music")),
            "YouTube Music (Brave)"
        );
        assert_eq!(
            classify_browser_source("Brave (macOS)", Some("open.spotify.com")),
            "Spotify Web (Brave)"
        );
    }

    #[test]
    fn test_normalize_keeps_browser_title_when_artist_missing() {
        let np = make_np(
            "Adele - Hello (Official Music Video)",
            "",
            "Google-Chrome (Linux)",
            0,
        );
        let result = normalize(np).unwrap();
        assert_eq!(result.artist, "");
        assert_eq!(result.title, "Adele - Hello");
    }

    #[test]
    fn test_is_browser_source() {
        assert!(is_browser_source("chrome.exe"));
        assert!(is_browser_source("Google-Chrome (Linux)"));
        assert!(is_browser_source("firefox (Windows)"));
        assert!(is_browser_source("Microsoft.Edge_xyz"));
        assert!(is_browser_source("Safari"));
        assert!(!is_browser_source("Spotify"));
        assert!(!is_browser_source("Apple Music"));
    }

    #[test]
    fn test_normalize_non_music_bypass_for_youtube_music() {
        // Even if the title contains "podcast" — YouTube Music source should still pass
        // since the user is explicitly using a music app
        let np = make_np(
            "Podcast Intro Music",
            "Some Artist",
            "music.youtube.com - Chrome",
            60000,
        );
        assert!(normalize(np).is_some());
    }

    #[test]
    fn test_normalize_empty_title_filtered() {
        let np = make_np("", "Artist", "Spotify", 200000);
        assert!(normalize(np).is_none());
    }

    #[test]
    fn test_clean_title_year_removal() {
        assert_eq!(clean_title("Song Title (2024)"), "Song Title");
    }
}

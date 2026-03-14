use crate::db::models::RawMediaInfo;
use crate::media::artist_parser;
use log::debug;

/// Known music streaming sites/services — these are always allowed.
/// The key is a domain fragment or identifier that appears in URLs or source app IDs.
const MUSIC_SITE_WHITELIST: &[SiteRule] = &[
    // Dedicated streaming services (always track)
    SiteRule {
        pattern: "music.youtube.com",
        site_name: "YouTube Music",
        is_music: true,
    },
    SiteRule {
        pattern: "open.spotify.com",
        site_name: "Spotify Web",
        is_music: true,
    },
    SiteRule {
        pattern: "soundcloud.com",
        site_name: "SoundCloud",
        is_music: true,
    },
    SiteRule {
        pattern: "tidal.com",
        site_name: "Tidal Web",
        is_music: true,
    },
    SiteRule {
        pattern: "music.apple.com",
        site_name: "Apple Music Web",
        is_music: true,
    },
    SiteRule {
        pattern: "music.amazon",
        site_name: "Amazon Music",
        is_music: true,
    },
    SiteRule {
        pattern: "deezer.com",
        site_name: "Deezer",
        is_music: true,
    },
    SiteRule {
        pattern: "pandora.com",
        site_name: "Pandora",
        is_music: true,
    },
    SiteRule {
        pattern: "bandcamp.com",
        site_name: "Bandcamp",
        is_music: true,
    },
    SiteRule {
        pattern: "last.fm",
        site_name: "Last.fm",
        is_music: true,
    },
    SiteRule {
        pattern: "jiosaavn.com",
        site_name: "JioSaavn",
        is_music: true,
    },
    SiteRule {
        pattern: "gaana.com",
        site_name: "Gaana",
        is_music: true,
    },
    SiteRule {
        pattern: "wynk.in",
        site_name: "Wynk",
        is_music: true,
    },
    SiteRule {
        pattern: "audiomack.com",
        site_name: "Audiomack",
        is_music: true,
    },
    SiteRule {
        pattern: "napster.com",
        site_name: "Napster",
        is_music: true,
    },
    SiteRule {
        pattern: "qobuz.com",
        site_name: "Qobuz",
        is_music: true,
    },
    // YouTube — conditionally music (needs title-based filtering)
    SiteRule {
        pattern: "youtube.com",
        site_name: "YouTube",
        is_music: false,
    },
    SiteRule {
        pattern: "youtu.be",
        site_name: "YouTube",
        is_music: false,
    },
];

/// Sites that should never be tracked (video, podcast, social).
const BLOCKED_SITES: &[&str] = &[
    "netflix.com",
    "hulu.com",
    "disneyplus.com",
    "primevideo.com",
    "twitch.tv",
    "facebook.com",
    "twitter.com",
    "x.com",
    "instagram.com",
    "tiktok.com",
    "reddit.com",
    "zoom.us",
    "meet.google.com",
    "teams.microsoft.com",
    "discord.com",
];

struct SiteRule {
    pattern: &'static str,
    site_name: &'static str,
    /// `true` = unconditionally music, `false` = needs title heuristic.
    is_music: bool,
}

/// Detected site classification result.
#[derive(Debug, Clone)]
pub struct SiteClassification {
    /// Human-readable site name (e.g., "YouTube Music", "Spotify Web").
    pub site_name: String,
    /// Whether this is a known music source.
    pub is_music_site: bool,
    /// Whether this is a definitively blocked non-music source.
    pub is_blocked: bool,
}

/// Extract the site from a `RawMediaInfo` by examining the URL, source app, title.
///
/// Returns the site domain/name, or `None` if it can't be determined.
pub fn extract_site(raw: &RawMediaInfo) -> Option<String> {
    // 1. Check URL first (Linux MPRIS and some Windows sessions provide this)
    if let Some(ref url) = raw.url {
        if let Some(domain) = extract_domain_from_url(url) {
            return Some(domain);
        }
    }

    // 2. Check source_app for known patterns
    let lower_source = raw.source_app.to_lowercase();
    for rule in MUSIC_SITE_WHITELIST {
        if lower_source.contains(rule.pattern)
            || lower_source.contains(&rule.site_name.to_lowercase())
        {
            return Some(rule.site_name.to_string());
        }
    }

    // 3. Title-based heuristic for YouTube: titles often end with "- YouTube"
    let lower_title = raw.title.to_lowercase();
    if lower_title.ends_with("- youtube") || lower_title.ends_with("| youtube") {
        return Some("YouTube".to_string());
    }

    // 4. Title-based heuristics for music sites (helps Windows where URL isn't
    //    available — GSMTC only provides title/artist, but browser media sessions
    //    often include the service name as a title suffix).
    if lower_title.ends_with("- youtube music") || lower_title.ends_with("| youtube music") {
        return Some("YouTube Music".to_string());
    }
    if lower_title.ends_with("- spotify") || lower_title.ends_with("| spotify") {
        return Some("Spotify Web".to_string());
    }
    if lower_title.ends_with("- soundcloud") || lower_title.ends_with("| soundcloud") {
        return Some("SoundCloud".to_string());
    }
    if lower_title.ends_with("- apple music") || lower_title.ends_with("| apple music") {
        return Some("Apple Music Web".to_string());
    }
    if lower_title.ends_with("- tidal") || lower_title.ends_with("| tidal") {
        return Some("Tidal Web".to_string());
    }
    if lower_title.ends_with("- deezer") || lower_title.ends_with("| deezer") {
        return Some("Deezer".to_string());
    }
    if lower_title.contains("bandcamp") {
        return Some("Bandcamp".to_string());
    }

    None
}

/// Classify a site detection result into music / not-music / blocked.
pub fn classify_site(site: Option<&str>, raw: &RawMediaInfo) -> SiteClassification {
    let source_lower = raw.source_app.to_lowercase();

    // Check against blocked list first
    if let Some(ref url) = raw.url {
        let url_lower = url.to_lowercase();
        for blocked in BLOCKED_SITES {
            if url_lower.contains(blocked) {
                return SiteClassification {
                    site_name: blocked.to_string(),
                    is_music_site: false,
                    is_blocked: true,
                };
            }
        }
    }

    // Check the site against whitelist
    if let Some(site_str) = site {
        for rule in MUSIC_SITE_WHITELIST {
            if site_str.to_lowercase().contains(rule.pattern)
                || rule.site_name.eq_ignore_ascii_case(site_str)
            {
                return SiteClassification {
                    site_name: rule.site_name.to_string(),
                    is_music_site: rule.is_music,
                    is_blocked: false,
                };
            }
        }
    }

    // Not a browser source? (Desktop apps like Spotify, Apple Music, Tidal)
    // These are implicitly music sources.
    if !is_browser_source_app(&source_lower) {
        return SiteClassification {
            site_name: raw.source_app.clone(),
            is_music_site: true,
            is_blocked: false,
        };
    }

    // Unknown browser site — let through but mark as non-music-site
    // (the normalizer will apply title-based heuristics)
    SiteClassification {
        site_name: site.unwrap_or("Unknown").to_string(),
        is_music_site: false,
        is_blocked: false,
    }
}

/// Determine if the raw media info should be tracked at all.
///
/// Returns `true` if it should be tracked, `false` if it should be dropped.
pub fn should_track(raw: &RawMediaInfo) -> bool {
    let site = extract_site(raw);
    let classification = classify_site(site.as_deref(), raw);

    if classification.is_blocked {
        debug!(
            "Site blocked: {} (title: '{}')",
            classification.site_name, raw.title
        );
        return false;
    }

    // Known music sites — always track
    if classification.is_music_site {
        return true;
    }

    // Non-browser desktop apps (Spotify, Apple Music, etc.) — always track
    let source_lower = raw.source_app.to_lowercase();
    if !is_browser_source_app(&source_lower) {
        return true;
    }

    // YouTube (not YouTube Music) — apply title heuristic + channel check
    if classification.site_name == "YouTube" {
        // If the artist matches a user-defined YouTube music channel, always track
        if !raw.artist.is_empty()
            && raw.artist != "Unknown"
            && artist_parser::is_known_youtube_channel(&raw.artist)
        {
            debug!(
                "Tracking YouTube content from known music channel: '{}'",
                raw.artist
            );
            return true;
        }
        debug!(
            "Rejecting plain YouTube content by default: '{}' from '{}'",
            raw.title, raw.source_app
        );
        return false;
    }

    // Unknown browser source with decent metadata — cautiously track
    if !raw.artist.is_empty() && raw.artist != "Unknown" && !raw.title.is_empty() {
        return true;
    }

    debug!(
        "Filtered unknown browser content: '{}' from '{}'",
        raw.title, raw.source_app
    );
    false
}

/// Check if a title/artist combo is likely music content (for YouTube filtering).
#[cfg(test)]
fn is_likely_music_title(title: &str, artist: &str) -> bool {
    let lower = title.to_lowercase();

    // Positive signals — strong indicators of music content
    let music_indicators = [
        "official music video",
        "official video",
        "official audio",
        "official lyric",
        "lyric video",
        "lyrics",
        "music video",
        "audio",
        "ft.",
        "feat.",
        "remix",
        "acoustic",
        "live performance",
        "visualizer",
        "official mv",
        "[mv]",
        "[m/v]",
        "cover",
        "karaoke",
    ];

    for indicator in &music_indicators {
        if lower.contains(indicator) {
            return true;
        }
    }

    // Has non-empty artist that passes the "likely artist name" validation
    if !artist.is_empty() && artist != "Unknown" && artist_parser::is_likely_artist_name(artist) {
        return true;
    }

    // Artist matches a known YouTube music channel
    if !artist.is_empty() && artist_parser::is_known_youtube_channel(artist) {
        return true;
    }

    // Title contains "Artist - Song" format
    let separators = [" - ", " — ", " – "];
    for sep in &separators {
        if lower.contains(sep) {
            return true;
        }
    }

    // Negative signals — strong indicators of non-music
    let non_music = [
        "gameplay",
        "walkthrough",
        "let's play",
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
        "stream",
        "episode",
    ];

    for nm in &non_music {
        if lower.contains(nm) {
            return false;
        }
    }

    // Ambiguous — default to not tracking
    false
}

fn is_browser_source_app(source_lower: &str) -> bool {
    const BROWSER_IDS: &[&str] = &[
        "chrome",
        "chromium",
        "brave",
        "edge",
        "msedge",
        "firefox",
        "safari",
        "opera",
        "vivaldi",
        "arc",
        "web browser",
    ];
    BROWSER_IDS.iter().any(|id| source_lower.contains(id))
}

/// Extract domain from a URL string.
fn extract_domain_from_url(url: &str) -> Option<String> {
    // Handle typical URLs: https://music.youtube.com/watch?v=...
    let without_scheme = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))
        .unwrap_or(url);

    let domain = without_scheme.split('/').next()?;
    let domain = domain.split(':').next()?; // strip port
    let domain = domain.trim();

    if domain.is_empty() || !domain.contains('.') {
        return None;
    }

    Some(domain.to_lowercase())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::models::RawMediaInfo;

    fn make_raw(title: &str, artist: &str, source: &str, url: Option<&str>) -> RawMediaInfo {
        RawMediaInfo {
            title: title.to_string(),
            artist: artist.to_string(),
            album: String::new(),
            duration_ms: 200_000,
            position_ms: 10_000,
            source_app: source.to_string(),
            is_playing: true,
            volume: 0.8,
            url: url.map(|u| u.to_string()),
            playback_rate: 1.0,
        }
    }

    #[test]
    fn test_extract_site_from_url() {
        let raw = make_raw(
            "Song",
            "Artist",
            "Chrome",
            Some("https://music.youtube.com/watch?v=abc"),
        );
        assert_eq!(extract_site(&raw), Some("music.youtube.com".to_string()));
    }

    #[test]
    fn test_extract_site_from_url_spotify() {
        let raw = make_raw(
            "Song",
            "Artist",
            "Chrome",
            Some("https://open.spotify.com/track/123"),
        );
        assert_eq!(extract_site(&raw), Some("open.spotify.com".to_string()));
    }

    #[test]
    fn test_extract_site_from_source_app() {
        let raw = make_raw("Song", "Artist", "music.youtube.com - Chrome", None);
        assert_eq!(extract_site(&raw), Some("YouTube Music".to_string()));
    }

    #[test]
    fn test_should_track_youtube_music() {
        let raw = make_raw(
            "Song",
            "Artist",
            "Chrome",
            Some("https://music.youtube.com/watch?v=abc"),
        );
        assert!(should_track(&raw));
    }

    #[test]
    fn test_should_track_spotify_desktop() {
        let raw = make_raw("Song", "Artist", "Spotify (macOS)", None);
        assert!(should_track(&raw));
    }

    #[test]
    fn test_should_not_track_netflix() {
        let raw = make_raw(
            "Movie Title",
            "",
            "Chrome",
            Some("https://www.netflix.com/watch/123"),
        );
        assert!(!should_track(&raw));
    }

    #[test]
    fn test_should_not_track_plain_youtube_music_video() {
        let raw = make_raw(
            "The Weeknd - Blinding Lights (Official Music Video)",
            "",
            "Chrome",
            Some("https://www.youtube.com/watch?v=abc"),
        );
        assert!(!should_track(&raw));
    }

    #[test]
    fn test_should_not_track_youtube_gameplay() {
        let raw = make_raw(
            "GTA V Gameplay Walkthrough Part 1",
            "",
            "Chrome",
            Some("https://www.youtube.com/watch?v=abc"),
        );
        assert!(!should_track(&raw));
    }

    #[test]
    fn test_should_not_track_youtube_with_artist_by_default() {
        let raw = make_raw(
            "Blinding Lights",
            "The Weeknd",
            "Chrome",
            Some("https://www.youtube.com/watch?v=abc"),
        );
        assert!(!should_track(&raw));
    }

    #[test]
    fn test_should_track_soundcloud() {
        let raw = make_raw(
            "Song",
            "Artist",
            "Firefox",
            Some("https://soundcloud.com/artist/track"),
        );
        assert!(should_track(&raw));
    }

    #[test]
    fn test_should_not_track_twitch() {
        let raw = make_raw(
            "Streamer Live",
            "",
            "Chrome",
            Some("https://www.twitch.tv/streamer"),
        );
        assert!(!should_track(&raw));
    }

    #[test]
    fn test_extract_domain() {
        assert_eq!(
            extract_domain_from_url("https://music.youtube.com/watch?v=abc"),
            Some("music.youtube.com".to_string())
        );
        assert_eq!(extract_domain_from_url("http://localhost:3000/"), None);
        assert_eq!(extract_domain_from_url("not-a-url"), None);
    }

    #[test]
    fn test_classify_unknown_browser_no_metadata() {
        let raw = make_raw("Some Page Title", "", "Chrome", None);
        assert!(!should_track(&raw));
    }

    #[test]
    fn test_classify_bandcamp() {
        let raw = make_raw(
            "Song",
            "Artist",
            "Firefox",
            Some("https://artist.bandcamp.com/track/song"),
        );
        assert!(should_track(&raw));
    }

    #[test]
    fn test_likely_music_title_with_separator() {
        assert!(is_likely_music_title("Adele - Hello", ""));
        assert!(is_likely_music_title("BTS — Dynamite", ""));
    }

    #[test]
    fn test_likely_music_title_with_indicator() {
        assert!(is_likely_music_title("Hello (Official Music Video)", ""));
        assert!(is_likely_music_title("Song ft. Other Artist", ""));
    }

    #[test]
    fn test_not_likely_music_podcast() {
        assert!(!is_likely_music_title("My Podcast Episode 42", ""));
        assert!(!is_likely_music_title("Gameplay Walkthrough", ""));
    }
}

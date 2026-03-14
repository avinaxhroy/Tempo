use log::debug;
use once_cell::sync::Lazy;
use regex::Regex;
use std::collections::HashSet;
use std::sync::RwLock;

/// Result of parsing an artist string.
#[derive(Debug, Clone)]
pub struct ParsedArtists {
    /// The primary/main artist(s) — before any featuring notation.
    pub primary_artists: Vec<String>,
    /// Featured artists (after feat., ft., etc.).
    pub featured_artists: Vec<String>,
    /// All artists combined.
    pub all_artists: Vec<String>,
    /// Original raw string.
    pub original: String,
}

impl ParsedArtists {
    /// Get the first/main artist name.
    pub fn primary_artist(&self) -> &str {
        self.primary_artists
            .first()
            .map(|s| s.as_str())
            .unwrap_or(&self.original)
    }

    /// Check if this has multiple artists.
    pub fn has_multiple_artists(&self) -> bool {
        self.all_artists.len() > 1
    }
}

// ---------------------------------------------------------------------------
// Known complex bands — whitelist of names that contain separators (&, and, +, etc.)
// and must NEVER be split into multiple artists.
// Ported from Android ArtistParser.kt KNOWN_COMPLEX_BANDS.
// ---------------------------------------------------------------------------
static KNOWN_COMPLEX_BANDS: Lazy<HashSet<&'static str>> = Lazy::new(|| {
    [
        // Classic Rock & Oldies
        "dead & company",
        "derek & the dominos",
        "belle & sebastian",
        "iron & wine",
        "simon & garfunkel",
        "hall & oates",
        "brooks & dunn",
        "big & rich",
        "florida georgia line",
        "the mamas & the papas",
        "peter paul & mary",
        "crosby stills nash & young",
        "emerson lake & palmer",
        "blood sweat & tears",
        "earth wind & fire",
        "earth, wind & fire",
        "kool & the gang",
        "rob base & dj ez rock",
        "eric b & rakim",
        "eric b. & rakim",
        "salt n pepa",
        "salt-n-pepa",
        "outkast",
        "tears for fears",
        "tom petty & the heartbreakers",
        "bob seger & the silver bullet band",
        "bruce springsteen & the e street band",
        "hootie & the blowfish",
        "ac/dc",
        "sly & the family stone",
        "tommy james & the shondells",
        "sam & dave",
        "ike & tina turner",
        "sonny & cher",
        "ashford & simpson",
        "peaches & herb",
        "martha reeves & the vandellas",
        "diana ross & the supremes",
        "smokey robinson & the miracles",
        "gladys knight & the pips",
        "junior walker & the all stars",
        "kc & the sunshine band",
        "tony orlando & dawn",
        "huey lewis & the news",
        "echo & the bunnymen",
        "siouxsie & the banshees",
        "the jesus & mary chain",
        "captain & tennille",
        "gerry & the pacemakers",
        "peter & gordon",
        "chad & jeremy",
        "george thorogood & the destroyers",
        "elvis costello & the attractions",
        "bob marley & the wailers",
        // Prince variants
        "prince and the revolution",
        "prince & the revolution",
        "prince and the power generation",
        "prince & the power generation",
        "prince and the new power generation",
        "prince & the new power generation",
        // Indie & Modern Rock
        "mumford & sons",
        "mumford and sons",
        "florence & the machine",
        "florence and the machine",
        "florence + the machine",
        "of monsters & men",
        "king gizzard & the lizard wizard",
        "catfish and the bottlemen",
        "catfish & the bottlemen",
        "fitz & the tantrums",
        "marina & the diamonds",
        "years & years",
        "angus & julia stone",
        "tegan & sara",
        "matt & kim",
        "she & him",
        "edward sharpe & the magnetic zeros",
        "grace potter & the nocturnals",
        "judah & the lion",
        "aly & aj",
        // Country
        "maddie & tae",
        "dan & shay",
        "love & theft",
        // Electronic & Dance
        "chase & status",
        "above & beyond",
        "aly & fila",
        "w & w",
        "sunnery james & ryan marciano",
        "dimitri vegas & like mike",
        "axwell & ingrosso",
        // R&B & Soul
        "marvin gaye & tammi terrell",
        "roberta flack & donny hathaway",
        "boyz ii men",
        "tony! toni! toné!",
        "tlc",
        "run the jewels",
        // Hip Hop & Rap
        "dj jazzy jeff & the fresh prince",
        "macklemore & ryan lewis",
        "run-dmc",
        "black star",
        "method man & redman",
        "mobb deep",
        "ugk",
        "8ball & mjg",
        "rae sremmurd",
        "kids see ghosts",
        "city girls",
        "earthgang",
        "tyler, the creator",
        // Jazz & Blues
        "sonny terry & brownie mcghee",
        "django reinhardt & stéphane grappelli",
        "ella fitzgerald & louis armstrong",
        // Folk & Americana
        "shovels & rope",
        "mandolin orange",
        "watchhouse",
        "the civil wars",
        "johnnyswim",
        "jamestown revival",
        "penny & sparrow",
        // Pop & Other
        "maroon 5",
        "soft cell",
        "wham!",
        "eurythmics",
        "pet shop boys",
        "daft punk",
        "justice",
        "empire of the sun",
        "mgmt",
        "foster the people",
        "phoenix",
        "passion pit",
        "chromeo",
        "flight facilities",
        "duck sauce",
        "disclosure",
        "rudimental",
        "clean bandit",
        "chainsmokers",
        "marshmello",
        "galantis",
        // Additional known collaborations
        "kid cudi & eminem",
        "lil nas x & billy ray cyrus",
    ]
    .into_iter()
    .collect()
});

// ---------------------------------------------------------------------------
// User-defined known bands (loaded from DB at startup, supplemented at runtime).
// ---------------------------------------------------------------------------
static USER_KNOWN_BANDS: Lazy<RwLock<HashSet<String>>> =
    Lazy::new(|| RwLock::new(HashSet::new()));

/// Load user-defined known band names from the database.
/// Called once at app startup.
pub fn load_user_known_bands(names: &[String]) {
    let mut set = USER_KNOWN_BANDS.write().unwrap();
    set.clear();
    for name in names {
        set.insert(name.trim().to_lowercase());
    }
    debug!("Loaded {} user-known bands", set.len());
}

/// Add a single user-known band name at runtime.
pub fn add_user_known_band(name: &str) {
    let lower = name.trim().to_lowercase();
    let mut set = USER_KNOWN_BANDS.write().unwrap();
    set.insert(lower);
}

/// Remove a user-known band name at runtime.
pub fn remove_user_known_band(name: &str) {
    let lower = name.trim().to_lowercase();
    let mut set = USER_KNOWN_BANDS.write().unwrap();
    set.remove(&lower);
}

// ---------------------------------------------------------------------------
// User-defined YouTube channels (known music channels).
// ---------------------------------------------------------------------------
static USER_YOUTUBE_CHANNELS: Lazy<RwLock<HashSet<String>>> =
    Lazy::new(|| RwLock::new(HashSet::new()));

/// Load user-defined YouTube channel names from the database.
pub fn load_user_youtube_channels(names: &[String]) {
    let mut set = USER_YOUTUBE_CHANNELS.write().unwrap();
    set.clear();
    for name in names {
        set.insert(name.trim().to_lowercase());
    }
    debug!("Loaded {} user YouTube channels", set.len());
}

/// Add a single user YouTube channel at runtime.
pub fn add_user_youtube_channel(name: &str) {
    let lower = name.trim().to_lowercase();
    let mut set = USER_YOUTUBE_CHANNELS.write().unwrap();
    set.insert(lower);
}

/// Remove a user YouTube channel at runtime.
pub fn remove_user_youtube_channel(name: &str) {
    let lower = name.trim().to_lowercase();
    let mut set = USER_YOUTUBE_CHANNELS.write().unwrap();
    set.remove(&lower);
}

/// Check if a YouTube channel/artist name is in the user's known music channel list.
pub fn is_known_youtube_channel(name: &str) -> bool {
    let lower = name.trim().to_lowercase();
    let set = USER_YOUTUBE_CHANNELS.read().unwrap();
    set.contains(&lower)
}

// ---------------------------------------------------------------------------
// Regex patterns (compiled once).
// ---------------------------------------------------------------------------

/// Featuring patterns — match "feat.", "ft.", "featuring", "with" and variants.
static FEATURING_PATTERNS: Lazy<Vec<Regex>> = Lazy::new(|| {
    vec![
        Regex::new(r"(?i)\s+feat\.?\s+").unwrap(),
        Regex::new(r"(?i)\s+ft\.?\s+").unwrap(),
        Regex::new(r"(?i)\s+featuring\s+").unwrap(),
        Regex::new(r"(?i)\s+with\s+").unwrap(),
        Regex::new(r"(?i)\(feat\.?\s*").unwrap(),
        Regex::new(r"(?i)\(ft\.?\s*").unwrap(),
        Regex::new(r"(?i)\(featuring\s*").unwrap(),
        Regex::new(r"(?i)\(with\s*").unwrap(),
        Regex::new(r"(?i)\[feat\.?\s*").unwrap(),
        Regex::new(r"(?i)\[ft\.?\s*").unwrap(),
    ]
});

/// Patterns indicating the ampersand is part of a band name, not a separator.
static AMPERSAND_BAND_PATTERNS: Lazy<Vec<Regex>> = Lazy::new(|| {
    vec![
        Regex::new(r"(?i)\s*&\s*the\s+").unwrap(),      // "& The ..."
        Regex::new(r"(?i)\s*&\s*company\b").unwrap(),    // "& Company"
        Regex::new(r"(?i)\s*&\s*friends\b").unwrap(),    // "& Friends"
        Regex::new(r"(?i)\s*&\s*associates\b").unwrap(), // "& Associates"
    ]
});

/// Safe splitters — almost always indicate multiple artists.
static SAFE_SPLIT_PATTERNS: Lazy<Vec<Regex>> = Lazy::new(|| {
    vec![
        Regex::new(r"\s*,\s*").unwrap(),  // Comma
        Regex::new(r"\s*\|\s*").unwrap(), // Pipe
        Regex::new(r"\s*/\s*").unwrap(),  // Slash
    ]
});

/// Complex splitters — might be part of a band name (checked against known bands).
static COMPLEX_SPLIT_PATTERNS: Lazy<Vec<Regex>> = Lazy::new(|| {
    vec![
        Regex::new(r"(?i)\s+and\s+").unwrap(),  // "and"
        Regex::new(r"(?i)\s+x\s+").unwrap(),    // "x" collaboration
        Regex::new(r"(?i)\s+vs\.?\s+").unwrap(), // "vs" or "vs."
        Regex::new(r"\s*\+\s*").unwrap(),        // Plus sign
    ]
});

/// Ampersand split pattern.
static AMPERSAND_SPLIT: Lazy<Regex> = Lazy::new(|| Regex::new(r"\s*&\s*").unwrap());

/// Closing bracket cleanup.
static CLOSING_BRACKET: Lazy<Regex> = Lazy::new(|| Regex::new(r"[)\]]+.*$").unwrap());

/// Whitespace normalization.
static MULTI_WHITESPACE: Lazy<Regex> = Lazy::new(|| Regex::new(r"\s+").unwrap());

/// Trailing/leading bracket cleanup.
static TRAILING_BRACKETS: Lazy<Regex> = Lazy::new(|| Regex::new(r"[)\]]+$").unwrap());
static LEADING_BRACKETS: Lazy<Regex> = Lazy::new(|| Regex::new(r"^[(&\[]+").unwrap());

/// Special characters for normalization.
static SPECIAL_CHARS: Lazy<Regex> = Lazy::new(|| Regex::new(r"[^a-z0-9\s]").unwrap());

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/// Parse an artist string into its component parts.
///
/// Handles all common formats:
/// - "Artist1, Artist2" (comma-separated)
/// - "Artist1 & Artist2" (ampersand)
/// - "Artist1 feat. Artist2" (featuring)
/// - "Artist1 x Artist2" (collaboration)
/// - "Artist1 / Artist2" (slash-separated)
/// - "Artist1 and Artist2"
/// - "Artist1 vs. Artist2"
/// - "Artist1 + Artist2"
pub fn parse(artist_string: &str) -> ParsedArtists {
    if artist_string.trim().is_empty() {
        return ParsedArtists {
            primary_artists: vec!["Unknown Artist".to_string()],
            featured_artists: vec![],
            all_artists: vec!["Unknown Artist".to_string()],
            original: artist_string.to_string(),
        };
    }

    let cleaned = artist_string.trim();

    // Separate main artists from featured artists
    let (main_part, featured_part) = split_featuring(cleaned);

    // Split main part into individual artists
    let primary_artists = split_artists(&main_part);

    // Split featured part into individual artists
    let featured_artists = if featured_part.is_empty() {
        vec![]
    } else {
        split_artists(&featured_part)
    };

    // Combine all artists (deduplicated)
    let mut all_artists = primary_artists.clone();
    for fa in &featured_artists {
        if !all_artists.iter().any(|a| a.to_lowercase() == fa.to_lowercase()) {
            all_artists.push(fa.clone());
        }
    }

    ParsedArtists {
        primary_artists,
        featured_artists,
        all_artists,
        original: artist_string.to_string(),
    }
}

/// Get just the primary artist name.
pub fn get_primary_artist(artist_string: &str) -> String {
    parse(artist_string).primary_artist().to_string()
}

/// Get all artists as a list.
pub fn get_all_artists(artist_string: &str) -> Vec<String> {
    parse(artist_string).all_artists
}

/// Normalize an artist string for comparison/search purposes.
pub fn normalize_for_search(artist: &str) -> String {
    let lower = artist.to_lowercase().replace('$', "s");
    let cleaned = SPECIAL_CHARS.replace_all(&lower, "");
    let normalized = MULTI_WHITESPACE.replace_all(&cleaned, " ");
    normalized.trim().to_string()
}

/// Check if two artist strings likely refer to the same artist.
pub fn is_same_artist(artist1: &str, artist2: &str) -> bool {
    let norm1 = normalize_for_search(artist1);
    let norm2 = normalize_for_search(artist2);

    // Exact match after normalization
    if norm1 == norm2 {
        return true;
    }

    // Check if one contains the other
    if norm1.contains(&norm2) || norm2.contains(&norm1) {
        return true;
    }

    // Check if the parsed primary artists match
    let primary1 = normalize_for_search(&get_primary_artist(artist1));
    let primary2 = normalize_for_search(&get_primary_artist(artist2));
    if primary1 == primary2 {
        return true;
    }

    // Jaccard word similarity
    let words1: HashSet<&str> = norm1.split_whitespace().collect();
    let words2: HashSet<&str> = norm2.split_whitespace().collect();
    let intersection = words1.intersection(&words2).count();
    let union = words1.union(&words2).count();
    if union > 0 {
        let similarity = intersection as f64 / union as f64;
        if similarity >= 0.5 {
            return true;
        }
    }

    false
}

/// Check if an artist string represents an unknown/empty artist.
pub fn is_unknown_artist(artist: &str) -> bool {
    let normalized = artist.trim().to_lowercase();
    normalized.is_empty()
        || normalized == "unknown artist"
        || normalized == "unknown"
        || normalized == "<unknown>"
        || normalized == "various artists"
}

/// Validate if text looks like a real artist name (not a video description).
/// Ported from Android's `isLikelyArtistName()`.
pub fn is_likely_artist_name(text: &str) -> bool {
    // Too long for a typical artist name
    if text.len() > 60 {
        return false;
    }

    let lower = text.to_lowercase();

    // Video/audio quality patterns
    let quality_patterns = ["1080p", "720p", "480p", "4k", "hd video", "full hd"];
    for pattern in &quality_patterns {
        if lower.contains(&format!("({}", pattern)) || lower.contains(&format!("[{}", pattern)) {
            return false;
        }
    }

    // Clear video description patterns
    let video_patterns = [
        "official video",
        "official audio",
        "official music video",
        "lyric video",
        "lyrics video",
        "music video",
        "full video",
        "visualizer",
        "full album",
        "audio only",
    ];
    if video_patterns.iter().any(|p| lower.contains(p)) {
        return false;
    }

    // Parenthesized description suffixes
    let description_suffixes = [
        "(official)",
        "(audio)",
        "(video)",
        "(lyrics)",
        "(visualizer)",
        "(official video)",
        "(official audio)",
        "(lyric video)",
        "(theme song)",
        "(full video)",
        "(music video)",
        "(trailer)",
    ];
    if description_suffixes.iter().any(|s| lower.ends_with(s)) {
        return false;
    }

    // Anthem/theme in parentheses
    if lower.contains("anthem)") || lower.contains("theme)") {
        return false;
    }

    // Very long text with separator + parentheses → likely description
    if text.len() > 50 && lower.contains(" - ") && lower.contains('(') {
        return false;
    }

    true
}

/// Clean a track title by removing embedded artist info (feat./ft.).
pub fn clean_track_title(title: &str) -> String {
    let mut cleaned = title.to_string();

    // Remove (feat. ...) or [feat. ...] patterns
    static EMBEDDED_FEAT: Lazy<Regex> =
        Lazy::new(|| Regex::new(r"(?i)\s*[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+[^)\]]+[)\]]").unwrap());
    cleaned = EMBEDDED_FEAT.replace_all(&cleaned, "").to_string();

    // Remove trailing feat. patterns without brackets
    static TRAILING_FEAT: Lazy<Regex> =
        Lazy::new(|| Regex::new(r"(?i)\s+(?:feat\.?|ft\.?)\s+.*$").unwrap());
    cleaned = TRAILING_FEAT.replace_all(&cleaned, "").to_string();

    cleaned.trim().to_string()
}

/// Extract featured artists embedded in a track title.
pub fn extract_artists_from_title(title: &str) -> Vec<String> {
    for pattern in FEATURING_PATTERNS.iter() {
        if let Some(mat) = pattern.find(title) {
            let after_match = &title[mat.end()..];
            let cleaned = CLOSING_BRACKET.replace(after_match, "");
            let cleaned = cleaned.trim();
            if !cleaned.is_empty() {
                return split_artists(cleaned);
            }
        }
    }
    vec![]
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/// Separate main part from featured part.
fn split_featuring(input: &str) -> (String, String) {
    for pattern in FEATURING_PATTERNS.iter() {
        if let Some(mat) = pattern.find(input) {
            let main_part = input[..mat.start()].trim().to_string();
            let after = &input[mat.end()..];
            let featured_part = CLOSING_BRACKET.replace(after, "").trim().to_string();
            return (main_part, featured_part);
        }
    }
    (input.to_string(), String::new())
}

/// Split an artist string by collaboration patterns.
/// Uses smart detection to avoid splitting known band names.
fn split_artists(artist_string: &str) -> Vec<String> {
    // If the whole string is a known band, don't split at all
    if is_known_band(artist_string) {
        return vec![artist_string.to_string()];
    }

    let mut parts: Vec<String> = vec![artist_string.to_string()];

    // 1. SAFE separators (comma, pipe, slash) — always split
    for pattern in SAFE_SPLIT_PATTERNS.iter() {
        parts = parts
            .into_iter()
            .flat_map(|part| {
                pattern
                    .split(&part)
                    .map(|s| s.trim().to_string())
                    .collect::<Vec<_>>()
            })
            .collect();
    }

    // 2. COMPLEX separators (and, x, vs, +) — check for known bands
    for pattern in COMPLEX_SPLIT_PATTERNS.iter() {
        parts = parts
            .into_iter()
            .flat_map(|part| {
                if is_known_band(&part) {
                    vec![part]
                } else {
                    pattern
                        .split(&part)
                        .map(|s| s.trim().to_string())
                        .collect::<Vec<_>>()
                }
            })
            .collect();
    }

    // 3. Ampersand — smart handling
    parts = parts
        .into_iter()
        .flat_map(|part| split_by_ampersand_smartly(&part))
        .collect();

    // Normalize and deduplicate
    let result: Vec<String> = parts
        .into_iter()
        .map(|p| normalize_artist_name(&p))
        .filter(|p| !p.is_empty() && p.to_lowercase() != "unknown artist")
        .collect::<Vec<_>>();

    // Deduplicate (case-insensitive)
    let mut seen = HashSet::new();
    let mut deduped = Vec::new();
    for artist in result {
        let lower = artist.to_lowercase();
        if seen.insert(lower) {
            deduped.push(artist);
        }
    }

    if deduped.is_empty() {
        vec![artist_string.trim().to_string()]
    } else {
        deduped
    }
}

/// Smart ampersand splitting that preserves band names.
fn split_by_ampersand_smartly(artist_string: &str) -> Vec<String> {
    if !artist_string.contains('&') {
        return vec![artist_string.to_string()];
    }

    // Check against known bands
    if is_known_band(artist_string) {
        return vec![artist_string.to_string()];
    }

    // Check against pattern-based whitelist ("& The", "& Company", etc.)
    if AMPERSAND_BAND_PATTERNS
        .iter()
        .any(|p| p.is_match(artist_string))
    {
        return vec![artist_string.to_string()];
    }

    // Otherwise, treat ampersand as a separator
    AMPERSAND_SPLIT
        .split(artist_string)
        .map(|s| s.trim().to_string())
        .collect()
}

/// Check if artist name is in the known complex bands list (hardcoded + user-defined).
fn is_known_band(artist: &str) -> bool {
    let lower = artist.trim().to_lowercase();

    // Check hardcoded list (fast HashSet lookup)
    if KNOWN_COMPLEX_BANDS.contains(lower.as_str()) {
        return true;
    }

    // Check user-defined list
    let user_bands = USER_KNOWN_BANDS.read().unwrap();
    user_bands.contains(&lower)
}

/// Normalize an artist name by cleaning up formatting.
fn normalize_artist_name(name: &str) -> String {
    let cleaned = name.trim();
    let cleaned = MULTI_WHITESPACE.replace_all(cleaned, " ");
    let cleaned = TRAILING_BRACKETS.replace_all(&cleaned, "");
    let cleaned = LEADING_BRACKETS.replace_all(&cleaned, "");
    cleaned.trim().to_string()
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_single_artist() {
        let result = parse("Adele");
        assert_eq!(result.primary_artists, vec!["Adele"]);
        assert!(result.featured_artists.is_empty());
        assert_eq!(result.all_artists, vec!["Adele"]);
    }

    #[test]
    fn test_parse_featuring() {
        let result = parse("Drake feat. Rihanna");
        assert_eq!(result.primary_artists, vec!["Drake"]);
        assert_eq!(result.featured_artists, vec!["Rihanna"]);
        assert_eq!(result.all_artists, vec!["Drake", "Rihanna"]);
    }

    #[test]
    fn test_parse_ft_dot() {
        let result = parse("Post Malone ft. Swae Lee");
        assert_eq!(result.primary_artists, vec!["Post Malone"]);
        assert_eq!(result.featured_artists, vec!["Swae Lee"]);
    }

    #[test]
    fn test_parse_parenthesized_feat() {
        let result = parse("Calvin Harris (feat. Rihanna)");
        assert_eq!(result.primary_artists, vec!["Calvin Harris"]);
        assert_eq!(result.featured_artists, vec!["Rihanna"]);
    }

    #[test]
    fn test_parse_comma_separated() {
        let result = parse("Beyoncé, Jay-Z");
        assert_eq!(result.primary_artists, vec!["Beyoncé", "Jay-Z"]);
    }

    #[test]
    fn test_parse_ampersand_collaboration() {
        // Two separate artists joined by &
        let result = parse("Jay-Z & Kanye West");
        assert_eq!(result.primary_artists, vec!["Jay-Z", "Kanye West"]);
    }

    #[test]
    fn test_known_band_preserved() {
        let result = parse("Simon & Garfunkel");
        assert_eq!(result.primary_artists, vec!["Simon & Garfunkel"]);
        assert!(!result.has_multiple_artists());
    }

    #[test]
    fn test_florence_and_the_machine() {
        let result = parse("Florence + The Machine");
        assert_eq!(result.primary_artists, vec!["Florence + The Machine"]);
    }

    #[test]
    fn test_earth_wind_fire() {
        let _result = parse("Earth, Wind & Fire");
        // Comma splits, but "Wind & Fire" stays together due to & The pattern? No.
        // Actually, the comma is a safe splitter so it splits into ["Earth", "Wind & Fire"]
        // "Wind & Fire" is not a known band, and doesn't match "& The" pattern.
        // So it further splits to ["Earth", "Wind", "Fire"]
        // This is the correct behavior since "Earth, Wind & Fire" is listed as a known band
        // Let's check: the full string is "Earth, Wind & Fire" — is that in the known list?
        // No, but "earth, wind & fire" IS in the list!
        let result = parse("earth, wind & fire");
        assert_eq!(result.primary_artists, vec!["earth, wind & fire"]);
    }

    #[test]
    fn test_tyler_the_creator() {
        let result = parse("Tyler, The Creator");
        // Full string should match known band
        assert_eq!(result.primary_artists, vec!["Tyler, The Creator"]);
    }

    #[test]
    fn test_x_collaboration() {
        let result = parse("Future x Metro Boomin");
        assert_eq!(result.primary_artists, vec!["Future", "Metro Boomin"]);
    }

    #[test]
    fn test_complex_multi_artist() {
        let result = parse("DJ Khaled feat. Drake, Lil Wayne & Rick Ross");
        assert_eq!(result.primary_artists, vec!["DJ Khaled"]);
        assert_eq!(
            result.featured_artists,
            vec!["Drake", "Lil Wayne", "Rick Ross"]
        );
    }

    #[test]
    fn test_is_likely_artist_name() {
        assert!(is_likely_artist_name("The Weeknd"));
        assert!(is_likely_artist_name("Taylor Swift"));
        assert!(!is_likely_artist_name("Song Name (Official Music Video)"));
        assert!(!is_likely_artist_name(
            "A very long description that exceeds the sixty character limit for an artist name yo"
        ));
        assert!(!is_likely_artist_name("Song (1080p HD)"));
    }

    #[test]
    fn test_is_same_artist() {
        assert!(is_same_artist("The Weeknd", "the weeknd"));
        assert!(is_same_artist("The Beatles", "Beatles"));
        assert!(!is_same_artist("Adele", "Drake"));
    }

    #[test]
    fn test_is_unknown_artist() {
        assert!(is_unknown_artist(""));
        assert!(is_unknown_artist("Unknown"));
        assert!(is_unknown_artist("Unknown Artist"));
        assert!(is_unknown_artist("Various Artists"));
        assert!(!is_unknown_artist("Adele"));
    }

    #[test]
    fn test_clean_track_title() {
        assert_eq!(
            clean_track_title("Blinding Lights (feat. Someone)"),
            "Blinding Lights"
        );
        assert_eq!(
            clean_track_title("Hello ft. World"),
            "Hello"
        );
    }

    #[test]
    fn test_user_known_band() {
        // Add a user-defined known band
        add_user_known_band("My Custom Band & Friends Forever");
        let result = parse("My Custom Band & Friends Forever");
        assert_eq!(
            result.primary_artists,
            vec!["My Custom Band & Friends Forever"]
        );
        // Cleanup
        remove_user_known_band("My Custom Band & Friends Forever");
    }

    #[test]
    fn test_extract_artists_from_title() {
        let artists = extract_artists_from_title("Blinding Lights (feat. Doja Cat & SZA)");
        assert_eq!(artists, vec!["Doja Cat", "SZA"]);
    }

    #[test]
    fn test_youtube_channel_management() {
        add_user_youtube_channel("BANGTANTV");
        assert!(is_known_youtube_channel("BANGTANTV"));
        assert!(is_known_youtube_channel("bangtantv"));
        remove_user_youtube_channel("BANGTANTV");
        assert!(!is_known_youtube_channel("BANGTANTV"));
    }
}

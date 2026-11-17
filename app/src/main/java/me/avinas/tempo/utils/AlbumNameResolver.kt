package me.avinas.tempo.utils

/**
 * Decides whether a string reported in an "album" slot is actually a release name.
 *
 * Tempo learns album names from whatever the playing app exposes. MediaSession's
 * `METADATA_KEY_ALBUM` is authoritative, but apps that publish nothing there leave the
 * notification's sub-text/info-text as the only source — and those slots just as often carry
 * the artist, the app's own name, "Now playing", or a video description. Stored verbatim, one
 * of those becomes an album a song can never leave: the row is written once at first listen and
 * a non-blank album is never rewritten afterwards.
 *
 * The rules below only reject strings that cannot plausibly be a release title, so real albums
 * with punctuation, numbers, or long names still pass.
 */
object AlbumNameResolver {

    /** Longer than any real release title; almost certainly a paragraph of metadata. */
    private const val MAX_LENGTH = 150

    /** Exact strings (normalized) that are state labels, never release titles. */
    private val STATE_LABELS =
        setOf(
            "unknown",
            "unknown album",
            "no album",
            "none",
            "null",
            "n/a",
            "na",
            "-",
            "--",
            "now playing",
            "playing now",
            "not playing",
        )

    /** Values media apps put in metadata slots that name the app, not the release. */
    private val APP_LABELS =
        setOf(
            "spotify",
            "youtube",
            "youtube music",
            "yt music",
            "ytmusic",
            "apple music",
            "amazon music",
            "deezer",
            "tidal",
            "soundcloud",
            "plexamp",
        )

    /** Phrases that mark a description rather than a title. */
    private val DESCRIPTION_MARKERS =
        listOf(
            "now playing",
            "playing from",
            "official video",
            "official audio",
            "lyric video",
            "audio only",
            "full album",
        )

    /** Prefixes some apps add to the album slot, e.g. "Album: Discovery". */
    private val ALBUM_PREFIXES = listOf("album:", "album -", "album –", "from the album")

    private const val QUOTES = "\"'“”‘’«»"

    /**
     * Cleans a reported album name, or returns null when the string cannot be one.
     *
     * @param raw what the platform reported
     * @param title the track title it was reported for, used to reject "album" values that are
     *   really the song name
     * @param artist the track artist, used to reject values that are really the artist
     */
    fun clean(
        raw: String?,
        title: String?,
        artist: String?,
    ): String? {
        var value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        for (prefix in ALBUM_PREFIXES) {
            if (value.startsWith(prefix, ignoreCase = true)) {
                value = value.substring(prefix.length).trim()
            }
        }
        value = value.trim(*QUOTES.toCharArray()).trim()
        value = value.replace(Regex("\\s+"), " ").trim()
        if (value.isEmpty() || value.length > MAX_LENGTH) return null

        val key = normalizedKey(value)
        if (key.isEmpty()) return null
        if (key in STATE_LABELS || key in APP_LABELS) return null
        if (looksLikeUrl(value)) return null

        val lower = value.lowercase()
        if (DESCRIPTION_MARKERS.any { lower.contains(it) }) return null

        // A slot that repeats the song or the artist is not naming a release. This is the most
        // common misfire: notifications put the artist in SUB_TEXT when they have no album.
        if (title != null && key == normalizedKey(title)) return null
        if (artist != null && key == normalizedKey(artist)) return null

        return value
    }

    /** True when [album] is a plausible release name for this track. */
    fun isValid(
        album: String?,
        title: String?,
        artist: String?,
    ): Boolean = clean(album, title, artist) != null

    /**
     * Picks the album to store: keeps an already-valid value, otherwise takes the candidate.
     *
     * Used at every write site so a junk or blank album gets upgraded when better metadata
     * arrives, while a valid album never flip-flops between sources.
     */
    fun choose(
        existing: String?,
        candidate: String?,
        title: String?,
        artist: String?,
    ): String? {
        if (isValid(existing, title, artist)) {
            return existing?.trim()
        }
        return clean(candidate, title, artist)
    }

    private fun looksLikeUrl(value: String): Boolean {
        val lower = value.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("content://") ||
            lower.startsWith("file://") ||
            (lower.contains("://") && lower.contains("."))
    }

    /** Case/space/punctuation-insensitive form, so "Album!" and "album" compare equal. */
    private fun normalizedKey(value: String): String =
        value
            .lowercase()
            .filter { it.isLetterOrDigit() || it == ' ' }
            .replace(Regex("\\s+"), " ")
            .trim()
}

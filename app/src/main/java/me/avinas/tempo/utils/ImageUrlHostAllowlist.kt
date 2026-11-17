package me.avinas.tempo.utils

/**
 * Host allowlist for bulk background image fetching.
 *
 * Restored backups are untrusted input: the `hotlinkedUrls` list inside a backup
 * file is attacker-controlled data. [me.avinas.tempo.worker.PostRestoreCacheWorker]
 * silently fetches up to 200 of those URLs in the background, so a crafted backup
 * could otherwise abuse Tempo as a covert "phone-home" beacon (the fetch leaks the
 * device IP to an attacker-chosen server, plus User-Agent metadata).
 *
 * Restricting the bulk pre-cache to the music-art CDN hosts Tempo legitimately
 * links to closes that vector. Normal UI image loading is untouched: those URLs
 * are only fetched one at a time while the user is actually looking at a track,
 * artist, or album.
 *
 * Hosts mirror what the app's scrapers/enrichment services emit and what
 * [me.avinas.tempo.ui.components.CachedAsyncImage] already special-cases for
 * cache-key normalization.
 */
object ImageUrlHostAllowlist {

    private val ALLOWED_EXACT_HOSTS = setOf(
        "archive.org",                  // Cover Art Archive via archive.org
        "coverartarchive.org",          // Cover Art Archive direct host
        "musicbrainz.org",              // MusicBrainz
        "lastfm.freetls.fastly.net",    // Last.fm image CDN
    )

    // Suffix-anchored (a leading dot) so "notscdn.co.attacker.com" can never pass.
    private val ALLOWED_HOST_SUFFIXES = listOf(
        ".scdn.co",                     // Spotify album/artist art (i.scdn.co, thisis-images.scdn.co, ...)
        ".spotifycdn.com",              // Spotify CDN variant
        ".coverartarchive.org",         // Cover Art Archive (MusicBrainz)
        ".mzstatic.com",                // Apple / iTunes CDN (is1-ssl.mzstatic.com, ...)
        ".dzcdn.net",                   // Deezer CDN (e-cdns-images.dzcdn.net, ...)
        ".ytimg.com",                   // YouTube thumbnails
        ".discogs.com",                 // Discogs images
        ".musicbrainz.org",             // MusicBrainz
    )

    /**
     * True when [url] is an http(s) URL whose host belongs to a known music-art CDN.
     * Conservative: any URL that cannot be parsed unambiguously is rejected.
     */
    fun isAllowed(url: String): Boolean {
        val host = extractHost(url) ?: return false
        if (host in ALLOWED_EXACT_HOSTS) return true
        return ALLOWED_HOST_SUFFIXES.any(host::endsWith)
    }

    /** Returns only the URLs that pass [isAllowed], preserving order and duplicates. */
    fun filterAllowed(urls: Collection<String>): List<String> =
        urls.filter(::isAllowed)

    /**
     * Extracts the lowercase host from an http(s) URL without android.net.Uri so the
     * check is also usable (and unit-testable) in plain JVM code paths.
     * Returns null for anything that is not a plain http/https URL with a host.
     */
    internal fun extractHost(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null

        val scheme = url.take(schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null

        var authority = url.substring(schemeEnd + 3)
        val terminator = authority.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (terminator >= 0) authority = authority.substring(0, terminator)

        // Userinfo ("https://user:pass@host/...") must never be mistaken for the host.
        val atIndex = authority.lastIndexOf('@')
        if (atIndex >= 0) authority = authority.substring(atIndex + 1)

        // Strip the port; bracketed IPv6 literals never match the allowlist anyway.
        if (!authority.startsWith("[")) {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) authority = authority.substring(0, colon)
        }

        val host = authority.trim().lowercase()
        return host.ifEmpty { null }
    }
}

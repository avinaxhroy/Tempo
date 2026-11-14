package me.avinas.tempo.data.enrichment

import android.util.Log
import me.avinas.tempo.data.remote.deezer.DeezerApi
import me.avinas.tempo.data.remote.deezer.DeezerTrack
import me.avinas.tempo.utils.ArtistParser
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * Fetches 30-second audio previews from Deezer's search API
 * as fallback when Spotify and iTunes have no sample.
 */
@Singleton
class DeezerEnrichmentService @Inject constructor(
    private val deezerApi: DeezerApi
) {
    companion object {
        private const val TAG = "DeezerEnrichment"
        private const val RATE_LIMIT_DELAY_MS = 500L // Be generous with rate limits on public API
    }
    
    sealed class DeezerResult {
        data class Success(
            val previewUrl: String? = null,
            val deezerId: Long,
            val link: String,
            val coverMd5: String?,
            // Album cover art URLs (Deezer provides multiple sizes)
            val albumArtUrl: String? = null,      // Best quality (XL or Big)
            val albumArtUrlSmall: String? = null, // Small
            val albumArtUrlLarge: String? = null, // XL
            // Artist image URL
            val artistImageUrl: String? = null,   // Best quality (XL or Big)
            // Album title for fallback
            val albumTitle: String? = null
        ) : DeezerResult()
        
        object NotFound : DeezerResult()
        data class Error(val message: String) : DeezerResult()
    }
    
    data class CoverArtResult(
        val albumArtUrl: String,
        val albumArtUrlSmall: String? = null,
        val albumArtUrlLarge: String? = null,
        val albumTitle: String? = null
    )

    /**
     * Search Deezer specifically for album artwork. This lookup is independent of
     * preview availability, so a valid cover can still be offered when Deezer does
     * not expose a 30-second sample for the track.
     */
    suspend fun searchAlbumArt(
        artist: String,
        track: String,
        album: String? = null,
        preserveExplicitTrackVersion: Boolean = false,
    ): CoverArtResult? {
        if (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist)) return null

        try {
            // Track metadata is interpolated into Deezer's `artist:"..." track:"..."`
            // syntax, so quotes and backslashes are neutralised first: a crafted title
            // must not be able to close a field early and append its own filters.
            val cleanArtist = sanitizeDeezerSearchValue(
                me.avinas.tempo.utils.ArtistParser.getPrimaryArtist(artist)
            )
            val titleVariants =
                (
                    if (preserveExplicitTrackVersion) coverSearchTitleVariants(track)
                    else listOf(me.avinas.tempo.utils.ArtistParser.cleanTrackTitle(track))
                ).map(::sanitizeDeezerSearchValue)
            var hadSuccessfulStructuredResponse = false
            var lastStructuredError: Int? = null

            for ((index, titleVariant) in titleVariants.withIndex()) {
                if (index > 0) delay(RATE_LIMIT_DELAY_MS)
                val structuredQuery = "artist:\"$cleanArtist\" track:\"$titleVariant\""
                val structured = deezerApi.searchTracks(structuredQuery)
                if (structured.isSuccessful) {
                    hadSuccessfulStructuredResponse = true
                    findBestCoverMatch(
                        results = structured.body()?.data.orEmpty(),
                        artist = artist,
                        track = track,
                        album = album,
                    )?.let { return it.toCoverArtResult() }
                } else {
                    lastStructuredError = structured.code()
                }
            }

            // Structured search may return near matches without the requested track.
            // Try relaxed queries in the same order: explicit version first, then
            // cleaned fallback. Identity validation still decides what may be shown.
            var hadSuccessfulLooseResponse = false
            var lastLooseError: Int? = null
            for (titleVariant in titleVariants) {
                delay(RATE_LIMIT_DELAY_MS)
                val loose = deezerApi.searchTracks("$titleVariant $cleanArtist")
                if (loose.isSuccessful) {
                    hadSuccessfulLooseResponse = true
                    findBestCoverMatch(
                        results = loose.body()?.data.orEmpty(),
                        artist = artist,
                        track = track,
                        album = album,
                    )?.let { return it.toCoverArtResult() }
                } else {
                    lastLooseError = loose.code()
                }
            }

            if (!hadSuccessfulStructuredResponse && !hadSuccessfulLooseResponse) {
                throw IllegalStateException(
                    "Deezer API error: ${lastStructuredError ?: "unknown"} / " +
                        "${lastLooseError ?: "unknown"}"
                )
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Deezer cover-art search error", e)
            throw e
        }
    }

    private fun findBestCoverMatch(
        results: List<DeezerTrack>,
        artist: String,
        track: String,
        album: String?,
    ): DeezerTrack? {
        val expectedAlbum = album
            ?.let(me.avinas.tempo.utils.ArtistParser::normalizeForSearch)
            ?.takeIf { it.isNotBlank() }

        return results
            .asSequence()
            .filter { result ->
                val hasArt = !result.album.coverXl.isNullOrBlank() ||
                    !result.album.coverBig.isNullOrBlank() ||
                    !result.album.coverMedium.isNullOrBlank()
                if (!hasArt) return@filter false

                val artistMatches = isSafeCoverArtistMatch(
                    expectedArtist = artist,
                    candidateArtist = result.artist.name,
                )
                if (!artistMatches) return@filter false

                isSafeCoverTrackTitleMatch(track, result.title)
            }
            .sortedByDescending { result ->
                var score = 0
                val candidateAlbum = me.avinas.tempo.utils.ArtistParser.normalizeForSearch(
                    result.album.title
                )
                if (expectedAlbum != null && candidateAlbum == expectedAlbum) score += 4
                if (!result.album.coverXl.isNullOrBlank()) score += 2
                if (!result.album.coverBig.isNullOrBlank()) score += 1
                score
            }
            .firstOrNull()
    }

    private fun DeezerTrack.toCoverArtResult(): CoverArtResult {
        val bestUrl = album.coverXl
            ?: album.coverBig
            ?: album.coverMedium
            ?: error("Validated Deezer match unexpectedly has no artwork")
        return CoverArtResult(
            albumArtUrl = bestUrl,
            albumArtUrlSmall = album.coverSmall,
            albumArtUrlLarge = album.coverXl ?: album.coverBig,
            albumTitle = album.title,
        )
    }

    /**
     * Search for a track and get its preview URL, album art, and artist image.
     */
    suspend fun getAudioPreview(
        artist: String,
        track: String,
        album: String? = null
    ): DeezerResult {
        if (ArtistParser.isUnknownArtist(artist)) return DeezerResult.NotFound
        return try {
            val cleanArtist = sanitizeDeezerSearchValue(ArtistParser.getPrimaryArtist(artist))
            val cleanTrack = sanitizeDeezerSearchValue(ArtistParser.cleanTrackTitle(track))
            if (cleanArtist.isBlank() || cleanTrack.isBlank()) return DeezerResult.NotFound
            val query = "artist:\"$cleanArtist\" track:\"$cleanTrack\""
            Log.d(TAG, "Searching Deezer: $query")

            val response = deezerApi.searchTracks(query, limit = 25)
            if (!response.isSuccessful) {
                return DeezerResult.Error("API Error: ${response.code()}")
            }

            val results = response.body()?.data.orEmpty()
            val match = findPreferredDeezerArtwork(results, artist, track, album)
                ?: if (results.isEmpty()) searchLoose(artist, track, album) else null

            if (match == null) {
                return DeezerResult.NotFound
            }

            Log.i(TAG, "Found Deezer match: '${match.title}' by '${match.artist.name}'")

            DeezerResult.Success(
                previewUrl = match.preview?.takeIf { it.isNotBlank() },
                deezerId = match.id,
                link = match.link,
                coverMd5 = match.album.md5Image,
                albumArtUrl = match.album.coverXl ?: match.album.coverBig
                    ?: match.album.coverMedium ?: match.album.coverSmall,
                albumArtUrlSmall = match.album.coverSmall,
                albumArtUrlLarge = match.album.coverXl ?: match.album.coverBig,
                artistImageUrl = match.artist.pictureXl ?: match.artist.pictureBig
                    ?: match.artist.pictureMedium,
                albumTitle = match.album.title,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Deezer search error", e)
            DeezerResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun searchLoose(artist: String, track: String, album: String?): DeezerTrack? {
        return try {
            delay(RATE_LIMIT_DELAY_MS)
            val cleanArtist = sanitizeDeezerSearchValue(ArtistParser.getPrimaryArtist(artist))
            val cleanTrack = sanitizeDeezerSearchValue(ArtistParser.cleanTrackTitle(track))
            val response = deezerApi.searchTracks("$cleanTrack $cleanArtist", limit = 10)
            if (!response.isSuccessful) return null
            val results = response.body()?.data.orEmpty()
            findPreferredDeezerArtwork(results, artist, track, album)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Make [value] safe to interpolate into a Deezer search query.
 *
 * Deezer's advanced search uses quoted fields (`artist:"..." track:"..."`), and the
 * values come from untrusted track metadata. A title containing a double quote
 * closes the field early, so `X" artist:"Y` would let crafted text append its own
 * filters instead of being treated as a literal title.
 *
 * Quotes and backslashes are stripped rather than backslash-escaped: Deezer
 * documents no escape sequence for them, so emitting `\"` would leak a literal
 * backslash into the search term and break matching for legitimate titles such as
 * `Say "Hello"`.
 */
internal fun sanitizeDeezerSearchValue(value: String): String =
    value
        .replace('"', ' ')
        .replace('\\', ' ')
        .replace(WHITESPACE_RUN, " ")
        .trim()

private val WHITESPACE_RUN = Regex("""\s+""")

/**
 * Strict matching for preferred covers; never take Deezer's first result simply
 * because it has a preview. This deliberately does not alter legacy preview lookup.
 */
internal fun findPreferredDeezerArtwork(
    results: List<DeezerTrack>,
    artist: String,
    title: String,
    album: String?,
): DeezerTrack? {
    val expectedTitle = ArtistParser.normalizeForSearch(title)
    val cleanedTitle = ArtistParser.normalizeForSearch(ArtistParser.cleanTrackTitle(title))
    val expectedAlbum = album?.let(ArtistParser::normalizeForSearch).orEmpty()
    if (expectedTitle.isBlank() || ArtistParser.isUnknownArtist(artist)) return null
    val primaryArtist = ArtistParser.getPrimaryArtist(artist)
    return results
        .asSequence()
        .filter { candidate ->
            val a = candidate.album
            val hasCover = listOf(a.coverXl, a.coverBig, a.coverMedium, a.coverSmall)
                .any { !it.isNullOrBlank() }
            val candidateArtistMatches = ArtistParser.isStrictSameArtist(primaryArtist, candidate.artist.name) ||
                ArtistParser.isStrictSameArtist(primaryArtist, ArtistParser.getPrimaryArtist(candidate.artist.name))
            if (!hasCover || !candidateArtistMatches) {
                false
            } else {
                val candidates = listOf(candidate.title, candidate.titleShort)
                    .map(ArtistParser::normalizeForSearch)
                val exact = expectedTitle in candidates
                val cleanedMatch = cleanedTitle.isNotBlank() &&
                    listOf(candidate.title, candidate.titleShort).any {
                        ArtistParser.normalizeForSearch(ArtistParser.cleanTrackTitle(it)) == cleanedTitle
                    }
                exact || cleanedMatch
            }
        }
        .sortedWith(
            compareByDescending<DeezerTrack> {
                expectedAlbum.isNotBlank() &&
                    ArtistParser.normalizeForSearch(it.album.title) == expectedAlbum
            }.thenByDescending {
                ArtistParser.normalizeForSearch(it.title) == expectedTitle ||
                    ArtistParser.normalizeForSearch(it.titleShort) == expectedTitle
            },
        )
        .firstOrNull()
}

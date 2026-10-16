package me.avinas.tempo.data.enrichment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.Track
import javax.inject.Inject
import javax.inject.Singleton

enum class CoverArtProvider {
    CURRENT,
    SPOTIFY,
    APPLE_MUSIC,
    MUSICBRAINZ,
    LASTFM,
    DEEZER,
}

enum class CoverArtLookupStatus {
    LOADING,
    FOUND,
    NOT_FOUND,
    UNAVAILABLE,
    ERROR,
}

data class CoverArtCandidate(
    val provider: CoverArtProvider,
    val albumArtUrl: String,
    val albumArtUrlSmall: String? = null,
    val albumArtUrlLarge: String? = null,
    val albumTitle: String? = null,
    val isCurrent: Boolean = false,
)

data class CoverArtLookupResult(
    val provider: CoverArtProvider,
    val status: CoverArtLookupStatus,
    val candidate: CoverArtCandidate? = null,
    val message: String? = null,
)

internal fun buildCurrentCoverArtCandidate(
    track: Track,
    currentMetadata: EnrichedMetadata?,
): CoverArtCandidate? {
    val current = track.albumArtUrl?.takeIf { it.isNotBlank() } ?: return null
    val normalizedCurrent = MusicBrainzEnrichmentService.fixHttpUrl(current) ?: current
    val metadataMatchesCurrent =
        currentMetadata != null &&
            listOf(
                currentMetadata.albumArtUrl,
                currentMetadata.albumArtUrlSmall,
                currentMetadata.albumArtUrlLarge,
            ).any { metadataUrl ->
                val normalizedMetadata = MusicBrainzEnrichmentService.fixHttpUrl(metadataUrl)
                !normalizedMetadata.isNullOrBlank() && normalizedMetadata == normalizedCurrent
            }

    return CoverArtCandidate(
        provider = CoverArtProvider.CURRENT,
        albumArtUrl = current,
        albumArtUrlSmall = currentMetadata?.albumArtUrlSmall
            ?.takeIf { metadataMatchesCurrent && it.isNotBlank() },
        albumArtUrlLarge = currentMetadata?.albumArtUrlLarge
            ?.takeIf { metadataMatchesCurrent && it.isNotBlank() }
            ?: current,
        albumTitle = currentMetadata?.albumTitle ?: track.album,
        isCurrent = true,
    )
}

/**
 * User-driven artwork lookup. Each provider is queried independently and no database
 * state is changed until the user explicitly selects a candidate.
 */
@Singleton
class CoverArtPickerService @Inject constructor(
    private val spotifyEnrichmentService: SpotifyEnrichmentService,
    private val iTunesEnrichmentService: ITunesEnrichmentService,
    private val musicBrainzEnrichmentService: MusicBrainzEnrichmentService,
    private val lastFmEnrichmentService: LastFmEnrichmentService,
    private val deezerEnrichmentService: DeezerEnrichmentService,
) {
    companion object {
        const val PROVIDER_LOOKUP_TIMEOUT_MS = 15_000L

        val REMOTE_PROVIDERS = listOf(
            CoverArtProvider.SPOTIFY,
            CoverArtProvider.APPLE_MUSIC,
            CoverArtProvider.MUSICBRAINZ,
            CoverArtProvider.DEEZER,
            CoverArtProvider.LASTFM,
        )
    }

    fun currentCandidate(
        track: Track,
        currentMetadata: EnrichedMetadata? = null,
    ): CoverArtCandidate? =
        buildCurrentCoverArtCandidate(
            track = track,
            currentMetadata = currentMetadata,
        )

    suspend fun searchProvider(
        provider: CoverArtProvider,
        track: Track,
        currentMetadata: EnrichedMetadata?,
    ): CoverArtLookupResult {
        val albumHint = track.album ?: currentMetadata?.albumTitle

        val lookup = try {
            withTimeoutOrNull(PROVIDER_LOOKUP_TIMEOUT_MS) {
                when (provider) {
                    CoverArtProvider.CURRENT -> {
                        val candidate = currentCandidate(track, currentMetadata)
                        CoverArtLookupResult(
                            provider = provider,
                            status = if (candidate != null) CoverArtLookupStatus.FOUND else CoverArtLookupStatus.NOT_FOUND,
                            candidate = candidate,
                        )
                    }

                    CoverArtProvider.SPOTIFY ->
                        when (val result = spotifyEnrichmentService.fetchCoverArtForPicker(track, currentMetadata)) {
                            is SpotifyEnrichmentService.SpotifyCoverArtResult.Success ->
                                CoverArtLookupResult(
                                    provider = provider,
                                    status = CoverArtLookupStatus.FOUND,
                                    candidate = CoverArtCandidate(
                                        provider = provider,
                                        albumArtUrl = result.albumArtUrl,
                                        albumArtUrlLarge = result.albumArtUrl,
                                        albumTitle = null,
                                    ),
                                )
                            SpotifyEnrichmentService.SpotifyCoverArtResult.Unavailable ->
                                CoverArtLookupResult(provider, CoverArtLookupStatus.UNAVAILABLE)
                            SpotifyEnrichmentService.SpotifyCoverArtResult.NotFound ->
                                CoverArtLookupResult(provider, CoverArtLookupStatus.NOT_FOUND)
                            is SpotifyEnrichmentService.SpotifyCoverArtResult.Error ->
                                CoverArtLookupResult(
                                    provider = provider,
                                    status = CoverArtLookupStatus.ERROR,
                                    message = result.message,
                                )
                        }

                    CoverArtProvider.APPLE_MUSIC ->
                        when (
                            val result = iTunesEnrichmentService.searchAlbumArt(
                                artist = track.artist,
                                album = albumHint,
                                track = track.title,
                                preserveExplicitTrackVersion = true,
                            )
                        ) {
                            is ITunesEnrichmentService.iTunesResult.Success ->
                                CoverArtLookupResult(
                                    provider = provider,
                                    status = CoverArtLookupStatus.FOUND,
                                    candidate = CoverArtCandidate(
                                        provider = provider,
                                        albumArtUrl = result.albumArtUrlLarge ?: result.albumArtUrl,
                                        albumArtUrlSmall = result.albumArtUrlSmall,
                                        albumArtUrlLarge = result.albumArtUrlLarge,
                                        albumTitle = result.albumTitle,
                                    ),
                                )
                            ITunesEnrichmentService.iTunesResult.NotFound ->
                                CoverArtLookupResult(provider, CoverArtLookupStatus.NOT_FOUND)
                            is ITunesEnrichmentService.iTunesResult.Error ->
                                CoverArtLookupResult(
                                    provider = provider,
                                    status = CoverArtLookupStatus.ERROR,
                                    message = result.message,
                                )
                        }

                    CoverArtProvider.MUSICBRAINZ ->
                        mapMusicBrainzCoverSearchResult(
                            musicBrainzEnrichmentService.searchCoverArt(track, currentMetadata)
                        )

                    CoverArtProvider.LASTFM ->
                        mapLastFmCoverSearchResult(
                            result = lastFmEnrichmentService.searchTrackInfo(
                                title = track.title,
                                artist = track.artist,
                            ),
                            expectedTitle = track.title,
                            expectedArtist = track.artist,
                        )

                    CoverArtProvider.DEEZER -> {
                        val result = deezerEnrichmentService.searchAlbumArt(
                            artist = track.artist,
                            track = track.title,
                            album = albumHint,
                            preserveExplicitTrackVersion = true,
                        )
                        if (result == null) {
                            CoverArtLookupResult(provider, CoverArtLookupStatus.NOT_FOUND)
                        } else {
                            CoverArtLookupResult(
                                provider = provider,
                                status = CoverArtLookupStatus.FOUND,
                                candidate = CoverArtCandidate(
                                    provider = provider,
                                    albumArtUrl = result.albumArtUrlLarge ?: result.albumArtUrl,
                                    albumArtUrlSmall = result.albumArtUrlSmall,
                                    albumArtUrlLarge = result.albumArtUrlLarge,
                                    albumTitle = result.albumTitle,
                                ),
                            )
                        }
                    }
                }
            } ?: CoverArtLookupResult(
                provider = provider,
                status = CoverArtLookupStatus.ERROR,
                message = "Search timed out",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CoverArtLookupResult(
                provider = provider,
                status = CoverArtLookupStatus.ERROR,
                message = e.message,
            )
        }

        // Some legacy enrichment helpers catch broad exceptions internally. Re-check
        // coroutine cancellation before returning so a dismissed picker can never
        // publish a stale provider result.
        currentCoroutineContext().ensureActive()
        return lookup
    }
}

internal fun mapMusicBrainzCoverSearchResult(
    result: MusicBrainzEnrichmentService.CoverArtSearchResult,
): CoverArtLookupResult =
    when (result) {
        is MusicBrainzEnrichmentService.CoverArtSearchResult.Success -> {
            val artwork = result.artwork
            CoverArtLookupResult(
                provider = CoverArtProvider.MUSICBRAINZ,
                status = CoverArtLookupStatus.FOUND,
                candidate = CoverArtCandidate(
                    provider = CoverArtProvider.MUSICBRAINZ,
                    albumArtUrl = artwork.albumArtUrlLarge ?: artwork.albumArtUrl,
                    albumArtUrlSmall = artwork.albumArtUrlSmall,
                    albumArtUrlLarge = artwork.albumArtUrlLarge,
                    albumTitle = artwork.albumTitle,
                ),
            )
        }
        MusicBrainzEnrichmentService.CoverArtSearchResult.NotFound ->
            CoverArtLookupResult(
                provider = CoverArtProvider.MUSICBRAINZ,
                status = CoverArtLookupStatus.NOT_FOUND,
            )
        is MusicBrainzEnrichmentService.CoverArtSearchResult.Error ->
            CoverArtLookupResult(
                provider = CoverArtProvider.MUSICBRAINZ,
                status = CoverArtLookupStatus.ERROR,
                message = result.message,
            )
    }

internal fun mapLastFmCoverSearchResult(
    result: LastFmEnrichmentService.LastFmResult,
    expectedTitle: String,
    expectedArtist: String,
): CoverArtLookupResult =
    when (result) {
        is LastFmEnrichmentService.LastFmResult.Success -> {
            val identityMatches =
                result.trackTitle?.let { isSafeCoverTrackTitleMatch(expectedTitle, it) } == true &&
                    result.artistName?.let { isSafeCoverArtistMatch(expectedArtist, it) } == true
            val url = result.albumArtUrl?.takeIf { it.isNotBlank() }

            if (!identityMatches || url == null) {
                CoverArtLookupResult(
                    provider = CoverArtProvider.LASTFM,
                    status = CoverArtLookupStatus.NOT_FOUND,
                )
            } else {
                CoverArtLookupResult(
                    provider = CoverArtProvider.LASTFM,
                    status = CoverArtLookupStatus.FOUND,
                    candidate = CoverArtCandidate(
                        provider = CoverArtProvider.LASTFM,
                        albumArtUrl = url,
                        albumArtUrlLarge = url,
                        albumTitle = result.albumTitle,
                    ),
                )
            }
        }
        LastFmEnrichmentService.LastFmResult.NotConfigured ->
            CoverArtLookupResult(CoverArtProvider.LASTFM, CoverArtLookupStatus.UNAVAILABLE)
        LastFmEnrichmentService.LastFmResult.TrackNotFound,
        LastFmEnrichmentService.LastFmResult.AlreadyHasData ->
            CoverArtLookupResult(CoverArtProvider.LASTFM, CoverArtLookupStatus.NOT_FOUND)
        is LastFmEnrichmentService.LastFmResult.Error ->
            CoverArtLookupResult(
                provider = CoverArtProvider.LASTFM,
                status = CoverArtLookupStatus.ERROR,
                message = result.message,
            )
    }

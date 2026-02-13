package me.avinas.tempo.data.enrichment

import android.util.Log
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.utils.ArtistParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.firstOrNull
import me.avinas.tempo.ui.onboarding.dataStore

/**
 * Represents a gap in metadata that needs to be filled.
 */
data class EnrichmentGap(
    val missingAlbumArt: Boolean = false,
    val missingGenres: Boolean = false,
    val missingAudioFeatures: Boolean = false,
    val missingArtistImage: Boolean = false,
    val missingPreviewUrl: Boolean = false
) {
    fun isEmpty(): Boolean = !missingAlbumArt && !missingGenres && !missingAudioFeatures && !missingArtistImage && !missingPreviewUrl
}

/**
 * Interface for any source that can provide enrichment data.
 */
interface EnrichmentSource {
    val name: String
    val priority: Int // Lower value = higher priority

    /**
     * Check if this source can potentially fill the given gap.
     */
    fun canProvide(gap: EnrichmentGap): Boolean

    /**
     * Attempt to enrich the track.
     * @return The updated metadata, or null if no changes were made.
     */
    suspend fun enrich(track: Track, currentMetadata: EnrichedMetadata?): EnrichedMetadata?
}

// ============================================================================
// CONCRETE STRATEGIES
// ============================================================================

@Singleton
class SpotifyEnrichmentSource @Inject constructor(
    private val spotifyService: SpotifyEnrichmentService,
    private val enrichedMetadataDao: me.avinas.tempo.data.local.dao.EnrichedMetadataDao
) : EnrichmentSource {
    override val name = "Spotify"
    override val priority = 1

    override fun canProvide(gap: EnrichmentGap): Boolean {
        // Spotify is the best source for everything EXCEPT genres (MusicBrainz is better)
        // providing we have a connection
        return spotifyService.isAvailable() && (gap.missingAlbumArt || gap.missingAudioFeatures || gap.missingArtistImage || gap.missingPreviewUrl)
    }

    override suspend fun enrich(track: Track, currentMetadata: EnrichedMetadata?): EnrichedMetadata? {
        Log.d("EnrichmentSource", "Attempting Spotify enrichment for ${track.title}")
        
        // Step 1: Enrich basic metadata and audio features
        val result = spotifyService.enrichTrack(track, currentMetadata)
        
        if (result !is SpotifyEnrichmentService.SpotifyEnrichmentResult.Success) {
            return null
        }
        
        // The service updates the DB internally, so we re-fetch the updated metadata
        var metadata = enrichedMetadataDao.forTrackSync(track.id)
        
        if (metadata == null) return null // Should not happen if success
        
        // Step 2: Fetch artist image if missing and we have a Spotify Artist ID
        if (metadata.spotifyArtistImageUrl == null && metadata.spotifyArtistId != null) {
            Log.d("EnrichmentSource", "Fetching missing artist image for ${metadata.spotifyArtistId}")
            val artistImageUrl = spotifyService.fetchAndCacheArtistImage(metadata.spotifyArtistId)
            
            if (artistImageUrl != null) {
                metadata = metadata.copy(spotifyArtistImageUrl = artistImageUrl)
            }
        }
        
        return metadata
    }
    
    fun isAvailable(): Boolean = spotifyService.isAvailable()
}

/**
 * Pre-enrichment source that fetches MusicBrainz IDs from Last.fm.
 * 
 * This runs BEFORE MusicBrainz to improve accuracy of MB lookups.
 * When Last.fm has MBIDs (common for popular tracks and users with linked accounts),
 * MusicBrainz can fetch directly by ID instead of searching, which is:
 * - Faster (no search required)
 * - More accurate (no fuzzy matching issues)
 * - More reliable (exact ID match)
 * 
 * Priority 2 means it runs after Spotify (which might already have accurate data)
 * but before MusicBrainz (which benefits from MBIDs).
 */
@Singleton
class LastFmMbidPreEnrichmentSource @Inject constructor(
    private val lastFmService: LastFmEnrichmentService
) : EnrichmentSource {
    override val name = "Last.fm MBID Lookup"
    override val priority = 2 // Run before MusicBrainz (priority 3)

    override fun canProvide(gap: EnrichmentGap): Boolean {
        // Only provide if we're missing genres or album art (which MusicBrainz will handle)
        // This is a "helper" source that improves MusicBrainz accuracy
        return lastFmService.isAvailable() && (gap.missingGenres || gap.missingAlbumArt)
    }

    override suspend fun enrich(track: Track, currentMetadata: EnrichedMetadata?): EnrichedMetadata? {
        if (currentMetadata == null) return null
        
        // Skip if we already have MBIDs
        if (currentMetadata.musicbrainzRecordingId != null) {
            return null
        }
        
        // Try to get MBIDs from Last.fm
        return lastFmService.preEnrichWithMbids(track, currentMetadata)
    }
}

@Singleton
class MusicBrainzEnrichmentSource @Inject constructor(
    private val musicBrainzService: MusicBrainzEnrichmentService
) : EnrichmentSource {
    override val name = "MusicBrainz"
    override val priority = 3

    override fun canProvide(gap: EnrichmentGap): Boolean {
        // Primary source for generic metadata and genres
        return gap.missingGenres || gap.missingAlbumArt || gap.missingArtistImage
    }

    override suspend fun enrich(track: Track, currentMetadata: EnrichedMetadata?): EnrichedMetadata? {
        // MusicBrainz logic typically creates new metadata if none exists, or supplements
        // In this modular flow, we might need to handle both.
        // The service's enrichTrack handles both creation and update/supplement.
        val result = musicBrainzService.enrichTrack(track, forceRefresh = false)
        return if (result is MusicBrainzEnrichmentService.EnrichmentResult.Success) {
            result.metadata
        } else {
            // If we already had metadata and MB search failed, we return currentMetadata (no change), or null?
            // The interface expects "updated metadata or null if no changes".
            // If MB fails, we return null so the worker continues to next source.
            null
        }
    }
}

@Singleton
class LastFmEnrichmentSource @Inject constructor(
    private val lastFmService: LastFmEnrichmentService
) : EnrichmentSource {
    override val name = "Last.fm"
    override val priority = 4

    override fun canProvide(gap: EnrichmentGap): Boolean {
        return lastFmService.isAvailable() && (gap.missingGenres || gap.missingArtistImage)
    }

    override suspend fun enrich(track: Track, currentMetadata: EnrichedMetadata?): EnrichedMetadata? {
        if (currentMetadata == null) return null // Last.fm usually supplements existing data
        
        val result = lastFmService.supplementMetadata(track, currentMetadata)
        return if (result is LastFmEnrichmentService.LastFmResult.Success) {
            currentMetadata
        } else {
            null
        }
    }
}

@Singleton
class ITunesEnrichmentSource @Inject constructor(
    private val iTunesService: ITunesEnrichmentService,
    private val metadataDao: me.avinas.tempo.data.local.dao.EnrichedMetadataDao,
    private val artistDao: me.avinas.tempo.data.local.dao.ArtistDao
) : EnrichmentSource {
    override val name = "iTunes"
    override val priority = 5

    override fun canProvide(gap: EnrichmentGap): Boolean {
        // iTunes is great for cover art, genres, and artist images
        return gap.missingAlbumArt || gap.missingGenres || gap.missingArtistImage || gap.missingPreviewUrl
    }

    override suspend fun enrich(track: Track, currentMetadata: EnrichedMetadata?): EnrichedMetadata? {
        // iTunes enrichment requires basic metadata to search effectively
        // If currentMetadata is null, we can still search by track/artist
        
        val result = iTunesService.searchAlbumArt(
            artist = track.artist,
            album = track.album,
            track = track.title
        )

        return if (result is ITunesEnrichmentService.iTunesResult.Success) {
            val base = currentMetadata ?: EnrichedMetadata(trackId = track.id)
            
            // Check if we should update album art based on source priority
            // iTunes (priority 4) can replace LOCAL (priority 2) and DEEZER (priority 3)
            val shouldUpdateArt = base.albumArtUrl.isNullOrBlank() || 
                base.albumArtSource.shouldBeReplacedBy(AlbumArtSource.ITUNES)
            
            // Merge logic with source-aware album art handling
            val updated = base.copy(
                albumArtUrl = if (shouldUpdateArt && result.albumArtUrl.isNotBlank()) result.albumArtUrl else base.albumArtUrl,
                albumArtUrlSmall = if (shouldUpdateArt) result.albumArtUrlSmall ?: base.albumArtUrlSmall else base.albumArtUrlSmall,
                albumArtUrlLarge = if (shouldUpdateArt) result.albumArtUrlLarge ?: base.albumArtUrlLarge else base.albumArtUrlLarge,
                albumArtSource = if (shouldUpdateArt && result.albumArtUrl.isNotBlank()) AlbumArtSource.ITUNES else base.albumArtSource,
                albumTitle = base.albumTitle ?: result.albumTitle,
                genres = if (base.genres.isEmpty() && result.genre != null) listOf(result.genre) else base.genres,
                releaseYear = base.releaseYear ?: result.releaseYear,
                releaseDateFull = base.releaseDateFull ?: result.releaseDateFull,
                trackDurationMs = base.trackDurationMs ?: result.durationMs,
                appleMusicUrl = base.appleMusicUrl ?: result.appleMusicUrl,
                previewUrl = base.previewUrl ?: result.previewUrl,
                artistCountry = base.artistCountry ?: result.artistCountry,
                cacheTimestamp = System.currentTimeMillis(),
                lastEnrichmentAttempt = System.currentTimeMillis()
            )
            
            if (shouldUpdateArt && result.albumArtUrl.isNotBlank()) {
                Log.d("EnrichmentSource", "iTunes: Replacing ${base.albumArtSource} album art with ITUNES source")
            }
            
            // Handle artist images for ALL artists
            // This ensures secondary artists (feat. X) also get their images enriched and saved
            // Returns the primary artist's image URL (if found) to avoid a duplicate fetch
            val primaryArtistImageUrl = enrichAndPersistAllArtists(track.artist, result)
            
            // Use the primary artist image for the track metadata if we don't have one yet
            var finalMetadata = updated
            if (base.iTunesArtistImageUrl == null && primaryArtistImageUrl != null) {
                finalMetadata = updated.copy(iTunesArtistImageUrl = primaryArtistImageUrl)
            }
            
            metadataDao.upsert(finalMetadata)
            finalMetadata
        } else {
            null
        }
    }
    
    /**
     * Persist artist images for ALL artists on the track to the artists table.
     * This prevents the Stats screen from needing to make redundant iTunes API calls.
     */
    /**
     * Persist artist images for ALL artists on the track to the artists table.
     * Returns the primary artist's image URL (if found) to avoid duplicate fetches.
     */
    private suspend fun enrichAndPersistAllArtists(trackArtistString: String, result: ITunesEnrichmentService.iTunesResult.Success): String? {
        // 1. Parse all artists from the track string (e.g. "Artist A & Artist B")
        val allArtists = ArtistParser.getAllArtists(trackArtistString)
        val primaryArtist = ArtistParser.getPrimaryArtist(trackArtistString)
        var primaryArtistImageUrl: String? = null

        Log.d("EnrichmentSource", "Enriching images for artists: $allArtists")

        for (artistName in allArtists) {
            try {
                // Check if we already have an image for this artist
                val normalizedName = Artist.normalizeName(artistName)
                val existingArtist = artistDao.getArtistByNormalizedName(normalizedName)
                    ?: artistDao.getArtistByName(artistName)

                if (existingArtist != null && !existingArtist.imageUrl.isNullOrBlank()) {
                    Log.d("EnrichmentSource", "Artist '$artistName' already has image, skipping.")
                    // Still capture the primary artist's image for reuse
                    if (ArtistParser.isSameArtist(artistName, primaryArtist)) {
                        primaryArtistImageUrl = existingArtist.imageUrl
                    }
                    continue
                }

                // If this is the primary artist and we already have the ID from the main search result
                val isPrimary = ArtistParser.isSameArtist(artistName, primaryArtist)
                if (isPrimary && result.artistId != null) {
                    val imageUrl = iTunesService.fetchArtistImage(result.artistId)
                    if (imageUrl != null) {
                        persistImage(artistName, imageUrl, result.artistId.toString())
                        primaryArtistImageUrl = imageUrl
                        continue
                    }
                }

                // For other artists (or if primary failed), do a dedicated search
                Log.d("EnrichmentSource", "Fetching missing image for artist: $artistName")
                val imageUrl = iTunesService.searchAndFetchArtistImage(artistName)
                if (imageUrl != null) {
                    persistImage(artistName, imageUrl, null)
                    if (isPrimary) primaryArtistImageUrl = imageUrl
                }

            } catch (e: Exception) {
                Log.w("EnrichmentSource", "Failed to enrich image for artist '$artistName': ${e.message}")
            }
        }
        
        return primaryArtistImageUrl
    }

    private suspend fun persistImage(name: String, imageUrl: String, artistId: String?) {
        try {
            // Ensure artist exists in DB
            val artist = artistDao.getOrCreate(name, imageUrl)
            
            // Update if needed
            if (artist.imageUrl.isNullOrBlank() || artist.imageUrl != imageUrl) {
                artistDao.updateImageUrl(artist.id, imageUrl)
                Log.d("EnrichmentSource", "Persisted iTunes artist image for: $name")
            }
        } catch (e: Exception) {
            Log.w("EnrichmentSource", "Failed to persist artist '$name': ${e.message}")
        }
    }
}

@Singleton
class DeezerEnrichmentSource @Inject constructor(
    private val deezerService: DeezerEnrichmentService,
    private val metadataDao: me.avinas.tempo.data.local.dao.EnrichedMetadataDao
) : EnrichmentSource {
    override val name = "Deezer"
    override val priority = 6

    override fun canProvide(gap: EnrichmentGap): Boolean {
        // Deezer is mainly for previews and cover art fallbacks
        return gap.missingPreviewUrl || gap.missingAlbumArt || gap.missingArtistImage
    }

    override suspend fun enrich(track: Track, currentMetadata: EnrichedMetadata?): EnrichedMetadata? {
        val result = deezerService.getAudioPreview(track.artist, track.title, track.album)
        
        return if (result is DeezerEnrichmentService.DeezerResult.Success) {
            val base = currentMetadata ?: EnrichedMetadata(trackId = track.id)
            
            // Check if we should update album art based on source priority
            // Deezer (priority 3) can only replace LOCAL (priority 2) and NONE
            val shouldUpdateArt = (base.albumArtUrl.isNullOrBlank() || 
                base.albumArtSource.shouldBeReplacedBy(AlbumArtSource.DEEZER)) &&
                result.albumArtUrl != null
            
            val updated = base.copy(
                previewUrl = base.previewUrl ?: result.previewUrl,
                albumArtUrl = if (shouldUpdateArt) result.albumArtUrl else base.albumArtUrl,
                albumArtUrlSmall = if (shouldUpdateArt) result.albumArtUrlSmall ?: base.albumArtUrlSmall else base.albumArtUrlSmall,
                albumArtUrlLarge = if (shouldUpdateArt) result.albumArtUrlLarge ?: base.albumArtUrlLarge else base.albumArtUrlLarge,
                albumArtSource = if (shouldUpdateArt) AlbumArtSource.DEEZER else base.albumArtSource,
                deezerArtistImageUrl = base.deezerArtistImageUrl ?: result.artistImageUrl,
                cacheTimestamp = System.currentTimeMillis()
            )
            
            if (shouldUpdateArt) {
                Log.d("EnrichmentSource", "Deezer: Replacing ${base.albumArtSource} album art with DEEZER source")
            }
            
            metadataDao.upsert(updated)
            updated
        } else {
            null
        }
    }
}

@Singleton
class ReccoBeatsEnrichmentSource @Inject constructor(
    private val reccoBeatsService: ReccoBeatsEnrichmentService,
    private val enrichedMetadataDao: me.avinas.tempo.data.local.dao.EnrichedMetadataDao,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : EnrichmentSource {
    override val name = "ReccoBeats"
    override val priority = 8 // Run LAST - requires preview URLs from earlier sources (Deezer/iTunes)

    override fun canProvide(gap: EnrichmentGap): Boolean {
        return gap.missingAudioFeatures
    }

    override suspend fun enrich(track: Track, currentMetadata: EnrichedMetadata?): EnrichedMetadata? {
        // CRITICAL: Wrap entire ReccoBeats logic in try-catch to ensure failures don't affect music tracking
        return try {
            Log.d("EnrichmentSource", "ReccoBeats: Starting enrichment for track ${track.id}")
            
            // Check if extended analysis is enabled
            val extendedAnalysisEnabled = try {
                val key = androidx.datastore.preferences.core.booleanPreferencesKey("extended_audio_analysis")
                val prefs = context.dataStore.data.firstOrNull()
                prefs?.get(key) ?: false
            } catch (e: Exception) {
                Log.w("EnrichmentSource", "ReccoBeats: Failed to check extended analysis setting, defaulting to false", e)
                false
            }

            // Only pass previewUrl if extended analysis is enabled. 
            // ReccoBeatsService uses this to trigger the expensive audio analysis fallback.
            val previewUrl = if (extendedAnalysisEnabled) currentMetadata?.previewUrl else null
            
            if (extendedAnalysisEnabled && previewUrl != null) {
                Log.d("EnrichmentSource", "ReccoBeats: Extended analysis enabled with preview URL")
            } else if (extendedAnalysisEnabled && previewUrl == null) {
                Log.d("EnrichmentSource", "ReccoBeats: Extended analysis enabled but no preview URL available yet")
            }
            
            val result = reccoBeatsService.enrichTrack(track, currentMetadata, previewUrl)
             
            when (result) {
                is ReccoBeatsEnrichmentService.ReccoBeatsResult.Success -> {
                    Log.d("EnrichmentSource", "ReccoBeats: Successfully enriched track ${track.id}")
                    // The service updates the DB internally, so we re-fetch the updated metadata
                    enrichedMetadataDao.forTrackSync(track.id)
                }
                is ReccoBeatsEnrichmentService.ReccoBeatsResult.Error -> {
                    Log.w("EnrichmentSource", "ReccoBeats: Failed to enrich track ${track.id}: ${result.message}")
                    null
                }
                else -> {
                    Log.d("EnrichmentSource", "ReccoBeats: Track ${track.id} not found or no audio features available")
                    null
                }
            }
        } catch (e: Exception) {
            // CRITICAL: Catch all exceptions to prevent ReccoBeats failures from affecting music tracking
            Log.e("EnrichmentSource", "ReccoBeats: Critical error enriching track ${track.id}, but music tracking will continue", e)
            null
        }
    }
}

@Singleton
class SpotifyArtistFeaturesSource @Inject constructor(
    private val spotifyService: SpotifyEnrichmentService,
    private val enrichedMetadataDao: me.avinas.tempo.data.local.dao.EnrichedMetadataDao
) : EnrichmentSource {
    override val name = "SpotifyArtistFeatures"
    override val priority = 7

    override fun canProvide(gap: EnrichmentGap): Boolean {
        // Only attempted if we still miss audio features and have a Spotify Artist ID
        return gap.missingAudioFeatures && spotifyService.isAvailable()
    }

    override suspend fun enrich(track: Track, currentMetadata: EnrichedMetadata?): EnrichedMetadata? {
        // Need Spotify artist ID to look up artist's features
        val spotifyArtistId = currentMetadata?.spotifyArtistId
        if (spotifyArtistId.isNullOrBlank()) {
            return null
        }

        val result = spotifyService.deriveAudioFeaturesFromArtist(spotifyArtistId)
        
        return if (result is SpotifyEnrichmentService.ArtistDerivedFeaturesResult.Success) {
             val updated = currentMetadata.copy(
                 audioFeaturesJson = result.audioFeaturesJson,
                 audioFeaturesSource = me.avinas.tempo.data.local.entities.AudioFeaturesSource.SPOTIFY_ARTIST_DERIVED,
                 cacheTimestamp = System.currentTimeMillis()
             )
             enrichedMetadataDao.upsert(updated)
             updated
        } else {
            null
        }
    }
}

package me.avinas.tempo.data.enrichment

import android.util.Log
import me.avinas.tempo.data.remote.deezer.DeezerApi
import me.avinas.tempo.data.remote.deezer.DeezerTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * Service responsible for fetching audio previews from Deezer.
 * 
 * Deezer provides public 30-second MP3 previews via their search API.
 * This is used as a reliable fallback when Spotify/iTunes fail to provide previews.
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
            val previewUrl: String,
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
    
    /**
     * Search for a track and get its preview URL.
     */
    suspend fun getAudioPreview(
        artist: String,
        track: String,
        album: String? = null
    ): DeezerResult {
        return try {
            // Deezer advanced search syntax: artist:"..." track:"..."
            // Cleaning up the search terms is crucial
            val cleanArtist = me.avinas.tempo.utils.ArtistParser.getPrimaryArtist(artist)
            val cleanTrack = me.avinas.tempo.utils.ArtistParser.cleanTrackTitle(track)
            
            // Construct query: artist:'X' track:'Y'
            // We use standard quotes for Deezer syntax
            val query = "artist:\"$cleanArtist\" track:\"$cleanTrack\""
            
            Log.d(TAG, "Searching Deezer: $query")
            
            val response = deezerApi.searchTracks(query)
            
            if (!response.isSuccessful) {
                return DeezerResult.Error("API Error: ${response.code()}")
            }
            
            val results = response.body()?.data
            if (results.isNullOrEmpty()) {
                // Try relaxed search (just text, no filters) if structured search failed
                Log.d(TAG, "Structured search failed, trying loose search for '$cleanTrack $cleanArtist'")
                val looseQuery = "$cleanTrack $cleanArtist"
                return searchLoose(looseQuery)
            }
            
            // Find best match (prefer one with preview)
            val bestMatch = results.firstOrNull { 
                !it.preview.isNullOrBlank() 
            } ?: results.first()
            
            if (bestMatch.preview.isNullOrBlank()) {
                return DeezerResult.Error("Track found but no preview available")
            }
            
            Log.i(TAG, "Found Deezer match: '${bestMatch.title}' by '${bestMatch.artist.name}' with cover art and artist image")
            
            DeezerResult.Success(
                previewUrl = bestMatch.preview,
                deezerId = bestMatch.id,
                link = bestMatch.link,
                coverMd5 = bestMatch.album.md5Image,
                // Include album cover art URLs
                albumArtUrl = bestMatch.album.coverBig ?: bestMatch.album.coverMedium,
                albumArtUrlSmall = bestMatch.album.coverSmall,
                albumArtUrlLarge = bestMatch.album.coverXl ?: bestMatch.album.coverBig,
                // Include artist image URL
                artistImageUrl = bestMatch.artist.pictureXl ?: bestMatch.artist.pictureBig ?: bestMatch.artist.pictureMedium,
                // Album title for fallback
                albumTitle = bestMatch.album.title
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Deezer search error", e)
            DeezerResult.Error(e.message ?: "Unknown error")
        }
    }
    
    private suspend fun searchLoose(query: String): DeezerResult {
        try {
            delay(RATE_LIMIT_DELAY_MS)
            val response = deezerApi.searchTracks(query)
            
            if (!response.isSuccessful) return DeezerResult.NotFound
            
            val results = response.body()?.data
            if (results.isNullOrEmpty()) return DeezerResult.NotFound
            
            val bestMatch = results.firstOrNull { !it.preview.isNullOrBlank() } 
                ?: return DeezerResult.NotFound
                
            Log.i(TAG, "Found Deezer match (loose search): '${bestMatch.title}' with cover art and artist image")
            
            return DeezerResult.Success(
                previewUrl = bestMatch.preview!!,
                deezerId = bestMatch.id,
                link = bestMatch.link,
                coverMd5 = bestMatch.album.md5Image,
                // Include album cover art URLs
                albumArtUrl = bestMatch.album.coverBig ?: bestMatch.album.coverMedium,
                albumArtUrlSmall = bestMatch.album.coverSmall,
                albumArtUrlLarge = bestMatch.album.coverXl ?: bestMatch.album.coverBig,
                // Include artist image URL
                artistImageUrl = bestMatch.artist.pictureXl ?: bestMatch.artist.pictureBig ?: bestMatch.artist.pictureMedium,
                // Album title for fallback
                albumTitle = bestMatch.album.title
            )
        } catch (e: Exception) {
            return DeezerResult.Error(e.message ?: "Unknown error")
        }
    }
}

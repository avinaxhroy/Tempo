package me.avinas.tempo.data.repository

import android.util.Log
import me.avinas.tempo.data.local.dao.ArtistAliasDao
import me.avinas.tempo.data.local.dao.ArtistDao
import me.avinas.tempo.data.local.dao.TrackArtistDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.ArtistRole
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.TrackArtist
import me.avinas.tempo.utils.ArtistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service responsible for creating and linking artists to tracks.
 * 
 * This service:
 * 1. Parses artist strings to extract individual artists
 * 2. Creates Artist records if they don't exist
 * 3. Links tracks to artists via the TrackArtist junction table
 * 4. Updates track.primary_artist_id
 * 
 * This ensures all tracks are properly linked to artist entities.
 */
@Singleton
class ArtistLinkingService @Inject constructor(
    private val trackDao: TrackDao,
    private val artistDao: ArtistDao,
    private val trackArtistDao: TrackArtistDao,
    private val artistAliasDao: ArtistAliasDao
) {
    companion object {
        private const val TAG = "ArtistLinkingService"
    }
    
    /**
     * Process a track to ensure all artists are created and linked.
     * 
     * @param track The track to process
     * @return The updated track with primary_artist_id set
     */
    suspend fun linkArtistsForTrack(track: Track): Track = withContext(Dispatchers.IO) {
        if (track.artist.isBlank()) {
            Log.d(TAG, "Skipping track ${track.id} - no artist")
            return@withContext track
        }
        
        // Parse the artist string
        val parsedArtists = ArtistParser.parse(track.artist)
        
        // Get or create primary artists
        val primaryArtists = parsedArtists.primaryArtists.mapNotNull { artistName ->
            if (artistName.isBlank() || ArtistParser.isUnknownArtist(artistName)) null
            else getOrCreateArtist(artistName)
        }
        
        // Get or create featured artists
        val featuredArtists = parsedArtists.featuredArtists.mapNotNull { artistName ->
            if (artistName.isBlank() || ArtistParser.isUnknownArtist(artistName)) null
            else getOrCreateArtist(artistName)
        }
        
        // Determine the primary artist (first one)
        val primaryArtist = primaryArtists.firstOrNull()
        
        // Update track with primary artist ID if needed
        val updatedTrack = if (primaryArtist != null && track.primaryArtistId != primaryArtist.id) {
            trackDao.updatePrimaryArtistId(track.id, primaryArtist.id)
            track.copy(primaryArtistId = primaryArtist.id)
        } else if (primaryArtist == null && track.primaryArtistId == null) {
            // No valid artist found - use "Unknown Artist" placeholder to prevent infinite loop
            val unknownArtist = getOrCreateUnknownArtist()
            trackDao.updatePrimaryArtistId(track.id, unknownArtist.id)
            track.copy(primaryArtistId = unknownArtist.id)
        } else {
            track
        }
        
        // Clear existing relationships and create new ones
        trackArtistDao.deleteAllForTrack(track.id)
        
        // Link primary artists
        var creditOrder = 0
        primaryArtists.forEach { artist ->
            trackArtistDao.insert(
                TrackArtist(
                    trackId = track.id,
                    artistId = artist.id,
                    role = ArtistRole.PRIMARY,
                    creditOrder = creditOrder++
                )
            )
        }
        
        // Link featured artists
        featuredArtists.forEach { artist ->
            trackArtistDao.insert(
                TrackArtist(
                    trackId = track.id,
                    artistId = artist.id,
                    role = ArtistRole.FEATURED,
                    creditOrder = creditOrder++
                )
            )
        }
        
        updatedTrack
    }
    
    /**
     * Get or create an artist by name.
     * 
     * First checks if this artist name has been merged (aliased) to another artist.
     * If so, returns the target artist instead of creating a duplicate.
     */
    suspend fun getOrCreateArtist(name: String, imageUrl: String? = null): Artist {
        val normalizedName = Artist.normalizeName(name)
        
        // NEW: Check if this name has an alias (was merged into another artist)
        artistAliasDao.findAlias(normalizedName)?.let { alias ->
            artistDao.getArtistById(alias.targetArtistId)?.let { targetArtist ->
                // Alias resolved silently - no need to log every resolution
                return targetArtist
            }
        }
        
        // Try to find existing artist by normalized name
        artistDao.getArtistByNormalizedName(normalizedName)?.let { return it }
        
        // Try exact name match
        artistDao.getArtistByName(name)?.let { return it }
        
        // Create new artist
        val newArtist = Artist(
            name = name.trim(),
            normalizedName = normalizedName,
            imageUrl = imageUrl,
            genres = emptyList(),
            musicbrainzId = null,
            spotifyId = null
        )
        
        val id = artistDao.insert(newArtist)
        
        return if (id > 0) {
            Log.d(TAG, "Created new artist: '$name' with ID $id")
            newArtist.copy(id = id)
        } else {
            // Insert failed (likely race condition), try to get again
            artistDao.getArtistByNormalizedName(normalizedName) 
                ?: artistDao.getArtistByName(name) 
                ?: newArtist.copy(id = id)
        }
    }
    
    /**
     * Get or create an artist by name, with additional metadata.
     */
    suspend fun getOrCreateArtistWithMetadata(
        name: String,
        imageUrl: String? = null,
        spotifyId: String? = null,
        musicbrainzId: String? = null,
        country: String? = null,
        genres: List<String> = emptyList()
    ): Artist {
        val normalizedName = Artist.normalizeName(name)
        
        // Try to find existing artist
        var existingArtist = artistDao.getArtistByNormalizedName(normalizedName)
            ?: artistDao.getArtistByName(name)
            ?: spotifyId?.let { artistDao.getArtistBySpotifyId(it) }
            ?: musicbrainzId?.let { artistDao.getArtistByMusicBrainzId(it) }
        
        if (existingArtist != null) {
            // Update existing artist with new metadata if available
            var needsUpdate = false
            var updated = existingArtist
            
            if (imageUrl != null && existingArtist.imageUrl.isNullOrBlank()) {
                updated = updated.copy(imageUrl = imageUrl)
                needsUpdate = true
            }
            if (spotifyId != null && existingArtist.spotifyId == null) {
                updated = updated.copy(spotifyId = spotifyId)
                needsUpdate = true
            }
            if (musicbrainzId != null && existingArtist.musicbrainzId == null) {
                updated = updated.copy(musicbrainzId = musicbrainzId)
                needsUpdate = true
            }
            if (country != null && existingArtist.country == null) {
                updated = updated.copy(country = country)
                needsUpdate = true
            }
            if (genres.isNotEmpty() && existingArtist.genres.isEmpty()) {
                updated = updated.copy(genres = genres)
                needsUpdate = true
            }
            
            if (needsUpdate) {
                artistDao.update(updated)
                Log.d(TAG, "Updated artist '${existingArtist.name}' with new metadata")
            }
            
            return updated
        }
        
        // Create new artist
        val newArtist = Artist(
            name = name.trim(),
            normalizedName = normalizedName,
            imageUrl = imageUrl,
            genres = genres,
            musicbrainzId = musicbrainzId,
            spotifyId = spotifyId,
            country = country
        )
        
        val id = artistDao.insert(newArtist)
        
        return if (id > 0) {
            Log.d(TAG, "Created new artist: '$name' with ID $id and metadata")
            newArtist.copy(id = id)
        } else {
            // Fallback
            artistDao.getArtistByNormalizedName(normalizedName) 
                ?: artistDao.getArtistByName(name) 
                ?: newArtist.copy(id = id)
        }
    }
    
    /**
     * Get or create the "Unknown Artist" placeholder.
     * This is used for tracks that have no valid artist data,
     * allowing us to mark them as processed without violating FK constraints.
     */
    private suspend fun getOrCreateUnknownArtist(): Artist {
        val unknownName = "Unknown Artist"
        val normalizedName = Artist.normalizeName(unknownName)
        
        // Try to find existing Unknown Artist
        artistDao.getArtistByNormalizedName(normalizedName)?.let { return it }
        
        // Create new Unknown Artist
        val newArtist = Artist(
            name = unknownName,
            normalizedName = normalizedName,
            imageUrl = null,
            genres = emptyList(),
            musicbrainzId = null,
            spotifyId = null
        )
        
        val id = artistDao.insert(newArtist)
        return if (id > 0) {
            newArtist.copy(id = id)
        } else {
            artistDao.getArtistByNormalizedName(normalizedName) ?: newArtist
        }
    }
    
    /**
     * Process all tracks that don't have a primary artist linked.
     * This is used for migration and background cleanup.
     * 
     * IMPORTANT: Limited to prevent runaway processing.
     */
    suspend fun processUnlinkedTracks(batchSize: Int = 100): Int = withContext(Dispatchers.IO) {
        var totalProcessed = 0
        var totalFeatured = 0
        val maxIterations = 1000 // Safety limit: max 100,000 tracks per call
        var iterations = 0
        
        // Track failed track IDs in memory to avoid reprocessing in this run
        val failedTrackIds = mutableSetOf<Long>()
        
        Log.i(TAG, "Starting to process unlinked tracks...")
        
        while (iterations < maxIterations) {
            iterations++
            val unlinkedTracks = trackDao.getTracksWithoutPrimaryArtist(batchSize)
                .filter { it.id !in failedTrackIds } // Skip tracks that already failed
            
            if (unlinkedTracks.isEmpty()) break
            
            unlinkedTracks.forEach { track ->
                try {
                    val result = linkArtistsForTrack(track)
                    totalProcessed++
                    
                    // Count tracks with featured artists for batch summary
                    val parsed = ArtistParser.parse(track.artist)
                    if (parsed.featuredArtists.isNotEmpty()) {
                        totalFeatured++
                    }
                } catch (e: Exception) {
                    // Mark as failed in memory to skip in subsequent iterations
                    failedTrackIds.add(track.id)
                    
                    // Try to mark with Unknown Artist to prevent future reruns
                    try {
                        val unknownArtist = getOrCreateUnknownArtist()
                        trackDao.updatePrimaryArtistId(track.id, unknownArtist.id)
                    } catch (updateError: Exception) {
                        // Silently fail - will be retried next time
                    }
                }
            }
            
            // Batch log every 500 tracks to avoid log bloat
            if (totalProcessed % 500 == 0 && totalProcessed > 0) {
                Log.i(TAG, "Progress: $totalProcessed tracks linked ($totalFeatured with featured artists)")
            }
        }
        
        if (iterations >= maxIterations) {
            Log.w(TAG, "Reached iteration limit ($maxIterations). Stopping to prevent runaway.")
        }
        
        // Final summary log
        val failedCount = failedTrackIds.size
        if (failedCount > 0) {
            Log.i(TAG, "Completed: $totalProcessed linked, $totalFeatured with featured artists, $failedCount failed")
        } else {
            Log.i(TAG, "Completed: $totalProcessed tracks linked ($totalFeatured with featured artists)")
        }
        
        totalProcessed
    }
    
    /**
     * Get the primary artist for a track, creating if necessary.
     */
    suspend fun getPrimaryArtistForTrack(track: Track): Artist? {
        // If track already has a primary artist ID, use it
        track.primaryArtistId?.let { artistId ->
            artistDao.getArtistById(artistId)?.let { return it }
        }
        
        // Otherwise, create and link
        if (track.artist.isNotBlank() && !ArtistParser.isUnknownArtist(track.artist)) {
            val parsedArtists = ArtistParser.parse(track.artist)
            val primaryName = parsedArtists.primaryArtist
            
            if (primaryName.isNotBlank()) {
                val artist = getOrCreateArtist(primaryName)
                trackDao.updatePrimaryArtistId(track.id, artist.id)
                return artist
            }
        }
        
        return null
    }
    
    /**
     * Get all artists for a track, creating if necessary.
     */
    suspend fun getAllArtistsForTrack(track: Track): List<Artist> {
        // First try to get from junction table
        val existingArtists = trackArtistDao.getArtistsForTrack(track.id)
        if (existingArtists.isNotEmpty()) {
            return existingArtists
        }
        
        // If no links exist, create them
        linkArtistsForTrack(track)
        return trackArtistDao.getArtistsForTrack(track.id)
    }
}

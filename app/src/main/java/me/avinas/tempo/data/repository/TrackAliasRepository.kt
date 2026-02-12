package me.avinas.tempo.data.repository

import android.util.Log
import androidx.room.withTransaction
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.dao.TrackAliasDao
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.TrackAlias
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing track alias and merge operations.
 *
 * When merging tracks:
 * 1. Creates an alias from source -> target for future lookups
 * 2. Re-points any existing aliases that pointed to source to now point to target
 *    (handles chained merges: A->B, B->C results in A->C)
 * 3. Merges useful metadata from source to target (album art, IDs, etc.)
 * 4. Moves all listening history from source to target
 * 5. Deletes the source track
 * 6. Invalidates stats cache to refresh UI
 */
@Singleton
class TrackAliasRepository @Inject constructor(
    private val trackAliasDao: TrackAliasDao,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val database: AppDatabase,
    private val statsRepository: StatsRepository
) {
    companion object {
        private const val TAG = "TrackAliasRepository"
    }

    /**
     * Find an alias for the given title and artist.
     * Returns the alias if one exists, or null.
     */
    suspend fun findAlias(title: String, artist: String): TrackAlias? {
        return trackAliasDao.findAlias(title, artist)
    }

    /**
     * Create a new alias mapping (originalTitle, originalArtist) -> targetTrackId.
     */
    suspend fun createAlias(targetTrackId: Long, originalTitle: String, originalArtist: String) {
        val alias = TrackAlias(
            targetTrackId = targetTrackId,
            originalTitle = originalTitle,
            originalArtist = originalArtist
        )
        trackAliasDao.insertAlias(alias)
    }
    
    /**
     * Get all aliases that point to a specific track.
     */
    suspend fun getAliasesForTrack(trackId: Long): List<TrackAlias> {
        return trackAliasDao.getAliasesForTrack(trackId)
    }
    
    /**
     * Merges source track into target track:
     * 1. Creates alias (Source -> Target)
     * 2. Re-points existing aliases that pointed to source to now point to target
     *    (handles chained merges so no aliases are lost when source is deleted)
     * 3. Merges metadata from source to target (fills in missing fields)
     * 4. Moves listening history from source to target
     * 5. Deletes source track
     * 6. Invalidates stats cache
     *
     * @param sourceTrackId The track to merge FROM (will be deleted)
     * @param targetTrackId The track to merge INTO (will remain)
     * @return true if merge succeeded, false otherwise
     */
    suspend fun mergeTracks(sourceTrackId: Long, targetTrackId: Long): Boolean {
        if (sourceTrackId == targetTrackId) {
            Log.w(TAG, "Cannot merge track into itself")
            return false
        }
        
        val sourceTrack = trackDao.getTrackById(sourceTrackId)
        val targetTrack = trackDao.getTrackById(targetTrackId)
        
        if (sourceTrack == null) {
            Log.w(TAG, "Source track $sourceTrackId not found")
            return false
        }
        if (targetTrack == null) {
            Log.w(TAG, "Target track $targetTrackId not found")
            return false
        }
        
        Log.i(TAG, "Merging track '${sourceTrack.title}' by '${sourceTrack.artist}' " +
                "into '${targetTrack.title}' by '${targetTrack.artist}'")
        
        return try {
            database.withTransaction {
                // 1. Create alias for future lookups
                val alias = TrackAlias(
                    targetTrackId = targetTrackId,
                    originalTitle = sourceTrack.title,
                    originalArtist = sourceTrack.artist
                )
                trackAliasDao.insertAlias(alias)
                Log.d(TAG, "Created alias: '${sourceTrack.title}' -> '${targetTrack.title}'")
                
                // 2. CRITICAL: Re-point existing aliases that pointed to source track
                // This handles chained merges: if "Song (Live)" was merged into "Song",
                // and now "Song" is merged into "Song (Remastered)", the alias for
                // "Song (Live)" must now point to "Song (Remastered)" to not be lost
                // when the CASCADE delete removes it.
                val existingAliases = trackAliasDao.getAliasesForTrack(sourceTrackId)
                for (existingAlias in existingAliases) {
                    // Re-create the alias pointing to the new target
                    // (delete old + insert new since we can't just update the FK)
                    trackAliasDao.insertAlias(
                        TrackAlias(
                            targetTrackId = targetTrackId,
                            originalTitle = existingAlias.originalTitle,
                            originalArtist = existingAlias.originalArtist
                        )
                    )
                }
                if (existingAliases.isNotEmpty()) {
                    Log.d(TAG, "Re-pointed ${existingAliases.size} existing aliases to target")
                }
                
                // 3. Merge metadata from source into target (fill missing fields)
                val updatedTarget = mergeTrackMetadata(sourceTrack, targetTrack)
                if (updatedTarget != targetTrack) {
                    trackDao.update(updatedTarget)
                    Log.d(TAG, "Updated target track metadata")
                }
                
                // 4. Move listening history
                listeningEventDao.reattributeEvents(sourceTrackId, targetTrackId)
                Log.d(TAG, "Reattributed listening events from source to target")
                
                // 5. Delete source track (CASCADE will clean up old aliases and track_artists)
                trackDao.deleteById(sourceTrackId)
                Log.i(TAG, "Deleted source track '${sourceTrack.title}'")
            }
            
            // 6. Invalidate stats cache to refresh UI
            statsRepository.invalidateCache()
            Log.d(TAG, "Invalidated stats cache after merge")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error merging tracks: ${e.message}", e)
            false
        }
    }
    
    /**
     * Merge metadata from source track into target track.
     * Only copies fields that target is missing.
     */
    private fun mergeTrackMetadata(source: Track, target: Track): Track {
        var result = target
        
        // Copy album if target doesn't have one
        if (target.album.isNullOrBlank() && !source.album.isNullOrBlank()) {
            result = result.copy(album = source.album)
        }
        
        // Copy album art URL if target doesn't have one
        if (target.albumArtUrl.isNullOrBlank() && !source.albumArtUrl.isNullOrBlank()) {
            result = result.copy(albumArtUrl = source.albumArtUrl)
        }
        
        // Copy Spotify ID if target doesn't have one
        if (target.spotifyId.isNullOrBlank() && !source.spotifyId.isNullOrBlank()) {
            result = result.copy(spotifyId = source.spotifyId)
        }
        
        // Copy MusicBrainz ID if target doesn't have one
        if (target.musicbrainzId.isNullOrBlank() && !source.musicbrainzId.isNullOrBlank()) {
            result = result.copy(musicbrainzId = source.musicbrainzId)
        }
        
        // Copy duration if target doesn't have one
        if ((target.duration == null || target.duration == 0L) && source.duration != null && source.duration > 0) {
            result = result.copy(duration = source.duration)
        }
        
        return result
    }

    /**
     * Get all aliases for backup/export.
     */
    suspend fun getAllAliases(): List<TrackAlias> {
        return trackAliasDao.getAllSync()
    }
}

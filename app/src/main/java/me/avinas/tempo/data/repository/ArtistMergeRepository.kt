package me.avinas.tempo.data.repository

import android.util.Log
import androidx.room.withTransaction
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.dao.ArtistAliasDao
import me.avinas.tempo.data.local.dao.ArtistDao
import me.avinas.tempo.data.local.dao.TrackArtistDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.ArtistAlias
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing artist merging operations.
 * 
 * When merging artists:
 * 1. Creates an alias from source -> target for future lookups
 * 2. Re-links all tracks from source artist to target
 * 3. Copies useful metadata from source to target (if target lacks it)
 * 4. Deletes the source artist
 * 
 * This consolidates listening history under a single canonical artist.
 */
@Singleton
class ArtistMergeRepository @Inject constructor(
    private val artistAliasDao: ArtistAliasDao,
    private val artistDao: ArtistDao,
    private val trackArtistDao: TrackArtistDao,
    private val trackDao: TrackDao,
    private val database: AppDatabase,
    private val statsRepository: StatsRepository
) {
    companion object {
        private const val TAG = "ArtistMergeRepository"
    }

    /**
     * Find an alias for the given normalized artist name.
     * Returns the alias if one exists, or null.
     */
    suspend fun findAlias(normalizedName: String): ArtistAlias? {
        return artistAliasDao.findAlias(normalizedName)
    }
    
    /**
     * Get all aliases that point to a specific artist.
     */
    suspend fun getAliasesForArtist(artistId: Long): List<ArtistAlias> {
        return artistAliasDao.getAliasesForArtist(artistId)
    }

    /**
     * Create a new alias mapping (originalName) -> targetArtistId.
     * Returns true if created, false if already exists.
     */
    suspend fun createAlias(originalName: String, targetArtistId: Long): Boolean {
        val alias = ArtistAlias.create(originalName, targetArtistId)
        val result = artistAliasDao.insertAlias(alias)
        return result > 0
    }
    
    /**
     * Delete an alias by ID.
     */
    suspend fun deleteAlias(aliasId: Long) {
        artistAliasDao.deleteById(aliasId)
    }

    /**
     * Merges source artist into target artist:
     * 1. Creates alias (Source -> Target)
     * 2. Updates any existing aliases that pointed to source to now point to target
     * 3. Re-links all track relationships from source to target
     * 4. Updates tracks.primary_artist_id references
     * 5. Copies metadata from source to target (if target lacks it)
     * 6. Deletes source artist
     * 
     * @param sourceArtistId The artist to merge FROM (will be deleted)
     * @param targetArtistId The artist to merge INTO (will remain)
     * @return true if merge succeeded, false otherwise
     */
    suspend fun mergeArtists(sourceArtistId: Long, targetArtistId: Long): Boolean {
        if (sourceArtistId == targetArtistId) {
            Log.w(TAG, "Cannot merge artist into itself")
            return false
        }

        val sourceArtist = artistDao.getArtistById(sourceArtistId)
        val targetArtist = artistDao.getArtistById(targetArtistId)

        if (sourceArtist == null) {
            Log.w(TAG, "Source artist $sourceArtistId not found")
            return false
        }
        if (targetArtist == null) {
            Log.w(TAG, "Target artist $targetArtistId not found")
            return false
        }

        Log.i(TAG, "Merging artist '${sourceArtist.name}' into '${targetArtist.name}'")

        return try {
            database.withTransaction {
                // 1. Create alias for future lookups
                val alias = ArtistAlias.create(sourceArtist.name, targetArtistId)
                artistAliasDao.insertAlias(alias)
                Log.d(TAG, "Created alias: '${sourceArtist.name}' -> '${targetArtist.name}'")
                
                // 2. CRITICAL: Update any existing aliases that pointed to the source artist
                // This handles "chained" merges: A->B, then B->C should result in A->C
                val existingAliases = artistAliasDao.getAliasesForArtist(sourceArtistId)
                for (existingAlias in existingAliases) {
                    // Delete old alias and create new one pointing to target
                    artistAliasDao.deleteById(existingAlias.id)
                    val updatedAlias = existingAlias.copy(
                        id = 0,  // New ID will be generated
                        targetArtistId = targetArtistId
                    )
                    artistAliasDao.insertAlias(updatedAlias)
                }
                if (existingAliases.isNotEmpty()) {
                    Log.d(TAG, "Re-pointed ${existingAliases.size} existing aliases to target")
                }

                // 3. Re-link all track_artists from source to target
                val relationships = trackArtistDao.getRelationshipsForArtist(sourceArtistId)
                for (rel in relationships) {
                    // Check if target already has a relationship with this track
                    val hasExisting = trackArtistDao.hasRelationship(rel.trackId, targetArtistId)
                    if (!hasExisting) {
                        // Create new relationship with target artist
                        trackArtistDao.insert(rel.copy(artistId = targetArtistId))
                    }
                    // Delete the old relationship
                    trackArtistDao.delete(rel)
                }
                Log.d(TAG, "Re-linked ${relationships.size} track relationships")

                // 4. Update tracks.primary_artist_id references
                updatePrimaryArtistReferences(sourceArtistId, targetArtistId)

                // 5. Copy metadata from source to target if target lacks it
                // Note: We skip copying Spotify/MusicBrainz IDs if target already has one
                // to avoid unique constraint violations
                val updatedTarget = mergeArtistMetadata(sourceArtist, targetArtist)
                if (updatedTarget != targetArtist) {
                    artistDao.update(updatedTarget)
                    Log.d(TAG, "Updated target artist metadata")
                }
                
                // 6. CRITICAL: Update tracks.artist text column
                // This is essential because stats aggregation uses this column, not track_artists!
                // First try exact match replacement
                val exactReplaced = trackDao.replaceArtistName(sourceArtist.name, targetArtist.name)
                Log.d(TAG, "Replaced artist name in $exactReplaced tracks (exact match)")
                
                // Then handle multi-artist strings (e.g., "OldArtist, OtherArtist")
                val multiReplaced = trackDao.replaceArtistNameInMultiArtist(sourceArtist.name, targetArtist.name)
                Log.d(TAG, "Replaced artist name in multi-artist strings: $multiReplaced tracks")

                // 7. Delete source artist
                artistDao.deleteById(sourceArtistId)
                Log.i(TAG, "Deleted source artist '${sourceArtist.name}'")
            }
            
            // 8. Invalidate stats cache to refresh UI
            statsRepository.invalidateCache()
            Log.d(TAG, "Invalidated stats cache after merge")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error merging artists: ${e.message}", e)
            false
        }
    }

    /**
     * Update all tracks that have source artist as primary to point to target.
     */
    private suspend fun updatePrimaryArtistReferences(sourceArtistId: Long, targetArtistId: Long) {
        // Get tracks where source is primary artist
        val tracksWithSource = trackDao.getTracksByPrimaryArtist(sourceArtistId)
        var updateCount = 0
        for (track in tracksWithSource) {
            trackDao.updatePrimaryArtistId(track.id, targetArtistId)
            updateCount++
        }
        if (updateCount > 0) {
            Log.d(TAG, "Updated primary_artist_id for $updateCount tracks")
        }
    }

    /**
     * Merge metadata from source artist into target artist.
     * Only copies fields that target is missing.
     */
    private fun mergeArtistMetadata(source: Artist, target: Artist): Artist {
        var result = target

        // Copy image URL if target doesn't have one
        if (target.imageUrl.isNullOrBlank() && !source.imageUrl.isNullOrBlank()) {
            result = result.copy(imageUrl = source.imageUrl)
        }

        // Copy Spotify ID if target doesn't have one
        if (target.spotifyId.isNullOrBlank() && !source.spotifyId.isNullOrBlank()) {
            result = result.copy(spotifyId = source.spotifyId)
        }

        // Copy MusicBrainz ID if target doesn't have one
        if (target.musicbrainzId.isNullOrBlank() && !source.musicbrainzId.isNullOrBlank()) {
            result = result.copy(musicbrainzId = source.musicbrainzId)
        }

        // Copy country if target doesn't have one
        if (target.country.isNullOrBlank() && !source.country.isNullOrBlank()) {
            result = result.copy(country = source.country)
        }

        // Merge genres - combine both lists
        if (source.genres.isNotEmpty()) {
            val combinedGenres = (target.genres + source.genres).distinct()
            result = result.copy(genres = combinedGenres)
        }

        return result
    }

    /**
     * Search for artists by name (for merge destination selection).
     * Excludes the source artist.
     */
    suspend fun searchArtists(query: String, excludeArtistId: Long? = null): List<Artist> {
        val results = artistDao.searchSync(query)
        return if (excludeArtistId != null) {
            results.filter { it.id != excludeArtistId }
        } else {
            results
        }
    }

    /**
     * Get all aliases for backup/export.
     */
    suspend fun getAllAliases(): List<ArtistAlias> {
        return artistAliasDao.getAllSync()
    }
}

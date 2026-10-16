package me.avinas.tempo.data.repository

import android.util.Log
import androidx.room.withTransaction
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.ArchiveTimestampCodec
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.dao.ScrobbleArchiveDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.dao.TrackAliasDao
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.ScrobbleArchive
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.TrackAlias
import me.avinas.tempo.utils.ArtistParser
import javax.inject.Inject
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FeatureUsed
import me.avinas.tempo.data.analytics.TempoFeature
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
open class TrackAliasRepository @Inject constructor(
    private val trackAliasDao: TrackAliasDao,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val scrobbleArchiveDao: ScrobbleArchiveDao,
    private val database: AppDatabase,
    private val statsRepository: StatsRepository,
    private val artistLinkingService: ArtistLinkingService,
    private val tracker: AnalyticsTracker
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
        createAliasInternal(targetTrackId, originalTitle, originalArtist)
        tracker.track(FeatureUsed(TempoFeature.ALIAS_RENAME))
    }

    private suspend fun createAliasInternal(
        targetTrackId: Long,
        originalTitle: String,
        originalArtist: String
    ) {
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
    open suspend fun mergeTracks(sourceTrackId: Long, targetTrackId: Long): Boolean {
        val merged = mergeTracksInternal(sourceTrackId, targetTrackId)
        if (merged) tracker.track(FeatureUsed(TempoFeature.TRACK_MERGE))
        return merged
    }

    private suspend fun mergeTracksInternal(sourceTrackId: Long, targetTrackId: Long): Boolean {
        if (sourceTrackId == targetTrackId) {
            Log.w(TAG, "Cannot merge track into itself")
            return false
        }
        
        val sourceTrackSnapshot = trackDao.getTrackById(sourceTrackId)
        val targetTrackSnapshot = trackDao.getTrackById(targetTrackId)
        
        if (sourceTrackSnapshot == null) {
            Log.w(TAG, "Source track $sourceTrackId not found")
            return false
        }
        if (targetTrackSnapshot == null) {
            Log.w(TAG, "Target track $targetTrackId not found")
            return false
        }
        
        Log.i(TAG, "Merging track '${sourceTrackSnapshot.title}' by '${sourceTrackSnapshot.artist}' " +
                "into '${targetTrackSnapshot.title}' by '${targetTrackSnapshot.artist}'")
        
        return try {
            database.withTransaction {
                // Re-read every merge-sensitive row inside the transaction. The user may
                // have selected/reset artwork after the preliminary existence check.
                val sourceTrack = trackDao.getTrackById(sourceTrackId)
                    ?: error("Source track $sourceTrackId disappeared before merge")
                val targetTrack = trackDao.getTrackById(targetTrackId)
                    ?: error("Target track $targetTrackId disappeared before merge")
                val sourceMetadata = enrichedMetadataDao.forTrackSync(sourceTrackId)
                val targetMetadata = enrichedMetadataDao.forTrackSync(targetTrackId)

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
                var updatedTarget = mergeTrackMetadata(sourceTrack, targetTrack)

                // Returning the surviving target to automatic selection is also an
                // explicit preference. Do not resurrect artwork from the source track.
                if (targetMetadata?.albumArtSource == AlbumArtSource.USER_RESET) {
                    updatedTarget = updatedTarget.copy(albumArtUrl = null)
                }

                // A user-selected cover is an explicit preference, not ordinary enrichment.
                // Preserve the target's manual cover when it already has one; otherwise carry
                // the source manual cover across the merge before the source row is deleted.
                val manualArtwork = preferredManualArtwork(
                    sourceMetadata = sourceMetadata,
                    targetMetadata = targetMetadata,
                )

                if (manualArtwork != null) {
                    val selectedUrl = manualArtwork.albumArtUrl!!
                    updatedTarget = updatedTarget.copy(albumArtUrl = selectedUrl)

                    val mergedMetadata = (targetMetadata ?: EnrichedMetadata(trackId = targetTrackId)).copy(
                        albumArtUrl = selectedUrl,
                        albumArtUrlSmall = manualArtwork.albumArtUrlSmall ?: selectedUrl,
                        albumArtUrlLarge = manualArtwork.albumArtUrlLarge ?: selectedUrl,
                        albumArtSource = AlbumArtSource.USER_SELECTED,
                        cacheTimestamp = System.currentTimeMillis(),
                    )
                    enrichedMetadataDao.upsert(mergedMetadata)
                    Log.d(TAG, "Preserved user-selected cover while merging into track $targetTrackId")
                }
                // A merge target whose artist is a structural placeholder label
                // (e.g. the Takeout "Release" artifact) must not keep it — the
                // surviving track would stay pooled under the bogus artist and
                // stats would never recognize the real one. Adopt the source's
                // real artist instead, then re-run the linking pipeline so
                // artist junctions / primary_artist_id follow the fix.
                val artistOverridden = ArtistParser.isPlaceholderArtistName(targetTrack.artist) &&
                    !ArtistParser.isPlaceholderArtistName(sourceTrack.artist)
                if (artistOverridden) {
                    updatedTarget = updatedTarget.copy(artist = sourceTrack.artist)
                }
                if (updatedTarget != targetTrack) {
                    trackDao.update(updatedTarget)
                    Log.d(TAG, "Updated target track metadata")
                }
                if (artistOverridden) {
                    try {
                        artistLinkingService.linkArtistsForTrack(updatedTarget)
                        Log.i(
                            TAG,
                            "Replaced placeholder artist '${targetTrack.artist}' with " +
                                "'${sourceTrack.artist}' on merged track '${updatedTarget.title}'"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to re-link merged track ${targetTrackId}", e)
                    }
                }
                
                // 4. Move listening history through the dedup pipeline.
                // A blind UPDATE (old reattributeEvents) kept stale content
                // fingerprints computed for the source track and double-counted
                // plays that already existed on the target. Re-inserting with
                // contentFingerprint=null forces recomputation for the target
                // track and reconciles same-play conflicts.
                val sourceEvents = listeningEventDao.getEventsForTrack(sourceTrackId)
                if (sourceEvents.isNotEmpty()) {
                    val moved = sourceEvents.map {
                        it.copy(id = 0, track_id = targetTrackId, contentFingerprint = null)
                    }
                    val moveResult = listeningEventDao.insertAllBatchedWithDedup(moved)
                    listeningEventDao.deleteEventsForTrack(sourceTrackId)
                    Log.d(TAG, "Moved listening history: ${moveResult.inserted} inserted, " +
                            "${moveResult.skipped} deduplicated against target")
                }

                // 5. Re-key the compressed scrobble archive row (Last.fm long-tail
                // history) so it follows the merged identity. Timestamps are union-
                // merged in case the target already has its own archive row.
                val archiveRow = scrobbleArchiveDao.findByArtistAndTitle(
                    sourceTrack.artist, sourceTrack.title
                )
                if (archiveRow != null) {
                    val newHash = ScrobbleArchive.generateTrackHash(targetTrack.artist, targetTrack.title)
                    if (newHash != archiveRow.trackHash) {
                        val retargeted = archiveRow.copy(
                            id = 0,
                            trackHash = newHash,
                            trackTitle = targetTrack.title,
                            artistName = targetTrack.artist,
                            artistNameNormalized = targetTrack.artist.lowercase().trim(),
                            updatedAt = System.currentTimeMillis()
                        )
                        scrobbleArchiveDao.upsertWithMerge(
                            retargeted,
                            ArchiveTimestampCodec::decompress,
                            ArchiveTimestampCodec::compress
                        )
                        scrobbleArchiveDao.delete(archiveRow)
                        Log.d(TAG, "Re-keyed scrobble archive row to target identity")
                    }
                }

                // 6. Delete source track (CASCADE will clean up old aliases and track_artists)
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


/**
 * Resolve the artwork preference for a track merge. The surviving target's explicit
 * choice always wins; otherwise an explicit choice from the source follows the merge.
 */
internal fun preferredManualArtwork(
    sourceMetadata: EnrichedMetadata?,
    targetMetadata: EnrichedMetadata?,
): EnrichedMetadata? {
    fun EnrichedMetadata?.validManualArtwork(): EnrichedMetadata? =
        this?.takeIf {
            it.albumArtSource == AlbumArtSource.USER_SELECTED &&
                !it.albumArtUrl.isNullOrBlank()
        }

    if (targetMetadata?.albumArtSource == AlbumArtSource.USER_RESET) return null
    return targetMetadata.validManualArtwork() ?: sourceMetadata.validManualArtwork()
}

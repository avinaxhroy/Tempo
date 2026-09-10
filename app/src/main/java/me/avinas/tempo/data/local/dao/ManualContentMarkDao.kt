package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.ManualContentMark
import me.avinas.tempo.data.local.entities.ManualContentRuleResolver
import kotlinx.coroutines.flow.Flow

@Dao
interface ManualContentMarkDao {
    /**
     * Get all manual content marks, newest first so equal-specificity rules resolve
     * deterministically to the latest user correction.
     */
    @Query("SELECT * FROM manual_content_marks ORDER BY marked_at DESC, id DESC")
    fun getAllMarks(): Flow<List<ManualContentMark>>

    /**
     * Get all marks for a specific content type.
     */
    @Query("SELECT * FROM manual_content_marks WHERE content_type = :contentType")
    fun getMarksByType(contentType: String): Flow<List<ManualContentMark>>

    /**
     * Resolve the effective matching rule using the same precedence as the service:
     * TITLE_ARTIST > TITLE > ARTIST, newest rule first for equal specificity.
     */
    suspend fun findMatchingMark(title: String, artist: String): ManualContentMark? =
        ManualContentRuleResolver.resolve(getAllSync(), title, artist)

    /**
     * Insert a new manual mark.
     */
    @Transaction
    suspend fun insertMark(mark: ManualContentMark): Long {
        // SQLite's default unique index is case-sensitive. Replace the semantic rule too,
        // so changing casing cannot leave a hidden older rule that reappears on deletion.
        getAllSync().filter {
            it.patternType.equals(mark.patternType, ignoreCase = true) &&
                ManualContentRuleResolver.matches(it, mark.originalTitle, mark.originalArtist)
        }.forEach { deleteMark(it) }
        return insertMarkRow(mark.copy(
            originalTitle = mark.originalTitle.trim(),
            originalArtist = mark.originalArtist.trim()
        ))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarkRow(mark: ManualContentMark): Long

    /**
     * Delete a specific mark.
     */
    @Delete
    suspend fun deleteMark(mark: ManualContentMark)

    /**
     * Delete all marks for a specific track.
     */
    @Query("DELETE FROM manual_content_marks WHERE target_track_id = :trackId")
    suspend fun deleteMarksByTrackId(trackId: Long)

    /**
     * Delete all marks.
     */
    @Query("DELETE FROM manual_content_marks")
    suspend fun deleteAll()

    /**
     * Get all manual content marks for synchronous rule evaluation, newest first.
     */
    @Query("SELECT * FROM manual_content_marks ORDER BY marked_at DESC, id DESC")
    suspend fun getAllSync(): List<ManualContentMark>
}

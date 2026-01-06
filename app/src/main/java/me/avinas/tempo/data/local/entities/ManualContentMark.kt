package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Manual content marks for user-defined podcast/audiobook filtering.
 * 
 * When a user manually marks content as podcast or audiobook,
 * we store a pattern (title/artist/album) that will be used to
 * auto-filter similar content in the future.
 * 
 * Example: User marks "Chapter 5 of The Great Gatsby" as audiobook
 * -> Store pattern: "Chapter % of The Great Gatsby" (wildcard for chapter numbers)
 * -> Future chapters of this book are auto-filtered
 */
@Entity(
    tableName = "manual_content_marks",
    // Note: We intentionally do NOT use a foreign key to Track here.
    // Artist-level marks should persist even after the original track is deleted,
    // as they are meant to filter FUTURE content from that artist.
    // If we had CASCADE delete, deleting an orphaned track would also delete
    // the artist block, defeating the purpose.
    indices = [
        Index(value = ["original_title", "original_artist"], unique = true),
        Index(value = ["target_track_id"]),
        Index(value = ["content_type"]),
        Index(value = ["pattern_type", "original_artist"]) // For faster artist-level lookups
    ]
)
data class ManualContentMark(
    @PrimaryKey(autoGenerate = true) 
    val id: Long = 0,
    
    /**
     * Reference to the original track that was marked.
     * Used for deletion cascade.
     */
    @ColumnInfo(name = "target_track_id")
    val targetTrackId: Long,
    
    /**
     * Pattern type: TITLE, ARTIST, or ALBUM.
     * Determines which field to match against.
     */
    @ColumnInfo(name = "pattern_type")
    val patternType: String,
    
    /**
     * Original title of the marked track.
     * Used for exact matching.
     */
    @ColumnInfo(name = "original_title")
    val originalTitle: String,
    
    /**
     * Original artist of the marked track.
     * Used for exact matching.
     */
    @ColumnInfo(name = "original_artist")
    val originalArtist: String,
    
    /**
     * Pattern value for matching similar content.
     * May contain SQL wildcards (%) for flexible matching.
     * Example: "Chapter % of Book Name" matches "Chapter 1 of Book Name", "Chapter 2 of Book Name", etc.
     */
    @ColumnInfo(name = "pattern_value")
    val patternValue: String,
    
    /**
     * Content type: PODCAST or AUDIOBOOK.
     */
    @ColumnInfo(name = "content_type")
    val contentType: String,
    
    /**
     * Timestamp when this mark was created.
     */
    @ColumnInfo(name = "marked_at")
    val markedAt: Long = System.currentTimeMillis()
)

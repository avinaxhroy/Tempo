package me.avinas.tempo.data.local

import android.util.Log
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.avinas.tempo.data.local.dao.*
import me.avinas.tempo.data.local.entities.*

@Database(
    entities = [
        Track::class,
        ListeningEvent::class,
        Artist::class,
        Album::class,
        EnrichedMetadata::class,
        UserPreferences::class,
        TrackArtist::class  // New junction table
    ],
    version = 15, // Deezer and Last.fm artist image URLs for comprehensive artist image fallback
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun listeningEventDao(): ListeningEventDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun enrichedMetadataDao(): EnrichedMetadataDao
    abstract fun userPreferencesDao(): UserPreferencesDao
    abstract fun statsDao(): StatsDao
    abstract fun trackArtistDao(): TrackArtistDao  // New DAO
    
    companion object {
        private const val TAG = "AppDatabase"
        
        /**
         * Migration from version 6 to 7: Add enhanced tracking columns to listening_events.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add new columns with default values for backward compatibility
                db.execSQL("ALTER TABLE listening_events ADD COLUMN was_skipped INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE listening_events ADD COLUMN is_replay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE listening_events ADD COLUMN estimated_duration_ms INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE listening_events ADD COLUMN pause_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE listening_events ADD COLUMN session_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE listening_events ADD COLUMN end_timestamp INTEGER DEFAULT NULL")
                
                // Create indices for efficient queries on the new columns
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_session_id ON listening_events(session_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_was_skipped ON listening_events(was_skipped)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_is_replay ON listening_events(is_replay)")
            }
        }
        
        /**
         * Migration from version 7 to 8: Add proper relational schema.
         * 
         * Changes:
         * 1. Add normalized_name, country, artist_type columns to artists table
         * 2. Add primary_artist_id foreign key to tracks table
         * 3. Create track_artists junction table for many-to-many relationship
         * 4. Migrate existing data to create artist records and link them
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 7 to 8")
                
                // Step 1: Add new columns to artists table
                db.execSQL("ALTER TABLE artists ADD COLUMN normalized_name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE artists ADD COLUMN country TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE artists ADD COLUMN artist_type TEXT DEFAULT NULL")
                
                // Update normalized_name for existing artists
                db.execSQL("""
                    UPDATE artists SET normalized_name = LOWER(TRIM(name))
                    WHERE normalized_name = ''
                """)
                
                // Create index on normalized_name (can't be unique during migration due to potential duplicates)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artists_normalized_name ON artists(normalized_name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artists_name ON artists(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artists_spotify_id ON artists(spotify_id)")
                
                // Step 2: Add primary_artist_id column to tracks table
                db.execSQL("ALTER TABLE tracks ADD COLUMN primary_artist_id INTEGER DEFAULT NULL REFERENCES artists(id) ON DELETE SET NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_primary_artist_id ON tracks(primary_artist_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_title_artist ON tracks(title, artist)")
                
                // Step 3: Create track_artists junction table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS track_artists (
                        track_id INTEGER NOT NULL,
                        artist_id INTEGER NOT NULL,
                        role TEXT NOT NULL DEFAULT 'PRIMARY',
                        credit_order INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(track_id, artist_id),
                        FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE,
                        FOREIGN KEY(artist_id) REFERENCES artists(id) ON DELETE CASCADE
                    )
                """)
                
                // Create indices on track_artists
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_artists_track_id ON track_artists(track_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_artists_artist_id ON track_artists(artist_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_artists_track_id_role ON track_artists(track_id, role)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_artists_artist_id_role ON track_artists(artist_id, role)")
                
                // Step 4: Migrate existing data
                // Create artists from unique artist names in tracks table
                db.execSQL("""
                    INSERT OR IGNORE INTO artists (name, normalized_name, image_url, musicbrainz_id, spotify_id)
                    SELECT DISTINCT 
                        artist, 
                        LOWER(TRIM(artist)),
                        NULL,
                        NULL,
                        NULL
                    FROM tracks
                    WHERE artist IS NOT NULL AND artist != ''
                    AND NOT EXISTS (
                        SELECT 1 FROM artists WHERE LOWER(TRIM(name)) = LOWER(TRIM(tracks.artist))
                    )
                """)
                
                // Link tracks to their primary artists
                db.execSQL("""
                    UPDATE tracks 
                    SET primary_artist_id = (
                        SELECT id FROM artists 
                        WHERE LOWER(TRIM(artists.name)) = LOWER(TRIM(tracks.artist))
                        LIMIT 1
                    )
                    WHERE primary_artist_id IS NULL
                    AND artist IS NOT NULL AND artist != ''
                """)
                
                // Create track_artists relationships for primary artists
                db.execSQL("""
                    INSERT OR IGNORE INTO track_artists (track_id, artist_id, role, credit_order)
                    SELECT 
                        tracks.id,
                        artists.id,
                        'PRIMARY',
                        0
                    FROM tracks
                    INNER JOIN artists ON tracks.primary_artist_id = artists.id
                    WHERE tracks.primary_artist_id IS NOT NULL
                """)
                
                Log.i(TAG, "Migration from version 7 to 8 completed successfully")
            }
        }
        
        /**
         * Migration from version 8 to 9: Add performance optimization indexes.
         * 
         * These composite indexes significantly improve query performance for:
         * - Time-range queries on listening_events
         * - Top tracks/artists queries
         * - Stats calculations
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 8 to 9 - Adding performance indexes")
                
                // Composite indexes for listening_events time-range queries
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_timestamp_track_id 
                    ON listening_events(timestamp, track_id)
                """)
                
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_track_id_timestamp 
                    ON listening_events(track_id, timestamp DESC)
                """)
                
                // Covering index for common stats queries
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_stats 
                    ON listening_events(timestamp, track_id, playDuration, completionPercentage)
                """)
                
                // Index for enriched_metadata lookups
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_enriched_metadata_status_timestamp 
                    ON enriched_metadata(enrichment_status, last_enrichment_attempt)
                """)
                
                // Index for track title search
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_tracks_title_lower 
                    ON tracks(title COLLATE NOCASE)
                """)
                
                Log.i(TAG, "Migration from version 8 to 9 completed successfully")
            }
        }
        
        /**
         * Migration from version 9 to 10: Add ReccoBeats audio features support.
         * 
         * ReccoBeats is a FREE API that provides audio features similar to Spotify's 
         * deprecated endpoint. This migration adds:
         * - reccobeats_id column to store ReccoBeats track ID
         * - audio_features_source column to track where audio features came from
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 9 to 10 - Adding ReccoBeats support")
                
                // Add ReccoBeats ID column
                db.execSQL("""
                    ALTER TABLE enriched_metadata 
                    ADD COLUMN reccobeats_id TEXT DEFAULT NULL
                """)
                
                // Add audio features source column
                // Values: NONE, SPOTIFY, RECCOBEATS, RECCOBEATS_ANALYSIS, LOCAL_ANALYSIS
                db.execSQL("""
                    ALTER TABLE enriched_metadata 
                    ADD COLUMN audio_features_source TEXT NOT NULL DEFAULT 'NONE'
                """)
                
                // Update existing records that have audio features from Spotify
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET audio_features_source = 'SPOTIFY' 
                    WHERE audio_features_json IS NOT NULL 
                    AND spotify_id IS NOT NULL
                """)
                
                // Create index for ReccoBeats ID lookups
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_enriched_metadata_reccobeats_id 
                    ON enriched_metadata(reccobeats_id)
                """)
                
                Log.i(TAG, "Migration from version 9 to 10 completed successfully")
            }
        }
        
        /**
         * Migration from version 10 to 11.
         * 
         * Adds spotify_preview_url column to store Spotify 30-second preview URL.
         * This is used for ReccoBeats audio analysis fallback when database lookup fails.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 10 to 11 - Adding Spotify preview URL")
                
                // Add Spotify preview URL column
                db.execSQL("""
                    ALTER TABLE enriched_metadata 
                    ADD COLUMN spotify_preview_url TEXT DEFAULT NULL
                """)
                
                Log.i(TAG, "Migration from version 10 to 11 completed successfully")
            }
        }
        
        /**
         * Migration from version 11 to 12.
         * 
         * Adds enhanced robustness tracking fields to listening_events:
         * - total_pause_duration_ms: Total time the track was paused
         * - seek_count: Number of seek operations (forward/backward)
         * - position_updates_count: Number of position updates (for validation)
         * - was_interrupted: Whether the session was interrupted (app kill, crash)
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 11 to 12 - Adding enhanced tracking fields")
                
                // Add robustness tracking columns to listening_events
                db.execSQL("""
                    ALTER TABLE listening_events 
                    ADD COLUMN total_pause_duration_ms INTEGER NOT NULL DEFAULT 0
                """)
                
                db.execSQL("""
                    ALTER TABLE listening_events 
                    ADD COLUMN seek_count INTEGER NOT NULL DEFAULT 0
                """)
                
                db.execSQL("""
                    ALTER TABLE listening_events 
                    ADD COLUMN position_updates_count INTEGER NOT NULL DEFAULT 0
                """)
                
                db.execSQL("""
                    ALTER TABLE listening_events 
                    ADD COLUMN was_interrupted INTEGER NOT NULL DEFAULT 0
                """)
                
                Log.i(TAG, "Migration from version 11 to 12 completed successfully")
            }
        }
        
        /**
         * Migration from version 12 to 13.
         * 
         * Adds iTunes and music streaming enhancements:
         * - itunes_artist_image_url: Artist images from iTunes (fallback for Spotify/Last.fm)
         * - apple_music_url: Direct link to track on Apple Music
         * - spotify_track_url: Direct link to track on Spotify
         * - release_date_full: Full ISO 8601 release date (not just year)
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 12 to 13 - Adding iTunes and music links")
                
                // Add iTunes artist image URL
                db.execSQL("""
                    ALTER TABLE enriched_metadata 
                    ADD COLUMN itunes_artist_image_url TEXT DEFAULT NULL
                """)
                
                // Add Apple Music URL
                db.execSQL("""
                    ALTER TABLE enriched_metadata 
                    ADD COLUMN apple_music_url TEXT DEFAULT NULL
                """)
                
                // Add Spotify track URL
                db.execSQL("""
                    ALTER TABLE enriched_metadata 
                    ADD COLUMN spotify_track_url TEXT DEFAULT NULL
                """)
                
                // Add full release date (ISO 8601 format)
                db.execSQL("""
                    ALTER TABLE enriched_metadata 
                    ADD COLUMN release_date_full TEXT DEFAULT NULL
                """)
                
                // Populate release_date_full from existing release_date where available
                // release_date already contains partial ISO format in many cases
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET release_date_full = release_date 
                    WHERE release_date IS NOT NULL 
                    AND LENGTH(release_date) >= 10
                """)
                
                Log.i(TAG, "Migration from version 12 to 13 completed successfully")
            }
        }
        
        /**
         * Migration from version 13 to 14.
         * 
         * Adds preview_url column to enriched_metadata table.
         * This stores the direct audio stream URL (usually .m4a from iTunes) 
         * for 30-second song previews.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 13 to 14 - Adding preview_url")
                
                db.execSQL("""
                    ALTER TABLE enriched_metadata 
                    ADD COLUMN preview_url TEXT DEFAULT NULL
                """)
                
                Log.i(TAG, "Migration from version 13 to 14 completed successfully")
            }
        }
        
        /**
         * Migration from version 14 to 15.
         * 
         * Adds Deezer and Last.fm artist image URL columns for comprehensive
         * artist image fallback chain: Spotify > iTunes > Last.fm > Deezer
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 14 to 15 - Adding Deezer/Last.fm artist images")
                
                // Add Deezer artist image URL
                db.execSQL("""
                    ALTER TABLE enriched_metadata 
                    ADD COLUMN deezer_artist_image_url TEXT DEFAULT NULL
                """)
                
                // Add Last.fm artist image URL
                db.execSQL("""
                    ALTER TABLE enriched_metadata 
                    ADD COLUMN lastfm_artist_image_url TEXT DEFAULT NULL
                """)
                
                Log.i(TAG, "Migration from version 14 to 15 completed successfully")
            }
        }
        
        /**
         * All migrations in order.
         */
        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15
        )
    }
}


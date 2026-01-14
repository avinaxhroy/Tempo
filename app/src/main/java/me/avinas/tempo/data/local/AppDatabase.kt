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
        TrackArtist::class,  // Track-Artist junction table
        TrackAlias::class,   // Smart metadata aliases for tracks
        ManualContentMark::class,  // Content filtering marks
        ArtistAlias::class   // Artist merge aliases
    ],
    version = 24, // Add Spotlight reminder tracking to UserPreferences
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
    abstract fun trackArtistDao(): TrackArtistDao
    abstract fun trackAliasDao(): TrackAliasDao
    abstract fun manualContentMarkDao(): ManualContentMarkDao  // Content filtering DAO
    abstract fun artistAliasDao(): ArtistAliasDao  // Artist merge DAO
    
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
         * Migration from version 15 to 16.
         * 
         * Adds album_art_source column to track where album art came from.
         * This enables priority-based replacement where API sources can replace
         * local device-extracted art, but local art won't replace higher quality API art.
         * 
         * Priority: SPOTIFY > MUSICBRAINZ > ITUNES > DEEZER > LOCAL > NONE
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 15 to 16 - Adding album art source tracking")
                
                // Add album_art_source column with default value
                db.execSQL("""
                    ALTER TABLE enriched_metadata 
                    ADD COLUMN album_art_source TEXT NOT NULL DEFAULT 'NONE'
                """)
                
                // For existing records with album art, infer the source:
                // - If has MusicBrainz release ID, assume MUSICBRAINZ
                // - If URL starts with http(s) (API source), mark as ITUNES (safe default)
                // - If URL starts with file:// (local), mark as LOCAL
                
                // Mark MusicBrainz sourced art
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_source = 'MUSICBRAINZ' 
                    WHERE album_art_url IS NOT NULL 
                    AND musicbrainz_release_id IS NOT NULL
                """)
                
                // Mark Spotify sourced art
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_source = 'SPOTIFY' 
                    WHERE album_art_url IS NOT NULL 
                    AND spotify_id IS NOT NULL
                    AND album_art_source = 'NONE'
                """)
                
                // Mark local file art
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_source = 'LOCAL' 
                    WHERE album_art_url IS NOT NULL 
                    AND album_art_url LIKE 'file://%'
                    AND album_art_source = 'NONE'
                """)
                
                // Mark remaining API art as ITUNES (safe default for http URLs)
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_source = 'ITUNES' 
                    WHERE album_art_url IS NOT NULL 
                    AND (album_art_url LIKE 'http://%' OR album_art_url LIKE 'https://%')
                    AND album_art_source = 'NONE'
                """)
                
                Log.i(TAG, "Migration from version 15 to 16 completed successfully")
            }
        }
        
        /**
         * Migration from version 16 to 17.
         * 
         * Fix HTTP URLs to HTTPS for Cover Art Archive images.
         * Cover Art Archive returns HTTP URLs but HTTPS works better and avoids redirect issues.
         * This migration updates all existing album art URLs to use HTTPS.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 16 to 17 - Fixing HTTP URLs to HTTPS")
                
                // Fix Cover Art Archive URLs in enriched_metadata table
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_url = REPLACE(album_art_url, 'http://coverartarchive.org', 'https://coverartarchive.org')
                    WHERE album_art_url LIKE 'http://coverartarchive.org%'
                """)
                
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_url_small = REPLACE(album_art_url_small, 'http://coverartarchive.org', 'https://coverartarchive.org')
                    WHERE album_art_url_small LIKE 'http://coverartarchive.org%'
                """)
                
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_url_large = REPLACE(album_art_url_large, 'http://coverartarchive.org', 'https://coverartarchive.org')
                    WHERE album_art_url_large LIKE 'http://coverartarchive.org%'
                """)
                
                // Fix Internet Archive URLs (used by Cover Art Archive)
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_url = REPLACE(album_art_url, 'http://archive.org', 'https://archive.org')
                    WHERE album_art_url LIKE 'http://archive.org%'
                """)
                
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_url_small = REPLACE(album_art_url_small, 'http://archive.org', 'https://archive.org')
                    WHERE album_art_url_small LIKE 'http://archive.org%'
                """)
                
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_url_large = REPLACE(album_art_url_large, 'http://archive.org', 'https://archive.org')
                    WHERE album_art_url_large LIKE 'http://archive.org%'
                """)
                
                // Fix Internet Archive CDN URLs (ia*.us.archive.org)
                // These use HTTP by default but support HTTPS
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_url = 'https' || SUBSTR(album_art_url, 5)
                    WHERE album_art_url LIKE 'http://ia%.us.archive.org%'
                """)
                
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_url_small = 'https' || SUBSTR(album_art_url_small, 5)
                    WHERE album_art_url_small LIKE 'http://ia%.us.archive.org%'
                """)
                
                db.execSQL("""
                    UPDATE enriched_metadata 
                    SET album_art_url_large = 'https' || SUBSTR(album_art_url_large, 5)
                    WHERE album_art_url_large LIKE 'http://ia%.us.archive.org%'
                """)
                
                // Also fix URLs in the tracks table
                db.execSQL("""
                    UPDATE tracks 
                    SET album_art_url = REPLACE(album_art_url, 'http://coverartarchive.org', 'https://coverartarchive.org')
                    WHERE album_art_url LIKE 'http://coverartarchive.org%'
                """)
                
                db.execSQL("""
                    UPDATE tracks 
                    SET album_art_url = REPLACE(album_art_url, 'http://archive.org', 'https://archive.org')
                    WHERE album_art_url LIKE 'http://archive.org%'
                """)
                
                db.execSQL("""
                    UPDATE tracks 
                    SET album_art_url = 'https' || SUBSTR(album_art_url, 5)
                    WHERE album_art_url LIKE 'http://ia%.us.archive.org%'
                """)
                
                // Also fix URLs in albums table if present
                db.execSQL("""
                    UPDATE albums 
                    SET artwork_url = REPLACE(artwork_url, 'http://coverartarchive.org', 'https://coverartarchive.org')
                    WHERE artwork_url LIKE 'http://coverartarchive.org%'
                """)
                
                db.execSQL("""
                    UPDATE albums 
                    SET artwork_url = REPLACE(artwork_url, 'http://archive.org', 'https://archive.org')
                    WHERE artwork_url LIKE 'http://archive.org%'
                """)
                
                db.execSQL("""
                    UPDATE albums 
                    SET artwork_url = 'https' || SUBSTR(artwork_url, 5)
                    WHERE artwork_url LIKE 'http://ia%.us.archive.org%'
                """)
                
                Log.i(TAG, "Migration from version 16 to 17 completed successfully")
            }
        }
        
        /**
         * Migration from version 17 to 18.
         * 
         * Adds track_aliases table for smart metadata deduplication.
         * This allows users to manually merge duplicate tracks and have the system
         * remember the mapping for future plays.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 17 to 18 - Adding track_aliases table")
                
                // Step 1: Create track_aliases table for manual merge tracking
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS track_aliases (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        target_track_id INTEGER NOT NULL,
                        original_title TEXT NOT NULL,
                        original_artist TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(target_track_id) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                """)
                
                // Step 2: Create indices for efficient lookups
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_track_aliases_original_title_original_artist ON track_aliases(original_title, original_artist)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_track_aliases_target_track_id ON track_aliases(target_track_id)")
                
                // Step 3: Add mergeAlternateVersions column to user_preferences
                // Default to 1 (true) for cleaner library experience
                db.execSQL("ALTER TABLE user_preferences ADD COLUMN mergeAlternateVersions INTEGER NOT NULL DEFAULT 1")
                
                Log.i(TAG, "Migration from version 17 to 18 completed successfully")
            }
        }
        
        /**
         * Migration from version 18 to 19.
         * 
         * Adds comprehensive content filtering support:
         * 1. Add content_type column to tracks table (MUSIC, PODCAST, AUDIOBOOK)
         * 2. Add filterPodcasts and filterAudiobooks to user_preferences
         * 3. Create manual_content_marks table for user-defined filtering patterns
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 18 to 19 - Adding content filtering")
                
                // Step 1: Add content_type column to tracks table
                db.execSQL("""
                    ALTER TABLE tracks 
                    ADD COLUMN content_type TEXT NOT NULL DEFAULT 'MUSIC'
                """)
                
                // Create index for content type filtering
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_tracks_content_type 
                    ON tracks(content_type)
                """)
                
                // Step 2: Add filter preferences to user_preferences
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN filterPodcasts INTEGER NOT NULL DEFAULT 1
                """)
                
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN filterAudiobooks INTEGER NOT NULL DEFAULT 1
                """)
                
                // Step 3: Create manual_content_marks table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS manual_content_marks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        target_track_id INTEGER NOT NULL,
                        pattern_type TEXT NOT NULL,
                        original_title TEXT NOT NULL,
                        original_artist TEXT NOT NULL,
                        pattern_value TEXT NOT NULL,
                        content_type TEXT NOT NULL,
                        marked_at INTEGER NOT NULL,
                        FOREIGN KEY(target_track_id) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                """)
                
                // Create indices for efficient pattern matching
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_manual_content_marks_original_title_original_artist 
                    ON manual_content_marks(original_title, original_artist)
                """)
                
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_manual_content_marks_target_track_id 
                    ON manual_content_marks(target_track_id)
                """)
                
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_manual_content_marks_content_type 
                    ON manual_content_marks(content_type)
                """)
                
                Log.i(TAG, "Migration from version 18 to 19 completed successfully")
            }
        }

        /**
         * Migration from version 19 to 20.
         * 
         * Adds 'hasSeenHistoryCoachMark' to user_preferences to track
         * if the user has been shown the history long-press tutorial.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 19 to 20")
                
                // Add column with default false (0)
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN hasSeenHistoryCoachMark INTEGER NOT NULL DEFAULT 0
                """)
                
                Log.i(TAG, "Migration from version 19 to 20 completed successfully")
            }
        }

        /**
         * Migration from version 20 to 21.
         * 
         * Removes foreign key from manual_content_marks table.
         * Artist-level marks should persist even after the original track is deleted,
         * as they are meant to filter FUTURE content from that artist.
         * With CASCADE delete, deleting an orphaned track would also delete
         * the artist block, defeating the purpose.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 20 to 21")
                
                // Check if the old table exists (it should from migration 18->19)
                val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='manual_content_marks'")
                val tableExists = cursor.moveToFirst()
                cursor.close()
                
                if (!tableExists) {
                    // Table doesn't exist - create it fresh without foreign key
                    Log.i(TAG, "manual_content_marks table not found, creating fresh")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS manual_content_marks (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            target_track_id INTEGER NOT NULL,
                            pattern_type TEXT NOT NULL,
                            original_title TEXT NOT NULL,
                            original_artist TEXT NOT NULL,
                            pattern_value TEXT NOT NULL,
                            content_type TEXT NOT NULL,
                            marked_at INTEGER NOT NULL DEFAULT 0
                        )
                    """)
                } else {
                    // Table exists - migrate it to remove foreign key
                    // SQLite doesn't support dropping foreign keys directly.
                    // We need to recreate the table without the foreign key.
                    
                    Log.i(TAG, "Migrating manual_content_marks to remove foreign key")
                    
                    // 1. Create new table without foreign key
                    db.execSQL("""
                        CREATE TABLE manual_content_marks_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            target_track_id INTEGER NOT NULL,
                            pattern_type TEXT NOT NULL,
                            original_title TEXT NOT NULL,
                            original_artist TEXT NOT NULL,
                            pattern_value TEXT NOT NULL,
                            content_type TEXT NOT NULL,
                            marked_at INTEGER NOT NULL DEFAULT 0
                        )
                    """)
                    
                    // 2. Copy data from old table (preserving all user marks!)
                    db.execSQL("""
                        INSERT INTO manual_content_marks_new 
                        SELECT id, target_track_id, pattern_type, original_title, 
                               original_artist, pattern_value, content_type, marked_at
                        FROM manual_content_marks
                    """)
                    
                    // 3. Drop old table
                    db.execSQL("DROP TABLE manual_content_marks")
                    
                    // 4. Rename new table
                    db.execSQL("ALTER TABLE manual_content_marks_new RENAME TO manual_content_marks")
                }
                
                // 5. Recreate indices (safe with IF NOT EXISTS)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_manual_content_marks_original_title_original_artist ON manual_content_marks(original_title, original_artist)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_content_marks_target_track_id ON manual_content_marks(target_track_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_content_marks_content_type ON manual_content_marks(content_type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_content_marks_pattern_type_original_artist ON manual_content_marks(pattern_type, original_artist)")
                
                Log.i(TAG, "Migration from version 20 to 21 completed successfully")
            }
        }

        /**
         * Migration from version 21 to 22.
         * 
         * Adds boolean flags for the new "Passive Game" Walkthrough system:
         * - hasSeenSpotlightTutorial
         * - hasSeenStatsSortTutorial
         * - hasSeenStatsItemClickTutorial
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 21 to 22")
                
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN hasSeenSpotlightTutorial INTEGER NOT NULL DEFAULT 0
                """)
                
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN hasSeenStatsSortTutorial INTEGER NOT NULL DEFAULT 0
                """)
                
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN hasSeenStatsItemClickTutorial INTEGER NOT NULL DEFAULT 0
                """)
                
                Log.i(TAG, "Migration from version 21 to 22 completed successfully")
            }
        }

        /**
         * Migration from version 22 to 23.
         * 
         * Adds artist_aliases table for artist merging feature.
         * This allows users to merge duplicate artists (e.g., "Billie Eilish - Topic" into "Billie Eilish")
         * and has the system remember the mapping for future plays.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 22 to 23 - Adding artist_aliases table")
                
                // Create artist_aliases table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS artist_aliases (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        target_artist_id INTEGER NOT NULL,
                        original_name TEXT NOT NULL,
                        original_name_normalized TEXT NOT NULL,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(target_artist_id) REFERENCES artists(id) ON DELETE CASCADE
                    )
                """)
                
                // Create indices for efficient lookups
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_artist_aliases_original_name_normalized ON artist_aliases(original_name_normalized)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artist_aliases_target_artist_id ON artist_aliases(target_artist_id)")
                
                Log.i(TAG, "Migration from version 22 to 23 completed successfully")
            }
        }

        /**
         * Migration from version 23 to 24.
         * 
         * Adds Spotlight Story reminder tracking fields to user_preferences:
         * - lastMonthlyReminderShown: Date when monthly reminder was last displayed (YYYY-MM-DD)
         * - lastYearlyReminderShown: Date when yearly reminder was last displayed (YYYY-MM-DD)
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 23 to 24 - Adding Spotlight reminder tracking")
                
                // Add Spotlight reminder tracking columns
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN lastMonthlyReminderShown TEXT DEFAULT NULL
                """)
                
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN lastYearlyReminderShown TEXT DEFAULT NULL
                """)
                
                Log.i(TAG, "Migration from version 23 to 24 completed successfully")
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
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24
        )
        
    }
}

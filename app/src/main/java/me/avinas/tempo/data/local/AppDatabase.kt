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
        ArtistAlias::class,  // Artist merge aliases
        AppPreference::class, // User-controlled app tracking preferences
        LastFmImportMetadata::class, // Last.fm import session tracking
        ScrobbleArchive::class, // Compressed archive for long-tail scrobbles
        UserLevel::class,    // Gamification: user level & XP
        Badge::class          // Gamification: achievement badges
    ],
    version = 31, // Gamification: levels & badges
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
    abstract fun appPreferenceDao(): AppPreferenceDao  // User app preferences DAO
    abstract fun lastFmImportMetadataDao(): LastFmImportMetadataDao  // Last.fm import tracking
    abstract fun scrobbleArchiveDao(): ScrobbleArchiveDao  // Scrobble archive DAO
    abstract fun gamificationDao(): GamificationDao  // Gamification: levels & badges
    
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
         * Migration from version 24 to 25.
         * 
         * Adds app_preferences table for user-controlled app selection.
         * Seeds the table with ALL apps from the original hardcoded sets to ensure
         * existing users don't lose tracking for any apps they were using.
         * 
         * DATA PRESERVATION: This migration ONLY creates a new table - it does NOT
         * modify or delete any existing data (tracks, listening_events, etc.).
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 24 to 25 - Adding app_preferences table")
                
                // Create app_preferences table
                // NOTE: Do NOT use DEFAULT clauses or create indices here - the entity
                // class doesn't declare @ColumnInfo(defaultValue=...) or @Index annotations,
                // so Room's schema validation will fail if these are present in the table.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_preferences (
                        packageName TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        isUserAdded INTEGER NOT NULL,
                        isBlocked INTEGER NOT NULL,
                        category TEXT NOT NULL,
                        addedAt INTEGER NOT NULL
                    )
                """)
                

                val currentTime = System.currentTimeMillis()
                
                // =====================================================================
                // MUSIC APPS - ALL apps from the original MUSIC_APPS set (enabled)
                // This ensures existing users can continue tracking all apps they used
                // =====================================================================
                val musicApps = listOf(
                    // Major Streaming Services
                    Pair("com.spotify.music", "Spotify"),
                    Pair("com.google.android.apps.youtube.music", "YouTube Music"),
                    Pair("com.apple.android.music", "Apple Music"),
                    Pair("com.amazon.mp3", "Amazon Music"),
                    Pair("com.soundcloud.android", "SoundCloud"),
                    Pair("deezer.android.app", "Deezer"),
                    Pair("com.pandora.android", "Pandora"),
                    Pair("com.aspiro.tidal", "Tidal"),
                    Pair("com.tidal.android", "Tidal (Old)"),
                    Pair("com.qobuz.music", "Qobuz"),
                    Pair("ru.yandex.music", "Yandex Music"),
                    
                    // Indian Music Services
                    Pair("com.jio.media.jiobeats", "JioSaavn"),
                    Pair("com.gaana", "Gaana"),
                    Pair("com.bsbportal.music", "Wynk Music"),
                    Pair("com.hungama.myplay.activity", "Hungama Music"),
                    Pair("in.startv.hotstar.music", "Hotstar Music"),
                    Pair("com.moonvideo.android.resso", "Resso"),
                    
                    // Device Manufacturers
                    Pair("com.samsung.android.app.music", "Samsung Music"),
                    Pair("com.sec.android.app.music", "Samsung Music (Old)"),
                    Pair("com.miui.player", "Mi Music"),
                    
                    // Revanced/Vanced Variants
                    Pair("app.revanced.android.youtube.music", "YouTube Music ReVanced"),
                    Pair("app.revanced.android.apps.youtube.music", "YouTube Music ReVanced"),
                    Pair("com.vanced.android.youtube.music", "YouTube Music Vanced"),
                    
                    // Other Streaming
                    Pair("com.audiomack", "Audiomack"),
                    Pair("com.mmm.trebelmusic", "Trebel"),
                    Pair("nugs.net", "Nugs.net"),
                    Pair("net.nugs.multiband", "Nugs.net (Multiband)"),
                    
                    // Open Source / FOSS Music Clients
                    Pair("com.dd3boh.outertune", "OuterTune"),
                    Pair("com.zionhuang.music", "InnerTune"),
                    Pair("it.vfsfitvnm.vimusic", "ViMusic"),
                    Pair("oss.krtirtho.spotube", "Spotube"),
                    Pair("com.shadow.blackhole", "BlackHole"),
                    Pair("com.anandnet.harmonymusic", "Harmony Music"),
                    Pair("it.fast4x.rimusic", "RiMusic"),
                    Pair("com.msob7y.namida", "Namida"),
                    Pair("com.metrolist.music", "Metrolist"),
                    Pair("com.gokadzev.musify", "Musify"),
                    Pair("com.gokadzev.musify.fdroid", "Musify (F-Droid)"),
                    Pair("ls.bloomee.musicplayer", "BloomeeTunes"),
                    Pair("com.maxrave.simpmusic", "SimpMusic"),
                    Pair("it.ncaferra.pixelplayerfree", "Pixel Player"),
                    Pair("com.theveloper.pixelplay", "PixelPlay"),
                    Pair("com.singularity.gramophone", "Gramophone"),
                    Pair("player.phonograph.plus", "Phonograph Plus"),
                    Pair("org.oxycblt.auxio", "Auxio"),
                    Pair("com.maloy.muzza", "Muzza"),
                    Pair("uk.co.projectneon.echo", "Echo"),
                    Pair("com.shabinder.spotiflyer", "SpotiFlyer"),
                    Pair("com.kapp.youtube.final", "YMusic"),
                    Pair("org.schabi.newpipe", "NewPipe"),
                    Pair("org.polymorphicshade.newpipe", "NewPipe (Fork)"),
                    
                    // Popular Offline Players
                    Pair("com.maxmpz.audioplayer", "Poweramp"),
                    Pair("in.krosbits.musicolet", "Musicolet"),
                    Pair("com.kodarkooperativet.blackplayerfree", "BlackPlayer Free"),
                    Pair("com.kodarkooperativet.blackplayerex", "BlackPlayer EX"),
                    Pair("com.rhmsoft.pulsar", "Pulsar"),
                    Pair("com.neutroncode.mp", "Neutron Player"),
                    Pair("gonemad.gmmp", "GoneMad"),
                    Pair("code.name.monkey.retromusic", "Retro Music Player"),
                    Pair("com.piyush.music", "Oto Music"),
                    Pair("com.simplecity.amp_pro", "Shuttle+"),
                    Pair("ru.stellio.player", "Stellio"),
                    Pair("io.stellio.music", "Stellio (Alt)"),
                    Pair("com.frolo.musp", "Frolomuse"),
                    Pair("com.rhmsoft.omnia", "Omnia")
                )
                
                musicApps.forEach { (pkg, name) ->
                    db.execSQL("""
                        INSERT OR IGNORE INTO app_preferences 
                        (packageName, displayName, isEnabled, isUserAdded, isBlocked, category, addedAt)
                        VALUES ('$pkg', '$name', 1, 0, 0, 'MUSIC', $currentTime)
                    """)
                }
                
                // =====================================================================
                // PODCAST APPS - Seed as enabled (user can disable if they don't want)
                // =====================================================================
                val podcastApps = listOf(
                    Pair("com.google.android.apps.podcasts", "Google Podcasts"),
                    Pair("fm.player", "Player FM"),
                    Pair("au.com.shiftyjelly.pocketcasts", "Pocket Casts"),
                    Pair("com.bambuna.podcastaddict", "Podcast Addict"),
                    Pair("com.clearchannel.iheartradio.controller", "iHeartRadio"),
                    Pair("app.tunein.player", "TuneIn Radio"),
                    Pair("com.stitcher.app", "Stitcher"),
                    Pair("com.castbox.player", "Castbox"),
                    Pair("com.apple.android.podcasts", "Apple Podcasts"),
                    Pair("fm.castbox.audiobook.radio.podcast", "Castbox Variant")
                )
                
                podcastApps.forEach { (pkg, name) ->
                    db.execSQL("""
                        INSERT OR IGNORE INTO app_preferences 
                        (packageName, displayName, isEnabled, isUserAdded, isBlocked, category, addedAt)
                        VALUES ('$pkg', '$name', 1, 0, 0, 'PODCAST', $currentTime)
                    """)
                }
                
                // =====================================================================
                // AUDIOBOOK APPS - Seed as enabled 
                // =====================================================================
                val audiobookApps = listOf(
                    Pair("com.audible.application", "Audible"),
                    Pair("com.google.android.apps.books", "Google Play Books"),
                    Pair("com.audiobooks.android.audiobooks", "Audiobooks.com"),
                    Pair("com.scribd.app.reader0", "Scribd"),
                    Pair("com.storytel", "Storytel"),
                    Pair("fm.libro", "Libro.fm"),
                    Pair("com.kobo.books.ereader", "Kobo Books")
                )
                
                audiobookApps.forEach { (pkg, name) ->
                    db.execSQL("""
                        INSERT OR IGNORE INTO app_preferences 
                        (packageName, displayName, isEnabled, isUserAdded, isBlocked, category, addedAt)
                        VALUES ('$pkg', '$name', 1, 0, 0, 'AUDIOBOOK', $currentTime)
                    """)
                }
                
                // =====================================================================
                // BLOCKED APPS - Video apps, social media, browsers (blocked by default)
                // These were always blocked and should remain blocked
                // =====================================================================
                val blockedApps = listOf(
                    // Video Streaming
                    Triple("com.google.android.youtube", "YouTube", "VIDEO"),
                    Triple("com.google.android.apps.youtube", "YouTube", "VIDEO"),
                    Triple("app.revanced.android.youtube", "YouTube ReVanced", "VIDEO"),
                    Triple("com.netflix.mediaclient", "Netflix", "VIDEO"),
                    Triple("com.amazon.avod.thirdpartyclient", "Prime Video", "VIDEO"),
                    Triple("com.disney.disneyplus", "Disney+", "VIDEO"),
                    Triple("in.startv.hotstar", "Hotstar", "VIDEO"),
                    Triple("com.hotstar.android", "Hotstar", "VIDEO"),
                    Triple("tv.twitch.android.app", "Twitch", "VIDEO"),
                    
                    // Social Media
                    Triple("com.zhiliaoapp.musically", "TikTok", "VIDEO"),
                    Triple("com.ss.android.ugc.trill", "TikTok", "VIDEO"),
                    Triple("com.instagram.android", "Instagram", "VIDEO"),
                    Triple("com.facebook.katana", "Facebook", "VIDEO"),
                    Triple("com.snapchat.android", "Snapchat", "VIDEO"),
                    
                    // Video Players
                    Triple("com.vimeo.android.videoapp", "Vimeo", "VIDEO"),
                    Triple("com.mxtech.videoplayer.ad", "MX Player", "VIDEO"),
                    Triple("com.mxtech.videoplayer.pro", "MX Player Pro", "VIDEO"),
                    Triple("org.videolan.vlc", "VLC", "VIDEO"),
                    Triple("com.google.android.apps.photos", "Google Photos", "VIDEO"),
                    Triple("com.whatsapp", "WhatsApp", "VIDEO"),
                    Triple("org.telegram.messenger", "Telegram", "VIDEO"),
                    Triple("com.google.android.gm", "Gmail", "OTHER"),
                    
                    // Browsers
                    Triple("com.android.chrome", "Chrome", "OTHER"),
                    Triple("com.chrome.beta", "Chrome Beta", "OTHER"),
                    Triple("com.chrome.dev", "Chrome Dev", "OTHER"),
                    Triple("org.mozilla.firefox", "Firefox", "OTHER"),
                    Triple("org.mozilla.firefox_beta", "Firefox Beta", "OTHER"),
                    Triple("com.opera.browser", "Opera", "OTHER"),
                    Triple("com.brave.browser", "Brave", "OTHER"),
                    Triple("com.microsoft.edge", "Microsoft Edge", "OTHER"),
                    Triple("com.sec.android.app.sbrowser", "Samsung Browser", "OTHER"),
                    
                    // Device Gallery/Video Apps
                    Triple("com.samsung.android.video", "Samsung Video", "VIDEO"),
                    Triple("com.miui.videoplayer", "Mi Video", "VIDEO"),
                    Triple("com.miui.gallery", "Mi Gallery", "VIDEO"),
                    Triple("com.google.android.videos", "Google Play Movies", "VIDEO"),
                    Triple("com.google.android.apps.youtube.kids", "YouTube Kids", "VIDEO")
                )
                
                blockedApps.forEach { (pkg, name, category) ->
                    db.execSQL("""
                        INSERT OR IGNORE INTO app_preferences 
                        (packageName, displayName, isEnabled, isUserAdded, isBlocked, category, addedAt)
                        VALUES ('$pkg', '$name', 0, 0, 1, '$category', $currentTime)
                    """)
                }
                
                Log.i(TAG, "Migration from version 24 to 25 completed successfully - seeded ${musicApps.size} music, ${podcastApps.size} podcast, ${audiobookApps.size} audiobook, ${blockedApps.size} blocked apps")
            }
        }

        /**
         * Migration from version 25 to 26.
         * 
         * Adds Spotify Import feature support:
         * - spotifyApiOnlyMode: Boolean flag for using Spotify API instead of notifications
         * - spotifyImportCursor: Cursor for incremental polling
         * - lastSpotifyImportTimestamp: Timestamp of last successful import
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 25 to 26 - Adding Spotify Import fields")
                
                // Add Spotify-API-Only mode flag
                // Default 0 (false) = use notification tracking for all apps including Spotify
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN spotifyApiOnlyMode INTEGER NOT NULL DEFAULT 0
                """)
                
                // Add cursor for incremental polling
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN spotifyImportCursor TEXT DEFAULT NULL
                """)
                
                // Add last import timestamp
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN lastSpotifyImportTimestamp INTEGER DEFAULT NULL
                """)
                
                Log.i(TAG, "Migration from version 25 to 26 completed successfully")
            }
        }

        /**
         * Migration from version 26 to 27.
         * 
         * Adds All-Time Story reminder tracking to user_preferences:
         * - lastAllTimeReminderShown: Date when all-time story notification was sent (YYYY-MM-DD)
         *   This tracks when the 6-month milestone notification was shown to avoid duplicate notifications.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 26 to 27 - Adding All-Time story reminder tracking")
                
                // Add All-Time story reminder tracking column
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN lastAllTimeReminderShown TEXT DEFAULT NULL
                """)
                
                Log.i(TAG, "Migration from version 26 to 27 completed successfully")
            }
        }

        /**
         * Migration from version 27 to 28.
         * 
         * Adds Last.fm import support:
         * 1. lastfm_import_metadata table - Tracks import sessions and progress
         * 2. scrobbles_archive table - Compressed storage for long-tail scrobbles
         * 3. New columns in user_preferences for Last.fm settings
         * 
         * The two-tier architecture (active + archive) allows importing massive
         * Last.fm histories (200K+ scrobbles) without impacting query performance.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 27 to 28 - Adding Last.fm import support")
                
                // =====================================================
                // Step 1: Create lastfm_import_metadata table
                // NOTE: Do NOT use DEFAULT clauses - the entity class doesn't declare
                // @ColumnInfo(defaultValue=...) so Room expects no defaults in schema
                // =====================================================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS lastfm_import_metadata (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        lastfm_username TEXT NOT NULL,
                        import_tier TEXT NOT NULL,
                        active_track_threshold INTEGER NOT NULL,
                        recent_months_included INTEGER NOT NULL,
                        total_scrobbles_found INTEGER NOT NULL,
                        earliest_scrobble INTEGER,
                        latest_scrobble INTEGER,
                        status TEXT NOT NULL,
                        current_page INTEGER NOT NULL,
                        total_pages INTEGER NOT NULL,
                        scrobbles_processed INTEGER NOT NULL,
                        events_imported INTEGER NOT NULL,
                        tracks_created INTEGER NOT NULL,
                        artists_created INTEGER NOT NULL,
                        scrobbles_archived INTEGER NOT NULL,
                        duplicates_skipped INTEGER NOT NULL,
                        last_sync_cursor INTEGER,
                        last_sync_timestamp INTEGER,
                        import_started_at INTEGER NOT NULL,
                        import_completed_at INTEGER,
                        error_message TEXT
                    )
                """)
                
                // NOTE: Do NOT create indices here - the entity class doesn't declare
                // @Index annotations, so Room expects no indices in schema validation
                
                // =====================================================
                // Step 2: Create scrobbles_archive table
                // =====================================================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scrobbles_archive (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        track_hash TEXT NOT NULL,
                        track_title TEXT NOT NULL,
                        artist_name TEXT NOT NULL,
                        artist_name_normalized TEXT NOT NULL,
                        album_name TEXT,
                        musicbrainz_id TEXT,
                        timestamps_blob BLOB NOT NULL,
                        play_count INTEGER NOT NULL,
                        first_scrobble INTEGER NOT NULL,
                        last_scrobble INTEGER NOT NULL,
                        album_art_url TEXT,
                        was_loved INTEGER NOT NULL,
                        import_id INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """)
                
                // Create indices for scrobbles_archive (matching @Index annotations in entity)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_scrobbles_archive_track_hash ON scrobbles_archive(track_hash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scrobbles_archive_artist_name_normalized ON scrobbles_archive(artist_name_normalized)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scrobbles_archive_first_scrobble ON scrobbles_archive(first_scrobble)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scrobbles_archive_last_scrobble ON scrobbles_archive(last_scrobble)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scrobbles_archive_play_count ON scrobbles_archive(play_count)")
                
                // =====================================================
                // Step 3: Add Last.fm columns to user_preferences
                // NOTE: These use DEFAULT because the entity has default values in Kotlin
                // and Room handles this differently for ALTER TABLE vs CREATE TABLE
                // =====================================================
                
                // Last.fm username for syncing
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN lastfmUsername TEXT
                """)
                
                // Whether Last.fm is connected
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN lastfmConnected INTEGER NOT NULL DEFAULT 0
                """)
                
                // Auto-sync frequency: NONE, DAILY, WEEKLY
                db.execSQL("""
                    ALTER TABLE user_preferences 
                    ADD COLUMN lastfmSyncFrequency TEXT NOT NULL DEFAULT 'NONE'
                """)
                
                Log.i(TAG, "Migration from version 27 to 28 completed successfully")
            }
        }
        
        /**
         * Migration from version 28 to 29: Add source index for Last.fm query performance.
         * 
         * The new History screen filters by source to separate:
         * - Recent Activity (source != 'fm.last.import')
         * - Last.fm History (source = 'fm.last.import')
         * 
         * Without an index on source, these queries cause full table scans which
         * severely impacts performance on large listening_events tables.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 28 to 29 - Adding listening_events indices")
                
                // Index for filtering by source (Last.fm vs live tracking)
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_source 
                    ON listening_events(source)
                """)
                
                // Composite index for source + timestamp queries (must be ASC,ASC to match entity)
                // DROP first in case an older version created it with wrong order (ASC,DESC)
                db.execSQL("DROP INDEX IF EXISTS index_listening_events_source_timestamp")
                db.execSQL("""
                    CREATE INDEX index_listening_events_source_timestamp 
                    ON listening_events(source ASC, timestamp ASC)
                """)

                
                // Composite index for timestamp + track_id queries
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_timestamp_track_id 
                    ON listening_events(timestamp ASC, track_id ASC)
                """)
                
                // Composite index for track_id + timestamp queries
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_track_id_timestamp 
                    ON listening_events(track_id ASC, timestamp ASC)
                """)
                
                // Composite index for stats queries
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_stats 
                    ON listening_events(timestamp ASC, track_id ASC, playDuration ASC, completionPercentage ASC)
                """)
                
                Log.i(TAG, "Migration from version 28 to 29 completed successfully")
            }
        }
        
        /**
         * Migration 29 -> 30: Repair indices for existing users.
         * 
         * This migration fixes users who ran the broken 28->29 migration that created
         * the source_timestamp index with wrong order (ASC,DESC instead of ASC,ASC)
         * and was missing several required indices.
         * 
         * This is a repair migration - it drops and recreates all affected indices
         * to ensure schema consistency without data loss.
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 29 to 30 - Repairing listening_events indices")
                
                // Drop potentially broken source_timestamp index and recreate with correct order
                db.execSQL("DROP INDEX IF EXISTS index_listening_events_source_timestamp")
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_source_timestamp 
                    ON listening_events(source ASC, timestamp ASC)
                """)
                
                // Ensure source index exists
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_source 
                    ON listening_events(source)
                """)
                
                // Add any missing composite indices (these were missing in broken 28->29)
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_timestamp_track_id 
                    ON listening_events(timestamp ASC, track_id ASC)
                """)
                
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_track_id_timestamp 
                    ON listening_events(track_id ASC, timestamp ASC)
                """)
                
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_listening_events_stats 
                    ON listening_events(timestamp ASC, track_id ASC, playDuration ASC, completionPercentage ASC)
                """)
                
                Log.i(TAG, "Migration from version 29 to 30 completed successfully - Indices repaired")
            }
        }

        /**
         * Migration from version 30 to 31.
         * 
         * Adds gamification tables:
         * - user_level: Single-row table for XP and level tracking
         * - badges: Achievement badges with progress tracking
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 30 to 31 - Adding gamification")
                
                // Create user_level table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_level (
                        id INTEGER NOT NULL PRIMARY KEY,
                        total_xp INTEGER NOT NULL DEFAULT 0,
                        current_level INTEGER NOT NULL DEFAULT 0,
                        xp_for_current_level INTEGER NOT NULL DEFAULT 0,
                        xp_for_next_level INTEGER NOT NULL DEFAULT 100,
                        last_xp_awarded_at INTEGER NOT NULL DEFAULT 0,
                        current_streak INTEGER NOT NULL DEFAULT 0,
                        longest_streak INTEGER NOT NULL DEFAULT 0,
                        last_streak_date TEXT NOT NULL DEFAULT ''
                    )
                """)
                
                // Create badges table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS badges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        badge_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        icon_name TEXT NOT NULL,
                        category TEXT NOT NULL,
                        earned_at INTEGER NOT NULL DEFAULT 0,
                        progress INTEGER NOT NULL DEFAULT 0,
                        max_progress INTEGER NOT NULL DEFAULT 1,
                        is_earned INTEGER NOT NULL DEFAULT 0
                    )
                """)
                
                // Create indices for badges
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_badges_badge_id ON badges(badge_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_badges_category ON badges(category)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_badges_is_earned ON badges(is_earned)")
                
                Log.i(TAG, "Migration from version 30 to 31 completed successfully")
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
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31
        )
        
    }
}

package me.avinas.tempo.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    /**
     * Migration from version 1 to 2: Enhanced EnrichedMetadata table
     */
    private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create new enriched_metadata table with all new columns
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS enriched_metadata_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    track_id INTEGER NOT NULL,
                    musicbrainz_recording_id TEXT,
                    musicbrainz_artist_id TEXT,
                    musicbrainz_release_id TEXT,
                    musicbrainz_release_group_id TEXT,
                    album_title TEXT,
                    release_date TEXT,
                    release_year INTEGER,
                    release_type TEXT,
                    album_art_url TEXT,
                    album_art_url_small TEXT,
                    album_art_url_large TEXT,
                    artist_name TEXT,
                    artist_country TEXT,
                    artist_type TEXT,
                    track_duration_ms INTEGER,
                    isrc TEXT,
                    tags TEXT,
                    genres TEXT,
                    record_label TEXT,
                    audio_features_json TEXT,
                    spotify_id TEXT,
                    enrichment_status TEXT NOT NULL DEFAULT 'PENDING',
                    enrichment_error TEXT,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    last_enrichment_attempt INTEGER,
                    cache_timestamp INTEGER NOT NULL,
                    cache_version INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
                )
            """)
            
            // Copy data from old table if it exists
            db.execSQL("""
                INSERT INTO enriched_metadata_new (id, track_id, tags, audio_features_json, cache_timestamp)
                SELECT id, track_id, tags, audio_features_json, cache_timestamp 
                FROM enriched_metadata
            """)
            
            // Drop old table
            db.execSQL("DROP TABLE enriched_metadata")
            
            // Rename new table
            db.execSQL("ALTER TABLE enriched_metadata_new RENAME TO enriched_metadata")
            
            // Create indices
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_enriched_metadata_track_id ON enriched_metadata(track_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_enriched_metadata_musicbrainz_recording_id ON enriched_metadata(musicbrainz_recording_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_enriched_metadata_enrichment_status ON enriched_metadata(enrichment_status)")
        }
    }
    
    /**
     * Migration from version 2 to 3: Add Spotify enrichment status columns
     */
    private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add Spotify enrichment status columns
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_enrichment_status TEXT NOT NULL DEFAULT 'NOT_ATTEMPTED'")
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_enrichment_error TEXT")
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_last_attempt INTEGER")
        }
    }
    
    /**
     * Migration from version 3 to 4: Add MusicBrainz ID and release type to albums
     */
    private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add musicbrainz_id column to albums table
            db.execSQL("ALTER TABLE albums ADD COLUMN musicbrainz_id TEXT")
            // Add release_type column to albums table
            db.execSQL("ALTER TABLE albums ADD COLUMN release_type TEXT")
            // Create index for faster lookups by MusicBrainz ID
            db.execSQL("CREATE INDEX IF NOT EXISTS index_albums_musicbrainz_id ON albums(musicbrainz_id)")
        }
    }
    
    /**
     * Migration from version 4 to 5: Add Spotify artist ID and image URL columns
     */
    private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add spotify_artist_id column for storing the primary artist's Spotify ID
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_artist_id TEXT")
            // Add spotify_artist_image_url column for caching artist profile images
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_artist_image_url TEXT")
        }
    }
    
    /**
     * Migration from version 5 to 6: Add Spotify artist IDs and verified artist name
     */
    private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add spotify_artist_ids for all collab artists (comma-separated)
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_artist_ids TEXT")
            // Add spotify_verified_artist for the correct artist name from Spotify
            db.execSQL("ALTER TABLE enriched_metadata ADD COLUMN spotify_verified_artist TEXT")
        }
    }
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "tempo.db")
            .addMigrations(
                MIGRATION_1_2, 
                MIGRATION_2_3, 
                MIGRATION_3_4, 
                MIGRATION_4_5, 
                MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,  // Enhanced ListeningEvent tracking
                AppDatabase.MIGRATION_7_8,  // Proper relational schema with TrackArtist
                AppDatabase.MIGRATION_8_9,   // Performance optimization indexes
                AppDatabase.MIGRATION_9_10,  // ReccoBeats support
                AppDatabase.MIGRATION_10_11, // Spotify preview URL
                AppDatabase.MIGRATION_11_12, // Enhanced robustness tracking
                AppDatabase.MIGRATION_12_13, // iTunes and music links
                AppDatabase.MIGRATION_13_14,  // Preview URL
                AppDatabase.MIGRATION_14_15,  // Deezer and Last.fm artist images
                AppDatabase.MIGRATION_15_16,  // Album art source tracking for robust local fallback
                AppDatabase.MIGRATION_16_17,  // Fix HTTP URLs to HTTPS for Cover Art Archive
                AppDatabase.MIGRATION_17_18   // Track aliases for manual merge tracking
            )
            // Enable Write-Ahead Logging for better concurrent read/write performance
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // Enable multi-instance invalidation for better reactivity
            .enableMultiInstanceInvalidation()
            // Add callback for database optimization on open
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // Optimize SQLite settings for better performance
                    // Use query() for PRAGMA statements as execSQL may fail on some Android versions
                    try {
                        db.query("PRAGMA synchronous = NORMAL").close()  // Balance safety vs speed
                        db.query("PRAGMA cache_size = -8000").close()    // 8MB cache
                        db.query("PRAGMA temp_store = MEMORY").close()   // Keep temp tables in memory
                        db.query("PRAGMA mmap_size = 268435456").close() // 256MB memory-mapped I/O
                    } catch (e: Exception) {
                        // Log but don't crash - these are optimizations, not requirements
                        android.util.Log.w("DatabaseModule", "Failed to apply PRAGMA optimizations", e)
                    }
                }
            })
            .build()
    }

    @Provides
    fun provideTrackDao(db: AppDatabase) = db.trackDao()

    @Provides
    fun provideListeningEventDao(db: AppDatabase) = db.listeningEventDao()

    @Provides
    fun provideArtistDao(db: AppDatabase) = db.artistDao()

    @Provides
    fun provideAlbumDao(db: AppDatabase) = db.albumDao()

    @Provides
    fun provideEnrichedMetadataDao(db: AppDatabase) = db.enrichedMetadataDao()

    @Provides
    fun provideUserPreferencesDao(db: AppDatabase) = db.userPreferencesDao()

    @Provides
    fun provideStatsDao(db: AppDatabase) = db.statsDao()
    
    @Provides
    fun provideTrackArtistDao(db: AppDatabase) = db.trackArtistDao()
    
    @Provides
    fun provideTrackAliasDao(db: AppDatabase) = db.trackAliasDao()
}

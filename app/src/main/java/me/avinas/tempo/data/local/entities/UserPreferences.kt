package me.avinas.tempo.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_preferences")
data class UserPreferences(
    @PrimaryKey val id: Int = 0,
    val theme: String = "system",
    val notifications: Boolean = true,
    val spotifyLinked: Boolean = false,
    // Extended audio analysis downloads 30s preview audio for mood/energy analysis
    // Default false to save data usage - only ReccoBeats DB lookup is used by default
    val extendedAudioAnalysis: Boolean = false,
    
    // Smart Metadata: Merge "Live", "Remix", etc. with studio versions by default
    // True = Cleaner library (User request), False = Precise separation
    val mergeAlternateVersions: Boolean = true,
    
    // Content Filtering: Filter podcasts from tracking by default
    // True = Only track music, False = Track all audio content
    val filterPodcasts: Boolean = true,
    
    // Content Filtering: Filter audiobooks from tracking by default
    // True = Only track music, False = Track all audio content
    val filterAudiobooks: Boolean = true,
    
    // Onboarding: Track if user has seen the "long press" coach mark in History
    val hasSeenHistoryCoachMark: Boolean = false,
    
    // Walkthrough Flags (Passive Game)
    val hasSeenSpotlightTutorial: Boolean = false,
    val hasSeenStatsSortTutorial: Boolean = false,
    val hasSeenStatsItemClickTutorial: Boolean = false,
    
    // Spotlight Story Reminders: Track when reminders were last shown (YYYY-MM-DD format)
    val lastMonthlyReminderShown: String? = null, // Last date monthly reminder was shown
    val lastYearlyReminderShown: String? = null,  // Last date yearly reminder was shown
    val lastAllTimeReminderShown: String? = null,  // Last date all-time reminder was shown (6-month milestone)
    
    // =====================================================
    // Spotify Import Feature
    // =====================================================
    
    /**
     * Spotify-API-Only Mode:
     * When TRUE, Spotify listening data comes from API polling instead of notification tracking.
     * - Spotify notifications are ignored (MusicTrackingService skips com.spotify.music)
     * - SpotifyPollingWorker periodically fetches recently played via API
     * - Other music apps still use notification tracking as normal
     * 
     * When FALSE (default), all apps including Spotify use notification tracking.
     * This is the more accurate mode but relies on notification listener.
     */
    val spotifyApiOnlyMode: Boolean = false,
    
    /**
     * Cursor for incremental Spotify import polling.
     * Stores the 'after' cursor from the last successful poll.
     * Used to fetch only new plays since last poll, avoiding duplicates.
     */
    val spotifyImportCursor: String? = null,
    
    /**
     * Timestamp of the last successful Spotify import/poll.
     * Used for:
     * - Knowing when last sync occurred (display to user)
     * - Fallback for polling if cursor is lost
     */
    val lastSpotifyImportTimestamp: Long? = null,
    
    // =====================================================
    // Last.fm Import Feature
    // =====================================================
    
    /**
     * Last.fm username for import/sync.
     * Stored after successful import for incremental syncs.
     */
    val lastfmUsername: String? = null,
    
    /**
     * Whether Last.fm is connected (has completed at least one import).
     */
    val lastfmConnected: Boolean = false,
    
    /**
     * Auto-sync frequency for Last.fm scrobbles.
     * Values: NONE, DAILY, WEEKLY
     * Default: NONE (manual sync only)
     */
    val lastfmSyncFrequency: String = "NONE"
)


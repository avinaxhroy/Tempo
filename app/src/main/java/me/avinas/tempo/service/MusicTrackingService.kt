package me.avinas.tempo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.RoomStatsRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.repository.TrackAliasRepository
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.local.dao.ManualContentMarkDao
import me.avinas.tempo.utils.TrackMatcher
import me.avinas.tempo.utils.TrackCandidate
import me.avinas.tempo.worker.EnrichmentWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * MusicTrackingService captures listening events from music apps via:
 * 1. NotificationListenerService - detects music notifications (primary)
 * 2. MediaSessionManager - tracks playback state changes (fallback/supplement)
 *
 * Handles play, pause, skip, resume to calculate accurate listening duration.
 * 
 * Enhanced features (v2):
 * - Smart track matching with fuzzy deduplication
 * - Session persistence and recovery
 * - Event batching for efficient database writes
 * - Intelligent duration estimation
 * - Comprehensive error handling and retry logic
 * 
 * Note: Uses manual Hilt injection via EntryPoint because NotificationListenerService
 * is managed by the system and @AndroidEntryPoint doesn't work properly for it.
 */
class MusicTrackingService : NotificationListenerService() {

    /**
     * Hilt EntryPoint for manual dependency injection in NotificationListenerService.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MusicTrackingServiceEntryPoint {
        fun trackRepository(): TrackRepository
        fun listeningRepository(): ListeningRepository
        fun enrichedMetadataRepository(): EnrichedMetadataRepository
        fun statsRepository(): StatsRepository
        fun artistLinkingService(): ArtistLinkingService
        fun trackAliasRepository(): TrackAliasRepository
        fun userPreferencesDao(): UserPreferencesDao
        fun manualContentMarkDao(): ManualContentMarkDao
    }

    companion object {
        private const val TAG = "MusicTrackingService"
        private const val CHANNEL_ID = "tempo_tracking_channel"
        private const val NOTIFICATION_ID = 1001

        // Music app package names to monitor (ONLY music apps, no video)
        private val MUSIC_APPS = setOf(
            "com.google.android.apps.youtube.music",  // YouTube Music
            "com.spotify.music",                       // Spotify
            "com.apple.android.music",                 // Apple Music
            "com.amazon.mp3",                          // Amazon Music
            "com.soundcloud.android",                  // SoundCloud
            "deezer.android.app",                      // Deezer
            "com.pandora.android",                     // Pandora
            "com.jio.media.jiobeats",                  // JioSaavn
            "com.gaana",                               // Gaana
            "com.bsbportal.music",                     // Wynk
            "com.hungama.myplay.activity",             // Hungama
            "com.samsung.android.app.music",           // Samsung Music
            "com.miui.player",                         // Mi Music
            "com.sec.android.app.music",               // Samsung Music (old)
            "in.startv.hotstar.music",                 // Hotstar Music
            "com.tidal.android",                       // Tidal (Old/Alt)
            "com.aspiro.tidal",                        // Tidal (Official)
            "com.qobuz.music",                         // Qobuz
            "app.revanced.android.youtube.music",      // YouTube Music ReVanced
            "com.vanced.android.youtube.music",        // YouTube Music Vanced
            "com.moonvideo.android.resso",             // Resso
            "com.audiomack",                           // Audiomack
            "com.mmm.trebelmusic",                     // Trebel
            "com.maxmpz.audioplayer",                  // Poweramp
            "in.krosbits.musicolet",                   // Musicolet
            "com.kodarkooperativet.blackplayerfree",   // BlackPlayer Free
            "com.kodarkooperativet.blackplayerex",     // BlackPlayer EX
            "nugs.net",                                // Nugs.net
            "net.nugs.multiband"                       // Nugs.net (Multiband)
        )
        
        // Podcast apps - filtered by content filtering settings
        // Note: Spotify is NOT in this list because it has music, podcasts, AND audiobooks
        // We rely on metadata detection for Spotify
        private val PODCAST_APPS = setOf(
            "com.google.android.apps.podcasts",     // Google Podcasts
            "fm.player",                             // Player FM
            "au.com.shiftyjelly.pocketcasts",       // Pocket Casts
            "com.bambuna.podcastaddict",            // Podcast Addict
            "com.clearchannel.iheartradio.controller", // iHeartRadio
            "app.tunein.player",                    // TuneIn Radio
            "com.stitcher.app",                     // Stitcher
            "com.castbox.player",                   // Castbox
            "com.overcast.app",                     // Overcast
            "com.apple.android.podcasts",           // Apple Podcasts
            "com.podcastone.mobile",                // PodcastOne
            "com.wondery.wondery",                  // Wondery
            "com.podcasts.android",                 // Podcasts (generic)
            "fm.castbox.audiobook.radio.podcast"    // Castbox variant
        )
        
        // Audiobook apps - filtered by content filtering settings  
        private val AUDIOBOOK_APPS = setOf(
            "com.audible.application",              // Audible
            "com.google.android.apps.books",        // Google Play Books
            "com.audiobooks.android.audiobooks",    // Audiobooks.com
            "com.scribd.app.reader0",               // Scribd
            "com.storytel",                         // Storytel
            "fm.libro",                             // Libro.fm
            "com.libro.app",                        // Libro.fm (alt)
            "com.kobo.books.ereader",               // Kobo Books
            "com.nook.app",                         // Nook Audiobooks
            "com.audiobooks.androidapp"             // Audiobooks (generic)
        )
        
        // Video/non-music apps to explicitly BLOCK from tracking
        // These apps have MediaSessions but we don't want to track their content
        private val BLOCKED_APPS = setOf(
            // Video streaming
            "com.google.android.youtube",             // YouTube (video)
            "com.google.android.apps.youtube",        // YouTube (alternative)
            "com.netflix.mediaclient",                // Netflix
            "com.amazon.avod.thirdpartyclient",       // Prime Video
            "com.disney.disneyplus",                  // Disney+
            "in.startv.hotstar",                      // Hotstar (video)
            "com.hotstar.android",                    // Hotstar (alternative)
            "tv.twitch.android.app",                  // Twitch
            
            // Social media (reels, shorts, videos)
            "com.zhiliaoapp.musically",               // TikTok
            "com.ss.android.ugc.trill",               // TikTok (alternative)
            "com.instagram.android",                  // Instagram (reels)
            "com.facebook.katana",                    // Facebook (videos)
            "com.snapchat.android",                   // Snapchat
            
            // Video players & media
            "com.vimeo.android.videoapp",             // Vimeo
            "com.mxtech.videoplayer.ad",              // MX Player
            "com.mxtech.videoplayer.pro",             // MX Player Pro
            "org.videolan.vlc",                       // VLC
            "com.google.android.apps.photos",         // Google Photos (videos)
            "com.whatsapp",                           // WhatsApp (status videos)
            "org.telegram.messenger",                 // Telegram (videos)
            "com.google.android.gm",                  // Gmail (video attachments)
            
            // Expanded Video Player Blocklist (User Request)
            "com.playit.videoplayer",
            "video.player.videoplayer",
            "com.inshot.videoplayer",
            "hd.videoplayer.allformat",
            "video.player.allformat.hd.player",
            "videoplayer.video.player.hd",
            "video.player.hd.videoplayer",
            "uplayer.video.player",
            "com.kmplayer",
            "com.bsplayer.bspandroid.free",
            "com.bsplayer.bspandroid",
            "com.archos.mediacenter.video",
            "com.archos.arcmedia",
            "com.samsung.android.video",
            "com.sec.android.gallery3d",
            "com.samsung.android.service.peoplestripe",
            "com.miui.videoplayer",
            "com.miui.gallery",
            "com.coloros.video",
            "com.oppo.video",
            "com.realme.video",
            "com.android.VideoPlayer",
            "com.videoplayer.vivoplayer",
            "com.oneplus.gallery",
            "com.motorola.MotGallery2",
            "com.motorola.cn.gallery",
            "com.huawei.himovie",
            "com.huawei.himovie.overseas",
            "com.sonyericsson.album",
            "com.lge.video",
            "com.lge.gallery",
            "com.htc.album",
            "com.asus.gallery",
            "com.lenovo.videoplayer",
            "com.google.android.videos",
            "com.google.android.apps.youtube.media",
            "com.google.android.apps.youtube.kids",
            "com.tcl.video",
            "com.tcl.gallery",
            "com.zte.video",
            "com.tecno.video",
            "com.infinix.video",
            "com.transsion.video",
            "com.nokia.gallery",
            "com.hmdglobal.gallery",
            "com.alcatel.video",
            "com.panasonic.video",
            "com.sharp.video",
            "com.hisense.video",
            "com.vlc.remote",
            "com.vidma.videoplayer",
            "com.litterpeng.videoplayer",
            "com.rhmsoft.playerpro",
            "com.videoplayer.hdplayer2020",
            "videoplayer.videoplayer",
            "com.amplayer.video",
            "com.kmvideoplayer.hd",
            "allformat.player.videoplayer",
            "com.xplayer.hd",
            "videoplayer.musicplayer.mp4",
            "com.ufyl.videoplayer",
            "com.lemon.videoplayer",
            "com.hd.videoplayer.master",
            "com.mytech.video.player",
            "com.mp4.hd.videoplayer",
            "com.hdvideoplayer.allformat",
            "com.easytech.videoplayer",
            "com.powerful.videoplayer",
            "com.mediaplayer.fullhd",
            "com.fullhd.video.player",
            "com.max.video.hd",
            "com.super.video.hd",
            "com.player.mediaplayer.hd",
            "com.media.masterplayer",
            "com.playtube.videoplayer",
            "com.flashplayer.videoplayer",
            "com.svplayer.hd",
            "com.evoplayer.hd",
            "com.nova.videoplayer",
            "com.jplayer.hd",
            "com.videoplayer.masterpro",
            "com.vidx.player",
            "com.stream.videoplayer",
            "com.prime.hdplayer",
            "com.alpha.videoplayer",
            "com.boom.hdplayer",
            "com.edge.videoplayer",
            "com.spark.videoplayer",
            "com.ultra.hdplayer",
            "com.pixel.videoplayer",

            // Browsers (can have video/audio that shouldn't be tracked)
            "com.android.chrome",                     // Chrome
            "com.chrome.beta",                        // Chrome Beta
            "com.chrome.dev",                         // Chrome Dev
            "com.chrome.canary",                      // Chrome Canary
            "org.mozilla.firefox",                    // Firefox
            "org.mozilla.firefox_beta",               // Firefox Beta
            "com.opera.browser",                      // Opera
            "com.opera.browser.beta",                 // Opera Beta
            "com.UCMobile.intl",                      // UC Browser
            "com.brave.browser",                      // Brave
            "com.microsoft.edge",                     // Microsoft Edge
            "org.torproject.torbrowser",              // Tor Browser
            "org.dolphin.browser",                    // Dolphin
            "com.sec.android.app.sbrowser",           // Samsung Browser
            "com.ksmobile.cb",                        // CM Browser
            "com.qwant.browser",                      // Qwant
            "org.chromium.webview_shell"              // Chrome WebView shell
        )
        
        // Typical music duration range (used for music detection)
        private const val MIN_MUSIC_DURATION_MS = 30_000L      // 30 seconds
        private const val MAX_MUSIC_DURATION_MS = 20 * 60 * 1000L  // 20 minutes (for long tracks/mixes)
        private const val TYPICAL_SONG_MIN_MS = 60_000L        // 1 minute
        private const val TYPICAL_SONG_MAX_MS = 10 * 60 * 1000L   // 10 minutes

        // Notification extras keys for music metadata
        private const val EXTRA_TITLE = Notification.EXTRA_TITLE
        private const val EXTRA_TEXT = Notification.EXTRA_TEXT
        private const val EXTRA_SUB_TEXT = Notification.EXTRA_SUB_TEXT
        private const val EXTRA_INFO_TEXT = Notification.EXTRA_INFO_TEXT
        private const val EXTRA_BIG_TEXT = Notification.EXTRA_BIG_TEXT
        
        // Album art directory name
        private const val ALBUM_ART_DIR = "album_art"
        
        // Replay detection: maximum time between plays to consider it a replay (5 minutes)
        private const val REPLAY_THRESHOLD_MS = 5 * 60 * 1000L
        
        // Smart adaptive polling intervals for accurate duration tracking
        // These are dynamically adjusted based on song phase and duration
        private const val POLL_INTERVAL_SONG_START_MS = 3_000L     // First 30s: poll every 3s (skip detection)
        private const val POLL_INTERVAL_SONG_MIDDLE_MS = 8_000L    // Middle: poll every 8s (battery efficient)
        private const val POLL_INTERVAL_SONG_END_MS = 4_000L       // Last 30s: poll every 4s (completion detection)
        private const val POLL_INTERVAL_SHORT_TRACK_MS = 5_000L    // Tracks <90s: always poll every 5s
        private const val POLL_INTERVAL_UNKNOWN_DURATION_MS = 6_000L // Unknown duration: balanced polling
        
        // Song phase thresholds
        private const val SONG_START_PHASE_MS = 30_000L    // First 30 seconds
        private const val SONG_END_PHASE_MS = 30_000L      // Last 30 seconds
        private const val SHORT_TRACK_THRESHOLD_MS = 90_000L // Tracks under 90 seconds
        
        // Minimum play duration to record (5 seconds)
        private const val MIN_PLAY_DURATION_MS = 5_000L
        
        // Skip threshold: plays under 30% completion are considered skips
        private const val SKIP_COMPLETION_THRESHOLD = 30
        
        // Full play threshold: plays over 80% completion are considered full plays
        private const val FULL_PLAY_COMPLETION_THRESHOLD = 80
        
        // Session auto-save interval
        private const val SESSION_AUTOSAVE_INTERVAL_MS = 30_000L
        
        // Track matching threshold for deduplication
        private const val TRACK_MATCH_THRESHOLD = 0.85
        
        // Position tracking constants
        private const val MAX_POSITION_JUMP_MS = 30_000L // 30 seconds - max seek to still count
        private const val MAX_PLAY_DURATION_MS = 3_600_000L // 1 hour - max duration per session
        
        // Maximum reasonable position delta in one update (5 minutes worth of playback)
        // This catches cases where polling was delayed for a long time (device sleep, service suspension)
        private const val MAX_REASONABLE_DELTA_MS = 5 * 60 * 1000L // 5 minutes
        
        // Patterns for extracting artist from title (e.g., "Song - Artist", "Song (by Artist)")
        private val TITLE_ARTIST_PATTERNS = listOf(
            Regex("""^(.+?)\s*[-–—]\s*(.+)$"""),           // "Title - Artist" or "Title – Artist"
            Regex("""^(.+?)\s*\|\s*(.+)$"""),              // "Title | Artist"
            Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE),  // "Title by Artist"
        )
    }
    
    // =====================
    // Robust Metadata Extraction Helpers
    // =====================
    
    /**
     * Extract artist from MediaMetadata using multiple fallback sources.
     * Tries: ARTIST -> ALBUM_ARTIST -> AUTHOR -> DISPLAY_SUBTITLE -> WRITER -> COMPOSER
     */
    private fun extractArtistFromMetadata(metadata: MediaMetadata): String {
        // Primary: METADATA_KEY_ARTIST
        metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        
        // Fallback 1: ALBUM_ARTIST
        metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        
        // Fallback 2: AUTHOR (some apps use this)
        metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        
        // Fallback 3: DISPLAY_SUBTITLE (often contains artist)
        metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        
        // Fallback 4: WRITER
        metadata.getString(MediaMetadata.METADATA_KEY_WRITER)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        
        // Fallback 5: COMPOSER (rare but some classical music apps)
        metadata.getString(MediaMetadata.METADATA_KEY_COMPOSER)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        
        // Fallback 6: Try to extract from title if it contains separator
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title != null) {
            extractArtistFromTitle(title)?.let { return it }
        }
        
        // Debug log when falling back to Unknown Artist (debounced - only once per track)
        // Skip logging when title is empty - metadata hasn't fully arrived yet
        val logKey = title?.takeIf { it.isNotBlank() }
        if (logKey != null && !loggedArtistExtractionFailures.containsKey(logKey)) {
            loggedArtistExtractionFailures[logKey] = true
            Log.d(TAG, "Artist extraction failed for MediaMetadata. Available keys: " +
                "ARTIST='${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}', " +
                "ALBUM_ARTIST='${metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)}', " +
                "DISPLAY_SUBTITLE='${metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)}', " +
                "TITLE='$title'")
        }
        
        return "Unknown Artist"
    }
    
    /**
     * Extract artist from notification extras using multiple fallback sources.
     */
    private fun extractArtistFromNotification(extras: android.os.Bundle, title: String): String {
        // Primary: EXTRA_TEXT (most common for artist in media notifications)
        extras.getCharSequence(EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        
        // Fallback 1: EXTRA_SUB_TEXT (sometimes contains artist)
        extras.getCharSequence(EXTRA_SUB_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { 
                // SUB_TEXT might be album, check if it looks like an artist name
                // (doesn't contain common album indicators)
                if (!looksLikeAlbum(it)) return it.trim()
            }
        
        // Fallback 2: EXTRA_INFO_TEXT
        extras.getCharSequence(EXTRA_INFO_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        
        // Fallback 3: BIG_TEXT might contain artist info
        extras.getCharSequence(EXTRA_BIG_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { bigText ->
                // Try to parse "Title - Artist" or "Title\nArtist" format
                val lines = bigText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                if (lines.size >= 2 && lines[0] == title) {
                    return lines[1].takeIf { !isPlaceholderArtist(it) } ?: "Unknown Artist"
                }
            }
        
        // Fallback 4: Try to extract from title
        extractArtistFromTitle(title)?.let { return it }
        
        // Debug log when falling back to Unknown Artist (debounced - only once per track)
        if (!loggedArtistExtractionFailures.containsKey(title)) {
            loggedArtistExtractionFailures[title] = true
            Log.d(TAG, "Artist extraction failed for notification. Available: " +
                "TEXT='${extras.getCharSequence(EXTRA_TEXT)}', " +
                "SUB_TEXT='${extras.getCharSequence(EXTRA_SUB_TEXT)}', " +
                "INFO_TEXT='${extras.getCharSequence(EXTRA_INFO_TEXT)}', " +
                "TITLE='$title'")
        }
        
        return "Unknown Artist"
    }
    
    /**
     * Try to extract artist from title string (e.g., "Song - Artist" format).
     */
    private fun extractArtistFromTitle(title: String): String? {
        for (pattern in TITLE_ARTIST_PATTERNS) {
            val match = pattern.find(title)
            if (match != null) {
                val potentialArtist = match.groupValues[2].trim()
                // Validate it looks like an artist name (not too long, not just numbers)
                if (potentialArtist.length in 1..50 && 
                    !potentialArtist.all { it.isDigit() } &&
                    !isPlaceholderArtist(potentialArtist)) {
                    return potentialArtist
                }
            }
        }
        return null
    }
    
    /**
     * Clean title by removing embedded artist if detected.
     */
    private fun cleanTitleIfNeeded(title: String): String {
        for (pattern in TITLE_ARTIST_PATTERNS) {
            val match = pattern.find(title)
            if (match != null) {
                val cleanTitle = match.groupValues[1].trim()
                if (cleanTitle.isNotBlank()) {
                    return cleanTitle
                }
            }
        }
        return title
    }
    
    /**
     * Check if artist string is a placeholder/unknown value.
     */
    private fun isPlaceholderArtist(artist: String): Boolean {
        val lower = artist.lowercase()
        return lower in listOf(
            "unknown", "unknown artist", "<unknown>", "various artists",
            "various", "n/a", "na", "none", "null", "", " ",
            "artist", "track", "music", "audio", "media"
        ) || lower.startsWith("track ") || lower.matches(Regex("^\\d+$"))
    }
    
    /**
     * Check if string looks like an album name rather than artist.
     */
    private fun looksLikeAlbum(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("album") ||
               lower.contains("soundtrack") ||
               lower.contains("ost") ||
               lower.contains("compilation") ||
               lower.contains("collection") ||
               lower.contains("vol.") ||
               lower.contains("volume") ||
               lower.matches(Regex(".*\\(\\d{4}\\).*")) || // Contains year like "(2023)"
               lower.matches(Regex(".*\\d{4}.*remaster.*")) // Remaster with year
    }
    
    /**
     * Check if notification content is likely an advertisement.
     * Uses heuristics similar to LocalMediaMetadata.isLikelyAdvertisement().
     */
    private fun isLikelyAdvertisementFromNotification(
        title: String, 
        artist: String, 
        album: String?,
        packageName: String
    ): Boolean {
        val lowerTitle = title.lowercase()
        val lowerArtist = artist.lowercase()
        
        // Common ad title patterns
        val adTitlePatterns = listOf(
            "advertisement", "sponsored", "ad break",
            "premium", "upgrade", "subscribe",
            "commercial", "promo", "promotion"
        )
        
        // Check title for ad keywords
        if (adTitlePatterns.any { lowerTitle.contains(it) }) {
            return true
        }
        
        // Spotify-specific ad detection
        if (packageName == "com.spotify.music") {
            if (lowerArtist == "spotify" || lowerArtist.contains("spotify")) {
                return true
            }
            if (album?.lowercase()?.contains("spotify") == true) {
                return true
            }
        }
        
        // YouTube Music ad detection - YTM ads often have generic titles
        if (packageName == "com.google.android.apps.youtube.music") {
            if (lowerArtist == "youtube" || lowerArtist.contains("youtube music")) {
                return true
            }
        }
        
        // Artist name matches common ad sources
        val adArtists = listOf(
            "advertisement", "ad", "spotify", "youtube", 
            "google", "amazon", "apple", "commercial"
        )
        if (adArtists.any { lowerArtist == it || lowerArtist.startsWith("$it ") }) {
            return true
        }
        
        return false
    }

    // Dependencies - initialized in onCreate via Hilt EntryPoint
    private lateinit var trackRepository: TrackRepository
    private lateinit var listeningRepository: ListeningRepository
    private lateinit var enrichedMetadataRepository: EnrichedMetadataRepository
    private lateinit var statsRepository: StatsRepository
    private lateinit var artistLinkingService: ArtistLinkingService
    private lateinit var userPreferencesDao: UserPreferencesDao
    private lateinit var trackAliasRepository: TrackAliasRepository
    private lateinit var manualContentMarkDao: ManualContentMarkDao
    
    /**
     * Check if content should be filtered based on user preferences and detection.
     * Three-layer approach:
     * 1. App-level: Block dedicated podcast/audiobook apps (NOT Spotify)
     * 2. Metadata-based: Detect content type from metadata (critical for Spotify)
     * 3. Manual marks: User-defined patterns
     * 
     * @param packageName The source app package
     * @param metadata Optional full metadata (may be null if MediaSession didn't provide enough)
     * @param title Track title (required for manual marks check even without full metadata)
     * @param artist Track artist (required for manual marks check even without full metadata)
     */
    private suspend fun shouldFilterContent(
        packageName: String,
        metadata: LocalMediaMetadata?,
        title: String,
        artist: String
    ): Boolean {
        // Get user preferences - use defaults if no row exists yet
        // CRITICAL: Don't return false if prefs is null - use default filtering values!
        val prefs = userPreferencesDao.getSync()
        val filterPodcasts = prefs?.filterPodcasts ?: true  // Default: filter podcasts
        val filterAudiobooks = prefs?.filterAudiobooks ?: true  // Default: filter audiobooks
        
        // Layer 1: App-level filtering (ONLY for dedicated apps, NOT Spotify!)
        // Spotify has music + podcasts + audiobooks, so we rely on metadata detection
        if (filterPodcasts && packageName in PODCAST_APPS) {
            Log.d(TAG, "Filtering podcast app: $packageName")
            return true
        }
        if (filterAudiobooks && packageName in AUDIOBOOK_APPS) {
            Log.d(TAG, "Filtering audiobook app: $packageName")
            return true
        }
        
        // Layer 2: Metadata-based detection (works for all apps including Spotify)
        if (metadata != null) {
            if (filterPodcasts && metadata.isPodcast()) {
                Log.d(TAG, "Filtering podcast content: ${metadata.title} by ${metadata.artist}")
                return true
            }
            if (filterAudiobooks && metadata.isAudiobook()) {
                Log.d(TAG, "Filtering audiobook content: ${metadata.title} by ${metadata.artist}")
                return true
            }
        }
        
        // Layer 3: Manual marks (user-defined patterns)
        // Uses title/artist parameters directly - works even without full metadata
        val manualMark = manualContentMarkDao.findMatchingMark(title, artist)
        if (manualMark != null) {
            // IMPORTANT: Only filter if the user has filtering enabled for this content type
            val shouldFilter = when (manualMark.contentType) {
                "PODCAST" -> filterPodcasts
                "AUDIOBOOK" -> filterAudiobooks
                else -> false
            }
            if (shouldFilter) {
                Log.d(TAG, "Filtering manually marked content: $title by $artist (type: ${manualMark.contentType}, pattern: ${manualMark.patternType})")
                return true
            } else {
                Log.d(TAG, "Manual mark found for '$title' but filtering disabled for ${manualMark.contentType}")
            }
        }
        
        return false
    }
    
    // Enhanced tracking components
    private lateinit var trackingManager: MusicTrackingManager
    private lateinit var sessionPersistence: SessionPersistence
    private lateinit var durationEstimator: SmartDurationEstimator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // MediaSession management
    private var mediaSessionManager: MediaSessionManager? = null
    // Map of package name to MediaController - synchronized for thread safety
    private val activeControllers = java.util.Collections.synchronizedMap(mutableMapOf<String, MediaController>())
    // We no longer use a single shared callback
    // private val sessionCallback = MediaSessionCallback()

    // Track current playback state per app - ConcurrentHashMap for thread-safe operations
    private val playbackStates = java.util.concurrent.ConcurrentHashMap<String, PlaybackSession>()
    
    // Lock for complex session operations that span multiple map operations
    // Use this for operations that need to read, decide, and write atomically
    private val sessionOperationLock = Any()
    
    // Track listener connection state to prevent duplicate processing
    @Volatile
    private var isListenerConnected = false
    private val connectionLock = Any()
    
    // Session auto-save job
    private var autoSaveJob: Job? = null
    
    // Service uptime tracking
    private val serviceStartTime = AtomicLong(0)
    
    // Recent plays cache for replay detection (trackId -> lastPlayTimestamp)
    private val recentPlaysCache = mutableMapOf<Long, Long>()
    
    // Estimated durations cache (title+artist hash -> duration) for when MediaSession doesn't provide duration
    private val durationEstimateCache = mutableMapOf<String, Long>()
    
    // Smart Metadata: Cached user preference for matching strictness
    // This avoids database call on every track match (preferences rarely change)
    @Volatile
    private var cachedMergeAlternateVersions: Boolean = true // Default value
    private var lastPreferencesFetch: Long = 0
    private val PREFERENCES_CACHE_TTL_MS = 60_000L // Refresh every 60 seconds

    
    // Notification debouncing - prevents excessive notification updates
    private var lastNotificationUpdate = 0L
    private var lastNotificationContent = ""
    
    // Pending metadata debounce - waits briefly for complete metadata before inserting tracks
    // Key: packageName, Value: pending job that will insert the track
    private val pendingTrackInserts = mutableMapOf<String, Job>()
    
    // How long to wait for complete metadata (artist) before inserting track
    private val METADATA_DEBOUNCE_MS = 500L
    
    // Cache for local MediaSession metadata by trackId
    // Used as ultimate fallback for genre when external sources fail
    // Music players often send metadata in stages, so we keep updating this
    private val localMetadataCache = mutableMapOf<Long, LocalMediaMetadata>()
    
    // =====================
    // Log Spam Prevention
    // =====================
    // Track what we've already logged to avoid spamming the same messages
    
    // LRU-style set of track titles for which we've already logged artist extraction failures
    // Uses LinkedHashMap with access order to limit memory (max 100 entries)
    private val loggedArtistExtractionFailures = object : LinkedHashMap<String, Boolean>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 100
    }
    
    // LRU-style set of titles for which we've logged "Skipping likely advertisement"
    // Uses LinkedHashMap with access order to limit memory (max 50 entries)
    private val loggedAdvertisementSkips = object : LinkedHashMap<String, Boolean>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 50
    }
    
    // Track restart log debouncing - only log once per track per 5 seconds
    private var lastTrackRestartLog: Pair<String, Long>? = null
    private val TRACK_RESTART_LOG_DEBOUNCE_MS = 5_000L
    
    // Session change log debouncing - avoid spam when session count unchanged
    private var lastSessionChangeLog: Pair<Int, Long>? = null
    private val SESSION_CHANGE_LOG_DEBOUNCE_MS = 2_000L

    /**
     * Represents an active playback session with POSITION-BASED tracking.
     * 
     * KEY CHANGE: We now track actual playback position from MediaSession
     * instead of wall-clock time. This fixes the issue where:
     * - Play at 9:00 AM, pause after 1 minute
     * - Resume at 10:00 AM
     * - Old logic: would calculate 61 minutes played (wrong!)
     * - New logic: tracks actual position progress = 1 minute + continued progress (correct!)
     */
    data class PlaybackSession(
        val packageName: String,
        var title: String,
        var artist: String,
        var album: String?,
        var trackId: Long? = null,
        var startTimestamp: Long = System.currentTimeMillis(),
        // POSITION-BASED TRACKING: accumulated actual playback time from MediaSession positions
        var accumulatedPositionMs: Long = 0,
        // Last position we recorded (to calculate delta on next update)
        var lastRecordedPosition: Long = 0,
        // Timestamp when we last recorded position (to detect stale data)
        var lastPositionUpdateTime: Long = System.currentTimeMillis(),
        var isPlaying: Boolean = false,
        var estimatedDurationMs: Long? = null,
        // Enhanced tracking fields
        var pauseCount: Int = 0,
        var lastPauseTimestamp: Long? = null,
        // NEW: Track total pause duration for analytics
        var totalPauseDurationMs: Long = 0,
        // NEW: Track seek operations
        var seekCount: Int = 0,
        var lastSeekTimestamp: Long? = null,
        // NEW: Track position update count for validation
        var positionUpdatesCount: Int = 0,
        // Session tracking
        val sessionId: String = java.util.UUID.randomUUID().toString().take(8),
        var playbackStartPosition: Long = 0, // Position when this session started
        var lastKnownPosition: Long = 0, // Last known playback position from MediaSession
        // Delayed metadata retry tracking
        var delayedMetadataRetryScheduled: Boolean = false,
        // Track if this session looks like music (for filtering)
        var isLikelyMusic: Boolean = true,
        // NEW: Flag for interrupted sessions (app kill, crash, etc.)
        var wasInterrupted: Boolean = false,
        // FIX: Flag to track if we've received the first valid position update
        // This prevents the initial position from being counted as played time
        var hasReceivedInitialPosition: Boolean = false
    ) {
        /**
         * Calculate total play duration using ACTUAL POSITION DATA.
         * This is the key fix - we use position progress, not wall-clock time.
         */
        fun calculateCurrentPlayDuration(): Long {
            // Use accumulated position-based duration
            // This represents actual audio playback time, not wall-clock time
            // Cap at MAX_PLAY_DURATION_MS to prevent runaway values from bugs
            return accumulatedPositionMs.coerceAtMost(MAX_PLAY_DURATION_MS)
        }
        
        /**
         * Update tracking with new position from MediaSession.
         * Call this whenever we get a position update.
         * 
         * @param newPosition Current playback position in ms from MediaSession
         * @param isCurrentlyPlaying Whether playback is currently active
         */
        fun updatePosition(newPosition: Long, isCurrentlyPlaying: Boolean) {
            val now = System.currentTimeMillis()
            positionUpdatesCount++
            
            // Validate position is reasonable
            // -1 is commonly sent during pause/stop - ignore silently (no warning needed)
            if (newPosition < 0) {
                return
            }
            
            // CRITICAL FIX: On first position update, establish baseline without accumulating
            // This prevents counting the initial playback position as played time
            // e.g., if user starts at 1:30 into a song, we shouldn't count 1:30 as played
            if (!hasReceivedInitialPosition) {
                lastRecordedPosition = newPosition
                lastKnownPosition = newPosition
                lastPositionUpdateTime = now
                isPlaying = isCurrentlyPlaying
                hasReceivedInitialPosition = true
                Log.d("PlaybackSession", "Established initial position baseline: ${newPosition}ms for '$title'")
                return
            }
            
            // Check if position exceeds estimated duration by too much (likely error)
            estimatedDurationMs?.let { estimated ->
                if (newPosition > estimated * 1.5) {
                    Log.w("PlaybackSession", "Position $newPosition exceeds duration $estimated by 50%, capping")
                    // Continue but cap the accumulation
                }
            }
            
            if (isCurrentlyPlaying && newPosition > lastRecordedPosition) {
                // Position advanced - accumulate the delta
                val delta = newPosition - lastRecordedPosition
                
                // Enhanced sanity check for seek detection
                val timeElapsed = now - lastPositionUpdateTime
                val maxReasonableDelta = timeElapsed + 2000 // 2 second buffer
                
                // Detect different types of position changes
                if (delta <= maxReasonableDelta) {
                    // Normal playback progression - but cap individual deltas as sanity check
                    val safeDelta = delta.coerceAtMost(MAX_REASONABLE_DELTA_MS)
                    if (delta > MAX_REASONABLE_DELTA_MS) {
                        Log.w("PlaybackSession", "ANOMALY: Large delta ${delta}ms capped to ${safeDelta}ms for '$title'")
                    }
                    accumulatedPositionMs += safeDelta
                    
                    // Cap total accumulation at 1.5x estimated duration per continuous session
                    // This allows for loop detection while preventing runaway accumulation
                    estimatedDurationMs?.let { estimated ->
                        val maxAccumulation = (estimated * 1.5).toLong()
                        if (accumulatedPositionMs > maxAccumulation && estimated > 60_000) { // Only for >1min tracks
                            Log.w("PlaybackSession", "ANOMALY: Capping accumulated ${accumulatedPositionMs}ms to $maxAccumulation (1.5x duration) for '$title'")
                            accumulatedPositionMs = maxAccumulation
                        }
                    }
                } else if (delta < MAX_POSITION_JUMP_MS) {
                    // Small seek forward (still count as seek, not playback)
                    seekCount++
                    lastSeekTimestamp = now
                    Log.d("PlaybackSession", "Forward seek detected: +${delta}ms for '${title}'")
                    // Don't accumulate seek distance
                } else {
                    // Large position jump - likely app error or major seek
                    Log.w("PlaybackSession", "ANOMALY: Large position jump: $lastRecordedPosition -> $newPosition (delta: ${delta}ms, time: ${timeElapsed}ms)")
                    // Don't accumulate to avoid inflating play time
                }
            } else if (newPosition < lastRecordedPosition) {
                // Position went backward - either seek backward or restart
                val delta = lastRecordedPosition - newPosition
                
                if (newPosition < 5000) {
                    // Reset to beginning - track restarted or looped
                    // Use verbose logging since multiple restarts in quick succession are common
                    Log.v("PlaybackSession", "Track restarted/looped for '${title}'")
                    
                    // CRITICAL FIX: Do NOT reset accumulatedPositionMs here.
                    // If the track finished and restarted, we want to KEEP the accumulated time 
                    // so that when the session eventually ends (track change), we save the FULL duration.
                    // For example, if a 3min song plays twice on loop, we should record 6mins.
                    
                    // We simply act as if nothing happened to the accumulation.
                    // The lastRecordedPosition update below handles the new baseline.
                } else if (delta > 100) {
                    // Seek backward - only count/log if delta > 100ms (ignore jitter)
                    seekCount++
                    lastSeekTimestamp = now
                    Log.d("PlaybackSession", "Backward seek detected: -${delta}ms for '${title}'")
                }
                // Tiny backward movements (< 100ms) are position jitter, ignore silently
            }
            
            lastRecordedPosition = newPosition
            lastKnownPosition = newPosition
            lastPositionUpdateTime = now
            isPlaying = isCurrentlyPlaying
        }

        /**
         * Pause playback and track pause count.
         */
        fun pause() {
            if (isPlaying) {
                isPlaying = false
                pauseCount++
                lastPauseTimestamp = System.currentTimeMillis()
                Log.d("PlaybackSession", "Paused '${title}' (pause #$pauseCount)")
            }
        }

        /**
         * Resume playback - just update state, position tracking handles the rest.
         */
        fun resume() {
            if (!isPlaying) {
                val now = System.currentTimeMillis()
                
                // Calculate pause duration
                lastPauseTimestamp?.let { pauseStart ->
                    val pauseDuration = now - pauseStart
                    totalPauseDurationMs += pauseDuration
                    Log.d("PlaybackSession", "Resumed '${title}' after ${pauseDuration}ms pause (total pause: ${totalPauseDurationMs}ms)")
                }
                
                isPlaying = true
                // Reset position update time so we don't accumulate the pause gap
                lastPositionUpdateTime = now
                lastPauseTimestamp = null
            }
        }
        
        /**
         * Check if this was a quick skip (< 30 seconds or < 30% completion).
         */
        fun wasSkipped(): Boolean {
            val duration = calculateCurrentPlayDuration()
            // Skip if less than 30 seconds played
            if (duration < 30_000) return true
            // Skip if less than 30% of estimated duration
            estimatedDurationMs?.let { estimated ->
                if (estimated > 0 && (duration.toFloat() / estimated) < 0.3f) {
                    return true
                }
            }
            return false
        }
        
        /**
         * Calculate completion percentage with fallback estimation.
         */
        fun calculateCompletionPercent(): Int {
            val duration = calculateCurrentPlayDuration()
            
            // If we have estimated duration, use it
            estimatedDurationMs?.let { estimated ->
                if (estimated > 0) {
                    return ((duration.toDouble() / estimated) * 100).toInt().coerceIn(0, 100)
                }
            }
            
            // Fallback: Estimate based on typical song duration (3.5 minutes average)
            // This gives a reasonable approximation when duration is unknown
            val typicalDurationMs = 210_000L // 3.5 minutes
            val estimatedCompletion = ((duration.toDouble() / typicalDurationMs) * 100).toInt()
            
            // Cap at 100% but use 75% as "likely full play" threshold for unknown duration
            return when {
                duration >= 240_000 -> 100 // 4+ minutes = definitely full play
                duration >= 180_000 -> 90  // 3+ minutes = likely full play
                duration >= 120_000 -> 70  // 2+ minutes = partial play
                duration >= 60_000 -> 50   // 1+ minute = half play
                else -> estimatedCompletion.coerceIn(0, 40)
            }
        }
        
        /**
         * Validate that play duration is reasonable.
         * Returns true if valid, false if suspicious.
         */
        fun validatePlayDuration(): Boolean {
            val duration = calculateCurrentPlayDuration()
            
            // Check for impossibly long duration (max 1 hour per session)
            if (duration > MAX_PLAY_DURATION_MS) {
                Log.w("PlaybackSession", "Validation failed: duration ${duration}ms exceeds max ${MAX_PLAY_DURATION_MS}ms for '${title}'")
                return false
            }
            
            // Check if duration exceeds estimated duration by unreasonable amount
            estimatedDurationMs?.let { estimated ->
                if (estimated > 0 && duration > estimated * 3) {
                    Log.w("PlaybackSession", "Validation failed: duration ${duration}ms is 3x estimated ${estimated}ms for '${title}'")
                    return false
                }
            }
            
            // Check for minimum duration (at least 1 second of playback)
            if (duration < 1000 && positionUpdatesCount > 5) {
                Log.w("PlaybackSession", "Validation warning: only ${duration}ms recorded despite ${positionUpdatesCount} updates")
                // Still return true - might be very short track or quick skip
            }
            
            return true
        }
        
        /**
         * Validate completion percentage is accurate.
         */
        fun validateCompletionPercentage(): Boolean {
            val percent = calculateCompletionPercent()
            
            // Completion should be 0-100
            if (percent < 0 || percent > 100) {
                Log.w("PlaybackSession", "Invalid completion percentage: $percent for '${title}'")
                return false
            }
            
            return true
        }
        
        /**
         * Detect and report anomalies in the session data.
         * Returns a list of anomaly descriptions.
         */
        fun detectAnomalies(): List<String> {
            val anomalies = mutableListOf<String>()
            
            val duration = calculateCurrentPlayDuration()
            
            // Anomaly 1: Very high pause count relative to duration
            if (pauseCount > 0 && duration > 0) {
                val avgTimeBetweenPauses = duration / pauseCount
                if (avgTimeBetweenPauses < 5000) { // Pause every 5 seconds
                    anomalies.add("High pause frequency: $pauseCount pauses in ${duration}ms")
                }
            }
            
            // Anomaly 2: Pause duration exceeds play duration significantly
            if (totalPauseDurationMs > duration * 5) {
                anomalies.add("Pause time (${totalPauseDurationMs}ms) >> play time (${duration}ms)")
            }
            
            // Anomaly 3: Many seeks in short time
            if (seekCount > 10 && duration < 60000) {
                anomalies.add("Excessive seeking: $seekCount seeks in ${duration}ms")
            }
            
            // Anomaly 4: Very few position updates for long duration
            if (duration > 60000 && positionUpdatesCount < 10) {
                anomalies.add("Few position updates: $positionUpdatesCount updates for ${duration}ms")
            }
            
            // Anomaly 5: Duration far exceeds estimated
            estimatedDurationMs?.let { estimated ->
                if (estimated > 0 && duration > estimated * 2) {
                    anomalies.add("Duration ${duration}ms is 2x+ estimated ${estimated}ms")
                }
            }
            
            return anomalies
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MusicTrackingService created")
        serviceStartTime.set(System.currentTimeMillis())
        
        // Initialize dependencies via Hilt EntryPoint
        // This is necessary because NotificationListenerService is managed by the system
        // and @AndroidEntryPoint doesn't work properly for it
        initializeDependencies()
        
        // Initialize enhanced tracking components
        initializeTrackingComponents()
        
        // Recover any persisted sessions from previous runs
        recoverPersistedSessions()
        
        createNotificationChannel()
        startForegroundServiceWithNotification()
        // Initialize MediaSessionManager
        initializeMediaSessionManager()

        // Initial check for service lifecycle
        updateServiceLifecycle()
    }
    
    private fun initializeDependencies() {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                MusicTrackingServiceEntryPoint::class.java
            )
            trackRepository = entryPoint.trackRepository()
            listeningRepository = entryPoint.listeningRepository()
            enrichedMetadataRepository = entryPoint.enrichedMetadataRepository()
            statsRepository = entryPoint.statsRepository()
            artistLinkingService = entryPoint.artistLinkingService()
            userPreferencesDao = entryPoint.userPreferencesDao()
            trackAliasRepository = entryPoint.trackAliasRepository()
            manualContentMarkDao = entryPoint.manualContentMarkDao()
            
            // Load initial preferences for caching
            serviceScope.launch {
                try {
                    val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
                    cachedMergeAlternateVersions = prefs.mergeAlternateVersions
                    lastPreferencesFetch = System.currentTimeMillis()
                    Log.d(TAG, "Loaded user preferences: mergeAlternateVersions=$cachedMergeAlternateVersions")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load initial preferences, using defaults", e)
                }
            }
            
            Log.d(TAG, "Dependencies initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize dependencies", e)
            throw e
        }
    }
    
    private fun initializeTrackingComponents() {
        try {
            // Initialize tracking manager for batched event saving
            trackingManager = MusicTrackingManager(listeningRepository, serviceScope)
            
            // Initialize session persistence for recovery
            sessionPersistence = SessionPersistence(applicationContext)
            sessionPersistence.markServiceActive()
            
            // Initialize smart duration estimator
            durationEstimator = SmartDurationEstimator()
            
            Log.d(TAG, "Enhanced tracking components initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize tracking components", e)
            // Create fallback instances
            trackingManager = MusicTrackingManager(listeningRepository, serviceScope)
            sessionPersistence = SessionPersistence(applicationContext)
            durationEstimator = SmartDurationEstimator()
        }
    }
    
    private fun recoverPersistedSessions() {
        serviceScope.launch {
            try {
                if (sessionPersistence.wasUncleanShutdown()) {
                    Log.i(TAG, "Detected unclean shutdown, attempting session recovery")
                    val recoveredSessions = sessionPersistence.loadSessions()
                    
                    if (recoveredSessions.isNotEmpty()) {
                        Log.i(TAG, "Recovered ${recoveredSessions.size} sessions from persistence")
                        
                        // Process recovered sessions - they were interrupted
                        for (state in recoveredSessions) {
                            // FIX: Don't add time since last save - the session was interrupted
                            // and we can't know if playback actually continued after last save.
                            // Using only the persisted play time prevents inflation from device sleep.
                            val estimatedPlayTime = state.totalPlayedMs
                            
                            // Cap recovered sessions at reasonable maximum
                            val cappedPlayTime = estimatedPlayTime.coerceAtMost(MAX_PLAY_DURATION_MS)
                            if (estimatedPlayTime != cappedPlayTime) {
                                Log.w(TAG, "ANOMALY: Recovered session '${state.trackTitle}' had ${estimatedPlayTime}ms, capped to ${cappedPlayTime}ms")
                            }
                            
                            if (cappedPlayTime > MIN_PLAY_DURATION_MS && state.trackId != null) {
                                // Also cap at 3x estimated duration for sanity
                                val finalPlayTime = if (state.estimatedDurationMs != null && state.estimatedDurationMs > 0) {
                                    val maxReasonable = state.estimatedDurationMs * 3
                                    cappedPlayTime.coerceAtMost(maxReasonable)
                                } else {
                                    cappedPlayTime
                                }
                                
                                // Create a listening event for the recovered session
                                val event = ListeningEvent(
                                    track_id = state.trackId,
                                    timestamp = state.startTimestamp,
                                    playDuration = finalPlayTime,
                                    completionPercentage = durationEstimator.calculateCompletionPercent(
                                        finalPlayTime, 
                                        state.estimatedDurationMs,
                                        false
                                    ),
                                    source = state.packageName,
                                    wasSkipped = false,
                                    isReplay = false,
                                    estimatedDurationMs = state.estimatedDurationMs,
                                    pauseCount = state.pauseCount,
                                    sessionId = state.sessionId,
                                    endTimestamp = System.currentTimeMillis(),
                                    wasInterrupted = true  // Mark as recovered/interrupted session
                                )
                                
                                trackingManager.queueEvent(event, state.sessionId)
                                Log.i(TAG, "Queued recovered event for '${state.trackTitle}': ${finalPlayTime}ms (was ${estimatedPlayTime}ms)")
                            }
                        }
                    }
                }
                
                // Clear persisted sessions after recovery
                sessionPersistence.clearSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover persisted sessions", e)
            }
        }
    }
    
    private fun startSessionAutoSave() {
        if (autoSaveJob?.isActive == true) return
        
        Log.d(TAG, "Starting session auto-save")
        
        autoSaveJob = serviceScope.launch {
            while (isActive) {
                delay(SESSION_AUTOSAVE_INTERVAL_MS)
                if (!hasActivePlayback()) {
                    Log.d(TAG, "No active playback, stopping auto-save")
                    break
                }
                saveSessionsToPersistence()
            }
        }
    }
    
    /**
     * Check if there is any active music playback.
     * Used to optimize polling frequency and notification behavior.
     */
    private fun hasActivePlayback(): Boolean {
        return playbackStates.values.any { session ->
            session.isPlaying && session.isLikelyMusic
        }
    }
    
    // Position polling job for accurate duration tracking
    private var positionPollingJob: Job? = null
    
    /**
     * Start polling MediaSession controllers for position updates.
     * Only runs when there is active playback.
     */
    private fun startPositionPolling() {
        // Only start if not already running
        if (positionPollingJob?.isActive == true) return
        
        Log.d(TAG, "Starting position polling (active playback detected)")
        
        positionPollingJob = serviceScope.launch {
            while (isActive) {
                if (!hasActivePlayback()) {
                    Log.d(TAG, "No active playback, stopping position polling")
                    break
                }
                
                pollMediaSessionPositions()
                
                // Use smart adaptive polling interval based on song phase
                val interval = calculateSmartPollingInterval()
                delay(interval)
            }
        }
    }
    
    /**
     * Calculate smart polling interval based on song phase and duration.
     * 
     * Strategy:
     * - SHORT TRACKS (<90s): Poll every 5s - these need accuracy throughout
     * - SONG START (first 30s): Poll every 3s - critical for skip detection
     * - SONG END (last 30s): Poll every 4s - critical for completion detection
     * - SONG MIDDLE: Poll every 8s - battery efficient, callbacks handle state changes
     * - UNKNOWN DURATION: Poll every 6s - balanced approach
     * 
     * Battery savings: ~50% fewer polls for 3-4 minute songs while maintaining
     * accuracy where it matters most (skip/completion detection).
     */
    private fun calculateSmartPollingInterval(): Long {
        val activeSession = playbackStates.values.firstOrNull { it.isPlaying && it.isLikelyMusic }
            ?: return POLL_INTERVAL_UNKNOWN_DURATION_MS
        
        val estimatedDuration = activeSession.estimatedDurationMs
        val currentPosition = activeSession.lastRecordedPosition
        
        // Case 1: Unknown duration - use balanced polling
        if (estimatedDuration == null || estimatedDuration <= 0) {
            return POLL_INTERVAL_UNKNOWN_DURATION_MS
        }
        
        // Case 2: Short track - needs consistent accuracy throughout
        if (estimatedDuration < SHORT_TRACK_THRESHOLD_MS) {
            return POLL_INTERVAL_SHORT_TRACK_MS
        }
        
        // Case 3: Determine song phase for longer tracks
        val remainingTime = estimatedDuration - currentPosition
        
        return when {
            // Start phase: first 30 seconds - critical for skip detection
            currentPosition < SONG_START_PHASE_MS -> POLL_INTERVAL_SONG_START_MS
            
            // End phase: last 30 seconds - critical for completion detection
            remainingTime > 0 && remainingTime < SONG_END_PHASE_MS -> POLL_INTERVAL_SONG_END_MS
            
            // Middle phase: battery efficient polling
            else -> POLL_INTERVAL_SONG_MIDDLE_MS
        }
    }
    
    /**
     * Update service lifecycle based on current state.
     * Starts/stops background jobs and manages foreground state.
     */
    private fun updateServiceLifecycle() {
        val hasActive = hasActivePlayback()
        
        if (hasActive) {
            startPositionPolling()
            startSessionAutoSave()
        } else {
            // Stop jobs if running
            if (positionPollingJob?.isActive == true) {
                Log.d(TAG, "Stopping position polling (no active playback)")
                positionPollingJob?.cancel()
                positionPollingJob = null
            }
            
            if (autoSaveJob?.isActive == true) {
                Log.d(TAG, "Stopping session auto-save (no active playback)")
                autoSaveJob?.cancel()
                autoSaveJob = null
            }
            
            // FIX: Check if we still have a valid (paused) session
            val currentSession = playbackStates.values.firstOrNull()
            if (currentSession != null) {
                 // We have a session (likely paused), keep showing it
                 // This resolves the user complaint "it stopped tracking on pause"
                 updateTrackingNotification(currentSession.title, currentSession.artist)
            } else {
                 // No sessions at all -> Idle
                 updateTrackingNotification(null, null)
            }
        }
    }
    
    /**
     * Poll all active MediaSession controllers for position updates.
     * Updates the corresponding PlaybackSession with accurate position data.
     */
    private fun pollMediaSessionPositions() {
        activeControllers.forEach { (packageName, controller) ->
            try {
                val playbackState = controller.playbackState ?: return@forEach
                val session = playbackStates[packageName] ?: return@forEach
                
                val currentPosition = playbackState.position
                val isPlaying = playbackState.state == PlaybackState.STATE_PLAYING
                
                // Update position tracking
                session.updatePosition(currentPosition, isPlaying)
                
                // Log periodically for debugging (every ~30 seconds of accumulated play time)
                if (isPlaying && session.accumulatedPositionMs > 0 && session.accumulatedPositionMs % 30000 < 10000) {
                    Log.d(TAG, "Position poll: '${session.title}' - position: ${currentPosition}ms, accumulated: ${session.accumulatedPositionMs}ms")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error polling position for $packageName", e)
            }
        }
    }
    
    private suspend fun saveSessionsToPersistence() {
        try {
            val currentSessions = playbackStates.mapNotNull { (pkg, session) ->
                session.trackId?.let { trackId ->
                    SessionState(
                        sessionId = session.sessionId,
                        packageName = session.packageName,
                        trackId = trackId,
                        trackTitle = session.title,
                        trackArtist = session.artist,
                        trackAlbum = session.album,
                        startTimestamp = session.startTimestamp,
                        lastResumeTimestamp = session.lastPositionUpdateTime,
                        totalPlayedMs = session.accumulatedPositionMs,
                        isPlaying = session.isPlaying,
                        pauseCount = session.pauseCount,
                        estimatedDurationMs = session.estimatedDurationMs
                    )
                }
            }.associateBy { it.sessionId }
            
            // Only persist if there are active sessions (optimization)
            if (currentSessions.isNotEmpty()) {
                sessionPersistence.saveSessions(currentSessions)
            } else {
                // No active sessions - skip persistence to reduce I/O
                Log.v(TAG, "Skipping session persistence - no active sessions")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-save sessions", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        return START_STICKY // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind: ${intent?.action}")
        return super.onBind(intent)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        
        synchronized(connectionLock) {
            if (isListenerConnected) {
                Log.e(TAG, "onListenerConnected called on an already connected service! This can result in duplicate events.")
                return
            }
            isListenerConnected = true
        }
        
        Log.i(TAG, "NotificationListener connected - scanning active notifications")
        
        // Scan existing notifications for music that's already playing
        // Use a slight delay to ensure the binder is fully registered with the system
        serviceScope.launch {
            delay(500) // Wait for system to fully register the listener
            withContext(Dispatchers.Main) {
                try {
                    activeNotifications?.forEach { sbn ->
                        if (isMusicNotification(sbn)) {
                            processNotificationPosted(sbn)
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException scanning notifications - listener may have been rebound", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error scanning active notifications", e)
                }
                
                // Update lifecycle after initial scan using the correct context
                updateServiceLifecycle()
            }
        }
    }

    override fun onListenerDisconnected() {
        synchronized(connectionLock) {
            isListenerConnected = false
        }
        
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener disconnected - attempting reconnect")
        
        // Request rebind
        requestRebind(ComponentName(this, MusicTrackingService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isMusicNotification(sbn)) return
        
        Log.d(TAG, "Music notification posted: ${sbn.packageName}")
        processNotificationPosted(sbn)
        updateServiceLifecycle()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isMusicNotification(sbn)) return
        
        Log.d(TAG, "Music notification removed: ${sbn.packageName}")
        processNotificationRemoved(sbn)
        updateServiceLifecycle()
    }

    private fun isMusicNotification(sbn: StatusBarNotification): Boolean {
        val packageName = sbn.packageName
        
        // FIRST: Explicitly block video/non-music apps
        if (packageName in BLOCKED_APPS) {
            Log.d(TAG, "Blocking notification from video app: $packageName")
            return false
        }
        
        // Check if it's from a known music app
        if (packageName in MUSIC_APPS) return true
        
        // For unknown apps, check notification category
        val notification = sbn.notification
        val category = notification.category
        if (category == Notification.CATEGORY_TRANSPORT || 
            category == Notification.CATEGORY_SERVICE) {
            // Check for media style - but still need to verify it's music
            val extras = notification.extras
            if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                // Additional check: don't track if package name suggests video
                val lowerPkg = packageName.lowercase()
                if (lowerPkg.contains("video") || lowerPkg.contains("movie") || 
                    lowerPkg.contains("tv") || lowerPkg.contains("stream")) {
                    Log.d(TAG, "Blocking media notification from likely video app: $packageName")
                    return false
                }
                return true
            }
        }
        
        return false
    }

    private fun processNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras

        // Extract metadata from notification using robust extraction
        val rawTitle = extras.getCharSequence(EXTRA_TITLE)?.toString()?.trim()
        // Skip notifications with empty titles - metadata hasn't fully arrived yet
        if (rawTitle.isNullOrBlank()) {
            Log.d(TAG, "Ignoring notification with empty title from $packageName")
            return
        }
        val artist = extractArtistFromNotification(extras, rawTitle)
        
        // Clean title if it contains embedded artist (e.g., "Song - Artist")
        val title = if (artist != "Unknown Artist" && artist != extractArtistFromTitle(rawTitle)) {
            rawTitle // Artist came from elsewhere, keep title as-is
        } else if (extractArtistFromTitle(rawTitle) != null) {
            cleanTitleIfNeeded(rawTitle) // Artist extracted from title, clean it
        } else {
            rawTitle
        }
        
        val album = extras.getCharSequence(EXTRA_SUB_TEXT)?.toString()?.trim()
            ?.takeIf { !it.equals(artist, ignoreCase = true) } // Don't use SUB_TEXT if it's the artist
            ?: extras.getCharSequence(EXTRA_INFO_TEXT)?.toString()?.trim()
        
        // Extract album art from notification (for fallback use)
        val albumArtBitmap = extractAlbumArtFromNotification(notification)

        Log.d(TAG, "Music notification: '$title' by '$artist' from $packageName (has art: ${albumArtBitmap != null})")

        // CRITICAL: Use synchronized block to prevent race condition when
        // notification and MediaSession callbacks fire simultaneously for the same track.
        // Without this, both could see existingSession=null, create sessions, and one overwrites the other.
        val (sessionAction, existingSession) = synchronized(sessionOperationLock) {
            val existing = playbackStates[packageName]
            
            // Check if this is the same track (handle artist updates from Unknown -> Real)
            val isSameTrackInfo = existing != null && existing.title == title && (
                existing.artist == artist || 
                // If existing session has unknown artist but notification has real artist, update it
                (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(existing.artist) && 
                 !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist))
            )
            
            // Detect replay based on accumulated play time exceeding estimated duration
            val isReplayDetected = isSameTrackInfo && run {
                val playedMs = existing!!.calculateCurrentPlayDuration()
                val estimatedDuration = existing.estimatedDurationMs
                val playedExceedsDuration = estimatedDuration != null && 
                    estimatedDuration > 0 && 
                    playedMs > estimatedDuration + 10_000L
                if (playedExceedsDuration) {
                    Log.d(TAG, "Notification: Replay detected for '${existing.title}': " +
                        "played ${playedMs}ms exceeds estimated duration ${estimatedDuration}ms")
                }
                playedExceedsDuration
            }
            
            val isSameTrack = isSameTrackInfo && !isReplayDetected
            
            when {
                isSameTrack -> Pair("UPDATE", existing)
                else -> Pair("NEW", existing) // existing may be non-null (different track) or null
            }
        }

        when (sessionAction) {
            "UPDATE" -> {
                // Same track - update artist if we now have a better one
                val session = existingSession!!
                if (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(session.artist) && 
                    !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist)) {
                    Log.d(TAG, "Notification: Updating session artist from '${session.artist}' to '$artist' for '$title'")
                    session.artist = artist
                    
                    // Update the track in database if we have a trackId
                    session.trackId?.let { trackId ->
                        serviceScope.launch {
                            try {
                                val tracks = trackRepository.all().first()
                                val track = tracks.find { it.id == trackId }
                                if (track != null && me.avinas.tempo.utils.ArtistParser.isUnknownArtist(track.artist)) {
                                    Log.i(TAG, "Updating track $trackId artist from '${track.artist}' to '$artist' (via notification)")
                                    val updatedTrack = track.copy(artist = artist)
                                    trackRepository.update(updatedTrack)
                                    
                                    // Re-link artists and trigger enrichment
                                    artistLinkingService.linkArtistsForTrack(updatedTrack)
                                    EnrichmentWorker.enqueueImmediate(applicationContext, trackId)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to update track artist from notification", e)
                            }
                        }
                    }
                    
                    updateTrackingNotification(title, artist)
                }
                
                // Might be a play/pause update
                if (!session.isPlaying) {
                    session.resume()
                }
                return
            }
            "NEW" -> {
                // Check if this is likely an advertisement - skip tracking ads
                if (artist.isNotBlank() && 
                    !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist) &&
                    isLikelyAdvertisementFromNotification(title, artist, album, packageName)) {
                    if (!loggedAdvertisementSkips.containsKey(title)) {
                        loggedAdvertisementSkips[title] = true
                        Log.d(TAG, "Skipping likely advertisement: '$title' from $packageName")
                    }
                    return
                }

                // New track or different track - save previous session first
                if (existingSession != null) {
                    existingSession.pause()
                    saveListeningEvent(existingSession)
                }

                // Try to get cached duration estimate for this track
                val cachedDurationKey = generateHash("$title|$artist")
                val cachedDuration = durationEstimateCache[cachedDurationKey]

                // Clear log debounce caches for fresh logging on new track
                loggedArtistExtractionFailures.clear()
                loggedAdvertisementSkips.clear()
                
                val newSession = PlaybackSession(
                    packageName = packageName,
                    title = title,
                    artist = artist,
                    album = album,
                    isPlaying = true,
                    estimatedDurationMs = cachedDuration,
                    accumulatedPositionMs = 0,
                    lastRecordedPosition = 0,
                    isLikelyMusic = true
                )
                
                // Atomic put - thread-safe even without the outer lock
                playbackStates[packageName] = newSession
                updateTrackingNotification(title, artist)
                
                // Save album art locally for fallback use (if extracted)
                val localArtUrl = albumArtBitmap?.let { saveAlbumArtToStorage(it, title, artist) }

                // Insert track asynchronously but ensure trackId is set before any save
                serviceScope.launch {
                    try {
                        val track = getOrInsertTrack(title, artist, album)
                        newSession.trackId = track.id
                        
                        // Try to get duration from track or enriched metadata
                        val duration = track.duration ?: getTrackDurationFromMetadata(track.id)
                        if (duration != null && duration > 0) {
                            newSession.estimatedDurationMs = duration
                            // Also cache it for future use
                            durationEstimateCache[cachedDurationKey] = duration
                        }
                        
                        Log.d(TAG, "Track ID ${track.id} assigned for '${title}' (duration: ${newSession.estimatedDurationMs?.let { "${it/1000}s" } ?: "unknown"})")
                        
                        // If we have local album art, schedule a delayed update
                        // This gives enrichment time to complete first (MusicBrainz is preferred)
                        if (localArtUrl != null) {
                            // Wait for enrichment to complete (give it 10 seconds)
                            delay(10_000)
                            updateTrackAlbumArtIfNeeded(track.id, localArtUrl)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error inserting track", e)
                    }
                }
            }
        }
    }
    
    /**
     * Try to get track duration from enriched metadata.
     */
    private suspend fun getTrackDurationFromMetadata(trackId: Long): Long? {
        return try {
            enrichedMetadataRepository.forTrackSync(trackId)?.trackDurationMs
        } catch (e: Exception) {
            Log.w(TAG, "Could not get duration from metadata for track $trackId", e)
            null
        }
    }

    private fun processNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val session = playbackStates.remove(packageName) ?: return

        session.pause()
        saveListeningEvent(session)

        // Update notification
        if (playbackStates.isEmpty()) {
            updateTrackingNotification(null, null)
        } else {
            // Show the next active session
            val nextSession = playbackStates.values.firstOrNull()
            updateTrackingNotification(nextSession?.title, nextSession?.artist)
        }
    }

    private suspend fun getOrInsertTrack(
        title: String, 
        artist: String, 
        album: String?,
        metadata: LocalMediaMetadata? = null
    ): Track {
        // Clean the title to remove embedded artist info for better matching
        val cleanTitle = me.avinas.tempo.utils.ArtistParser.cleanTrackTitle(title)
        
        // Get all tracks for matching
        val tracks = trackRepository.all().first()
        
        // First, try exact match (fast path)
        val exactMatch = tracks.find { track ->
            track.title.equals(title, ignoreCase = true) && 
            track.artist.equals(artist, ignoreCase = true)
        }
        
        if (exactMatch != null) {
            Log.d(TAG, "Found exact match for track ${exactMatch.id}: '$title' by '$artist'")
            return handleExistingTrack(exactMatch, artist, album)
        }
        
        // Step 0: Check for Smart Alias (Manual Override)
        // If user manually merged this track before, respect that decision forever.
        val alias = trackAliasRepository.findAlias(title, artist)
        if (alias != null) {
            val targetTrack = trackRepository.getById(alias.targetTrackId).first()
            if (targetTrack != null) {
                Log.i(TAG, "Smart Alias found: '$title' -> mapped to '${targetTrack.title}' (ID: ${targetTrack.id})")
                return handleExistingTrack(targetTrack, artist, album)
            } else {
                // Orphaned alias (target deleted), ignore it
                Log.w(TAG, "Orphaned alias found for '$title', ignoring")
            }
        }

        // Use advanced fuzzy matching with TrackMatcher
        val candidates = tracks.map { track ->
            TrackCandidate(
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album
            )
        }
        
        // Check user preference for matching strictness (cached for performance)
        // Refresh cache if older than TTL (60 seconds)
        val now = System.currentTimeMillis()
        if (now - lastPreferencesFetch > PREFERENCES_CACHE_TTL_MS) {
            try {
                val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
                cachedMergeAlternateVersions = prefs.mergeAlternateVersions
                lastPreferencesFetch = now
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh preferences, using cached value", e)
            }
        }
        
        // Invert the preference: mergeAlternateVersions=true means strictMatching=false
        val strictMatching = !cachedMergeAlternateVersions
        
        val matchResult = TrackMatcher.findBestMatch(title, artist, candidates, strictMatching)
        
        if (matchResult != null && matchResult.second.overallScore >= TRACK_MATCH_THRESHOLD) {
            val (candidate, result) = matchResult
            val existingTrack = tracks.find { it.id == candidate.id }
            
            if (existingTrack != null) {
                Log.d(TAG, "Found fuzzy match for track ${existingTrack.id}: '$title' by '$artist' " +
                        "(score: ${String.format("%.2f", result.overallScore)}, type: ${result.matchType})")
                return handleExistingTrack(existingTrack, artist, album)
            }
        }
        
        // Also try with cleaned title for legacy compatibility
        val cleanTitleMatch = tracks.find { track ->
            track.title.equals(cleanTitle, ignoreCase = true) && 
            me.avinas.tempo.utils.ArtistParser.hasAnyMatchingArtist(track.artist, artist)
        }
        
        if (cleanTitleMatch != null) {
            Log.d(TAG, "Found clean title match for track ${cleanTitleMatch.id}: '$title' by '$artist'")
            return handleExistingTrack(cleanTitleMatch, artist, album)
        }
        
        // No existing track found - insert new one
        return insertNewTrack(title, artist, album, metadata)
    }
    
    /**
     * Handle an existing track - update if needed and ensure proper linking.
     */
    private suspend fun handleExistingTrack(existingTrack: Track, newArtist: String, newAlbum: String?): Track {
        // Update artist if the existing track has unknown artist but new one has real artist
        if (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(existingTrack.artist) &&
            !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(newArtist)) {
            Log.i(TAG, "Updating track ${existingTrack.id} artist from '${existingTrack.artist}' to '$newArtist'")
            val updatedTrack = existingTrack.copy(artist = newArtist, album = newAlbum ?: existingTrack.album)
            trackRepository.update(updatedTrack)
            
            // Re-link artists now that we have the real artist name
            artistLinkingService.linkArtistsForTrack(updatedTrack)
            
            // Re-trigger enrichment now that we have the real artist
            EnrichmentWorker.enqueueImmediate(applicationContext, existingTrack.id)
            
            return updatedTrack
        }
        
        // Ensure artists are linked even for existing tracks (migration support)
        if (existingTrack.primaryArtistId == null && !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(existingTrack.artist)) {
            serviceScope.launch {
                try {
                    artistLinkingService.linkArtistsForTrack(existingTrack)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to link artists for existing track ${existingTrack.id}", e)
                }
            }
        }
        
        return existingTrack
    }
    
    /**
     * Insert a new track into the database.
     */
    private suspend fun insertNewTrack(
        title: String, 
        artist: String, 
        album: String?,
        metadata: LocalMediaMetadata? = null
    ): Track {
        Log.d(TAG, "Inserting new track: '$title' by '$artist'")
        
        // Determine content type from metadata if available, otherwise default to MUSIC
        val contentType = metadata?.getContentType()?.toString() ?: "MUSIC"
        
        val newTrack = Track(
            title = title,
            artist = artist,
            album = album,
            duration = null,
            albumArtUrl = null,
            spotifyId = null,
            musicbrainzId = null,
            contentType = contentType
        )
        
        val id = trackRepository.insert(newTrack)
        Log.i(TAG, "Inserted new track with ID $id: '$title' by '$artist'")
        
        if (id <= 0) {
            Log.e(TAG, "Failed to insert track - got invalid ID: $id")
        }
        
        val insertedTrack = newTrack.copy(id = id)
        
        // Link artists for the new track (creates Artist records and TrackArtist links)
        if (!me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist)) {
            try {
                val linkedTrack = artistLinkingService.linkArtistsForTrack(insertedTrack)
                
                // Create pending enrichment entry for new track
                enrichedMetadataRepository.createPendingIfNotExists(id)
                
                // Trigger background enrichment
                EnrichmentWorker.enqueueImmediate(applicationContext, id)
                
                return linkedTrack
            } catch (e: Exception) {
                Log.e(TAG, "Failed to link artists for track $id", e)
            }
        } else {
            Log.d(TAG, "Deferring artist linking for track $id - artist is unknown, waiting for metadata to settle")
            
            // Create pending enrichment entry for new track
            enrichedMetadataRepository.createPendingIfNotExists(id)
            
            // DON'T trigger immediate enrichment for unknown artists - wait for metadata to settle
            // The artist often arrives shortly after via notification or MediaSession update
            // Enrichment will be triggered when the artist is discovered and the track is updated
            // This prevents wasted API calls searching for "Unknown Artist"
            
            return insertedTrack
        }
        
        // Fallback: this should only be reached if artistLinkingService.linkArtistsForTrack throws
        // Create pending enrichment entry for new track (enrichment will correct artist if needed)
        enrichedMetadataRepository.createPendingIfNotExists(id)
        
        return insertedTrack
    }
    
    /**
     * Save rich local metadata as a fallback for when external enrichment fails.
     * This is called after a delay to give MusicBrainz/Spotify a chance first.
     * 
     * Key insight: Even if external enrichment succeeded, it might not have found genres.
     * In that case, we should still use local genre if available.
     */
    private val loggedEnrichedTracks = mutableSetOf<Long>() // Track IDs we've already logged as enriched
    
    private suspend fun saveLocalMetadataFallback(trackId: Long, localMetadata: LocalMediaMetadata) {
        try {
            // Always update the local metadata cache (music players send metadata in stages)
            val cachedMetadata = localMetadataCache[trackId]
            val updatedLocalMetadata = if (cachedMetadata != null) {
                // Merge: prefer non-null values from latest metadata
                cachedMetadata.copy(
                    genre = localMetadata.genre ?: cachedMetadata.genre,
                    album = localMetadata.album ?: cachedMetadata.album,
                    year = localMetadata.year ?: cachedMetadata.year,
                    durationMs = localMetadata.durationMs ?: cachedMetadata.durationMs,
                    albumArtBitmap = localMetadata.albumArtBitmap ?: cachedMetadata.albumArtBitmap,
                    albumArtUri = localMetadata.albumArtUri ?: cachedMetadata.albumArtUri
                )
            } else {
                localMetadata
            }
            localMetadataCache[trackId] = updatedLocalMetadata
            
            // IMPORTANT: Always save local bitmap to storage immediately if we have one
            // This ensures we have a local backup even if enriched URL exists but fails to load
            val savedLocalArtUrl = updatedLocalMetadata.albumArtBitmap?.let { 
                saveAlbumArtToStorage(it, updatedLocalMetadata.title, updatedLocalMetadata.artist) 
            }
            
            // Check if track already has enriched metadata from external sources
            val existingMetadata = enrichedMetadataRepository.forTrackSync(trackId)
            
            // Check if enriched art URL exists and is a remote URL
            val enrichedArtUrl = existingMetadata?.albumArtUrl
            val enrichedArtIsRemoteUrl = enrichedArtUrl?.startsWith("http") == true
            
            // Strategy for album art:
            // - If we have enriched hotlink: Store enriched in EnrichedMetadata, local in Track (as backup)
            // - If no enriched hotlink: Store local in both
            // - UI will try enriched first, fall back to local if it fails, and delete local on success
            
            // Determine what we should fill in from local metadata
            val hasLocalArt = savedLocalArtUrl != null || updatedLocalMetadata.albumArtUri != null
            val needsAlbumArt = enrichedArtUrl.isNullOrBlank() && hasLocalArt
            val needsGenre = existingMetadata?.genres.isNullOrEmpty() && updatedLocalMetadata.genre != null
            val needsAlbum = existingMetadata?.albumTitle.isNullOrBlank() && updatedLocalMetadata.album != null
            val needsYear = existingMetadata?.releaseYear == null && updatedLocalMetadata.getReleaseYear() != null
            val needsDuration = existingMetadata?.trackDurationMs == null && updatedLocalMetadata.durationMs != null
            
            val shouldUpdate = existingMetadata == null || needsAlbumArt || needsGenre || needsAlbum || needsYear || needsDuration

            
            // CRITICAL FIX: Even if we don't need to update EnrichedMetadata (shouldUpdate=false),
            // we MUST ensure Track entity has album art for the UI.
            // Strategy: Store local as backup in Track table when enriched hotlink exists
            if (!shouldUpdate) {
                val track = trackRepository.getById(trackId).first()
                if (track != null && track.albumArtUrl.isNullOrBlank() && savedLocalArtUrl != null) {
                    // Store local art in Track table as immediate backup
                    Log.i(TAG, "Storing local art as backup in Track table while enriched hotlink exists")
                    trackRepository.update(track.copy(albumArtUrl = savedLocalArtUrl))
                } else if (track != null && enrichedArtIsRemoteUrl && savedLocalArtUrl != null) {
                    // We have both enriched hotlink and local backup
                    // Keep current setup but log for tracking
                    Log.d(TAG, "Track $trackId: Has enriched hotlink '${enrichedArtUrl?.take(30)}...' and local backup saved: $savedLocalArtUrl")
                }
                
                // Only log once per track to reduce spam
                if (loggedEnrichedTracks.add(trackId)) {
                     val hasGenres = !existingMetadata?.genres.isNullOrEmpty()
                     val localHasGenre = updatedLocalMetadata.genre != null
                     if (!hasGenres && !localHasGenre) {
                        Log.d(TAG, "Track $trackId: No genres from external sources or local metadata (local genre was null)")
                     } else {
                        Log.d(TAG, "Track $trackId: Already has enriched metadata. " +
                            "Enriched art: '${enrichedArtUrl?.take(20)}...', Local backup saved: $savedLocalArtUrl")
                     }
                }
                return
            }
            
            // Use already-saved local art URL, or get from URI
            val localArtUrl = savedLocalArtUrl ?: updatedLocalMetadata.getBestAlbumArtSource()
            
            // Update the enriched metadata with local data (only fill in missing fields)
            val updatedMetadata = (existingMetadata ?: me.avinas.tempo.data.local.entities.EnrichedMetadata(trackId = trackId)).copy(
                albumTitle = existingMetadata?.albumTitle ?: updatedLocalMetadata.album,
                albumArtUrl = existingMetadata?.albumArtUrl ?: localArtUrl,
                // Set source as LOCAL when using local album art - this allows API sources to replace it later
                albumArtSource = if (needsAlbumArt && localArtUrl != null) {
                    me.avinas.tempo.data.local.entities.AlbumArtSource.LOCAL
                } else {
                    existingMetadata?.albumArtSource ?: me.avinas.tempo.data.local.entities.AlbumArtSource.NONE
                },
                releaseYear = existingMetadata?.releaseYear ?: updatedLocalMetadata.getReleaseYear(),
                trackDurationMs = existingMetadata?.trackDurationMs ?: updatedLocalMetadata.durationMs,
                genres = if (existingMetadata?.genres.isNullOrEmpty() && updatedLocalMetadata.genre != null) {
                    listOf(updatedLocalMetadata.genre)
                } else {
                    existingMetadata?.genres ?: emptyList()
                },
                // Mark as having local metadata if we filled in any fields
                cacheTimestamp = System.currentTimeMillis()
            )
            
            enrichedMetadataRepository.upsert(updatedMetadata)
            
            // Log what we filled in
            val filledFields = mutableListOf<String>()
            if (needsGenre) filledFields.add("genre='${updatedLocalMetadata.genre}'")
            if (needsAlbumArt && localArtUrl != null) filledFields.add("albumArt")
            if (needsAlbum) filledFields.add("album='${updatedLocalMetadata.album}'")
            if (needsYear) filledFields.add("year=${updatedLocalMetadata.getReleaseYear()}")
            
            if (filledFields.isNotEmpty()) {
                Log.i(TAG, "Local metadata fallback for track $trackId: ${filledFields.joinToString(", ")}")
            }
            
            // Also update the Track table with album art for display
            if (localArtUrl != null) {
                val track = trackRepository.getById(trackId).first()
                if (track != null && track.albumArtUrl.isNullOrBlank()) {
                    val updatedTrack = track.copy(
                        albumArtUrl = localArtUrl,
                        album = track.album.takeUnless { it.isNullOrBlank() } ?: updatedLocalMetadata.album,
                        duration = track.duration ?: updatedLocalMetadata.durationMs
                    )
                    trackRepository.update(updatedTrack)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving local metadata fallback for track $trackId", e)
        }
    }

    /**
     * Helper to ensure Track entity has album art, preferring enriched source but falling back to local.
     */
    /**
     * Helper to ensure Track entity has album art, preferring enriched source but falling back to local.
     * 
     * @param trackId The track ID to check
     * @param enrichedArtUrl The enriched art URL (may be HTTP which could fail)
     * @param localMetadata Local metadata from MediaSession (contains bitmap in memory)
     * @param savedLocalArtUrl Pre-saved local art file URL (already persisted to disk)
     */
    private suspend fun checkAndBackfillTrackArt(
        trackId: Long, 
        enrichedArtUrl: String?, 
        localMetadata: LocalMediaMetadata,
        savedLocalArtUrl: String? = null
    ) {
        try {
            val track = trackRepository.getById(trackId).first()
            if (track != null && track.albumArtUrl.isNullOrBlank()) {
                // If we have enriched art, use that first
                // Fix HTTP URLs to HTTPS for better reliability
                val fixedEnrichedUrl = me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService.fixHttpUrl(enrichedArtUrl)
                if (!fixedEnrichedUrl.isNullOrBlank()) {
                    Log.i(TAG, "Backfilling Track $trackId with enriched art: $fixedEnrichedUrl")
                    trackRepository.update(track.copy(albumArtUrl = fixedEnrichedUrl))
                } 
                // Otherwise try pre-saved local art first (already on disk)
                else if (savedLocalArtUrl != null) {
                    Log.i(TAG, "Backfilling Track $trackId with pre-saved local art: $savedLocalArtUrl")
                    trackRepository.update(track.copy(albumArtUrl = savedLocalArtUrl))
                }
                // Finally try to save from bitmap or URI
                else {
                    val localArtUrl = localMetadata.albumArtBitmap?.let { 
                        saveAlbumArtToStorage(it, localMetadata.title, localMetadata.artist) 
                    } ?: localMetadata.getBestAlbumArtSource()
                    
                    if (localArtUrl != null) {
                        Log.i(TAG, "Backfilling Track $trackId with local art: $localArtUrl")
                        trackRepository.update(track.copy(albumArtUrl = localArtUrl))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking/backfilling track art", e)
        }
    }
    
    // =====================
    // Album Art Extraction (Fallback for when MusicBrainz doesn't have cover art)
    // =====================
    
    /**
     * Extract album art bitmap from a notification.
     * Tries multiple sources: large icon, extras, etc.
     */
    private fun extractAlbumArtFromNotification(notification: Notification): Bitmap? {
        try {
            val extras = notification.extras
            
            // Try EXTRA_LARGE_ICON first (most common for media notifications)
            @Suppress("DEPRECATION")
            val largeIconBitmap = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            if (largeIconBitmap != null) {
                Log.d(TAG, "Extracted album art from EXTRA_LARGE_ICON")
                return largeIconBitmap
            }
            
            // Try getLargeIcon() for API 23+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notification.getLargeIcon()?.let { icon ->
                    try {
                        val drawable = icon.loadDrawable(applicationContext)
                        drawable?.let {
                            Log.d(TAG, "Extracted album art from getLargeIcon()")
                            return it.toBitmap()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load large icon drawable", e)
                    }
                }
            }
            
            // Try notification.largeIcon (deprecated but still works)
            @Suppress("DEPRECATION")
            notification.largeIcon?.let {
                Log.d(TAG, "Extracted album art from largeIcon")
                return it
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting album art from notification", e)
        }
        return null
    }
    
    /**
     * Extract album art bitmap from MediaSession metadata.
     */
    private fun extractAlbumArtFromMetadata(metadata: MediaMetadata): Bitmap? {
        try {
            // Try METADATA_KEY_ART first (generic art)
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let {
                Log.d(TAG, "Extracted album art from METADATA_KEY_ART")
                return it
            }
            
            // Try METADATA_KEY_ALBUM_ART
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let {
                Log.d(TAG, "Extracted album art from METADATA_KEY_ALBUM_ART")
                return it
            }
            
            // Try METADATA_KEY_DISPLAY_ICON
            metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let {
                Log.d(TAG, "Extracted album art from METADATA_KEY_DISPLAY_ICON")
                return it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting album art from metadata", e)
        }
        return null
    }
    
    /**
     * Save album art bitmap to local storage and return the file URI.
     * Uses a hash of title+artist as filename for deduplication.
     */
    private fun saveAlbumArtToStorage(bitmap: Bitmap, title: String, artist: String): String? {
        try {
            // Create album art directory
            val albumArtDir = File(applicationContext.filesDir, ALBUM_ART_DIR)
            if (!albumArtDir.exists()) {
                albumArtDir.mkdirs()
            }
            
            // Generate unique filename based on title + artist hash
            val hash = generateHash("$title|$artist")
            val fileName = "art_$hash.jpg"
            val file = File(albumArtDir, fileName)
            
            // If file already exists, just return the URI
            if (file.exists()) {
                Log.d(TAG, "Album art already saved: ${file.absolutePath}")
                return "file://${file.absolutePath}"
            }
            
            // Save bitmap to file
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            
            Log.i(TAG, "Saved album art to: ${file.absolutePath}")
            return "file://${file.absolutePath}"
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving album art", e)
            return null
        }
    }
    
    /**
     * Generate MD5 hash for filename.
     */
    private fun generateHash(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.lowercase().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
    
    /**
     * Update track with album art URL if it doesn't have one and enrichment didn't find one.
     * This is used as a fallback after enrichment completes.
     */
    private suspend fun updateTrackAlbumArtIfNeeded(trackId: Long, localArtUrl: String) {
        try {
            val tracks = trackRepository.all().first()
            val track = tracks.find { it.id == trackId } ?: return
            
            // Only update if track doesn't have album art URL
            if (track.albumArtUrl.isNullOrBlank()) {
                Log.i(TAG, "Updating track $trackId with local album art: $localArtUrl")
                val updatedTrack = track.copy(albumArtUrl = localArtUrl)
                trackRepository.update(updatedTrack)
            } else {
                Log.d(TAG, "Track $trackId already has album art, skipping local art update")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating track album art", e)
        }
    }

    private fun saveListeningEvent(session: PlaybackSession) {
        val playDuration = session.calculateCurrentPlayDuration()
        
        // Don't save very short plays (less than MIN_PLAY_DURATION_MS)
        if (playDuration < MIN_PLAY_DURATION_MS) {
            Log.d(TAG, "Skipping short play: ${playDuration}ms for '${session.title}'")
            return
        }

        val trackId = session.trackId
        if (trackId == null) {
            Log.w(TAG, "No trackId for session '${session.title}', deferring save for ${playDuration}ms play")
            // Queue for later when track ID is available - retry multiple times
            serviceScope.launch {
                var retries = 0
                val maxRetries = 5
                while (session.trackId == null && retries < maxRetries) {
                    delay(500) // Wait for track insertion
                    retries++
                }
                
                val resolvedTrackId = session.trackId
                if (resolvedTrackId != null) {
                    Log.d(TAG, "TrackId resolved after ${retries * 500}ms, saving event for '${session.title}'")
                    insertListeningEvent(resolvedTrackId, session)
                } else {
                    // Try to find the track by title/artist as last resort
                    try {
                        val track = getOrInsertTrack(session.title, session.artist, session.album)
                        Log.d(TAG, "Found/created track ${track.id} for deferred save of '${session.title}'")
                        insertListeningEvent(track.id, session)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save deferred listening event for '${session.title}'", e)
                    }
                }
            }
            return
        }

        serviceScope.launch {
            insertListeningEvent(trackId, session)
        }
    }

    private suspend fun insertListeningEvent(trackId: Long, session: PlaybackSession) {
        var playDuration = session.calculateCurrentPlayDuration()
        val currentTime = System.currentTimeMillis()
        
        // Final check for play duration
        if (playDuration < MIN_PLAY_DURATION_MS) {
            Log.d(TAG, "Skipping short play in insert: ${playDuration}ms for '${session.title}'")
            return
        }
        
        // CRITICAL FIX: Final validation to cap duration and prevent inflated values
        // This is the last line of defense before saving to database
        if (playDuration > MAX_PLAY_DURATION_MS) {
            Log.w(TAG, "ANOMALY: Duration ${playDuration}ms exceeds 1 hour max, capping to ${MAX_PLAY_DURATION_MS}ms for '${session.title}'")
            playDuration = MAX_PLAY_DURATION_MS
        }
        
        // Also cap at 3x estimated duration (if available) as sanity check
        session.estimatedDurationMs?.let { estimated ->
            if (estimated > 0) {
                val maxReasonable = estimated * 3
                if (playDuration > maxReasonable) {
                    Log.w(TAG, "ANOMALY: Duration ${playDuration}ms is >3x estimated ${estimated}ms, capping to ${maxReasonable}ms for '${session.title}'")
                    playDuration = maxReasonable
                }
            }
        }
        
        // Get duration from smart estimator if not available
        val estimatedDuration = session.estimatedDurationMs ?: run {
            val estimate = durationEstimator.estimateDuration(session.title, session.artist, session.album)
            estimate.durationMs
        }
        
        // Calculate completion percentage using smart estimator
        val completionPct = durationEstimator.calculateCompletionPercent(
            playDuration, 
            estimatedDuration, 
            isStillPlaying = false
        )
        
        // Determine if this was a skip
        val wasSkipped = session.wasSkipped() || completionPct < SKIP_COMPLETION_THRESHOLD
        
        // Check if this is a replay (same track played within 5 minutes)
        val isReplay = recentPlaysCache[trackId]?.let { lastPlayTime ->
            (currentTime - lastPlayTime) < REPLAY_THRESHOLD_MS
        } ?: false
        
        // Update recent plays cache
        recentPlaysCache[trackId] = currentTime
        
        // Clean old entries from cache (older than replay threshold)
        val oldThreshold = currentTime - REPLAY_THRESHOLD_MS
        recentPlaysCache.entries.removeAll { it.value < oldThreshold }
        
        // Record duration with smart estimator for future learning (only full plays are reliable)
        // Record duration with smart estimator for future learning (only full plays are reliable)
        val isFullPlay = completionPct >= FULL_PLAY_COMPLETION_THRESHOLD
        val currentEstimatedDuration = session.estimatedDurationMs
        if (isFullPlay && currentEstimatedDuration != null && currentEstimatedDuration > 0) {
            durationEstimator.recordObservedDuration(
                title = session.title,
                artist = session.artist,
                durationMs = currentEstimatedDuration,
                wasFullPlay = true,
                album = session.album
            )
        }
        
        // Legacy duration cache update for backward compatibility
        session.estimatedDurationMs?.let { duration ->
            if (duration > 0) {
                val key = generateHash("${session.title}|${session.artist}")
                durationEstimateCache[key] = duration
            }
        }

        val event = ListeningEvent(
            track_id = trackId,
            timestamp = session.startTimestamp,
            playDuration = playDuration,
            completionPercentage = completionPct,
            source = session.packageName,
            wasSkipped = wasSkipped,
            isReplay = isReplay,
            estimatedDurationMs = estimatedDuration,
            pauseCount = session.pauseCount,
            sessionId = session.sessionId,
            endTimestamp = currentTime,
            // NEW: Enhanced tracking fields
            totalPauseDurationMs = session.totalPauseDurationMs,
            seekCount = session.seekCount,
            positionUpdatesCount = session.positionUpdatesCount,
            wasInterrupted = session.wasInterrupted
        )
        
        // VALIDATION: Validate event before saving
        if (!session.validatePlayDuration()) {
            Log.w(TAG, "Validation failed for '${session.title}': invalid play duration, capping to max")
            // Still save but with capped duration
        }
        
        if (!session.validateCompletionPercentage()) {
            Log.w(TAG, "Validation failed for '${session.title}': invalid completion percentage")
        }
        
        // ANOMALY DETECTION: Log any anomalies for debugging (don't block save)
        val anomalies = session.detectAnomalies()
        if (anomalies.isNotEmpty()) {
            Log.i(TAG, "Anomalies detected for '${session.title}': ${anomalies.joinToString("; ")}")
        }


        try {
            // Use tracking manager for batched saving with retry logic
            val queued = trackingManager.queueEvent(event, session.sessionId)
            
            if (queued) {
                val eventDetails = buildString {
                    append("'${session.title}' played for ${playDuration / 1000}s")
                    append(" (${completionPct}%)")
                    if (wasSkipped) append(" [SKIPPED]")
                    if (isReplay) append(" [REPLAY]")
                    if (session.pauseCount > 0) append(" [${session.pauseCount} pauses]")
                }
                Log.i(TAG, "Queued listening event: $eventDetails")
                
                // Invalidate stats cache to trigger real-time updates
                (statsRepository as? RoomStatsRepository)?.onNewListeningEvent(event.timestamp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue listening event, falling back to direct insert", e)
            // Fallback to direct insert
            try {
                listeningRepository.insert(event)
                (statsRepository as? RoomStatsRepository)?.onNewListeningEvent(event.timestamp)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Direct insert also failed", fallbackError)
            }
        }
    }

    // =====================
    // MediaSessionManager Integration (Fallback)
    // =====================

    @Volatile
    private var isMediaSessionManagerInitialized = false

    private fun initializeMediaSessionManager() {
        if (isMediaSessionManagerInitialized) {
            Log.d(TAG, "MediaSessionManager already initialized, skipping")
            return
        }
        
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            
            val componentName = ComponentName(this, MusicTrackingService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                { controllers -> onActiveSessionsChanged(controllers) },
                componentName
            )
            
            isMediaSessionManagerInitialized = true
            
            // Process currently active sessions
            val activeSessions = mediaSessionManager?.getActiveSessions(componentName)
            onActiveSessionsChanged(activeSessions)
            
            Log.i(TAG, "MediaSessionManager initialized with ${activeSessions?.size ?: 0} active sessions")
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaSessionManager access denied - notification access may not be granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaSessionManager", e)
        }
    }

    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        val currentControllers = controllers ?: emptyList()
        
        // Debounce session change logs - avoid spam when count unchanged within 2 seconds
        val now = System.currentTimeMillis()
        val (lastCount, lastTime) = lastSessionChangeLog ?: (-1 to 0L)
        if (currentControllers.size != lastCount || (now - lastTime) > SESSION_CHANGE_LOG_DEBOUNCE_MS) {
            Log.d(TAG, "Active sessions changed: ${currentControllers.size} sessions")
            lastSessionChangeLog = currentControllers.size to now
        }
        
        // 1. Identify removed packages
        val newPackageNames = currentControllers.map { it.packageName }.toSet()
        val removedPackages = activeControllers.keys - newPackageNames
        
        removedPackages.forEach { packageName ->
            // Unregister callback
            activeControllers[packageName]?.unregisterCallback(packageSpecificCallbacks[packageName] ?: sharedCallback)
            activeControllers.remove(packageName)
            packageSpecificCallbacks.remove(packageName)
            
            // Handle session end
            val session = playbackStates.remove(packageName)
            if (session != null) {
                Log.d(TAG, "Session ended for '$packageName' (controller removed)")
                session.pause()
                saveListeningEvent(session)
            }
        }
        
        // 2. Identify and process new controllers
        currentControllers.forEach { controller ->
            val packageName = controller.packageName
            
            // Skip if already tracked
            if (!activeControllers.containsKey(packageName)) {
                if (packageName in MUSIC_APPS || isMusicSession(controller)) {
                    // Register specific callback
                    val callback = PackageSpecificCallback(packageName)
                    controller.registerCallback(callback)
                    
                    activeControllers[packageName] = controller
                    packageSpecificCallbacks[packageName] = callback
                    
                    // Process initial state
                    processMediaControllerState(controller)
                }
            }
        }
        
        // Update notification and lifecycle
        if (playbackStates.isEmpty()) {
            updateTrackingNotification(null, null)
        }
        
        updateServiceLifecycle()
    }

    // Storage for package-specific callbacks to prevent leaks
    private val packageSpecificCallbacks = mutableMapOf<String, MediaController.Callback>()
    // Fallback shared callback for legacy cleanup
    private val sharedCallback = PackageSpecificCallback("shared_fallback")

    /**
     * Determine if a MediaController session represents MUSIC (not video).
     * 
     * Uses multiple heuristics:
     * 1. Package name check (blocked apps, music apps)
     * 2. Duration check (music is typically 30s - 20min)
     * 3. Metadata characteristics
     */
    private fun isMusicSession(controller: MediaController): Boolean {
        val packageName = controller.packageName
        
        // FIRST: Block known video apps
        if (packageName in BLOCKED_APPS) {
            Log.d(TAG, "Blocking MediaSession from video app: $packageName")
            return false
        }
        
        // Known music apps are always music
        if (packageName in MUSIC_APPS) {
            return true
        }
        
        // For unknown apps, use heuristics to detect music vs video
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        
        // Must have title at minimum
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title == null) return false
        
        // Check duration - music typically 30s to 20 minutes
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
        if (duration > 0) {
            // Very short (< 30s) = probably notification sound, ad, etc.
            if (duration < MIN_MUSIC_DURATION_MS) {
                Log.d(TAG, "Rejecting short content ($duration ms): '$title' from $packageName")
                return false
            }
            // Very long (> 20 min) = probably video, podcast, audiobook
            if (duration > MAX_MUSIC_DURATION_MS) {
                Log.d(TAG, "Rejecting long content ($duration ms, ${duration/60000} min): '$title' from $packageName")
                return false
            }
        }
        
        // Check if metadata looks like music (has artist/album)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        
        // Having artist OR album is a strong indicator of music
        val hasMusicMetadata = !artist.isNullOrBlank() || !album.isNullOrBlank()
        
        // Package name heuristics for unknown apps
        val lowerPkg = packageName.lowercase()
        val looksLikeVideoApp = lowerPkg.contains("video") || lowerPkg.contains("movie") ||
                                lowerPkg.contains("tv") || lowerPkg.contains("stream") ||
                                lowerPkg.contains("player") && !lowerPkg.contains("music")
        
        if (looksLikeVideoApp && !hasMusicMetadata) {
            Log.d(TAG, "Rejecting likely video app without music metadata: $packageName")
            return false
        }
        
        // Accept if playing and has music-like metadata
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        return isPlaying || hasMusicMetadata
    }

    private fun processMediaControllerState(controller: MediaController) {
        val packageName = controller.packageName
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        
        val state = playbackState?.state
        
        // Handle stopped/ended states - save the session and remove it
        if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE) {
            val session = playbackStates.remove(packageName)
            if (session != null) {
                Log.d(TAG, "Playback stopped/ended for '$packageName', saving listening event for '${session.title}'")
                session.pause()
                saveListeningEvent(session)
                
                // Update notification if no active sessions
                if (playbackStates.isEmpty()) {
                    updateTrackingNotification(null, null)
                }
            }
            return
        }
        
        // Need metadata to process further
        if (metadata == null) return

        // Use robust metadata extraction
        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) 
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        // Skip metadata with empty titles - metadata hasn't fully arrived yet
        if (rawTitle.isNullOrBlank()) {
            Log.d(TAG, "Ignoring MediaSession update with empty title from $packageName")
            return
        }
        val artist = extractArtistFromMetadata(metadata)
        
        // Clean title if artist was extracted from it
        val title = if (artist != "Unknown Artist" && extractArtistFromTitle(rawTitle) == artist) {
            cleanTitleIfNeeded(rawTitle)
        } else {
            rawTitle
        }
        
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        
        // Get current playback position for replay detection
        val currentPosition = playbackState?.position ?: 0L

        val isPlaying = state == PlaybackState.STATE_PLAYING

        // CRITICAL: Use synchronized block to prevent race condition when
        // notification and MediaSession callbacks fire simultaneously for the same track.
        val (sessionAction, session, isSameTrack) = synchronized(sessionOperationLock) {
            val existing = playbackStates[packageName]
            
            // Check if this is the same track (handle artist updates from Unknown -> Real)
            val isSameTrackInfo = existing != null && existing.title == title && (
                existing.artist == artist || 
                // If existing session has unknown artist but new one has real artist, it's the same track
                (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(existing.artist) && 
                 !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist)) ||
                // If new metadata has unknown artist but session has real artist, keep session's artist
                (!me.avinas.tempo.utils.ArtistParser.isUnknownArtist(existing.artist) && 
                 me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist))
            )
            
            // Detect replay: same track info but position reset to near beginning
            val isReplayDetected = isSameTrackInfo && isPlaying && run {
                val lastPosition = existing!!.lastKnownPosition
                val playedMs = existing.calculateCurrentPlayDuration()
                
                val positionResetThresholdMs = 5_000L
                val minPlayedForReplayMs = 30_000L
                
                val positionWentBack = lastPosition > positionResetThresholdMs && 
                    currentPosition < positionResetThresholdMs
                
                val estimatedDuration = existing.estimatedDurationMs ?: duration.takeIf { it > 0 }
                
                val minCompletionForReplay = 0.5f
                val completionPercent = if (estimatedDuration != null && estimatedDuration > 0) {
                    playedMs.toFloat() / estimatedDuration
                } else {
                    0f
                }
                val hasMinCompletion = completionPercent >= minCompletionForReplay
                
                val playedExceedsDuration = estimatedDuration != null && 
                    estimatedDuration > 0 && 
                    playedMs > estimatedDuration + 10_000L
                
                val isReplay = (positionWentBack && playedMs >= minPlayedForReplayMs && hasMinCompletion) || 
                               playedExceedsDuration
                
                if (isReplay) {
                    Log.d(TAG, "Replay detected for '${existing.title}': " +
                        "position reset from ${lastPosition}ms to ${currentPosition}ms, " +
                        "played ${playedMs}ms (${(completionPercent * 100).toInt()}%), estimated duration ${estimatedDuration}ms")
                }
                
                isReplay
            }
            
            // Update last known position for replay detection
            existing?.let { it.lastKnownPosition = currentPosition }
            
            val isSameTrackResult = isSameTrackInfo && !isReplayDetected
            
            when {
                isSameTrackResult -> Triple("UPDATE", existing, true)
                isPlaying -> Triple("NEW", existing, false)
                else -> Triple("IGNORE", existing, false)
            }
        }

        when (sessionAction) {
            "UPDATE" -> {
                // Same track - update play state and potentially update artist
                val currentSession = session!!
            
            // Extract local metadata - music players often send metadata in stages
            // Genre, year, etc. might come in later updates
            val localMetadata = LocalMediaMetadata.fromMediaMetadata(metadata, packageName)
            if (localMetadata != null && currentSession.trackId != null) {
                val trackId = currentSession.trackId!!
                // Update cache and check if we should apply local metadata fallback
                serviceScope.launch {
                    try {
                        // Update cache with latest metadata
                        val cached = localMetadataCache[trackId]
                        val hasNewData = cached == null || 
                            (localMetadata.genre != null && cached.genre == null) ||
                            (localMetadata.year != null && cached.year == null) ||
                            (localMetadata.album != null && cached.album == null) ||
                            (localMetadata.durationMs != null && cached.durationMs == null)
                        
                        if (hasNewData) {
                            // Always update the cache with latest data
                            localMetadataCache[trackId] = if (cached != null) {
                                cached.copy(
                                    genre = localMetadata.genre ?: cached.genre,
                                    album = localMetadata.album ?: cached.album,
                                    year = localMetadata.year ?: cached.year,
                                    durationMs = localMetadata.durationMs ?: cached.durationMs,
                                    albumArtBitmap = localMetadata.albumArtBitmap ?: cached.albumArtBitmap,
                                    albumArtUri = localMetadata.albumArtUri ?: cached.albumArtUri
                                )
                            } else {
                                localMetadata
                            }
                            Log.d(TAG, "Updated local metadata cache for track $trackId (genre: ${localMetadata.genre}, album: ${localMetadata.album})")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error updating local metadata cache", e)
                    }
                }
                
                // Schedule a delayed metadata retry after 1 minute if not already scheduled
                // This catches metadata that streaming apps send late (after initial batch)
                if (!currentSession.delayedMetadataRetryScheduled) {
                    currentSession.delayedMetadataRetryScheduled = true
                    serviceScope.launch {
                        delay(60_000) // Wait 1 minute
                        // Only proceed if this track is still playing (same session)
                        val stillSameSession = playbackStates[packageName]?.let {
                            it.trackId == trackId && it.sessionId == currentSession.sessionId
                        } ?: false
                        
                        if (stillSameSession) {
                            Log.d(TAG, "Performing delayed metadata retry for track $trackId after 1 minute")
                            val latestCachedMetadata = localMetadataCache[trackId]
                            if (latestCachedMetadata != null) {
                                saveLocalMetadataFallback(trackId, latestCachedMetadata)
                            }
                        }
                    }
                }
            }
            
            // Update artist if we now have a better one
            if (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(currentSession.artist) && 
                !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist)) {
                Log.d(TAG, "Updating session artist from '${currentSession.artist}' to '$artist' for '$title'")
                currentSession.artist = artist
                
                // Update the track in database if we have a trackId
                currentSession.trackId?.let { trackId ->
                    serviceScope.launch {
                        try {
                            val tracks = trackRepository.all().first()
                            val track = tracks.find { it.id == trackId }
                            if (track != null && me.avinas.tempo.utils.ArtistParser.isUnknownArtist(track.artist)) {
                                Log.i(TAG, "Updating track $trackId artist from '${track.artist}' to '$artist'")
                                val updatedTrack = track.copy(artist = artist)
                                trackRepository.update(updatedTrack)
                                
                                // Re-link artists and trigger enrichment
                                artistLinkingService.linkArtistsForTrack(updatedTrack)
                                EnrichmentWorker.enqueueImmediate(applicationContext, trackId)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update track artist", e)
                        }
                    }
                }
                
                updateTrackingNotification(title, artist)
            }
            
            // KEY: Update position tracking with actual MediaSession position
            // This is the core of position-based tracking
            currentSession.updatePosition(currentPosition, isPlaying)
            
            if (isPlaying && !currentSession.isPlaying) {
                currentSession.resume()
                Log.d(TAG, "Resumed: '$title' at position ${currentPosition}ms")
            } else if (!isPlaying && currentSession.isPlaying) {
                currentSession.pause()
                Log.d(TAG, "Paused: '$title' at position ${currentPosition}ms, accumulated: ${currentSession.accumulatedPositionMs}ms")
            }
            if (duration > 0) {
                currentSession.estimatedDurationMs = duration
            }
            }
            "NEW" -> {
                // New track started (or replay detected - same track restarted)
                session?.let { 
                    it.pause()
                    saveListeningEvent(it) 
                }
                
                // Extract ALL available metadata from MediaSession (for fallback use)
                val localMetadata = LocalMediaMetadata.fromMediaMetadata(metadata, packageName)
                
                // Check if this is likely an advertisement - skip tracking ads
                if (artist.isNotBlank() && 
                    !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist) &&
                    localMetadata?.isLikelyAdvertisement() == true) {
                    if (!loggedAdvertisementSkips.containsKey(title)) {
                        loggedAdvertisementSkips[title] = true
                        Log.d(TAG, "Skipping likely advertisement: '$title' from $packageName")
                    }
                    return
                }
                
                // Extract album art from metadata (for fallback use)
                val albumArtBitmap = localMetadata?.albumArtBitmap ?: extractAlbumArtFromMetadata(metadata)
                val localArtUrl = albumArtBitmap?.let { saveAlbumArtToStorage(it, title, artist) }

                // Clear log debounce caches for fresh logging on new track
                loggedArtistExtractionFailures.clear()
                loggedAdvertisementSkips.clear()

                val newSession = PlaybackSession(
                    packageName = packageName,
                    title = title,
                    artist = artist,
                    album = album,
                    isPlaying = true,
                    estimatedDurationMs = if (duration > 0) duration else null,
                    playbackStartPosition = currentPosition,
                    lastKnownPosition = currentPosition,
                    lastRecordedPosition = currentPosition,
                    accumulatedPositionMs = 0,
                    isLikelyMusic = true
                )

                // Add to playback states immediately so we start tracking time
                playbackStates[packageName] = newSession
                updateTrackingNotification(title, artist)

                // Insert track asynchronously
                serviceScope.launch {
                    try {
                        // CONTENT FILTERING: Check if this should be filtered (podcasts/audiobooks)
                        // Must be done in coroutine since shouldFilterContent is suspend
                        // Note: We pass title/artist directly so manual marks work even without full metadata
                        if (shouldFilterContent(packageName, localMetadata, title, artist)) {
                            val contentType = localMetadata?.getContentType()?.name ?: "MANUAL_MARK"
                            Log.d(TAG, "Content filtered ($contentType): '$title' by '$artist' from $packageName")
                            // Remove the session we just added
                            playbackStates.remove(packageName)
                            updateTrackingNotification(null, null)
                            return@launch
                        }
                        
                        val track = getOrInsertTrack(title, artist, album, localMetadata)
                        newSession.trackId = track.id
                        Log.d(TAG, "Track ID ${track.id} assigned for '${title}' (via MediaSession)")
                        
                        // Ensure locally extracted art URI is available to the fallback logic
                        if (localArtUrl != null) {
                            val currentCache = localMetadataCache[track.id] ?: localMetadata
                            if (currentCache != null) {
                                localMetadataCache[track.id] = currentCache.copy(albumArtUri = localArtUrl)
                                Log.d(TAG, "Updated local metadata cache with art URI: $localArtUrl")
                            } else if (localMetadata == null) {
                                localMetadataCache[track.id] = LocalMediaMetadata(
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    albumArtUri = localArtUrl
                                )
                            }
                        }
                        
                        // Save local metadata as fallback (delayed to let external enrichment run first)
                        if (localMetadata != null && localMetadata.hasRichMetadata()) {
                            delay(30_000)
                            val latestMetadata = localMetadataCache[track.id] ?: localMetadata
                            saveLocalMetadataFallback(track.id, latestMetadata)
                        } else if (localArtUrl != null) {
                            delay(20_000)
                            updateTrackAlbumArtIfNeeded(track.id, localArtUrl)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error inserting track", e)
                    }
                }
            }
            "IGNORE" -> {
                // Not playing and not same track - nothing to do
            }
        }
    }

    inner class PackageSpecificCallback(private val packageName: String) : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // fast lookup - we know exactly which controller triggered this
            activeControllers[packageName]?.let { controller ->
                processMediaControllerState(controller)
                updateServiceLifecycle()
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            activeControllers[packageName]?.let { controller ->
                processMediaControllerState(controller)
                updateServiceLifecycle()
            }
        }
        
        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            Log.d(TAG, "Session destroyed for $packageName")
            // Handle destruction same as removal
            activeControllers.remove(packageName)
            packageSpecificCallbacks.remove(packageName)
            
            val session = playbackStates.remove(packageName)
            if (session != null) {
                session.pause()
                saveListeningEvent(session)
                updateTrackingNotification(null, null) // Check remaining
            }
            updateServiceLifecycle()
        }
    }



    // =====================
    // Foreground Service & Notifications
    // =====================

    private fun createNotificationChannel() {
        // Main channel for when actively tracking music
        val trackingChannel = NotificationChannel(
            CHANNEL_ID,
            "Music Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Tempo is actively tracking your music"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        
        // Silent channel for background monitoring (minimal visibility)
        val silentChannel = NotificationChannel(
            CHANNEL_ID + "_silent",
            "Background Monitoring",
            NotificationManager.IMPORTANCE_MIN  // Minimal importance - won't show on status bar
        ).apply {
            description = "Silent notification when waiting for music"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(trackingChannel)
        manager.createNotificationChannel(silentChannel)
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildTrackingNotification(null, null)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildTrackingNotification(currentTrack: String?, currentArtist: String?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Count music sessions being actively tracked
        val activeMusicSessions = playbackStates.count { it.value.isLikelyMusic && it.value.isPlaying }
        val isActivelyTracking = currentTrack != null || activeMusicSessions > 0
        
        // Use silent channel when not actively tracking music
        val channelId = if (isActivelyTracking) CHANNEL_ID else CHANNEL_ID + "_silent"
        
        return if (isActivelyTracking) {
            // ACTIVE TRACKING: Show meaningful notification
            val contentText = if (currentTrack != null && currentArtist != null) {
                val session = playbackStates.values.find { it.title == currentTrack && it.artist == currentArtist }
                val timeInfo = session?.let { 
                    val secs = it.accumulatedPositionMs / 1000
                    if (secs > 0) " (${secs}s)" else ""
                } ?: ""
                "🎵 $currentTrack - $currentArtist$timeInfo"
            } else {
                "🎵 Tracking $activeMusicSessions music session(s)"
            }
            
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("Tempo is tracking music")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // Show on lock screen when tracking
                .build()
        } else {
            // NOT TRACKING: Minimal silent notification (required by Android for foreground service)
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("Tempo")
                .setContentText("Waiting for music...")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)  // Minimum priority
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // Hide from lock screen
                .setSilent(true)  // No sound/vibration
                .build()
        }
    }

    private fun updateTrackingNotification(currentTrack: String?, currentArtist: String?) {
        val now = System.currentTimeMillis()
        
        // Create notification content identifier for comparison
        val contentId = "$currentTrack|$currentArtist|${hasActivePlayback()}"
        
        // Debounce: skip update if content hasn't changed and updated within last 2 seconds
        // Increased from 1s to 2s to reduce notification spam during rapid MediaSession callbacks
        if (contentId == lastNotificationContent && (now - lastNotificationUpdate) < 2000) {
            // Silent skip - no logging needed for normal debounce behavior
            return
        }
        
        // Update notification
        val notification = buildTrackingNotification(currentTrack, currentArtist)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
        
        // Track last update
        lastNotificationUpdate = now
        lastNotificationContent = contentId
        
        Log.v(TAG, "Notification updated: ${if (hasActivePlayback()) "tracking" else "idle"}")
    }

    // =====================
    // Lifecycle
    // =====================

    override fun onDestroy() {
        Log.i(TAG, "MusicTrackingService destroyed - saving active sessions")
        
        // Mark clean shutdown BEFORE any async operations
        // This prevents false positive "unclean shutdown" detection on next start
        sessionPersistence.markServiceInactive()
        
        // Cancel auto-save job and position polling
        autoSaveJob?.cancel()
        positionPollingJob?.cancel()
        
        // Reset connection state
        synchronized(connectionLock) {
            isListenerConnected = false
        }
        isMediaSessionManagerInitialized = false
        
        // Save all active sessions before shutdown
        playbackStates.values.forEach { session ->
            session.pause()
            saveListeningEvent(session)
        }
        playbackStates.clear()

        // Cleanup MediaSession callbacks
        // Cleanup MediaSession callbacks
        activeControllers.forEach { (packageName, controller) ->
            val callback = packageSpecificCallbacks[packageName] ?: sharedCallback
            controller.unregisterCallback(callback)
        }
        activeControllers.clear()
        packageSpecificCallbacks.clear()
        
        // Flush tracking manager (async, but we've already marked shutdown)
        serviceScope.launch {
            try {
                trackingManager.flushAll()
                sessionPersistence.clearSessions()
                Log.i(TAG, "Tracking manager flushed, metrics: ${trackingManager.getMetrics()}")
            } catch (e: Exception) {
                Log.e(TAG, "Error during shutdown cleanup", e)
            }
        }
        
        // Log service uptime
        val uptime = System.currentTimeMillis() - serviceStartTime.get()
        Log.i(TAG, "Service uptime: ${uptime / 1000}s, Duration estimator stats: ${durationEstimator.getStats()}")

        serviceScope.cancel()
        super.onDestroy()
    }
    
    /**
     * Get service health information for debugging.
     */
    fun getServiceHealth(): ServiceHealth {
        return ServiceHealth(
            isListenerConnected = isListenerConnected,
            activeSessionCount = playbackStates.size,
            trackingMetrics = trackingManager.getMetrics(),
            durationEstimatorStats = durationEstimator.getStats(),
            uptimeMs = System.currentTimeMillis() - serviceStartTime.get()
        )
    }
}

/**
 * Service health information for debugging and monitoring.
 */
data class ServiceHealth(
    val isListenerConnected: Boolean,
    val activeSessionCount: Int,
    val trackingMetrics: TrackingMetrics,
    val durationEstimatorStats: EstimatorStats,
    val uptimeMs: Long
)

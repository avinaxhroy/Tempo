package me.avinas.tempo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.data.local.dao.ManualContentMarkDao
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.ManualContentMark
import me.avinas.tempo.data.local.entities.ManualContentRuleResolver
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.preferences.TrackingRulesPreferences
import me.avinas.tempo.data.preferences.TrackingRulesPreferences.ContentOverrideType
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.RefreshCoordinator
import me.avinas.tempo.data.repository.RoomStatsRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackAliasRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.receiver.BatteryStateReceiver
import me.avinas.tempo.utils.BatteryUtils
import me.avinas.tempo.utils.TrackCandidate
import me.avinas.tempo.utils.TrackMatcher
import me.avinas.tempo.worker.EnrichmentWorker
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * MusicTrackingService captures listening events from music apps via:
 * 1. NotificationListenerService - detects music notifications (primary)
 * 2. MediaSessionManager - tracks playback state changes (fallback/supplement)
 *
 * Handles play, pause, skip, and resume to calculate accurate listening duration.
 *
 * Note: Uses manual Hilt injection via EntryPoint because NotificationListenerService
 * is managed by the system lifecycle.
 */
class MusicTrackingService : NotificationListenerService() {
    /** Entry point for dependency injection outside Fragment/Activity lifecycle. */
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

        fun appPreferenceDao(): me.avinas.tempo.data.local.dao.AppPreferenceDao

        fun refreshCoordinator(): RefreshCoordinator
    }

    companion object {
        private const val TAG = "MusicTrackingService"
        private const val CHANNEL_ID = "tempo_tracking_channel"
        private const val NOTIFICATION_ID = 1001

        /** Sent by [BatteryStateReceiver] to trigger a re-scan of active media sessions. */
        const val ACTION_BATTERY_RECOVERED = "me.avinas.tempo.ACTION_BATTERY_RECOVERED"

        /**
         * Self-heal the listener component on any process start.
         *
         * [me.avinas.tempo.worker.ServiceHealthWorker] restarts a dead listener by
         * toggling this component DISABLED → ENABLED. If the process dies in that
         * window (or the re-enable throws), the component is left DISABLED and
         * tracking stays dead until the user reinstalls — the exact failure users
         * report as "it stopped tracking and never came back".
         *
         * This runs on every app process start: if the component is stuck disabled,
         * re-enable it and ask the system to rebind the listener. Cheap (two
         * PackageManager calls), no battery impact, and it makes the kill window
         * self-recovering instead of permanent.
         */
        fun ensureComponentEnabled(context: Context) {
            try {
                val componentName = ComponentName(context, MusicTrackingService::class.java)
                val state = context.packageManager.getComponentEnabledSetting(componentName)
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    Log.w(TAG, "Listener component found DISABLED — re-enabling (self-heal)")
                    context.packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                    try {
                        requestRebind(componentName)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestRebind after self-heal failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureComponentEnabled failed", e)
            }
        }

        // Music app package names to monitor (ONLY music apps, no video)
        private val MUSIC_APPS =
            setOf(
                "com.google.android.apps.youtube.music", // YouTube Music
                "com.spotify.music", // Spotify
                "com.apple.android.music", // Apple Music
                "com.amazon.mp3", // Amazon Music
                "com.soundcloud.android", // SoundCloud
                "deezer.android.app", // Deezer
                "com.pandora.android", // Pandora
                "com.jio.media.jiobeats", // JioSaavn
                "com.gaana", // Gaana
                "com.bsbportal.music", // Wynk
                "com.hungama.myplay.activity", // Hungama
                "com.samsung.android.app.music", // Samsung Music
                "com.miui.player", // Mi Music
                "com.sec.android.app.music", // Samsung Music (old)
                "in.startv.hotstar.music", // Hotstar Music
                "com.tidal.android", // Tidal (Old/Alt)
                "com.aspiro.tidal", // Tidal (Official)
                "com.qobuz.music", // Qobuz
                "app.revanced.android.youtube.music", // YouTube Music ReVanced
                "app.revanced.android.apps.youtube.music", // YouTube Music ReVanced (Correct App ID)
                "com.vanced.android.youtube.music", // YouTube Music Vanced
                "com.moonvideo.android.resso", // Resso
                "com.audiomack", // Audiomack
                "com.mmm.trebelmusic", // Trebel
                "com.maxmpz.audioplayer", // Poweramp
                "in.krosbits.musicolet", // Musicolet
                "com.kodarkooperativet.blackplayerfree", // BlackPlayer Free
                "com.kodarkooperativet.blackplayerex", // BlackPlayer EX
                "nugs.net", // Nugs.net
                "net.nugs.multiband", // Nugs.net (Multiband)
                // Open Source / GitHub Music Clients (Ad-free)
                "com.dd3boh.outertune", // OuterTune
                "com.zionhuang.music", // InnerTune
                "it.vfsfitvnm.vimusic", // ViMusic
                "oss.krtirtho.spotube", // Spotube
                "com.shadow.blackhole", // BlackHole
                "com.anandnet.harmonymusic", // Harmony Music
                "it.fast4x.rimusic", // RiMusic
                "com.msob7y.namida", // Namida
                "com.metrolist.music", // Metrolist
                "com.gokadzev.musify", // Musify
                "com.gokadzev.musify.fdroid", // Musify (F-Droid)
                "ls.bloomee.musicplayer", // BloomeeTunes
                "com.maxrave.simpmusic", // SimpMusic
                "it.ncaferra.pixelplayerfree", // Pixel Player
                "com.theveloper.pixelplay", // PixelPlay
                "com.singularity.gramophone", // Gramophone
                "player.phonograph.plus", // Phonograph Plus
                "org.oxycblt.auxio", // Auxio
                "com.maloy.muzza", // Muzza
                "uk.co.projectneon.echo", // Echo
                "com.shabinder.spotiflyer", // SpotiFlyer
                "com.kapp.youtube.final", // YMusic
                "org.schabi.newpipe", // NewPipe
                "org.polymorphicshade.newpipe", // NewPipe (Fork)
                // Popular Offline Players
                "com.rhmsoft.pulsar", // Pulsar
                "com.neutroncode.mp", // Neutron Player
                "gonemad.gmmp", // GoneMad
                "code.name.monkey.retromusic", // Retro Music Player
                "com.piyush.music", // Oto Music
                "com.simplecity.amp_pro", // Shuttle+
                "ru.stellio.player", // Stellio
                "io.stellio.music", // Stellio (Alt)
                "com.frolo.musp", // Frolomuse
                "com.rhmsoft.omnia", // Omnia
                "ru.yandex.music", // Yandex Music
                // Open Source - Vivi Music & similar
                "com.vivi.vivimusic", // Vivi Music (open source)
            )

        // Podcast apps - filtered by content filtering settings
        // Note: Spotify is NOT in this list because it has music, podcasts, AND audiobooks
        // We rely on metadata detection for Spotify
        private val PODCAST_APPS =
            setOf(
                "com.google.android.apps.podcasts", // Google Podcasts
                "com.google.android.apps.magazines", // Google News (plays news briefings via "Hey Google, Play the News")
                "fm.player", // Player FM
                "au.com.shiftyjelly.pocketcasts", // Pocket Casts
                "com.bambuna.podcastaddict", // Podcast Addict
                "com.clearchannel.iheartradio.controller", // iHeartRadio
                "app.tunein.player", // TuneIn Radio
                "com.stitcher.app", // Stitcher
                "com.castbox.player", // Castbox
                "com.overcast.app", // Overcast
                "com.apple.android.podcasts", // Apple Podcasts
                "com.podcastone.mobile", // PodcastOne
                "com.wondery.wondery", // Wondery
                "com.podcasts.android", // Podcasts (generic)
                "fm.castbox.audiobook.radio.podcast", // Castbox variant
            )

        // Audiobook apps - filtered by content filtering settings
        private val AUDIOBOOK_APPS =
            setOf(
                "com.audible.application", // Audible
                "com.google.android.apps.books", // Google Play Books
                "com.audiobooks.android.audiobooks", // Audiobooks.com
                "com.scribd.app.reader0", // Scribd
                "com.storytel", // Storytel
                "fm.libro", // Libro.fm
                "com.libro.app", // Libro.fm (alt)
                "com.kobo.books.ereader", // Kobo Books
                "com.nook.app", // Nook Audiobooks
                "com.audiobooks.androidapp", // Audiobooks (generic)
            )

        // Video/non-music apps to explicitly BLOCK from tracking
        // These apps have MediaSessions but we don't want to track their content
        private val BLOCKED_APPS =
            setOf(
                // Video streaming
                "com.google.android.youtube", // YouTube (video)
                "com.google.android.apps.youtube", // YouTube (alternative)
                "app.revanced.android.youtube", // YouTube ReVanced (Video)
                "com.netflix.mediaclient", // Netflix
                "com.amazon.avod.thirdpartyclient", // Prime Video
                "com.disney.disneyplus", // Disney+
                "in.startv.hotstar", // Hotstar (video)
                "com.hotstar.android", // Hotstar (alternative)
                "tv.twitch.android.app", // Twitch
                // Social media (reels, shorts, videos)
                "com.zhiliaoapp.musically", // TikTok
                "com.ss.android.ugc.trill", // TikTok (alternative)
                "com.instagram.android", // Instagram (reels)
                "com.facebook.katana", // Facebook (videos)
                "com.snapchat.android", // Snapchat
                // Video players & media
                "com.vimeo.android.videoapp", // Vimeo
                "com.mxtech.videoplayer.ad", // MX Player
                "com.mxtech.videoplayer.pro", // MX Player Pro
                "org.videolan.vlc", // VLC
                "com.google.android.apps.photos", // Google Photos (videos)
                "com.whatsapp", // WhatsApp (status videos)
                "org.telegram.messenger", // Telegram (videos)
                "com.google.android.gm", // Gmail (video attachments)
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
                "com.android.chrome", // Chrome
                "com.chrome.beta", // Chrome Beta
                "com.chrome.dev", // Chrome Dev
                "com.chrome.canary", // Chrome Canary
                "org.mozilla.firefox", // Firefox
                "org.mozilla.firefox_beta", // Firefox Beta
                "com.opera.browser", // Opera
                "com.opera.browser.beta", // Opera Beta
                "com.UCMobile.intl", // UC Browser
                "com.brave.browser", // Brave
                "com.microsoft.edge", // Microsoft Edge
                "org.torproject.torbrowser", // Tor Browser
                "org.dolphin.browser", // Dolphin
                "com.sec.android.app.sbrowser", // Samsung Browser
                "com.ksmobile.cb", // CM Browser
                "com.qwant.browser", // Qwant
                "org.chromium.webview_shell", // Chrome WebView shell
                // Remote control / mirroring (mirrors MediaSessions, must never be tracked)
                "org.kde.kdeconnect_tp", // KDE Connect
            )

        // Typical music duration range (used for music detection)
        private const val MIN_MUSIC_DURATION_MS = 30_000L // 30 seconds
        private const val TYPICAL_SONG_MIN_MS = 60_000L // 1 minute
        private const val TYPICAL_SONG_MAX_MS = 10 * 60 * 1000L // 10 minutes

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

        // Polling intervals adjusted by song duration and playback progress
        private const val POLL_INTERVAL_SONG_START_MS = 3_000L // First 30s: poll every 3s (skip detection)
        private const val POLL_INTERVAL_SONG_MIDDLE_MS = 8_000L // Middle: poll every 8s (battery efficient)
        private const val POLL_INTERVAL_SONG_END_MS = 4_000L // Last 30s: poll every 4s (completion detection)
        private const val POLL_INTERVAL_SHORT_TRACK_MS = 5_000L // Tracks <90s: always poll every 5s
        private const val POLL_INTERVAL_UNKNOWN_DURATION_MS = 6_000L // Unknown duration: balanced polling

        // Song phase thresholds
        private const val SONG_START_PHASE_MS = 30_000L // First 30 seconds
        private const val SONG_END_PHASE_MS = 30_000L // Last 30 seconds
        private const val SHORT_TRACK_THRESHOLD_MS = 90_000L // Tracks under 90 seconds

        // Skip threshold: plays under 30% completion are considered skips
        private const val SKIP_COMPLETION_THRESHOLD = 30

        // Full play threshold: plays over 80% completion are considered full plays
        private const val FULL_PLAY_COMPLETION_THRESHOLD = 80

        // Session auto-save interval
        private const val SESSION_AUTOSAVE_INTERVAL_MS = 30_000L

        // Maximum time to block in onDestroy while flushing pending listening events.
        // A normal Room batch write is well under 100ms, so 5s is generous headroom.
        // Bounded so we never hold the main thread long enough to risk an ANR, but
        // long enough to land the final batch of events in the database before the
        // process goes away. If it does time out, the sessions were already persisted
        // to SharedPreferences at the top of the flush, so recovery picks them up.
        private const val SHUTDOWN_FLUSH_TIMEOUT_MS = 5_000L

        // Track matching threshold for deduplication
        private const val TRACK_MATCH_THRESHOLD = 0.85

        // Position tracking constants
        private const val MAX_POSITION_JUMP_MS = 30_000L // 30 seconds - max seek to still count

        // Maximum reasonable position delta in one update (5 minutes worth of playback)
        // This catches cases where polling was delayed for a long time (device sleep, service suspension)
        private const val MAX_REASONABLE_DELTA_MS = 5 * 60 * 1000L // 5 minutes

        // Patterns for extracting artist from title (e.g., "Song - Artist", "Song (by Artist)")
        private val TITLE_ARTIST_PATTERNS =
            listOf(
                Regex("""^(.+?)\s*[-–—]\s*(.+)$"""), // "Title - Artist" or "Title – Artist"
                Regex("""^(.+?)\s*\|\s*(.+)$"""), // "Title | Artist"
                Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE), // "Title by Artist"
            )

        // Placeholder and album detection patterns - pre-compiled for performance
        private val NUMERIC_ONLY_PATTERN = Regex("^\\d+$")
        private val YEAR_PATTERN = Regex(".*\\(\\d{4}\\).*") // Contains year like "(2023)"
        private val REMASTER_PATTERN = Regex(".*\\d{4}.*remaster.*") // Remaster with year
    }

    // Metadata extraction helpers

    /**
     * Extract artist from MediaMetadata using multiple fallback sources.
     * Tries: ARTIST -> ALBUM_ARTIST -> AUTHOR -> DISPLAY_SUBTITLE -> WRITER -> COMPOSER
     */
    private fun extractArtistFromMetadata(metadata: MediaMetadata): String {
        // Primary: METADATA_KEY_ARTIST
        metadata
            .getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }

        // Fallback 1: ALBUM_ARTIST
        metadata
            .getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }

        // Fallback 2: AUTHOR (some apps use this)
        metadata
            .getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }

        // Fallback 3: DISPLAY_SUBTITLE (often contains artist, but can be video descriptions)
        // Additional validation to filter out video descriptions/subtitles
        metadata
            .getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) && isLikelyArtistName(it) }
            ?.let { return it.trim() }

        // Fallback 4: WRITER
        metadata
            .getString(MediaMetadata.METADATA_KEY_WRITER)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }

        // Fallback 5: COMPOSER (rare but some classical music apps)
        metadata
            .getString(MediaMetadata.METADATA_KEY_COMPOSER)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }

        // Fallback 6: Try to extract from title if it contains separator
        val title =
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title != null) {
            extractArtistFromTitle(title)?.let { return it }
        }

        // Debug log when falling back to Unknown Artist (debounced - only once per track)
        // Skip logging when title is empty - metadata hasn't fully arrived yet
        val logKey = title?.takeIf { it.isNotBlank() }
        if (logKey != null && !loggedArtistExtractionFailures.containsKey(logKey)) {
            loggedArtistExtractionFailures[logKey] = true
            Log.d(
                TAG,
                "Artist extraction failed for MediaMetadata. Available keys: " +
                    "ARTIST='${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}', " +
                    "ALBUM_ARTIST='${metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)}', " +
                    "DISPLAY_SUBTITLE='${metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)}', " +
                    "TITLE='$title'",
            )
        }

        return "Unknown Artist"
    }

    /**
     * Extract artist from notification extras using multiple fallback sources.
     */
    private fun extractArtistFromNotification(
        extras: android.os.Bundle,
        title: String,
    ): String {
        // Primary: EXTRA_TEXT (most common for artist in media notifications)
        extras
            .getCharSequence(EXTRA_TEXT)
            ?.toString()
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }

        // Fallback 1: EXTRA_SUB_TEXT (sometimes contains artist)
        extras
            .getCharSequence(EXTRA_SUB_TEXT)
            ?.toString()
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let {
                // SUB_TEXT might be album, check if it looks like an artist name
                // (doesn't contain common album indicators)
                if (!looksLikeAlbum(it)) return it.trim()
            }

        // Fallback 2: EXTRA_INFO_TEXT
        extras
            .getCharSequence(EXTRA_INFO_TEXT)
            ?.toString()
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }

        // Fallback 3: BIG_TEXT might contain artist info
        extras
            .getCharSequence(EXTRA_BIG_TEXT)
            ?.toString()
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
            Log.d(
                TAG,
                "Artist extraction failed for notification. Available: " +
                    "TEXT='${extras.getCharSequence(EXTRA_TEXT)}', " +
                    "SUB_TEXT='${extras.getCharSequence(EXTRA_SUB_TEXT)}', " +
                    "INFO_TEXT='${extras.getCharSequence(EXTRA_INFO_TEXT)}', " +
                    "TITLE='$title'",
            )
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
                    !isPlaceholderArtist(potentialArtist)
                ) {
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
        return lower in
            listOf(
                "unknown",
                "unknown artist",
                "<unknown>",
                "various artists",
                "various",
                "n/a",
                "na",
                "none",
                "null",
                "",
                " ",
                "artist",
                "track",
                "music",
                "audio",
                "media",
            ) || lower.startsWith("track ") || lower.matches(NUMERIC_ONLY_PATTERN)
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
            lower.matches(YEAR_PATTERN) || // Contains year like "(2023)"
            lower.matches(REMASTER_PATTERN) // Remaster with year
    }

    /**
     * Check if a string looks like an artist name rather than a video description/subtitle.
     *
     * YouTube Music and other apps sometimes put video descriptions in DISPLAY_SUBTITLE,
     * which we incorrectly captured as artist names. This function filters those out.
     *
     * Examples of what should be rejected:
     * - "Kaisa Mera Desh (Anti Corruption anthem)" - video description
     * - "Official Music Video - HD" - video descriptor
     * - Very long text (> 60 chars) - likely descriptions, not artists
     */
    private fun isLikelyArtistName(text: String): Boolean {
        // Too long for a typical artist name (descriptions are often longer)
        if (text.length > 60) return false

        val lowerText = text.lowercase()

        // Video/audio quality suffixes in parentheses - very specific patterns
        // These are clearly metadata, not artist names
        val qualityPatterns = listOf("1080p", "720p", "480p", "4k", "hd video", "full hd")
        if (qualityPatterns.any { lowerText.contains("($it") || lowerText.contains("[$it") }) {
            return false
        }

        // Clear video description patterns that indicate metadata, not artist
        val videoPatterns =
            listOf(
                "official video",
                "official audio",
                "official music video",
                "lyric video",
                "lyrics video",
                "music video",
                "full video",
                "visualizer",
                "full album",
                "audio only",
            )
        if (videoPatterns.any { lowerText.contains(it) }) {
            return false
        }

        // Specific parenthesized description patterns (more restrictive)
        // Only reject if it's clearly a description suffix like "(Official Video)"
        val descriptionSuffixes =
            listOf(
                "(official)",
                "(audio)",
                "(video)",
                "(lyrics)",
                "(visualizer)",
                "(official video)",
                "(official audio)",
                "(lyric video)",
                "(theme song)",
                "(full video)",
                "(music video)",
                "(trailer)",
            )
        if (descriptionSuffixes.any { lowerText.endsWith(it) }) {
            return false
        }

        // The original problematic case: "Kaisa Mera Desh (Anti Corruption anthem)"
        // Detect anthem/theme in parentheses specifically
        if (lowerText.contains("anthem)") || lowerText.contains("theme)")) {
            return false
        }

        // Very long text with separator + parentheses likely indicates description format
        if (text.length > 50 && lowerText.contains(" - ") && lowerText.contains("(")) {
            return false
        }

        return true
    }

    /**
     * Check if notification content is likely an advertisement.
     * Uses heuristics similar to LocalMediaMetadata.isLikelyAdvertisement().
     */
    private fun isLikelyAdvertisementFromNotification(
        title: String,
        artist: String,
        album: String?,
        packageName: String,
    ): Boolean {
        val lowerTitle = title.lowercase()
        val lowerArtist = artist.lowercase()

        // Common ad title patterns
        val adTitlePatterns =
            listOf(
                "advertisement",
                "sponsored",
                "ad break",
                "premium",
                "upgrade",
                "subscribe",
                "commercial",
                "promo",
                "promotion",
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
        val adArtists =
            listOf(
                "advertisement",
                "ad",
                "spotify",
                "youtube",
                "google",
                "amazon",
                "apple",
                "commercial",
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
    private lateinit var appPreferenceDao: me.avinas.tempo.data.local.dao.AppPreferenceDao
    private lateinit var refreshCoordinator: RefreshCoordinator
    private lateinit var trackingRules: TrackingRulesPreferences

    /** Set during component init, read by [recoverPersistedSessions] later in onCreate. */
    private var needsSessionRecovery = false

    /**
     * Room-backed manual content marks, kept live by [watchManualContentMarks] so the
     * synchronous MediaSession/notification paths never need a database read.
     */
    @Volatile
    private var cachedManualContentMarks: List<ManualContentMark> = emptyList()

    /** Re-evaluates active sessions whenever a duration rule changes in Settings. */
    private val durationRulesListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            serviceScope.launch { reevaluateActiveContent() }
        }

    // App filter caches populated from Room
    @Volatile
    private var cachedEnabledApps: Set<String> = emptySet()

    @Volatile
    private var cachedBlockedApps: Set<String> = emptySet()

    @Volatile
    private var cachedAllKnownPackages: Set<String> = emptySet()

    // All packages in DB (enabled or not)
    @Volatile
    private var isAppPreferenceCacheInitialized: Boolean = false // Flag to track if cache has been loaded from DB
    private var lastAppPreferenceFetch: Long = 0
    private val APP_PREFERENCE_CACHE_TTL_MS = 30_000L // Refresh every 30 seconds

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
        artist: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPreferencesFetch > PREFERENCES_CACHE_TTL_MS) {
            try {
                val prefs = userPreferencesDao.getSync()
                cachedFilterPodcasts = prefs?.filterPodcasts ?: true
                cachedFilterAudiobooks = prefs?.filterAudiobooks ?: true
                cachedSpotifyApiOnlyMode = prefs?.spotifyApiOnlyMode ?: false
                cachedMergeAlternateVersions = prefs?.mergeAlternateVersions ?: true
                lastPreferencesFetch = now
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh preferences in shouldFilterContent, using cached", e)
            }
        }

        val filterPodcasts = cachedFilterPodcasts
        val filterAudiobooks = cachedFilterAudiobooks

        if (cachedSpotifyApiOnlyMode && packageName == "com.spotify.music") {
            Log.d(TAG, "Filtering Spotify notification (Spotify-API-Only mode enabled)")
            return true
        }

        if (filterPodcasts && packageName in PODCAST_APPS) {
            Log.d(TAG, "Filtering podcast app: $packageName")
            return true
        }
        if (filterAudiobooks && packageName in AUDIOBOOK_APPS) {
            Log.d(TAG, "Filtering audiobook app: $packageName")
            return true
        }

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

        val manualMark = manualContentMarkDao.findMatchingMark(title, artist)
        if (manualMark != null) {
            val shouldFilter =
                when (manualMark.contentType) {
                    "PODCAST" -> filterPodcasts
                    "AUDIOBOOK" -> filterAudiobooks
                    else -> false
                }
            if (shouldFilter) {
                Log.d(
                    TAG,
                    "Filtering manually marked content: $title by $artist (type: ${manualMark.contentType}, pattern: ${manualMark.patternType})",
                )
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

    // Map of package name to MediaController - ConcurrentHashMap for thread-safe iteration
    private val activeControllers = java.util.concurrent.ConcurrentHashMap<String, MediaController>()
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

    // Cheap liveness signal for ServiceHealthWorker. This avoids heavier polling
    // while still letting the app notice when OEM battery management kills the listener.
    private var heartbeatJob: Job? = null

    // Service uptime tracking
    private val serviceStartTime = AtomicLong(0)

    // Recent plays cache for replay detection (trackId -> lastPlayTimestamp)
    private val recentPlaysCache = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    // Estimated durations cache (title+artist hash -> duration) for when MediaSession doesn't provide duration.
    // LruCache is both thread-safe (read on the notification thread, written from IO
    // coroutines) and bounded, so a long-lived listener can't accumulate entries forever.
    private val durationEstimateCache = android.util.LruCache<String, Long>(512)

    // Smart Metadata: Cached user preference for matching strictness
    // This avoids database call on every track match (preferences rarely change)
    @Volatile
    private var cachedMergeAlternateVersions: Boolean = true

    // Default value
    @Volatile
    private var cachedFilterPodcasts: Boolean = true

    @Volatile
    private var cachedFilterAudiobooks: Boolean = true

    @Volatile
    private var cachedSpotifyApiOnlyMode: Boolean = false
    private var lastPreferencesFetch: Long = 0
    private val PREFERENCES_CACHE_TTL_MS = 60_000L // Refresh every 60 seconds

    // Notification debouncing - prevents excessive notification updates
    private var lastNotificationUpdate = 0L
    private var lastNotificationContent = ""

    // Cache for local MediaSession metadata by trackId
    // Used as ultimate fallback for genre when external sources fail
    // Music players often send metadata in stages, so we keep updating this.
    // Bounded, thread-safe LRU: entries can hold album-art bitmaps, so an unbounded
    // map would retain one bitmap per unique track played for the service's lifetime
    // (the listener can stay alive for weeks).
    private val localMetadataCache = android.util.LruCache<Long, LocalMediaMetadata>(64)

    // Dedup sets to prevent log spam

    // LRU-style set of track titles for which we've already logged artist extraction failures
    // Uses LinkedHashMap with access order to limit memory (max 100 entries)
    private val loggedArtistExtractionFailures =
        object : LinkedHashMap<String, Boolean>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 100
        }

    // LRU-style set of titles for which we've logged "Skipping likely advertisement"
    // Uses LinkedHashMap with access order to limit memory (max 50 entries)
    private val loggedAdvertisementSkips =
        object : LinkedHashMap<String, Boolean>(50, 0.75f, true) {
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
        val sessionId: String =
            java.util.UUID
                .randomUUID()
                .toString()
                .take(8),
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
        var hasReceivedInitialPosition: Boolean = false,
    ) {
        /**
         * Ensures a session produces at most one listening event.
         * Claimed atomically in saveListeningEventSuspend before queueing.
         */
        val eventSaveClaimed =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        /**
         * Calculate total play duration using ACTUAL POSITION DATA.
         * This is the key fix - we use position progress, not wall-clock time.
         */
        fun calculateCurrentPlayDuration(): Long {
            // Use accumulated position-based duration.
            // This represents actual audio playback time, not wall-clock time.
            // No fixed cap: long-form content allowed by the user's tracking rules
            // (ALWAYS_MUSIC, no-limit apps) must count in full. Runaway values are
            // guarded by MAX_REASONABLE_DELTA_MS in updatePosition() and the
            // 3x-estimated-duration checks in validatePlayDuration()/insert paths.
            return accumulatedPositionMs
        }

        /**
         * Update tracking with new position from MediaSession.
         * Call this whenever we get a position update.
         *
         * @param newPosition Current playback position in ms from MediaSession
         * @param isCurrentlyPlaying Whether playback is currently active
         */
        fun updatePosition(
            newPosition: Long,
            isCurrentlyPlaying: Boolean,
        ) {
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

                    // Cap total accumulation to prevent runaway values. Use the same
                    // 3x bound as the save-time sanity cap (insertListeningEvent) so the
                    // two limits agree. The old 1.5x cap truncated looped playback early:
                    // a track that loops without triggering replay-splitting was saved at
                    // 1.5x instead of its true (up to 3x) play time, under-counting
                    // heavily-repeated listens. 3x still bounds runaway accumulation but
                    // matches what the save path would permit anyway.
                    estimatedDurationMs?.let { estimated ->
                        val maxAccumulation = estimated * 3
                        if (accumulatedPositionMs > maxAccumulation && estimated > 60_000) { // Only for >1min tracks
                            Log.w(
                                "PlaybackSession",
                                "ANOMALY: Capping accumulated ${accumulatedPositionMs}ms to $maxAccumulation (3x duration) for '$title'",
                            )
                            accumulatedPositionMs = maxAccumulation
                        }
                    }
                } else if (delta < MAX_POSITION_JUMP_MS) {
                    // Small seek forward (still count as seek, not playback)
                    seekCount++
                    lastSeekTimestamp = now
                    Log.d("PlaybackSession", "Forward seek detected: +${delta}ms for '$title'")
                    // Don't accumulate seek distance
                } else {
                    // Large position jump - likely app error or major seek
                    Log.w(
                        "PlaybackSession",
                        "ANOMALY: Large position jump: $lastRecordedPosition -> $newPosition (delta: ${delta}ms, time: ${timeElapsed}ms)",
                    )
                    // Don't accumulate to avoid inflating play time
                }
            } else if (newPosition < lastRecordedPosition) {
                // Position went backward - either seek backward or restart
                val delta = lastRecordedPosition - newPosition

                if (newPosition < 5000) {
                    // Reset to beginning - track restarted or looped
                    // Use verbose logging since multiple restarts in quick succession are common
                    Log.v("PlaybackSession", "Track restarted/looped for '$title'")

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
                    Log.d("PlaybackSession", "Backward seek detected: -${delta}ms for '$title'")
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
                Log.d("PlaybackSession", "Paused '$title' (pause #$pauseCount)")
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
                    Log.d("PlaybackSession", "Resumed '$title' after ${pauseDuration}ms pause (total pause: ${totalPauseDurationMs}ms)")
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
                duration >= 240_000 -> 100

                // 4+ minutes = definitely full play
                duration >= 180_000 -> 90

                // 3+ minutes = likely full play
                duration >= 120_000 -> 70

                // 2+ minutes = partial play
                duration >= 60_000 -> 50

                // 1+ minute = half play
                else -> estimatedCompletion.coerceIn(0, 40)
            }
        }

        /**
         * Validate that play duration is reasonable.
         * Returns true if valid, false if suspicious.
         */
        fun validatePlayDuration(): Boolean {
            val duration = calculateCurrentPlayDuration()

            // Check if duration exceeds estimated duration by unreasonable amount
            estimatedDurationMs?.let { estimated ->
                if (estimated > 0 && duration > estimated * 3) {
                    Log.w("PlaybackSession", "Validation failed: duration ${duration}ms is 3x estimated ${estimated}ms for '$title'")
                    return false
                }
            }

            // Check for minimum duration (at least 1 second of playback)
            if (duration < 1000 && positionUpdatesCount > 5) {
                Log.w("PlaybackSession", "Validation warning: only ${duration}ms recorded despite $positionUpdatesCount updates")
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
                Log.w("PlaybackSession", "Invalid completion percentage: $percent for '$title'")
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

    /** Receiver unregistered on service teardown. */
    private var batteryStateReceiver: BatteryStateReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MusicTrackingService created")
        serviceStartTime.set(System.currentTimeMillis())
        TrackingServiceHeartbeat.markServiceCreated(this)
        startHeartbeat()
        trackingRules = TrackingRulesPreferences(applicationContext)

        // Promote to foreground before blocking operations (like database migrations in
        // initializeDependencies) to satisfy the foreground service start window.
        createNotificationChannel()
        startForegroundServiceWithNotification()

        // Initialize dependencies via Hilt EntryPoint
        // This is necessary because NotificationListenerService is managed by the system
        // and @AndroidEntryPoint doesn't work properly for it
        initializeDependencies()

        // Initialize enhanced tracking components
        initializeTrackingComponents()
        trackingRules.registerListener(durationRulesListener)

        // Recover any persisted sessions from previous runs
        recoverPersistedSessions()

        // Initialize MediaSessionManager
        initializeMediaSessionManager()

        // Register battery state receiver so we resume tracking the moment battery recovers.
        // Dynamic registration means it only fires while this service is running.
        // NOTE: ACTION_BATTERY_CHANGED is a sticky broadcast — Android delivers it immediately
        // on registration with the current level. We set batteryReceiverReady AFTER registering
        // so the receiver can suppress the initial spurious delivery.
        val receiver = BatteryStateReceiver()
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
        // RECEIVER_NOT_EXPORTED is required on Android 14+ (API 34+) for dynamic receivers
        // that aren't exported; without it registerReceiver throws SecurityException, which
        // crashes onCreate before startForeground can run → BadForegroundServiceNotificationException.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        batteryStateReceiver = receiver
        // Signal receiver that the startup-sticky delivery has passed
        receiver.markReady()
        Log.d(TAG, "BatteryStateReceiver registered")

        // Initial check for service lifecycle
        updateServiceLifecycle()
    }

    private fun initializeDependencies() {
        try {
            val entryPoint =
                EntryPointAccessors.fromApplication(
                    applicationContext,
                    MusicTrackingServiceEntryPoint::class.java,
                )
            trackRepository = entryPoint.trackRepository()
            listeningRepository = entryPoint.listeningRepository()
            enrichedMetadataRepository = entryPoint.enrichedMetadataRepository()
            statsRepository = entryPoint.statsRepository()
            artistLinkingService = entryPoint.artistLinkingService()
            userPreferencesDao = entryPoint.userPreferencesDao()
            trackAliasRepository = entryPoint.trackAliasRepository()
            manualContentMarkDao = entryPoint.manualContentMarkDao()
            appPreferenceDao = entryPoint.appPreferenceDao()
            refreshCoordinator = entryPoint.refreshCoordinator()

            // Manual overrides participate in synchronous MediaSession decisions, so load the
            // small Room table once before any session scan. A Flow keeps it fresh afterwards.
            cachedManualContentMarks =
                runBlocking(Dispatchers.IO) {
                    try {
                        manualContentMarkDao.getAllSync()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

            // Preload app preferences synchronously before the first MediaSession/notification
            // scan. Without this, a statically-known app that the user explicitly disabled
            // could be treated as enabled for a brief startup window.
            try {
                val initialApps = runBlocking(Dispatchers.IO) { appPreferenceDao.getAllApps().first() }
                applyAppPreferenceCache(initialApps)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload app preferences; falling back to startup defaults", e)
            }

            // Load initial preferences for caching
            serviceScope.launch {
                try {
                    val prefs =
                        userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities
                            .UserPreferences()
                    cachedMergeAlternateVersions = prefs.mergeAlternateVersions
                    lastPreferencesFetch = System.currentTimeMillis()
                    Log.d(TAG, "Loaded user preferences: mergeAlternateVersions=$cachedMergeAlternateVersions")

                    // Load app preferences cache
                    refreshAppPreferenceCache()

                    // Load all known packages for checking disabled vs unknown apps
                    cachedAllKnownPackages =
                        appPreferenceDao
                            .getAllApps()
                            .first()
                            .map { it.packageName }
                            .toSet()
                    isAppPreferenceCacheInitialized = true
                    Log.i(
                        TAG,
                        "App preference cache initialized with ${cachedEnabledApps.size} enabled, ${cachedBlockedApps.size} blocked, ${cachedAllKnownPackages.size} total known",
                    )

                    // Fix for tracking bug: now that cache is ready, scan active MediaSessions.
                    // This resolves the race condition where initializeMediaSessionManager()
                    // runs before the cache loads, causing all apps to be initially skipped.
                    withContext(Dispatchers.Main) {
                        rescanActiveMediaSessions()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load initial preferences, using defaults", e)
                }
            }

            Log.d(TAG, "Dependencies initialized successfully")

            // Start watching the battery pause preference so the cached flag stays live
            watchBatteryPreference()
            // Live tracking-rule sources: app enable/block rows and manual content exceptions.
            watchAppPreferences()
            watchManualContentMarks()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize dependencies", e)
            throw e
        }
        // watchBatteryPreference is intentionally called INSIDE the try block above,
        // but is safe because it only launches a coroutine (no throws).
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob =
            serviceScope.launch {
                while (isActive) {
                    TrackingServiceHeartbeat.markServiceAlive(applicationContext)
                    delay(TrackingServiceHeartbeat.HEARTBEAT_INTERVAL_MS)
                }
            }
    }

    /**
     * Refresh the cached enabled/blocked app sets from database.
     * Called on init and periodically when checking apps.
     */
    private suspend fun refreshAppPreferenceCache() {
        try {
            cachedEnabledApps = appPreferenceDao.getEnabledPackageNames().toSet()
            cachedBlockedApps = appPreferenceDao.getBlockedPackageNames().toSet()
            lastAppPreferenceFetch = System.currentTimeMillis()
            Log.d(TAG, "Refreshed app preferences: ${cachedEnabledApps.size} enabled, ${cachedBlockedApps.size} blocked")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh app preferences, using cached values", e)
        }
    }

    /**
     * Check if app preference cache needs refresh and refresh if needed.
     */
    private suspend fun ensureAppPreferenceCacheValid() {
        val now = System.currentTimeMillis()
        if (now - lastAppPreferenceFetch > APP_PREFERENCE_CACHE_TTL_MS) {
            refreshAppPreferenceCache()
        }
    }

    /**
     * Check if a package is in the user-enabled music apps list.
     *
     * Falls back to the static MUSIC_APPS set only when the DB cache hasn't loaded yet
     * (race condition on service start). Once the cache is initialised the DB wins, so
     * any app the user has explicitly disabled is still respected.
     */
    private fun isInEnabledApps(packageName: String): Boolean {
        // Cache not ready yet → fall back to the compile-time whitelist so that apps
        // already playing when the service starts are not silently dropped.
        // This fixes the race condition where initializeMediaSessionManager() fires before
        // refreshAppPreferenceCache() completes and all apps are skipped.
        if (!isAppPreferenceCacheInitialized) {
            val inStaticList = packageName in MUSIC_APPS
            if (inStaticList) {
                Log.d(TAG, "App preference cache not ready, using static whitelist for $packageName")
            }
            return inStaticList
        }
        // A package with no row at all (app_preferences not yet seeded, or a curated app
        // added in a newer release that hasn't been backfilled) gets the same whitelist
        // fallback. Apps the user explicitly disabled have a row, so user intent is never
        // overridden — without this, an empty/unseeded table reads as "nothing enabled"
        // and curated apps (Mi Music, Poweramp, ...) fall through to heuristics that
        // reject "player"-named packages as video apps.
        if (packageName !in cachedAllKnownPackages && packageName in MUSIC_APPS) {
            Log.d(TAG, "No app_preferences row for $packageName, using static whitelist")
            return true
        }
        return packageName in cachedEnabledApps
    }

    /**
     * Check if a package is in the blocked apps list.
     *
     * Two-tier block check: compile-time [BLOCKED_APPS] set, then user blacklist in Room.
     *
     * The static list is checked first so video apps like YouTube
     * are blocked immediately regardless of database cache state.
     */
    private fun isInBlockedApps(packageName: String): Boolean {
        // FIRST: Always block apps in the static blocklist (guaranteed protection)
        // This catches video apps, browsers, social media that should NEVER be tracked
        if (packageName in BLOCKED_APPS) {
            return true
        }

        // SECOND: Check database cache for user-defined blocked apps
        // If cache not initialized yet, return false (will be filtered by isInEnabledApps anyway)
        if (!isAppPreferenceCacheInitialized) {
            return false
        }
        return packageName in cachedBlockedApps
    }

    /**
     * Clean up any active session for a package that was just disabled or blocked.
     * Saves any accumulated playtime and removes the session.
     * Called reactively when app preferences change in the database.
     */
    private fun cleanupSessionForPackage(packageName: String) {
        val session = playbackStates[packageName] ?: return

        Log.i(TAG, "Cleaning up session for disabled/blocked app: $packageName (${session.title})")

        // Save any accumulated play time before removing
        if (session.calculateCurrentPlayDuration() >= minimumPlayDurationMs()) {
            saveListeningEvent(session)
        }

        // Remove the session
        playbackStates.remove(packageName)

        // Unregister controller callback
        activeControllers[packageName]?.let { controller ->
            val callback = packageSpecificCallbacks[packageName] ?: sharedCallback
            try {
                controller.unregisterCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering callback for $packageName", e)
            }
        }
        activeControllers.remove(packageName)
        packageSpecificCallbacks.remove(packageName)

        // Update service lifecycle (may go idle if no more sessions)
        updateServiceLifecycle()
    }

    /** Configured minimum accumulated play time before an event is stored. */
    private fun minimumPlayDurationMs(): Long =
        if (::trackingRules.isInitialized) {
            trackingRules.minimumPlayDurationMs
        } else {
            TrackingRulesPreferences.DEFAULT_MIN_PLAY_DURATION_MS
        }

    private fun findManualContentMark(
        title: String,
        artist: String,
    ): ManualContentMark? = ManualContentRuleResolver.resolve(cachedManualContentMarks, title, artist)

    private fun manualContentOverride(
        title: String,
        artist: String,
    ): ContentOverrideType? =
        when (findManualContentMark(title, artist)?.contentType?.uppercase()) {
            "ALWAYS_MUSIC" -> ContentOverrideType.MUSIC
            "NON_MUSIC", "VIDEO" -> ContentOverrideType.VIDEO
            else -> null
        }

    /**
     * Applies the user's content exceptions and per-app/global maximum duration rules.
     * Unknown duration (0) is never rejected here — callers re-check once a reliable
     * duration is resolved from the track row or enrichment.
     */
    private fun shouldRejectByTrackingRules(
        packageName: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): Boolean {
        when (manualContentOverride(title, artist)) {
            ContentOverrideType.VIDEO -> {
                Log.d(TAG, "Rejecting manual video/non-music override: '$title' by '$artist'")
                return true
            }

            ContentOverrideType.MUSIC -> {
                return false
            }

            null -> {
                Unit
            }
        }

        if (durationMs > 0L && ::trackingRules.isInitialized) {
            val maxDuration = trackingRules.getMaxMusicDurationMs(packageName)
            if (maxDuration != null && durationMs > maxDuration) {
                Log.d(
                    TAG,
                    "Rejecting content over configured music limit: '$title' by '$artist' " +
                        "(${durationMs / 1000}s > ${maxDuration / 1000}s) from $packageName",
                )
                return true
            }
        }
        return false
    }

    /**
     * Atomically drops the session for [packageName] when it matches the rejected media.
     * A different in-flight session is saved instead of discarded.
     */
    private fun removeRejectedSession(
        packageName: String,
        title: String,
        artist: String,
        expectedSession: PlaybackSession? = null,
    ) {
        val previous = expectedSession ?: playbackStates[packageName] ?: return
        if (!playbackStates.remove(packageName, previous)) return
        val sameRejectedMedia =
            previous.title.equals(title, ignoreCase = true) &&
                (
                    previous.artist.equals(artist, ignoreCase = true) ||
                        me.avinas.tempo.utils.ArtistParser
                            .isUnknownArtist(previous.artist) ||
                        me.avinas.tempo.utils.ArtistParser
                            .isUnknownArtist(artist)
                )
        previous.pause()
        if (!sameRejectedMedia) {
            saveListeningEvent(previous)
        }
    }

    /** Applies a freshly loaded app-preference list to the in-memory caches. */
    private fun applyAppPreferenceCache(apps: List<me.avinas.tempo.data.local.entities.AppPreference>) {
        cachedEnabledApps = apps.filter { it.isEnabled && !it.isBlocked }.map { it.packageName }.toSet()
        cachedBlockedApps = apps.filter { it.isBlocked }.map { it.packageName }.toSet()
        cachedAllKnownPackages = apps.map { it.packageName }.toSet()
        isAppPreferenceCacheInitialized = true
        lastAppPreferenceFetch = System.currentTimeMillis()
    }

    /**
     * Keeps app enable/block state live. Disabling an app stops its running session
     * immediately; enabling one (including an explicitly enabled YouTube) makes the
     * static blocklist fallback step aside and rescans active media.
     */
    private fun watchAppPreferences() {
        serviceScope.launch {
            appPreferenceDao.getAllApps().collect { apps ->
                applyAppPreferenceCache(apps)

                val packagesToStop =
                    playbackStates.keys.filter { packageName ->
                        packageName in cachedBlockedApps ||
                            (packageName in cachedAllKnownPackages && packageName !in cachedEnabledApps)
                    }
                packagesToStop.forEach(::cleanupSessionForPackage)
                withContext(Dispatchers.Main) {
                    rescanActiveMediaSessions()
                    try {
                        activeNotifications?.forEach { sbn ->
                            if (isMusicNotification(sbn)) processNotificationPosted(sbn)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to re-evaluate notifications after app preference change", e)
                    }
                    updateServiceLifecycle()
                }
            }
        }
    }

    /** Keeps manual content exceptions live and re-evaluates anything already playing. */
    private fun watchManualContentMarks() {
        serviceScope.launch {
            manualContentMarkDao.getAllMarks().collect { marks ->
                cachedManualContentMarks = marks
                reevaluateActiveContent()
            }
        }
    }

    /**
     * Re-applies content exceptions and duration rules to active sessions and active
     * sources, so corrections take effect without waiting for the next player event.
     */
    private suspend fun reevaluateActiveContent() {
        playbackStates.values.toList().forEach { session ->
            val rejectedByTrackingRule =
                shouldRejectByTrackingRules(
                    session.packageName,
                    session.title,
                    session.artist,
                    session.estimatedDurationMs ?: 0L,
                )
            val filteredByContent =
                if (rejectedByTrackingRule) {
                    false
                } else {
                    shouldFilterContent(
                        session.packageName,
                        session.trackId?.let { localMetadataCache.get(it) },
                        session.title,
                        session.artist,
                    )
                }
            if (rejectedByTrackingRule || filteredByContent) {
                removeRejectedSession(session.packageName, session.title, session.artist, session)
            }
        }

        withContext(Dispatchers.Main) {
            rescanActiveMediaSessions()
            activeControllers.values.toList().forEach { controller ->
                try {
                    processMediaControllerState(controller)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to re-evaluate MediaSession after rule change", e)
                }
            }
            try {
                activeNotifications?.forEach { sbn ->
                    if (isMusicNotification(sbn)) processNotificationPosted(sbn)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-evaluate notifications after rule change", e)
            }
            updateServiceLifecycle()
        }
    }

    private fun initializeTrackingComponents() {
        // Durable store for events that failed to reach the database — survives
        // OEM kills, crashes and reboots.
        val offlineStore =
            try {
                OfflineEventStore(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "OfflineEventStore unavailable, events will not survive process death", e)
                null
            }

        try {
            // Initialize tracking manager for batched event saving
            trackingManager = MusicTrackingManager(listeningRepository, serviceScope, offlineStore)

            // Initialize session persistence for recovery.
            // Capture the unclean-shutdown flag BEFORE marking the service active:
            // markServiceActive() sets the same timestamp wasUncleanShutdown() reads,
            // so reading it afterwards would always report true and re-recover the
            // previous run's autosaved sessions, duplicating listening events.
            sessionPersistence = SessionPersistence(applicationContext)
            needsSessionRecovery = sessionPersistence.wasUncleanShutdown()
            sessionPersistence.markServiceActive()

            // Initialize smart duration estimator
            durationEstimator = SmartDurationEstimator()

            Log.d(TAG, "Enhanced tracking components initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize tracking components", e)
            // Create fallback instances
            trackingManager = MusicTrackingManager(listeningRepository, serviceScope, offlineStore)
            sessionPersistence = SessionPersistence(applicationContext)
            durationEstimator = SmartDurationEstimator()
        }
    }

    private fun recoverPersistedSessions() {
        serviceScope.launch {
            try {
                if (needsSessionRecovery) {
                    Log.i(TAG, "Detected unclean shutdown, attempting session recovery")
                    val recoveredSessions = sessionPersistence.loadSessions()

                    if (recoveredSessions.isNotEmpty()) {
                        Log.i(TAG, "Recovered ${recoveredSessions.size} sessions from persistence")

                        // Process recovered sessions - they were interrupted
                        for (state in recoveredSessions) {
                            // Dedup guard: if an event with this sessionId already
                            // reached the database (session ended normally but the
                            // persisted copy was stale when we were killed), do NOT
                            // re-queue it — that would double-count the play.
                            val alreadySaved =
                                try {
                                    listeningRepository.getEventsBySessionId(state.sessionId).isNotEmpty()
                                } catch (e: Exception) {
                                    false
                                }
                            if (alreadySaved) {
                                Log.i(TAG, "Skipping recovery of '${state.trackTitle}' — event already saved (session ${state.sessionId})")
                                continue
                            }

                            // FIX: Don't add time since last save - the session was interrupted
                            // and we can't know if playback actually continued after last save.
                            // Using only the persisted play time prevents inflation from device sleep.
                            val estimatedPlayTime = state.totalPlayedMs

                            // Recovered sessions keep their full accumulated play time; a
                            // fixed 1-hour cap would silently truncate legitimately long
                            // content (podcasts, mixes) that the user allows to be tracked.
                            val cappedPlayTime = estimatedPlayTime.coerceAtLeast(0L)

                            if (cappedPlayTime < minimumPlayDurationMs()) continue

                            // Resolve the track. Sessions persisted before their
                            // trackId was assigned (track insert still in flight when
                            // we were killed) used to be dropped here — losing the play
                            // entirely. Resolve by title/artist instead.
                            val trackId =
                                state.trackId ?: try {
                                    getOrInsertTrack(state.trackTitle, state.trackArtist, state.trackAlbum).id
                                } catch (e: Exception) {
                                    Log.e(TAG, "Could not resolve track for recovered session '${state.trackTitle}'", e)
                                    null
                                }
                            if (trackId == null) {
                                Log.w(TAG, "Dropping unrecoverable session '${state.trackTitle}' (no trackId, resolution failed)")
                                continue
                            }

                            // Resolve a reliable duration and apply the same content/duration
                            // rules as the live save paths before replaying a recovered session.
                            val reliableDuration =
                                state.estimatedDurationMs?.takeIf { it > 0L } ?: try {
                                    trackRepository
                                        .getById(trackId)
                                        .first()
                                        ?.duration
                                        ?.takeIf { it > 0L }
                                        ?: getTrackDurationFromMetadata(trackId)
                                } catch (e: Exception) {
                                    null
                                }

                            if (shouldRejectByTrackingRules(
                                    state.packageName,
                                    state.trackTitle,
                                    state.trackArtist,
                                    reliableDuration ?: 0L,
                                )
                            ) {
                                continue
                            }

                            if (shouldFilterContent(
                                    state.packageName,
                                    localMetadataCache.get(trackId),
                                    state.trackTitle,
                                    state.trackArtist,
                                )
                            ) {
                                continue
                            }

                            // Also cap at 3x the reliable duration for sanity
                            val finalPlayTime =
                                if (reliableDuration != null && reliableDuration > 0) {
                                    cappedPlayTime.coerceAtMost(reliableDuration * 3)
                                } else {
                                    cappedPlayTime
                                }

                            if (finalPlayTime < minimumPlayDurationMs()) continue

                            // Create a listening event for the recovered session
                            val event =
                                ListeningEvent(
                                    track_id = trackId,
                                    timestamp = state.startTimestamp,
                                    playDuration = finalPlayTime,
                                    completionPercentage =
                                        durationEstimator.calculateCompletionPercent(
                                            finalPlayTime,
                                            state.estimatedDurationMs,
                                            false,
                                        ),
                                    source = state.packageName,
                                    wasSkipped = false,
                                    isReplay = false,
                                    estimatedDurationMs = state.estimatedDurationMs,
                                    pauseCount = state.pauseCount,
                                    sessionId = state.sessionId,
                                    endTimestamp = System.currentTimeMillis(),
                                    wasInterrupted = true, // Mark as recovered/interrupted session
                                )

                            trackingManager.queueEvent(event, state.sessionId)
                            Log.i(TAG, "Queued recovered event for '${state.trackTitle}': ${finalPlayTime}ms (was ${estimatedPlayTime}ms)")
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

        autoSaveJob =
            serviceScope.launch {
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
    private fun hasActivePlayback(): Boolean =
        playbackStates.values.any { session ->
            session.isPlaying && session.isLikelyMusic
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

        positionPollingJob =
            serviceScope.launch {
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
        val activeSession =
            playbackStates.values.firstOrNull { it.isPlaying && it.isLikelyMusic }
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
        // ConcurrentHashMap provides weakly consistent iteration that won't throw
        // ConcurrentModificationException. Create a copy to avoid issues with
        // entries being removed during iteration.
        val controllerSnapshot: List<Pair<String, MediaController>>
        synchronized(activeControllers) {
            controllerSnapshot = activeControllers.toList()
        }

        controllerSnapshot.forEach { (packageName, controller) ->
            try {
                val playbackState = controller.playbackState ?: return@forEach
                val session = playbackStates[packageName] ?: return@forEach

                val currentPosition = playbackState.position
                val isPlaying = playbackState.state == PlaybackState.STATE_PLAYING

                // Update position tracking
                session.updatePosition(currentPosition, isPlaying)

                // Log periodically for debugging (every ~30 seconds of accumulated play time)
                if (isPlaying && session.accumulatedPositionMs > 0 && session.accumulatedPositionMs % 30000 < 10000) {
                    Log.d(
                        TAG,
                        "Position poll: '${session.title}' - position: ${currentPosition}ms, accumulated: ${session.accumulatedPositionMs}ms",
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error polling position for $packageName", e)
            }
        }
    }

    private suspend fun saveSessionsToPersistence() {
        try {
            // Create a snapshot to avoid ConcurrentModificationException
            val statesSnapshot = playbackStates.toMap()
            // Persist sessions even before their trackId is resolved — title/artist
            // are enough to recover the play after a kill, and recovery resolves the
            // track lazily. Dropping these sessions used to lose plays entirely.
            val currentSessions =
                statesSnapshot
                    .map { (_, session) ->
                        SessionState(
                            sessionId = session.sessionId,
                            packageName = session.packageName,
                            trackId = session.trackId,
                            trackTitle = session.title,
                            trackArtist = session.artist,
                            trackAlbum = session.album,
                            startTimestamp = session.startTimestamp,
                            lastResumeTimestamp = session.lastPositionUpdateTime,
                            totalPlayedMs = session.accumulatedPositionMs,
                            isPlaying = session.isPlaying,
                            pauseCount = session.pauseCount,
                            estimatedDurationMs = session.estimatedDurationMs,
                        )
                    }.associateBy { it.sessionId }

            if (currentSessions.isNotEmpty()) {
                sessionPersistence.saveSessions(currentSessions)
            } else {
                // No active sessions — clear any stale persisted sessions so a
                // later kill cannot resurrect (and double-count) finished plays.
                sessionPersistence.clearSessions()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-save sessions", e)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        // Always (re-)assert the foreground state here. If the system restarts the service
        // to deliver an intent (e.g. ACTION_BATTERY_RECOVERED via startForegroundService) and
        // onCreate's startForeground didn't land within the ANR/low-memory restart window,
        // the system throws BadForegroundServiceNotificationException. Asserting here makes
        // the foreground promotion idempotent and deadline-safe on Android 14+.
        startForegroundServiceWithNotification()

        when (intent?.action) {
            ACTION_BATTERY_RECOVERED -> {
                Log.i(TAG, "Battery recovered — rescanning active notifications and media sessions")
                // Invalidate battery cache so shouldPauseForBattery() reads fresh level
                BatteryUtils.invalidateCache()
                // Re-scan any notifications and media sessions that were missed while paused
                serviceScope.launch {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        try {
                            activeNotifications?.forEach { sbn ->
                                if (isMusicNotification(sbn)) {
                                    processNotificationPosted(sbn)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error rescanning notifications after battery recovery", e)
                        }
                        rescanActiveMediaSessions()
                        updateServiceLifecycle()
                    }
                }
            }
        }
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
        TrackingServiceHeartbeat.markListenerConnected(this)

        Log.i(TAG, "NotificationListener connected - scanning active notifications")

        // Scan existing notifications for music that's already playing
        // Delay briefly to allow system binder registration before querying notifications
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

                // Also rescan active MediaSessions in case they were missed
                // during initial initialization
                rescanActiveMediaSessions()

                // Update lifecycle after initial scan using the correct context
                updateServiceLifecycle()
            }
        }
    }

    override fun onListenerDisconnected() {
        synchronized(connectionLock) {
            isListenerConnected = false
        }
        TrackingServiceHeartbeat.markListenerDisconnected(this)

        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener disconnected - attempting reconnect")

        // Request rebind
        requestRebind(ComponentName(this, MusicTrackingService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isMusicNotification(sbn)) return
        TrackingServiceHeartbeat.markNotificationCallback(this)

        Log.d(TAG, "Music notification posted: ${sbn.packageName}")
        processNotificationPosted(sbn)
        updateServiceLifecycle()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isMusicNotification(sbn)) return
        TrackingServiceHeartbeat.markNotificationCallback(this)

        Log.d(TAG, "Music notification removed: ${sbn.packageName}")
        processNotificationRemoved(sbn)
        updateServiceLifecycle()
    }

    private fun isMusicNotification(sbn: StatusBarNotification): Boolean {
        val packageName = sbn.packageName

        // FIRST: Explicitly block video/non-music apps
        if (isInBlockedApps(packageName)) {
            Log.d(TAG, "Blocking notification from video app: $packageName")
            return false
        }

        // Check if it's from a known music app (user-enabled)
        if (isInEnabledApps(packageName)) return true

        // For unknown apps, check notification category
        val notification = sbn.notification
        val category = notification.category
        if (category == Notification.CATEGORY_TRANSPORT ||
            category == Notification.CATEGORY_SERVICE
        ) {
            // Check for media style - but still need to verify it's music
            val extras = notification.extras
            if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                // Additional check: don't track if package name suggests video
                val lowerPkg = packageName.lowercase()
                if (lowerPkg.contains("video") || lowerPkg.contains("movie") ||
                    lowerPkg.contains("tv") || lowerPkg.contains("stream")
                ) {
                    Log.d(TAG, "Blocking media notification from likely video app: $packageName")
                    return false
                }
                return true
            }
        }

        return false
    }

    private fun processNotificationPosted(sbn: StatusBarNotification) {
        if (shouldPauseForBattery()) {
            Log.d(TAG, "Tracking paused (low battery) — ignoring notification from ${sbn.packageName}")
            // Save any in-progress session so no listening data is lost
            val session = playbackStates.remove(sbn.packageName)
            if (session != null) {
                session.pause()
                saveListeningEvent(session)
            }
            // Reflect paused-for-battery state in foreground notification
            updateTrackingNotification(null, null)
            return
        }

        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras

        val rawTitle = extras.getCharSequence(EXTRA_TITLE)?.toString()?.trim()
        // Skip notifications with empty titles - metadata hasn't fully arrived yet
        if (rawTitle.isNullOrBlank()) {
            Log.d(TAG, "Ignoring notification with empty title from $packageName")
            return
        }
        val artist = extractArtistFromNotification(extras, rawTitle)

        // Clean title if it contains embedded artist (e.g., "Song - Artist")
        val title =
            if (artist != "Unknown Artist" && artist != extractArtistFromTitle(rawTitle)) {
                rawTitle // Artist came from elsewhere, keep title as-is
            } else if (extractArtistFromTitle(rawTitle) != null) {
                cleanTitleIfNeeded(rawTitle) // Artist extracted from title, clean it
            } else {
                rawTitle
            }

        val album =
            extras
                .getCharSequence(EXTRA_SUB_TEXT)
                ?.toString()
                ?.trim()
                ?.takeIf { !it.equals(artist, ignoreCase = true) } // Don't use SUB_TEXT if it's the artist
                ?: extras.getCharSequence(EXTRA_INFO_TEXT)?.toString()?.trim()

        // Manual video/non-music override: never track this content, even from an
        // otherwise enabled music app (e.g. a podcast episode on a music app).
        if (manualContentOverride(title, artist) == ContentOverrideType.VIDEO) {
            removeRejectedSession(packageName, title, artist)
            updateTrackingNotification(null, null)
            updateServiceLifecycle()
            return
        }

        // Extract album art from notification (for fallback use)
        val albumArtBitmap = extractAlbumArtFromNotification(notification)

        Log.d(TAG, "Music notification: '$title' by '$artist' from $packageName (has art: ${albumArtBitmap != null})")

        // CRITICAL: Use synchronized block to prevent race condition when
        // notification and MediaSession callbacks fire simultaneously for the same track.
        // Without this, both could see existingSession=null, create sessions, and one overwrites the other.
        val (sessionAction, existingSession) =
            synchronized(sessionOperationLock) {
                val existing = playbackStates[packageName]

                // Check if this is the same track (handle artist updates from Unknown -> Real)
                val isSameTrackInfo =
                    existing != null && existing.title == title && (
                        existing.artist == artist ||
                            // If existing session has unknown artist but notification has real artist, update it
                            (
                                me.avinas.tempo.utils.ArtistParser
                                    .isUnknownArtist(existing.artist) &&
                                    !me.avinas.tempo.utils.ArtistParser
                                        .isUnknownArtist(artist)
                            )
                    )

                // Detect replay based on accumulated play time exceeding estimated duration
                val isReplayDetected =
                    isSameTrackInfo &&
                        run {
                            val playedMs = existing!!.calculateCurrentPlayDuration()
                            val estimatedDuration = existing.estimatedDurationMs
                            val playedExceedsDuration =
                                estimatedDuration != null &&
                                    estimatedDuration > 0 &&
                                    playedMs > estimatedDuration + 10_000L
                            if (playedExceedsDuration) {
                                Log.d(
                                    TAG,
                                    "Notification: Replay detected for '${existing.title}': " +
                                        "played ${playedMs}ms exceeds estimated duration ${estimatedDuration}ms",
                                )
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
                if (me.avinas.tempo.utils.ArtistParser
                        .isUnknownArtist(session.artist) &&
                    !me.avinas.tempo.utils.ArtistParser
                        .isUnknownArtist(artist)
                ) {
                    Log.d(TAG, "Notification: Updating session artist from '${session.artist}' to '$artist' for '$title'")
                    session.artist = artist

                    // Update the track in database if we have a trackId
                    session.trackId?.let { trackId ->
                        serviceScope.launch {
                            try {
                                val track = trackRepository.getById(trackId).first()
                                if (track != null &&
                                    me.avinas.tempo.utils.ArtistParser
                                        .isUnknownArtist(track.artist)
                                ) {
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
                    !me.avinas.tempo.utils.ArtistParser
                        .isUnknownArtist(artist) &&
                    isLikelyAdvertisementFromNotification(title, artist, album, packageName)
                ) {
                    if (!loggedAdvertisementSkips.containsKey(title)) {
                        loggedAdvertisementSkips[title] = true
                        Log.d(TAG, "Skipping likely advertisement: '$title' from $packageName")
                    }
                    return
                }

                // Detach previous session before saving to prevent duplicate saves on subsequent returns.
                if (existingSession != null) {
                    playbackStates.remove(packageName, existingSession)
                    existingSession.pause()
                    saveListeningEvent(existingSession)
                }

                // Try to get cached duration estimate for this track
                val cachedDurationKey = generateHash("$title|$artist")
                val cachedDuration = durationEstimateCache.get(cachedDurationKey)

                // Reject by rules once a cached duration is known (NON_MUSIC override or over
                // the configured maximum) before a session is even created for this track.
                if (cachedDuration != null &&
                    shouldRejectByTrackingRules(packageName, title, artist, cachedDuration)
                ) {
                    Log.d(TAG, "Rejecting content by tracking rules (cached duration): '$title' by '$artist'")
                    removeRejectedSession(packageName, title, artist)
                    updateTrackingNotification(null, null)
                    return
                }

                // Clear log debounce caches for fresh logging on new track
                loggedArtistExtractionFailures.clear()
                loggedAdvertisementSkips.clear()

                val newSession =
                    PlaybackSession(
                        packageName = packageName,
                        title = title,
                        artist = artist,
                        album = album,
                        isPlaying = true,
                        estimatedDurationMs = cachedDuration,
                        accumulatedPositionMs = 0,
                        lastRecordedPosition = 0,
                        isLikelyMusic = true,
                    )

                // Atomic put - thread-safe even without the outer lock
                playbackStates[packageName] = newSession
                updateTrackingNotification(title, artist)

                // Asynchronously resolve and insert track before persisting child records
                serviceScope.launch {
                    try {
                        // CONTENT FILTERING: same rules as the MediaSession path (podcast/
                        // audiobook apps, manual marks, Spotify-API-only mode). Without this,
                        // plays detected via notification are tracked and saved before the
                        // media path gets a chance to filter them out.
                        if (shouldFilterContent(packageName, null, title, artist)) {
                            Log.d(TAG, "Content filtered via notification: '$title' by '$artist' from $packageName")
                            removeRejectedSession(packageName, title, artist, newSession)
                            updateTrackingNotification(null, null)
                            return@launch
                        }
                        val track = getOrInsertTrack(title, artist, album)
                        newSession.trackId = track.id

                        // Try to get duration from track or enriched metadata
                        val duration = track.duration?.takeIf { it > 0L } ?: getTrackDurationFromMetadata(track.id)
                        if (duration != null && duration > 0) {
                            // Duration only became known now — re-apply content/duration rules
                            // before this session can be saved.
                            if (shouldRejectByTrackingRules(packageName, title, artist, duration)) {
                                removeRejectedSession(packageName, title, artist, newSession)
                                updateTrackingNotification(null, null)
                                updateServiceLifecycle()
                                return@launch
                            }
                            newSession.estimatedDurationMs = duration
                            // Also cache it for future use
                            durationEstimateCache.put(cachedDurationKey, duration)
                        }

                        Log.d(
                            TAG,
                            "Track ID ${track.id} assigned for '$title' (duration: ${newSession.estimatedDurationMs?.let {
                                "${it / 1000}s"
                            } ?: "unknown"})",
                        )

                        // Save the extracted art off the notification callback (main) thread —
                        // JPEG encode + file write there was a jank/ANR risk on rapid track skips.
                        val localArtUrl = albumArtBitmap?.let { saveAlbumArtToStorage(it, title, artist) }

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
    private suspend fun getTrackDurationFromMetadata(trackId: Long): Long? =
        try {
            enrichedMetadataRepository.forTrackSync(trackId)?.trackDurationMs
        } catch (e: Exception) {
            Log.w(TAG, "Could not get duration from metadata for track $trackId", e)
            null
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
        metadata: LocalMediaMetadata? = null,
    ): Track {
        val cleanTitle =
            me.avinas.tempo.utils.ArtistParser
                .cleanTrackTitle(title)

        val exactMatch = trackRepository.findByTitleAndArtist(title, artist)

        if (exactMatch != null) {
            Log.d(TAG, "Found exact match for track ${exactMatch.id}: '$title' by '$artist'")
            return handleExistingTrack(exactMatch, artist, album)
        }

        val alias = trackAliasRepository.findAlias(title, artist)
        if (alias != null) {
            val targetTrack = trackRepository.getById(alias.targetTrackId).first()
            if (targetTrack != null) {
                Log.i(TAG, "Smart Alias found: '$title' -> mapped to '${targetTrack.title}' (ID: ${targetTrack.id})")
                return handleExistingTrack(targetTrack, artist, album)
            } else {
                Log.w(TAG, "Orphaned alias found for '$title', ignoring")
            }
        }

        val fuzzyArtistMatch = trackRepository.findByTitleAndArtistFuzzy(title, artist)
        if (fuzzyArtistMatch != null) {
            Log.d(TAG, "Found fuzzy artist match for track ${fuzzyArtistMatch.id}: '$title' by '$artist'")
            return handleExistingTrack(fuzzyArtistMatch, artist, album)
        }

        val titleCandidates = trackRepository.findCandidatesByTitle(title)
        val cleanTitleMatch =
            titleCandidates.find { track ->
                track.title.equals(cleanTitle, ignoreCase = true) &&
                    me.avinas.tempo.utils.ArtistParser
                        .hasAnyMatchingArtist(track.artist, artist)
            }

        if (cleanTitleMatch != null) {
            Log.d(TAG, "Found clean title match for track ${cleanTitleMatch.id}: '$title' by '$artist'")
            return handleExistingTrack(cleanTitleMatch, artist, album)
        }

        val now = System.currentTimeMillis()
        if (now - lastPreferencesFetch > PREFERENCES_CACHE_TTL_MS) {
            try {
                val prefs =
                    userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities
                        .UserPreferences()
                cachedMergeAlternateVersions = prefs.mergeAlternateVersions
                lastPreferencesFetch = now
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh preferences, using cached value", e)
            }
        }

        val strictMatching = !cachedMergeAlternateVersions

        val candidates =
            trackRepository.findFuzzyCandidates(title, artist).map { track ->
                TrackCandidate(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                )
            }

        val matchResult = TrackMatcher.findBestMatch(title, artist, candidates, strictMatching)

        if (matchResult != null && matchResult.second.overallScore >= TRACK_MATCH_THRESHOLD) {
            val (candidate, result) = matchResult
            val existingTrack = trackRepository.getById(candidate.id).first()

            if (existingTrack != null) {
                Log.d(
                    TAG,
                    "Found fuzzy match for track ${existingTrack.id}: '$title' by '$artist' " +
                        "(score: ${String.format(java.util.Locale.US, "%.2f", result.overallScore)}, type: ${result.matchType})",
                )
                return handleExistingTrack(existingTrack, artist, album)
            }
        }

        return insertNewTrack(title, artist, album, metadata)
    }

    /**
     * Update metadata and link artists for an existing track record.
     */
    private suspend fun handleExistingTrack(
        existingTrack: Track,
        newArtist: String,
        newAlbum: String?,
    ): Track {
        // Update artist if the existing track has unknown artist but new one has real artist
        if (me.avinas.tempo.utils.ArtistParser
                .isUnknownArtist(existingTrack.artist) &&
            !me.avinas.tempo.utils.ArtistParser
                .isUnknownArtist(newArtist)
        ) {
            Log.i(TAG, "Updating track ${existingTrack.id} artist from '${existingTrack.artist}' to '$newArtist'")
            val updatedTrack = existingTrack.copy(artist = newArtist, album = newAlbum ?: existingTrack.album)
            trackRepository.update(updatedTrack)

            // Re-link artists now that we have the real artist name
            artistLinkingService.linkArtistsForTrack(updatedTrack)

            // Re-trigger enrichment now that we have the real artist
            EnrichmentWorker.enqueueImmediate(applicationContext, existingTrack.id)

            return updatedTrack
        }

        // Link artists for tracks imported prior to relational artist schema
        if (existingTrack.primaryArtistId == null &&
            !me.avinas.tempo.utils.ArtistParser
                .isUnknownArtist(existingTrack.artist)
        ) {
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
        metadata: LocalMediaMetadata? = null,
    ): Track {
        Log.d(TAG, "Inserting new track: '$title' by '$artist'")

        // Determine content type from metadata if available, otherwise default to MUSIC
        val contentType = metadata?.getContentType()?.toString() ?: "MUSIC"

        val newTrack =
            Track(
                title = title,
                artist = artist,
                album = album,
                duration = null,
                albumArtUrl = null,
                spotifyId = null,
                musicbrainzId = null,
                contentType = contentType,
            )

        val id = trackRepository.insert(newTrack)
        Log.i(TAG, "Inserted new track with ID $id: '$title' by '$artist'")

        if (id <= 0) {
            Log.e(TAG, "Failed to insert track - got invalid ID: $id")
        }

        val insertedTrack = newTrack.copy(id = id)

        // Link artists for the new track (creates Artist records and TrackArtist links)
        if (!me.avinas.tempo.utils.ArtistParser
                .isUnknownArtist(artist)
        ) {
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
    // Track IDs we've already logged as enriched (gates a log message only).
    // Bounded LRU + lock: written from IO coroutines on multiple threads, and an
    // unbounded set would grow for the lifetime of the listener service.
    private val loggedEnrichedTracks =
        object : LinkedHashMap<Long, Boolean>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>): Boolean = size > 256
        }

    private suspend fun saveLocalMetadataFallback(
        trackId: Long,
        localMetadata: LocalMediaMetadata,
    ) {
        try {
            // Always update the local metadata cache (music players send metadata in stages)
            val cachedMetadata = localMetadataCache.get(trackId)
            val updatedLocalMetadata =
                if (cachedMetadata != null) {
                    // Merge: prefer non-null values from latest metadata
                    cachedMetadata.copy(
                        genre = localMetadata.genre ?: cachedMetadata.genre,
                        album = localMetadata.album ?: cachedMetadata.album,
                        year = localMetadata.year ?: cachedMetadata.year,
                        durationMs = localMetadata.durationMs ?: cachedMetadata.durationMs,
                        albumArtBitmap = localMetadata.albumArtBitmap ?: cachedMetadata.albumArtBitmap,
                        albumArtUri = localMetadata.albumArtUri ?: cachedMetadata.albumArtUri,
                    )
                } else {
                    localMetadata
                }
            localMetadataCache.put(trackId, updatedLocalMetadata)

            // Cache local bitmap to storage as offline backup even if remote URL exists
            val savedLocalArtUrl =
                updatedLocalMetadata.albumArtBitmap?.let {
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

            // Keep local art as fallback in Track table when enriched remote hotlink exists
            if (!shouldUpdate) {
                val track = trackRepository.getById(trackId).first()
                if (track != null && track.albumArtUrl.isNullOrBlank() && savedLocalArtUrl != null) {
                    // Store local art in Track table as immediate backup
                    Log.i(TAG, "Storing local art as backup in Track table while enriched hotlink exists")
                    trackRepository.updateAutomaticAlbumArtUrl(trackId, savedLocalArtUrl)
                } else if (track != null && enrichedArtIsRemoteUrl && savedLocalArtUrl != null) {
                    // We have both enriched hotlink and local backup
                    // Keep current setup but log for tracking
                    Log.d(
                        TAG,
                        "Track $trackId: Has enriched hotlink '${enrichedArtUrl?.take(30)}...' and local backup saved: $savedLocalArtUrl",
                    )
                }

                // Only log once per track to reduce spam
                val shouldLogEnriched =
                    synchronized(loggedEnrichedTracks) {
                        if (loggedEnrichedTracks.containsKey(trackId)) {
                            false
                        } else {
                            loggedEnrichedTracks[trackId] = true
                            true
                        }
                    }
                if (shouldLogEnriched) {
                    val hasGenres = !existingMetadata?.genres.isNullOrEmpty()
                    val localHasGenre = updatedLocalMetadata.genre != null
                    if (!hasGenres && !localHasGenre) {
                        Log.d(TAG, "Track $trackId: No genres from external sources or local metadata (local genre was null)")
                    } else {
                        Log.d(
                            TAG,
                            "Track $trackId: Already has enriched metadata. " +
                                "Enriched art: '${enrichedArtUrl?.take(20)}...', Local backup saved: $savedLocalArtUrl",
                        )
                    }
                }
                return
            }

            // Use already-saved local art URL, or get from URI
            val localArtUrl = savedLocalArtUrl ?: updatedLocalMetadata.getBestAlbumArtSource()

            // Update the enriched metadata with local data (only fill in missing fields)
            val updatedMetadata =
                (
                    existingMetadata ?: me.avinas.tempo.data.local.entities
                        .EnrichedMetadata(trackId = trackId)
                ).copy(
                    albumTitle = existingMetadata?.albumTitle ?: updatedLocalMetadata.album,
                    albumArtUrl = existingMetadata?.albumArtUrl ?: localArtUrl,
                    // Set source as LOCAL when using local album art - this allows API sources to replace it later
                    albumArtSource =
                        if (needsAlbumArt && localArtUrl != null) {
                            me.avinas.tempo.data.local.entities.AlbumArtSource.LOCAL
                        } else {
                            existingMetadata?.albumArtSource ?: me.avinas.tempo.data.local.entities.AlbumArtSource.NONE
                        },
                    releaseYear = existingMetadata?.releaseYear ?: updatedLocalMetadata.getReleaseYear(),
                    trackDurationMs = existingMetadata?.trackDurationMs ?: updatedLocalMetadata.durationMs,
                    genres =
                        if (existingMetadata?.genres.isNullOrEmpty() && updatedLocalMetadata.genre != null) {
                            listOf(updatedLocalMetadata.genre)
                        } else {
                            existingMetadata?.genres ?: emptyList()
                        },
                    // Mark as having local metadata if we filled in any fields
                    cacheTimestamp = System.currentTimeMillis(),
                )

            enrichedMetadataRepository.upsertFromAutomaticEnrichment(updatedMetadata)

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
                    val updatedTrack =
                        track.copy(
                            album = track.album.takeUnless { it.isNullOrBlank() } ?: updatedLocalMetadata.album,
                            duration = track.duration ?: updatedLocalMetadata.durationMs,
                        )
                    trackRepository.update(updatedTrack)
                    trackRepository.updateAutomaticAlbumArtUrl(trackId, localArtUrl)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving local metadata fallback for track $trackId", e)
        }
    }

    /** Backfills Track.albumArtUrl from enriched metadata or local storage fallback. */
    private suspend fun checkAndBackfillTrackArt(
        trackId: Long,
        enrichedArtUrl: String?,
        localMetadata: LocalMediaMetadata,
        savedLocalArtUrl: String? = null,
    ) {
        try {
            val track = trackRepository.getById(trackId).first()
            if (track != null && track.albumArtUrl.isNullOrBlank()) {
                // If we have enriched art, use that first
                // Fix HTTP URLs to HTTPS for better reliability
                val fixedEnrichedUrl =
                    me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
                        .fixHttpUrl(enrichedArtUrl)
                if (!fixedEnrichedUrl.isNullOrBlank()) {
                    Log.i(TAG, "Backfilling Track $trackId with enriched art: $fixedEnrichedUrl")
                    trackRepository.updateAutomaticAlbumArtUrl(trackId, fixedEnrichedUrl)
                }
                // Otherwise try pre-saved local art first (already on disk)
                else if (savedLocalArtUrl != null) {
                    Log.i(TAG, "Backfilling Track $trackId with pre-saved local art: $savedLocalArtUrl")
                    trackRepository.updateAutomaticAlbumArtUrl(trackId, savedLocalArtUrl)
                }
                // Finally try to save from bitmap or URI
                else {
                    val localArtUrl =
                        localMetadata.albumArtBitmap?.let {
                            saveAlbumArtToStorage(it, localMetadata.title, localMetadata.artist)
                        } ?: localMetadata.getBestAlbumArtSource()

                    if (localArtUrl != null) {
                        Log.i(TAG, "Backfilling Track $trackId with local art: $localArtUrl")
                        trackRepository.updateAutomaticAlbumArtUrl(trackId, localArtUrl)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking/backfilling track art", e)
        }
    }

    // Album art extraction from notifications

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
    private fun saveAlbumArtToStorage(
        bitmap: Bitmap,
        title: String,
        artist: String,
    ): String? {
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

            // Save bitmap to file (downsampled — huge cover art bitmaps previously
            // caused OOM/TransactionTooLarge-style failures when saved + reloaded)
            val toSave = downsampleBitmap(bitmap, 512)
            FileOutputStream(file).use { out ->
                toSave.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (toSave !== bitmap) toSave.recycle()

            Log.i(TAG, "Saved album art to: ${file.absolutePath}")
            return "file://${file.absolutePath}"
        } catch (e: Exception) {
            Log.e(TAG, "Error saving album art", e)
            return null
        }
    }

    private fun downsampleBitmap(
        bitmap: Bitmap,
        maxDimension: Int,
    ): Bitmap {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        if (w <= maxDimension && h <= maxDimension) return bitmap
        val ratio = (maxDimension / maxOf(w, h)).coerceAtMost(1f)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
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
    private suspend fun updateTrackAlbumArtIfNeeded(
        trackId: Long,
        localArtUrl: String,
    ) {
        try {
            val track = trackRepository.getById(trackId).first() ?: return

            // Only update if track doesn't have album art URL
            if (track.albumArtUrl.isNullOrBlank()) {
                Log.i(TAG, "Updating track $trackId with local album art: $localArtUrl")
                trackRepository.updateAutomaticAlbumArtUrl(trackId, localArtUrl)
            } else {
                Log.d(TAG, "Track $trackId already has album art, skipping local art update")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating track album art", e)
        }
    }

    private fun saveListeningEvent(session: PlaybackSession) {
        serviceScope.launch { saveListeningEventSuspend(session) }
    }

    /**
     * Suspend variant that performs the save inline (no internal coroutine launch).
     *
     * The normal hot path calls it via [saveListeningEvent] on [serviceScope].
     * onDestroy calls it directly inside a bounded runBlocking so the final events
     * are guaranteed to reach the database before the service scope is cancelled.
     */
    private suspend fun saveListeningEventSuspend(session: PlaybackSession) {
        val playDuration = session.calculateCurrentPlayDuration()

        // Don't save plays shorter than the configured minimum.
        if (playDuration < minimumPlayDurationMs()) {
            Log.d(TAG, "Skipping short play: ${playDuration}ms for '${session.title}'")
            return
        }

        // Apply any rule that can already be decided before resolving the track row.
        if (shouldRejectByTrackingRules(
                session.packageName,
                session.title,
                session.artist,
                session.estimatedDurationMs ?: 0L,
            )
        ) {
            return
        }

        var trackId = session.trackId
        if (trackId == null) {
            Log.w(TAG, "No trackId for session '${session.title}', deferring save for ${playDuration}ms play")
            // Wait briefly for the async track insertion to resolve the ID
            var retries = 0
            val maxRetries = 5
            while (session.trackId == null && retries < maxRetries) {
                delay(500) // Wait for track insertion
                retries++
            }

            trackId = session.trackId
            if (trackId == null) {
                // Try to find the track by title/artist as a last resort
                try {
                    val track = getOrInsertTrack(session.title, session.artist, session.album)
                    Log.d(TAG, "Found/created track ${track.id} for deferred save of '${session.title}'")
                    trackId = track.id
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save deferred listening event for '${session.title}'", e)
                    return
                }
            } else {
                Log.d(TAG, "TrackId resolved after ${retries * 500}ms, saving event for '${session.title}'")
            }
        }

        // A notification-only session can start with an unknown duration. Resolve a reliable
        // DB/enrichment duration at save time and re-apply the maximum then; never reject an
        // unknown duration merely because it was unknown at session start.
        if (session.estimatedDurationMs == null || session.estimatedDurationMs!! <= 0L) {
            val reliableDuration =
                try {
                    trackRepository
                        .getById(trackId)
                        .first()
                        ?.duration
                        ?.takeIf { it > 0L }
                        ?: getTrackDurationFromMetadata(trackId)
                } catch (e: Exception) {
                    null
                }
            if (reliableDuration != null && reliableDuration > 0L) {
                session.estimatedDurationMs = reliableDuration
            }
        }

        if (shouldRejectByTrackingRules(
                session.packageName,
                session.title,
                session.artist,
                session.estimatedDurationMs ?: 0L,
            )
        ) {
            return
        }

        if (shouldFilterContent(
                session.packageName,
                localMetadataCache.get(trackId),
                session.title,
                session.artist,
            )
        ) {
            return
        }

        // Claim the session. If already claimed, skip saving to prevent duplicate listening events.
        if (!session.eventSaveClaimed.compareAndSet(false, true)) {
            Log.w(
                TAG,
                "Blocked duplicate save for session ${session.sessionId} " +
                    "('${session.title}' by '${session.artist}') — session was already saved",
            )
            return
        }

        insertListeningEvent(trackId, session)
    }

    private suspend fun insertListeningEvent(
        trackId: Long,
        session: PlaybackSession,
    ) {
        var playDuration = session.calculateCurrentPlayDuration()
        val currentTime = System.currentTimeMillis()

        // Final check for play duration
        if (playDuration < minimumPlayDurationMs()) {
            Log.d(TAG, "Skipping short play in insert: ${playDuration}ms for '${session.title}'")
            return
        }

        // Also cap at 3x estimated duration (if available) as sanity check
        session.estimatedDurationMs?.let { estimated ->
            if (estimated > 0) {
                val maxReasonable = estimated * 3
                if (playDuration > maxReasonable) {
                    Log.w(
                        TAG,
                        "ANOMALY: Duration ${playDuration}ms is >3x estimated ${estimated}ms, capping to ${maxReasonable}ms for '${session.title}'",
                    )
                    playDuration = maxReasonable
                }
            }
        }

        // Get duration from smart estimator if not available
        val estimatedDuration =
            session.estimatedDurationMs ?: run {
                val estimate = durationEstimator.estimateDuration(session.title, session.artist, session.album)
                estimate.durationMs
            }

        // Calculate completion percentage using smart estimator
        val completionPct =
            durationEstimator.calculateCompletionPercent(
                playDuration,
                estimatedDuration,
                isStillPlaying = false,
            )

        // Determine if this was a skip
        val wasSkipped = session.wasSkipped() || completionPct < SKIP_COMPLETION_THRESHOLD

        // Check if this is a replay (same track played within 5 minutes)
        val isReplay =
            recentPlaysCache[trackId]?.let { lastPlayTime ->
                (currentTime - lastPlayTime) < REPLAY_THRESHOLD_MS
            } ?: false

        // Update recent plays cache
        recentPlaysCache[trackId] = currentTime

        // Clean old entries from cache (older than replay threshold)
        val oldThreshold = currentTime - REPLAY_THRESHOLD_MS
        recentPlaysCache.entries.removeIf { it.value < oldThreshold }

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
                album = session.album,
            )
        }

        // Legacy duration cache update for backward compatibility
        session.estimatedDurationMs?.let { duration ->
            if (duration > 0) {
                val key = generateHash("${session.title}|${session.artist}")
                durationEstimateCache.put(key, duration)
            }
        }

        val event =
            ListeningEvent(
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
                wasInterrupted = session.wasInterrupted,
                volumeLevel =
                    (
                        getSystemService(
                            Context.AUDIO_SERVICE,
                        ) as android.media.AudioManager
                    ).getStreamVolume(android.media.AudioManager.STREAM_MUSIC),
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
                val eventDetails =
                    buildString {
                        append("'${session.title}' played for ${playDuration / 1000}s")
                        append(" ($completionPct%)")
                        if (wasSkipped) append(" [SKIPPED]")
                        if (isReplay) append(" [REPLAY]")
                        if (session.pauseCount > 0) append(" [${session.pauseCount} pauses]")
                    }
                Log.i(TAG, "Queued listening event: $eventDetails")

                // Invalidate stats cache to trigger real-time updates
                (statsRepository as? RoomStatsRepository)?.onNewListeningEvent(event.timestamp)

                // Notify active ViewModels about the new event
                refreshCoordinator.notifyNewTrackRecorded()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue listening event, falling back to direct insert", e)
            // Fallback to direct insert — routed through the manager so the
            // session-identity dedup applies on this path as well.
            try {
                val result = trackingManager.saveEventImmediate(event)
                if (result.getOrDefault(0L) > 0L) {
                    (statsRepository as? RoomStatsRepository)?.onNewListeningEvent(event.timestamp)
                    refreshCoordinator.notifyNewTrackRecorded()
                }
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Direct insert also failed", fallbackError)
            }
        }
    }

    // MediaSessionManager fallback

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
                componentName,
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

    private fun rescanActiveMediaSessions() {
        if (!isMediaSessionManagerInitialized) {
            Log.d(TAG, "MediaSessionManager not initialized, skipping rescan")
            return
        }
        try {
            val componentName = ComponentName(this, MusicTrackingService::class.java)
            val activeSessions = mediaSessionManager?.getActiveSessions(componentName)
            Log.i(TAG, "Rescanning active media sessions: ${activeSessions?.size ?: 0} sessions found")
            onActiveSessionsChanged(activeSessions)
        } catch (e: Exception) {
            Log.w(TAG, "Error rescanning active media sessions", e)
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

        // 1. Identify removed packages - synchronized to get consistent view of keys
        val newPackageNames = currentControllers.map { it.packageName }.toSet()
        val removedPackages: Set<String>
        synchronized(activeControllers) {
            removedPackages = activeControllers.keys.toSet() - newPackageNames
        }

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
                // CRITICAL: Only track if the app is explicitly enabled in user preferences,
                // or if it's a music session from an UNKNOWN app (not in our database).
                // Apps that user explicitly disabled (exists in DB but isEnabled=false) should NEVER be tracked.
                val isEnabled = isInEnabledApps(packageName)
                val isBlocked = isInBlockedApps(packageName)
                val isKnownApp = packageName in cachedAllKnownPackages

                // App is explicitly disabled if: exists in DB AND not enabled AND not blocked
                // (not blocked because blocked is handled separately with higher priority)
                val isExplicitlyDisabled = isKnownApp && !isEnabled && !isBlocked

                // Track if:
                // 1. Explicitly enabled by user, OR
                // 2. Is music session AND is NOT a known disabled app (allows unknown apps)
                val shouldTrack = isEnabled || (isMusicSession(controller) && !isExplicitlyDisabled)

                if (shouldTrack && !isBlocked) {
                    // Register specific callback
                    val callback = PackageSpecificCallback(packageName)
                    controller.registerCallback(callback)

                    activeControllers[packageName] = controller
                    packageSpecificCallbacks[packageName] = callback

                    // Process initial state
                    processMediaControllerState(controller)
                } else if (isExplicitlyDisabled) {
                    Log.d(TAG, "Skipping disabled app: $packageName (user disabled in preferences)")
                } else if (isBlocked) {
                    Log.d(TAG, "Skipping blocked app: $packageName")
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

        // FIRST: Block known video apps (user-blocked or default blocked)
        if (isInBlockedApps(packageName)) {
            Log.d(TAG, "Blocking MediaSession from video app: $packageName")
            return false
        }

        // Manual content exceptions and duration rules apply even to apps the user enabled:
        // ALWAYS_MUSIC bypasses content heuristics, NON_MUSIC/VIDEO excludes the content,
        // and the configured maximum duration rejects long-form media.
        val earlyMetadata = controller.metadata
        val earlyTitle =
            earlyMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: earlyMetadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (!earlyTitle.isNullOrBlank()) {
            val earlyArtist =
                earlyMetadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: earlyMetadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: "Unknown Artist"
            when (manualContentOverride(earlyTitle, earlyArtist)) {
                ContentOverrideType.VIDEO -> {
                    Log.d(TAG, "Rejecting manual video/non-music override: '$earlyTitle' by '$earlyArtist'")
                    return false
                }

                ContentOverrideType.MUSIC -> {
                    return true
                }

                null -> {
                    Unit
                }
            }
            val earlyDuration = earlyMetadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (shouldRejectByTrackingRules(packageName, earlyTitle, earlyArtist, earlyDuration)) {
                return false
            }
        }

        // Known music apps (user-enabled) are always music
        if (isInEnabledApps(packageName)) {
            return true
        }

        // For unknown apps, use heuristics to detect music vs video
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        // Must have title at minimum
        val title =
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
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
            // Long content is no longer rejected unconditionally here: the configurable
            // maximum-duration rule (checked above, and again at save time once a reliable
            // duration is known) decides whether long-form media is tracked.
        }

        // Check if metadata looks like music (has artist/album)
        val artist =
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)

        // Having artist OR album is a strong indicator of music
        val hasMusicMetadata = !artist.isNullOrBlank() || !album.isNullOrBlank()

        // Package name heuristics for unknown apps
        val lowerPkg = packageName.lowercase()
        val looksLikeVideoApp =
            lowerPkg.contains("video") || lowerPkg.contains("movie") ||
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

        if (shouldPauseForBattery()) {
            Log.d(TAG, "Tracking paused (low battery) — ignoring MediaController update from $packageName")
            val session = playbackStates.remove(packageName)
            if (session != null) {
                session.pause()
                saveListeningEvent(session)
            }
            updateTrackingNotification(null, null)
            return
        }

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

        val rawTitle =
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        // Skip metadata with empty titles - metadata hasn't fully arrived yet
        if (rawTitle.isNullOrBlank()) {
            Log.d(TAG, "Ignoring MediaSession update with empty title from $packageName")
            return
        }
        val artist = extractArtistFromMetadata(metadata)

        // Clean title if artist was extracted from it
        val title =
            if (artist != "Unknown Artist" && extractArtistFromTitle(rawTitle) == artist) {
                cleanTitleIfNeeded(rawTitle)
            } else {
                rawTitle
            }

        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (shouldRejectByTrackingRules(packageName, title, artist, duration)) {
            removeRejectedSession(packageName, title, artist)
            updateTrackingNotification(null, null)
            updateServiceLifecycle()
            return
        }

        // Get current playback position for replay detection
        val currentPosition = playbackState?.position ?: 0L

        val isPlaying = state == PlaybackState.STATE_PLAYING

        // CRITICAL: Use synchronized block to prevent race condition when
        // notification and MediaSession callbacks fire simultaneously for the same track.
        val (sessionAction, session, isSameTrack) =
            synchronized(sessionOperationLock) {
                val existing = playbackStates[packageName]

                // Check if this is the same track (handle artist updates from Unknown -> Real)
                val isSameTrackInfo =
                    existing != null && existing.title == title && (
                        existing.artist == artist ||
                            // If existing session has unknown artist but new one has real artist, it's the same track
                            (
                                me.avinas.tempo.utils.ArtistParser
                                    .isUnknownArtist(existing.artist) &&
                                    !me.avinas.tempo.utils.ArtistParser
                                        .isUnknownArtist(artist)
                            ) ||
                            // If new metadata has unknown artist but session has real artist, keep session's artist
                            (
                                !me.avinas.tempo.utils.ArtistParser
                                    .isUnknownArtist(existing.artist) &&
                                    me.avinas.tempo.utils.ArtistParser
                                        .isUnknownArtist(artist)
                            )
                    )

                // Detect replay: same track info but position reset to near beginning
                val isReplayDetected =
                    isSameTrackInfo && isPlaying &&
                        run {
                            val lastPosition = existing!!.lastKnownPosition
                            val playedMs = existing.calculateCurrentPlayDuration()

                            val positionResetThresholdMs = 5_000L
                            val minPlayedForReplayMs = 30_000L

                            val positionWentBack =
                                lastPosition > positionResetThresholdMs &&
                                    currentPosition < positionResetThresholdMs

                            val estimatedDuration = existing.estimatedDurationMs ?: duration.takeIf { it > 0 }

                            val minCompletionForReplay = 0.5f
                            val completionPercent =
                                if (estimatedDuration != null && estimatedDuration > 0) {
                                    playedMs.toFloat() / estimatedDuration
                                } else {
                                    0f
                                }
                            val hasMinCompletion = completionPercent >= minCompletionForReplay

                            val playedExceedsDuration =
                                estimatedDuration != null &&
                                    estimatedDuration > 0 &&
                                    playedMs > estimatedDuration + 10_000L

                            val isReplay =
                                (positionWentBack && playedMs >= minPlayedForReplayMs && hasMinCompletion) ||
                                    playedExceedsDuration

                            if (isReplay) {
                                Log.d(
                                    TAG,
                                    "Replay detected for '${existing.title}': " +
                                        "position reset from ${lastPosition}ms to ${currentPosition}ms, " +
                                        "played ${playedMs}ms (${(completionPercent * 100).toInt()}%), estimated duration ${estimatedDuration}ms",
                                )
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
                            val cached = localMetadataCache.get(trackId)
                            val hasNewData =
                                cached == null ||
                                    (localMetadata.genre != null && cached.genre == null) ||
                                    (localMetadata.year != null && cached.year == null) ||
                                    (localMetadata.album != null && cached.album == null) ||
                                    (localMetadata.durationMs != null && cached.durationMs == null)

                            if (hasNewData) {
                                // Always update the cache with latest data
                                localMetadataCache.put(
                                    trackId,
                                    if (cached != null) {
                                        cached.copy(
                                            genre = localMetadata.genre ?: cached.genre,
                                            album = localMetadata.album ?: cached.album,
                                            year = localMetadata.year ?: cached.year,
                                            durationMs = localMetadata.durationMs ?: cached.durationMs,
                                            albumArtBitmap = localMetadata.albumArtBitmap ?: cached.albumArtBitmap,
                                            albumArtUri = localMetadata.albumArtUri ?: cached.albumArtUri,
                                        )
                                    } else {
                                        localMetadata
                                    },
                                )
                                Log.d(
                                    TAG,
                                    "Updated local metadata cache for track $trackId (genre: ${localMetadata.genre}, album: ${localMetadata.album})",
                                )
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
                            val stillSameSession =
                                playbackStates[packageName]?.let {
                                    it.trackId == trackId && it.sessionId == currentSession.sessionId
                                } ?: false

                            if (stillSameSession) {
                                Log.d(TAG, "Performing delayed metadata retry for track $trackId after 1 minute")
                                val latestCachedMetadata = localMetadataCache.get(trackId)
                                if (latestCachedMetadata != null) {
                                    saveLocalMetadataFallback(trackId, latestCachedMetadata)
                                }
                            }
                        }
                    }
                }

                // Update artist if we now have a better one
                if (me.avinas.tempo.utils.ArtistParser
                        .isUnknownArtist(currentSession.artist) &&
                    !me.avinas.tempo.utils.ArtistParser
                        .isUnknownArtist(artist)
                ) {
                    Log.d(TAG, "Updating session artist from '${currentSession.artist}' to '$artist' for '$title'")
                    currentSession.artist = artist

                    // Update the track in database if we have a trackId
                    currentSession.trackId?.let { trackId ->
                        serviceScope.launch {
                            try {
                                val track = trackRepository.getById(trackId).first()
                                if (track != null &&
                                    me.avinas.tempo.utils.ArtistParser
                                        .isUnknownArtist(track.artist)
                                ) {
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
                // Detach previous session before saving so subsequent callbacks cannot re-save it.
                session?.let {
                    playbackStates.remove(packageName, it)
                    it.pause()
                    saveListeningEvent(it)
                }

                // Extract ALL available metadata from MediaSession (for fallback use)
                val localMetadata = LocalMediaMetadata.fromMediaMetadata(metadata, packageName)

                // Check if this is likely an advertisement - skip tracking ads
                if (artist.isNotBlank() &&
                    !me.avinas.tempo.utils.ArtistParser
                        .isUnknownArtist(artist) &&
                    localMetadata?.isLikelyAdvertisement() == true
                ) {
                    if (!loggedAdvertisementSkips.containsKey(title)) {
                        loggedAdvertisementSkips[title] = true
                        Log.d(TAG, "Skipping likely advertisement: '$title' from $packageName")
                    }
                    return
                }

                // Extract album art from metadata (for fallback use)
                val albumArtBitmap = localMetadata?.albumArtBitmap ?: extractAlbumArtFromMetadata(metadata)

                // Clear log debounce caches for fresh logging on new track
                loggedArtistExtractionFailures.clear()
                loggedAdvertisementSkips.clear()

                val newSession =
                    PlaybackSession(
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
                        isLikelyMusic = true,
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
                        Log.d(TAG, "Track ID ${track.id} assigned for '$title' (via MediaSession)")

                        // Save the extracted art off the MediaController callback (main) thread —
                        // JPEG encode + file write there was a jank/ANR risk on rapid track skips.
                        val localArtUrl = albumArtBitmap?.let { saveAlbumArtToStorage(it, title, artist) }

                        // Cache local art URI for downstream metadata fallback
                        if (localArtUrl != null) {
                            val currentCache = localMetadataCache.get(track.id) ?: localMetadata
                            if (currentCache != null) {
                                localMetadataCache.put(track.id, currentCache.copy(albumArtUri = localArtUrl))
                                Log.d(TAG, "Updated local metadata cache with art URI: $localArtUrl")
                            } else if (localMetadata == null) {
                                localMetadataCache.put(
                                    track.id,
                                    LocalMediaMetadata(
                                        title = title,
                                        artist = artist,
                                        album = album,
                                        albumArtUri = localArtUrl,
                                    ),
                                )
                            }
                        }

                        // Save local metadata as fallback (delayed to let external enrichment run first)
                        if (localMetadata != null && localMetadata.hasRichMetadata()) {
                            delay(30_000)
                            val latestMetadata = localMetadataCache.get(track.id) ?: localMetadata
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

    inner class PackageSpecificCallback(
        private val packageName: String,
    ) : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            TrackingServiceHeartbeat.markMediaSessionCallback(applicationContext)
            // fast lookup - we know exactly which controller triggered this
            activeControllers[packageName]?.let { controller ->
                processMediaControllerState(controller)
                updateServiceLifecycle()
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            TrackingServiceHeartbeat.markMediaSessionCallback(applicationContext)
            activeControllers[packageName]?.let { controller ->
                processMediaControllerState(controller)
                updateServiceLifecycle()
            }
        }

        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            TrackingServiceHeartbeat.markMediaSessionCallback(applicationContext)
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

    // Foreground service notification

    /**
     * Creates or refreshes both notification channels without throwing.
     */
    private fun createNotificationChannel() {
        try {
            // Main channel for when actively tracking music
            val trackingChannel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Music Tracking",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows when Tempo is actively tracking your music"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }

            // Silent channel for background monitoring (minimal visibility)
            val silentChannel =
                NotificationChannel(
                    CHANNEL_ID + "_silent",
                    "Background Monitoring",
                    NotificationManager.IMPORTANCE_MIN, // Minimal importance - won't show on status bar
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channels", e)
        }
    }

    /**
     * Checks that both notification channels exist, recreating them if wiped by the system.
     * Posting a foreground notification without a valid channel triggers BadForegroundServiceNotificationException.
     */
    private fun ensureNotificationChannelsExist(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        val missing =
            listOf(CHANNEL_ID, CHANNEL_ID + "_silent")
                .filter { manager.getNotificationChannel(it) == null }
        if (missing.isEmpty()) return true

        Log.w(TAG, "Notification channel(s) missing ($missing) — recreating before startForeground")
        createNotificationChannel()

        val stillMissing =
            listOf(CHANNEL_ID, CHANNEL_ID + "_silent")
                .filter { manager.getNotificationChannel(it) == null }
        if (stillMissing.isNotEmpty()) {
            Log.e(TAG, "Cannot create notification channel(s) $stillMissing; refusing to enter foreground")
            return false
        }
        return true
    }

    private fun startForegroundServiceWithNotification() {
        // Verify channels exist before calling startForeground to avoid BadForegroundServiceNotificationException.
        if (!ensureNotificationChannelsExist()) {
            Log.e(TAG, "Skipping foreground promotion: notification channels unavailable")
            return
        }

        val notification = buildTrackingNotificationSafely(null, null)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // A transient failure (e.g. foreground-start window lapsed, or the system
            // forbids the type) must not crash the service. Stop cleanly instead.
            Log.e(TAG, "Failed to enter foreground state", e)
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Builds the tracking notification, falling back to a minimal notification if state lookups fail.
     */
    private fun buildTrackingNotificationSafely(
        currentTrack: String?,
        currentArtist: String?,
    ): Notification =
        try {
            buildTrackingNotification(currentTrack, currentArtist)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build tracking notification; using minimal fallback", e)
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle("Tempo")
                .setContentText("Waiting for music...")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

    private fun buildTrackingNotification(
        currentTrack: String?,
        currentArtist: String?,
    ): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // If we are paused specifically due to low battery, show a clear status.
        // This keeps users informed instead of silently showing "Waiting for music..."
        if (shouldPauseForBattery()) {
            val batteryLevel = BatteryUtils.getBatteryLevel(this)
            return NotificationCompat
                .Builder(this, CHANNEL_ID + "_silent")
                .setContentTitle("Tempo — Tracking paused")
                .setContentText("🪫 Battery at $batteryLevel%. Tracking resumes above ${BatteryUtils.CRITICAL_BATTERY_LEVEL}%.")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        // Count music sessions being actively tracked
        val activeMusicSessions = playbackStates.count { it.value.isLikelyMusic && it.value.isPlaying }
        val isActivelyTracking = currentTrack != null || activeMusicSessions > 0

        // Use silent channel when not actively tracking music
        val channelId = if (isActivelyTracking) CHANNEL_ID else CHANNEL_ID + "_silent"

        return if (isActivelyTracking) {
            // ACTIVE TRACKING: Show meaningful notification
            val contentText =
                if (currentTrack != null && currentArtist != null) {
                    val session = playbackStates.values.find { it.title == currentTrack && it.artist == currentArtist }
                    val timeInfo =
                        session?.let {
                            val secs = it.accumulatedPositionMs / 1000
                            if (secs > 0) " (${secs}s)" else ""
                        } ?: ""

                    // Anti-gaming warning: muted playback does not earn XP.
                    val volume = getCurrentMusicStreamVolume()
                    val mutedWarning = if (volume == 0) " [Muted playback detected - XP paused]" else ""

                    "🎵 $currentTrack - $currentArtist$timeInfo$mutedWarning"
                } else {
                    "🎵 Tracking $activeMusicSessions music session(s)"
                }

            NotificationCompat
                .Builder(this, channelId)
                .setContentTitle("Tempo is tracking music")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lock screen when tracking
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        } else {
            // NOT TRACKING: Minimal silent notification (required by Android for foreground service)
            NotificationCompat
                .Builder(this, channelId)
                .setContentTitle("Tempo")
                .setContentText("Waiting for music...")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN) // Minimum priority
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hide from lock screen
                .setSilent(true) // No sound/vibration
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }

    private fun updateTrackingNotification(
        currentTrack: String?,
        currentArtist: String?,
    ) {
        val now = System.currentTimeMillis()
        val currentVolume = getCurrentMusicStreamVolume()
        val isPausedForBattery = shouldPauseForBattery()

        // Create notification content identifier for comparison.
        // Include isPausedForBattery so the "🪫" notification always appears immediately
        // when battery drops and disappears immediately when it recovers — the debounce
        // must not suppress a battery-state transition.
        val contentId = "$currentTrack|$currentArtist|${hasActivePlayback()}|$currentVolume|$isPausedForBattery"

        // Debounce: skip update if content hasn't changed and updated within last 2 seconds
        // Increased from 1s to 2s to reduce notification spam during rapid MediaSession callbacks
        if (contentId == lastNotificationContent && (now - lastNotificationUpdate) < 2000) {
            // Silent skip - no logging needed for normal debounce behavior
            return
        }

        // Verify channels exist to prevent SecurityException on foreground service notifications.
        if (!ensureNotificationChannelsExist()) return

        val notification = buildTrackingNotificationSafely(currentTrack, currentArtist)
        val manager = getSystemService(NotificationManager::class.java)
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update tracking notification", e)
            return
        }

        // Track last update
        lastNotificationUpdate = now
        lastNotificationContent = contentId

        Log.v(TAG, "Notification updated: ${if (hasActivePlayback()) "tracking" else "idle"}")
    }

    private fun getCurrentMusicStreamVolume(): Int {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
    }

    override fun onDestroy() {
        Log.i(TAG, "MusicTrackingService destroyed - saving active sessions")

        if (::trackingRules.isInitialized) trackingRules.unregisterListener(durationRulesListener)

        // Cancel auto-save job and position polling so no further state changes arrive
        autoSaveJob?.cancel()
        positionPollingJob?.cancel()

        // Unregister battery state receiver
        batteryStateReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "BatteryStateReceiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering BatteryStateReceiver", e)
            }
            batteryStateReceiver = null
        }

        // Reset connection state
        synchronized(connectionLock) {
            isListenerConnected = false
        }
        isMediaSessionManagerInitialized = false

        // Cleanup MediaSession callbacks so no new position updates land mid-flush
        val controllerSnapshot: List<Pair<String, MediaController>>
        synchronized(activeControllers) {
            controllerSnapshot = activeControllers.toList()
        }
        controllerSnapshot.forEach { (packageName, controller) ->
            val callback = packageSpecificCallbacks[packageName] ?: sharedCallback
            try {
                controller.unregisterCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering callback for $packageName", e)
            }
        }
        activeControllers.clear()
        packageSpecificCallbacks.clear()

        // CRITICAL: flush everything synchronously BEFORE cancelling serviceScope.
        //
        // The previous implementation launched the final session saves and
        // trackingManager.flushAll() on serviceScope and then immediately called
        // serviceScope.cancel() — cancelling those very coroutines and silently
        // dropping the last batch of listening events on EVERY clean destroy
        // (reboot, app update, service restart). That directly under-counts plays.
        // We block here (bounded) so accrued listening time always reaches the DB.
        val playbackSnapshot = playbackStates.values.toList()
        val flushed =
            try {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(SHUTDOWN_FLUSH_TIMEOUT_MS) {
                        // Persist the freshest session state first so an interrupted
                        // flush can still be recovered on the next start.
                        saveSessionsToPersistence()
                        playbackSnapshot.forEach { session ->
                            session.pause()
                            saveListeningEventSuspend(session)
                        }
                        trackingManager.flushAll()
                        true
                    } ?: false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during shutdown flush", e)
                false
            }
        playbackStates.clear()

        if (flushed) {
            // Events are safely in the DB — clear recovery sessions and record a
            // clean shutdown. Doing this only AFTER a successful flush means a
            // crash/timeout mid-flush still reports wasUncleanShutdown()==true, so
            // the next start recovers the sessions instead of dropping them.
            try {
                sessionPersistence.clearSessions()
                sessionPersistence.markServiceInactive()
                Log.i(TAG, "Shutdown flush complete, metrics: ${trackingManager.getMetrics()}")
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing persisted sessions on shutdown", e)
            }
        } else {
            Log.w(TAG, "Shutdown flush incomplete — persisted sessions left for recovery")
        }

        // Log service uptime
        val uptime = System.currentTimeMillis() - serviceStartTime.get()
        Log.i(TAG, "Service uptime: ${uptime / 1000}s, Duration estimator stats: ${durationEstimator.getStats()}")

        heartbeatJob?.cancel()
        heartbeatJob = null
        TrackingServiceHeartbeat.markListenerDisconnected(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Cached preference value — whether to pause tracking when battery is low.
     * Updated from the service's coroutine scope every time preferences change.
     * Defaults to true so the first check (before prefs load) is conservative.
     */
    @Volatile
    private var cachedPauseOnLowBattery: Boolean = true

    /**
     * Called from [initializeDependencies] to keep [cachedPauseOnLowBattery] in sync.
     */
    private fun watchBatteryPreference() {
        serviceScope.launch {
            userPreferencesDao.preferences().collect { prefs ->
                cachedPauseOnLowBattery = prefs?.pauseTrackingOnLowBattery ?: true
            }
        }
    }

    /**
     * Returns true if tracking should be paused because:
     *   1. The user has "Pause tracking on low battery" enabled (default: true).
     *   2. The device battery is at or below [BatteryUtils.CRITICAL_BATTERY_LEVEL] (20%).
     *
     * Uses [BatteryUtils]' shared cached implementation (refreshed every 2 minutes) so
     * there is no duplicate BatteryManager polling across the app.
     */
    private fun shouldPauseForBattery(): Boolean {
        if (!cachedPauseOnLowBattery) return false
        return BatteryUtils.isCriticalBattery(this)
    }

    /**
     * Get service health information for debugging.
     */
    fun getServiceHealth(): ServiceHealth =
        ServiceHealth(
            isListenerConnected = isListenerConnected,
            activeSessionCount = playbackStates.size,
            trackingMetrics = trackingManager.getMetrics(),
            durationEstimatorStats = durationEstimator.getStats(),
            uptimeMs = System.currentTimeMillis() - serviceStartTime.get(),
        )
}

/**
 * Service health information for debugging and monitoring.
 */
data class ServiceHealth(
    val isListenerConnected: Boolean,
    val activeSessionCount: Int,
    val trackingMetrics: TrackingMetrics,
    val durationEstimatorStats: EstimatorStats,
    val uptimeMs: Long,
)

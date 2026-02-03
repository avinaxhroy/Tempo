package me.avinas.tempo.data

/**
 * Centralized definition of default music apps and blocked apps.
 * 
 * This replaces the hardcoded sets in MusicTrackingService.kt companion object.
 * Used for:
 * 1. Database seeding on first install or migration
 * 2. Reference for app display names in UI
 * 3. Fallback when database is unavailable
 */
object DefaultMusicApps {
    
    data class AppInfo(
        val packageName: String,
        val displayName: String,
        val category: String = "MUSIC"
    )
    
    /**
     * Default music apps that are enabled for tracking.
     * Users can disable any of these in settings.
     */
    val MUSIC_APPS: List<AppInfo> = listOf(
        // Major Streaming Services
        AppInfo("com.spotify.music", "Spotify"),
        AppInfo("com.google.android.apps.youtube.music", "YouTube Music"),
        AppInfo("com.apple.android.music", "Apple Music"),
        AppInfo("com.amazon.mp3", "Amazon Music"),
        AppInfo("com.soundcloud.android", "SoundCloud"),
        AppInfo("deezer.android.app", "Deezer"),
        AppInfo("com.pandora.android", "Pandora"),
        AppInfo("com.aspiro.tidal", "Tidal"),
        AppInfo("com.tidal.android", "Tidal (Old)"),
        AppInfo("com.qobuz.music", "Qobuz"),
        AppInfo("ru.yandex.music", "Yandex Music"),
        
        // Indian Music Services
        AppInfo("com.jio.media.jiobeats", "JioSaavn"),
        AppInfo("com.gaana", "Gaana"),
        AppInfo("com.bsbportal.music", "Wynk Music"),
        AppInfo("com.hungama.myplay.activity", "Hungama Music"),
        AppInfo("in.startv.hotstar.music", "Hotstar Music"),
        AppInfo("com.moonvideo.android.resso", "Resso"),
        
        // Device Manufacturers
        AppInfo("com.samsung.android.app.music", "Samsung Music"),
        AppInfo("com.sec.android.app.music", "Samsung Music (Old)"),
        AppInfo("com.miui.player", "Mi Music"),
        
        // Revanced/Vanced Variants
        AppInfo("app.revanced.android.youtube.music", "YouTube Music ReVanced"),
        AppInfo("app.revanced.android.apps.youtube.music", "YouTube Music ReVanced"),
        AppInfo("com.vanced.android.youtube.music", "YouTube Music Vanced"),
        
        // Other Streaming
        AppInfo("com.audiomack", "Audiomack"),
        AppInfo("com.mmm.trebelmusic", "Trebel"),
        AppInfo("nugs.net", "Nugs.net"),
        AppInfo("net.nugs.multiband", "Nugs.net (Multiband)"),
        
        // Open Source / FOSS Music Clients
        AppInfo("com.dd3boh.outertune", "OuterTune"),
        AppInfo("com.zionhuang.music", "InnerTune"),
        AppInfo("it.vfsfitvnm.vimusic", "ViMusic"),
        AppInfo("oss.krtirtho.spotube", "Spotube"),
        AppInfo("com.shadow.blackhole", "BlackHole"),
        AppInfo("com.anandnet.harmonymusic", "Harmony Music"),
        AppInfo("it.fast4x.rimusic", "RiMusic"),
        AppInfo("com.msob7y.namida", "Namida"),
        AppInfo("com.metrolist.music", "Metrolist"),
        AppInfo("com.gokadzev.musify", "Musify"),
        AppInfo("com.gokadzev.musify.fdroid", "Musify (F-Droid)"),
        AppInfo("ls.bloomee.musicplayer", "BloomeeTunes"),
        AppInfo("com.maxrave.simpmusic", "SimpMusic"),
        AppInfo("com.singularity.gramophone", "Gramophone"),
        AppInfo("player.phonograph.plus", "Phonograph Plus"),
        AppInfo("org.oxycblt.auxio", "Auxio"),
        AppInfo("com.maloy.muzza", "Muzza"),
        AppInfo("uk.co.projectneon.echo", "Echo"),
        AppInfo("com.shabinder.spotiflyer", "SpotiFlyer"),
        AppInfo("com.kapp.youtube.final", "YMusic"),
        AppInfo("org.schabi.newpipe", "NewPipe"),
        AppInfo("org.polymorphicshade.newpipe", "NewPipe (Fork)"),
        
        // Popular Offline Players
        AppInfo("com.maxmpz.audioplayer", "Poweramp"),
        AppInfo("in.krosbits.musicolet", "Musicolet"),
        AppInfo("com.kodarkooperativet.blackplayerfree", "BlackPlayer Free"),
        AppInfo("com.kodarkooperativet.blackplayerex", "BlackPlayer EX"),
        AppInfo("it.ncaferra.pixelplayerfree", "Pixel Player"),
        AppInfo("com.theveloper.pixelplay", "PixelPlay"),
        AppInfo("com.rhmsoft.pulsar", "Pulsar"),
        AppInfo("com.neutroncode.mp", "Neutron Player"),
        AppInfo("gonemad.gmmp", "GoneMad"),
        AppInfo("code.name.monkey.retromusic", "Retro Music Player"),
        AppInfo("com.piyush.music", "Oto Music"),
        AppInfo("com.simplecity.amp_pro", "Shuttle+"),
        AppInfo("ru.stellio.player", "Stellio"),
        AppInfo("io.stellio.music", "Stellio (Alt)"),
        AppInfo("com.frolo.musp", "Frolomuse"),
        AppInfo("com.rhmsoft.omnia", "Omnia")
    )
    
    /**
     * Apps explicitly blocked from tracking (video apps, browsers, etc.).
     * These create MediaSessions but shouldn't be tracked as music apps.
     */
    val BLOCKED_APPS: List<AppInfo> = listOf(
        // Video Streaming
        AppInfo("com.google.android.youtube", "YouTube", "VIDEO"),
        AppInfo("com.google.android.apps.youtube", "YouTube", "VIDEO"),
        AppInfo("app.revanced.android.youtube", "YouTube ReVanced", "VIDEO"),
        AppInfo("com.netflix.mediaclient", "Netflix", "VIDEO"),
        AppInfo("com.amazon.avod.thirdpartyclient", "Prime Video", "VIDEO"),
        AppInfo("com.disney.disneyplus", "Disney+", "VIDEO"),
        AppInfo("in.startv.hotstar", "Hotstar", "VIDEO"),
        AppInfo("com.hotstar.android", "Hotstar", "VIDEO"),
        AppInfo("tv.twitch.android.app", "Twitch", "VIDEO"),
        
        // Social Media
        AppInfo("com.zhiliaoapp.musically", "TikTok", "VIDEO"),
        AppInfo("com.ss.android.ugc.trill", "TikTok", "VIDEO"),
        AppInfo("com.instagram.android", "Instagram", "VIDEO"),
        AppInfo("com.facebook.katana", "Facebook", "VIDEO"),
        AppInfo("com.snapchat.android", "Snapchat", "VIDEO"),
        
        // Video Players
        AppInfo("com.vimeo.android.videoapp", "Vimeo", "VIDEO"),
        AppInfo("com.mxtech.videoplayer.ad", "MX Player", "VIDEO"),
        AppInfo("com.mxtech.videoplayer.pro", "MX Player Pro", "VIDEO"),
        AppInfo("org.videolan.vlc", "VLC", "VIDEO"),
        AppInfo("com.google.android.apps.photos", "Google Photos", "VIDEO"),
        AppInfo("com.whatsapp", "WhatsApp", "VIDEO"),
        AppInfo("org.telegram.messenger", "Telegram", "VIDEO"),
        
        // Browsers
        AppInfo("com.android.chrome", "Chrome", "OTHER"),
        AppInfo("com.chrome.beta", "Chrome Beta", "OTHER"),
        AppInfo("com.chrome.dev", "Chrome Dev", "OTHER"),
        AppInfo("org.mozilla.firefox", "Firefox", "OTHER"),
        AppInfo("org.mozilla.firefox_beta", "Firefox Beta", "OTHER"),
        AppInfo("com.opera.browser", "Opera", "OTHER"),
        AppInfo("com.brave.browser", "Brave", "OTHER"),
        AppInfo("com.microsoft.edge", "Microsoft Edge", "OTHER"),
        AppInfo("com.sec.android.app.sbrowser", "Samsung Browser", "OTHER"),
        
        // Device Gallery/Video Apps
        AppInfo("com.samsung.android.video", "Samsung Video", "VIDEO"),
        AppInfo("com.miui.videoplayer", "Mi Video", "VIDEO"),
        AppInfo("com.miui.gallery", "Mi Gallery", "VIDEO"),
        AppInfo("com.google.android.videos", "Google Play Movies", "VIDEO"),
        AppInfo("com.google.android.apps.youtube.kids", "YouTube Kids", "VIDEO")
    )
    
    /**
     * Podcast apps - tracked separately for content filtering.
     */
    val PODCAST_APPS: List<AppInfo> = listOf(
        AppInfo("com.google.android.apps.podcasts", "Google Podcasts", "PODCAST"),
        AppInfo("fm.player", "Player FM", "PODCAST"),
        AppInfo("au.com.shiftyjelly.pocketcasts", "Pocket Casts", "PODCAST"),
        AppInfo("com.bambuna.podcastaddict", "Podcast Addict", "PODCAST"),
        AppInfo("com.clearchannel.iheartradio.controller", "iHeartRadio", "PODCAST"),
        AppInfo("app.tunein.player", "TuneIn Radio", "PODCAST"),
        AppInfo("com.stitcher.app", "Stitcher", "PODCAST"),
        AppInfo("com.castbox.player", "Castbox", "PODCAST"),
        AppInfo("com.apple.android.podcasts", "Apple Podcasts", "PODCAST"),
        AppInfo("fm.castbox.audiobook.radio.podcast", "Castbox Variant", "PODCAST")
    )
    
    /**
     * Audiobook apps - tracked separately for content filtering.
     */
    val AUDIOBOOK_APPS: List<AppInfo> = listOf(
        AppInfo("com.audible.application", "Audible", "AUDIOBOOK"),
        AppInfo("com.google.android.apps.books", "Google Play Books", "AUDIOBOOK"),
        AppInfo("com.audiobooks.android.audiobooks", "Audiobooks.com", "AUDIOBOOK"),
        AppInfo("com.scribd.app.reader0", "Scribd", "AUDIOBOOK"),
        AppInfo("com.storytel", "Storytel", "AUDIOBOOK"),
        AppInfo("fm.libro", "Libro.fm", "AUDIOBOOK"),
        AppInfo("com.kobo.books.ereader", "Kobo Books", "AUDIOBOOK")
    )
    
    /**
     * Get all apps as a combined list (for seeding database).
     */
    fun getAllApps(): List<AppInfo> = MUSIC_APPS + BLOCKED_APPS + PODCAST_APPS + AUDIOBOOK_APPS
    
    /**
     * Get display name for a package, or format the package name if not found.
     */
    fun getDisplayName(packageName: String): String {
        return getAllApps().find { it.packageName == packageName }?.displayName
            ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}

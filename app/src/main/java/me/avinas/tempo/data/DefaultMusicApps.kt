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
        AppInfo("com.vivi.vivimusic", "Vivi Music"),      // Vivi Music (open source)
        
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
        AppInfo("com.google.android.apps.youtube.kids", "YouTube Kids", "VIDEO"),

        // Remote control / mirroring (mirrors MediaSessions, must never be tracked)
        AppInfo("org.kde.kdeconnect_tp", "KDE Connect", "OTHER")
    )
    
    /**
     * Podcast apps - tracked separately for content filtering.
     */
    val PODCAST_APPS: List<AppInfo> = listOf(
        AppInfo("com.google.android.apps.podcasts", "Google Podcasts", "PODCAST"),
        AppInfo("com.google.android.apps.magazines", "Google News", "PODCAST"),
        AppInfo("fm.player", "Player FM", "PODCAST"),
        AppInfo("au.com.shiftyjelly.pocketcasts", "Pocket Casts", "PODCAST"),
        AppInfo("com.bambuna.podcastaddict", "Podcast Addict", "PODCAST"),
        AppInfo("com.clearchannel.iheartradio.controller", "iHeartRadio", "PODCAST"),
        AppInfo("app.tunein.player", "TuneIn Radio", "PODCAST"),
        AppInfo("com.stitcher.app", "Stitcher", "PODCAST"),
        AppInfo("com.castbox.player", "Castbox", "PODCAST"),
        AppInfo("com.overcast.app", "Overcast", "PODCAST"),
        AppInfo("com.apple.android.podcasts", "Apple Podcasts", "PODCAST"),
        AppInfo("com.podcastone.mobile", "PodcastOne", "PODCAST"),
        AppInfo("com.wondery.wondery", "Wondery", "PODCAST"),
        AppInfo("com.podcasts.android", "Podcasts", "PODCAST"),
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
        AppInfo("com.libro.app", "Libro.fm (Alt)", "AUDIOBOOK"),
        AppInfo("com.kobo.books.ereader", "Kobo Books", "AUDIOBOOK"),
        AppInfo("com.nook.app", "Nook Audiobooks", "AUDIOBOOK"),
        AppInfo("com.audiobooks.androidapp", "Audiobooks", "AUDIOBOOK")
    )

    /** Package names of [PODCAST_APPS], for content filtering on the hot path. */
    val PODCAST_PACKAGES: Set<String> = PODCAST_APPS.map { it.packageName }.toSet()

    /** Package names of [AUDIOBOOK_APPS], for content filtering on the hot path. */
    val AUDIOBOOK_PACKAGES: Set<String> = AUDIOBOOK_APPS.map { it.packageName }.toSet()

    /** Package names of [BLOCKED_APPS], so the policy never linear-scans on the hot path. */
    val BLOCKED_PACKAGES: Set<String> = BLOCKED_APPS.map { it.packageName }.toSet()
    
    /**
     * Get all apps as a combined list (for seeding database).
     */
    fun getAllApps(): List<AppInfo> = MUSIC_APPS + BLOCKED_APPS + PODCAST_APPS + AUDIOBOOK_APPS
    
    /**
     * Apps whose audio is never music, regardless of what the user does.
     *
     * This is the one list that cannot be overridden. It covers messaging apps that emit voice
     * notes, dialers/voicemail, and voice recorders — anything where a "media session" is a
     * human speaking and journaling it as a song is simply wrong.
     *
     * The distinction from [BLOCKED_APPS] matters: a blocked app is a *default preference* the
     * user can reverse (YouTube is blocked by default, but someone may want their music videos
     * tracked). An entry here is a correctness guarantee, so the Manage Apps screen shows these
     * as "Not supported" with no toggle rather than offering an Unblock that would not work.
     *
     * Excluded: collaboration apps (Discord, Slack, Teams) and social video apps.
     * They do sometimes play real music, so they are left to the default-deny inbox where the
     * user can make the call.
     *
     * Never seeded into `app_preferences` — this list is enforced purely in code so it cannot
     * drift out of sync with a database row.
     */
    val NEVER_TRACK_APPS: List<AppInfo> = listOf(
        // Messaging with voice notes
        AppInfo("com.viber.voip", "Viber", "MESSAGING"),
        AppInfo("com.viber.voip2", "Viber (Dual)", "MESSAGING"),
        AppInfo("org.thoughtcrime.securesms", "Signal", "MESSAGING"),
        AppInfo("org.thoughtcrime.securesms.beta", "Signal (Beta)", "MESSAGING"),
        AppInfo("com.whatsapp", "WhatsApp", "MESSAGING"),
        AppInfo("com.whatsapp.w4b", "WhatsApp Business", "MESSAGING"),
        AppInfo("org.telegram.messenger", "Telegram", "MESSAGING"),
        AppInfo("org.telegram.messenger.web", "Telegram Web", "MESSAGING"),
        AppInfo("org.telegram.plus", "Telegram Plus", "MESSAGING"),
        AppInfo("org.thunderdog.challegram", "Telegram X", "MESSAGING"),
        AppInfo("com.facebook.orca", "Messenger", "MESSAGING"),
        AppInfo("com.google.android.apps.messaging", "Google Messages", "MESSAGING"),
        AppInfo("com.samsung.android.messaging", "Samsung Messages", "MESSAGING"),
        AppInfo("com.android.mms", "Messages", "MESSAGING"),
        AppInfo("com.android.messaging", "Messages (AOSP)", "MESSAGING"),
        AppInfo("com.textra", "Textra SMS", "MESSAGING"),
        AppInfo("com.p1.chompsms", "Chomp SMS", "MESSAGING"),
        AppInfo("jp.naver.line.android", "LINE", "MESSAGING"),
        AppInfo("com.kakao.talk", "KakaoTalk", "MESSAGING"),
        AppInfo("com.tencent.mm", "WeChat", "MESSAGING"),
        AppInfo("com.skype.raider", "Skype", "MESSAGING"),
        AppInfo("com.truecaller", "Truecaller", "MESSAGING"),

        // Dialers, calls and voicemail
        AppInfo("com.android.dialer", "Phone", "CALLS"),
        AppInfo("com.google.android.dialer", "Google Phone", "CALLS"),
        AppInfo("com.samsung.android.dialer", "Samsung Phone", "CALLS"),
        AppInfo("com.oneplus.dialer", "OnePlus Phone", "CALLS"),
        AppInfo("com.android.phone", "Phone Services", "CALLS"),
        AppInfo("com.android.server.telecom", "Telecom", "CALLS"),
        AppInfo("com.android.incallui", "Call UI", "CALLS"),
        AppInfo("com.android.voicemail", "Voicemail", "CALLS"),
        AppInfo("com.samsung.android.incallui", "Samsung Call UI", "CALLS"),

        // Voice recorders
        AppInfo("com.android.soundrecorder", "Sound Recorder", "RECORDER"),
        AppInfo("com.google.android.apps.recorder", "Recorder", "RECORDER"),
        AppInfo("com.sec.android.app.voicenote", "Voice Recorder", "RECORDER"),
        AppInfo("com.miui.voicerecorder", "Mi Voice Recorder", "RECORDER"),
        AppInfo("com.coloros.soundrecorder", "Sound Recorder", "RECORDER"),
        AppInfo("com.oppo.soundrecorder", "Sound Recorder", "RECORDER"),
    )

    /**
     * Long-tail blocklist: OEM video players, video-player clones and extra browsers.
     *
     * Enforced by [AppTrackingPolicy] but NOT seeded into `app_preferences`.
     * These are apps a user will never want tracked, so listing all ~90 of them in Manage Apps
     * would be noise; they are blocked silently rather than offered as a suggestion.
     *
     * They live here rather than in the tracking service so there is exactly one taxonomy:
     * before the default-deny move this list existed only inside MusicTrackingService, which is
     * how it drifted out of sync with the curated list used for seeding.
     */
    val EXTENDED_BLOCKED_PACKAGES: Set<String> = setOf(
        "allformat.player.videoplayer",
        "com.UCMobile.intl",
        "com.alcatel.video",
        "com.alpha.videoplayer",
        "com.amplayer.video",
        "com.android.VideoPlayer",
        "com.archos.arcmedia",
        "com.archos.mediacenter.video",
        "com.asus.gallery",
        "com.boom.hdplayer",
        "com.bsplayer.bspandroid",
        "com.bsplayer.bspandroid.free",
        "com.chrome.canary",
        "com.coloros.video",
        "com.easytech.videoplayer",
        "com.edge.videoplayer",
        "com.evoplayer.hd",
        "com.flashplayer.videoplayer",
        "com.fullhd.video.player",
        "com.google.android.apps.youtube.media",
        "com.google.android.gm",
        "com.hd.videoplayer.master",
        "com.hdvideoplayer.allformat",
        "com.hisense.video",
        "com.hmdglobal.gallery",
        "com.htc.album",
        "com.huawei.himovie",
        "com.huawei.himovie.overseas",
        "com.infinix.video",
        "com.inshot.videoplayer",
        "com.jplayer.hd",
        "com.kmplayer",
        "com.kmvideoplayer.hd",
        "com.ksmobile.cb",
        "com.lemon.videoplayer",
        "com.lenovo.videoplayer",
        "com.lge.gallery",
        "com.lge.video",
        "com.litterpeng.videoplayer",
        "com.max.video.hd",
        "com.media.masterplayer",
        "com.mediaplayer.fullhd",
        "com.motorola.MotGallery2",
        "com.motorola.cn.gallery",
        "com.mp4.hd.videoplayer",
        "com.mytech.video.player",
        "com.nokia.gallery",
        "com.nova.videoplayer",
        "com.oneplus.gallery",
        "com.opera.browser.beta",
        "com.oppo.video",
        "com.panasonic.video",
        "com.pixel.videoplayer",
        "com.player.mediaplayer.hd",
        "com.playit.videoplayer",
        "com.playtube.videoplayer",
        "com.powerful.videoplayer",
        "com.prime.hdplayer",
        "com.qwant.browser",
        "com.realme.video",
        "com.rhmsoft.playerpro",
        "com.samsung.android.service.peoplestripe",
        "com.sec.android.gallery3d",
        "com.sharp.video",
        "com.sonyericsson.album",
        "com.spark.videoplayer",
        "com.stream.videoplayer",
        "com.super.video.hd",
        "com.svplayer.hd",
        "com.tcl.gallery",
        "com.tcl.video",
        "com.tecno.video",
        "com.transsion.video",
        "com.ufyl.videoplayer",
        "com.ultra.hdplayer",
        "com.videoplayer.hdplayer2020",
        "com.videoplayer.masterpro",
        "com.videoplayer.vivoplayer",
        "com.vidma.videoplayer",
        "com.vidx.player",
        "com.vlc.remote",
        "com.xplayer.hd",
        "com.zte.video",
        "hd.videoplayer.allformat",
        "org.chromium.webview_shell",
        "org.dolphin.browser",
        "org.torproject.torbrowser",
        "uplayer.video.player",
        "video.player.allformat.hd.player",
        "video.player.hd.videoplayer",
        "video.player.videoplayer",
        "videoplayer.musicplayer.mp4",
        "videoplayer.video.player.hd",
        "videoplayer.videoplayer",
    )

    /** Package names of [NEVER_TRACK_APPS], for fast lookup on the tracking hot path. */
    val NEVER_TRACK_PACKAGES: Set<String> = NEVER_TRACK_APPS.map { it.packageName }.toSet()

    /**
     * Collections that are tracked but content-filtered (podcasts/audiobooks) or blocked by
     * default (video, browsers). Combined with [MUSIC_APPS] these form the curated allowlist:
     * an app on any of them is tracked without the user having to add it.
     */
    val CURATED_TRACKABLE_PACKAGES: Set<String> =
        (MUSIC_APPS + PODCAST_APPS + AUDIOBOOK_APPS).map { it.packageName }.toSet()

    /**
     * Package name to display name, built once.
     *
     * [getDisplayName] is called while building the Manage Apps list and its detected-apps inbox,
     * so the previous implementation — which allocated a fresh combined list via [getAllApps] and
     * then linear-scanned it on every call — was doing needless work per row.
     */
    private val displayNameIndex: Map<String, String> by lazy {
        (getAllApps() + NEVER_TRACK_APPS).associate { it.packageName to it.displayName }
    }

    /**
     * Get display name for a package, or format the package name if not found.
     */
    fun getDisplayName(packageName: String): String =
        displayNameIndex[packageName]
            ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}

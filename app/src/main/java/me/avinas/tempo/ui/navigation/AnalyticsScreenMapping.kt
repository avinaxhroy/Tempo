package me.avinas.tempo.ui.navigation

import me.avinas.tempo.data.analytics.AnalyticsScreen

/**
 * Maps a navigation route to the closed [AnalyticsScreen] vocabulary.
 *
 * Kept as a pure function over route strings so it is unit-testable and so no screen can
 * invent its own event name. Routes carry arguments (e.g. `song_details/{trackId}`), so
 * matching strips the argument segment before comparing.
 */
internal fun routeToAnalyticsScreen(route: String?): AnalyticsScreen {
    if (route.isNullOrBlank()) return AnalyticsScreen.UNKNOWN
    val base = route.substringBefore('?').substringBefore('/')
    return when (base) {
        "home" -> AnalyticsScreen.HOME
        "stats" -> AnalyticsScreen.STATS
        "history" -> AnalyticsScreen.HISTORY
        "settings" -> AnalyticsScreen.SETTINGS
        "spotlight" -> AnalyticsScreen.SPOTLIGHT
        "song_details" -> AnalyticsScreen.SONG_DETAILS
        "artist_details" -> AnalyticsScreen.ARTIST_DETAILS
        "album_details" -> AnalyticsScreen.ALBUM_DETAILS
        "backup_restore" -> AnalyticsScreen.BACKUP_RESTORE
        "supported_apps" -> AnalyticsScreen.SUPPORTED_APPS
        "background_protection" -> AnalyticsScreen.BACKGROUND_PROTECTION
        "lastfm_import" -> AnalyticsScreen.LASTFM_IMPORT
        "spotify_json_import" -> AnalyticsScreen.SPOTIFY_JSON_IMPORT
        "deezer_import" -> AnalyticsScreen.DEEZER_IMPORT
        "youtube_music_import" -> AnalyticsScreen.YOUTUBE_MUSIC_IMPORT
        "desktop_link" -> AnalyticsScreen.DESKTOP_LINK
        "enrichment_report" -> AnalyticsScreen.ENRICHMENT_REPORT
        "share_canvas" -> AnalyticsScreen.SHARE_CANVAS
        "profile" -> AnalyticsScreen.PROFILE
        "your_data" -> AnalyticsScreen.YOUR_DATA
        else -> AnalyticsScreen.UNKNOWN
    }
}

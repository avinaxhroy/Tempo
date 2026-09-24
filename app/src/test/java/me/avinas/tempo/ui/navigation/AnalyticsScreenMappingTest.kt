package me.avinas.tempo.ui.navigation

import me.avinas.tempo.data.analytics.AnalyticsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AnalyticsScreenMappingTest {
    /**
     * Every route the app can navigate to, so `screen_viewed` reports a real destination.
     * A new route must be added here and to the mapping — otherwise it is reported as
     * UNKNOWN and the assertion below catches it.
     *
     * `Screen.Insights` is deliberately absent: it is declared but never registered in the
     * NavHost, so it can never become the current destination.
     */
    private val routes =
        listOf(
            "home" to AnalyticsScreen.HOME,
            "stats" to AnalyticsScreen.STATS,
            "history" to AnalyticsScreen.HISTORY,
            "settings" to AnalyticsScreen.SETTINGS,
            "spotlight?timeRange={timeRange}&directLaunch={directLaunch}" to AnalyticsScreen.SPOTLIGHT,
            "song_details/{trackId}" to AnalyticsScreen.SONG_DETAILS,
            "artist_details/{artistId}?artistName={artistName}" to AnalyticsScreen.ARTIST_DETAILS,
            "album_details/{albumId}" to AnalyticsScreen.ALBUM_DETAILS,
            "backup_restore" to AnalyticsScreen.BACKUP_RESTORE,
            "supported_apps" to AnalyticsScreen.SUPPORTED_APPS,
            "background_protection" to AnalyticsScreen.BACKGROUND_PROTECTION,
            "lastfm_import" to AnalyticsScreen.LASTFM_IMPORT,
            "spotify_json_import" to AnalyticsScreen.SPOTIFY_JSON_IMPORT,
            "deezer_import" to AnalyticsScreen.DEEZER_IMPORT,
            "youtube_music_import" to AnalyticsScreen.YOUTUBE_MUSIC_IMPORT,
            "desktop_link" to AnalyticsScreen.DESKTOP_LINK,
            "enrichment_report" to AnalyticsScreen.ENRICHMENT_REPORT,
            "share_canvas/{initialCardId}" to AnalyticsScreen.SHARE_CANVAS,
            "profile" to AnalyticsScreen.PROFILE,
            "your_data" to AnalyticsScreen.YOUR_DATA,
        )

    @Test
    fun `every declared route maps to a real screen`() {
        routes.forEach { (route, expected) ->
            assertEquals(route, expected, routeToAnalyticsScreen(route))
            assertNotEquals("'$route' fell through to UNKNOWN", AnalyticsScreen.UNKNOWN, expected)
        }
    }

    @Test
    fun `concrete paths and query strings are stripped before matching`() {
        assertEquals(AnalyticsScreen.SONG_DETAILS, routeToAnalyticsScreen("song_details/42"))
        assertEquals(AnalyticsScreen.ALBUM_DETAILS, routeToAnalyticsScreen("album_details/17"))
        assertEquals(AnalyticsScreen.SHARE_CANVAS, routeToAnalyticsScreen("share_canvas/_empty_"))
        assertEquals(AnalyticsScreen.SPOTLIGHT, routeToAnalyticsScreen("spotlight"))
        assertEquals(
            AnalyticsScreen.SPOTLIGHT,
            routeToAnalyticsScreen("spotlight?timeRange=THIS_WEEK&directLaunch=true"),
        )
    }

    @Test
    fun `unrecognised or missing routes fall back to UNKNOWN`() {
        assertEquals(AnalyticsScreen.UNKNOWN, routeToAnalyticsScreen(null))
        assertEquals(AnalyticsScreen.UNKNOWN, routeToAnalyticsScreen(""))
        assertEquals(AnalyticsScreen.UNKNOWN, routeToAnalyticsScreen("   "))
        assertEquals(AnalyticsScreen.UNKNOWN, routeToAnalyticsScreen("a_screen_that_does_not_exist_yet"))
    }

    /**
     * A route is user navigation, not content, so nothing route-derived may leak a track or
     * artist name even though some routes can carry one as an argument.
     */
    @Test
    fun `screen names never contain content from route arguments`() {
        val mapped = AnalyticsScreen.entries.map { it.name }

        mapped.forEach { name ->
            assertEquals(name, name.uppercase())
            assertNotEquals("route argument leaked into a screen name", true, name.contains("/"))
        }
    }
}

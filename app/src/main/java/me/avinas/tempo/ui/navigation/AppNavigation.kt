package me.avinas.tempo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import me.avinas.tempo.ui.home.HomeScreen
import me.avinas.tempo.ui.settings.SettingsScreen
import me.avinas.tempo.ui.settings.SupportedAppsScreen
import me.avinas.tempo.ui.settings.BackupRestoreScreen
import me.avinas.tempo.ui.spotlight.SpotlightScreen
import me.avinas.tempo.ui.components.DeepOceanBackground
import kotlinx.coroutines.launch
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Stats : Screen("stats")
    object History : Screen("history")
    object Settings : Screen("settings")
    object Spotlight : Screen("spotlight?timeRange={timeRange}") {
        fun createRoute(timeRange: String? = null): String {
            return if (timeRange != null) {
                "spotlight?timeRange=$timeRange"
            } else {
                "spotlight"
            }
        }
    }
    object SongDetails : Screen("song_details/{trackId}") {
        fun createRoute(trackId: Long) = "song_details/$trackId"
    }
    // Artist details now supports both ID-based (preferred) and name-based (fallback) navigation
    object ArtistDetails : Screen("artist_details/{artistId}?artistName={artistName}") {
        fun createRouteById(artistId: Long) = "artist_details/$artistId"
        fun createRouteByName(artistName: String) = "artist_details/0?artistName=${java.net.URLEncoder.encode(artistName, "UTF-8")}"
        // Legacy method for backwards compatibility
        fun createRoute(artistName: String) = createRouteByName(artistName)
    }
    object AlbumDetails : Screen("album_details/{albumId}") {
        fun createRoute(albumId: Long) = "album_details/$albumId"
    }
    object Insights : Screen("insights")
    data object BackupRestore : Screen("backup_restore")
    data object SupportedApps : Screen("supported_apps")
    object ShareCanvas : Screen("share_canvas/{initialCardId}") {
        fun createRoute(initialCardId: String) = "share_canvas/$initialCardId"
        fun createRouteEmpty() = "share_canvas/_empty_"
    }
}

@Composable
fun AppNavigation(
    walkthroughController: me.avinas.tempo.ui.components.WalkthroughController,
    onResetToOnboarding: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentDestination = navBackStackEntry?.destination

    // Dismiss any active walkthrough when navigating to a new screen
    androidx.compose.runtime.LaunchedEffect(currentRoute) {
        if (currentRoute != null) {
            walkthroughController.dismissCurrent()
        }
    }

    val showBottomBar = currentRoute in listOf(
        Screen.Home.route,
        Screen.Stats.route,
        Screen.History.route
    )

    me.avinas.tempo.ui.components.DeepOceanBackground {
        me.avinas.tempo.ui.components.WalkthroughOverlay(
            controller = walkthroughController
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Screen.Home.route) {
                        me.avinas.tempo.ui.home.HomeScreen(
                            onNavigateToStats = {
                                navController.navigate(Screen.Stats.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onNavigateToHistory = {
                                navController.navigate(Screen.History.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                            onNavigateToTrack = { trackId -> navController.navigate(Screen.SongDetails.createRoute(trackId)) },
                            onNavigateToSpotlight = { timeRange ->
                                val route = if (timeRange != null) {
                                    Screen.Spotlight.createRoute(timeRange.name)
                                } else {
                                    Screen.Spotlight.createRoute()
                                }
                                navController.navigate(route)
                            },
                            onNavigateToSupportedApps = { navController.navigate(Screen.SupportedApps.route) } // Added
                        )
                    }

                    composable(Screen.Stats.route) {
                        val scope = rememberCoroutineScope()
                        val navigationViewModel: me.avinas.tempo.ui.navigation.NavigationViewModel = hiltViewModel()

                        me.avinas.tempo.ui.stats.StatsScreen(
                            onNavigateToTrack = { trackId -> navController.navigate(Screen.SongDetails.createRoute(trackId)) },
                            onNavigateToArtist = { artistIdentifier ->
                                if (artistIdentifier.startsWith("id:")) {
                                    val artistId = artistIdentifier.removePrefix("id:").toLongOrNull()
                                    if (artistId != null && artistId > 0) {
                                        navController.navigate(Screen.ArtistDetails.createRouteById(artistId))
                                    }
                                } else {
                                    navController.navigate(Screen.ArtistDetails.createRouteByName(artistIdentifier))
                                }
                            },
                            onNavigateToAlbum = { albumInfo ->
                                 scope.launch {
                                    val parts = albumInfo.split("|")
                                    if (parts.size == 2) {
                                        val albumId = navigationViewModel.getAlbumIdByTitleAndArtist(parts[0], parts[1])
                                        if (albumId != null) {
                                            navController.navigate(Screen.AlbumDetails.createRoute(albumId))
                                        }
                                    }
                                }
                            },
                            onNavigateToSupportedApps = { navController.navigate(Screen.SupportedApps.route) }
                        )
                    }

                    composable(Screen.History.route) {
                        me.avinas.tempo.ui.history.HistoryScreen(
                            onNavigateToTrack = { trackId -> navController.navigate(Screen.SongDetails.createRoute(trackId)) }
                        )
                    }

                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToOnboarding = onResetToOnboarding,
                            onNavigateToBackup = { navController.navigate(Screen.BackupRestore.route) },
                            onNavigateToSupportedApps = { navController.navigate(Screen.SupportedApps.route) }
                        )
                    }

                    composable(Screen.BackupRestore.route) {
                        BackupRestoreScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.SupportedApps.route) {
                        SupportedAppsScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        route = Screen.Spotlight.route,
                        arguments = listOf(
                            androidx.navigation.navArgument("timeRange") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { backStackEntry ->
                        val timeRangeString = backStackEntry.arguments?.getString("timeRange")
                        val initialTimeRange = when (timeRangeString) {
                            "THIS_MONTH" -> me.avinas.tempo.data.stats.TimeRange.THIS_MONTH
                            "THIS_YEAR" -> me.avinas.tempo.data.stats.TimeRange.THIS_YEAR
                            else -> null
                        }
                        
                        me.avinas.tempo.ui.spotlight.SpotlightScreen(
                            navController = navController,
                            initialTimeRange = initialTimeRange
                        )
                    }

                    composable(
                        route = Screen.SongDetails.route,
                        arguments = listOf(androidx.navigation.navArgument("trackId") { type = androidx.navigation.NavType.LongType })
                    ) { backStackEntry ->
                        val trackId = backStackEntry.arguments?.getLong("trackId") ?: return@composable
                        me.avinas.tempo.ui.details.SongDetailsScreen(
                            trackId = trackId,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        route = Screen.ArtistDetails.route,
                        arguments = listOf(
                            androidx.navigation.navArgument("artistId") {
                                type = androidx.navigation.NavType.LongType
                                defaultValue = 0L
                            },
                            androidx.navigation.navArgument("artistName") {
                                type = androidx.navigation.NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { backStackEntry ->
                        val artistId = backStackEntry.arguments?.getLong("artistId") ?: 0L
                        val artistName = backStackEntry.arguments?.getString("artistName")?.let {
                            java.net.URLDecoder.decode(it, "UTF-8")
                        }

                        if (artistId > 0) {
                            me.avinas.tempo.ui.details.ArtistDetailsScreen(
                                artistId = artistId,
                                artistName = null,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToSong = { trackId -> navController.navigate(Screen.SongDetails.createRoute(trackId)) }
                            )
                        } else if (artistName != null) {
                            me.avinas.tempo.ui.details.ArtistDetailsScreen(
                                artistId = null,
                                artistName = artistName,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToSong = { trackId -> navController.navigate(Screen.SongDetails.createRoute(trackId)) }
                            )
                        } else {
                            navController.popBackStack()
                        }
                    }

                    composable(
                        route = Screen.AlbumDetails.route,
                        arguments = listOf(androidx.navigation.navArgument("albumId") { type = androidx.navigation.NavType.LongType })
                    ) { backStackEntry ->
                        val albumId = backStackEntry.arguments?.getLong("albumId") ?: return@composable
                        me.avinas.tempo.ui.details.AlbumDetailsScreen(
                            albumId = albumId,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToSong = { trackId -> navController.navigate(Screen.SongDetails.createRoute(trackId)) }
                        )
                    }

                    composable(
                        route = Screen.ShareCanvas.route,
                        arguments = listOf(
                            androidx.navigation.navArgument("initialCardId") {
                                type = androidx.navigation.NavType.StringType
                            }
                        )
                    ) { backStackEntry ->
                        val initialCardId = backStackEntry.arguments?.getString("initialCardId")
                        me.avinas.tempo.ui.spotlight.canvas.ShareCanvasScreen(
                            initialCardId = initialCardId,
                            onClose = { navController.popBackStack() }
                        )
                    }
                }

                if (showBottomBar) {
                    me.avinas.tempo.ui.navigation.TempoBottomNavigation(
                        currentDestination = currentDestination,
                        onNavigateToHome = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToStats = {
                            navController.navigate(Screen.Stats.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToHistory = {
                            navController.navigate(Screen.History.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
}


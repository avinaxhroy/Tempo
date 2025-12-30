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
    object Spotlight : Screen("spotlight")
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
}

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    onResetToOnboarding: (() -> Unit)? = null,
    navigationViewModel: NavigationViewModel = hiltViewModel()
) {

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.route in listOf(
        Screen.Home.route,
        Screen.Stats.route,
        Screen.History.route
    )

    Box(modifier = Modifier.fillMaxSize().background(me.avinas.tempo.ui.theme.TempoDarkBackground)) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = modifier,
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                bottomBar = {}
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.padding(innerPadding)
                ) {
                    composable(Screen.Home.route) {
                        HomeScreen(
                            onNavigateToStats = { navController.navigate(Screen.Stats.route) },
                            onNavigateToHistory = { navController.navigate(Screen.History.route) },
                            onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                            onNavigateToTrack = { trackId -> navController.navigate(Screen.SongDetails.createRoute(trackId)) },
                            onNavigateToSpotlight = { navController.navigate(Screen.Spotlight.route) }
                        )
                    }
                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToOnboarding = onResetToOnboarding
                        )
                    }
                    composable(Screen.Spotlight.route) {
                        me.avinas.tempo.ui.spotlight.SpotlightScreen(
                            navController = navController
                        )
                    }
                    composable(Screen.Stats.route) {
                        val scope = rememberCoroutineScope()
                        me.avinas.tempo.ui.stats.StatsScreen(
                            onNavigateToTrack = { trackId -> navController.navigate(Screen.SongDetails.createRoute(trackId)) },
                            onNavigateToArtist = { artistIdentifier -> 
                                // artistIdentifier can be "id:123" for ID or just artist name for name-based
                                if (artistIdentifier.startsWith("id:")) {
                                    val artistId = artistIdentifier.removePrefix("id:").toLongOrNull()
                                    if (artistId != null && artistId > 0) {
                                        navController.navigate(Screen.ArtistDetails.createRouteById(artistId))
                                    }
                                } else {
                                    // Fallback to name-based navigation
                                    navController.navigate(Screen.ArtistDetails.createRouteByName(artistIdentifier))
                                }
                            },
                            onNavigateToAlbum = { albumInfo -> 
                                // albumInfo format: "albumTitle|artistName"
                                scope.launch {
                                    val parts = albumInfo.split("|")
                                    if (parts.size == 2) {
                                        val albumId = navigationViewModel.getAlbumIdByTitleAndArtist(parts[0], parts[1])
                                        if (albumId != null) {
                                            navController.navigate(Screen.AlbumDetails.createRoute(albumId))
                                        }
                                    }
                                }
                            }
                        )
                    }
                    composable(Screen.History.route) {
                        me.avinas.tempo.ui.history.HistoryScreen(
                            onNavigateToTrack = { trackId -> navController.navigate(Screen.SongDetails.createRoute(trackId)) }
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
                        
                        // Prefer ID-based navigation, fall back to name-based
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
                            // Invalid state - no artist ID or name
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
                }
            }

            if (showBottomBar) {
                TempoBottomNavigation(
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


package me.avinas.tempo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.SettingsSectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportedAppsScreen(
    onNavigateBack: () -> Unit
) {
    val supportedApps = listOf(
        "YouTube Music",
        "Spotify",
        "Apple Music",
        "Amazon Music",
        "SoundCloud",
        "Deezer",
        "Pandora",
        "JioSaavn",
        "Gaana",
        "Wynk Music",
        "Hungama Music",
        "Samsung Music",
        "Mi Music",
        "Tidal",
        "Qobuz",
        "Resso",
        "Audiomack",
        "Trebel",
        "Poweramp",
        "Musicolet",
        "BlackPlayer",
        "Nugs.net",
        "OuterTune",
        "InnerTune",
        "ViMusic",
        "Spotube",
        "BlackHole",
        "Harmony Music",
        "RiMusic",
        "Namida",
        "Metrolist",
        "Musify",
        "BloomeeTunes",
        "SimpMusic",
        "Pixel Player",
        "Gramophone",
        "Phonograph Plus",
        "Auxio",
        "Muzza",
        "Echo",
        "SpotiFlyer",
        "YMusic",
        "NewPipe",
        "Pulsar",
        "Neutron Player",
        "GoneMad",
        "Retro Music Player",
        "Oto Music",
        "Shuttle+",
        "Stellio",
        "Frolomuse",
        "Omnia",
        "Yandex Music"
    )

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Supported Apps", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        DeepOceanBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                SettingsSectionHeader("Music Players")
                
                GlassCard(
                    contentPadding = PaddingValues(16.dp),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        supportedApps.sorted().forEach { appName ->
                            Text(
                                text = appName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            if (appName != supportedApps.sorted().last()) {
                                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "Don't see your favorite app? Contact us to request support.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

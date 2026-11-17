package me.avinas.tempo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.avinas.tempo.ui.home.components.SpotlightStoryCard
import me.avinas.tempo.ui.spotlight.SpotlightStoryHeroCard
import me.avinas.tempo.ui.theme.TempoTheme

/**
 * Design-time previews for the neo-skeuomorphic Spotlight entry points. Open this file
 * in Android Studio and use the Compose preview pane; no Gradle run is needed.
 */
@Preview(name = "Home card", showBackground = true, backgroundColor = 0xFF050808, widthDp = 380)
@Composable
private fun HomeSpotlightCardPreview() {
    TempoTheme {
        Column(Modifier.padding(14.dp)) {
            SpotlightStoryCard(onClick = {}, onRingClick = {}, albumArtUrl = null)
        }
    }
}

@Preview(name = "Hero card", showBackground = true, backgroundColor = 0xFF050808, widthDp = 380)
@Composable
private fun SpotlightHeroPreview() {
    TempoTheme {
        Column(Modifier.padding(14.dp)) {
            SpotlightStoryHeroCard(
                onClick = {},
                buttonText = "View the story for Nov 9 — 15",
                isLocked = false,
                lockMessage = "",
                leadArtUrl = null,
                storyLoading = false
            )
        }
    }
}

@Preview(name = "Hero card locked", showBackground = true, backgroundColor = 0xFF050808, widthDp = 380)
@Composable
private fun SpotlightHeroLockedPreview() {
    TempoTheme {
        Column(Modifier.padding(14.dp)) {
            SpotlightStoryHeroCard(
                onClick = {},
                buttonText = "View the story for Nov 9 — 15",
                isLocked = true,
                lockMessage = "Unlocks at the end of the period",
                leadArtUrl = null,
                storyLoading = false
            )
        }
    }
}

/** Swatches for judging the moulded face, the extruded base and the disc on their own. */
@Preview(name = "Parts", showBackground = true, backgroundColor = 0xFF050808, widthDp = 380)
@Composable
private fun NeoPartsPreview() {
    TempoTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NeoSpotlightSurface(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Text("Enabled surface", color = Color(0xFFE6EAEA))
            }
            NeoSpotlightSurface(modifier = Modifier.fillMaxWidth(), enabled = false) {
                androidx.compose.material3.Text("Disabled surface", color = Color(0xFF9AA2A2))
            }
        }
    }
}

/** Full-bleed strip at the real list width, clipped to the card radius. */
@Preview(name = "Home card on list", showBackground = true, backgroundColor = 0xFF050808, widthDp = 411)
@Composable
private fun HomeSpotlightCardFullWidthPreview() {
    TempoTheme {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Transparent)
        ) {
            SpotlightStoryCard(
                onClick = {},
                onRingClick = {},
                albumArtUrl = null,
                storyAvailable = true,
                storyViewed = true
            )
        }
    }
}

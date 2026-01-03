package me.avinas.tempo.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.utils.TempoCopyEngine

@Composable
fun QuickStatsRow(
    topArtistName: String?,
    topArtistImage: String?,
    topTrackName: String?,
    topTrackImage: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Artist Item
        GlassCard(
            modifier = Modifier.weight(1f),
            backgroundColor = me.avinas.tempo.ui.theme.NeonRed.copy(alpha = 0.1f),
            variant = GlassCardVariant.LowProminence,
            contentPadding = PaddingValues(12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                 CachedAsyncImage(
                    imageUrl = topArtistImage,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.05f)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Top Artist",
                    style = MaterialTheme.typography.labelSmall,
                    color = me.avinas.tempo.ui.theme.NeonRed.copy(alpha = 0.8f),
                    maxLines = 1
                )
                Text(
                    text = topArtistName ?: "-",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Top Track Item
        GlassCard(
            modifier = Modifier.weight(1f),
            backgroundColor = me.avinas.tempo.ui.theme.ElectricBlue.copy(alpha = 0.1f),
            variant = GlassCardVariant.LowProminence,
            contentPadding = PaddingValues(12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                CachedAsyncImage(
                    imageUrl = topTrackImage,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.05f)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "On Repeat",
                    style = MaterialTheme.typography.labelSmall,
                    color = me.avinas.tempo.ui.theme.ElectricBlue.copy(alpha = 0.8f),
                    maxLines = 1
                )
                Text(
                    text = topTrackName ?: "-",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

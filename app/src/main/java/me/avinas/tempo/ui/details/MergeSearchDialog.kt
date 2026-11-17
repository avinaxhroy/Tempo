package me.avinas.tempo.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import me.avinas.tempo.ui.theme.*
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.ui.components.TempoDialogButtonRow
import me.avinas.tempo.ui.components.TempoDialogShape
import me.avinas.tempo.ui.components.CachedAsyncImage
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun MergeSearchDialog(
    sourceTrackId: Long,
    onDismiss: () -> Unit,
    onTrackSelected: (Track) -> Unit,
    viewModel: MergeTrackViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Set source track ID once
    LaunchedEffect(sourceTrackId) {
        viewModel.setSourceTrackId(sourceTrackId)
    }

    // Handle merge completion
    LaunchedEffect(uiState.mergeStatus) {
        if (uiState.mergeStatus is MergeStatus.Success) {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(TempoDialogShape.shape)
                .background(TempoSurfaceDialog)
                .border(1.dp, GlassBorderSoft, TempoDialogShape.shape)
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Merge with...",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(GlassFrostSoft)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info text
            Text(
                text = "Search for the correct track to merge into. All listening history will be combined.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Search Input
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search for the correct version...", color = TextTertiary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextTertiary) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = TempoPrimary,
                    focusedBorderColor = TempoPrimary,
                    unfocusedBorderColor = TextQuaternary
                )
            )

            Spacer(modifier = Modifier.height(14.dp))

            when {
                uiState.isSearching -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TempoPrimary, trackColor = GlassFrostSoft, strokeWidth = 2.dp)
                    }
                }
                uiState.searchResults.isEmpty() && uiState.query.length >= 2 -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("No tracks found", color = TextTertiary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                uiState.searchResults.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Type to search for tracks",
                            color = TextTertiary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            items = uiState.searchResults,
                            key = { track -> track.id }
                        ) { track ->
                            TrackSearchItem(track = track, onClick = {
                                viewModel.selectTrackForMerge(track)
                            })
                        }
                    }
                }
            }

            // Confirmation panel for pending merge
            if (uiState.pendingMergeTarget != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(TempoDarkSurfaceSunken)
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Confirm Merge",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Merge into \"${uiState.pendingMergeTarget!!.title}\" by ${uiState.pendingMergeTarget!!.artist}?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "This action cannot be undone. All listening history will be combined.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    TempoDialogButtonRow(
                        primaryText = "Merge",
                        onPrimary = { viewModel.confirmMerge() },
                        secondaryText = "Cancel",
                        onSecondary = { viewModel.cancelMerge() }
                    )
                }
            }

            // Error message
            if (uiState.mergeStatus is MergeStatus.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = (uiState.mergeStatus as MergeStatus.Error).message,
                    color = TempoError,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (uiState.mergeStatus is MergeStatus.Processing) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    color = TempoPrimary,
                    trackColor = GlassFrostSoft
                )
            }
        }
    }
}

@Composable
fun TrackSearchItem(track: Track, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlassFrostSoft)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track artwork or placeholder
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GlassFrostMedium),
            contentAlignment = Alignment.Center
        ) {
            if (!track.albumArtUrl.isNullOrBlank()) {
                CachedAsyncImage(
                    imageUrl = track.albumArtUrl,
                    contentDescription = track.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetSizeDp = 40,
                    error = {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.data.local.dao.AlbumSearchResult
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.TempoDialogButtonRow
import me.avinas.tempo.ui.components.TempoDialogShape
import me.avinas.tempo.ui.theme.*

/**
 * Dialog for searching and selecting an album to merge into.
 */
@Composable
fun AlbumMergeSearchDialog(
    sourceAlbumId: Long,
    sourceAlbumTitle: String,
    sourceArtistId: Long,
    sourceArtistName: String,
    onDismiss: () -> Unit,
    onMergeComplete: (targetAlbumId: Long) -> Unit,
    viewModel: MergeAlbumViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sourceAlbumId, sourceArtistId) {
        viewModel.setSourceAlbum(sourceAlbumId, sourceArtistId)
    }

    LaunchedEffect(uiState.mergeStatus) {
        val status = uiState.mergeStatus
        if (status is AlbumMergeStatus.Success) {
            onMergeComplete(status.targetAlbumId)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.details_merge_album),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.merge_merging, sourceAlbumTitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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

            Text(
                text = stringResource(R.string.merge_album_search),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Search Input
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = stringResource(R.string.merge_album_search_placeholder),
                        color = TextTertiary
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextTertiary)
                },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
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

            // Content Area
            when {
                uiState.isSearching -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = TempoPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                uiState.query.trim().length >= 2 -> {
                    if (uiState.searchResults.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.merge_album_no_results),
                                color = TextTertiary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                items = uiState.searchResults,
                                key = { it.id }
                            ) { album ->
                                AlbumSearchItem(
                                    album = album,
                                    isSelected = uiState.pendingMergeTarget?.id == album.id,
                                    onClick = { viewModel.selectAlbumForMerge(album) }
                                )
                            }
                        }
                    }
                }

                uiState.artistAlbums.isNotEmpty() -> {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.merge_album_other_by_artist, sourceArtistName),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextTertiary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                items = uiState.artistAlbums,
                                key = { it.id }
                            ) { album ->
                                AlbumSearchItem(
                                    album = album,
                                    isSelected = uiState.pendingMergeTarget?.id == album.id,
                                    onClick = { viewModel.selectAlbumForMerge(album) }
                                )
                            }
                        }
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.merge_search_albums),
                            color = TextTertiary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Confirmation panel for pending merge
            if (uiState.pendingMergeTarget != null) {
                val target = uiState.pendingMergeTarget!!
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(TempoDarkSurfaceSunken)
                        .border(1.dp, GlassBorderSoft, RoundedCornerShape(14.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.merge_confirm),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.merge_album_into, sourceAlbumTitle, target.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.merge_cannot_undo),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    TempoDialogButtonRow(
                        primaryText = stringResource(R.string.details_edit_title_merge_button),
                        onPrimary = { viewModel.confirmMerge() },
                        secondaryText = stringResource(R.string.common_cancel),
                        onSecondary = { viewModel.cancelMerge() }
                    )
                }
            }

            // Error message
            if (uiState.mergeStatus is AlbumMergeStatus.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = (uiState.mergeStatus as AlbumMergeStatus.Error).message,
                    color = TempoError,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Processing indicator
            if (uiState.mergeStatus is AlbumMergeStatus.Processing) {
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                        color = TempoPrimary,
                        trackColor = GlassFrostSoft
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.merge_merging_albums),
                        color = TextTertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumSearchItem(
    album: AlbumSearchResult,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) TempoPrimary.copy(alpha = 0.15f) else GlassFrostSoft
    val borderColor = if (isSelected) TempoPrimary.copy(alpha = 0.4f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album artwork
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GlassFrostMedium),
            contentAlignment = Alignment.Center
        ) {
            if (!album.artworkUrl.isNullOrBlank()) {
                CachedAsyncImage(
                    imageUrl = album.artworkUrl,
                    contentDescription = album.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetSizeDp = 44,
                    error = {
                        Icon(
                            imageVector = Icons.Rounded.Album,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Album,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val subtitleParts = buildList {
                add(album.artistName)
                album.releaseYear?.let { add(it.toString()) }
                album.releaseType?.takeIf { it.isNotBlank() }?.let { add(it) }
            }

            Text(
                text = subtitleParts.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

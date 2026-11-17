package me.avinas.tempo.ui.details

/* Tempo · AlbumDetails · Editorial album listening profile.
 * Shares the SongDetails design language: art-as-atmosphere background,
 * collapsing header title, frosted top-bar actions, a conditioned
 * dominant-color accent and the Obsidian stat masthead — extended with
 * the album's track sheet and inline edit mode.
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.stats.AlbumDetails
import me.avinas.tempo.data.stats.TrackWithStats
import me.avinas.tempo.ui.components.AlbumArtImage
import me.avinas.tempo.ui.components.AlbumShareCard
import me.avinas.tempo.ui.components.ArtAtmosphereLayer
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.TempoDialogShape
import me.avinas.tempo.ui.theme.*
import me.avinas.tempo.ui.components.TempoDropdownMenu
import me.avinas.tempo.ui.components.TempoDropdownMenuItem
import me.avinas.tempo.ui.components.TempoDialogSurface
import me.avinas.tempo.ui.components.TempoDialogIcon
import me.avinas.tempo.ui.components.TempoDialogTitle
import me.avinas.tempo.ui.components.TempoDialogBody
import me.avinas.tempo.ui.components.TempoDialogDangerButton
import me.avinas.tempo.ui.components.TempoDialogSecondaryButton
import me.avinas.tempo.ui.components.TempoIcons
import me.avinas.tempo.ui.components.share.SingleCardShareSheet
import java.util.Locale

@Composable
fun AlbumDetailsScreen(
    albumId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    onNavigateToArtist: (Long) -> Unit = {},
    onNavigateToAlbum: (Long) -> Unit = {},
    viewModel: AlbumDetailsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val albumDetails = uiState.albumDetails

    when {
        uiState.isLoading -> AlbumDetailsLoadingSkeleton()

        albumDetails != null -> AlbumDetailsContent(
            albumDetails = albumDetails,
            isEditMode = uiState.isEditMode,
            onNavigateBack = onNavigateBack,
            onNavigateToSong = onNavigateToSong,
            onNavigateToArtist = onNavigateToArtist,
            onNavigateToAlbum = onNavigateToAlbum,
            onToggleEdit = viewModel::toggleEditMode,
            onAddClick = viewModel::openAddDialog,
            onRemoveTrack = viewModel::requestRemove,
        )

        else -> DeepOceanBackground {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Album,
                    contentDescription = null,
                    tint = TempoError.copy(alpha = 0.7f),
                    modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = uiState.error ?: stringResource(R.string.album_not_found),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedButton(
                    onClick = viewModel::refresh,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(0.8.dp, GlassBorderMedium),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                ) {
                    Text(stringResource(R.string.common_retry).uppercase(Locale.getDefault()))
                }
            }
        }
    }

    if (uiState.addDialogVisible) {
        AddTrackToAlbumDialog(
            query = uiState.addQuery,
            results = uiState.addResults,
            isSearching = uiState.isSearching,
            artistName = albumDetails?.artistName.orEmpty(),
            onQueryChange = viewModel::onAddQueryChange,
            onTrackSelected = viewModel::addTrackToAlbum,
            onDismiss = viewModel::closeAddDialog,
        )
    }

    uiState.pendingRemove?.let { track ->
        RemoveFromAlbumDialog(
            trackTitle = track.track.title,
            onConfirm = viewModel::confirmRemove,
            onDismiss = viewModel::cancelRemove,
        )
    }
}

@Composable
private fun AlbumDetailsLoadingSkeleton() {
    DeepOceanBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SkeletonBlock(modifier = Modifier.size(232.dp), cornerRadius = 22.dp)
            Spacer(modifier = Modifier.height(24.dp))
            SkeletonBlock(modifier = Modifier.size(180.dp, 22.dp), cornerRadius = 8.dp)
            Spacer(modifier = Modifier.height(12.dp))
            SkeletonBlock(modifier = Modifier.size(110.dp, 14.dp), cornerRadius = 7.dp)
            Spacer(modifier = Modifier.height(30.dp))
            SkeletonBlock(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(152.dp),
                cornerRadius = 22.dp,
            )
        }
    }
}

@Composable
private fun AlbumDetailsContent(
    albumDetails: AlbumDetails,
    isEditMode: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    onNavigateToArtist: (Long) -> Unit,
    onNavigateToAlbum: (Long) -> Unit,
    onToggleEdit: () -> Unit,
    onAddClick: () -> Unit,
    onRemoveTrack: (TrackWithStats) -> Unit,
) {
    var dominantColor by remember { mutableStateOf<Color>(TempoPrimary) }
    var showMenu by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }

    // Collapsed header title appears when the hero title itself scrolls under
    // the top bar — anchored to its live position, not a scroll-pixel guess.
    val density = LocalDensity.current
    val headerBottomPx = with(density) {
        (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp).toPx()
    }
    var showCollapsedTitle by remember { mutableStateOf(false) }
    val headerScrimAlpha by animateFloatAsState(
        targetValue = if (showCollapsedTitle) 1f else 0f,
        animationSpec = tween(220),
        label = "albumHeaderScrim",
    )

    // The room recolors per album: blurred cover-art wash behind all content.
    // Falls through to the plain DeepOcean base when no art exists.
    val atmosphereArtUrl = albumDetails.album.artworkUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { MusicBrainzEnrichmentService.fixHttpUrl(it) }

    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            ArtAtmosphereLayer(
                artUrl = atmosphereArtUrl,
                tint = dominantColor,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp,
                    bottom = 64.dp,
                ),
            ) {
                // 1. Hero Stage
                item(key = "hero_section") {
                    AlbumHeroEditorialStage(
                        albumDetails = albumDetails,
                        dominantColor = dominantColor,
                        onPaletteExtracted = { color -> dominantColor = conditionedAccent(color) },
                        onNavigateToArtist = onNavigateToArtist,
                        onTitlePositioned = { top ->
                            val collapsed = top <= headerBottomPx
                            if (collapsed != showCollapsedTitle) {
                                showCollapsedTitle = collapsed
                            }
                        },
                    )
                }

                // 2. High-Contrast Master Stats
                item(key = "master_stats_masthead") {
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
                        AlbumStatMasthead(
                            albumDetails = albumDetails,
                            dominantColor = dominantColor,
                        )
                    }
                }

                // 3. Track Sheet — header, then one lazy item PER track row.
                //
                // The rows used to sit inside a single item(key = "track_sheet")
                // as an eager Column, which bypassed LazyColumn virtualization:
                // every row of a 50–200 track album/compilation composed and
                // measured on first layout and stayed composed while scrolled far
                // off-screen. Now only the visible window is materialised. Items
                // are keyed off the track id (never the index): index-baked keys
                // resolve to a different id when the list is mutated mid-scroll,
                // reuse a slot twice and throw the subcompose
                // IllegalArgumentException — see the same discipline in
                // HistoryScreen/StatsScreen.
                item(key = "track_sheet_header") {
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                        AlbumTrackSheetHeader(
                            trackCount = albumDetails.tracks.size,
                            isEditMode = isEditMode,
                            onAddClick = onAddClick,
                        )
                    }
                }

                if (albumDetails.tracks.isEmpty()) {
                    item(key = "track_sheet_empty") {
                        Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
                            AlbumEmptyTracksCard(tint = dominantColor)
                        }
                    }
                } else {
                    itemsIndexed(
                        items = albumDetails.tracks,
                        key = { _, item -> "track_${item.track.id}" },
                        contentType = { _, _ -> "album_track_row" },
                    ) { index, track ->
                        AlbumTrackRow(
                            index = index,
                            track = track,
                            tint = dominantColor,
                            isEditMode = isEditMode,
                            isFirst = index == 0,
                            isLast = index == albumDetails.tracks.lastIndex,
                            onNavigateToSong = onNavigateToSong,
                        )
                    }
                }

                // 4. Footer
                item(key = "footer") {
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 32.dp)) {
                        AlbumDetailsFooter()
                    }
                }
            }

            // Gradient scrim so scrolled content never collides with the
            // collapsed title; fades in with the title itself.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(136.dp)
                    .graphicsLayer { alpha = headerScrimAlpha }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                TempoDarkBackground.copy(alpha = 0.94f),
                                TempoDarkBackground.copy(alpha = 0.60f),
                                Color.Transparent,
                            )
                        )
                    )
            )

            AlbumTopBar(
                showCollapsedTitle = showCollapsedTitle,
                title = albumDetails.album.title,
                artist = albumDetails.artistName,
                isEditMode = isEditMode,
                onNavigateBack = onNavigateBack,
                onToggleEdit = onToggleEdit,
                onAddClick = onAddClick,
                onMenuClick = { showMenu = true },
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 48.dp)
            ) {
                TempoDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    TempoDropdownMenuItem(
                        title = stringResource(R.string.details_action_share_album_card),
                        leadingIcon = Icons.Rounded.Share,
                        onClick = {
                            showMenu = false
                            showShareDialog = true
                        }
                    )
                    TempoDropdownMenuItem(
                        title = stringResource(R.string.details_merge_album),
                        leadingIcon = TempoIcons.MergeStreams,
                        onClick = {
                            showMenu = false
                            showMergeDialog = true
                        }
                    )
                }
            }

            if (showShareDialog) {
                AlbumShareSheet(
                    albumDetails = albumDetails,
                    onDismiss = { showShareDialog = false },
                )
            }

            if (showMergeDialog) {
                AlbumMergeSearchDialog(
                    sourceAlbumId = albumDetails.album.id,
                    sourceAlbumTitle = albumDetails.album.title,
                    sourceArtistId = albumDetails.album.artistId,
                    sourceArtistName = albumDetails.artistName,
                    onDismiss = { showMergeDialog = false },
                    onMergeComplete = { targetAlbumId ->
                        showMergeDialog = false
                        onNavigateToAlbum(targetAlbumId)
                    }
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Top Bar with Collapsible Title & Edit Toggle
// ──────────────────────────────────────────────────────────────

@Composable
private fun AlbumTopBar(
    showCollapsedTitle: Boolean,
    title: String,
    artist: String,
    isEditMode: Boolean,
    onNavigateBack: () -> Unit,
    onToggleEdit: () -> Unit,
    onAddClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumTopBarAction(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.details_action_back),
            onClick = onNavigateBack,
        )

        AnimatedVisibility(
            visible = showCollapsedTitle,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -10 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { -10 },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (!showCollapsedTitle) {
            Spacer(modifier = Modifier.weight(1f))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ponytail: direct one-tap entry to Add Song; edit mode still gates
            // the inline pill, this just skips the two-step dance.
            AlbumTopBarAction(
                icon = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.album_add_song),
                onClick = onAddClick,
            )
            AlbumTopBarAction(
                icon = if (isEditMode) Icons.Rounded.Check else Icons.Rounded.Edit,
                contentDescription = stringResource(
                    if (isEditMode) R.string.album_action_done_cd else R.string.album_action_edit_cd
                ),
                onClick = onToggleEdit,
                iconTint = if (isEditMode) TempoPrimary else TextPrimary,
                containerColor = if (isEditMode) TempoPrimary.copy(alpha = 0.16f) else GlassFrostMedium,
                borderColor = if (isEditMode) TempoPrimary.copy(alpha = 0.40f) else GlassBorderSoft,
            )
            AlbumTopBarAction(
                icon = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.details_action_song_options),
                onClick = onMenuClick,
            )
        }
    }
}

@Composable
private fun AlbumTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconTint: Color = TextPrimary,
    containerColor: Color = GlassFrostMedium,
    borderColor: Color = GlassBorderSoft,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(containerColor)
            .border(0.8.dp, borderColor, CircleShape)
            .premiumClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 1. Hero Stage
// ──────────────────────────────────────────────────────────────

@Composable
private fun AlbumHeroEditorialStage(
    albumDetails: AlbumDetails,
    dominantColor: Color,
    onPaletteExtracted: (Color) -> Unit,
    onNavigateToArtist: (Long) -> Unit,
    onTitlePositioned: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Soft dominant-color halo — ties the extracted tint to the hero
        // and gives the art depth beyond its shadow.
        val glowBrush = remember(dominantColor) {
            Brush.radialGradient(
                colors = listOf(
                    dominantColor.copy(alpha = 0.20f),
                    dominantColor.copy(alpha = 0.0f),
                )
            )
        }
        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush = glowBrush, shape = CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(232.dp)
                    .shadow(
                        elevation = 22.dp,
                        shape = RoundedCornerShape(22.dp),
                        ambientColor = GlassShadowTeal,
                        spotColor = dominantColor.copy(alpha = 0.22f),
                    )
                    .clip(RoundedCornerShape(22.dp))
                    .background(TempoDarkSurfaceSunken)
                    .border(1.dp, GlassBorderStrong, RoundedCornerShape(22.dp)),
            ) {
                AlbumArtImage(
                    albumArtUrl = albumDetails.album.artworkUrl,
                    contentDescription = stringResource(
                        R.string.details_cover_artwork_cd, albumDetails.album.title
                    ),
                    onPaletteExtracted = onPaletteExtracted,
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    onTitlePositioned(coordinates.boundsInWindow().top)
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = albumDetails.album.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = DisplayFontFamily,
                    letterSpacing = (-0.5).sp,
                ),
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }


        // Release-style metadata: hairline divider over a kicker meta line,
        // matching the artist hero treatment.
        val metaParts = listOfNotNull(
            albumDetails.album.releaseYear?.toString(),
            albumDetails.album.releaseType
                ?.takeIf { it.isNotBlank() }
                ?.replaceFirstChar { it.uppercase(Locale.getDefault()) },
        )
        if (metaParts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(0.8.dp)
                        .background(GlassBorderMedium),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = metaParts.joinToString("  ·  ").uppercase(Locale.getDefault()),
                    style = KickerSmall,
                    color = TextTertiary,
                    letterSpacing = 1.4.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(0.8.dp)
                        .background(GlassBorderMedium),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

// ──────────────────────────────────────────────────────────────
// 2. Master Stats Masthead
// ──────────────────────────────────────────────────────────────

@Composable
private fun AlbumStatMasthead(
    albumDetails: AlbumDetails,
    dominantColor: Color,
) {
    val hasPlays = albumDetails.totalPlayCount > 0
    val completion = albumDetails.completionRate
    val completionText = if (hasPlays) {
        String.format(Locale.getDefault(), "%.0f%%", completion)
    } else {
        "—"
    }
    val completionSubtext = when {
        !hasPlays -> stringResource(R.string.details_awaiting_playback)
        completion >= 90 -> stringResource(R.string.details_completion_to_end)
        completion >= 70 -> stringResource(R.string.details_completion_high)
        completion >= 50 -> stringResource(R.string.details_completion_moderate)
        else -> stringResource(R.string.details_completion_skipped)
    }
    val runtimeMs = remember(albumDetails) {
        albumDetails.tracks.sumOf { it.track.duration ?: 0L }
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        variant = GlassCardVariant.Obsidian,
        borderColor = GlassBorderSoft,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp)) {
            // Hero metric — same anatomy as the song and artist mastheads:
            // display-size play count with the listening time as its suffix.
            MastheadHeroMetric(
                label = stringResource(R.string.details_total_plays),
                value = String.format(Locale.getDefault(), "%,d", albumDetails.totalPlayCount),
                suffix = stringResource(
                    R.string.details_together_suffix,
                    formatListeningTime(albumDetails.totalTimeMs),
                ),
                accentTint = dominantColor,
            )

            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(GlassBorderSoft)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Secondary row: track count with runtime, and the completion
            // rate — the one tint with meaning.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MastheadSecondaryStat(
                    label = stringResource(R.string.details_tracks),
                    value = "${albumDetails.tracks.size}",
                    subtext = if (runtimeMs > 0) {
                        formatListeningTime(runtimeMs)
                    } else {
                        stringResource(R.string.details_stat_recorded_library)
                    },
                    modifier = Modifier.weight(1f),
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(0.8.dp)
                        .background(GlassBorderSoft),
                )

                MastheadSecondaryStat(
                    label = stringResource(R.string.details_completion_rate),
                    value = completionText,
                    subtext = completionSubtext,
                    valueColor = when {
                        !hasPlays -> TextPrimary
                        completion >= 70 -> TempoSuccess
                        else -> TempoWarning
                    },
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 3. Track Sheet
// ──────────────────────────────────────────────────────────────

/**
 * Kicker + track count + edit-mode add affordance.
 *
 * Its own LazyColumn item directly above the track rows (see [AlbumTrackRow]),
 * so the header never forces those rows to compose.
 */
@Composable
private fun AlbumTrackSheetHeader(
    trackCount: Int,
    isEditMode: Boolean,
    onAddClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionCatalogKicker(
            number = "01",
            label = stringResource(R.string.details_tracks),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (trackCount == 1) R.string.album_one_song else R.string.album_songs_count,
                    trackCount,
                ),
                style = CaptionSmall,
                color = TextTertiary,
                fontWeight = FontWeight.Medium,
            )
            if (isEditMode) {
                AddSongPill(onClick = onAddClick)
            }
        }
    }
}

@Composable
private fun AlbumEmptyTracksCard(tint: Color) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        variant = GlassCardVariant.QuietGlass,
        accentColor = tint,
        contentPadding = PaddingValues(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(30.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.album_no_tracks),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.album_no_tracks_hint),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One lazily-composed track row of the track sheet.
 *
 * Replaces the eager `tracks.forEachIndexed` Column that used to sit inside a
 * single wraparound [GlassCard]: the card chrome (glass gradient, accent border,
 * hairline divider) is now applied per row, so the outer 20dp corners live on
 * the first/last row and the hairline sits inside each row (skipped on the last)
 * to stay clipped by those corners. Row height is unchanged — content padding
 * (11.2dp top / bottom) + the original 0.8dp [GlassBorderSoft] hairline == the
 * old 12dp + hairline.
 */
@Composable
private fun AlbumTrackRow(
    index: Int,
    track: TrackWithStats,
    tint: Color,
    isEditMode: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onNavigateToSong: (Long) -> Unit,
) {
    val rowShape = RoundedCornerShape(
        topStart = if (isFirst) 20.dp else 0.dp,
        topEnd = if (isFirst) 20.dp else 0.dp,
        bottomEnd = if (isLast) 20.dp else 0.dp,
        bottomStart = if (isLast) 20.dp else 0.dp,
    )

    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = rowShape,
            variant = GlassCardVariant.QuietGlass,
            accentColor = tint,
            contentPadding = PaddingValues(0.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isEditMode) Modifier
                            else Modifier.premiumClickable(onClick = { onNavigateToSong(track.track.id) })
                        )
                        .padding(horizontal = 16.dp, vertical = 11.2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = DisplayFontFamily,
                        ),
                        fontWeight = FontWeight.Bold,
                        color = if (track.playCount > 0) tint else TextQuaternary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(28.dp),
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.track.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        val duration = track.track.duration
                        val durationLabel = if (duration != null && duration > 0) {
                            formatDuration(duration)
                        } else {
                            null
                        }
                        if (durationLabel != null) {
                            Text(
                                text = durationLabel,
                                style = CaptionSmall,
                                color = TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${track.playCount}",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = DisplayFontFamily,
                            ),
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.details_plays).uppercase(Locale.getDefault()),
                            style = CaptionSmall,
                            color = TextTertiary,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .size(11.dp)
                    )
                }

                if (!isLast) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.8.dp)
                            .background(GlassBorderSoft)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSongPill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(PillSurface)
            .premiumClickable(onClick = onClick, pressedScale = 0.96f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = null,
            tint = PillTextPrimary,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = stringResource(R.string.album_add_song),
            color = PillTextPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 4. Footer
// ──────────────────────────────────────────────────────────────

@Composable
private fun AlbumDetailsFooter() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(1.dp)
                .background(GlassBorderSoft),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.album_footer).uppercase(Locale.getDefault()),
            style = KickerSmall,
            color = TextTertiary,
            letterSpacing = 2.sp,
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Dialogs
// ──────────────────────────────────────────────────────────────

@Composable
private fun AddTrackToAlbumDialog(
    query: String,
    results: List<Track>,
    isSearching: Boolean,
    artistName: String,
    onQueryChange: (String) -> Unit,
    onTrackSelected: (Track) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .clip(TempoDialogShape.shape)
                .background(TempoSurfaceDialog)
                .border(1.dp, GlassBorderSoft, TempoDialogShape.shape)
                .padding(20.dp)
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.album_add_song_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(GlassFrostSoft)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.album_close_cd),
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (artistName.isNotEmpty()) {
                        stringResource(R.string.album_add_song_desc, artistName)
                    } else {
                        stringResource(R.string.album_add_song_search)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            stringResource(R.string.album_add_song_search),
                            color = TextTertiary,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = TextTertiary,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = TempoPrimary,
                        focusedBorderColor = TempoPrimary,
                        unfocusedBorderColor = TextQuaternary,
                    ),
                )
                Spacer(modifier = Modifier.height(16.dp))
                when {
                    isSearching -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = TempoPrimary)
                        }
                    }

                    results.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.album_no_candidates),
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(items = results, key = { it.id }) { track ->
                                TrackSearchItem(track = track) { onTrackSelected(track) }
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun RemoveFromAlbumDialog(
    trackTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        TempoDialogSurface {
            TempoDialogIcon(
                icon = TempoIcons.Trash,
                tint = TempoError,
                size = 48
            )
            Spacer(modifier = Modifier.height(16.dp))
            TempoDialogTitle(text = stringResource(R.string.album_remove_title))
            Spacer(modifier = Modifier.height(8.dp))
            TempoDialogBody(text = stringResource(R.string.album_remove_msg, trackTitle))
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.album_remove_note),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            TempoDialogDangerButton(
                text = stringResource(R.string.common_remove),
                onClick = onConfirm,
                icon = TempoIcons.Trash
            )
            Spacer(modifier = Modifier.height(6.dp))
            TempoDialogSecondaryButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * The album's share sheet.
 *
 * Same sheet as the song, artist and badge cards — carousel, themes, motion,
 * save and share — so an album card cannot drift into looking like a different
 * feature. The summary switch comes from [SingleCardShareSheet], like every
 * other single-subject card.
 */
@Composable
private fun AlbumShareSheet(
    albumDetails: AlbumDetails,
    onDismiss: () -> Unit,
) {
    SingleCardShareSheet(
        artworkUrl = albumDetails.album.artworkUrl,
        onDismiss = onDismiss,
    ) { page, showSummary ->
        AlbumShareCard(
            albumDetails = albumDetails,
            card = page.card,
            modifier = Modifier.fillMaxSize(),
            showSummary = showSummary,
        )
    }
}

package me.avinas.tempo.ui.details

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import me.avinas.tempo.data.enrichment.CoverArtCandidate
import me.avinas.tempo.data.enrichment.CoverArtLookupStatus
import me.avinas.tempo.data.enrichment.CoverArtPickerService
import me.avinas.tempo.data.enrichment.CoverArtProvider
import me.avinas.tempo.ui.components.AlbumArtImage
import me.avinas.tempo.ui.theme.GlassBorderMedium
import me.avinas.tempo.ui.theme.GlassBorderSoft
import me.avinas.tempo.ui.theme.GlassFrostSoft
import me.avinas.tempo.ui.theme.TempoDarkSurface
import me.avinas.tempo.ui.theme.TempoDarkSurfaceSunken
import me.avinas.tempo.ui.theme.TempoPrimary
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary

internal fun reconcileCoverPickerSelection(
    selectedProvider: CoverArtProvider?,
    candidates: List<CoverArtCandidate>,
): CoverArtProvider? =
    selectedProvider?.takeIf { selected ->
        candidates.any { candidate -> candidate.provider == selected }
    }

internal fun resolveCoverPickerColumnCount(maxWidthDp: Float): Int =
    when {
        maxWidthDp >= 720f -> 4
        maxWidthDp >= 500f -> 3
        else -> 2
    }

/**
 * The subset of UI state the picker needs, independent of what the artwork belongs to.
 *
 * Songs and artists share one sheet, so the sheet reads this instead of a
 * screen-specific state object. Adding a new artwork surface means adding a
 * [CoverPickerUiState] field to that screen's state, not another sheet.
 */
@androidx.compose.runtime.Immutable
internal data class CoverPickerUiState(
    val selectedProvider: CoverArtProvider? = null,
    val candidates: List<CoverArtCandidate> = emptyList(),
    val providerStatuses: Map<CoverArtProvider, CoverArtLookupStatus> = emptyMap(),
    val error: String? = null,
    val isLoadingCandidates: Boolean = false,
    val isSaving: Boolean = false,
    val isResetting: Boolean = false,
    val isManual: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CoverArtPickerSheet(
    state: CoverPickerUiState,
    onSelect: (CoverArtCandidate) -> Unit,
    onSelectProvider: (CoverArtProvider) -> Unit = {},
    onGallerySelected: (Uri) -> Unit = {},
    onResetAutomatic: () -> Unit,
    onRetry: () -> Unit,
    onRetryProvider: ((CoverArtProvider) -> Unit)? = null,
    onDismiss: () -> Unit,
    providers: List<CoverArtProvider> = CoverArtPickerService.REMOTE_PROVIDERS,
    title: String = stringResource(R.string.details_change_cover_title),
    body: String = stringResource(R.string.details_change_cover_body),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedProvider = reconcileCoverPickerSelection(
        selectedProvider = state.selectedProvider,
        candidates = state.candidates,
    )
    val selected = state.candidates.firstOrNull { it.provider == selectedProvider }
    val visibleProviders = buildList {
        if (state.candidates.any { it.provider == CoverArtProvider.CURRENT }) {
            add(CoverArtProvider.CURRENT)
        }
        addAll(providers)
    }

    val configuration = LocalConfiguration.current
    val isCompactOrLargeFont = configuration.screenHeightDp < 640 || configuration.fontScale > 1.2f
    val previewSize = if (isCompactOrLargeFont) 120.dp else 168.dp

    var isPreviewImageError by remember(selected?.albumArtUrl) { mutableStateOf(false) }

    // The photo picker needs no storage permission and, on devices without it,
    // ActivityResultContracts falls back to a document picker automatically.
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let(onGallerySelected)
    }
    val coverChangeEnabled = !state.isSaving && !state.isResetting

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TempoDarkSurface,
        contentColor = TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )

                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = coverChangeEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, GlassBorderMedium),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.details_cover_from_gallery))
                }
                Text(
                    text = stringResource(R.string.details_cover_from_gallery_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )

                if (state.isManual) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.details_cover_manual),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TempoPrimary,
                    )
                }

                selected?.let { candidate ->
                    Spacer(modifier = Modifier.height(18.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AlbumArtImage(
                            albumArtUrl = candidate.albumArtUrlLarge ?: candidate.albumArtUrl,
                            contentDescription = providerLabel(candidate.provider),
                            targetSizePx = 1024,
                            onImageLoaded = {
                                isPreviewImageError = false
                            },
                            onImageError = {
                                isPreviewImageError = true
                            },
                            modifier = Modifier
                                .size(previewSize)
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.dp, GlassBorderMedium, RoundedCornerShape(18.dp)),
                        )
                        if (isPreviewImageError) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.details_cover_preview_error),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = providerLabel(candidate.provider),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                        )
                        val subtitle = candidate.albumTitle?.takeIf { it.isNotBlank() }
                            ?: if (candidate.provider == CoverArtProvider.CURRENT) {
                                stringResource(R.string.details_cover_current_hint)
                            } else null
                        subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val spacing = 12.dp
                    val columns = resolveCoverPickerColumnCount(maxWidth.value)
                    val cardWidth = (maxWidth - (spacing * (columns - 1))) / columns
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                        verticalArrangement = Arrangement.spacedBy(spacing),
                        maxItemsInEachRow = columns,
                    ) {
                        visibleProviders.forEach { provider ->
                            val candidate = state.candidates.firstOrNull { it.provider == provider }
                            val status = if (provider == CoverArtProvider.CURRENT) {
                                if (candidate != null) CoverArtLookupStatus.FOUND else CoverArtLookupStatus.NOT_FOUND
                            } else {
                                state.providerStatuses[provider] ?: CoverArtLookupStatus.LOADING
                            }
                            CoverProviderCard(
                                provider = provider,
                                candidate = candidate,
                                status = status,
                                selected = selectedProvider == provider && candidate != null,
                                onClick = {
                                    if (candidate != null && !state.isSaving && !state.isResetting) {
                                        onSelectProvider(provider)
                                    } else if (status == CoverArtLookupStatus.ERROR && !state.isLoadingCandidates) {
                                        if (onRetryProvider != null) {
                                            onRetryProvider(provider)
                                        } else {
                                            onRetry()
                                        }
                                    }
                                },
                                onRetry = onRetryProvider?.let { callback -> { callback(provider) } },
                                modifier = Modifier
                                    .width(cardWidth)
                                    .heightIn(min = 180.dp),
                            )
                        }
                    }
                }

                val hasProviderError = state.providerStatuses.values
                    .any { it == CoverArtLookupStatus.ERROR }
                if (state.error != null || hasProviderError) {
                    Spacer(modifier = Modifier.height(14.dp))
                    state.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(
                        onClick = onRetry,
                        enabled = !state.isLoadingCandidates && !state.isSaving && !state.isResetting,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(stringResource(R.string.details_cover_try_again))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { selected?.let(onSelect) },
                enabled = selected != null && coverChangeEnabled && !isPreviewImageError,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.details_cover_use))
                }
            }

            if (state.isManual) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = GlassBorderSoft)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onResetAutomatic,
                    enabled = coverChangeEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, GlassBorderMedium),
                ) {
                    if (state.isResetting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.details_cover_restore_auto))
                    }
                }
                Text(
                    text = stringResource(R.string.details_cover_restore_auto_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CoverProviderCard(
    provider: CoverArtProvider,
    candidate: CoverArtCandidate?,
    status: CoverArtLookupStatus,
    selected: Boolean,
    onClick: () -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (selected) TempoPrimary else GlassBorderSoft
    val providerName = providerLabel(provider)
    val statusDescription = when {
        status == CoverArtLookupStatus.LOADING -> stringResource(R.string.details_cover_searching)
        status == CoverArtLookupStatus.ERROR -> stringResource(R.string.details_cover_error)
        status == CoverArtLookupStatus.UNAVAILABLE -> stringResource(R.string.details_cover_unavailable)
        candidate != null -> candidate.albumTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.details_cover_current_hint)
        else -> stringResource(R.string.details_cover_not_found)
    }
    val cardContentDescription = buildString {
        append(providerName)
        append(", ")
        append(statusDescription)
        if (selected) {
            append(", selected")
        }
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) GlassFrostSoft else TempoDarkSurfaceSunken)
            .border(if (selected) 1.5.dp else 0.8.dp, borderColor, shape)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                this.contentDescription = cardContentDescription
            }
            .clickable(
                enabled = candidate != null || status == CoverArtLookupStatus.ERROR,
                onClickLabel = if (status == CoverArtLookupStatus.ERROR) {
                    stringResource(R.string.details_cover_retry_source)
                } else {
                    providerName
                },
                onClick = onClick,
            )
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassFrostSoft),
            contentAlignment = Alignment.Center,
        ) {
            when {
                candidate != null -> {
                    AlbumArtImage(
                        albumArtUrl = candidate.albumArtUrlSmall ?: candidate.albumArtUrl,
                        contentDescription = null,
                        targetSizePx = 256,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = TempoPrimary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(22.dp),
                        )
                    }
                }
                status == CoverArtLookupStatus.LOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = TempoPrimary,
                    )
                }
                status == CoverArtLookupStatus.UNAVAILABLE -> {
                    Text(
                        text = stringResource(R.string.details_cover_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
                status == CoverArtLookupStatus.ERROR -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.details_cover_error),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (onRetry != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.details_cover_retry_source),
                                tint = TempoPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        text = stringResource(R.string.details_cover_not_found),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = providerLabel(provider),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (candidate != null) TextPrimary else TextTertiary,
            maxLines = 1,
        )
        Text(
            text = when {
                candidate?.albumTitle?.isNotBlank() == true -> candidate.albumTitle.orEmpty()
                provider == CoverArtProvider.CURRENT -> stringResource(R.string.details_cover_current_hint)
                status == CoverArtLookupStatus.LOADING -> stringResource(R.string.details_cover_searching)
                status == CoverArtLookupStatus.UNAVAILABLE -> stringResource(R.string.details_cover_unavailable)
                status == CoverArtLookupStatus.ERROR -> if (onRetry != null) {
                    stringResource(R.string.details_cover_retry_source)
                } else {
                    stringResource(R.string.details_cover_error)
                }
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (status == CoverArtLookupStatus.ERROR && onRetry != null) TempoPrimary else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun providerLabel(provider: CoverArtProvider): String =
    when (provider) {
        CoverArtProvider.CURRENT -> stringResource(R.string.details_cover_current)
        CoverArtProvider.APPLE_MUSIC -> stringResource(R.string.details_cover_provider_apple_music)
        CoverArtProvider.MUSICBRAINZ -> stringResource(R.string.details_cover_provider_musicbrainz)
        CoverArtProvider.DEEZER -> stringResource(R.string.details_cover_provider_deezer)
        CoverArtProvider.LASTFM -> stringResource(R.string.details_cover_provider_lastfm)
    }

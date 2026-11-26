package me.avinas.tempo.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.avinas.tempo.R
import me.avinas.tempo.data.importexport.ImportExportProgress
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.TempoDialogBody
import me.avinas.tempo.ui.components.TempoDialogButtonRow
import me.avinas.tempo.ui.components.TempoDialogDangerButton
import me.avinas.tempo.ui.components.TempoDialogIcon
import me.avinas.tempo.ui.components.TempoDialogSecondaryButton
import me.avinas.tempo.ui.components.TempoDialogSurface
import me.avinas.tempo.ui.components.TempoDialogTextField
import me.avinas.tempo.ui.components.TempoDialogTitle
import me.avinas.tempo.ui.components.TempoIcons
import me.avinas.tempo.ui.theme.GlassBorderSoft
import me.avinas.tempo.ui.theme.GlassFrostSoft
import me.avinas.tempo.ui.theme.TempoDarkSurfaceSunken
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoPrimaryDeep
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary

private val AvatarGradientBrush = Brush.linearGradient(listOf(TempoPrimary, TempoPrimaryDeep))
private val DialogOptionShape = RoundedCornerShape(12.dp)
private val DialogButtonShape = RoundedCornerShape(10.dp)
private val DialogShape24 = RoundedCornerShape(24.dp)

@Composable
internal fun ProfileEditDialog(
    initialName: String,
    profileImagePath: String?,
    onDismiss: () -> Unit,
    onSaveName: (String) -> Unit,
    onChangePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
) {
    var tempName by remember(initialName) { mutableStateOf(initialName) }

    Dialog(onDismissRequest = onDismiss) {
        TempoDialogSurface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .clickable(onClick = onChangePhoto)
                            .background(
                                brush = AvatarGradientBrush,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (profileImagePath.isNullOrBlank()) {
                            Text(
                                text = tempName.trim().firstOrNull()?.uppercase() ?: "U",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                            )
                        } else {
                            CachedAsyncImage(
                                imageUrl = profileImagePath,
                                contentDescription = stringResource(R.string.settings_profile_photo),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = TempoDarkSurfaceSunken,
                        border = BorderStroke(1.dp, GlassBorderSoft),
                        modifier = Modifier.size(24.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.PhotoCamera,
                                contentDescription = stringResource(R.string.settings_change_photo),
                                tint = TextPrimary,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                TempoDialogTitle(text = stringResource(R.string.settings_profile_dialog_title))
                Spacer(modifier = Modifier.height(4.dp))
                TempoDialogBody(text = stringResource(R.string.settings_profile_dialog_body))
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onChangePhoto,
                        shape = DialogButtonShape,
                        border = BorderStroke(1.dp, GlassBorderSoft),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_change_photo),
                            style = MaterialTheme.typography.labelMedium,
                            color = TempoPrimary,
                        )
                    }
                    if (!profileImagePath.isNullOrBlank()) {
                        TextButton(
                            onClick = onRemovePhoto,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_remove_photo),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TempoDialogTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    label = stringResource(R.string.settings_display_name),
                )
                Spacer(modifier = Modifier.height(20.dp))
                TempoDialogButtonRow(
                    primaryText = stringResource(R.string.settings_save),
                    onPrimary = {
                        val trimmed = tempName.trim()
                        if (trimmed.isNotBlank()) {
                            onSaveName(trimmed)
                        }
                    },
                    secondaryText = stringResource(R.string.settings_cancel),
                    onSecondary = onDismiss,
                    primaryEnabled = tempName.isNotBlank(),
                )
            }
        }
    }
}

@Composable
internal fun LanguageSelectorDialog(
    languages: List<Pair<String, String>>,
    currentLocale: String,
    onSelectLanguage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        TempoDialogSurface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TempoDialogIcon(
                    icon = Icons.Rounded.Language,
                    tint = TempoPrimary,
                    size = 48,
                )
                Spacer(modifier = Modifier.height(14.dp))
                TempoDialogTitle(text = stringResource(R.string.settings_select_language))
                Spacer(modifier = Modifier.height(6.dp))
                TempoDialogBody(text = stringResource(R.string.settings_language_subtitle))
                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    languages.forEach { (langTag, langName) ->
                        val selected = currentLocale == langTag
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(DialogOptionShape)
                                .background(if (selected) TempoPrimary.copy(alpha = 0.10f) else TempoDarkSurfaceSunken.copy(alpha = 0.5f))
                                .selectable(
                                    selected = selected,
                                    onClick = { onSelectLanguage(langTag) },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = TempoPrimary),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = langName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = TextPrimary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                TempoDialogSecondaryButton(
                    text = stringResource(R.string.settings_cancel),
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
internal fun CoverSourceSelectorDialog(
    options: List<Pair<AlbumArtSource?, String>>,
    selectedSource: AlbumArtSource?,
    onSelectSource: (AlbumArtSource?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        TempoDialogSurface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TempoDialogIcon(
                    icon = Icons.Rounded.Album,
                    tint = TempoPrimary,
                    size = 48,
                )
                Spacer(modifier = Modifier.height(14.dp))
                TempoDialogTitle(text = stringResource(R.string.settings_cover_source_title))
                Spacer(modifier = Modifier.height(6.dp))
                TempoDialogBody(text = stringResource(R.string.settings_cover_source_description))
                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    options.forEach { (source, name) ->
                        val selected = source == selectedSource
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(DialogOptionShape)
                                .background(if (selected) TempoPrimary.copy(alpha = 0.10f) else TempoDarkSurfaceSunken.copy(alpha = 0.5f))
                                .selectable(
                                    selected = selected,
                                    onClick = { onSelectSource(source) },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = TempoPrimary),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = TextPrimary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                TempoDialogSecondaryButton(
                    text = stringResource(R.string.settings_cancel),
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
internal fun ClearDataConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        TempoDialogSurface {
            TempoDialogIcon(
                icon = TempoIcons.Trash,
                tint = TempoError,
                size = 48,
            )
            Spacer(modifier = Modifier.height(16.dp))
            TempoDialogTitle(text = stringResource(R.string.settings_clear_data_title))
            Spacer(modifier = Modifier.height(8.dp))
            TempoDialogBody(text = stringResource(R.string.settings_clear_data_msg))
            Spacer(modifier = Modifier.height(24.dp))
            TempoDialogDangerButton(
                text = stringResource(R.string.settings_clear_everything),
                onClick = onConfirm,
                icon = TempoIcons.Trash,
            )
            Spacer(modifier = Modifier.height(6.dp))
            TempoDialogSecondaryButton(
                text = stringResource(R.string.settings_cancel),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
internal fun ImportProgressDialog(progress: ImportExportProgress) {
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss while in progress */ },
        containerColor = TempoSurfaceDialog,
        shape = DialogShape24,
        title = {
            Text(
                text = stringResource(R.string.settings_processing),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = progress.phase,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (progress.isIndeterminate) {
                    CircularProgressIndicator(color = TempoPrimary, strokeWidth = 2.dp)
                } else {
                    LinearProgressIndicator(
                        progress = { progress.percentage.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = TempoPrimary,
                        trackColor = GlassFrostSoft,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${progress.current}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
internal fun ImportConflictDialog(
    onReplace: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = TempoSurfaceDialog,
        shape = DialogShape24,
        title = {
            Text(
                text = stringResource(R.string.settings_import_options),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.settings_import_conflict_msg),
                color = TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onReplace) {
                Text(
                    text = stringResource(R.string.settings_replace_existing),
                    color = TempoPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.settings_cancel), color = TextTertiary)
                }
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.settings_skip_duplicates), color = TextSecondary)
                }
            }
        },
    )
}

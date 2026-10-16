package me.avinas.tempo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.R
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.data.drive.DriveHistorySyncSettings
import me.avinas.tempo.data.drive.DriveHistorySyncStatus
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SettingsSwitch
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DriveHistorySyncSection(
    isGoogleSignedIn: Boolean,
    accountEmail: String?,
    onRequestGoogleSignIn: () -> Unit,
    viewModel: DriveHistorySyncViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val operation by viewModel.operation.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmShare by remember { mutableStateOf(false) }
    LaunchedEffect(accountEmail, isGoogleSignedIn) {
        // A prior account's success/error must not remain attached to a
        // newly signed-in identity. The shared coordinator retains a genuine
        // running operation across screen recreation.
        viewModel.clearOperation()
    }

    val running = operation is DriveHistoryUiOperation.Running ||
        settings.lastStatus == DriveHistorySyncStatus.RUNNING

    Text(
        text = stringResource(R.string.drive_heading),
        style = MaterialTheme.typography.titleSmall,
        color = TempoRed,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column {
            SettingsSwitch(
                title = stringResource(R.string.drive_toggle_title),
                subtitle = stringResource(R.string.drive_toggle_subtitle),
                checked = settings.enabled,
                onCheckedChange = viewModel::setEnabled,
                enabled = isGoogleSignedIn && !running
            )
            // Remote deletion can stop Drive sync in the background, outside
            // this ViewModel's UI operation. Keep its persisted reason visible
            // when the toggle becomes disabled, including after reopening.
            if (!settings.enabled && !settings.lastMessage.isNullOrBlank() &&
                operation is DriveHistoryUiOperation.Idle
            ) {
                Text(
                    text = settings.lastMessage.orEmpty(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
            if (!isGoogleSignedIn) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.drive_signin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                    Button(
                        onClick = onRequestGoogleSignIn,
                        enabled = !running,
                        modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary)
                    ) {
                        Text(stringResource(R.string.drive_signin_action))
                    }
                }
            }
            if (running && !settings.enabled) {
                Row(modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.drive_connecting),
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            if (settings.enabled && isGoogleSignedIn) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.drive_status_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                            Text(
                                text = historySyncSubtitle(settings),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                        Button(
                            onClick = viewModel::syncNow,
                            enabled = !running,
                            colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary)
                        ) {
                            if (running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                            Text(stringResource(R.string.drive_sync_now))
                        }
                    }

                    OutlinedButton(
                        onClick = viewModel::restoreFullHistory,
                        enabled = !running,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.drive_restore))
                    }
                    Text(
                        stringResource(R.string.drive_restore_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    OutlinedButton(
                        onClick = { confirmShare = true },
                        enabled = !running,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.drive_share))
                    }
                    Text(
                        stringResource(R.string.drive_share_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 4.dp)
                    )

                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Row(modifier = Modifier.padding(16.dp)) {
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = !running) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                        Text(stringResource(R.string.drive_delete))
                    }
                }
            }

            // Keep feedback visible even when sync has just been disabled.
            when (val op = operation) {
                is DriveHistoryUiOperation.Success -> Text(
                    text = op.message,
                    modifier = Modifier.padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
                is DriveHistoryUiOperation.Error -> Text(
                    text = op.message,
                    modifier = Modifier.padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                    style = MaterialTheme.typography.bodySmall,
                    color = TempoRed
                )
                else -> Unit
            }
        }
    }

    if (confirmShare) {
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text(stringResource(R.string.drive_share_confirm_title)) },
            text = {
                Text(stringResource(R.string.drive_share_confirm_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmShare = false
                    viewModel.shareOlderLocalHistory()
                }) { Text(stringResource(R.string.drive_share_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmShare = false }) { Text(stringResource(R.string.drive_cancel)) }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.CloudSync, contentDescription = null, tint = TempoRed) },
            title = { Text(stringResource(R.string.drive_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.drive_delete_confirm_body)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteCloudHistory()
                }) {
                    Text(stringResource(R.string.drive_delete_confirm), color = TempoRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.drive_cancel)) }
            }
        )
    }
}

@Composable
private fun historySyncSubtitle(settings: DriveHistorySyncSettings): String {
    if (!settings.enabled) return stringResource(R.string.drive_status_disabled)
    val last = settings.lastSyncTime?.let {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
    } ?: stringResource(R.string.drive_never)
    return when (settings.lastStatus) {
        DriveHistorySyncStatus.RUNNING -> stringResource(R.string.drive_status_running)
        DriveHistorySyncStatus.FAILED -> settings.lastMessage?.let {
            stringResource(R.string.drive_status_failed_detail, it)
        } ?: stringResource(R.string.drive_status_failed)
        DriveHistorySyncStatus.SUCCESS -> if (settings.lastMessage != null) {
            stringResource(R.string.drive_last_message, last, settings.lastMessage)
        } else {
            stringResource(R.string.drive_last_counts, last, settings.lastUploaded, settings.lastImported)
        }
        DriveHistorySyncStatus.NEVER -> stringResource(R.string.drive_schedule_hint)
    }
}

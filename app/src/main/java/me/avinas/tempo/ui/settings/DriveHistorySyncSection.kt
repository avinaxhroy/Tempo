package me.avinas.tempo.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.avinas.tempo.data.drive.DriveHistorySyncManager
import me.avinas.tempo.data.drive.DriveHistorySyncResult
import me.avinas.tempo.data.drive.DriveHistorySyncSettings
import me.avinas.tempo.data.drive.DriveHistorySyncSettingsManager
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SettingsSwitch
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.worker.DriveHistorySyncWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

@HiltViewModel
class DriveHistorySyncViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsManager: DriveHistorySyncSettingsManager,
    private val syncManager: DriveHistorySyncManager,
    private val applicationScope: CoroutineScope
) : ViewModel() {
    val settings: StateFlow<DriveHistorySyncSettings> = settingsManager.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DriveHistorySyncSettings()
    )

    private val _operation = MutableStateFlow<DriveHistoryUiOperation>(DriveHistoryUiOperation.Idle)
    val operation: StateFlow<DriveHistoryUiOperation> = _operation.asStateFlow()
    private val operationRunning = AtomicBoolean(false)

    fun setEnabled(enabled: Boolean) {
        launchExclusive {
            if (enabled) {
                if (!syncManager.enableSync()) {
                    return@launchExclusive DriveHistoryUiOperation.Error(
                        "Google Drive authorization is required before cross-device sync can be enabled."
                    )
                }
                DriveHistorySyncWorker.schedule(context)
                syncResultToUi(syncManager.syncNow())
            } else {
                syncManager.disableSync()
                DriveHistorySyncWorker.cancel(context)
                DriveHistoryUiOperation.Idle
            }
        }
    }

    fun syncNow() {
        launchExclusive {
            syncResultToUi(syncManager.syncNow())
        }
    }

    fun deleteCloudHistory() {
        launchExclusive {
            try {
                val deleted = syncManager.deleteCloudHistoryAndReset()
                DriveHistorySyncWorker.cancel(context)
                DriveHistoryUiOperation.Success(
                    "Deleted $deleted Tempo history batch${if (deleted == 1) "" else "es"} from Google Drive. Cross-device sync is now off on linked devices as they reconnect."
                )
            } catch (error: Exception) {
                // Account-boundary protection can deliberately turn sync off
                // before refusing to delete from an unexpected Google account.
                if (!settingsManager.settings.first().enabled) {
                    DriveHistorySyncWorker.cancel(context)
                }
                throw error
            }
        }
    }

    fun clearOperation() {
        if (!operationRunning.get()) _operation.value = DriveHistoryUiOperation.Idle
    }

    private fun launchExclusive(block: suspend () -> DriveHistoryUiOperation) {
        if (!operationRunning.compareAndSet(false, true)) return
        _operation.value = DriveHistoryUiOperation.Running
        applicationScope.launch {
            _operation.value = try {
                block()
            } catch (e: Exception) {
                DriveHistoryUiOperation.Error(e.message ?: "Google Drive history operation failed")
            } finally {
                operationRunning.set(false)
            }
        }
    }

    private fun syncResultToUi(result: DriveHistorySyncResult): DriveHistoryUiOperation = when (result) {
        DriveHistorySyncResult.Disabled -> DriveHistoryUiOperation.Idle
        is DriveHistorySyncResult.RemoteDisabled -> {
            DriveHistorySyncWorker.cancel(context)
            DriveHistoryUiOperation.Error(result.message)
        }
        is DriveHistorySyncResult.Success -> DriveHistoryUiOperation.Success(
            "Synced ${result.uploaded} outgoing and ${result.imported} incoming plays"
        )
        is DriveHistorySyncResult.Error -> DriveHistoryUiOperation.Error(result.message)
    }
}

sealed class DriveHistoryUiOperation {
    data object Idle : DriveHistoryUiOperation()
    data object Running : DriveHistoryUiOperation()
    data class Success(val message: String) : DriveHistoryUiOperation()
    data class Error(val message: String) : DriveHistoryUiOperation()
}

@Composable
fun DriveHistorySyncSection(
    viewModel: DriveHistorySyncViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val operation by viewModel.operation.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    Text(
        text = "CROSS-DEVICE HISTORY",
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
                title = "Sync listening history",
                subtitle = "Link Android and browser devices through your private Google Drive app-data space. Compatible desktop clients can use the same protocol. No Tempo server is used.",
                checked = settings.enabled,
                onCheckedChange = viewModel::setEnabled,
                enabled = operation !is DriveHistoryUiOperation.Running
            )

            if (settings.enabled) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Google Drive sync",
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
                            enabled = operation !is DriveHistoryUiOperation.Running,
                            colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary)
                        ) {
                            if (operation is DriveHistoryUiOperation.Running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                            Text("Sync now")
                        }
                    }

                    when (val op = operation) {
                        is DriveHistoryUiOperation.Success -> Text(
                            text = op.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        is DriveHistoryUiOperation.Error -> Text(
                            text = op.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = TempoRed,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        else -> Unit
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Row(modifier = Modifier.padding(16.dp)) {
                    OutlinedButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                        Text("Delete synced Drive history")
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.CloudSync, contentDescription = null, tint = TempoRed) },
            title = { Text("Delete cloud sync data?") },
            text = {
                Text(
                    "This deletes Tempo's cross-device history batches from Google Drive and publishes a shared off marker. " +
                        "Linked Tempo devices will stop Drive sync when they reconnect. Your local listening history stays on your devices. " +
                        "Sync can be explicitly enabled again later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteCloudHistory()
                }) {
                    Text("Delete and turn off", color = TempoRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

private fun historySyncSubtitle(settings: DriveHistorySyncSettings): String {
    val last = settings.lastSyncTime?.let {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
    } ?: "Never"
    return when {
        settings.lastMessage != null -> "Last sync: $last • ${settings.lastMessage}"
        settings.lastSyncTime != null ->
            "Last sync: $last • ${settings.lastUploaded} sent • ${settings.lastImported} received"
        else -> "Runs automatically about every 6 hours and whenever you tap Sync now"
    }
}

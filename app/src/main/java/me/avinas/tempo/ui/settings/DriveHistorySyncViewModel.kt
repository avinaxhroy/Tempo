package me.avinas.tempo.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.data.drive.DriveHistorySyncManager
import me.avinas.tempo.data.drive.DriveHistorySyncResult
import me.avinas.tempo.data.drive.DriveHistorySyncSettings
import me.avinas.tempo.data.drive.DriveHistorySyncSettingsManager
import me.avinas.tempo.worker.DriveHistorySyncWorker
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Keep an in-flight UI action visible if Android recreates the settings screen.
 * The actual sync work already runs in applicationScope; the matching progress
 * must not be tied to an individual ViewModel instance.
 */
@Singleton
class DriveHistoryUiOperationCoordinator @Inject constructor() {
    private val current = MutableStateFlow<DriveHistoryUiOperation>(DriveHistoryUiOperation.Idle)
    val operation: StateFlow<DriveHistoryUiOperation> = current.asStateFlow()
    private val busy = AtomicBoolean(false)

    fun begin(): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        current.value = DriveHistoryUiOperation.Running
        return true
    }

    fun finish(result: DriveHistoryUiOperation) {
        current.value = result
        busy.set(false)
    }

    fun clear() {
        if (!busy.get()) current.value = DriveHistoryUiOperation.Idle
    }
}

@HiltViewModel
class DriveHistorySyncViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsManager: DriveHistorySyncSettingsManager,
    private val syncManager: DriveHistorySyncManager,
    private val operationCoordinator: DriveHistoryUiOperationCoordinator,
    private val applicationScope: CoroutineScope
) : ViewModel() {
    val settings: StateFlow<DriveHistorySyncSettings> = settingsManager.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DriveHistorySyncSettings()
    )

    val operation: StateFlow<DriveHistoryUiOperation> = operationCoordinator.operation

    fun setEnabled(enabled: Boolean) {
        launchExclusive {
            if (enabled) {
                if (!syncManager.enableSync()) {
                    return@launchExclusive DriveHistoryUiOperation.Error(
                        context.getString(R.string.drive_auth_required)
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

    fun restoreFullHistory() {
        launchExclusive {
            syncResultToUi(syncManager.restoreFullHistory())
        }
    }

    fun shareOlderLocalHistory() {
        launchExclusive {
            when (val result = syncManager.shareOlderLocalHistory()) {
                is DriveHistorySyncResult.Success -> syncResultToUi(syncManager.syncNow())
                else -> syncResultToUi(result)
            }
        }
    }

    fun deleteCloudHistory() {
        launchExclusive {
            try {
                val deleted = syncManager.deleteCloudHistoryAndReset()
                DriveHistorySyncWorker.cancel(context)
                DriveHistoryUiOperation.Success(
                    context.getString(R.string.drive_deleted_success, deleted)
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

    fun clearOperation() = operationCoordinator.clear()

    private fun launchExclusive(block: suspend () -> DriveHistoryUiOperation) {
        if (!operationCoordinator.begin()) return
        applicationScope.launch {
            val result = try {
                block()
            } catch (e: Exception) {
                DriveHistoryUiOperation.Error(e.message ?: context.getString(R.string.drive_operation_failed))
            }
            operationCoordinator.finish(result)
        }
    }

    private fun syncResultToUi(result: DriveHistorySyncResult): DriveHistoryUiOperation = when (result) {
        DriveHistorySyncResult.Disabled -> DriveHistoryUiOperation.Idle
        is DriveHistorySyncResult.RemoteDisabled -> {
            DriveHistorySyncWorker.cancel(context)
            DriveHistoryUiOperation.Error(result.message)
        }
        is DriveHistorySyncResult.Success -> DriveHistoryUiOperation.Success(
            context.getString(R.string.drive_sync_success, result.uploaded, result.imported)
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

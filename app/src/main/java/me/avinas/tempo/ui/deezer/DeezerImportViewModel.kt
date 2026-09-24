package me.avinas.tempo.ui.deezer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FeatureUsed
import me.avinas.tempo.data.analytics.TempoFeature
import me.avinas.tempo.data.deezer.DeezerDataImportService
import me.avinas.tempo.worker.DeezerImportWorker
import javax.inject.Inject

@HiltViewModel
class DeezerImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val importService: DeezerDataImportService,
    private val tracker: AnalyticsTracker,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DeezerImportUiState>(DeezerImportUiState.Idle)
    val uiState: StateFlow<DeezerImportUiState> = _uiState.asStateFlow()

    val importState = importService.importState

    companion object {
        private const val STATE_PREFS = "deezer_import_state"
        private const val PREF_ACTIVE_WORK_ID = "active_work_id"
    }

    private val statePrefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private var activeWorkId: java.util.UUID? =
        statePrefs
            .getString(PREF_ACTIVE_WORK_ID, null)
            ?.let { storedId -> runCatching { java.util.UUID.fromString(storedId) }.getOrNull() }

    private fun rememberActiveWork(id: java.util.UUID?) {
        activeWorkId = id
        statePrefs.edit().apply {
            if (id == null) {
                remove(PREF_ACTIVE_WORK_ID)
            } else {
                putString(PREF_ACTIVE_WORK_ID, id.toString())
            }
        }.apply()
    }

    init {
        // 1. Live in-process state flow: provides high-frequency progress while the app is alive
        viewModelScope.launch {
            importService.importState.collect { state ->
                when (state) {
                    is DeezerDataImportService.ImportState.Parsing,
                    is DeezerDataImportService.ImportState.Importing -> {
                        _uiState.value = DeezerImportUiState.Importing
                    }

                    // Terminal UI state comes exclusively from WorkManager below.
                    // The service can publish Completed/Error slightly before the
                    // unique worker itself becomes terminal. Waiting for WorkManager
                    // prevents a fast "import another/retry" action from racing
                    // ExistingWorkPolicy.KEEP and being silently discarded.
                    is DeezerDataImportService.ImportState.Completed,
                    is DeezerDataImportService.ImportState.Error,
                    is DeezerDataImportService.ImportState.Idle -> Unit
                }
            }
        }

        // 2. Persistent WorkManager observation: survives navigation and process death
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(DeezerImportWorker.WORK_NAME)
                .collect { infos ->
                    val trackedInfo =
                        activeWorkId?.let { id ->
                            infos.firstOrNull { it.id == id }
                        }
                    val info =
                        trackedInfo
                            ?: infos
                                .firstOrNull {
                                    it.state == WorkInfo.State.ENQUEUED ||
                                        it.state == WorkInfo.State.RUNNING ||
                                        it.state == WorkInfo.State.BLOCKED
                                }?.also { rememberActiveWork(it.id) }
                            ?: return@collect

                    when (info.state) {
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.RUNNING,
                        WorkInfo.State.BLOCKED -> {
                            if (_uiState.value !is DeezerImportUiState.Importing) {
                                _uiState.value = DeezerImportUiState.Importing
                            }
                        }

                        WorkInfo.State.SUCCEEDED -> {
                            if (_uiState.value is DeezerImportUiState.Importing || activeWorkId == info.id) {
                                val result = DeezerDataImportService.ImportResult(
                                    tracksImported = info.outputData.getInt(DeezerImportWorker.KEY_TRACKS_IMPORTED, 0),
                                    eventsCreated = info.outputData.getInt(DeezerImportWorker.KEY_EVENTS_CREATED, 0),
                                    duplicatesSkipped = info.outputData.getInt(DeezerImportWorker.KEY_DUPLICATES_SKIPPED, 0),
                                    shortPlaysSkipped = info.outputData.getInt(DeezerImportWorker.KEY_SHORT_PLAYS_SKIPPED, 0),
                                    malformedRows = info.outputData.getInt(DeezerImportWorker.KEY_MALFORMED_ROWS, 0),
                                    totalEntries = info.outputData.getInt(DeezerImportWorker.KEY_TOTAL_ENTRIES, 0),
                                    errors =
                                        info.outputData
                                            .getStringArray(DeezerImportWorker.KEY_WARNINGS)
                                            ?.toList()
                                            .orEmpty(),
                                )
                                _uiState.value = DeezerImportUiState.Completed(result)
                            }
                        }

                        WorkInfo.State.FAILED -> {
                            if (_uiState.value is DeezerImportUiState.Importing || activeWorkId == info.id) {
                                val errorMsg =
                                    info.outputData.getString(DeezerImportWorker.KEY_ERROR_MESSAGE)
                                        ?: context.getString(R.string.deezer_import_error_generic)
                                _uiState.value = DeezerImportUiState.Error(errorMsg)
                            }
                        }

                        WorkInfo.State.CANCELLED -> {
                            if (_uiState.value is DeezerImportUiState.Importing || activeWorkId == info.id) {
                                _uiState.value =
                                    DeezerImportUiState.Error(
                                        context.getString(R.string.deezer_import_error_cancelled),
                                    )
                            }
                        }
                    }
                }
        }
    }

    fun importFile(context: Context, uri: Uri) {
        if (
            _uiState.value is DeezerImportUiState.Importing ||
            importService.importState.value is DeezerDataImportService.ImportState.Parsing ||
            importService.importState.value is DeezerDataImportService.ImportState.Importing
        ) {
            return
        }

        tracker.track(FeatureUsed(TempoFeature.DEEZER_IMPORT))
        _uiState.value = DeezerImportUiState.Importing

        viewModelScope.launch {
            var stagedPath: String? = null
            try {
                // Copy while the ActivityResult grant is definitely alive. The worker
                // then receives a private file URI, so provider-specific persistable
                // permission support can no longer break process-death recovery.
                val stagedFile = importService.stageImportFile(context, uri)
                stagedPath = stagedFile.absolutePath

                val enqueueResult =
                    DeezerImportWorker.enqueueImport(
                        context,
                        Uri.fromFile(stagedFile).toString(),
                    )

                if (!enqueueResult.requestAccepted) {
                    // KEEP selected an already-running unique work. This newly staged
                    // file has no owner and must not be left behind.
                    importService.deleteStagedImportFile(context, stagedPath)
                    stagedPath = null
                }

                rememberActiveWork(enqueueResult.workId)
            } catch (e: CancellationException) {
                importService.deleteStagedImportFile(context, stagedPath)
                throw e
            } catch (e: Exception) {
                importService.deleteStagedImportFile(context, stagedPath)
                _uiState.value =
                    DeezerImportUiState.Error(
                        DeezerDataImportService.userFacingError(e),
                    )
            }
        }
    }

    fun cancelImport() {
        DeezerImportWorker.cancel(context)
    }

    fun resetState() {
        rememberActiveWork(null)
        _uiState.value = DeezerImportUiState.Idle
        importService.resetState()
    }
}

sealed class DeezerImportUiState {
    object Idle : DeezerImportUiState()
    object Importing : DeezerImportUiState()
    data class Completed(val result: DeezerDataImportService.ImportResult) : DeezerImportUiState()
    data class Error(val message: String) : DeezerImportUiState()
}

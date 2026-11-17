package me.avinas.tempo.ui.applemusic

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.data.applemusic.AppleMusicImportService
import me.avinas.tempo.worker.AppleMusicImportWorker

@HiltViewModel
class AppleMusicImportViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val importService: AppleMusicImportService,
    ) : ViewModel() {

    private val _uiState = MutableStateFlow<AppleMusicImportUiState>(AppleMusicImportUiState.Idle)
    val uiState: StateFlow<AppleMusicImportUiState> = _uiState.asStateFlow()

    val importState = importService.importState

    companion object {
        private const val STATE_PREFS = "apple_music_import_state"
        private const val PREF_ACTIVE_WORK_ID = "active_work_id"
    }

    private val statePrefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private var activeWorkId: java.util.UUID? =
        statePrefs.getString(PREF_ACTIVE_WORK_ID, null)
            ?.let { storedId -> runCatching { java.util.UUID.fromString(storedId) }.getOrNull() }

    /** Set when the user cancels before the worker exists yet (staging in progress). */
    private var cancelRequested = false

    private fun rememberActiveWork(id: java.util.UUID?) {
        activeWorkId = id
        statePrefs.edit().apply {
            if (id == null) remove(PREF_ACTIVE_WORK_ID) else putString(PREF_ACTIVE_WORK_ID, id.toString())
        }.apply()
    }

    init {
        // Live in-process progress while the app is alive.
        viewModelScope.launch {
            importService.importState.collect { state ->
                when (state) {
                    is AppleMusicImportService.ImportState.Parsing,
                    is AppleMusicImportService.ImportState.Importing -> {
                        _uiState.value = AppleMusicImportUiState.Importing
                    }

                    // Terminal states come exclusively from WorkManager below: the service can
                    // publish Completed/Error slightly before the unique work becomes terminal,
                    // and acting on it early races ExistingWorkPolicy.KEEP.
                    is AppleMusicImportService.ImportState.Completed,
                    is AppleMusicImportService.ImportState.Error,
                    is AppleMusicImportService.ImportState.Idle -> Unit
                }
            }
        }

        // Persistent observation: survives navigation and process death.
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(AppleMusicImportWorker.WORK_NAME)
                .collect { infos ->
                    val trackedInfo = activeWorkId?.let { id -> infos.firstOrNull { it.id == id } }
                    val info = trackedInfo
                        ?: infos.firstOrNull {
                            it.state == WorkInfo.State.ENQUEUED ||
                                it.state == WorkInfo.State.RUNNING ||
                                it.state == WorkInfo.State.BLOCKED
                        }?.also { rememberActiveWork(it.id) }
                        ?: return@collect

                    when (info.state) {
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.RUNNING,
                        WorkInfo.State.BLOCKED -> {
                            if (_uiState.value !is AppleMusicImportUiState.Importing) {
                                _uiState.value = AppleMusicImportUiState.Importing
                            }
                        }

                        WorkInfo.State.SUCCEEDED -> {
                            if (_uiState.value is AppleMusicImportUiState.Importing || activeWorkId == info.id) {
                                _uiState.value = AppleMusicImportUiState.Completed(
                                    AppleMusicImportService.ImportResult(
                                        tracksImported = info.outputData.getInt(AppleMusicImportWorker.KEY_TRACKS_IMPORTED, 0),
                                        eventsCreated = info.outputData.getInt(AppleMusicImportWorker.KEY_EVENTS_CREATED, 0),
                                        duplicatesSkipped = info.outputData.getInt(AppleMusicImportWorker.KEY_DUPLICATES_SKIPPED, 0),
                                        shortPlaysSkipped = info.outputData.getInt(AppleMusicImportWorker.KEY_SHORT_PLAYS_SKIPPED, 0),
                                        malformedRows = info.outputData.getInt(AppleMusicImportWorker.KEY_MALFORMED_ROWS, 0),
                                        totalEntries = info.outputData.getInt(AppleMusicImportWorker.KEY_TOTAL_ENTRIES, 0),
                                        artistRecoveredRows = info.outputData.getInt(AppleMusicImportWorker.KEY_ARTIST_RECOVERED, 0),
                                        errors = info.outputData
                                            .getNullableStringArray(AppleMusicImportWorker.KEY_WARNINGS)
                                            ?.filterNotNull()
                                            .orEmpty(),
                                    ),
                                )
                            }
                        }

                        WorkInfo.State.FAILED -> {
                            if (_uiState.value is AppleMusicImportUiState.Importing || activeWorkId == info.id) {
                                _uiState.value = AppleMusicImportUiState.Error(
                                    info.outputData.getString(AppleMusicImportWorker.KEY_ERROR_MESSAGE)
                                        ?: context.getString(R.string.apple_music_import_error_generic),
                                )
                            }
                        }

                        WorkInfo.State.CANCELLED -> {
                            if (_uiState.value is AppleMusicImportUiState.Importing || activeWorkId == info.id) {
                                _uiState.value = AppleMusicImportUiState.Error(
                                    context.getString(R.string.apple_music_import_error_cancelled),
                                )
                            }
                        }
                    }
                }
        }
    }

    fun importFile(context: Context, uri: Uri) {
        if (_uiState.value is AppleMusicImportUiState.Importing ||
            importService.importState.value is AppleMusicImportService.ImportState.Parsing ||
            importService.importState.value is AppleMusicImportService.ImportState.Importing
        ) {
            return
        }

        cancelRequested = false
        _uiState.value = AppleMusicImportUiState.Importing

        viewModelScope.launch {
            var stagedPath: String? = null
            try {
                // Copy while the ActivityResult grant is definitely alive; the worker then gets
                // a private file URI, so process-death recovery no longer depends on the
                // provider supporting persistable permissions.
                val stagedFile = importService.stageImportFile(context, uri)
                stagedPath = stagedFile.absolutePath

                // Staging a large export takes seconds; cancelling during it must not then
                // enqueue the very import the user just cancelled.
                if (cancelRequested) {
                    importService.deleteStagedImportFile(context, stagedPath)
                    stagedPath = null
                    _uiState.value = AppleMusicImportUiState.Idle
                    return@launch
                }

                val enqueueResult = AppleMusicImportWorker.enqueueImport(
                    context,
                    Uri.fromFile(stagedFile).toString(),
                )

                if (!enqueueResult.requestAccepted) {
                    // KEEP picked an already-running unique work; this staged copy has no owner.
                    importService.deleteStagedImportFile(context, stagedPath)
                    stagedPath = null
                }

                rememberActiveWork(enqueueResult.workId)
            } catch (e: CancellationException) {
                importService.deleteStagedImportFile(context, stagedPath)
                throw e
            } catch (e: Exception) {
                importService.deleteStagedImportFile(context, stagedPath)
                _uiState.value = AppleMusicImportUiState.Error(
                    AppleMusicImportService.userFacingError(e),
                )
            }
        }
    }

    fun cancelImport() {
        cancelRequested = true
        AppleMusicImportWorker.cancel(context)
    }

    fun resetState() {
        cancelRequested = true
        rememberActiveWork(null)
        _uiState.value = AppleMusicImportUiState.Idle
        importService.resetState()
    }
}

sealed class AppleMusicImportUiState {
    object Idle : AppleMusicImportUiState()
    object Importing : AppleMusicImportUiState()
    data class Completed(val result: AppleMusicImportService.ImportResult) : AppleMusicImportUiState()
    data class Error(val message: String) : AppleMusicImportUiState()
}

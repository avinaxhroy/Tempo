package me.avinas.tempo.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight event bus for coordinating data refresh across the app.
 *
 * When [MusicTrackingService] records a new listening event, it calls
 * [notifyNewTrackRecorded]. Active ViewModels that collect [refreshEvents]
 * will re-fetch their data so the UI stays up-to-date without navigating away.
 *
 * Battery impact: zero when no collectors are active (SharedFlow is cold).
 */
@Singleton
class RefreshCoordinator @Inject constructor() {

    private val _refreshEvents = MutableSharedFlow<RefreshEvent>(extraBufferCapacity = 1)
    val refreshEvents: SharedFlow<RefreshEvent> = _refreshEvents.asSharedFlow()

    /** Called by MusicTrackingService after saving a new ListeningEvent. */
    fun notifyNewTrackRecorded() {
        _refreshEvents.tryEmit(RefreshEvent.NewTrackRecorded)
    }
}

sealed class RefreshEvent {
    data object NewTrackRecorded : RefreshEvent()
}

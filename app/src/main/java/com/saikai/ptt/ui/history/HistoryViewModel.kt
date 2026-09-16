package com.saikai.ptt.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.saikai.ptt.audio.PlaybackState
import com.saikai.ptt.audio.RecordingPlayback
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.session.SessionState
import com.saikai.ptt.usecase.HistoryUseCases
import com.saikai.ptt.usecase.ObserveSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The history list and one record's detail.
 *
 * ### Which record the detail shows
 *
 * Held here as an id, not carried in the navigation destination. ADR-009 chose
 * a navigation without typed arguments on the grounds that nothing needed them;
 * this is the first screen that takes a parameter, and one field in the view
 * model that owns both screens is smaller than giving every destination a
 * payload for the sake of one.
 *
 * ### A transmission stops playback
 *
 * Watched here rather than inside the player, because the session is the view
 * model's business and the speaker is the player's. Task39 is explicit that
 * voice wins; doing it by audio focus instead would also stop a recording for
 * a notification chime, which is not the same thing.
 */
class HistoryViewModel(
    private val useCases: HistoryUseCases,
    private val playback: RecordingPlayback,
    observeSession: ObserveSession,
) : ViewModel() {

    val rows: StateFlow<List<HistoryRow>> = useCases.observeHistory()
        .map { records ->
            records.map { record ->
                HistoryRow(
                    id = record.id,
                    remoteUserName = record.remoteUserName,
                    direction = record.direction,
                    timestamp = record.timestamp,
                    durationMs = record.durationMs,
                    transcriptPreview = record.transcript?.lineSequence()?.firstOrNull()
                        ?.takeIf { it.isNotBlank() },
                    isRead = record.isRead,
                    isFavorite = record.isFavorite,
                    status = record.status,
                    playable = playback.exists(record.audioPath),
                )
            }
        }
        .distinctUntilChanged()
        // Off the main thread: the mapping asks the file system whether each
        // recording is still there, and a list of a few hundred stat calls on
        // the main thread is a visible stall on the reference device.
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unreadCount: StateFlow<Int> = useCases.observeUnreadCount()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _detail = MutableStateFlow<HistoryDetail?>(null)

    /** Null while the record is being read, and after the screen is left. */
    val detail: StateFlow<HistoryDetail?> = _detail.asStateFlow()

    val playbackState: StateFlow<PlaybackState> = playback.state

    init {
        // Any live session takes the speaker. Idle is the only state a
        // recording may play in.
        viewModelScope.launch {
            observeSession()
                .map { it != SessionState.Idle }
                .distinctUntilChanged()
                .collect { inSession -> if (inSession) playback.yieldToVoice() }
        }
    }

    /**
     * Opens a record.
     *
     * Marking it read is part of opening it (`docs/04_UI_UX.md` section 28) and
     * happens whether or not anything is played -- the user has seen it.
     */
    fun open(id: String) {
        playback.stop()
        _detail.value = null
        viewModelScope.launch {
            val record = (useCases.readRecord(id) as? Outcome.Success)?.value
            if (record == null) {
                // Deleted from under us, or a database that will not answer.
                // Leaving the detail null is what the screen already draws as
                // "nothing to show".
                return@launch
            }
            _detail.value = HistoryDetail(
                id = record.id,
                remoteUserName = record.remoteUserName,
                direction = record.direction,
                timestamp = record.timestamp,
                durationMs = record.durationMs,
                audioPath = record.audioPath,
                transcript = record.transcript,
                transcriptStatus = record.transcriptStatus,
                isFavorite = record.isFavorite,
                status = record.status,
                fileAvailable = playback.exists(record.audioPath),
            )
            if (!record.isRead) useCases.markRead(id)
        }
    }

    /** Leaving the detail screen. */
    fun close() {
        playback.stop()
        _detail.value = null
    }

    fun play() {
        val current = _detail.value ?: return
        playback.play(current.id, current.audioPath)
    }

    fun pause() = playback.pause()

    fun stopPlayback() = playback.stop()

    fun toggleFavorite() {
        val current = _detail.value ?: return
        val next = !current.isFavorite
        // Shown immediately and corrected by the list's own flow if the write
        // fails; a star that waits for a disk feels broken.
        _detail.value = current.copy(isFavorite = next)
        viewModelScope.launch {
            if (useCases.setFavorite(current.id, next) is Outcome.Failure) {
                _detail.value = _detail.value?.copy(isFavorite = current.isFavorite)
            }
        }
    }

    /** Why the play button is off, or null when it is on. See [playbackUnavailable]. */
    fun unavailable(detail: HistoryDetail): PlaybackUnavailable? = playbackUnavailable(detail)

    override fun onCleared() {
        playback.stop()
        super.onCleared()
    }

    class Factory(
        private val useCases: HistoryUseCases,
        private val playback: RecordingPlayback,
        private val observeSession: ObserveSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(useCases, playback, observeSession) as T
    }
}

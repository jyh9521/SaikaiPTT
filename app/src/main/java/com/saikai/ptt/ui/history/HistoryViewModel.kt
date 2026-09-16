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
import com.saikai.ptt.core.domain.HistoryRetention
import com.saikai.ptt.usecase.HistoryUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
 * ### Search is a flow, not a mode
 *
 * The query is a `StateFlow` the list flow is built from, so there is no
 * "searching" state to get out of sync with what is on screen and no separate
 * path for the empty term -- a blank query is the whole history
 * (`docs/05_DataModel.md` section 28).
 *
 * ### Selection lives here, and selecting is not deleting
 *
 * The screen asks; this decides nothing until a confirmation comes back.
 * `docs/05_DataModel.md` sections 38 and 39 require a confirmation for one
 * record and a second one before favourites go, so the prompts are modelled as
 * state rather than left to the screen: what is about to be deleted has to be
 * knowable by the thing that will delete it.
 *
 * ### A transmission stops playback
 *
 * Watched here rather than inside the player, because the session is the view
 * model's business and the speaker is the player's. Task39 is explicit that
 * voice wins; doing it by audio focus instead would also stop a recording for
 * a notification chime, which is not the same thing.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val useCases: HistoryUseCases,
    private val playback: RecordingPlayback,
    observeSession: ObserveSession,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    /** What is in the search box. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())

    /** Which rows are ticked. Empty means the list is not in selection mode. */
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _prompt = MutableStateFlow<DeletePrompt?>(null)

    /** The confirmation on screen, or null. */
    val prompt: StateFlow<DeletePrompt?> = _prompt.asStateFlow()

    private val _usage = MutableStateFlow<HistoryUsage?>(null)

    /** Null until the history settings screen has asked for it. */
    val usage: StateFlow<HistoryUsage?> = _usage.asStateFlow()

    val retention: StateFlow<HistoryRetention> = useCases.observeRetention()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryRetention.DEFAULT)

    private val _busy = MutableStateFlow(false)

    /** True while a delete or a cleanup pass is running, so the screen can wait. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val rows: StateFlow<List<HistoryRow>> = _query
        // The search runs against the database, so a keystroke is a query. 250 ms
        // is below what a person notices and above the fastest anyone types.
        .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS }
        .flatMapLatest { term -> useCases.observeHistory(term) }
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

    // --- search ------------------------------------------------------------------------

    fun search(term: String) {
        _query.value = term
    }

    fun clearSearch() {
        _query.value = ""
    }

    // --- selection ---------------------------------------------------------------------

    fun toggleSelected(id: String) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /**
     * Asks to delete what is ticked.
     *
     * The prompt carries how many of them are favourites, because
     * `docs/05_DataModel.md` section 39 wants that said out loud rather than
     * discovered afterwards.
     */
    fun requestDeleteSelected() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        val favorites = rows.value.count { it.id in ids && it.isFavorite }
        _prompt.value = DeletePrompt.Selected(ids, favorites)
    }

    /** Asks to delete the one record the detail screen is showing. */
    fun requestDeleteOpen() {
        val current = _detail.value ?: return
        _prompt.value = DeletePrompt.Selected(setOf(current.id), if (current.isFavorite) 1 else 0)
    }

    /** Asks to delete everything. Favourites are a second question. */
    fun requestClearAll() {
        _prompt.value = DeletePrompt.Everything(includeFavorites = false)
    }

    /** The second question of section 39, answered. */
    fun setClearFavorites(include: Boolean) {
        val current = _prompt.value as? DeletePrompt.Everything ?: return
        _prompt.value = current.copy(includeFavorites = include)
    }

    fun dismissPrompt() {
        _prompt.value = null
    }

    /** Carries out whatever [prompt] is asking about. */
    fun confirmPrompt() {
        val pending = _prompt.value ?: return
        _prompt.value = null
        _busy.value = true
        viewModelScope.launch {
            // Whatever goes may be what is playing. Stopping first means the
            // player is not holding a file that is about to disappear.
            playback.stop()
            when (pending) {
                is DeletePrompt.Selected -> {
                    useCases.deleteRecords(pending.ids)
                    val open = _detail.value?.id
                    if (open != null && open in pending.ids) _detail.value = null
                }

                is DeletePrompt.Everything -> {
                    useCases.clearHistory(pending.includeFavorites)
                    _detail.value = null
                }
            }
            _selection.value = emptySet()
            refreshUsage()
            _busy.value = false
        }
    }

    // --- history settings ---------------------------------------------------------------

    /** Counted when the screen opens, not observed (`docs/05_DataModel.md` section 45). */
    fun refreshUsage() {
        viewModelScope.launch { _usage.value = useCases.readUsage() }
    }

    fun setRetention(retention: HistoryRetention) {
        viewModelScope.launch { useCases.setRetention(retention) }
    }

    /** Runs a cleanup pass now, through the same mutex the service's loop uses. */
    fun cleanUpNow() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            useCases.runCleanup()
            refreshUsage()
            _busy.value = false
        }
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

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 250L
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

package com.saikai.ptt.usecase

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.CommunicationRecord
import com.saikai.ptt.core.domain.HistoryError
import com.saikai.ptt.core.domain.HistoryRepository
import com.saikai.ptt.core.domain.HistoryRetention
import com.saikai.ptt.core.domain.SettingsRepository
import com.saikai.ptt.core.domain.HistoryCounts
import com.saikai.ptt.core.history.CleanupReport
import com.saikai.ptt.core.history.HistoryEraser
import com.saikai.ptt.storage.history.HistoryMaintenance
import com.saikai.ptt.storage.history.LocalRecordingFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * What the history screens can observe and do.
 *
 * One bundle for the list, the detail and the history settings, because all
 * three are the same screen's business and splitting them would mean the
 * settings screen holding a second view model to ask how many records there
 * are.
 */
class HistoryUseCases(
    val observeHistory: ObserveHistory,
    val observeUnreadCount: ObserveUnreadCount,
    val readRecord: ReadRecord,
    val markRead: MarkRecordRead,
    val setFavorite: SetRecordFavorite,
    val deleteRecords: DeleteRecords,
    val clearHistory: ClearHistory,
    val readUsage: ReadHistoryUsage,
    val runCleanup: RunCleanup,
    val observeRetention: ObserveRetention,
    val setRetention: SetRetention,
)

/** How long records are kept (`docs/05_DataModel.md` section 29). */
class ObserveRetention(private val settings: SettingsRepository) {
    operator fun invoke(): Flow<HistoryRetention> =
        settings.settings.map { it.historyRetention }.distinctUntilChanged()
}

/**
 * Changes the retention window.
 *
 * Does not clean up. Shortening the window from thirty days to one deletes
 * conversations, and doing that inside the tap that moved a radio button gives
 * the user no moment to notice they picked the wrong one. The next scheduled
 * pass -- or the explicit "clean up now" -- is what acts on it.
 */
class SetRetention(private val settings: SettingsRepository) {
    suspend operator fun invoke(retention: HistoryRetention) {
        settings.update { it.copy(historyRetention = retention) }
    }
}

/**
 * Stored conversations, newest first, filtered by a search term.
 *
 * One use case rather than a separate "search", because a blank term is the
 * whole history: the screen has one flow and no mode to get out of sync
 * (`docs/05_DataModel.md` section 28).
 */
class ObserveHistory(private val history: HistoryRepository) {
    operator fun invoke(query: String = ""): Flow<List<CommunicationRecord>> =
        history.observeMatching(query)
}

/** How many received conversations have not been opened (`docs/05_DataModel.md` section 24). */
class ObserveUnreadCount(private val history: HistoryRepository) {
    operator fun invoke(): Flow<Int> = history.observeUnreadCount()
}

class ReadRecord(private val history: HistoryRepository) {
    suspend operator fun invoke(id: String): Outcome<CommunicationRecord?, HistoryError> =
        history.byId(id)
}

/**
 * Marks a conversation as read.
 *
 * Called when the detail screen opens, which is what `docs/04_UI_UX.md`
 * section 28 means by "opening a record marks it read" -- not when it scrolls
 * past in a list.
 */
class MarkRecordRead(private val history: HistoryRepository) {
    suspend operator fun invoke(id: String): Outcome<Unit, HistoryError> =
        history.markRead(id, read = true)
}

class SetRecordFavorite(private val history: HistoryRepository) {
    suspend operator fun invoke(id: String, favorite: Boolean): Outcome<Unit, HistoryError> =
        history.markFavorite(id, favorite)
}

/**
 * Deletes records the user chose, with their recordings.
 *
 * The confirmation belongs to the screen (`docs/05_DataModel.md` section 38
 * requires one); by the time this is called it has happened, and a favourite in
 * the selection goes like anything else.
 */
class DeleteRecords(private val eraser: HistoryEraser, private val history: HistoryRepository) {

    suspend operator fun invoke(ids: Collection<String>): Outcome<Int, HistoryError> {
        if (ids.isEmpty()) return Outcome.success(0)
        val records = mutableListOf<CommunicationRecord>()
        for (id in ids) {
            when (val found = history.byId(id)) {
                // A selection is made from a list that can change underneath
                // it; an id that has already gone is not a failure.
                is Outcome.Success -> found.value?.let { records += it }
                is Outcome.Failure -> return found
            }
        }
        return eraser.eraseAll(records)
    }
}

/**
 * Deletes the whole history.
 *
 * [includeFavorites] is the answer to the second confirmation section 39 asks
 * for, never a default: clearing the history must not quietly take the records
 * somebody marked as worth keeping.
 */
class ClearHistory(private val eraser: HistoryEraser) {
    suspend operator fun invoke(includeFavorites: Boolean): Outcome<Int, HistoryError> =
        eraser.eraseEverything(includeFavorites)
}

/**
 * How much there is and how much disk it takes.
 *
 * Computed on demand, when the history settings open, and not observed:
 * `docs/05_DataModel.md` section 45 is explicit that this does not need to be
 * live, and a directory walk on every recomposition would be the wrong way to
 * show a number that changes once a day.
 */
class ReadHistoryUsage(
    private val history: HistoryRepository,
    private val files: LocalRecordingFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend operator fun invoke(): HistoryUsage = withContext(io) {
        val counts = history.counts().valueOrNull() ?: HistoryCounts(0, 0)
        HistoryUsage(
            total = counts.total,
            favorites = counts.favorites,
            bytes = files.totalBytes(),
        )
    }
}

/** What the history settings screen shows above the cleanup button. */
data class HistoryUsage(val total: Int, val favorites: Int, val bytes: Long)

/**
 * Runs a cleanup pass now.
 *
 * The same pass the service runs on its own schedule, through the same mutex,
 * so the button cannot race the loop.
 */
class RunCleanup(private val maintenance: HistoryMaintenance) {
    suspend operator fun invoke(): CleanupReport = maintenance.runOnce()
}

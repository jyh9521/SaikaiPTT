package com.saikai.ptt.usecase

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.CommunicationRecord
import com.saikai.ptt.core.domain.HistoryError
import com.saikai.ptt.core.domain.HistoryRepository
import kotlinx.coroutines.flow.Flow

/**
 * What the history screens can observe and do.
 *
 * Reading and two flags. Searching, deleting and cleanup are Task40's and are
 * deliberately absent rather than stubbed: a screen with a search box that does
 * not search is worse than one without.
 */
class HistoryUseCases(
    val observeHistory: ObserveHistory,
    val observeUnreadCount: ObserveUnreadCount,
    val readRecord: ReadRecord,
    val markRead: MarkRecordRead,
    val setFavorite: SetRecordFavorite,
)

/** Every stored conversation, newest first. */
class ObserveHistory(private val history: HistoryRepository) {
    operator fun invoke(): Flow<List<CommunicationRecord>> = history.observeRecent()
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

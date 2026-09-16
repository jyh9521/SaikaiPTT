package com.saikai.ptt.core.domain

import com.saikai.ptt.core.common.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * Stored conversations.
 *
 * ### Nothing here throws
 *
 * Every method returns an [Outcome], and that is the whole design.
 * `docs/05_DataModel.md` section 48 requires that a history failure cannot stop
 * discovery, heartbeat or voice, and an interface that throws makes that a
 * promise each caller has to keep by remembering a try/catch. A disk that is
 * full is an ordinary condition on a phone; it must cost the user a missing
 * history row, never a missed call.
 *
 * The reading side is the exception that proves it: a [Flow] cannot report a
 * failure as a value without wrapping every emission, so [observeRecent] emits
 * an empty list when the query cannot run. An empty history is what the user
 * would see anyway, and Task40's screen shows the same empty state either way.
 */
interface HistoryRepository {

    /**
     * Stores a finished conversation.
     *
     * Idempotent by session: `sessionId` is unique, so the same session stored
     * twice keeps the first row rather than producing two. Both devices write
     * their own record for the same session, which is fine -- they are
     * different databases.
     */
    suspend fun save(record: CommunicationRecord): Outcome<Unit, HistoryError>

    /** Newest first, up to [limit]. Re-emits on every change. */
    fun observeRecent(limit: Int = DEFAULT_PAGE): Flow<List<CommunicationRecord>>

    suspend fun byId(id: String): Outcome<CommunicationRecord?, HistoryError>

    suspend fun bySession(sessionId: String): Outcome<CommunicationRecord?, HistoryError>

    /** How many received records have not been opened yet (section 24). */
    fun observeUnreadCount(): Flow<Int>

    suspend fun markRead(id: String, read: Boolean = true): Outcome<Unit, HistoryError>

    suspend fun markFavorite(id: String, favorite: Boolean): Outcome<Unit, HistoryError>

    suspend fun delete(id: String): Outcome<Unit, HistoryError>

    companion object {
        const val DEFAULT_PAGE: Int = 200
    }
}

/** Why a history operation did not happen. */
sealed interface HistoryError {

    /** The row is not there. Not an error the user needs to see. */
    data class NotFound(val id: String) : HistoryError

    /**
     * The database refused or could not be reached.
     *
     * Deliberately one value rather than a taxonomy of SQLite results. Nothing
     * in the app behaves differently for a full disk than for a corrupt file:
     * both mean this record is not stored and the conversation itself was
     * unaffected. The specific cause goes to the log
     * (`docs/05_DataModel.md` section 26.1 makes the same argument for
     * `RecordStatus`).
     */
    data class Unavailable(val reason: String) : HistoryError
}

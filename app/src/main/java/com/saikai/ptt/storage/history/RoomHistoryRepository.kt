package com.saikai.ptt.storage.history

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.CommunicationRecord
import com.saikai.ptt.core.domain.HistoryError
import com.saikai.ptt.core.domain.HistoryRepository
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [HistoryRepository] on Room.
 *
 * ### Nothing that happens here reaches the caller as an exception
 *
 * `docs/05_DataModel.md` section 48 requires that a history failure cannot stop
 * discovery, heartbeat or voice. Making every method return an [Outcome] states
 * that; this class is what makes it true. A full disk, a corrupt file, a
 * migration that is not there -- all of them arrive as
 * [HistoryError.Unavailable] and a WARN in the log, and the conversation that
 * produced the record was over before this was called anyway.
 *
 * `CancellationException` is deliberately let through. It is not a failure: it
 * means the caller's scope is going away, and swallowing it would report a
 * storage error for a service that is simply shutting down, as well as breaking
 * structured concurrency.
 *
 * ### Off the caller's thread
 *
 * Room enforces this for suspend functions already; the dispatcher is stated
 * anyway so that the Flows are collected on IO as well, and so a test can
 * substitute one.
 */
class RoomHistoryRepository(
    private val dao: CommunicationRecordDao,
    private val logger: Logger,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HistoryRepository {

    override suspend fun save(record: CommunicationRecord): Outcome<Unit, HistoryError> =
        guard("save") {
            val stored = dao.save(record.toEntity())
            if (!stored) {
                // Not an error. sessionId is unique, so this is a retry or a
                // second path to the same session, and the first row stands.
                logger.i(LogCategory.STORAGE) {
                    "a record for this session already exists; keeping the first"
                }
            }
        }

    override fun observeRecent(limit: Int): Flow<List<CommunicationRecord>> =
        dao.observeRecent(limit)
            .map { rows -> rows.map { it.toDomain() } }
            .catch { error ->
                // A Flow cannot carry a typed failure without wrapping every
                // emission. An empty list is what the user would see anyway,
                // and the screen's empty state is the same either way.
                logger.e(LogCategory.STORAGE, error) { "could not read the history" }
                emit(emptyList())
            }
            .flowOn(io)

    override suspend fun byId(id: String): Outcome<CommunicationRecord?, HistoryError> =
        guard("byId") { dao.byId(id)?.toDomain() }

    override suspend fun bySession(
        sessionId: String,
    ): Outcome<CommunicationRecord?, HistoryError> =
        guard("bySession") { dao.bySession(sessionId)?.toDomain() }

    override fun observeUnreadCount(): Flow<Int> =
        dao.observeUnreadCount()
            .catch { error ->
                logger.e(LogCategory.STORAGE, error) { "could not count unread records" }
                emit(0)
            }
            .flowOn(io)

    override suspend fun markRead(id: String, read: Boolean): Outcome<Unit, HistoryError> =
        update(id) { dao.setRead(id, read, System.currentTimeMillis()) }

    override suspend fun markFavorite(
        id: String,
        favorite: Boolean,
    ): Outcome<Unit, HistoryError> =
        update(id) { dao.setFavorite(id, favorite, System.currentTimeMillis()) }

    override suspend fun delete(id: String): Outcome<Unit, HistoryError> =
        update(id) { dao.deleteById(id) }

    /** Turns "no rows touched" into [HistoryError.NotFound] rather than silent success. */
    private suspend fun update(
        id: String,
        block: suspend () -> Int,
    ): Outcome<Unit, HistoryError> {
        val outcome = guard("update") { block() }
        return when (outcome) {
            is Outcome.Failure -> outcome
            is Outcome.Success ->
                if (outcome.value > 0) Outcome.success(Unit)
                else Outcome.failure(HistoryError.NotFound(id))
        }
    }

    private suspend fun <T> guard(
        operation: String,
        block: suspend () -> T,
    ): Outcome<T, HistoryError> = withContext(io) {
        try {
            Outcome.success(block())
        } catch (cancellation: CancellationException) {
            // The scope is going away. Not a storage failure, and swallowing it
            // would break structured concurrency.
            throw cancellation
        } catch (error: Throwable) {
            logger.e(LogCategory.STORAGE, error) { "history $operation failed" }
            Outcome.failure(HistoryError.Unavailable(error.javaClass.simpleName))
        }
    }
}

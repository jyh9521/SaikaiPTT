package com.saikai.ptt.storage.history

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.CommunicationRecord
import com.saikai.ptt.core.domain.HistoryCounts
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

    override fun observeMatching(
        query: String,
        limit: Int,
    ): Flow<List<CommunicationRecord>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return observeRecent(limit)
        return dao.observeMatching(likePattern(trimmed), limit)
            .map { rows -> rows.map { it.toDomain() } }
            .catch { error ->
                logger.e(LogCategory.STORAGE, error) { "could not search the history" }
                emit(emptyList())
            }
            .flowOn(io)
    }

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

    override suspend fun deleteAll(ids: Collection<String>): Outcome<Int, HistoryError> =
        guard("deleteAll") {
            // Chunked because SQLite binds a limited number of parameters --
            // 999 on the versions this app's minimum ships with -- and the call
            // that would exceed it is "delete my whole history", which is
            // exactly the one that must not fail.
            ids.distinct().chunked(SQLITE_VARIABLES).sumOf { dao.deleteByIds(it) }
        }

    override suspend fun expiredBefore(
        beforeMillis: Long,
        limit: Int,
    ): Outcome<List<CommunicationRecord>, HistoryError> =
        guard("expiredBefore") {
            dao.expiredBefore(beforeMillis, limit).map { it.toDomain() }
        }

    override suspend fun audioPaths(): Outcome<Set<String>, HistoryError> =
        guard("audioPaths") { dao.audioPaths().toSet() }

    override suspend fun favorites(
        limit: Int,
    ): Outcome<List<CommunicationRecord>, HistoryError> =
        guard("favorites") { dao.favorites(limit).map { it.toDomain() } }

    override suspend fun counts(): Outcome<HistoryCounts, HistoryError> =
        guard("counts") { HistoryCounts(dao.count(), dao.favoriteCount()) }

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

    /**
     * Wraps a search term for `LIKE`, escaping what `LIKE` would otherwise read
     * as a wildcard.
     *
     * `%` and `_` are wildcards in SQL and ordinary characters to a user. A
     * name with an underscore in it would otherwise match every name of the
     * same length, and a transcript search for "100%" would match everything.
     * The backslash is escaped first, or escaping the others would undo it.
     */
    private fun likePattern(query: String): String {
        val escaped = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
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

    private companion object {
        /**
         * How many ids go into one `IN (...)`.
         *
         * SQLite's `SQLITE_MAX_VARIABLE_NUMBER` is 999 before 3.32 and Android
         * ships whichever version the device's platform has. 900 leaves room
         * for the statement's own parameters and needs no version check.
         */
        const val SQLITE_VARIABLES = 900
    }
}

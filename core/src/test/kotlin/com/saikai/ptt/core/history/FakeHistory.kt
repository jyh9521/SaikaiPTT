package com.saikai.ptt.core.history

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.AudioFormat
import com.saikai.ptt.core.domain.CommunicationRecord
import com.saikai.ptt.core.domain.Direction
import com.saikai.ptt.core.domain.HistoryCounts
import com.saikai.ptt.core.domain.HistoryError
import com.saikai.ptt.core.domain.HistoryRepository
import com.saikai.ptt.core.domain.RecordStatus
import com.saikai.ptt.core.domain.TranscriptStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * An in-memory history and an in-memory recordings directory, for the cleanup
 * tests.
 *
 * Deliberately not mocks. What is being tested is an order of operations across
 * two stores that cannot share a transaction, and the only way to see that
 * order is to let both stores actually hold state and then look at what is left
 * when the pass is interrupted.
 */
internal class FakeHistory(
    records: List<CommunicationRecord> = emptyList(),
) : HistoryRepository {

    val rows: MutableList<CommunicationRecord> = records.toMutableList()

    /** Set to make every read fail, as a database that will not answer does. */
    var readable: Boolean = true

    /** Set to make deletes fail. */
    var writable: Boolean = true

    var expiredQueries: Int = 0
        private set

    override suspend fun save(record: CommunicationRecord): Outcome<Unit, HistoryError> {
        rows += record
        return Outcome.success(Unit)
    }

    override fun observeRecent(limit: Int): Flow<List<CommunicationRecord>> = flowOf(rows.toList())

    override fun observeMatching(query: String, limit: Int): Flow<List<CommunicationRecord>> =
        flowOf(rows.toList())

    override suspend fun byId(id: String): Outcome<CommunicationRecord?, HistoryError> =
        Outcome.success(rows.firstOrNull { it.id == id })

    override suspend fun bySession(
        sessionId: String,
    ): Outcome<CommunicationRecord?, HistoryError> =
        Outcome.success(rows.firstOrNull { it.sessionId == sessionId })

    override fun observeUnreadCount(): Flow<Int> = flowOf(rows.count { !it.isRead })

    override suspend fun markRead(id: String, read: Boolean): Outcome<Unit, HistoryError> =
        Outcome.success(Unit)

    override suspend fun markFavorite(
        id: String,
        favorite: Boolean,
    ): Outcome<Unit, HistoryError> = Outcome.success(Unit)

    override suspend fun delete(id: String): Outcome<Unit, HistoryError> {
        rows.removeAll { it.id == id }
        return Outcome.success(Unit)
    }

    override suspend fun deleteAll(ids: Collection<String>): Outcome<Int, HistoryError> {
        if (!writable) return Outcome.failure(HistoryError.Unavailable("test"))
        val before = rows.size
        rows.removeAll { it.id in ids }
        return Outcome.success(before - rows.size)
    }

    override suspend fun expiredBefore(
        beforeMillis: Long,
        limit: Int,
    ): Outcome<List<CommunicationRecord>, HistoryError> {
        expiredQueries++
        if (!readable) return Outcome.failure(HistoryError.Unavailable("test"))
        return Outcome.success(
            rows.filter { !it.isFavorite && it.timestamp < beforeMillis }.take(limit)
        )
    }

    override suspend fun audioPaths(): Outcome<Set<String>, HistoryError> {
        if (!readable) return Outcome.failure(HistoryError.Unavailable("test"))
        return Outcome.success(rows.mapNotNull { it.audioPath }.toSet())
    }

    override suspend fun favorites(
        limit: Int,
    ): Outcome<List<CommunicationRecord>, HistoryError> {
        if (!readable) return Outcome.failure(HistoryError.Unavailable("test"))
        return Outcome.success(rows.filter { it.isFavorite }.take(limit))
    }

    override suspend fun pendingTranscripts(
        limit: Int,
    ): Outcome<List<CommunicationRecord>, HistoryError> {
        if (!readable) return Outcome.failure(HistoryError.Unavailable("test"))
        return Outcome.success(
            rows.filter { it.transcriptStatus == TranscriptStatus.PENDING && it.audioPath != null }
                .sortedBy { it.timestamp }
                .take(limit)
        )
    }

    override suspend fun setTranscript(
        id: String,
        transcript: String?,
        status: TranscriptStatus,
    ): Outcome<Unit, HistoryError> {
        if (!writable) return Outcome.failure(HistoryError.Unavailable("test"))
        val at = rows.indexOfFirst { it.id == id }
        if (at < 0) return Outcome.failure(HistoryError.NotFound(id))
        rows[at] = rows[at].copy(transcript = transcript, transcriptStatus = status)
        return Outcome.success(Unit)
    }

    override suspend fun requestTranscripts(
        ids: Collection<String>,
    ): Outcome<Int, HistoryError> {
        if (!writable) return Outcome.failure(HistoryError.Unavailable("test"))
        var changed = 0
        ids.forEach { id ->
            val at = rows.indexOfFirst { it.id == id }
            if (at >= 0) {
                rows[at] = rows[at].copy(transcriptStatus = TranscriptStatus.PENDING)
                changed++
            }
        }
        return Outcome.success(changed)
    }

    override suspend fun counts(): Outcome<HistoryCounts, HistoryError> =
        Outcome.success(HistoryCounts(rows.size, rows.count { it.isFavorite }))
}

/** An in-memory recordings directory. */
internal class FakeRecordingFiles(
    initial: Map<String, Long> = emptyMap(),
) : RecordingFiles {

    /** path -> last modified. */
    val present: MutableMap<String, Long> = initial.toMutableMap()

    /** Paths that refuse to be deleted, as a file held open by another process does. */
    val undeletable: MutableSet<String> = mutableSetOf()

    var pruned: Int = 0
        private set

    override fun list(): List<String> = present.keys.sorted()

    override fun lastModified(path: String): Long? = present[path]

    override fun sizeOf(path: String): Long = if (path in present) 1024L else 0L

    override fun delete(path: String): Boolean {
        if (path in undeletable) return false
        return present.remove(path) != null
    }

    override fun pruneEmptyDirectories() {
        pruned++
    }
}

internal fun record(
    id: String,
    timestamp: Long,
    audioPath: String? = "records/2026/09/16/$id.opus",
    favorite: Boolean = false,
): CommunicationRecord = CommunicationRecord(
    id = id,
    sessionId = "session-$id",
    senderDeviceId = "remote",
    receiverDeviceId = "local",
    remoteDeviceId = "remote",
    localUserId = "user",
    remoteUserName = "山田",
    direction = Direction.RECEIVE,
    timestamp = timestamp,
    durationMs = 1_000,
    audioPath = audioPath,
    audioFormat = if (audioPath == null) null else AudioFormat.OPUS,
    transcript = null,
    transcriptStatus = TranscriptStatus.NOT_REQUESTED,
    isRead = false,
    isFavorite = favorite,
    status = RecordStatus.COMPLETED,
    createdAt = timestamp,
    updatedAt = timestamp,
)

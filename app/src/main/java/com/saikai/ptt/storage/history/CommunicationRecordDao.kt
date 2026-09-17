package com.saikai.ptt.storage.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.saikai.ptt.core.domain.TranscriptStatus
import kotlinx.coroutines.flow.Flow

/**
 * Every query the history makes.
 *
 * Each one is served by an index declared on the entity, and there is no query
 * here that is not: a full scan of this table is cheap today and stops being
 * cheap on the device of somebody who talks all day and keeps everything
 * forever.
 */
@Dao
interface CommunicationRecordDao {

    /**
     * Stores a record, or leaves the existing one alone.
     *
     * `IGNORE` rather than `REPLACE`, and the difference matters. `sessionId`
     * is unique, so a second write for the same session is either a retry or a
     * bug; `REPLACE` would delete the first row and insert a new one with a new
     * record id, orphaning the audio file that was named after the old one
     * (`docs/05_DataModel.md` section 20).
     *
     * @return the new row id, or -1 when a record for that session was already
     *   there.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: CommunicationRecordEntity): Long

    /**
     * The whole of creating a record, in one transaction.
     *
     * One statement today, and deliberately a method rather than a bare
     * `insert` call: Task38 associates a recording with a record and has to do
     * both or neither, and this is where that goes.
     *
     * @return true when this call is what stored it.
     */
    @Transaction
    suspend fun save(record: CommunicationRecordEntity): Boolean = insert(record) != -1L

    @Query("SELECT * FROM communication_record ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CommunicationRecordEntity>>

    /**
     * The search `docs/05_DataModel.md` section 27.1 specifies: `LIKE`, over
     * the remote name and the transcript.
     *
     * `:pattern` arrives already wrapped in `%`, built by the repository, so
     * the user's own `%` and `_` can be escaped there -- a name containing an
     * underscore must not match every name. `ESCAPE '\\'` is what makes that
     * escaping mean anything to SQLite.
     *
     * SQLite's `LIKE` is case-insensitive for ASCII only. That is the whole of
     * the effect for Japanese, Chinese, Burmese and Bengali, which have no
     * case, and it is what an English speaker expects for the fifth language.
     */
    @Query(
        """
        SELECT * FROM communication_record
        WHERE remoteUserName LIKE :pattern ESCAPE '\'
           OR (transcript IS NOT NULL AND transcript LIKE :pattern ESCAPE '\')
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    fun observeMatching(pattern: String, limit: Int): Flow<List<CommunicationRecordEntity>>

    @Query("SELECT * FROM communication_record WHERE id = :id")
    suspend fun byId(id: String): CommunicationRecordEntity?

    @Query("SELECT * FROM communication_record WHERE sessionId = :sessionId")
    suspend fun bySession(sessionId: String): CommunicationRecordEntity?

    @Query("SELECT * FROM communication_record WHERE remoteDeviceId = :deviceId ORDER BY timestamp DESC")
    suspend fun byRemoteDevice(deviceId: String): List<CommunicationRecordEntity>

    /**
     * Unread counts only what was received.
     *
     * A record this device sent starts read and stays read (`docs/05_DataModel.md`
     * section 24); counting those would show a badge for the user's own voice.
     */
    @Query("SELECT COUNT(*) FROM communication_record WHERE direction = 'RECEIVE' AND isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("UPDATE communication_record SET isRead = :read, updatedAt = :now WHERE id = :id")
    suspend fun setRead(id: String, read: Boolean, now: Long): Int

    @Query("UPDATE communication_record SET isFavorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, now: Long): Int

    @Query("DELETE FROM communication_record WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /**
     * Deletes a set of records.
     *
     * Chunked by the caller: SQLite's parameter limit is 999 on old versions,
     * and a bulk delete of a whole history would otherwise fail at exactly the
     * moment it matters.
     */
    @Query("DELETE FROM communication_record WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    /**
     * What automatic cleanup may remove.
     *
     * `isFavorite = 0` is part of the query rather than a filter applied
     * afterwards: `docs/05_DataModel.md` section 25 exempts favourites from
     * cleanup at every retention setting, and a rule enforced in the query is
     * one no caller can skip. Oldest first, so a batched sweep makes progress
     * from the far end.
     */
    @Query(
        """
        SELECT * FROM communication_record
        WHERE timestamp < :beforeMillis AND isFavorite = 0
        ORDER BY timestamp ASC
        LIMIT :limit
        """
    )
    suspend fun expiredBefore(beforeMillis: Long, limit: Int): List<CommunicationRecordEntity>

    /** The favourites, which [expiredBefore] never returns. */
    @Query(
        """
        SELECT * FROM communication_record
        WHERE isFavorite = 1
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun favorites(limit: Int): List<CommunicationRecordEntity>

    /**
     * Recordings waiting to be recognised, oldest first.
     *
     * `audioPath IS NOT NULL` is part of the query: a record whose recording
     * was never written has nothing to transcribe, and returning it would make
     * the queue retry a file that does not exist until its allowance ran out.
     *
     * Served by the `transcriptStatus` index Task37 declared for exactly this
     * (`docs/05_DataModel.md` section 27).
     */
    @Query(
        """
        SELECT * FROM communication_record
        WHERE transcriptStatus = :pending AND audioPath IS NOT NULL
        ORDER BY timestamp ASC
        LIMIT :limit
        """
    )
    suspend fun pendingTranscripts(
        pending: TranscriptStatus,
        limit: Int,
    ): List<CommunicationRecordEntity>

    @Query(
        """
        UPDATE communication_record
        SET transcript = :transcript, transcriptStatus = :status, updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun setTranscript(
        id: String,
        transcript: String?,
        status: TranscriptStatus,
        now: Long,
    ): Int

    /**
     * Marks records for recognition.
     *
     * Only ones that have audio and are not already queued or done -- asking
     * again for something already PROCESSING would let two workers pick it up.
     */
    @Query(
        """
        UPDATE communication_record
        SET transcriptStatus = :pending, updatedAt = :now
        WHERE id IN (:ids)
          AND audioPath IS NOT NULL
          AND transcriptStatus IN (:requestable)
        """
    )
    suspend fun requestTranscripts(
        ids: List<String>,
        pending: TranscriptStatus,
        requestable: List<TranscriptStatus>,
        now: Long,
    ): Int

    /** Every path the database points at, for the orphan scan (section 31). */
    @Query("SELECT audioPath FROM communication_record WHERE audioPath IS NOT NULL")
    suspend fun audioPaths(): List<String>

    @Query("SELECT COUNT(*) FROM communication_record")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM communication_record WHERE isFavorite = 1")
    suspend fun favoriteCount(): Int
}

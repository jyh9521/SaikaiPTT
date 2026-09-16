package com.saikai.ptt.storage.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT COUNT(*) FROM communication_record")
    suspend fun count(): Int
}

package com.saikai.ptt.storage.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.saikai.ptt.core.domain.AudioFormat
import com.saikai.ptt.core.domain.CommunicationRecord
import com.saikai.ptt.core.domain.Direction
import com.saikai.ptt.core.domain.RecordStatus
import com.saikai.ptt.core.domain.TranscriptStatus

/**
 * The stored shape of a [CommunicationRecord].
 *
 * Field for field with `docs/05_DataModel.md` section 10, which is the only
 * definition of that list. Column names match the property names deliberately:
 * a rename here is a migration, and an alias would put the schema's real names
 * one indirection away from the document that specifies them.
 *
 * Separate from the domain class rather than annotating it, because the domain
 * class lives in `:core` and `:core` may not see `androidx` at all. That
 * boundary is what lets every rule about a record be proved in a JVM test
 * (`docs/02_Architecture.md` section 5.1); the price is this file, which is a
 * copy of the field list and two mapping functions with no logic in them.
 *
 * ### The indices are the point of the table
 *
 * All eight come from section 27, and each one exists for a query the product
 * actually makes:
 *
 *  - `timestamp` descending -- the history list's default order, and the only
 *    order it has.
 *  - `sessionId`, **unique** -- the join between the protocol and the database,
 *    and what makes storing the same session twice impossible rather than
 *    merely unlikely.
 *  - `remoteDeviceId` -- "everything with this device", as one indexed column
 *    instead of an OR across sender and receiver.
 *  - `remoteUserName` -- name search.
 *  - `isRead` -- the unread count, which is read on every screen.
 *  - `isFavorite` -- favourites, and the cleanup that must not delete them.
 *  - `transcriptStatus` -- the ASR worker asking what is still pending.
 *  - `status` -- finding the records that went wrong.
 */
@Entity(
    tableName = "communication_record",
    indices = [
        Index(value = ["timestamp"], orders = [Index.Order.DESC]),
        Index(value = ["sessionId"], unique = true),
        Index(value = ["remoteDeviceId"]),
        Index(value = ["remoteUserName"]),
        Index(value = ["isRead"]),
        Index(value = ["isFavorite"]),
        Index(value = ["transcriptStatus"]),
        Index(value = ["status"]),
    ],
)
data class CommunicationRecordEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val senderDeviceId: String,
    val receiverDeviceId: String,
    val remoteDeviceId: String,
    val localUserId: String,
    val remoteUserName: String,
    val direction: Direction,
    val timestamp: Long,
    val durationMs: Long,
    val audioPath: String?,
    val audioFormat: AudioFormat?,
    val transcript: String?,
    val transcriptStatus: TranscriptStatus,
    val isRead: Boolean,
    val isFavorite: Boolean,
    val status: RecordStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

internal fun CommunicationRecord.toEntity(): CommunicationRecordEntity =
    CommunicationRecordEntity(
        id = id,
        sessionId = sessionId,
        senderDeviceId = senderDeviceId,
        receiverDeviceId = receiverDeviceId,
        remoteDeviceId = remoteDeviceId,
        localUserId = localUserId,
        remoteUserName = remoteUserName,
        direction = direction,
        timestamp = timestamp,
        durationMs = durationMs,
        audioPath = audioPath,
        audioFormat = audioFormat,
        transcript = transcript,
        transcriptStatus = transcriptStatus,
        isRead = isRead,
        isFavorite = isFavorite,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/**
 * Back to the domain shape.
 *
 * `CommunicationRecord` rejects a format without a path, and a damaged row
 * could hold exactly that -- so the pair is dropped together rather than
 * allowed to throw on the way out of the database. `docs/05_DataModel.md`
 * section 41 requires damaged local data to degrade, and a history list that
 * crashes on one bad row is the whole feature gone for one row's sake.
 */
internal fun CommunicationRecordEntity.toDomain(): CommunicationRecord {
    val path = audioPath?.takeIf { audioFormat != null }
    return CommunicationRecord(
        id = id,
        sessionId = sessionId,
        senderDeviceId = senderDeviceId,
        receiverDeviceId = receiverDeviceId,
        remoteDeviceId = remoteDeviceId,
        localUserId = localUserId,
        remoteUserName = remoteUserName,
        direction = direction,
        timestamp = timestamp,
        durationMs = durationMs,
        audioPath = path,
        audioFormat = if (path == null) null else audioFormat,
        transcript = transcript,
        transcriptStatus = transcriptStatus,
        isRead = isRead,
        isFavorite = isFavorite,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/**
 * Enums are stored by name, and read back by a lookup that cannot throw.
 *
 * `valueOf` would be the obvious implementation and the wrong one: a value
 * written by a newer build and read by an older one -- a downgrade, or a
 * restored backup -- would take down every query that touched the row. The
 * `fromName` functions in `:core` fall back instead, and are tested there.
 */
object HistoryConverters {

    @TypeConverter
    fun directionToName(value: Direction): String = value.name

    @TypeConverter
    fun directionFromName(value: String?): Direction = Direction.fromName(value)

    @TypeConverter
    fun audioFormatToName(value: AudioFormat?): String? = value?.name

    @TypeConverter
    fun audioFormatFromName(value: String?): AudioFormat? = AudioFormat.fromName(value)

    @TypeConverter
    fun transcriptStatusToName(value: TranscriptStatus): String = value.name

    @TypeConverter
    fun transcriptStatusFromName(value: String?): TranscriptStatus =
        TranscriptStatus.fromName(value)

    @TypeConverter
    fun recordStatusToName(value: RecordStatus): String = value.name

    @TypeConverter
    fun recordStatusFromName(value: String?): RecordStatus = RecordStatus.fromName(value)
}

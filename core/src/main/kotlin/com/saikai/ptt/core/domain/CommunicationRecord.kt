package com.saikai.ptt.core.domain

import java.util.UUID

/**
 * One completed PTT conversation, as it is stored.
 *
 * The field list is `docs/05_DataModel.md` section 10 and that section is the
 * only definition of it. `.claude/CLAUDE.md` section 16 and `docs/01_PRD.md`
 * section 27 both defer to it, and this class is the code that has to match --
 * so nothing is added here without adding it there first, because every column
 * change past version 1 costs a migration.
 *
 * A pure Kotlin value in `core`, not the Room entity. The entity lives in
 * `app.storage` with the annotations; this is what the rest of the app passes
 * around, and keeping them apart is what lets every rule below be proved in a
 * JVM test rather than on a device.
 *
 * ### The redundant column is deliberate
 *
 * [remoteDeviceId] is always one of [senderDeviceId] and [receiverDeviceId].
 * Storing it anyway turns "show me everything with this device" into one
 * indexed column instead of an OR across two, on every query
 * (`docs/05_DataModel.md` section 10). It is computed once, at creation, by
 * [create] -- which is why that factory exists rather than a bare constructor
 * call at each call site.
 */
data class CommunicationRecord(
    /** Also the audio file's name (`docs/05_DataModel.md` section 20). */
    val id: String,
    /** The PTT session this came from. Unique: one session, at most one record. */
    val sessionId: String,
    val senderDeviceId: String,
    val receiverDeviceId: String,
    /** Whichever of the two above is not this device. Never recomputed on read. */
    val remoteDeviceId: String,
    /** Which local name this device was using at the time (section 15). */
    val localUserId: String,
    /**
     * What the peer called itself when this happened.
     *
     * A snapshot, not a lookup. The peer may rename itself afterwards and the
     * history still has to show the name that was on screen at the time
     * (section 16).
     */
    val remoteUserName: String,
    val direction: Direction,
    /** UTC epoch milliseconds. Formatted by the UI, never stored formatted. */
    val timestamp: Long,
    val durationMs: Long,
    /**
     * Path inside the app's private files directory, or null when nothing was
     * saved.
     *
     * Relative on purpose: an absolute path embeds a directory Android is free
     * to move (section 19).
     */
    val audioPath: String?,
    /** Null exactly when [audioPath] is null. */
    val audioFormat: AudioFormat?,
    val transcript: String?,
    val transcriptStatus: TranscriptStatus,
    val isRead: Boolean,
    val isFavorite: Boolean,
    val status: RecordStatus,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        // The one invariant the schema cannot express. A format without a file
        // is a record claiming an encoding for audio that does not exist, and
        // the cleanup in Task40 would go looking for it.
        require((audioPath == null) == (audioFormat == null)) {
            "audioPath and audioFormat are set together or not at all"
        }
    }

    companion object {

        /**
         * Builds a record, computing everything that can be got wrong by hand.
         *
         * Three things are derived rather than passed: which device is the
         * remote one, which direction this was, and whether it starts read.
         * Every one of them is a restatement of "who is this device", and a
         * caller that got one of them wrong would write a row that looks
         * perfectly valid and sorts into the wrong list forever.
         *
         * @param localDeviceId this installation. Everything else follows from
         *   comparing it against the sender.
         */
        fun create(
            localDeviceId: String,
            sessionId: String,
            senderDeviceId: String,
            receiverDeviceId: String,
            localUserId: String,
            remoteUserName: String,
            timestamp: Long,
            durationMs: Long,
            status: RecordStatus,
            audioPath: String? = null,
            audioFormat: AudioFormat? = null,
            now: Long = timestamp,
            id: String = UUID.randomUUID().toString(),
        ): CommunicationRecord {
            val outgoing = senderDeviceId == localDeviceId
            return CommunicationRecord(
                id = id,
                sessionId = sessionId,
                senderDeviceId = senderDeviceId,
                receiverDeviceId = receiverDeviceId,
                remoteDeviceId = if (outgoing) receiverDeviceId else senderDeviceId,
                localUserId = localUserId,
                remoteUserName = remoteUserName,
                direction = if (outgoing) Direction.SEND else Direction.RECEIVE,
                timestamp = timestamp,
                durationMs = durationMs,
                audioPath = audioPath,
                audioFormat = audioFormat,
                transcript = null,
                // ASR is off by default on every device, so this is what almost
                // every record starts as (section 23).
                transcriptStatus = TranscriptStatus.NOT_REQUESTED,
                // Something this device said is already known to its user;
                // something it heard is not (section 24).
                isRead = outgoing,
                isFavorite = false,
                status = status,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}

/**
 * Which way the voice went.
 *
 * Written at creation and never derived at read time. Comparing
 * `senderDeviceId` against the current device id in the UI would be the same
 * answer computed in more places, and wrong in all of them the day a record is
 * exported or forwarded (`docs/05_DataModel.md` section 13).
 */
enum class Direction {
    SEND,
    RECEIVE,
    ;

    companion object {
        /** Never throws: a value written by a future version must not crash a downgrade. */
        fun fromName(name: String?): Direction = entries.firstOrNull { it.name == name } ?: RECEIVE
    }
}

/** How the recording was encoded. Null in the record when there is no recording. */
enum class AudioFormat(val extension: String) {
    /** Ogg container, 16 kHz mono, the same frames that went over the wire (ADR-004 section 6). */
    OPUS("opus"),

    /** Fallback where Opus writing is not available. */
    AAC("m4a"),
    ;

    companion object {
        fun fromName(name: String?): AudioFormat? = entries.firstOrNull { it.name == name }
    }
}

/**
 * How far speech recognition got with this record.
 *
 * Five values, and [NOT_REQUESTED] is the one that matters most: ASR is off by
 * default on every device (`.claude/CLAUDE.md` section 18.1), so it is the
 * initial state of nearly every row. `docs/05_DataModel.md` section 23 records
 * that two other documents originally listed only four and were corrected --
 * this is a column's value domain, and getting it wrong costs a migration.
 */
enum class TranscriptStatus {
    /** ASR is off, or this record was never queued. The default. */
    NOT_REQUESTED,
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    ;

    companion object {
        fun fromName(name: String?): TranscriptStatus =
            entries.firstOrNull { it.name == name } ?: NOT_REQUESTED
    }
}

/**
 * How the conversation ended.
 *
 * Three values, deliberately. `docs/05_DataModel.md` section 26.1 folds session
 * timeout into [INTERRUPTED] rather than adding TIMEOUT: the specific reason
 * goes in the log, the UI shows all of them identically, and an extra enum
 * value is a migration for a distinction nobody can see.
 *
 * A request that was refused with BUSY, or that nobody answered, produces **no
 * record at all** (section 26.2). [FAILED] is only for a conversation that
 * happened and whose recording could not be stored.
 */
enum class RecordStatus {
    /** Ran to a normal VOICE_END. */
    COMPLETED,

    /** Ended early: force interrupt, timeout, WiFi loss, audio focus, peer gone. */
    INTERRUPTED,

    /** The session was fine; storing the audio was not. `audioPath` is null. */
    FAILED,
    ;

    companion object {
        fun fromName(name: String?): RecordStatus =
            entries.firstOrNull { it.name == name } ?: INTERRUPTED
    }
}

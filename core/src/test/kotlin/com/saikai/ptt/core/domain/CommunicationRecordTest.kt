package com.saikai.ptt.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules `CommunicationRecord.create` exists to stop a caller getting wrong.
 *
 * All of them are restatements of "which device is this", and every one of them
 * would produce a row that looks valid and is filed under the wrong peer, in
 * the wrong direction, or already read. None of that is visible until somebody
 * opens the history weeks later, which is why it is settled here instead.
 */
class CommunicationRecordTest {

    private val local = "11111111-1111-4111-8111-111111111111"
    private val remote = "22222222-2222-4222-8222-222222222222"

    private fun record(
        sender: String,
        receiver: String,
        status: RecordStatus = RecordStatus.COMPLETED,
        audioPath: String? = null,
        audioFormat: AudioFormat? = null,
    ) = CommunicationRecord.create(
        localDeviceId = local,
        sessionId = "33333333-3333-4333-8333-333333333333",
        senderDeviceId = sender,
        receiverDeviceId = receiver,
        localUserId = "user-1",
        remoteUserName = "山田",
        timestamp = 1_700_000_000_000L,
        durationMs = 4_200L,
        status = status,
        audioPath = audioPath,
        audioFormat = audioFormat,
    )

    @Test
    fun `a record this device sent is outgoing, and the remote id is the receiver`() {
        val sent = record(sender = local, receiver = remote)

        assertEquals(Direction.SEND, sent.direction)
        assertEquals(remote, sent.remoteDeviceId)
    }

    @Test
    fun `a record this device received is incoming, and the remote id is the sender`() {
        val received = record(sender = remote, receiver = local)

        assertEquals(Direction.RECEIVE, received.direction)
        assertEquals(remote, received.remoteDeviceId)
    }

    @Test
    fun `the redundant column always matches one of the two real ones`() {
        listOf(record(local, remote), record(remote, local)).forEach { row ->
            assertTrue(
                "remoteDeviceId must be whichever of sender/receiver is not this device",
                row.remoteDeviceId == row.senderDeviceId || row.remoteDeviceId == row.receiverDeviceId,
            )
            assertTrue("remoteDeviceId must never be this device", row.remoteDeviceId != local)
        }
    }

    @Test
    fun `something this device said starts read, something it heard does not`() {
        // docs/05_DataModel.md section 24: the user was there for their own
        // transmission, so there is nothing to catch up on.
        assertTrue(record(sender = local, receiver = remote).isRead)
        assertFalse(record(sender = remote, receiver = local).isRead)
    }

    @Test
    fun `a new record is not requested for transcription and not a favourite`() {
        val fresh = record(sender = remote, receiver = local)

        // ASR is off by default on every device, so this is the initial state
        // of nearly every row (section 23).
        assertEquals(TranscriptStatus.NOT_REQUESTED, fresh.transcriptStatus)
        assertNull(fresh.transcript)
        assertFalse(fresh.isFavorite)
    }

    @Test
    fun `an audio format without a file is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            record(local, remote, audioPath = null, audioFormat = AudioFormat.OPUS)
        }
    }

    @Test
    fun `a file without an audio format is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            record(local, remote, audioPath = "records/2026/09/16/x.opus", audioFormat = null)
        }
    }

    @Test
    fun `a record with both a path and a format is accepted`() {
        val stored = record(
            local,
            remote,
            audioPath = "records/2026/09/16/x.opus",
            audioFormat = AudioFormat.OPUS,
        )

        assertEquals("records/2026/09/16/x.opus", stored.audioPath)
        assertEquals(AudioFormat.OPUS, stored.audioFormat)
    }

    @Test
    fun `timestamps start equal`() {
        val fresh = record(local, remote)
        assertEquals(fresh.createdAt, fresh.updatedAt)
    }

    /**
     * Stored enum names are read back by name, and an unknown one never throws.
     *
     * A value can arrive from a build that had more of them -- an install that
     * was downgraded, or data restored from a backup. `docs/05_DataModel.md`
     * section 41 requires that damaged local data degrade rather than stop the
     * app starting, and a crash in the history list is the app not starting for
     * anyone who lands there.
     */
    @Test
    fun `unknown stored enum names fall back instead of throwing`() {
        assertEquals(TranscriptStatus.NOT_REQUESTED, TranscriptStatus.fromName("TRANSLATING"))
        assertEquals(TranscriptStatus.NOT_REQUESTED, TranscriptStatus.fromName(null))
        assertEquals(RecordStatus.INTERRUPTED, RecordStatus.fromName("TIMEOUT"))
        assertEquals(Direction.RECEIVE, Direction.fromName("FORWARDED"))
        assertNull(AudioFormat.fromName("FLAC"))
    }

    @Test
    fun `known enum names round-trip`() {
        TranscriptStatus.entries.forEach {
            assertEquals(it, TranscriptStatus.fromName(it.name))
        }
        RecordStatus.entries.forEach { assertEquals(it, RecordStatus.fromName(it.name)) }
        Direction.entries.forEach { assertEquals(it, Direction.fromName(it.name)) }
        AudioFormat.entries.forEach { assertEquals(it, AudioFormat.fromName(it.name)) }
    }

    @Test
    fun `the file extension follows the format`() {
        // docs/05_DataModel.md section 20 fixes both, and Task38 builds paths
        // from them.
        assertEquals("opus", AudioFormat.OPUS.extension)
        assertEquals("m4a", AudioFormat.AAC.extension)
    }
}

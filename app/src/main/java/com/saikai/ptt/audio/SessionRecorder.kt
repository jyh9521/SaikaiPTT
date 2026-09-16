package com.saikai.ptt.audio

import com.saikai.ptt.core.audio.OggOpusWriter
import com.saikai.ptt.core.audio.RecordingPaths
import com.saikai.ptt.core.common.LifecycleStep
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.AudioFormat
import com.saikai.ptt.core.domain.CommunicationRecord
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.HistoryRepository
import com.saikai.ptt.core.domain.RecordStatus
import com.saikai.ptt.core.domain.StorageError
import com.saikai.ptt.core.history.ActiveRecordings
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.session.RecordingSession
import com.saikai.ptt.core.session.VoiceRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Writes conversations to disk and files them in the history.
 *
 * The [VoiceRecording] the two voice pipelines hand their frames to. Both
 * directions arrive here: the send side's encoder output and the receive side's
 * jitter-buffer playout, already Opus, never re-encoded (ADR-004 section 6).
 *
 * ### The realtime path never waits for a disk
 *
 * [frame] is called on the capture thread when sending and on the voice receive
 * thread when receiving, every 20 ms. All it does is copy the bytes into a
 * bounded queue and return. A separate thread opens files, writes pages and
 * moves things around; nothing it does can slow a frame down
 * (`docs/02_Architecture.md` section 18).
 *
 * The queue holds five seconds. When it is full the frame is **dropped** and a
 * throttled warning is logged -- never blocked on, never grown. A queue that
 * grows has no bound on the delay it can hide, and a queue that blocks has
 * turned a full disk into dropped audio on the wire. Five seconds is far more
 * head-room than a writer that falls behind will ever recover from, so hitting
 * the limit means something is wrong with the storage rather than with the
 * timing.
 *
 * ### Two phases, because a filesystem is not in the transaction
 *
 * A recording is written to `records/.tmp/` while it happens and moved to
 * `records/YYYY/MM/DD/` when it ends. `docs/05_DataModel.md` section 32 asks
 * for that so cleanup can tell a file being written from a finished one by its
 * path alone -- nothing in the database points at a temporary file, so the path
 * is all cleanup has to go on.
 *
 * The order at the end is: finish the file, move it, then insert the row
 * (section 34). It cannot be atomic, so it is ordered to fail safely:
 *
 *  - the file cannot be finished or moved -> the row says FAILED with no path,
 *    never COMPLETED pointing at a file that is not there;
 *  - the row cannot be inserted -> **the audio is kept**, logged as an orphan
 *    for cleanup to find. Throwing away audio somebody recorded is worse than
 *    leaving a file nothing points at.
 *
 * @param filesDir the app's private files directory. Every stored path is
 *   relative to it and the prefix is added here and nowhere else
 *   (`docs/05_DataModel.md` section 19).
 */
class SessionRecorder(
    private val filesDir: File,
    private val config: SaikaiConfig,
    private val localDeviceId: DeviceId,
    private val localUserId: () -> String?,
    private val history: HistoryRepository,
    private val active: ActiveRecordings,
    private val onStorageError: (StorageError) -> Unit,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : VoiceRecording, LifecycleStep {

    override val name: String = "recorder"

    /** Five seconds of 20 ms frames. */
    private val queueCapacity: Int = (5_000 / FRAME_MILLIS).toInt()

    private val queue = ArrayBlockingQueue<Command>(queueCapacity)

    @Volatile
    private var writerThread: Thread? = null

    /** Set on the writer thread only. */
    private var open: OpenRecording? = null

    // --- LifecycleStep ----------------------------------------------------------------

    override suspend fun start() {
        if (writerThread != null) return
        queue.clear()
        val thread = Thread(::run, "saikai-recorder").apply {
            // Below the audio threads on purpose: if the device is short of CPU,
            // the call matters and the recording does not.
            priority = Thread.MIN_PRIORITY + 1
            isDaemon = true
        }
        writerThread = thread
        thread.start()
        logger.i(LogCategory.STORAGE) { "recorder ready, queue holds $queueCapacity frames" }
    }

    override suspend fun stop() {
        val thread = writerThread ?: return
        writerThread = null
        // Not offer(): this one must get through, and by now nothing is
        // producing frames. A full queue here would mean losing the close.
        queue.put(Command.Shutdown)
        thread.join(SHUTDOWN_JOIN_MILLIS)
        if (thread.isAlive) {
            logger.w(LogCategory.STORAGE) { "the recorder did not stop in time" }
        }
    }

    // --- VoiceRecording ---------------------------------------------------------------

    override fun begin(session: RecordingSession) {
        // A record id up front: it is the file's name as well as the row's key
        // (`docs/05_DataModel.md` section 20), so it has to exist before
        // anything is written.
        submit(Command.Begin(session, UUID.randomUUID().toString(), localUserId()))
    }

    override fun frame(frame: ByteArray, offset: Int, length: Int, capturedAtMillis: Long) {
        // Copied because the caller's array is the codec's and is reused before
        // the writer thread gets to it. Fifty small arrays a second is nothing
        // next to what a pool would cost in complexity here.
        if (!submit(Command.Frame(frame.copyOfRange(offset, offset + length)))) {
            logger.throttled(LogLevel.WARN, LogCategory.STORAGE, "recorder-full") {
                "the recording queue is full; dropping frames"
            }
        }
    }

    override fun finish(sessionId: SessionId, interrupted: Boolean) {
        submit(Command.Finish(sessionId, interrupted))
    }

    override fun discard(sessionId: SessionId) {
        submit(Command.Discard(sessionId))
    }

    /**
     * Hands a command to the writer thread without ever waiting.
     *
     * @return false when the queue was full, which only [frame] tolerates.
     */
    private fun submit(command: Command): Boolean {
        if (writerThread == null) return false
        return queue.offer(command)
    }

    // --- The writer thread ------------------------------------------------------------

    private fun run() {
        while (true) {
            val command = try {
                queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }
            when (command) {
                is Command.Begin -> onBegin(command)
                is Command.Frame -> onFrame(command)
                is Command.Finish -> onFinish(command)
                is Command.Discard -> onDiscard(command)
                Command.Shutdown -> {
                    // Whatever was open is kept: the service is stopping, and a
                    // conversation that was happening was still a conversation.
                    open?.let { complete(it, interrupted = true) }
                    return
                }
            }
        }
    }

    private fun onBegin(command: Command.Begin) {
        open?.let { complete(it, interrupted = true) }

        val temporary = RecordingPaths.temporary(command.recordId, AudioFormat.OPUS)
        val file = File(filesDir, temporary)
        val writer = try {
            file.parentFile?.mkdirs()
            val stream = BufferedOutputStream(file.outputStream())
            stream to OggOpusWriter(
                out = stream,
                sampleRateHz = config.audio.sampleRateHz,
                frameSizeSamples = config.audio.frameSizeSamples,
                serialNumber = command.recordId.hashCode(),
            )
        } catch (error: IOException) {
            logger.e(LogCategory.STORAGE, error) { "could not open a recording file" }
            onStorageError(StorageError.RECORDING_UNAVAILABLE)
            null
        }

        // Claimed for as long as this file exists under .tmp, so a cleanup
        // pass during a long call cannot take it (`docs/05_DataModel.md`
        // section 32). Released by whichever of complete() or onDiscard() ends
        // this recording, and both always run: the writer thread's shutdown
        // path completes whatever is open.
        active.claim(temporary)

        open = OpenRecording(
            session = command.session,
            recordId = command.recordId,
            localUserId = command.localUserId,
            file = file,
            temporaryPath = temporary,
            stream = writer?.first,
            writer = writer?.second,
        )
    }

    private fun onFrame(command: Command.Frame) {
        val current = open ?: return
        val writer = current.writer ?: return
        if (current.broken) return
        try {
            writer.write(command.bytes, 0, command.bytes.size)
        } catch (error: IOException) {
            // The disk filled, or the file went away. The call itself is
            // unaffected and carries on; this recording is over.
            logger.e(LogCategory.STORAGE, error) { "the recording could not be written" }
            current.broken = true
            onStorageError(StorageError.RECORDING_FAILED)
        }
    }

    private fun onFinish(command: Command.Finish) {
        val current = open ?: return
        if (current.session.sessionId != command.sessionId) return
        complete(current, command.interrupted)
    }

    private fun onDiscard(command: Command.Discard) {
        val current = open ?: return
        if (current.session.sessionId != command.sessionId) return
        closeQuietly(current)
        current.file.delete()
        active.release(current.temporaryPath)
        open = null
        logger.i(LogCategory.STORAGE) { "recording discarded; no history row" }
    }

    /**
     * Closes the file, moves it, and writes the row.
     *
     * The three can each fail independently and the record says which of them
     * did: a finished file gives COMPLETED or INTERRUPTED with a path, and
     * anything else gives FAILED with none.
     */
    private fun complete(current: OpenRecording, interrupted: Boolean) {
        open = null

        val frames = current.writer?.frameCount ?: 0L
        val finishedOk = closeQuietly(current) && !current.broken && frames > 0
        val finalPath = if (finishedOk) moveIntoPlace(current) else null

        // The claim moves with the file, and only then is the temporary one
        // given up: between the rename and the row being inserted the finished
        // file is referenced by nothing, which is what an orphan looks like.
        // The scan's one-hour grace period covers this window too; claiming it
        // means the window does not depend on that.
        finalPath?.let { active.claim(it) }
        active.release(current.temporaryPath)

        if (finalPath == null) {
            current.file.delete()
            if (frames > 0) onStorageError(StorageError.RECORDING_NOT_FINALISED)
        }

        val status = when {
            finalPath == null -> RecordStatus.FAILED
            interrupted -> RecordStatus.INTERRUPTED
            else -> RecordStatus.COMPLETED
        }

        save(current, finalPath, status, frames)
    }

    /** @return true when everything that was written reached the disk. */
    private fun closeQuietly(current: OpenRecording): Boolean = try {
        current.writer?.finish()
        current.stream?.close()
        true
    } catch (error: IOException) {
        logger.e(LogCategory.STORAGE, error) { "the recording could not be closed" }
        false
    }

    /** @return the stored relative path, or null when the move failed. */
    private fun moveIntoPlace(current: OpenRecording): String? {
        val relative = RecordingPaths.finished(
            recordId = current.recordId,
            format = AudioFormat.OPUS,
            timestampMillis = current.session.startedAtMillis,
        )
        val destination = File(filesDir, relative)
        return try {
            destination.parentFile?.mkdirs()
            // renameTo rather than a copy: both paths are in the same private
            // directory, so this is a directory entry change and cannot half
            // happen.
            if (current.file.renameTo(destination)) {
                relative
            } else {
                logger.e(LogCategory.STORAGE) { "could not move the recording into place" }
                null
            }
        } catch (error: SecurityException) {
            logger.e(LogCategory.STORAGE, error) { "could not move the recording into place" }
            null
        }
    }

    private fun save(
        current: OpenRecording,
        audioPath: String?,
        status: RecordStatus,
        frames: Long,
    ) {
        val userId = current.localUserId
        if (userId == null) {
            // No active name means nothing legitimate to attribute this to. It
            // cannot normally happen -- a device with no name cannot transmit,
            // and one that is receiving had a name when it announced itself.
            logger.w(LogCategory.STORAGE) { "no active user; not writing a history row" }
            audioPath?.let { active.release(it) }
            return
        }

        val session = current.session
        val speaker = if (session.outgoing) localDeviceId else session.peer
        val listener = if (session.outgoing) session.peer else localDeviceId

        val record = CommunicationRecord.create(
            localDeviceId = localDeviceId.value,
            sessionId = session.sessionId.value,
            senderDeviceId = speaker.value,
            receiverDeviceId = listener.value,
            localUserId = userId,
            remoteUserName = session.peerName,
            timestamp = session.startedAtMillis,
            // Counted from frames rather than the clock: the wall time includes
            // the handshake and whatever the user did before speaking, and the
            // number the history shows should be how long the audio is.
            durationMs = frames * FRAME_MILLIS,
            status = status,
            audioPath = audioPath,
            audioFormat = if (audioPath == null) null else AudioFormat.OPUS,
            now = now(),
            id = current.recordId,
        )

        scope.launch {
            try {
                when (val outcome = history.save(record)) {
                    is Outcome.Success ->
                        logger.i(LogCategory.STORAGE) {
                            "stored a ${record.durationMs}ms ${record.direction} record ($status)"
                        }

                    is Outcome.Failure -> {
                        // The audio stays. docs/05_DataModel.md section 34 calls
                        // this an orphan candidate and leaves it for cleanup;
                        // deleting a recording because a row would not insert
                        // destroys the only copy of something somebody said.
                        logger.e(LogCategory.STORAGE) {
                            "history refused the record (${outcome.error}); " +
                                "orphan candidate at ${audioPath ?: "no file"}"
                        }
                        onStorageError(StorageError.HISTORY_UNAVAILABLE)
                    }
                }
            } finally {
                // Released whichever way the insert went, and in a finally
                // because the service's scope can be cancelled mid-write. A
                // claim that leaks is a file cleanup can never remove -- and on
                // the failure path this is precisely the orphan candidate the
                // next pass is meant to find.
                audioPath?.let { active.release(it) }
            }
        }
    }

    private class OpenRecording(
        val session: RecordingSession,
        val recordId: String,
        val localUserId: String?,
        val file: File,
        /** The relative path this is claimed under until it is moved or dropped. */
        val temporaryPath: String,
        val stream: BufferedOutputStream?,
        val writer: OggOpusWriter?,
    ) {
        /** Set once a write has failed, so the rest are not attempted one by one. */
        var broken: Boolean = false
    }

    private sealed interface Command {
        data class Begin(
            val session: RecordingSession,
            val recordId: String,
            val localUserId: String?,
        ) : Command

        class Frame(val bytes: ByteArray) : Command

        data class Finish(val sessionId: SessionId, val interrupted: Boolean) : Command

        data class Discard(val sessionId: SessionId) : Command

        data object Shutdown : Command
    }

    private companion object {
        const val FRAME_MILLIS = 20L
        const val POLL_MILLIS = 250L
        const val SHUTDOWN_JOIN_MILLIS = 2_000L
    }
}

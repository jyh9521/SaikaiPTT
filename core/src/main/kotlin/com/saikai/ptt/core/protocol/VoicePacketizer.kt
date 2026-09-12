package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.config.AudioConfig
import com.saikai.ptt.core.domain.DeviceId
import java.util.concurrent.atomic.AtomicInteger

/**
 * The four packets of one voice session, with the sequence discipline that makes
 * them a stream rather than four unrelated datagrams.
 *
 * `docs/ADR/ADR-003-Wire-Format.md` section 4 fixes the payload layouts and
 * section 5 fixes what goes in the header's sequence field. Both were
 * implemented in Task11; what was missing is the thing that holds them together
 * for the length of a transmission -- the counter, and the buffers it writes
 * into.
 *
 * One instance per session. A device is either the sender or the receiver of a
 * given session, so it calls either [writeVoiceStart] / [writeVoiceData] /
 * [writeVoiceEnd] or only [writeVoiceAccept]. Both roles live here because both
 * are the same session on the wire, and two classes would be two places that
 * have to agree about what a session id is.
 *
 * ### One stored counter
 *
 * Frames are numbered from [SequenceNumbers.FIRST_VOICE_DATA] and every frame
 * written advances by one, so the last sequence and the frame count are two
 * views of a single number. Storing both would be two chances to disagree, and
 * a disagreement between them is exactly the thing a receiver uses them to
 * detect.
 *
 * The two VOICE_END payload fields therefore hold the same value in v1, by
 * construction. A receiver must not assume it: a sender that ever skips a
 * sequence number -- discontinuous transmission, a frame the encoder refused --
 * makes them differ, and telling "lost in the network" from "never sent" is the
 * whole reason the ADR carries both.
 *
 * ### Two buffers, and an atomic counter
 *
 * VOICE_DATA is written by the capture thread fifty times a second; VOICE_START,
 * VOICE_ACCEPT and VOICE_END are written by the session machine's coroutine.
 * They are separate paths already -- ADR-002 gives them separate sockets -- so
 * they get separate buffers rather than a data race on the array this product
 * writes to most often.
 *
 * The counter is shared between those two threads, and [writeVoiceEnd] has to
 * read it and close it as one step. Reading a volatile and then closing would
 * lose a frame that was written in between, which is not hypothetical: it is
 * precisely the moment the talk button comes up. One compare-and-set on a path
 * that runs fifty times a second costs nothing measurable and removes the
 * question, so the caller is free to stop capture before or after ending the
 * session.
 *
 * Beyond the counter the class is not thread-safe: one capture thread and one
 * control coroutine, which is what the session machine guarantees.
 */
class VoicePacketizer(
    private val selfDeviceId: DeviceId,
    private val peerDeviceId: DeviceId,
    val sessionId: SessionId,
    private val audio: AudioConfig,
) {

    /** Frames written, with [ENDED] in the sign bit once the stream is closed. */
    private val state = AtomicInteger(0)

    private val controlBuffer: ByteArray = WireFormat.newDatagramBuffer()
    private val voiceBuffer: ByteArray = ByteArray(WireFormat.MAX_VOICE_DATAGRAM_BYTES)

    /**
     * The buffer [writeVoiceStart], [writeVoiceAccept] and [writeVoiceEnd] fill.
     *
     * Valid up to the length that call returned, and only until the next one.
     */
    val controlDatagram: ByteArray get() = controlBuffer

    /**
     * The buffer [writeVoiceData] fills.
     *
     * Valid up to the length that call returned, and only until the next frame.
     */
    val voiceDatagram: ByteArray get() = voiceBuffer

    /** Frames actually written so far. Rejected frames do not count. */
    val framesSent: Int get() = framesOf(state.get())

    /** The sequence VOICE_END will report as the last frame. Zero when none was sent. */
    val finalDataSequence: Int get() = sequenceOfFrame(framesSent)

    /** True once [writeVoiceEnd] has been called. Further frames are refused. */
    val isEnded: Boolean get() = state.get() < 0

    /**
     * Writes VOICE_START into [controlDatagram] and returns its length.
     *
     * Callable more than once with the same result. ADR-003 section 6 has the
     * sender retransmit up to twice while it waits for VOICE_ACCEPT, and a
     * retransmission has to be the same packet, sequence included.
     *
     * [userName] is the sender's name as it is *now*, snapshotted into the
     * session so that the history entry says who spoke rather than who they
     * renamed themselves to afterwards.
     */
    fun writeVoiceStart(userName: String, timestampMillis: Long): Int = Packet.of(
        type = PacketType.VOICE_START,
        senderDeviceId = selfDeviceId,
        payload = VoiceStartPayload(
            codec = AudioCodec.OPUS,
            sampleRateHz = audio.sampleRateHz,
            frameMillis = audio.frameDuration.inWholeMilliseconds.toInt(),
            userName = userName,
        ),
        timestampMillis = timestampMillis,
        targetDeviceId = peerDeviceId,
        sessionId = sessionId,
        sequenceNumber = SequenceNumbers.VOICE_START,
    ).encodeTo(controlBuffer)

    /**
     * Writes VOICE_ACCEPT into [controlDatagram] and returns its length.
     *
     * The receiving side's one packet. Empty payload, sequence zero: it precedes
     * the frame stream rather than being part of it.
     */
    fun writeVoiceAccept(timestampMillis: Long): Int = Packet.of(
        type = PacketType.VOICE_ACCEPT,
        senderDeviceId = selfDeviceId,
        payload = EmptyPayload,
        timestampMillis = timestampMillis,
        targetDeviceId = peerDeviceId,
        sessionId = sessionId,
        sequenceNumber = SequenceNumbers.VOICE_START,
    ).encodeTo(controlBuffer)

    /**
     * Writes one encoded audio frame into [voiceDatagram] and returns the
     * datagram length, or [REJECTED] if it could not be packetized.
     *
     * Rejects rather than throws, which is the opposite of the rule everywhere
     * else on the encoding side of this package. The reason is where the bytes
     * come from: every other encoder input is this device's own validated state,
     * where an out-of-range value is a bug worth crashing on, while this one is
     * whatever a codec returned, fifty times a second, for the whole length of a
     * transmission. Killing the capture thread over one strange frame would turn
     * a click into a dropped call.
     *
     * A rejected frame does not advance the counter, and must not: a gap in the
     * sequence tells the receiver a packet was lost and sends it looking for the
     * in-band FEC copy of a frame that was never sent.
     *
     * The `require` inside [PacketCodec.encodeVoiceData] stays where it is. This
     * is the boundary where a codec's output enters the wire format and gets
     * checked; below it, the same condition really is a programming error.
     */
    fun writeVoiceData(
        frame: ByteArray,
        frameOffset: Int,
        frameLength: Int,
        timestampMillis: Long,
    ): Int {
        if (frameLength < 1 || frameLength > WireFormat.MAX_VOICE_PAYLOAD_BYTES) return REJECTED
        if (frameOffset < 0 || frameOffset + frameLength > frame.size) return REJECTED

        val sequence: Int
        while (true) {
            val current = state.get()
            if (current < 0) return REJECTED
            if (current == Int.MAX_VALUE) return REJECTED
            if (state.compareAndSet(current, current + 1)) {
                sequence = sequenceOfFrame(current + 1)
                break
            }
        }

        return PacketCodec.encodeVoiceData(
            senderDeviceId = selfDeviceId,
            targetDeviceId = peerDeviceId,
            sessionId = sessionId,
            sequenceNumber = sequence,
            timestampMillis = timestampMillis,
            frame = frame,
            frameOffset = frameOffset,
            frameLength = frameLength,
            target = voiceBuffer,
        )
    }

    /**
     * Writes VOICE_END into [controlDatagram] and returns its length.
     *
     * Its header sequence is one past the last frame, so a receiver holding
     * frames in a jitter buffer can tell "the stream ends here" from "a frame is
     * still on its way" without a timer. The payload repeats the last sequence
     * and the count, which is what lets it tell "still in the buffer" from
     * "lost" (ADR-003 sections 4 and 5).
     *
     * With no frames sent at all the payload is `(0, 0)` and the header sequence
     * is 1. Zero is never a VOICE_DATA sequence, so it says "none" without
     * needing a flag to say so.
     *
     * Idempotent: calling it again produces the same bytes. After it, frames are
     * refused -- a frame that escaped the capture thread after the button came
     * up would reach a peer that has already closed the session and be dropped
     * there anyway.
     */
    fun writeVoiceEnd(timestampMillis: Long): Int {
        var frames: Int
        while (true) {
            val current = state.get()
            frames = framesOf(current)
            if (state.compareAndSet(current, frames or ENDED)) break
        }
        val last = sequenceOfFrame(frames)

        return Packet.of(
            type = PacketType.VOICE_END,
            senderDeviceId = selfDeviceId,
            payload = VoiceEndPayload(finalDataSequence = last, frameCount = frames),
            timestampMillis = timestampMillis,
            targetDeviceId = peerDeviceId,
            sessionId = sessionId,
            sequenceNumber = SequenceNumbers.voiceEnd(last),
        ).encodeTo(controlBuffer)
    }

    /** The sequence carried by the [oneBased]-th frame. Frame zero does not exist. */
    private fun sequenceOfFrame(oneBased: Int): Int =
        SequenceNumbers.FIRST_VOICE_DATA - 1 + oneBased

    private fun framesOf(state: Int): Int = state and ENDED.inv()

    companion object {
        /** [writeVoiceData] could not packetize the frame. Nothing was written. */
        const val REJECTED: Int = -1

        /** Sign bit of [state]: the stream has been closed by VOICE_END. */
        private const val ENDED: Int = Int.MIN_VALUE
    }
}

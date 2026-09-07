package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.protocol.WireFormat.MAX_USER_NAME_BYTES
import com.saikai.ptt.core.protocol.WireFormat.MAX_VOICE_PAYLOAD_BYTES
import com.saikai.ptt.core.protocol.WireFormat.readU16
import com.saikai.ptt.core.protocol.WireFormat.readU32
import com.saikai.ptt.core.protocol.WireFormat.readU32AsLong
import com.saikai.ptt.core.protocol.WireFormat.readU8
import com.saikai.ptt.core.protocol.WireFormat.writeU16
import com.saikai.ptt.core.protocol.WireFormat.writeU32
import com.saikai.ptt.core.protocol.WireFormat.writeU8
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The bytes that follow the 72-byte header, laid out per packet type.
 *
 * Encoding and decoding are deliberately asymmetric:
 *
 * - Encoding uses `require`. The values come from this device's own validated
 *   state, so a name that is too long or a port outside u16 is a bug here, and a
 *   bug is worth a crash in development rather than a malformed packet that a
 *   peer silently drops.
 * - Decoding never throws and never uses `require`. The input is an arbitrary
 *   datagram from a LAN this app does not control. Every failure returns null
 *   and the caller drops the packet (`docs/ADR/ADR-003-Wire-Format.md` section 8).
 *
 * Every payload reports [encodedSize] without encoding, so a header can be
 * written with the right `PayloadLength` before the payload goes into the
 * buffer, and neither side needs a scratch allocation.
 */
sealed interface PacketPayload {

    /** Byte count this payload will write. Must equal the header's PayloadLength. */
    val encodedSize: Int

    /** Writes exactly [encodedSize] bytes into [target] starting at [offset]. */
    fun writeTo(target: ByteArray, offset: Int)
}

/**
 * The payload of the packet types that carry nothing: VOICE_ACCEPT, BUSY, PING
 * and PONG.
 *
 * BUSY is empty on purpose. Returning the active session id would tell any
 * device on the LAN who is talking to whom, which is a privacy leak the product
 * has no use for (ADR-003 section 4).
 */
data object EmptyPayload : PacketPayload {
    override val encodedSize: Int get() = 0
    override fun writeTo(target: ByteArray, offset: Int) = Unit
}

/**
 * DISCOVERY, DISCOVERY_RESPONSE and HEARTBEAT.
 *
 * One layout for all three: they answer the same question ("who is here, what
 * are they called, are they busy, and where do I send voice") and differ only in
 * when they are sent.
 *
 * [voicePort] is announced rather than assumed because the voice port floats: a
 * device that cannot bind the preferred port binds another and says so
 * (`docs/ADR/ADR-002-Transport-And-Ports.md`).
 */
data class PresencePayload(
    val peerState: PeerState,
    val voicePort: Int,
    val userName: String,
) : PacketPayload {

    private val userNameBytes: ByteArray = userName.toByteArray(Charsets.UTF_8)

    init {
        require(voicePort in 0..0xFFFF) { "voicePort must fit in u16, was $voicePort" }
        require(userNameBytes.isNotEmpty()) { "userName must not be empty" }
        require(userNameBytes.size <= MAX_USER_NAME_BYTES) {
            "userName is ${userNameBytes.size} UTF-8 bytes, limit is $MAX_USER_NAME_BYTES"
        }
    }

    override val encodedSize: Int get() = FIXED_BYTES + userNameBytes.size

    override fun writeTo(target: ByteArray, offset: Int) {
        writeU8(target, offset, peerState.code)
        writeU16(target, offset + 1, voicePort)
        writeU8(target, offset + 3, userNameBytes.size)
        userNameBytes.copyInto(target, offset + FIXED_BYTES)
    }

    companion object {
        /** peerState + voicePort + userNameLen. */
        const val FIXED_BYTES: Int = 4

        fun read(source: ByteArray, offset: Int, length: Int): PresencePayload? {
            if (length < FIXED_BYTES) return null
            val state = PeerState.fromCode(readU8(source, offset)) ?: return null
            val port = readU16(source, offset + 1)
            val nameLength = readU8(source, offset + 3)
            if (nameLength < 1 || nameLength > MAX_USER_NAME_BYTES) return null
            // Exact, not "at least": ADR-003 section 8 step 4 already pins the
            // datagram length to the header, so trailing bytes mean a disagreement
            // about the format, not a longer name.
            if (length != FIXED_BYTES + nameLength) return null
            val name = Utf8.decodeStrict(source, offset + FIXED_BYTES, nameLength) ?: return null
            return PresencePayload(state, port, name)
        }
    }
}

/**
 * VOICE_START: the request to open a session.
 *
 * Carries the audio parameters even though v1 fixes them, so that a receiver
 * refuses a mismatch instead of decoding 16 kHz frames as 48 kHz. Carries the
 * sender's name as a snapshot: the history entry for this transmission should
 * show who they were when they spoke, not who they renamed themselves to
 * afterwards.
 */
data class VoiceStartPayload(
    val codec: AudioCodec,
    val sampleRateHz: Int,
    val frameMillis: Int,
    val userName: String,
) : PacketPayload {

    private val userNameBytes: ByteArray = userName.toByteArray(Charsets.UTF_8)

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
        require(frameMillis in 1..0xFFFF) { "frameMillis must fit in u16, was $frameMillis" }
        require(userNameBytes.isNotEmpty()) { "userName must not be empty" }
        require(userNameBytes.size <= MAX_USER_NAME_BYTES) {
            "userName is ${userNameBytes.size} UTF-8 bytes, limit is $MAX_USER_NAME_BYTES"
        }
    }

    override val encodedSize: Int get() = FIXED_BYTES + userNameBytes.size

    override fun writeTo(target: ByteArray, offset: Int) {
        writeU8(target, offset, codec.code)
        writeU32(target, offset + 1, sampleRateHz)
        writeU16(target, offset + 5, frameMillis)
        writeU8(target, offset + 7, userNameBytes.size)
        userNameBytes.copyInto(target, offset + FIXED_BYTES)
    }

    companion object {
        /** codec + sampleRate + frameMs + userNameLen. */
        const val FIXED_BYTES: Int = 8

        fun read(source: ByteArray, offset: Int, length: Int): VoiceStartPayload? {
            if (length < FIXED_BYTES) return null
            val codec = AudioCodec.fromCode(readU8(source, offset)) ?: return null
            val sampleRate = readU32AsLong(source, offset + 1)
            if (sampleRate < 1L || sampleRate > Int.MAX_VALUE.toLong()) return null
            val frameMillis = readU16(source, offset + 5)
            if (frameMillis < 1) return null
            val nameLength = readU8(source, offset + 7)
            if (nameLength < 1 || nameLength > MAX_USER_NAME_BYTES) return null
            if (length != FIXED_BYTES + nameLength) return null
            val name = Utf8.decodeStrict(source, offset + FIXED_BYTES, nameLength) ?: return null
            return VoiceStartPayload(codec, sampleRate.toInt(), frameMillis, name)
        }
    }
}

/**
 * VOICE_DATA: one encoded Opus frame, verbatim.
 *
 * A view over a caller-owned buffer, not a copy. On the receive path that buffer
 * is the socket's reusable datagram buffer, so the view is only valid until the
 * next `receive()` on that socket; anything that outlives the callback must call
 * [copyFrame]. This is the one place in the protocol where 50 allocations a
 * second would be paid, and it is the reason for the whole
 * offset-and-length shape of this file.
 */
class VoiceDataPayload(
    val frame: ByteArray,
    val frameOffset: Int = 0,
    val frameLength: Int = frame.size - frameOffset,
) : PacketPayload {

    init {
        require(frameOffset >= 0 && frameLength >= 0 && frameOffset + frameLength <= frame.size) {
            "Frame range $frameOffset..${frameOffset + frameLength} is outside a ${frame.size}-byte buffer"
        }
        require(frameLength in 1..MAX_VOICE_PAYLOAD_BYTES) {
            "A voice frame is 1..$MAX_VOICE_PAYLOAD_BYTES bytes, was $frameLength"
        }
    }

    override val encodedSize: Int get() = frameLength

    override fun writeTo(target: ByteArray, offset: Int) {
        frame.copyInto(target, offset, frameOffset, frameOffset + frameLength)
    }

    /** An independent copy of the frame, for anything that outlives the receive buffer. */
    fun copyFrame(): ByteArray = frame.copyOfRange(frameOffset, frameOffset + frameLength)

    /** Content equality: two views of the same bytes in different buffers are equal. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceDataPayload) return false
        if (frameLength != other.frameLength) return false
        for (i in 0 until frameLength) {
            if (frame[frameOffset + i] != other.frame[other.frameOffset + i]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = 1
        for (i in 0 until frameLength) {
            result = 31 * result + frame[frameOffset + i]
        }
        return result
    }

    override fun toString(): String = "VoiceDataPayload(frameLength=$frameLength)"

    companion object {
        fun read(source: ByteArray, offset: Int, length: Int): VoiceDataPayload? {
            if (length < 1 || length > MAX_VOICE_PAYLOAD_BYTES) return null
            if (offset < 0 || offset + length > source.size) return null
            return VoiceDataPayload(source, offset, length)
        }
    }
}

/**
 * VOICE_END: the sender's normal end of transmission.
 *
 * [finalDataSequence] and [frameCount] together let the receiver tell "the last
 * frames are still in the jitter buffer" from "the last frames were lost", which
 * decides whether it waits or plays out immediately.
 */
data class VoiceEndPayload(
    val finalDataSequence: Int,
    val frameCount: Int,
) : PacketPayload {

    override val encodedSize: Int get() = BYTES

    override fun writeTo(target: ByteArray, offset: Int) {
        writeU32(target, offset, finalDataSequence)
        writeU32(target, offset + 4, frameCount)
    }

    companion object {
        const val BYTES: Int = 8

        fun read(source: ByteArray, offset: Int, length: Int): VoiceEndPayload? {
            if (length != BYTES) return null
            return VoiceEndPayload(readU32(source, offset), readU32(source, offset + 4))
        }
    }
}

/** SESSION_TERMINATE: the receiving side ends the session, and says why. */
data class SessionTerminatePayload(
    val reason: TerminationReason,
) : PacketPayload {

    override val encodedSize: Int get() = BYTES

    override fun writeTo(target: ByteArray, offset: Int) {
        writeU8(target, offset, reason.code)
    }

    companion object {
        const val BYTES: Int = 1

        fun read(source: ByteArray, offset: Int, length: Int): SessionTerminatePayload? {
            if (length != BYTES) return null
            val reason = TerminationReason.fromCode(readU8(source, offset)) ?: return null
            return SessionTerminatePayload(reason)
        }
    }
}

/**
 * Strict UTF-8 decoding.
 *
 * `String(bytes, UTF_8)` replaces every malformed sequence with U+FFFD, which
 * would turn 64 bytes of garbage into a 192-byte name and make an over-long name
 * out of a packet that passed the length check. Rejecting instead of repairing
 * also means a decoded name always re-encodes to the same bytes, which is what
 * makes the round-trip test meaningful.
 *
 * The decoder is built per call. This runs on the control path only -- roughly
 * one packet a second per peer -- never on the voice path.
 */
private object Utf8 {
    fun decodeStrict(source: ByteArray, offset: Int, length: Int): String? {
        if (offset < 0 || length < 0 || offset + length > source.size) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source, offset, length))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

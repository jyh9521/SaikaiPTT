package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.protocol.ProtocolFixtures.bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Payload layouts, and the asymmetry between encoding and decoding.
 *
 * Encoding rejects bad input loudly because it comes from this device. Decoding
 * rejects bad input silently because it comes from the network.
 */
class PacketPayloadTest {

    // --- Presence --------------------------------------------------------------

    @Test
    fun `presence payload has the layout the ADR specifies`() {
        val encoded = ByteArray(PresencePayload.FIXED_BYTES + 2)
        PresencePayload(PeerState.BUSY, voicePort = 45_821, userName = "AB").writeTo(encoded, 0)

        assertArrayEquals(
            bytes(
                0x01, // peerState BUSY
                0xB2, 0xFD, // voicePort 45821, big-endian
                0x02, // userNameLen
                0x41, 0x42, // "AB"
            ),
            encoded,
        )
    }

    @Test
    fun `presence payload round trips a multi-byte name`() {
        val original = PresencePayload(PeerState.IDLE, 45_821, "西海")
        val encoded = ByteArray(original.encodedSize).also { original.writeTo(it, 0) }

        assertEquals(original, PresencePayload.read(encoded, 0, encoded.size))
    }

    @Test
    fun `presence payload accepts a name of exactly the wire limit`() {
        val name = "a".repeat(WireFormat.MAX_USER_NAME_BYTES)
        val original = PresencePayload(PeerState.IDLE, 1, name)
        val encoded = ByteArray(original.encodedSize).also { original.writeTo(it, 0) }

        assertEquals(original, PresencePayload.read(encoded, 0, encoded.size))
    }

    @Test
    fun `presence decode rejects a declared name length outside one to sixty-four`() {
        val tooLong = bytes(0x00, 0x00, 0x01, 0x41) + ByteArray(65) { 0x61 }
        tooLong[3] = 65
        assertNull(PresencePayload.read(tooLong, 0, tooLong.size))

        val zeroLength = bytes(0x00, 0x00, 0x01, 0x00)
        assertNull(PresencePayload.read(zeroLength, 0, zeroLength.size))
    }

    @Test
    fun `presence decode rejects trailing bytes`() {
        val padded = bytes(0x00, 0xB2, 0xFD, 0x02, 0x41, 0x42, 0x00)
        assertNull(PresencePayload.read(padded, 0, padded.size))
    }

    @Test
    fun `presence decode rejects an unknown peer state`() {
        val unknownState = bytes(0x07, 0xB2, 0xFD, 0x01, 0x41)
        assertNull(PresencePayload.read(unknownState, 0, unknownState.size))
    }

    @Test
    fun `presence decode rejects malformed UTF-8 instead of repairing it`() {
        // String(bytes, UTF_8) would turn each bad byte into U+FFFD, which
        // re-encodes to three bytes and would break the length invariant.
        val badName = bytes(0x00, 0x00, 0x01, 0x02, 0xC3, 0x28)
        assertNull(PresencePayload.read(badName, 0, badName.size))

        val truncatedMultiByte = bytes(0x00, 0x00, 0x01, 0x01, 0xE8)
        assertNull(PresencePayload.read(truncatedMultiByte, 0, truncatedMultiByte.size))
    }

    @Test
    fun `presence decode rejects a payload shorter than the fixed part`() {
        assertNull(PresencePayload.read(bytes(0x00, 0x00, 0x01), 0, 3))
    }

    @Test
    fun `presence encoding refuses values this device should never produce`() {
        assertThrows(IllegalArgumentException::class.java) {
            PresencePayload(PeerState.IDLE, 70_000, "Kenji")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PresencePayload(PeerState.IDLE, 45_821, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PresencePayload(PeerState.IDLE, 45_821, "a".repeat(65))
        }
    }

    // --- VOICE_START -----------------------------------------------------------

    @Test
    fun `voice start payload has the layout the ADR specifies`() {
        val original = VoiceStartPayload(AudioCodec.OPUS, 16_000, 20, "AB")
        val encoded = ByteArray(original.encodedSize).also { original.writeTo(it, 0) }

        assertArrayEquals(
            bytes(
                0x01, // codec OPUS
                0x00, 0x00, 0x3E, 0x80, // sampleRate 16000
                0x00, 0x14, // frameMs 20
                0x02, // userNameLen
                0x41, 0x42,
            ),
            encoded,
        )
        assertEquals(original, VoiceStartPayload.read(encoded, 0, encoded.size))
    }

    @Test
    fun `voice start decode rejects an unknown codec and a zero sample rate`() {
        val unknownCodec = bytes(0x09, 0x00, 0x00, 0x3E, 0x80, 0x00, 0x14, 0x01, 0x41)
        assertNull(VoiceStartPayload.read(unknownCodec, 0, unknownCodec.size))

        val zeroRate = bytes(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x14, 0x01, 0x41)
        assertNull(VoiceStartPayload.read(zeroRate, 0, zeroRate.size))

        val zeroFrame = bytes(0x01, 0x00, 0x00, 0x3E, 0x80, 0x00, 0x00, 0x01, 0x41)
        assertNull(VoiceStartPayload.read(zeroFrame, 0, zeroFrame.size))
    }

    @Test
    fun `voice start decode rejects a sample rate above the signed range`() {
        // A u32 can carry values an Int cannot; they must be refused, not wrapped.
        val huge = bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x14, 0x01, 0x41)
        assertNull(VoiceStartPayload.read(huge, 0, huge.size))
    }

    // --- VOICE_DATA ------------------------------------------------------------

    @Test
    fun `voice data payload is the frame verbatim`() {
        val frame = ByteArray(53) { (it * 5).toByte() }
        val payload = VoiceDataPayload(frame)
        val encoded = ByteArray(payload.encodedSize).also { payload.writeTo(it, 0) }

        assertArrayEquals(frame, encoded)
        assertEquals(payload, VoiceDataPayload.read(encoded, 0, encoded.size))
    }

    @Test
    fun `voice data views of the same bytes in different buffers are equal`() {
        val frame = ByteArray(10) { it.toByte() }
        val padded = ByteArray(30).also { frame.copyInto(it, 12) }

        assertEquals(VoiceDataPayload(frame), VoiceDataPayload(padded, 12, 10))
        assertEquals(VoiceDataPayload(frame).hashCode(), VoiceDataPayload(padded, 12, 10).hashCode())
    }

    @Test
    fun `voice data decode enforces the four hundred byte limit`() {
        assertNotNull(VoiceDataPayload.read(ByteArray(400), 0, 400))
        assertNull(VoiceDataPayload.read(ByteArray(401), 0, 401))
        assertNull(VoiceDataPayload.read(ByteArray(10), 0, 0))
    }

    // --- VOICE_END, SESSION_TERMINATE, empty ------------------------------------

    @Test
    fun `voice end payload is two big-endian u32 fields`() {
        val original = VoiceEndPayload(finalDataSequence = 0x01020304, frameCount = 0x05060708)
        val encoded = ByteArray(original.encodedSize).also { original.writeTo(it, 0) }

        assertArrayEquals(bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08), encoded)
        assertEquals(original, VoiceEndPayload.read(encoded, 0, encoded.size))
        assertNull(VoiceEndPayload.read(encoded, 0, 7))
        assertNull(VoiceEndPayload.read(ByteArray(9), 0, 9))
    }

    @Test
    fun `session terminate carries one reason byte`() {
        for (reason in TerminationReason.entries) {
            val original = SessionTerminatePayload(reason)
            val encoded = ByteArray(1).also { original.writeTo(it, 0) }
            assertEquals(reason.code.toByte(), encoded[0])
            assertEquals(original, SessionTerminatePayload.read(encoded, 0, 1))
        }
        assertNull(SessionTerminatePayload.read(bytes(0x00), 0, 1))
        assertNull(SessionTerminatePayload.read(bytes(0x05), 0, 1))
        assertNull(SessionTerminatePayload.read(bytes(0x01, 0x01), 0, 2))
    }

    @Test
    fun `the empty payload encodes nothing`() {
        assertEquals(0, EmptyPayload.encodedSize)
        EmptyPayload.writeTo(ByteArray(0), 0)
    }

    @Test
    fun `a packet whose header disagrees with its payload cannot be built`() {
        val payload = PresencePayload(PeerState.IDLE, 45_821, "Kenji")
        val header = PacketHeader.of(
            type = PacketType.HEARTBEAT,
            senderDeviceId = ProtocolFixtures.SENDER,
            payload = payload,
            timestampMillis = ProtocolFixtures.TIMESTAMP,
        ).copy(payloadLength = 3)

        assertThrows(IllegalArgumentException::class.java) { Packet(header, payload) }
    }
}

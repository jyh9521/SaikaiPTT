package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.protocol.ProtocolFixtures.SENDER
import com.saikai.ptt.core.protocol.ProtocolFixtures.SESSION
import com.saikai.ptt.core.protocol.ProtocolFixtures.TARGET
import com.saikai.ptt.core.protocol.ProtocolFixtures.TIMESTAMP
import com.saikai.ptt.core.protocol.ProtocolFixtures.bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the header to `docs/ADR/ADR-003-Wire-Format.md` section 2, byte by byte.
 *
 * A round-trip test cannot catch a wrong offset or a flipped byte order: both
 * sides of the round trip share the mistake. Only a literal expectation, written
 * from the ADR table rather than from the implementation, can. If this test
 * fails, either the ADR changed -- in which case the protocol version must be
 * bumped -- or the codec drifted.
 */
class PacketHeaderTest {

    private val presence = PresencePayload(PeerState.BUSY, voicePort = 45_821, userName = "AB")

    private val header = PacketHeader.of(
        type = PacketType.HEARTBEAT,
        senderDeviceId = SENDER,
        payload = presence,
        timestampMillis = TIMESTAMP,
        targetDeviceId = TARGET,
        sessionId = SESSION,
        sequenceNumber = 0x01020304,
    )

    // Written out from the ADR table, not captured from a run.
    private val expectedHeaderBytes = bytes(
        // 0  Magic "SKPT"
        0x53, 0x4B, 0x50, 0x54,
        // 4  ProtocolVersion
        0x01,
        // 5  PacketType HEARTBEAT
        0x10,
        // 6  Flags
        0x00, 0x00,
        // 8  PayloadLength = 4 fixed + 2 name bytes
        0x00, 0x06,
        // 10 Reserved
        0x00, 0x00,
        // 12 SenderDeviceId
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF,
        // 28 TargetDeviceId
        0x0F, 0x1E, 0x2D, 0x3C, 0x4B, 0x5A, 0x69, 0x78,
        0x87, 0x96, 0xA5, 0xB4, 0xC3, 0xD2, 0xE1, 0xF0,
        // 44 SessionId
        0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0,
        0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0,
        // 60 SequenceNumber
        0x01, 0x02, 0x03, 0x04,
        // 64 Timestamp
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
    )

    @Test
    fun `header is exactly 72 bytes`() {
        assertEquals(72, WireFormat.HEADER_BYTES)
        assertEquals(72, header.toBytes().size)
    }

    @Test
    fun `header encodes to the exact bytes the ADR specifies`() {
        assertArrayEquals(expectedHeaderBytes, header.toBytes())
    }

    @Test
    fun `field offsets match the ADR table`() {
        assertEquals(0, WireFormat.OFFSET_MAGIC)
        assertEquals(4, WireFormat.OFFSET_PROTOCOL_VERSION)
        assertEquals(5, WireFormat.OFFSET_PACKET_TYPE)
        assertEquals(6, WireFormat.OFFSET_FLAGS)
        assertEquals(8, WireFormat.OFFSET_PAYLOAD_LENGTH)
        assertEquals(10, WireFormat.OFFSET_RESERVED)
        assertEquals(12, WireFormat.OFFSET_SENDER_DEVICE_ID)
        assertEquals(28, WireFormat.OFFSET_TARGET_DEVICE_ID)
        assertEquals(44, WireFormat.OFFSET_SESSION_ID)
        assertEquals(60, WireFormat.OFFSET_SEQUENCE_NUMBER)
        assertEquals(64, WireFormat.OFFSET_TIMESTAMP)
    }

    @Test
    fun `multi-byte fields are big-endian`() {
        // Byte order is invisible in a round trip and fatal between two devices,
        // so assert the high byte comes first for each width in use.
        val encoded = PacketHeader.of(
            type = PacketType.PING,
            senderDeviceId = SENDER,
            payload = EmptyPayload,
            timestampMillis = 0x0000_0000_0000_00FFL,
            targetDeviceId = TARGET,
            sequenceNumber = 0x000000FF,
            flags = 0x00FF,
        ).toBytes()

        assertEquals(0x00.toByte(), encoded[WireFormat.OFFSET_FLAGS])
        assertEquals(0xFF.toByte(), encoded[WireFormat.OFFSET_FLAGS + 1])
        assertEquals(0x00.toByte(), encoded[WireFormat.OFFSET_SEQUENCE_NUMBER])
        assertEquals(0xFF.toByte(), encoded[WireFormat.OFFSET_SEQUENCE_NUMBER + 3])
        assertEquals(0x00.toByte(), encoded[WireFormat.OFFSET_TIMESTAMP])
        assertEquals(0xFF.toByte(), encoded[WireFormat.OFFSET_TIMESTAMP + 7])
    }

    @Test
    fun `header round trips through bytes`() {
        val decoded = PacketHeader.read(header.toBytes())
        assertEquals(header, decoded)
    }

    @Test
    fun `header reads from a non-zero offset`() {
        val padded = ByteArray(100)
        header.writeTo(padded, 20)
        assertEquals(header, PacketHeader.read(padded, 20, 72))
    }

    @Test
    fun `header read returns null when fewer than 72 bytes are present`() {
        val truncated = header.toBytes().copyOf(71)
        assertNull(PacketHeader.read(truncated))
        assertNull(PacketHeader.read(header.toBytes(), 0, 71))
    }

    @Test
    fun `header read does not judge magic version or type`() {
        // Those are separate, separately counted validation steps (ADR section 8).
        val corrupted = header.toBytes()
        corrupted[WireFormat.OFFSET_MAGIC] = 0x00
        corrupted[WireFormat.OFFSET_PROTOCOL_VERSION] = 0x09
        corrupted[WireFormat.OFFSET_PACKET_TYPE] = 0x77.toByte()

        val decoded = PacketHeader.read(corrupted)
        assertNotNull(decoded)
        assertEquals(9, decoded!!.protocolVersion)
        assertEquals(0x77, decoded.packetTypeCode)
        assertNull(decoded.packetType)
    }

    @Test
    fun `magic check accepts SKPT and rejects everything else`() {
        assertEquals(true, WireFormat.hasMagic(header.toBytes()))
        assertEquals(false, WireFormat.hasMagic(bytes(0x53, 0x4B, 0x50, 0x55)))
        assertEquals(false, WireFormat.hasMagic(bytes(0x53, 0x4B, 0x50)))
        assertEquals(false, WireFormat.hasMagic(ByteArray(0)))
    }

    @Test
    fun `flags and reserved survive decoding unchanged`() {
        // v1 requires both to be zero, but the reader must report what arrived so
        // validation can decide, and so an unknown flag bit is ignored, not erased.
        val raw = header.toBytes()
        raw[WireFormat.OFFSET_FLAGS + 1] = 0x05
        raw[WireFormat.OFFSET_RESERVED + 1] = 0x09

        val decoded = PacketHeader.read(raw)!!
        assertEquals(5, decoded.flags)
        assertEquals(9, decoded.reserved)
    }
}

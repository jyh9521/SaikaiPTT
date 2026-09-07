package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.protocol.ProtocolFixtures.SENDER
import com.saikai.ptt.core.protocol.ProtocolFixtures.SESSION
import com.saikai.ptt.core.protocol.ProtocolFixtures.TARGET
import com.saikai.ptt.core.protocol.ProtocolFixtures.TIMESTAMP
import com.saikai.ptt.core.protocol.ProtocolFixtures.datagram
import com.saikai.ptt.core.protocol.ProtocolFixtures.payloadFor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round trips and rejections for the whole codec.
 *
 * The round trips are the task's acceptance criterion. The rejections matter as
 * much: every one of them is a datagram some other device on the LAN will
 * eventually send, and none of them may throw.
 */
class PacketCodecTest {

    @Test
    fun `every implemented type round trips`() {
        for (type in PacketType.implemented) {
            val original = Packet.of(
                type = type,
                senderDeviceId = SENDER,
                payload = payloadFor(type),
                timestampMillis = TIMESTAMP,
                targetDeviceId = TARGET,
                sessionId = SESSION,
                sequenceNumber = 7,
            )

            val decoded = PacketCodec.decode(original.toBytes())

            assertNotNull("$type failed to decode", decoded)
            assertEquals("$type header changed", original.header, decoded!!.header)
            assertEquals("$type payload changed", original.payload, decoded.payload)
        }
    }

    @Test
    fun `round trip survives a non-zero buffer offset`() {
        val original = Packet.of(
            type = PacketType.VOICE_START,
            senderDeviceId = SENDER,
            payload = payloadFor(PacketType.VOICE_START),
            timestampMillis = TIMESTAMP,
            targetDeviceId = TARGET,
            sessionId = SESSION,
        )
        val buffer = ByteArray(original.encodedSize + 40)
        val length = original.encodeTo(buffer, 13)

        assertEquals(original, PacketCodec.decode(buffer, 13, length))
    }

    @Test
    fun `payload length in the header equals the encoded payload`() {
        for (type in PacketType.implemented) {
            val packet = Packet.of(
                type = type,
                senderDeviceId = SENDER,
                payload = payloadFor(type),
                timestampMillis = TIMESTAMP,
                targetDeviceId = TARGET,
                sessionId = SESSION,
            )
            assertEquals(
                "$type",
                packet.encodedSize - WireFormat.HEADER_BYTES,
                packet.header.payloadLength,
            )
        }
    }

    @Test
    fun `no datagram exceeds the MTU budget`() {
        for (type in PacketType.implemented) {
            val size = Packet.of(
                type = type,
                senderDeviceId = SENDER,
                payload = payloadFor(type),
                timestampMillis = TIMESTAMP,
                targetDeviceId = TARGET,
                sessionId = SESSION,
            ).encodedSize
            assertTrue("$type is $size bytes", size <= WireFormat.MAX_DATAGRAM_BYTES)
        }
        assertTrue(WireFormat.MAX_DATAGRAM_BYTES < 1500)
    }

    @Test
    fun `decode rejects a datagram shorter than the header`() {
        val short = datagram(PacketType.HEARTBEAT).copyOf(71)
        assertNull(PacketCodec.decode(short))
        assertNull(PacketCodec.decode(ByteArray(0)))
    }

    @Test
    fun `decode rejects foreign traffic that lacks the magic`() {
        val raw = datagram(PacketType.HEARTBEAT)
        raw[WireFormat.OFFSET_MAGIC + 3] = 0x55
        assertNull(PacketCodec.decode(raw))
    }

    @Test
    fun `decode rejects an unsupported protocol version`() {
        val raw = datagram(PacketType.HEARTBEAT)
        raw[WireFormat.OFFSET_PROTOCOL_VERSION] = 0x02
        assertNull(PacketCodec.decode(raw))
    }

    @Test
    fun `decode rejects an unknown packet type`() {
        val raw = datagram(PacketType.HEARTBEAT)
        raw[WireFormat.OFFSET_PACKET_TYPE] = 0x7F
        assertNull(PacketCodec.decode(raw))
    }

    @Test
    fun `decode rejects the reserved packet types`() {
        for (type in PacketType.reserved) {
            val raw = datagram(PacketType.PING)
            raw[WireFormat.OFFSET_PACKET_TYPE] = type.code.toByte()
            assertNull("$type must not decode in v1", PacketCodec.decode(raw))
        }
    }

    @Test
    fun `decode rejects a payload length that disagrees with the datagram`() {
        val raw = datagram(PacketType.HEARTBEAT)
        // Claim one byte more than actually arrived.
        WireFormat.writeU16(raw, WireFormat.OFFSET_PAYLOAD_LENGTH, raw.size - 72 + 1)
        assertNull(PacketCodec.decode(raw))

        val truncated = datagram(PacketType.HEARTBEAT)
        assertNull(PacketCodec.decode(truncated, 0, truncated.size - 1))
    }

    @Test
    fun `decode rejects a payload length beyond the protocol maximum`() {
        val raw = datagram(PacketType.HEARTBEAT)
        WireFormat.writeU16(raw, WireFormat.OFFSET_PAYLOAD_LENGTH, 1025)
        assertNull(PacketCodec.decode(raw))
    }

    @Test
    fun `decode rejects a voice frame over the voice payload limit`() {
        // Inside the general 1024 limit, outside the 400-byte voice limit.
        val oversized = ByteArray(WireFormat.HEADER_BYTES + 401)
        val header = PacketHeader(
            protocolVersion = WireFormat.PROTOCOL_VERSION,
            packetTypeCode = PacketType.VOICE_DATA.code,
            flags = 0,
            payloadLength = 401,
            reserved = 0,
            senderDeviceId = SENDER,
            targetDeviceId = TARGET,
            sessionId = SESSION,
            sequenceNumber = 1,
            timestampMillis = TIMESTAMP,
        )
        header.writeTo(oversized)

        assertNull(PacketCodec.decode(oversized))
    }

    @Test
    fun `decode rejects an empty voice frame`() {
        val raw = ByteArray(WireFormat.HEADER_BYTES)
        PacketHeader(
            protocolVersion = WireFormat.PROTOCOL_VERSION,
            packetTypeCode = PacketType.VOICE_DATA.code,
            flags = 0,
            payloadLength = 0,
            reserved = 0,
            senderDeviceId = SENDER,
            targetDeviceId = TARGET,
            sessionId = SESSION,
            sequenceNumber = 1,
            timestampMillis = TIMESTAMP,
        ).writeTo(raw)

        assertNull(PacketCodec.decode(raw))
    }

    @Test
    fun `decode rejects a payload on a type that must carry none`() {
        for (type in listOf(
            PacketType.PING,
            PacketType.PONG,
            PacketType.VOICE_ACCEPT,
            PacketType.BUSY,
        )) {
            val raw = ByteArray(WireFormat.HEADER_BYTES + 1)
            PacketHeader(
                protocolVersion = WireFormat.PROTOCOL_VERSION,
                packetTypeCode = type.code,
                flags = 0,
                payloadLength = 1,
                reserved = 0,
                senderDeviceId = SENDER,
                targetDeviceId = TARGET,
                sessionId = SessionId.ZERO,
                sequenceNumber = 0,
                timestampMillis = TIMESTAMP,
            ).writeTo(raw)

            assertNull("$type must carry no payload", PacketCodec.decode(raw))
        }
    }

    @Test
    fun `decode never throws on arbitrary bytes`() {
        val random = java.util.Random(20260907)
        val buffer = ByteArray(200)
        repeat(2_000) {
            random.nextBytes(buffer)
            val length = 1 + random.nextInt(buffer.size)
            PacketCodec.decode(buffer, 0, length)
        }
        // Also fuzz packets that start out valid, so the magic check does not
        // short-circuit every case above.
        val valid = datagram(PacketType.HEARTBEAT)
        repeat(2_000) {
            val mutated = valid.copyOf()
            mutated[4 + random.nextInt(mutated.size - 4)] = random.nextInt(256).toByte()
            PacketCodec.decode(mutated)
        }
    }

    @Test
    fun `the voice fast path produces the same bytes as the general path`() {
        val frame = ByteArray(53) { (it * 11 + 3).toByte() }
        val expected = Packet.of(
            type = PacketType.VOICE_DATA,
            senderDeviceId = SENDER,
            payload = VoiceDataPayload(frame),
            timestampMillis = TIMESTAMP,
            targetDeviceId = TARGET,
            sessionId = SESSION,
            sequenceNumber = 42,
        ).toBytes()

        val buffer = WireFormat.newDatagramBuffer()
        val length = PacketCodec.encodeVoiceData(
            senderDeviceId = SENDER,
            targetDeviceId = TARGET,
            sessionId = SESSION,
            sequenceNumber = 42,
            timestampMillis = TIMESTAMP,
            frame = frame,
            frameOffset = 0,
            frameLength = frame.size,
            target = buffer,
        )

        assertEquals(expected.size, length)
        assertArrayEquals(expected, buffer.copyOf(length))
    }

    @Test
    fun `the voice fast path encodes a slice of a larger frame buffer`() {
        val backing = ByteArray(300) { it.toByte() }
        val buffer = WireFormat.newDatagramBuffer()
        val length = PacketCodec.encodeVoiceData(
            senderDeviceId = SENDER,
            targetDeviceId = TARGET,
            sessionId = SESSION,
            sequenceNumber = 1,
            timestampMillis = TIMESTAMP,
            frame = backing,
            frameOffset = 100,
            frameLength = 50,
            target = buffer,
        )

        val decoded = PacketCodec.decode(buffer, 0, length)!!
        val payload = decoded.payload as VoiceDataPayload
        assertArrayEquals(backing.copyOfRange(100, 150), payload.copyFrame())
    }

    @Test
    fun `a decoded voice frame is a view over the receive buffer`() {
        // Documents the aliasing contract that keeps the receive path allocation
        // free: whoever needs the bytes later must copy them.
        val buffer = WireFormat.newDatagramBuffer()
        val frame = ByteArray(20) { 7 }
        val length = PacketCodec.encodeVoiceData(
            senderDeviceId = SENDER,
            targetDeviceId = TARGET,
            sessionId = SESSION,
            sequenceNumber = 1,
            timestampMillis = TIMESTAMP,
            frame = frame,
            frameOffset = 0,
            frameLength = frame.size,
            target = buffer,
        )
        val payload = PacketCodec.decode(buffer, 0, length)!!.payload as VoiceDataPayload
        val copy = payload.copyFrame()

        buffer[WireFormat.HEADER_BYTES] = 99

        assertEquals(99.toByte(), payload.frame[payload.frameOffset])
        assertEquals(7.toByte(), copy[0])
    }
}

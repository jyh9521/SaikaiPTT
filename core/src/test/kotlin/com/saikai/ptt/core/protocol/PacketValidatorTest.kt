package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.FakeClock
import com.saikai.ptt.core.RecordingSink
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.ProtocolFixtures.payloadFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The twelve ordered checks of `docs/ADR/ADR-003-Wire-Format.md` section 8.
 *
 * Two things are being asserted throughout: that each step rejects what it is
 * supposed to, and that the steps run *in order*. The order is not decoration --
 * it is what keeps the cost of someone else's broadcast traffic down to four
 * byte comparisons -- so a datagram that is broken at several steps must be
 * reported as broken at the earliest one.
 */
class PacketValidatorTest {

    private val local = DeviceId.parse("11111111-2222-3333-4444-555555555555")!!
    private val peer = ProtocolFixtures.SENDER
    private val stranger = DeviceId.parse("99999999-8888-7777-6666-555555555555")!!
    private val session = ProtocolFixtures.SESSION

    private val sink = RecordingSink()
    private val logger = Logger(LoggingConfig.debug(), sink, FakeClock())

    private var openSession: ReceivingSession? = ReceivingSession(session, peer)

    private val validator = PacketValidator(
        localDeviceId = local,
        logger = logger,
        receivingSession = { openSession },
    )

    private fun bytesOf(
        type: PacketType,
        sender: DeviceId = peer,
        target: DeviceId = local,
        sessionId: SessionId = session,
        payload: PacketPayload = payloadFor(type),
        sequence: Int = 1,
    ): ByteArray = Packet.of(
        type = type,
        senderDeviceId = sender,
        payload = payload,
        timestampMillis = ProtocolFixtures.TIMESTAMP,
        targetDeviceId = target,
        sessionId = sessionId,
        sequenceNumber = sequence,
    ).toBytes()

    private fun reasonFor(raw: ByteArray, length: Int = raw.size): RejectionReason? =
        validator.validate(raw, 0, length).errorOrNull()

    // --- Happy path ------------------------------------------------------------

    @Test
    fun `every implemented packet type passes when it is well formed`() {
        for (type in PacketType.implemented) {
            val target = if (type.requiresTarget) local else DeviceId.ZERO
            val sessionId = if (type.requiresSession) session else SessionId.ZERO
            val outcome = validator.validate(
                bytesOf(type, target = target, sessionId = sessionId)
            )
            assertTrue("$type was rejected: ${outcome.errorOrNull()}", outcome.isSuccess)
        }
        assertEquals(PacketType.implemented.size.toLong(), validator.stats.accepted())
        assertEquals(0L, validator.stats.totalRejected())
    }

    // --- Step 1: length --------------------------------------------------------

    @Test
    fun `step 1 rejects anything shorter than a header`() {
        val raw = bytesOf(PacketType.HEARTBEAT, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
        assertEquals(RejectionReason.TOO_SHORT, reasonFor(raw, 71))
        assertEquals(RejectionReason.TOO_SHORT, reasonFor(ByteArray(0)))
        assertEquals(RejectionReason.TOO_SHORT, reasonFor(ByteArray(71)))
    }

    @Test
    fun `step 1 rejects an out-of-bounds range instead of throwing`() {
        val raw = ByteArray(100)
        assertEquals(RejectionReason.TOO_SHORT, validator.validate(raw, 50, 100).errorOrNull())
        assertEquals(RejectionReason.TOO_SHORT, validator.validate(raw, -1, 10).errorOrNull())
    }

    // --- Step 2: magic ---------------------------------------------------------

    @Test
    fun `step 2 rejects foreign traffic`() {
        val raw = bytesOf(PacketType.HEARTBEAT, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
        raw[WireFormat.OFFSET_MAGIC + 2] = 0x00
        assertEquals(RejectionReason.BAD_MAGIC, reasonFor(raw))
    }

    @Test
    fun `a magic failure stops before any later field is examined`() {
        // Every field after the magic is wrong here: version 0xFF, an unknown
        // type, a non-zero Reserved, a zero sender, and a payload length that
        // cannot match the datagram. If the validator reported anything other
        // than BAD_MAGIC it would mean it had gone on parsing.
        val raw = ByteArray(WireFormat.HEADER_BYTES) { 0xFF.toByte() }
        raw[WireFormat.OFFSET_MAGIC] = 0x53 // S
        raw[WireFormat.OFFSET_MAGIC + 1] = 0x4B // K
        raw[WireFormat.OFFSET_MAGIC + 2] = 0x50 // P
        raw[WireFormat.OFFSET_MAGIC + 3] = 0x00 // not T

        assertEquals(RejectionReason.BAD_MAGIC, reasonFor(raw))
        assertEquals(1L, validator.stats.rejected(RejectionReason.BAD_MAGIC))
        // Nothing later in the order was even reached.
        for (reason in RejectionReason.entries.filter { it.step > 2 }) {
            assertEquals("$reason must not have been evaluated", 0L, validator.stats.rejected(reason))
        }

        // And the same bytes with the magic repaired fail somewhere later, which
        // is what makes the assertion above meaningful rather than vacuous.
        raw[WireFormat.OFFSET_MAGIC + 3] = 0x54
        assertNotEquals(RejectionReason.BAD_MAGIC, reasonFor(raw))
    }

    // --- Step 3: version -------------------------------------------------------

    @Test
    fun `step 3 rejects a version this build does not speak`() {
        val raw = bytesOf(PacketType.HEARTBEAT, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
        raw[WireFormat.OFFSET_PROTOCOL_VERSION] = 0x02
        assertEquals(RejectionReason.UNSUPPORTED_VERSION, reasonFor(raw))

        raw[WireFormat.OFFSET_PROTOCOL_VERSION] = 0x00
        assertEquals(RejectionReason.UNSUPPORTED_VERSION, reasonFor(raw))
    }

    // --- Step 4: payload length ------------------------------------------------

    @Test
    fun `step 4 rejects a length that disagrees with the datagram`() {
        val raw = bytesOf(PacketType.HEARTBEAT, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
        WireFormat.writeU16(raw, WireFormat.OFFSET_PAYLOAD_LENGTH, raw.size - 72 + 1)
        assertEquals(RejectionReason.PAYLOAD_LENGTH_MISMATCH, reasonFor(raw))
    }

    @Test
    fun `step 4 rejects a payload length beyond the protocol maximum`() {
        val raw = bytesOf(PacketType.HEARTBEAT, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
        WireFormat.writeU16(raw, WireFormat.OFFSET_PAYLOAD_LENGTH, 1025)
        assertEquals(RejectionReason.PAYLOAD_LENGTH_MISMATCH, reasonFor(raw))
    }

    @Test
    fun `step 4 rejects a datagram larger than the MTU budget`() {
        // 03_Protocol section 30: the receive buffer is 2048 bytes and anything
        // over 1096 is dropped. It is caught here, by the length agreement.
        val oversized = ByteArray(1200)
        bytesOf(PacketType.HEARTBEAT, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
            .copyInto(oversized)
        assertEquals(RejectionReason.PAYLOAD_LENGTH_MISMATCH, reasonFor(oversized))
    }

    // --- Step 5: reserved ------------------------------------------------------

    @Test
    fun `step 5 rejects a non-zero reserved field`() {
        val raw = bytesOf(PacketType.HEARTBEAT, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
        raw[WireFormat.OFFSET_RESERVED + 1] = 0x01
        assertEquals(RejectionReason.RESERVED_NOT_ZERO, reasonFor(raw))
    }

    @Test
    fun `unknown flag bits are ignored rather than rejected`() {
        // ADR-003 section 2: v1 sends zero flags but a receiver must ignore bits
        // it does not know, or a v2 device setting one becomes undiscoverable.
        val raw = bytesOf(PacketType.HEARTBEAT, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
        raw[WireFormat.OFFSET_FLAGS] = 0x80.toByte()
        raw[WireFormat.OFFSET_FLAGS + 1] = 0x01
        assertTrue(validator.validate(raw).isSuccess)
    }

    // --- Step 6: packet type ---------------------------------------------------

    @Test
    fun `step 6 rejects an unknown packet type`() {
        val raw = bytesOf(PacketType.PING)
        raw[WireFormat.OFFSET_PACKET_TYPE] = 0x7E
        assertEquals(RejectionReason.UNKNOWN_PACKET_TYPE, reasonFor(raw))
    }

    @Test
    fun `step 6 rejects the types reserved for later versions`() {
        for (type in PacketType.reserved) {
            val raw = bytesOf(PacketType.PING)
            raw[WireFormat.OFFSET_PACKET_TYPE] = type.code.toByte()
            assertEquals("$type", RejectionReason.UNKNOWN_PACKET_TYPE, reasonFor(raw))
        }
    }

    // --- Step 7: sender --------------------------------------------------------

    @Test
    fun `step 7 rejects the all-zero sender`() {
        val raw = bytesOf(PacketType.HEARTBEAT, sender = DeviceId.ZERO, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
        assertEquals(RejectionReason.SENDER_INVALID, reasonFor(raw))
    }

    @Test
    fun `step 7 drops this device's own broadcast without calling it invalid`() {
        // Every broadcast comes straight back. Counting it as invalid would let
        // the device rate-limit itself into silence.
        val raw = bytesOf(PacketType.HEARTBEAT, sender = local, target = DeviceId.ZERO, sessionId = SessionId.ZERO)

        assertEquals(RejectionReason.OWN_BROADCAST_ECHO, reasonFor(raw))
        assertEquals(1L, validator.stats.rejected(RejectionReason.OWN_BROADCAST_ECHO))
        assertEquals(0L, validator.stats.totalInvalid())
        assertTrue("the echo must not be logged", sink.entries.isEmpty())
    }

    // --- Step 8: target --------------------------------------------------------

    @Test
    fun `step 8 rejects a unicast packet addressed to someone else`() {
        for (type in PacketType.implemented.filter { it.requiresTarget }) {
            val sessionId = if (type.requiresSession) session else SessionId.ZERO
            val raw = bytesOf(type, target = stranger, sessionId = sessionId)
            assertEquals("$type", RejectionReason.WRONG_TARGET, reasonFor(raw))
        }
    }

    @Test
    fun `step 8 does not demand a target on broadcast types`() {
        for (type in listOf(PacketType.DISCOVERY, PacketType.HEARTBEAT)) {
            val raw = bytesOf(type, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
            assertTrue("$type", validator.validate(raw).isSuccess)
        }
    }

    // --- Step 9: session -------------------------------------------------------

    @Test
    fun `step 9 rejects a session packet that names no session`() {
        for (type in PacketType.implemented.filter { it.requiresSession }) {
            val raw = bytesOf(type, sessionId = SessionId.ZERO)
            assertEquals("$type", RejectionReason.MISSING_SESSION, reasonFor(raw))
        }
    }

    @Test
    fun `a refusal carries no session and is still accepted`() {
        // BUSY means no session was created. The requester matches it by sender.
        val raw = bytesOf(PacketType.BUSY, sessionId = SessionId.ZERO)
        assertTrue(validator.validate(raw).isSuccess)
    }

    // --- Step 10: payload ------------------------------------------------------

    @Test
    fun `step 10 rejects a payload that does not fit its type`() {
        val raw = bytesOf(PacketType.VOICE_END)
        // VOICE_END is exactly eight bytes; claim seven and truncate to match, so
        // step 4 still agrees and the failure really is the payload layout.
        val shortened = raw.copyOf(raw.size - 1)
        WireFormat.writeU16(shortened, WireFormat.OFFSET_PAYLOAD_LENGTH, 7)
        assertEquals(RejectionReason.MALFORMED_PAYLOAD, reasonFor(shortened))
    }

    @Test
    fun `step 10 rejects a name that is not valid UTF-8`() {
        val raw = bytesOf(PacketType.DISCOVERY, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
        // Overwrite the first name byte with a lone continuation byte.
        raw[WireFormat.HEADER_BYTES + PresencePayload.FIXED_BYTES] = 0x80.toByte()
        assertEquals(RejectionReason.MALFORMED_PAYLOAD, reasonFor(raw))
    }

    // --- Step 11: session ownership --------------------------------------------

    @Test
    fun `step 11 accepts a voice frame from the open session`() {
        assertTrue(validator.validate(bytesOf(PacketType.VOICE_DATA)).isSuccess)
    }

    @Test
    fun `step 11 rejects a voice frame when no session is open`() {
        openSession = null
        assertEquals(RejectionReason.FOREIGN_SESSION, reasonFor(bytesOf(PacketType.VOICE_DATA)))
        // Section 45: late frames are expected traffic, not a fault. Silent, uncounted.
        assertEquals(0L, validator.stats.totalInvalid())
        assertTrue(sink.entries.isEmpty())
    }

    @Test
    fun `step 11 rejects a voice frame from a session that has ended`() {
        val raw = bytesOf(PacketType.VOICE_DATA, sessionId = SessionId.random())
        assertEquals(RejectionReason.FOREIGN_SESSION, reasonFor(raw))
    }

    @Test
    fun `step 11 rejects a third device injecting into the open session`() {
        // 03_Protocol section 35. The session id is right; the speaker is not.
        val raw = bytesOf(PacketType.VOICE_DATA, sender = stranger)
        assertEquals(RejectionReason.FOREIGN_SESSION_SENDER, reasonFor(raw))
        assertEquals(1L, validator.stats.totalInvalid())
    }

    // --- Step 12: per-type size caps --------------------------------------------

    @Test
    fun `the per-type payload caps match the ADR`() {
        assertEquals(68, PacketType.HEARTBEAT.maxPayloadBytes) // 4 + 64
        assertEquals(68, PacketType.DISCOVERY.maxPayloadBytes)
        assertEquals(68, PacketType.DISCOVERY_RESPONSE.maxPayloadBytes)
        assertEquals(72, PacketType.VOICE_START.maxPayloadBytes) // 8 + 64
        assertEquals(400, PacketType.VOICE_DATA.maxPayloadBytes)
        assertEquals(8, PacketType.VOICE_END.maxPayloadBytes)
        assertEquals(1, PacketType.SESSION_TERMINATE.maxPayloadBytes)
        for (type in listOf(PacketType.PING, PacketType.PONG, PacketType.VOICE_ACCEPT, PacketType.BUSY)) {
            assertEquals("$type", 0, type.maxPayloadBytes)
        }
    }

    @Test
    fun `a payload one byte over its type's cap never gets through`() {
        for (type in PacketType.implemented) {
            val over = type.maxPayloadBytes + 1
            val raw = ByteArray(WireFormat.HEADER_BYTES + over)
            PacketHeader(
                protocolVersion = WireFormat.PROTOCOL_VERSION,
                packetTypeCode = type.code,
                flags = 0,
                payloadLength = over,
                reserved = 0,
                senderDeviceId = peer,
                targetDeviceId = local,
                sessionId = session,
                sequenceNumber = 1,
                timestampMillis = ProtocolFixtures.TIMESTAMP,
            ).writeTo(raw)

            val outcome = validator.validate(raw)
            assertTrue("$type accepted an oversized payload", outcome.errorOrNull() != null)
        }
    }

    // --- Ordering ---------------------------------------------------------------

    @Test
    fun `an earlier step always wins over a later one`() {
        // Each entry breaks its own step and every step after it. The reported
        // reason must be the earliest break, which is what proves the order.
        val breaks: List<Pair<RejectionReason, (ByteArray) -> ByteArray>> = listOf(
            RejectionReason.UNSUPPORTED_VERSION to { raw ->
                raw.also { it[WireFormat.OFFSET_PROTOCOL_VERSION] = 0x09 }
            },
            RejectionReason.PAYLOAD_LENGTH_MISMATCH to { raw ->
                raw.also { WireFormat.writeU16(it, WireFormat.OFFSET_PAYLOAD_LENGTH, 999) }
            },
            RejectionReason.RESERVED_NOT_ZERO to { raw ->
                raw.also { it[WireFormat.OFFSET_RESERVED] = 0x01 }
            },
            RejectionReason.UNKNOWN_PACKET_TYPE to { raw ->
                raw.also { it[WireFormat.OFFSET_PACKET_TYPE] = 0x7D }
            },
            RejectionReason.SENDER_INVALID to { raw ->
                raw.also { DeviceId.ZERO.writeTo(it, WireFormat.OFFSET_SENDER_DEVICE_ID) }
            },
            RejectionReason.WRONG_TARGET to { raw ->
                raw.also { stranger.writeTo(it, WireFormat.OFFSET_TARGET_DEVICE_ID) }
            },
            RejectionReason.MISSING_SESSION to { raw ->
                raw.also { SessionId.ZERO.writeTo(it, WireFormat.OFFSET_SESSION_ID) }
            },
        )

        // Apply the breaks cumulatively from the end backwards, so that when the
        // Nth break is added, every later one is already present.
        for (index in breaks.indices) {
            var raw = bytesOf(PacketType.VOICE_START)
            for (later in index until breaks.size) {
                raw = breaks[later].second(raw)
            }
            assertEquals(
                "expected the earliest break to win",
                breaks[index].first,
                reasonFor(raw),
            )
        }
    }

    // --- Robustness --------------------------------------------------------------

    @Test
    fun `no input can make the validator throw`() {
        val random = java.util.Random(20260907)
        val buffer = ByteArray(1400)

        repeat(3_000) {
            random.nextBytes(buffer)
            validator.validate(buffer, 0, random.nextInt(buffer.size + 1))
        }

        // Bit flips on packets that start out valid, so the magic check does not
        // absorb every case.
        for (type in PacketType.implemented) {
            val target = if (type.requiresTarget) local else DeviceId.ZERO
            val sessionId = if (type.requiresSession) session else SessionId.ZERO
            val valid = bytesOf(type, target = target, sessionId = sessionId)
            repeat(2_000) {
                val mutated = valid.copyOf()
                val index = random.nextInt(mutated.size)
                mutated[index] = (mutated[index].toInt() xor (1 shl random.nextInt(8))).toByte()
                validator.validate(mutated)
            }
        }

        assertTrue(validator.stats.totalRejected() > 0L)
    }

    @Test
    fun `rejections are throttled to one per kind per second`() {
        val clock = FakeClock()
        val throttledSink = RecordingSink()
        val throttledValidator = PacketValidator(
            localDeviceId = local,
            logger = Logger(LoggingConfig.debug(), throttledSink, clock),
        )
        val raw = bytesOf(PacketType.HEARTBEAT, target = DeviceId.ZERO, sessionId = SessionId.ZERO)
        raw[WireFormat.OFFSET_MAGIC] = 0x00

        repeat(500) { throttledValidator.validate(raw) }
        assertEquals(1, throttledSink.entries.size)

        clock.advance(1_000)
        throttledValidator.validate(raw)
        assertEquals(2, throttledSink.entries.size)
        assertTrue(throttledSink.entries[1].contains("suppressed"))
        assertEquals(501L, throttledValidator.stats.rejected(RejectionReason.BAD_MAGIC))
    }

    @Test
    fun `release builds do not print rejections`() {
        val releaseSink = RecordingSink()
        val releaseValidator = PacketValidator(
            localDeviceId = local,
            logger = Logger(LoggingConfig.release(), releaseSink, FakeClock()),
        )
        val raw = bytesOf(PacketType.PING)
        raw[WireFormat.OFFSET_PACKET_TYPE] = 0x7C

        repeat(100) { releaseValidator.validate(raw) }

        assertTrue(releaseSink.entries.isEmpty())
        assertEquals(100L, releaseValidator.stats.rejected(RejectionReason.UNKNOWN_PACKET_TYPE))
    }

    @Test
    fun `statistics report only what actually happened`() {
        validator.validate(bytesOf(PacketType.VOICE_DATA))
        validator.validate(bytesOf(PacketType.VOICE_DATA, sender = stranger))

        assertEquals(1L, validator.stats.accepted())
        assertEquals(mapOf(RejectionReason.FOREIGN_SESSION_SENDER to 1L), validator.stats.snapshot())

        validator.stats.reset()
        assertEquals(0L, validator.stats.accepted())
        assertTrue(validator.stats.snapshot().isEmpty())
    }

    @Test
    fun `a rejected packet never reaches the caller as a packet`() {
        val raw = bytesOf(PacketType.VOICE_DATA, sender = stranger)
        val outcome: Outcome<Packet, RejectionReason> = validator.validate(raw)
        assertNull(outcome.valueOrNull())
    }
}

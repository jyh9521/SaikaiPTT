package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.protocol.ProtocolFixtures.SENDER
import com.saikai.ptt.core.protocol.ProtocolFixtures.SESSION
import com.saikai.ptt.core.protocol.ProtocolFixtures.TARGET
import com.saikai.ptt.core.protocol.ProtocolFixtures.TIMESTAMP
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One transmission, packetized and read back.
 *
 * The round trip is the acceptance criterion, but the sequence numbering is what
 * this class exists for and where a mistake would be worst: an off-by-one at the
 * start of the stream, or a VOICE_END that names the wrong last frame, produces
 * audio that mostly works. The receiver waits for a frame that will never
 * arrive, or plays out early and clips the last word -- on some transmissions,
 * for some listeners.
 */
class VoicePacketizerTest {

    private val audio = SaikaiConfig().audio

    private fun packetizer() = VoicePacketizer(SENDER, TARGET, SESSION, audio)

    /** Frames of varying, plausible Opus size, distinguishable from each other. */
    private fun frames(count: Int): List<ByteArray> = List(count) { index ->
        ByteArray(40 + index % 17) { position -> (index * 31 + position).toByte() }
    }

    @Test
    fun `a whole transmission round trips, frame for frame`() {
        val subject = packetizer()
        val sent = frames(120)

        val start = PacketCodec.decode(
            subject.controlDatagram, 0, subject.writeVoiceStart("Kenji", TIMESTAMP),
        )
        assertNotNull(start)
        assertEquals(PacketType.VOICE_START, start!!.type)
        assertEquals(SequenceNumbers.VOICE_START, start.header.sequenceNumber)
        assertEquals(SESSION, start.header.sessionId)
        assertEquals(TARGET, start.header.targetDeviceId)
        assertEquals(
            VoiceStartPayload(AudioCodec.OPUS, audio.sampleRateHz, 20, "Kenji"),
            start.payload,
        )

        sent.forEachIndexed { index, frame ->
            val length = subject.writeVoiceData(frame, 0, frame.size, TIMESTAMP + index)
            val packet = PacketCodec.decode(subject.voiceDatagram, 0, length)

            assertNotNull("frame $index failed to decode", packet)
            assertEquals(PacketType.VOICE_DATA, packet!!.type)
            assertEquals("frame $index", index + 1, packet.header.sequenceNumber)
            assertEquals(SESSION, packet.header.sessionId)
            assertArrayEquals(
                "frame $index came back different",
                frame,
                (packet.payload as VoiceDataPayload).copyFrame(),
            )
        }

        val end = PacketCodec.decode(
            subject.controlDatagram, 0, subject.writeVoiceEnd(TIMESTAMP + 1_000),
        )
        assertNotNull(end)
        assertEquals(PacketType.VOICE_END, end!!.type)
        // One past the last frame: 120 frames are numbered 1..120.
        assertEquals(121, end.header.sequenceNumber)
        assertEquals(VoiceEndPayload(finalDataSequence = 120, frameCount = 120), end.payload)
    }

    @Test
    fun `the first frame is sequence one, not zero`() {
        // Zero belongs to VOICE_START. If frames started there, the very first
        // frame of every transmission would collide with the handshake packet.
        val subject = packetizer()
        val length = subject.writeVoiceData(ByteArray(50), 0, 50, TIMESTAMP)

        val packet = PacketCodec.decode(subject.voiceDatagram, 0, length)

        assertEquals(SequenceNumbers.FIRST_VOICE_DATA, packet!!.header.sequenceNumber)
        assertEquals(1, packet.header.sequenceNumber)
    }

    @Test
    fun `voice end with no frames reports zero and takes sequence one`() {
        val subject = packetizer()

        val end = PacketCodec.decode(
            subject.controlDatagram, 0, subject.writeVoiceEnd(TIMESTAMP),
        )!!

        assertEquals(VoiceEndPayload(finalDataSequence = 0, frameCount = 0), end.payload)
        assertEquals(1, end.header.sequenceNumber)
    }

    @Test
    fun `voice end is idempotent`() {
        val subject = packetizer()
        subject.writeVoiceData(ByteArray(50), 0, 50, TIMESTAMP)

        val first = subject.writeVoiceEnd(TIMESTAMP).let { subject.controlDatagram.copyOf(it) }
        val second = subject.writeVoiceEnd(TIMESTAMP).let { subject.controlDatagram.copyOf(it) }

        assertArrayEquals(first, second)
    }

    @Test
    fun `voice start can be retransmitted byte for byte`() {
        // ADR-003 section 6 retransmits VOICE_START up to twice. A retransmission
        // that differed would look to the receiver like a second request.
        val subject = packetizer()

        val first = subject.writeVoiceStart("Kenji", TIMESTAMP)
            .let { subject.controlDatagram.copyOf(it) }
        val second = subject.writeVoiceStart("Kenji", TIMESTAMP)
            .let { subject.controlDatagram.copyOf(it) }

        assertArrayEquals(first, second)
    }

    @Test
    fun `voice accept carries an empty payload and sequence zero`() {
        val subject = packetizer()

        val accept = PacketCodec.decode(
            subject.controlDatagram, 0, subject.writeVoiceAccept(TIMESTAMP),
        )!!

        assertEquals(PacketType.VOICE_ACCEPT, accept.type)
        assertEquals(EmptyPayload, accept.payload)
        assertEquals(0, accept.header.sequenceNumber)
        assertEquals(SESSION, accept.header.sessionId)
    }

    @Test
    fun `an oversized frame is refused without consuming a sequence number`() {
        // The refusal matters less than the sequence number surviving it. A gap
        // tells the receiver a packet was lost, and it will go looking for the
        // FEC copy of a frame that never existed.
        val subject = packetizer()
        val tooBig = ByteArray(WireFormat.MAX_VOICE_PAYLOAD_BYTES + 1)

        assertEquals(VoicePacketizer.REJECTED, subject.writeVoiceData(tooBig, 0, tooBig.size, TIMESTAMP))
        assertEquals(0, subject.framesSent)

        val length = subject.writeVoiceData(ByteArray(50), 0, 50, TIMESTAMP)
        val packet = PacketCodec.decode(subject.voiceDatagram, 0, length)!!

        assertEquals(1, packet.header.sequenceNumber)
        assertEquals(1, subject.framesSent)
    }

    @Test
    fun `an empty frame and a frame outside its buffer are refused`() {
        val subject = packetizer()
        val frame = ByteArray(50)

        assertEquals(VoicePacketizer.REJECTED, subject.writeVoiceData(frame, 0, 0, TIMESTAMP))
        assertEquals(VoicePacketizer.REJECTED, subject.writeVoiceData(frame, 40, 20, TIMESTAMP))
        assertEquals(VoicePacketizer.REJECTED, subject.writeVoiceData(frame, -1, 10, TIMESTAMP))
        assertEquals(0, subject.framesSent)
    }

    @Test
    fun `a frame that exactly fills the cap is accepted`() {
        val subject = packetizer()
        val largest = ByteArray(WireFormat.MAX_VOICE_PAYLOAD_BYTES) { it.toByte() }

        val length = subject.writeVoiceData(largest, 0, largest.size, TIMESTAMP)

        assertEquals(WireFormat.MAX_VOICE_DATAGRAM_BYTES, length)
        assertArrayEquals(
            largest,
            (PacketCodec.decode(subject.voiceDatagram, 0, length)!!.payload as VoiceDataPayload)
                .copyFrame(),
        )
    }

    @Test
    fun `frames after voice end are refused`() {
        val subject = packetizer()
        subject.writeVoiceData(ByteArray(50), 0, 50, TIMESTAMP)
        subject.writeVoiceEnd(TIMESTAMP)

        assertEquals(VoicePacketizer.REJECTED, subject.writeVoiceData(ByteArray(50), 0, 50, TIMESTAMP))
        assertTrue(subject.isEnded)
        assertEquals(1, subject.framesSent)
    }

    @Test
    fun `frames reuse one buffer`() {
        // The requirement is "no allocation per frame". What is observable is
        // that every frame lands in the same array, and that the control packets
        // do not land in that same array -- they are written from another thread.
        val subject = packetizer()
        val voice = subject.voiceDatagram

        subject.writeVoiceData(ByteArray(50), 0, 50, TIMESTAMP)
        subject.writeVoiceData(ByteArray(50), 0, 50, TIMESTAMP)

        assertSame(voice, subject.voiceDatagram)
        assertTrue(subject.controlDatagram !== subject.voiceDatagram)
    }

    @Test
    fun `the offsets a packetizer reports match what it wrote`() {
        val subject = packetizer()

        assertEquals(0, subject.finalDataSequence)
        subject.writeVoiceData(ByteArray(50), 0, 50, TIMESTAMP)
        subject.writeVoiceData(ByteArray(50), 0, 50, TIMESTAMP)
        assertEquals(2, subject.framesSent)
        assertEquals(2, subject.finalDataSequence)
        assertEquals(
            SequenceNumbers.voiceEnd(subject.finalDataSequence),
            PacketCodec.decode(subject.controlDatagram, 0, subject.writeVoiceEnd(TIMESTAMP))!!
                .header.sequenceNumber,
        )
    }

    @Test
    fun `no packet this protocol can produce is large enough to fragment`() {
        // ADR-003: a voice datagram is at most 472 bytes, and the general cap is
        // 1096. With a 20-byte IPv4 header and an 8-byte UDP header, the largest
        // voice datagram puts 500 bytes on the wire. That is inside 576, the
        // smallest IPv4 datagram every host must accept without fragmenting, and
        // it is what makes "one frame, one packet" true rather than aspirational:
        // a fragmented voice packet is lost entirely if either half is dropped,
        // which would turn a 1% loss rate into something much worse.
        val ipv4AndUdpHeaders = 20 + 8

        assertEquals(472, WireFormat.MAX_VOICE_DATAGRAM_BYTES)
        assertTrue(WireFormat.MAX_VOICE_DATAGRAM_BYTES + ipv4AndUdpHeaders <= 576)
        assertTrue(WireFormat.MAX_DATAGRAM_BYTES + ipv4AndUdpHeaders <= 1280)

        // And what the packetizer actually produces, for every type it can emit.
        val subject = packetizer()
        val longestName = "ﾅ".repeat(WireFormat.MAX_USER_NAME_BYTES / 3)
        val lengths = listOf(
            subject.writeVoiceStart(longestName, TIMESTAMP),
            subject.writeVoiceAccept(TIMESTAMP),
            subject.writeVoiceData(
                ByteArray(WireFormat.MAX_VOICE_PAYLOAD_BYTES), 0,
                WireFormat.MAX_VOICE_PAYLOAD_BYTES, TIMESTAMP,
            ),
            subject.writeVoiceEnd(TIMESTAMP),
        )

        assertEquals(WireFormat.MAX_VOICE_DATAGRAM_BYTES, lengths.max())
        assertTrue(lengths.max() + ipv4AndUdpHeaders <= 576)
    }
}

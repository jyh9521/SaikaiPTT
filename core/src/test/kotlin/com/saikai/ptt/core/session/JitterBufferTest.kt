package com.saikai.ptt.core.session

import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.WireFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering, waiting and giving up.
 *
 * Every assertion here is about *when* something is played, not whether. A
 * jitter buffer that eventually emits every frame but emits them at the wrong
 * moment produces audio that is complete and unintelligible, and it only does
 * it on a network with loss -- which is the network this product is for.
 */
class JitterBufferTest {

    private val config = SaikaiConfig()
    private val logger = Logger(
        LoggingConfig.debug(),
        object : LogSink {
            override fun write(
                level: LogLevel,
                category: LogCategory,
                message: String,
                throwable: Throwable?,
            ) = Unit
        },
    )

    private val played = mutableListOf<String>()

    private val sink = object : JitterBufferSink {
        override fun onFrame(frame: ByteArray, offset: Int, length: Int) {
            played += (frame[offset].toInt() and 0xFF).toString()
        }

        override fun onLostFrame(next: ByteArray?, nextOffset: Int, nextLength: Int) {
            played += if (next == null) "gap" else "fec:${next[nextOffset].toInt() and 0xFF}"
        }
    }

    private val buffer = JitterBuffer(config, logger, sink)

    /** A frame whose every byte is its own sequence number, so output is readable. */
    private fun offer(sequence: Int, size: Int = 40) {
        buffer.offer(sequence, ByteArray(size) { sequence.toByte() }, 0, size)
    }

    /** Plays through the priming threshold with frames 1..3. */
    private fun prime() {
        offer(1)
        offer(2)
        offer(3)
    }

    @Test
    fun `nothing is played until the start threshold is reached`() {
        // 60 ms of cushion, paid once, so the first reordering does not have to
        // resolve in zero time.
        offer(1)
        offer(2)

        assertTrue(buffer.isPriming)
        assertEquals(emptyList<String>(), played)

        offer(3)

        assertFalse(buffer.isPriming)
        assertEquals(listOf("1", "2", "3"), played)
    }

    @Test
    fun `frames that arrive out of order are played in order`() {
        offer(1)
        offer(3)
        offer(2)

        assertEquals(listOf("1", "2", "3"), played)
        assertEquals(0, buffer.concealed)
    }

    @Test
    fun `a straggler is waited for, and then given up on`() {
        prime()
        played.clear()

        // Frame 4 is missing. Two newer frames are not enough to give up on it.
        offer(5)
        offer(6)
        assertEquals(emptyList<String>(), played)

        // The third one is: at fifty frames a second, that is the 60 ms the
        // config allows a straggler.
        offer(7)
        assertEquals(listOf("fec:5", "5", "6", "7"), played)
        assertEquals(1, buffer.concealed)
    }

    @Test
    fun `the frame after a gap is offered, because it carries the redundant copy`() {
        // In-band FEC puts a copy of each frame in the *next* packet. Handing
        // the follower over is the only thing that makes the FEC bitrate worth
        // paying for.
        prime()
        played.clear()

        offer(5)
        offer(6)
        offer(7)

        assertEquals("fec:5", played.first())
    }

    @Test
    fun `a straggler that arrives after its turn is dropped, not played late`() {
        prime()
        played.clear()
        offer(5)
        offer(6)
        offer(7)
        played.clear()

        offer(4)

        assertEquals(emptyList<String>(), played)
        assertEquals(1, buffer.droppedLate)
    }

    @Test
    fun `a duplicate is dropped`() {
        offer(1)
        offer(2)
        offer(2)

        assertEquals(1, buffer.droppedLate)
        assertTrue(buffer.isPriming)

        offer(3)
        assertEquals(listOf("1", "2", "3"), played)
    }

    @Test
    fun `a long outage costs the oldest audio, not the newest`() {
        // Beyond ten frames the buffer would be holding 200 ms the listener has
        // not heard, which feels like a fault even when everything arrives.
        prime()
        played.clear()

        // Sixteen frames of silence on the network, then the far end reappears.
        offer(20)

        // Seven were dropped unheard to get back inside the maximum depth, and
        // seven more were concealed -- but not all the way to 20. The buffer
        // stops three frames short of the newest and waits there, because 18
        // and 19 could still be in flight; that is the same rule that governs a
        // straggler, and it is what leaves the buffer sitting at its target
        // depth again rather than empty.
        assertEquals(7, buffer.droppedOverflow)
        assertEquals(7, buffer.concealed)
        assertEquals("gap", played.last())
        assertEquals(1, buffer.depth)

        // And it recovers without further loss once the stream continues.
        played.clear()
        offer(21)
        offer(22)

        assertEquals(listOf("gap", "fec:20", "20", "21", "22"), played)
    }

    @Test
    fun `an empty or oversized frame is refused`() {
        buffer.offer(1, ByteArray(10), 0, 0)
        buffer.offer(2, ByteArray(WireFormat.MAX_VOICE_PAYLOAD_BYTES + 1), 0,
            WireFormat.MAX_VOICE_PAYLOAD_BYTES + 1)

        assertEquals(2, buffer.droppedLate)
        assertEquals(0, buffer.depth)
    }

    @Test
    fun `flush plays out what is held`() {
        offer(1)
        offer(2)
        // Still priming: two frames is below the threshold, and without a flush
        // they would never be heard at all.
        assertEquals(emptyList<String>(), played)

        buffer.flush(finalDataSequence = 2)

        assertEquals(listOf("1", "2"), played)
    }

    @Test
    fun `flush fills a gap inside what it holds`() {
        offer(1)
        offer(3)

        buffer.flush(finalDataSequence = 3)

        assertEquals(listOf("1", "fec:3", "3"), played)
    }

    @Test
    fun `flush does not invent audio for frames that never arrived`() {
        // The sender says it sent a hundred; frame 5 is the newest that reached
        // this device. The other ninety-five are gone rather than late, and
        // ninety-five frames of concealment would be nearly two seconds of
        // noise where silence belongs.
        prime()
        offer(5)
        played.clear()

        buffer.flush(finalDataSequence = 100)

        assertEquals(listOf("fec:5", "5"), played)
        assertEquals(1, buffer.concealed)
    }

    @Test
    fun `flush with nothing received plays nothing`() {
        buffer.flush(finalDataSequence = 0)

        assertEquals(emptyList<String>(), played)
        assertEquals(0, buffer.concealed)
    }

    @Test
    fun `a whole transmission with loss and reordering comes out in order`() {
        val arrival = listOf(1, 2, 4, 3, 5, 7, 8, 9, 6, 10, 12, 13, 14, 15)
        arrival.forEach { offer(it) }
        buffer.flush(finalDataSequence = 15)

        // 6 arrived after 7, 8 and 9, so its turn had passed; 11 never came.
        assertEquals(
            listOf("1", "2", "3", "4", "5", "fec:7", "7", "8", "9", "10", "fec:12",
                "12", "13", "14", "15"),
            played,
        )
        assertEquals(1, buffer.droppedLate)
        assertEquals(2, buffer.concealed)
        assertEquals(13, buffer.played)
    }
}

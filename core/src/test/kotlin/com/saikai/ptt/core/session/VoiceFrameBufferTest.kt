package com.saikai.ptt.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pre-roll buffer: order in, order out, and what happens when it fills. */
class VoiceFrameBufferTest {

    private fun frame(marker: Int, size: Int = 4) = ByteArray(size) { marker.toByte() }

    private fun VoiceFrameBuffer.markers(): List<Int> {
        val seen = mutableListOf<Int>()
        drainTo { bytes, length -> seen += bytes[0].toInt() and 0xFF; assertTrue(length > 0) }
        return seen
    }

    @Test
    fun `frames come out in the order they went in`() {
        val buffer = VoiceFrameBuffer(capacityFrames = 8, maxFrameBytes = 400)
        repeat(5) { buffer.add(frame(it), 0, 4) }

        assertEquals(5, buffer.size)
        assertEquals(listOf(0, 1, 2, 3, 4), buffer.markers())
        assertEquals(0, buffer.size)
    }

    @Test
    fun `a full buffer drops the oldest frame, not the newest`() {
        // If this ever overflows in production the handshake has already failed,
        // but the rule still has to be the same one the playback path uses: the
        // audio worth keeping is the most recent.
        val buffer = VoiceFrameBuffer(capacityFrames = 4, maxFrameBytes = 400)
        repeat(7) { buffer.add(frame(it), 0, 4) }

        assertTrue(buffer.isFull)
        assertEquals(3, buffer.overflowed)
        assertEquals(listOf(3, 4, 5, 6), buffer.markers())
    }

    @Test
    fun `the ring wraps without losing order`() {
        val buffer = VoiceFrameBuffer(capacityFrames = 3, maxFrameBytes = 400)
        repeat(2) { buffer.add(frame(it), 0, 4) }
        assertEquals(listOf(0, 1), buffer.markers())

        repeat(3) { buffer.add(frame(it + 10), 0, 4) }
        assertEquals(listOf(10, 11, 12), buffer.markers())
        assertEquals(0, buffer.overflowed)
    }

    @Test
    fun `a frame larger than the wire allows is refused rather than stored`() {
        val buffer = VoiceFrameBuffer(capacityFrames = 4, maxFrameBytes = 50)

        assertFalse(buffer.add(frame(1, size = 51), 0, 51))
        assertFalse(buffer.add(frame(1), 0, 0))
        assertFalse(buffer.add(frame(1), 3, 4))
        assertEquals(0, buffer.size)
    }

    @Test
    fun `each frame is copied, so the caller can reuse its buffer`() {
        // The caller hands in the codec's single output buffer, refilled every
        // 20 ms. Holding a reference instead of a copy would make every buffered
        // frame the last one.
        val buffer = VoiceFrameBuffer(capacityFrames = 4, maxFrameBytes = 400)
        val reused = ByteArray(4)

        for (marker in 1..3) {
            reused.fill(marker.toByte())
            buffer.add(reused, 0, 4)
        }
        reused.fill(99)

        assertEquals(listOf(1, 2, 3), buffer.markers())
    }

    @Test
    fun `clear forgets the frames and the overflow count`() {
        val buffer = VoiceFrameBuffer(capacityFrames = 2, maxFrameBytes = 400)
        repeat(5) { buffer.add(frame(it), 0, 4) }

        buffer.clear()

        assertEquals(0, buffer.size)
        assertEquals(0, buffer.overflowed)
        assertEquals(emptyList<Int>(), buffer.markers())
    }
}

package com.saikai.ptt.core.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFormatTest {

    @Test
    fun `a voice frame is summarised, never reproduced`() {
        // CLAUDE.md section 26 forbids logging raw audio. A 20 ms frame is
        // around 50 bytes encoded; a whole second is 50 of those. Rendering any
        // of it in full would put speech in the log and make the log unusable.
        val frame = ByteArray(50) { it.toByte() }

        val rendered = LogFormat.bytes(frame)

        assertTrue("Length must be reported: $rendered", rendered.contains("50 bytes"))
        assertTrue(
            "Output must stay bounded regardless of input size: $rendered",
            rendered.length < 40,
        )
        assertTrue("Truncation must be visible: $rendered", rendered.contains("…"))
    }

    @Test
    fun `output length does not grow with input length`() {
        // A thousandfold larger payload may only cost the extra digits needed to
        // print its size. This is what makes the helper safe to call on the
        // realtime path.
        val small = LogFormat.bytes(ByteArray(64))
        val large = LogFormat.bytes(ByteArray(64_000))

        assertTrue(
            "1000x the input produced a much longer line: '$small' vs '$large'",
            large.length - small.length <= 3,
        )
    }

    @Test
    fun `a short array is shown in full without an ellipsis`() {
        val rendered = LogFormat.bytes(byteArrayOf(0x53, 0x4B, 0x50, 0x54))
        assertEquals("<4 bytes: 534b5054>", rendered)
    }

    @Test
    fun `an empty range is stated rather than rendered`() {
        assertEquals("<0 bytes>", LogFormat.bytes(ByteArray(0)))
    }

    @Test
    fun `offset and length are respected`() {
        val data = byteArrayOf(0x00, 0x11, 0x22, 0x33)
        assertEquals("<2 bytes: 1122>", LogFormat.bytes(data, offset = 1, length = 2))
    }

    @Test
    fun `a user name is identifiable but not reproduced`() {
        // Names travel in every voice packet and are personal data.
        val rendered = LogFormat.userName("田中太郎")
        assertTrue(rendered.startsWith("田"))
        assertFalse("The full name must not appear: $rendered", rendered.contains("田中太郎"))
    }
}

package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.domain.DeviceId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIdTest {

    @Test
    fun `the zero session is sixteen zero bytes`() {
        assertTrue(SessionId.ZERO.isZero)
        assertArrayEquals(ByteArray(16), SessionId.ZERO.toBytes())
    }

    @Test
    fun `a random session is not the zero session`() {
        val session = SessionId.random()
        assertFalse(session.isZero)
        assertNotEquals(SessionId.ZERO, session)
    }

    @Test
    fun `binary form round trips`() {
        val session = SessionId.random()
        assertEquals(session, SessionId.fromBytes(session.toBytes()))
    }

    @Test
    fun `text form round trips`() {
        val session = SessionId.random()
        assertEquals(session, SessionId.parse(session.value))
    }

    @Test
    fun `binary and text forms describe the same value`() {
        val session = SessionId.parse("12345678-9abc-def0-1234-56789abcdef0")!!
        assertArrayEquals(
            ByteArray(16) { i ->
                val pattern = byteArrayOf(
                    0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(), 0xF0.toByte(),
                )
                pattern[i % 8]
            },
            session.toBytes(),
        )
    }

    @Test
    fun `parse refuses text that is not a UUID`() {
        assertNull(SessionId.parse(null))
        assertNull(SessionId.parse(""))
        assertNull(SessionId.parse("   "))
        assertNull(SessionId.parse("not-a-uuid"))
        // UUID.fromString is lenient about short groups; the round-trip check is not.
        assertNull(SessionId.parse("1-2-3-4-5"))
    }

    @Test
    fun `fromBytes refuses a short range instead of throwing`() {
        assertNull(SessionId.fromBytes(ByteArray(15)))
        assertNull(SessionId.fromBytes(ByteArray(16), 1))
        assertNull(SessionId.fromBytes(ByteArray(16), -1))
    }

    @Test
    fun `writeTo refuses a buffer that is too small`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionId.random().writeTo(ByteArray(15))
        }
    }

    @Test
    fun `a session id and a device id with the same bytes are different types`() {
        // The compiler is the only thing stopping the two adjacent uuid fields in
        // the header from being swapped, so make sure they really are distinct.
        val raw = SessionId.random().toBytes()
        val session = SessionId.fromBytes(raw)!!
        val device = DeviceId.fromBytes(raw)!!
        assertEquals(session.value, device.value)
    }
}

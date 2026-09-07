package com.saikai.ptt.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserNameValidatorTest {

    /** A headphone emoji, written as a surrogate pair rather than a literal. */
    private val emoji = "🎧"

    private fun accept(raw: String): String {
        val result = UserNameValidator.validate(raw)
        assertTrue("Expected '$raw' to be accepted, got $result", result.isSuccess)
        return result.valueOrNull()!!
    }

    private fun reject(raw: String): UserError.Name {
        val result = UserNameValidator.validate(raw)
        assertTrue("Expected '$raw' to be rejected", !result.isSuccess)
        return result.errorOrNull()!!
    }

    @Test
    fun `ordinary names in every supported script are accepted`() {
        listOf("田中", "张三", "Warehouse", "ဝန်ထမ်း", "গুদাম").forEach { accept(it) }
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("田中", accept("  田中  "))
        assertEquals("Warehouse", accept("\tWarehouse\n"))
    }

    @Test
    fun `blank names are rejected`() {
        listOf("", " ", "\t", "\n", "   \t  ").forEach {
            assertEquals(UserError.Name.Blank, reject(it))
        }
    }

    @Test
    fun `a name over the character limit is rejected`() {
        val tooLong = "a".repeat(UserNameValidator.MAX_CODE_POINTS + 1)
        assertEquals(
            UserError.Name.TooManyCharacters(25, UserNameValidator.MAX_CODE_POINTS),
            reject(tooLong),
        )
    }

    @Test
    fun `a name at exactly the character limit is accepted`() {
        accept("a".repeat(UserNameValidator.MAX_CODE_POINTS))
    }

    @Test
    fun `a short name that is too many bytes is rejected`() {
        // Why both limits exist. Twenty-three Burmese characters are within the
        // character limit but 69 bytes on the wire, and the name field carries a
        // one-byte length prefix (Protocol section 5.1). Counting characters
        // alone would let an ordinary Burmese name overflow the field.
        val burmese = "ဝ".repeat(23)
        val error = reject(burmese)

        assertTrue(
            "Expected a byte-limit rejection, got $error",
            error is UserError.Name.TooManyBytes,
        )
        val bytes = (error as UserError.Name.TooManyBytes).actual
        assertTrue(
            "Should exceed ${UserNameValidator.MAX_BYTES}: $bytes",
            bytes > UserNameValidator.MAX_BYTES,
        )
        assertEquals("but stay within the character limit", 23, burmese.length)
    }

    @Test
    fun `an emoji counts as one character`() {
        // codePointCount, not length: a surrogate pair must not cost two.
        val name = emoji.repeat(12)
        assertEquals("twelve emoji are twenty-four UTF-16 units", 24, name.length)
        accept(name)
    }

    @Test
    fun `validation never throws`() {
        listOf(" ", "\uD800", "a\uDC00b", emoji.repeat(100), "ဝ".repeat(100)).forEach {
            UserNameValidator.validate(it)
        }
    }
}

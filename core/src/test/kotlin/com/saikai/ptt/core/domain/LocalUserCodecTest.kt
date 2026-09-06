package com.saikai.ptt.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUserCodecTest {

    private fun user(id: String, name: String) =
        LocalUser(id, name, 1_700_000_000_000, 1_700_000_001_000)

    @Test
    fun `round trips a list`() {
        val users = listOf(user("a-1", "田中"), user("b-2", "倉庫"), user("c-3", "保安"))
        assertEquals(users, LocalUserCodec.decode(LocalUserCodec.encode(users)))
    }

    @Test
    fun `an empty list round trips`() {
        assertEquals(
            emptyList<LocalUser>(),
            LocalUserCodec.decode(LocalUserCodec.encode(emptyList())),
        )
    }

    // --- Names are user input in five scripts; no character may be special ---

    @Test
    fun `a name containing the field separator round trips`() {
        // The whole reason the format is length-prefixed rather than delimited.
        val users = listOf(user("a", "12:34"), user("b", "::::"))
        assertEquals(users, LocalUserCodec.decode(LocalUserCodec.encode(users)))
    }

    @Test
    fun `a name that looks like an encoded record round trips`() {
        val users = listOf(user("a", "5:hello3:abc"))
        assertEquals(users, LocalUserCodec.decode(LocalUserCodec.encode(users)))
    }

    @Test
    fun `names with newlines round trip`() {
        val users = listOf(user("a", "line1\nline2"))
        assertEquals(users, LocalUserCodec.decode(LocalUserCodec.encode(users)))
    }

    @Test
    fun `names in every supported script round trip`() {
        val users = listOf(
            user("ja", "田中太郎"),
            user("zh", "张三"),
            user("en", "Warehouse"),
            user("my", "ဝန်ထမ်း"),
            user("bn", "গুদাম"),
        )
        assertEquals(users, LocalUserCodec.decode(LocalUserCodec.encode(users)))
    }

    @Test
    fun `an empty display name round trips`() {
        val users = listOf(user("a", ""))
        assertEquals(users, LocalUserCodec.decode(LocalUserCodec.encode(users)))
    }

    // --- Corruption must degrade, never throw --------------------------------
    // A process killed mid-write, or a damaged file, must not stop the app
    // starting (docs/05_DataModel.md section 41).

    @Test
    fun `every truncation decodes to empty rather than a partial user`() {
        val encoded = LocalUserCodec.encode(listOf(user("a-1", "田中")))
        for (cut in 1 until encoded.length) {
            val partial = encoded.substring(0, cut)
            assertTrue(
                "Truncating to $cut chars yielded a partial user: '$partial'",
                LocalUserCodec.decode(partial).isEmpty(),
            )
        }
    }

    @Test
    fun `garbage decodes to empty`() {
        listOf(
            "not encoded at all",
            "::::",
            "-1:x",
            "abc:def",
            "999:short",
            "  ",
            "3:abc",
        ).forEach { garbage ->
            assertEquals(
                "'$garbage' should decode to an empty list",
                emptyList<LocalUser>(),
                LocalUserCodec.decode(garbage),
            )
        }
    }

    @Test
    fun `a non-numeric timestamp decodes to empty`() {
        assertEquals(emptyList<LocalUser>(), LocalUserCodec.decode("3:abc4:name3:xyz3:xyz"))
    }

    @Test
    fun `a record with an empty id is rejected`() {
        // The id is the identity of a stored name. A blank one would leave the
        // active-user pointer unresolvable with no way for the user to fix it.
        val corrupt = "0:4:name13:170000000000013:1700000000000"
        assertEquals(emptyList<LocalUser>(), LocalUserCodec.decode(corrupt))
    }
}

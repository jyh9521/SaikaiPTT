package com.saikai.ptt.core.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The register that stops cleanup deleting a file somebody is holding open.
 *
 * Counted rather than flagged, because two holders are possible -- a recording
 * being written while the same path is somehow read -- and the second release
 * must not free a claim the first still needs.
 */
class ActiveRecordingsTest {

    private val active = ActiveRecordings()

    @Test
    fun `an unclaimed path is not active`() {
        assertFalse(active.isActive("records/a.opus"))
    }

    @Test
    fun `claim and release`() {
        active.claim("records/a.opus")
        assertTrue(active.isActive("records/a.opus"))
        active.release("records/a.opus")
        assertFalse(active.isActive("records/a.opus"))
    }

    @Test
    fun `two claims need two releases`() {
        active.claim("records/a.opus")
        active.claim("records/a.opus")
        active.release("records/a.opus")
        assertTrue("one release must not free the other holder", active.isActive("records/a.opus"))
        active.release("records/a.opus")
        assertFalse(active.isActive("records/a.opus"))
    }

    @Test
    fun `releasing something never claimed is harmless`() {
        active.release("records/never.opus")
        assertFalse(active.isActive("records/never.opus"))
    }

    @Test
    fun `releasing more times than claimed does not go negative`() {
        active.claim("records/a.opus")
        active.release("records/a.opus")
        active.release("records/a.opus")
        active.claim("records/a.opus")
        assertTrue(active.isActive("records/a.opus"))
    }

    @Test
    fun `holding releases even when the block throws`() {
        val failure = runCatching {
            active.holding("records/a.opus") { error("the writer died") }
        }
        assertTrue(failure.isFailure)
        assertFalse("a leaked claim is a file never cleaned up", active.isActive("records/a.opus"))
    }

    @Test
    fun `the snapshot does not change under the caller`() {
        active.claim("records/a.opus")
        val snapshot = active.snapshot()
        active.claim("records/b.opus")
        assertEquals(setOf("records/a.opus"), snapshot)
    }
}

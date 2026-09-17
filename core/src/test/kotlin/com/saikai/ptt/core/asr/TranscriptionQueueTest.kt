package com.saikai.ptt.core.asr

import com.saikai.ptt.core.domain.TranscriptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The queue's job is to be boring: one at a time, in order, a bounded number of
 * tries. What is worth pinning is the arithmetic around retries, because it
 * decides whether a recording is eventually marked FAILED or retried forever.
 */
class TranscriptionQueueTest {

    private fun queue(maxAttempts: Int = 3, capacity: Int = 200) =
        TranscriptionQueue(maxAttempts, capacity)

    // --- ordering and exclusivity ------------------------------------------------------

    @Test
    fun `records come back in the order they arrived`() {
        val q = queue()
        listOf("a", "b", "c").forEach { assertTrue(q.enqueue(it)) }

        assertEquals("a", q.next()?.recordId)
        q.succeeded("a")
        assertEquals("b", q.next()?.recordId)
        q.succeeded("b")
        assertEquals("c", q.next()?.recordId)
    }

    @Test
    fun `only one record is out at a time`() {
        val q = queue()
        q.enqueue("a")
        q.enqueue("b")

        assertEquals("a", q.next()?.recordId)
        assertNull("a second worker must get nothing", q.next())
        assertTrue(q.busy)

        q.succeeded("a")
        assertFalse(q.busy)
        assertEquals("b", q.next()?.recordId)
    }

    @Test
    fun `an empty queue hands out nothing`() {
        assertNull(queue().next())
    }

    @Test
    fun `the same record is not queued twice`() {
        val q = queue()
        assertTrue(q.enqueue("a"))
        assertFalse(q.enqueue("a"))
        assertEquals(1, q.size)
    }

    @Test
    fun `a record that is running is not queued again`() {
        val q = queue()
        q.enqueue("a")
        q.next()

        assertFalse("it is already being worked on", q.enqueue("a"))
    }

    @Test
    fun `a full queue refuses rather than growing`() {
        val q = queue(capacity = 3)
        assertTrue(q.enqueue("a"))
        assertTrue(q.enqueue("b"))
        assertTrue(q.enqueue("c"))

        // Refused, not dropped silently from the far end: the record keeps its
        // PENDING status and the next sweep finds it again.
        assertFalse(q.enqueue("d"))
        assertEquals(3, q.size)
    }

    // --- retries -----------------------------------------------------------------------

    @Test
    fun `a failure retries until the allowance is spent, then marks FAILED`() {
        val q = queue(maxAttempts = 3)
        q.enqueue("a")

        // Attempt 1 and 2 go back to PENDING; the third exhausts it.
        assertEquals(TranscriptStatus.PENDING, attemptAndFail(q, "a", expectedAttempt = 1))
        assertEquals(TranscriptStatus.PENDING, attemptAndFail(q, "a", expectedAttempt = 2))
        assertEquals(TranscriptStatus.FAILED, attemptAndFail(q, "a", expectedAttempt = 3))

        assertNull("it is done, whatever the outcome", q.next())
    }

    @Test
    fun `the last attempt says so before it runs`() {
        val q = queue(maxAttempts = 2)
        q.enqueue("a")

        assertFalse(q.next()!!.lastAttempt)
        q.failed("a")
        assertTrue("the worker can tell the caller this is the final try", q.next()!!.lastAttempt)
    }

    @Test
    fun `zero retries means one attempt and then FAILED`() {
        // maxAttempts = 0 is a legal setting and must not loop forever.
        val q = queue(maxAttempts = 0)
        q.enqueue("a")

        val item = q.next()!!
        assertTrue(item.lastAttempt)
        assertEquals(TranscriptStatus.FAILED, q.failed("a"))
        assertNull(q.next())
    }

    @Test
    fun `a retry goes behind anything newer`() {
        // A recording somebody just made is worth more than one that has
        // already failed twice.
        val q = queue(maxAttempts = 3)
        q.enqueue("old")
        q.next()
        q.enqueue("new")
        q.failed("old")

        assertEquals("new", q.next()?.recordId)
    }

    @Test
    fun `success clears the attempt count`() {
        val q = queue(maxAttempts = 3)
        q.enqueue("a")
        q.next()
        q.failed("a")
        assertEquals(1, q.attemptsFor("a"))

        q.next()
        q.succeeded("a")

        assertEquals("a later request starts fresh", 0, q.attemptsFor("a"))
    }

    @Test
    fun `a record requeued after being marked FAILED gets a fresh allowance`() {
        // This is the manual re-recognition path: the user pressed the button
        // on a record that had given up. It must be able to try again.
        val q = queue(maxAttempts = 2)
        q.enqueue("a")
        q.next(); q.failed("a")
        q.next(); assertEquals(TranscriptStatus.FAILED, q.failed("a"))

        assertTrue(q.enqueue("a"))
        assertEquals(1, q.next()!!.attempt)
    }

    // --- deferral ----------------------------------------------------------------------

    @Test
    fun `a deferral does not spend an attempt`() {
        // A call started, so the work never ran. Charging for that would let a
        // busy hour exhaust a recording's retries without trying it once.
        val q = queue(maxAttempts = 3)
        q.enqueue("a")

        repeat(10) {
            assertEquals(1, q.next()!!.attempt)
            q.defer("a")
        }

        assertEquals(0, q.attemptsFor("a"))
        assertEquals(TranscriptStatus.PENDING, attemptAndFail(q, "a", expectedAttempt = 1))
    }

    @Test
    fun `a deferred record goes back to the front`() {
        // Nothing about it changed, so it keeps its place.
        val q = queue()
        q.enqueue("first")
        q.enqueue("second")
        q.next()
        q.defer("first")

        assertEquals("first", q.next()?.recordId)
    }

    @Test
    fun `a deferral frees the queue for other work`() {
        val q = queue()
        q.enqueue("a")
        q.next()
        assertTrue(q.busy)

        q.defer("a")

        assertFalse(q.busy)
    }

    // --- clearing ----------------------------------------------------------------------

    @Test
    fun `clearing empties everything including what is running`() {
        val q = queue()
        q.enqueue("a")
        q.enqueue("b")
        q.next()

        q.clear()

        assertEquals(0, q.size)
        assertFalse(q.busy)
        assertNull(q.next())
        assertEquals(0, q.attemptsFor("a"))
    }

    // --- concurrency -------------------------------------------------------------------

    @Test
    fun `two workers never hold the same record`() {
        // The service enqueues as calls end; the worker drains. They are
        // different threads, and handing the same id to two of them would mean
        // two recognisers on a 4 GB phone.
        val q = queue(maxAttempts = 1, capacity = 500)
        val total = 300
        repeat(total) { q.enqueue("r$it") }

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(8)

        repeat(8) {
            pool.execute {
                start.await()
                while (true) {
                    val item = q.next()
                    if (item == null) {
                        if (q.size == 0) break else continue
                    }
                    seen += item.recordId
                    q.succeeded(item.recordId)
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("workers finished", done.await(20, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("every record handed out exactly once", total, seen.size)
        assertEquals(total, seen.toSet().size)
    }

    private fun attemptAndFail(
        q: TranscriptionQueue,
        id: String,
        expectedAttempt: Int,
    ): TranscriptStatus {
        val item = q.next()
        assertNotNull("expected an item on attempt $expectedAttempt", item)
        assertEquals(expectedAttempt, item!!.attempt)
        return q.failed(id)
    }
}

package dev.veneranative.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateMachineTest {

    @Test
    fun `a queued page may start or be cancelled, and nothing else`() {
        assertTrue(DownloadStateMachine.canMove(DownloadPageState.Queued, DownloadPageState.Running))
        assertTrue(DownloadStateMachine.canMove(DownloadPageState.Queued, DownloadPageState.Canceled))
        assertFalse(DownloadStateMachine.canMove(DownloadPageState.Queued, DownloadPageState.Succeeded))
        assertFalse(DownloadStateMachine.canMove(DownloadPageState.Queued, DownloadPageState.Failed))
        assertFalse(DownloadStateMachine.canMove(DownloadPageState.Queued, DownloadPageState.Paused))
    }

    @Test
    fun `a running page may land, fail, be paused or be cancelled`() {
        for (to in listOf(
            DownloadPageState.Succeeded,
            DownloadPageState.Failed,
            DownloadPageState.Paused,
            DownloadPageState.Canceled,
        )) {
            assertTrue(DownloadStateMachine.canMove(DownloadPageState.Running, to))
        }
        assertFalse(DownloadStateMachine.canMove(DownloadPageState.Running, DownloadPageState.Queued))
    }

    @Test
    fun `a failed or paused page may go back to the queue or be cancelled`() {
        for (from in listOf(DownloadPageState.Failed, DownloadPageState.Paused)) {
            assertTrue(DownloadStateMachine.canMove(from, DownloadPageState.Queued))
            assertTrue(DownloadStateMachine.canMove(from, DownloadPageState.Canceled))
            assertFalse(DownloadStateMachine.canMove(from, DownloadPageState.Running))
            assertFalse(DownloadStateMachine.canMove(from, DownloadPageState.Succeeded))
        }
    }

    @Test
    fun `a succeeded page never moves again`() {
        for (to in DownloadPageState.entries) {
            assertFalse("Succeeded -> $to", DownloadStateMachine.canMove(DownloadPageState.Succeeded, to))
        }
    }

    @Test
    fun `a cancelled page cannot be revived`() {
        for (to in DownloadPageState.entries) {
            assertFalse("Canceled -> $to", DownloadStateMachine.canMove(DownloadPageState.Canceled, to))
        }
    }

    @Test
    fun `move applies a legal transition and refuses an illegal one`() {
        assertEquals(
            DownloadPageState.Running,
            DownloadStateMachine.move(DownloadPageState.Queued, DownloadPageState.Running),
        )
        assertThrows<IllegalStateException> {
            DownloadStateMachine.move(DownloadPageState.Succeeded, DownloadPageState.Queued)
        }
    }

    @Test
    fun `an unknown stored name is not guessed at`() {
        assertEquals(DownloadPageState.Queued, DownloadStateMachine.stateNamed("Queued"))
        assertNull(DownloadStateMachine.stateNamed("Uploading"))
    }

    @Test
    fun `a chapter is completed only when every page is`() {
        assertEquals(DownloadChapterState.Completed, state(listOf(Succeeded, Succeeded)))
        assertEquals(DownloadChapterState.Running, state(listOf(Succeeded, Running)))
        assertEquals(DownloadChapterState.Queued, state(listOf(Succeeded, Queued)))
        assertEquals(DownloadChapterState.Paused, state(listOf(Succeeded, Paused)))
        assertEquals(DownloadChapterState.Partial, state(listOf(Succeeded, Failed)))
        assertEquals(DownloadChapterState.Canceled, state(listOf(Succeeded, Canceled)))
        assertEquals(DownloadChapterState.Canceled, state(listOf(Canceled, Canceled)))
        assertEquals(DownloadChapterState.Queued, state(emptyList()))
    }

    @Test
    fun `a failure outranks a page that is still waiting for its turn`() {
        assertEquals(DownloadChapterState.Partial, state(listOf(Failed, Queued)))
        assertEquals(DownloadChapterState.Running, state(listOf(Failed, Running)))
        assertEquals(DownloadChapterState.Paused, state(listOf(Failed, Paused)))
    }

    @Test
    fun `retries stop after three attempts`() {
        assertTrue(DownloadStateMachine.shouldRetry(0))
        assertTrue(DownloadStateMachine.shouldRetry(2))
        assertFalse(DownloadStateMachine.shouldRetry(DownloadStateMachine.MAX_ATTEMPTS))
    }

    private fun state(pages: List<DownloadPageState>): DownloadChapterState =
        DownloadStateMachine.chapterStateOf(pages)

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            thrown = failure
        }
        assertTrue("expected ${T::class.java.simpleName}, got $thrown", thrown is T)
    }

    private companion object {
        val Succeeded = DownloadPageState.Succeeded
        val Running = DownloadPageState.Running
        val Queued = DownloadPageState.Queued
        val Paused = DownloadPageState.Paused
        val Failed = DownloadPageState.Failed
        val Canceled = DownloadPageState.Canceled
    }
}

package dev.veneranative.data.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {

    private fun pages(count: Int, sourceId: String = "s", taskId: String = "task-1"): List<QueuePage> =
        (0 until count).map { QueuePage(taskId = taskId, pageIndex = it, sourceId = sourceId, imageRef = "p$it") }

    @Test
    fun `at most four pages are in flight at once`() = runTest {
        val queue = DownloadQueue(
            limits = DownloadLimits(global = 4, perSource = 4),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        var running = 0
        var peak = 0

        queue.run(pages(12)) {
            peak = maxOf(peak, ++running)
            delay(10)
            running--
            null
        }

        advanceUntilIdle()
        assertEquals(4, peak)
        assertEquals(0, running)
    }

    @Test
    fun `at most two pages of one source are in flight at once`() = runTest {
        val queue = DownloadQueue(
            limits = DownloadLimits(global = 8, perSource = 2),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val running = mutableMapOf<String, Int>()
        val peak = mutableMapOf<String, Int>()

        queue.run(pages(6, sourceId = "a") + pages(6, sourceId = "b")) { page ->
            val now = (running[page.sourceId] ?: 0) + 1
            running[page.sourceId] = now
            peak[page.sourceId] = maxOf(peak[page.sourceId] ?: 0, now)
            delay(10)
            running[page.sourceId] = (running[page.sourceId] ?: 1) - 1
            null
        }

        advanceUntilIdle()
        assertEquals(2, peak["a"])
        assertEquals(2, peak["b"])
    }

    @Test
    fun `a slow source does not keep the other source's pages waiting`() = runTest {
        val queue = DownloadQueue(
            limits = DownloadLimits(global = 4, perSource = 2),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val started = mutableListOf<String>()

        queue.run(
            listOf(
                QueuePage("t", 0, "slow", "a"),
                QueuePage("t", 1, "slow", "b"),
                QueuePage("t", 2, "slow", "c"),
                QueuePage("t", 0, "fast", "d"),
            ),
        ) { page ->
            started += page.sourceId
            if (page.sourceId == "slow") delay(1_000) else delay(1)
            null
        }

        advanceUntilIdle()
        // Three slow pages but only two permits for them, so the fast page starts before the third.
        assertEquals(listOf("slow", "slow", "fast", "slow"), started)
    }

    @Test
    fun `results come back in the order the pages were given, however they finished`() = runTest {
        val queue = DownloadQueue(dispatcher = StandardTestDispatcher(testScheduler))

        val results = queue.run(pages(4)) { page ->
            delay((4 - page.pageIndex).toLong())
            null
        }

        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3), results.map { it.page.pageIndex })
    }

    @Test
    fun `a page that throws is a failed page and the others still land`() = runTest {
        val queue = DownloadQueue(dispatcher = StandardTestDispatcher(testScheduler))

        val results = queue.run(pages(3)) { page ->
            if (page.pageIndex == 1) throw IllegalStateException("the disk went away")
            null
        }

        advanceUntilIdle()
        assertNull(results[0].error)
        assertEquals(DownloadError.Corrupt("the page could not be written"), results[1].error)
        assertNull(results[2].error)
    }

    @Test
    fun `a page that reports an error is reported back, not swallowed`() = runTest {
        val queue = DownloadQueue(dispatcher = StandardTestDispatcher(testScheduler))

        val results = queue.run(pages(2)) { page ->
            if (page.pageIndex == 0) DownloadError.Network else null
        }

        advanceUntilIdle()
        assertEquals(DownloadError.Network, results[0].error)
        assertNull(results[1].error)
    }
}

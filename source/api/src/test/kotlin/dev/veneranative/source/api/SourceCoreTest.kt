package dev.veneranative.source.api

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract semantics of [SourceCore], pinned against [FakeSourceCore].
 *
 * These are the rules any implementation — including the engine-backed one — has to keep, and two
 * of them are product rules rather than technical ones: a missing capability must degrade instead
 * of reporting the source as broken, and a source failure must arrive as a value, not an exception.
 */
class SourceCoreTest {

    private val sourceId = SourceId("fake-source")
    private val core = FakeSourceCore(sourceId = sourceId)

    @Test
    fun `declared capabilities are reported for the loaded source`() = runTest {
        val outcome = core.capabilities(sourceId)

        val capabilities = (outcome as SourceOutcome.Success).value
        assertTrue(capabilities.supports(SourceCapability.SEARCH))
        assertFalse(capabilities.supports(SourceCapability.EXPLORE))
    }

    @Test
    fun `capabilities of an unloaded source are a failure`() = runTest {
        val outcome = core.capabilities(SourceId("not-installed"))

        assertTrue(outcome is SourceOutcome.Failure)
        assertTrue((outcome as SourceOutcome.Failure).error is SourceRuntimeError.SourceNotLoaded)
    }

    @Test
    fun `an unsupported explore is not a source failure`() = runTest {
        val outcome = core.explore(ExploreRequest(sourceId = sourceId, pageKey = "Popular"))

        val error = (outcome as SourceOutcome.Failure).error
        assertEquals(SourceCapability.EXPLORE, (error as SourceRuntimeError.UnsupportedCapability).capability)
        assertFalse(error.retryable)
    }

    @Test
    fun `an unsupported pages capability is not a source failure`() = runTest {
        val chapterKey = ChapterKey(
            comicKey = comicKey(1),
            remoteId = RemoteChapterId("chapter-1"),
        )

        val outcome = core.pages(chapterKey)

        val error = (outcome as SourceOutcome.Failure).error
        assertTrue(error is SourceRuntimeError.UnsupportedCapability)
        assertFalse(error.retryable)
    }

    @Test
    fun `search walks every page exactly once and stops at the last one`() = runTest {
        val collected = mutableListOf<String>()
        var cursor: PageCursor? = null

        do {
            val outcome = core.search(SearchRequest(sourceId = sourceId, keyword = "anything", cursor = cursor))
            val page = (outcome as SourceOutcome.Success).value
            collected += page.items.map { it.title }
            cursor = page.next
        } while (cursor != null)

        assertEquals(listOf("Comic 1", "Comic 2", "Comic 3", "Comic 4", "Comic 5"), collected)
        // Five comics at two per page is three pages, so no page may be fetched twice.
        assertEquals(3, core.searchCalls)
    }

    @Test
    fun `a source failure is returned as data instead of thrown`() = runTest {
        val outcome = core.search(SearchRequest(sourceId = sourceId, keyword = "boom"))

        val error = (outcome as SourceOutcome.Failure).error
        assertTrue(error is SourceRuntimeError.Timeout)
        assertTrue(error.retryable)
    }

    @Test
    fun `detail carries the chapters in the order the source declared them`() = runTest {
        val outcome = core.detail(comicKey(1))

        val detail = (outcome as SourceOutcome.Success).value
        assertEquals(listOf("Chapter 2", "Chapter 1"), detail.chapters.map { it.title })
        assertEquals(listOf(0, 1), detail.chapters.map { it.index })
        assertEquals(listOf("chapter-2", "chapter-1"), detail.chapters.map { it.key.remoteId.value })
    }

    @Test
    fun `chapters belong to the requested comic`() = runTest {
        val outcome = core.chapters(comicKey(3))

        val chapters = (outcome as SourceOutcome.Success).value
        assertTrue(chapters.all { it.key.comicKey == comicKey(3) })
    }

    @Test
    fun `detail of an unknown comic is a failure rather than empty detail`() = runTest {
        val outcome = core.detail(comicKey(99))

        assertTrue((outcome as SourceOutcome.Failure).error is SourceRuntimeError.InvalidCall)
    }

    private fun comicKey(number: Int): ComicKey =
        ComicKey(sourceId = sourceId, remoteId = RemoteComicId("comic-$number"))
}

package dev.veneranative.feature.reader

import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val chapterKey = ChapterKey(
        comicKey = ComicKey(SourceId("test-source"), RemoteComicId("comic-1")),
        remoteId = RemoteChapterId("chapter-1"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `reports ready state with the first page after loading`() = runTest(dispatcher) {
        val viewModel = ReaderViewModel(chapterKey, FakePageProvider(pageCount = 5))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ReaderStatus.Ready, state.status)
        assertEquals(5, state.pageCount)
        assertEquals(1, state.currentPageNumber)
        assertEquals(ReadingDirection.Vertical, state.direction)
    }

    @Test
    fun `visible page updates the page number`() = runTest(dispatcher) {
        val viewModel = ReaderViewModel(chapterKey, FakePageProvider(pageCount = 5))
        advanceUntilIdle()

        viewModel.onAction(ReaderAction.PageShown(3))

        assertEquals(4, viewModel.state.value.currentPageNumber)
    }

    @Test
    fun `page index is clamped to the loaded range`() = runTest(dispatcher) {
        val viewModel = ReaderViewModel(chapterKey, FakePageProvider(pageCount = 5))
        advanceUntilIdle()

        viewModel.onAction(ReaderAction.PageShown(99))

        assertEquals(5, viewModel.state.value.currentPageNumber)
    }

    @Test
    fun `prefetches the neighbourhood of the visible page`() = runTest(dispatcher) {
        val provider = FakePageProvider(pageCount = 5)
        val viewModel = ReaderViewModel(chapterKey, provider)
        advanceUntilIdle()

        viewModel.onAction(ReaderAction.PageShown(1))
        advanceUntilIdle()

        assertEquals(listOf(0, 1, 2), provider.prefetchedPages)
    }

    @Test
    fun `never prefetches the same page twice`() = runTest(dispatcher) {
        val provider = FakePageProvider(pageCount = 5)
        val viewModel = ReaderViewModel(chapterKey, provider)
        advanceUntilIdle()

        viewModel.onAction(ReaderAction.PageShown(2))
        viewModel.onAction(ReaderAction.PageShown(2))
        advanceUntilIdle()

        val prefetched = provider.prefetchedPages
        assertEquals(prefetched.distinct(), prefetched)
    }

    @Test
    fun `provider failure becomes a domain failure state`() = runTest(dispatcher) {
        val viewModel = ReaderViewModel(chapterKey, FailingPageProvider())
        advanceUntilIdle()

        assertEquals(ReaderStatus.Failed, viewModel.state.value.status)
    }

    @Test
    fun `retry after a failure loads the chapter`() = runTest(dispatcher) {
        val viewModel = ReaderViewModel(chapterKey, FakePageProvider(pageCount = 2))
        advanceUntilIdle()

        viewModel.onAction(ReaderAction.Retry)
        advanceUntilIdle()

        assertEquals(ReaderStatus.Ready, viewModel.state.value.status)
        assertEquals(2, viewModel.state.value.pageCount)
    }

    @Test
    fun `direction change is reflected in state`() = runTest(dispatcher) {
        val viewModel = ReaderViewModel(chapterKey, FakePageProvider(pageCount = 3))
        advanceUntilIdle()

        viewModel.onAction(ReaderAction.ChangeDirection(ReadingDirection.RightToLeft))

        assertEquals(ReadingDirection.RightToLeft, viewModel.state.value.direction)
    }

    @Test
    fun `only the neighbourhood resolves and a failed page keeps its index until retry`() = runTest(dispatcher) {
        val resolved = mutableListOf<Int>()
        var fail = true
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterKey) = ChapterContent("Chapter", List(20) {
                dev.veneranative.core.model.ComicPage(it, "https://image/$it", 1080, 1440,
                    sizeState = dev.veneranative.core.model.PageSizeState.Pending)
            })
            override suspend fun resolve(page: dev.veneranative.core.model.ComicPage): dev.veneranative.core.model.ComicPage {
                resolved += page.index
                if (page.index == 1 && fail) throw java.io.IOException("offline")
                return page.copy(sizeState = dev.veneranative.core.model.PageSizeState.Ready)
            }
        }
        val vm = ReaderViewModel(chapterKey, provider)
        advanceUntilIdle()
        assertEquals(listOf(0, 1), resolved)
        assertEquals(20, vm.state.value.pageCount)
        assertEquals(dev.veneranative.core.model.PageSizeState.Failed, vm.state.value.pages[1].sizeState)
        fail = false
        vm.onAction(ReaderAction.RetryPage(1))
        advanceUntilIdle()
        assertEquals(dev.veneranative.core.model.PageSizeState.Ready, vm.state.value.pages[1].sizeState)
        assertEquals((0 until 20).toList(), vm.state.value.pages.map { it.index })
    }

    private class FailingPageProvider : PageProvider {
        override suspend fun loadChapter(chapter: ChapterKey): ChapterContent =
            throw IllegalStateException("provider unavailable")
    }
}

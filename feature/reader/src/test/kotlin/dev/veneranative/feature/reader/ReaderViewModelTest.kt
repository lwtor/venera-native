package dev.veneranative.feature.reader

import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SplitPreferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `waits for async resume then records the initial visible page`() = runTest(dispatcher) {
        val saved = kotlinx.coroutines.CompletableDeferred<Int>()
        val recorded = mutableListOf<Int>()
        val progress = object : dev.veneranative.core.model.ReaderProgress {
            override suspend fun resumePage() = saved.await()
            override fun record(content: ChapterContent, pageIndex: Int) { recorded += pageIndex }
        }
        val model = ReaderViewModel(chapterKey, FakePageProvider(8), progress = progress)
        runCurrent()
        assertEquals(ReaderStatus.Loading, model.state.value.status)
        saved.complete(4)
        advanceUntilIdle()
        assertEquals(4, model.state.value.currentPageIndex)
        assertEquals(listOf(4), recorded)
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
    fun `exposes the next chapter supplied with chapter content`() = runTest(dispatcher) {
        val next = Chapter(chapterKey.copy(remoteId = RemoteChapterId("chapter-2")), "Chapter 2", 1)
        val prefetchedChapters = mutableListOf<ChapterRef>()
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef) =
                ChapterContent("Chapter 1", FakePageProvider(1).loadChapter(chapter).pages, nextChapter = next)
            override suspend fun prefetchChapter(chapter: ChapterRef) { prefetchedChapters += chapter }
        }
        val viewModel = ReaderViewModel(chapterKey, provider)
        advanceUntilIdle()

        assertEquals(next, viewModel.state.value.nextChapter)
        assertEquals(listOf(ChapterRef.Remote(next.key)), prefetchedChapters)
    }

    @Test
    fun `next chapter pages append to the current reader canvas`() = runTest(dispatcher) {
        val next = Chapter(chapterKey.copy(remoteId = RemoteChapterId("chapter-2")), "Chapter 2", 1)
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef): ChapterContent =
                if (chapter == ChapterRef.Remote(chapterKey)) {
                    ChapterContent("Chapter 1", listOf(ComicPage(0, "page-1", 100, 100)), nextChapter = next)
                } else {
                    ChapterContent("Chapter 2", listOf(ComicPage(0, "page-2", 100, 100)))
                }
        }
        val viewModel = ReaderViewModel(chapterKey, provider)
        advanceUntilIdle()

        viewModel.onAction(ReaderAction.LoadNextChapter)
        advanceUntilIdle()

        assertEquals(listOf("page-1", "page-2"), viewModel.state.value.pages.map { it.imageRef })
        assertEquals(listOf(0, 1), viewModel.state.value.pages.map { it.index })
        assertEquals(0, viewModel.state.value.currentPageIndex)
        assertEquals("Chapter 1", viewModel.state.value.chapterTitle)
        assertEquals(next, viewModel.state.value.nextChapter)
    }

    @Test
    fun `quick chapter navigation loads the previous chapter and keeps pages on one canvas`() = runTest(dispatcher) {
        val previous = Chapter(chapterKey.copy(remoteId = RemoteChapterId("chapter-0")), "Chapter 0", 0)
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef): ChapterContent = when (chapter) {
                ChapterRef.Remote(chapterKey) -> ChapterContent(
                    "Chapter 1", listOf(ComicPage(0, "current-0", 100, 100), ComicPage(1, "current-1", 100, 100)),
                    previousChapter = previous,
                )
                ChapterRef.Remote(previous.key) -> ChapterContent(
                    "Chapter 0", listOf(ComicPage(0, "previous-0", 100, 100), ComicPage(1, "previous-1", 100, 100),
                        ComicPage(2, "previous-2", 100, 100)),
                )
                else -> error("Unexpected chapter $chapter")
            }
        }
        val viewModel = ReaderViewModel(chapterKey, provider)
        advanceUntilIdle()

        viewModel.onAction(ReaderAction.LoadPreviousChapter)
        advanceUntilIdle()

        assertEquals(listOf("previous-0", "previous-1", "previous-2", "current-0", "current-1"),
            viewModel.state.value.pages.map { it.imageRef })
        assertEquals(2, viewModel.state.value.currentPageIndex)
        assertEquals("Chapter 0", viewModel.state.value.chapterTitle)
        assertEquals(3, viewModel.state.value.currentChapterPageCount)
        assertEquals(3, viewModel.state.value.nextChapterStartIndex)

        viewModel.onAction(ReaderAction.SeekPage(0))
        assertEquals(0, viewModel.state.value.currentPageIndex)
        viewModel.onAction(ReaderAction.NavigateNextChapter)
        assertEquals(3, viewModel.state.value.currentPageIndex)
        assertEquals("Chapter 1", viewModel.state.value.chapterTitle)
    }

    @Test
    fun `automatic previous chapter loading preserves the visible page`() = runTest(dispatcher) {
        val previous = Chapter(chapterKey.copy(remoteId = RemoteChapterId("chapter-0")), "Chapter 0", 0)
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef): ChapterContent =
                if (chapter == ChapterRef.Remote(chapterKey)) {
                    ChapterContent(
                        "Chapter 1",
                        listOf(ComicPage(0, "current-0", 100, 100), ComicPage(1, "current-1", 100, 100)),
                        previousChapter = previous,
                    )
                } else {
                    ChapterContent("Chapter 0", listOf(ComicPage(0, "previous-0", 100, 100)))
                }
        }
        val viewModel = ReaderViewModel(chapterKey, provider)
        advanceUntilIdle()
        viewModel.onAction(ReaderAction.PageShown(0))

        viewModel.onAction(ReaderAction.AutoLoadPreviousChapter)
        advanceUntilIdle()

        assertEquals(listOf("previous-0", "current-0", "current-1"), viewModel.state.value.pages.map { it.imageRef })
        assertEquals(1, viewModel.state.value.currentPageIndex)
        assertEquals(1, viewModel.state.value.currentChapterStartIndex)
        assertEquals("Chapter 1", viewModel.state.value.chapterTitle)
    }

    @Test
    fun `reaching the end without a following chapter exposes an end page state`() = runTest(dispatcher) {
        val viewModel = ReaderViewModel(chapterKey, FakePageProvider(pageCount = 2))
        advanceUntilIdle()

        viewModel.onAction(ReaderAction.LoadNextChapter)

        assertEquals(true, viewModel.state.value.endOfChaptersReached)
        viewModel.onAction(ReaderAction.PageShown(0))
        assertEquals(false, viewModel.state.value.endOfChaptersReached)
    }

    @Test
    fun `failed next chapter remains on the same canvas and retry appends it`() = runTest(dispatcher) {
        val next = Chapter(chapterKey.copy(remoteId = RemoteChapterId("chapter-2")), "Chapter 2", 1)
        var nextAttempts = 0
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef): ChapterContent =
                if (chapter == ChapterRef.Remote(chapterKey)) {
                    ChapterContent("Chapter 1", listOf(ComicPage(0, "page-1", 100, 100)), nextChapter = next)
                } else {
                    nextAttempts++
                    if (nextAttempts == 1) throw java.io.IOException("temporary source failure")
                    ChapterContent("Chapter 2", listOf(ComicPage(0, "page-2", 100, 100)))
                }
        }
        val viewModel = ReaderViewModel(chapterKey, provider)
        advanceUntilIdle()

        viewModel.onAction(ReaderAction.LoadNextChapter)
        advanceUntilIdle()
        assertEquals(true, viewModel.state.value.nextChapterLoadFailed)
        assertEquals(listOf("page-1"), viewModel.state.value.pages.map { it.imageRef })

        viewModel.onAction(ReaderAction.RetryNextChapter)
        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.nextChapterLoadFailed)
        assertEquals(listOf("page-1", "page-2"), viewModel.state.value.pages.map { it.imageRef })
        assertEquals("Chapter 1", viewModel.state.value.chapterTitle)
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
    fun `prefetches three pages on each side of the visible page`() = runTest(dispatcher) {
        val provider = FakePageProvider(pageCount = 7)
        val viewModel = ReaderViewModel(chapterKey, provider, startPageIndex = 3)
        advanceUntilIdle()

        assertEquals((0..6).toList(), provider.prefetchedPages)
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
    fun `revisiting a local page resolves it again after it leaves the visible window`() = runTest(dispatcher) {
        val resolved = mutableListOf<Int>()
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef) = ChapterContent("Local", List(3) {
                dev.veneranative.core.model.ComicPage(it, "local://$it", 1080, 1440,
                    sizeState = dev.veneranative.core.model.PageSizeState.Pending)
            })
            override suspend fun resolve(page: dev.veneranative.core.model.ComicPage): dev.veneranative.core.model.ComicPage {
                resolved += page.index
                return page.copy(sizeState = dev.veneranative.core.model.PageSizeState.Ready)
            }
        }
        val chapter = ChapterRef.Local(dev.veneranative.core.model.LocalComicId("comic"),
            dev.veneranative.core.model.LocalChapterId("chapter"))
        val viewModel = ReaderViewModel(chapter, provider, prefetchRadius = 0)
        advanceUntilIdle()
        viewModel.onAction(ReaderAction.PageShown(1))
        advanceUntilIdle()
        viewModel.onAction(ReaderAction.PageShown(0))
        advanceUntilIdle()

        assertEquals(listOf(0, 1, 0), resolved)
        assertEquals(dev.veneranative.core.model.PageSizeState.Ready, viewModel.state.value.pages[0].sizeState)
    }

    @Test
    fun `provider failure becomes a domain failure state`() = runTest(dispatcher) {
        val viewModel = ReaderViewModel(chapterKey, FailingPageProvider())
        advanceUntilIdle()

        assertEquals(ReaderStatus.Failed, viewModel.state.value.status)
    }

    @Test fun `hung chapter exits loading and manual retry recovers`() = runTest(dispatcher) {
        var hung = true
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef): ChapterContent {
                if (hung) kotlinx.coroutines.awaitCancellation()
                return ChapterContent("Fixture", listOf(ComicPage(0, "fixture://page", 100, 100)))
            }
        }
        val model = ReaderViewModel(ChapterRef.Remote(chapterKey), provider, chapterLoadTimeoutMillis = 100)
        advanceUntilIdle()
        assertEquals(ReaderStatus.Failed, model.state.value.status)
        hung = false
        model.onAction(ReaderAction.Retry)
        advanceUntilIdle()
        assertEquals(ReaderStatus.Ready, model.state.value.status)
    }

    @Test fun `hung next chapter fails visibly while current pages remain readable`() = runTest(dispatcher) {
        val next = Chapter(chapterKey.copy(remoteId = RemoteChapterId("next")), "Next", 1)
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef): ChapterContent {
                if ((chapter as ChapterRef.Remote).key == next.key) kotlinx.coroutines.awaitCancellation()
                return ChapterContent("Fixture", listOf(ComicPage(0, "fixture://page", 100, 100)), nextChapter = next)
            }
        }
        val model = ReaderViewModel(ChapterRef.Remote(chapterKey), provider, chapterLoadTimeoutMillis = 100)
        advanceUntilIdle()
        model.onAction(ReaderAction.LoadNextChapter)
        advanceUntilIdle()
        assertEquals(ReaderStatus.Ready, model.state.value.status)
        assertEquals(false, model.state.value.isLoadingNextChapter)
        assertEquals(true, model.state.value.nextChapterLoadFailed)
        assertEquals(1, model.state.value.pages.size)
    }

    @Test fun `hung page resolution stops after three attempts and exposes page retry`() = runTest(dispatcher) {
        var attempts = 0
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef) = ChapterContent("Fixture", listOf(
                ComicPage(0, "fixture://page", 100, 100, sizeState = dev.veneranative.core.model.PageSizeState.Pending),
            ))
            override suspend fun resolve(page: ComicPage): ComicPage {
                attempts++
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val model = ReaderViewModel(ChapterRef.Remote(chapterKey), provider, pageLoadTimeoutMillis = 100)
        advanceUntilIdle()
        assertEquals(3, attempts)
        assertEquals(dev.veneranative.core.model.PageSizeState.Failed, model.state.value.pages.single().sizeState)
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
            override suspend fun loadChapter(chapter: ChapterRef) = ChapterContent("Chapter", List(20) {
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

        // The neighbourhood is three pages on each side (S3-00K1), so pages 0..3 resolve from page 0.
        // Page 1 fails, so it is attempted three times in total before it is given up on.
        assertEquals(listOf(0, 1, 2, 3, 1, 1), resolved)
        assertEquals(20, vm.state.value.pageCount)
        assertEquals(dev.veneranative.core.model.PageSizeState.Failed, vm.state.value.pages[1].sizeState)
        fail = false
        vm.onAction(ReaderAction.RetryPage(1))
        advanceUntilIdle()
        assertEquals(dev.veneranative.core.model.PageSizeState.Ready, vm.state.value.pages[1].sizeState)
        assertEquals((0 until 20).toList(), vm.state.value.pages.map { it.index })
    }

    private class FailingPageProvider : PageProvider {
        override suspend fun loadChapter(chapter: ChapterRef): ChapterContent =
            throw IllegalStateException("provider unavailable")
    }

    private class AlwaysSplitPreference : SplitPreferenceRepository {
        override suspend fun isSplitEnabled(chapterRef: ChapterRef) = true
        override suspend fun setSplitEnabled(chapterRef: ChapterRef, enabled: Boolean) = Unit
        override suspend fun isPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int) = false
        override suspend fun setPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int, excluded: Boolean) = Unit
    }

    @Test
    fun `split enabled splits wide pages and records progress by source index`() = runTest(dispatcher) {
        val recorded = mutableListOf<Int>()
        val progress = object : dev.veneranative.core.model.ReaderProgress {
            override suspend fun resumePage() = 0
            override fun record(content: ChapterContent, pageIndex: Int) { recorded += pageIndex }
        }
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef) = ChapterContent(
                "Split",
                listOf(
                    ComicPage(0, "wide", 2000, 1400),
                    ComicPage(1, "portrait", 1000, 1400),
                ),
            )
        }
        val viewModel = ReaderViewModel(
            ChapterRef.Remote(chapterKey), provider,
            progress = progress, splitPreference = AlwaysSplitPreference(),
        )
        advanceUntilIdle()

        val pages = viewModel.state.value.pages
        assertEquals(3, pages.size)
        assertNotNull(pages[0].crop)
        assertNotNull(pages[1].crop)
        assertNull(pages[2].crop)
        assertEquals(listOf(0, 0, 1), pages.map { it.sourceIndex })
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        // Progress records the source index, not the doubled display index.
        assertEquals(listOf(0), recorded)
    }

    @Test
    fun `excluding a page can be undone and splits it again`() = runTest(dispatcher) {
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef) = ChapterContent(
                "Split", listOf(ComicPage(0, "wide", 2000, 1400), ComicPage(1, "wide-2", 2000, 1400)),
            )
        }
        val viewModel = ReaderViewModel(
            ChapterRef.Remote(chapterKey), provider, splitPreference = perComicSplit(),
        )
        advanceUntilIdle()
        assertEquals(ReaderStatus.Ready, viewModel.state.value.status)
        assertEquals(4, viewModel.state.value.pages.size)

        viewModel.onAction(ReaderAction.TogglePageSplit(0))
        advanceUntilIdle()

        // Excluding re-splits in place: the page collapses to one display page, still readable.
        assertEquals(ReaderStatus.Ready, viewModel.state.value.status)
        assertEquals(3, viewModel.state.value.pages.size)
        assertNull(viewModel.state.value.pages[0].crop)

        viewModel.onAction(ReaderAction.TogglePageSplit(0))
        advanceUntilIdle()

        // The exclusion must round-trip: restoring the page splits it again instead of being a no-op.
        assertEquals(ReaderStatus.Ready, viewModel.state.value.status)
        assertEquals(4, viewModel.state.value.pages.size)
        assertNotNull(viewModel.state.value.pages[0].crop)
    }

    @Test
    fun `excluded wide page stays unsplit`() = runTest(dispatcher) {
        val preference = object : SplitPreferenceRepository {
            override suspend fun isSplitEnabled(chapterRef: ChapterRef) = true
            override suspend fun setSplitEnabled(chapterRef: ChapterRef, enabled: Boolean) = Unit
            override suspend fun isPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int) = sourcePageIndex == 0
            override suspend fun setPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int, excluded: Boolean) = Unit
        }
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef) = ChapterContent(
                "Split", listOf(ComicPage(0, "wide", 2000, 1400), ComicPage(1, "wide-2", 2000, 1400)),
            )
        }
        val viewModel = ReaderViewModel(ChapterRef.Remote(chapterKey), provider, splitPreference = preference)
        advanceUntilIdle()

        val pages = viewModel.state.value.pages
        // Page 0 is excluded so stays one page; page 1 splits into two.
        assertEquals(3, pages.size)
        assertNull(pages[0].crop)
        assertNotNull(pages[1].crop)
        assertNotNull(pages[2].crop)
    }

    /** Splits the current test chapter only, so a prepended chapter keeps splitting off. */
    private fun currentChapterOnlySplit() = object : SplitPreferenceRepository {
        override suspend fun isSplitEnabled(chapterRef: ChapterRef) = chapterRef == ChapterRef.Remote(chapterKey)
        override suspend fun setSplitEnabled(chapterRef: ChapterRef, enabled: Boolean) = Unit
        override suspend fun isPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int) = false
        override suspend fun setPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int, excluded: Boolean) = Unit
    }

    @Test
    fun `preloading the previous chapter keeps the current chapter split`() = runTest(dispatcher) {
        val previous = Chapter(chapterKey.copy(remoteId = RemoteChapterId("chapter-0")), "Chapter 0", 0)
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef): ChapterContent = when (chapter) {
                ChapterRef.Remote(chapterKey) -> ChapterContent(
                    "Chapter 1",
                    listOf(ComicPage(0, "current-wide", 2000, 1400), ComicPage(1, "current-wide-2", 2000, 1400)),
                    previousChapter = previous,
                )
                else -> ChapterContent("Chapter 0", listOf(ComicPage(0, "previous-portrait", 1000, 1400)))
            }
        }
        val viewModel = ReaderViewModel(
            ChapterRef.Remote(chapterKey), provider, splitPreference = currentChapterOnlySplit(),
        )
        advanceUntilIdle()
        assertEquals(4, viewModel.state.value.pages.size)

        viewModel.onAction(ReaderAction.PageShown(0))
        viewModel.onAction(ReaderAction.AutoLoadPreviousChapter)
        advanceUntilIdle()

        val state = viewModel.state.value
        // Scrolling to the top prepends the un-split previous chapter, but the four halves of the
        // current chapter must keep their crop so the split survives the prepend.
        assertEquals(
            listOf("previous-portrait", "current-wide", "current-wide", "current-wide-2", "current-wide-2"),
            state.pages.map { it.imageRef },
        )
        assertEquals(listOf(null, 0, 900, 0, 900), state.pages.map { it.crop?.leftPx })
        assertEquals(4, state.currentChapterPageCount)
        assertTrue(state.splitEnabled)
    }

    @Test
    fun `crossing chapters replays that chapter's split setting`() = runTest(dispatcher) {
        val previous = Chapter(chapterKey.copy(remoteId = RemoteChapterId("chapter-0")), "Chapter 0", 0)
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef): ChapterContent = when (chapter) {
                ChapterRef.Remote(chapterKey) -> ChapterContent(
                    "Chapter 1",
                    listOf(ComicPage(0, "current-wide", 2000, 1400)),
                    previousChapter = previous,
                )
                else -> ChapterContent("Chapter 0", listOf(ComicPage(0, "previous-portrait", 1000, 1400)))
            }
        }
        val viewModel = ReaderViewModel(
            ChapterRef.Remote(chapterKey), provider, splitPreference = currentChapterOnlySplit(),
        )
        advanceUntilIdle()
        assertTrue(viewModel.state.value.splitEnabled)

        viewModel.onAction(ReaderAction.LoadPreviousChapter)
        advanceUntilIdle()
        // The previous chapter has splitting off, so the toggle has to follow the visible chapter.
        assertFalse(viewModel.state.value.splitEnabled)

        viewModel.onAction(ReaderAction.PageShown(1))
        assertTrue(viewModel.state.value.splitEnabled)
    }

    /** Split preference whose per-comic overlap and order are fixed by the test. */
    private fun perComicSplit(
        overlap: Float = dev.veneranative.core.model.PageSplit.DEFAULT_OVERLAP_FRACTION,
        rightToLeft: Boolean = false,
    ) = object : SplitPreferenceRepository {
        override suspend fun isSplitEnabled(chapterRef: ChapterRef) = true
        override suspend fun setSplitEnabled(chapterRef: ChapterRef, enabled: Boolean) = Unit
        override suspend fun isPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int) = false
        override suspend fun setPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int, excluded: Boolean) = Unit
        override suspend fun overlapFraction(comicRef: dev.veneranative.core.model.ComicRef) = overlap
        override suspend fun setOverlapFraction(comicRef: dev.veneranative.core.model.ComicRef, fraction: Float) = Unit
        override suspend fun isRightToLeft(comicRef: dev.veneranative.core.model.ComicRef) = rightToLeft
        override suspend fun setRightToLeft(comicRef: dev.veneranative.core.model.ComicRef, rightToLeft: Boolean) = Unit
    }

    @Test
    fun `right to left comic shows the right half first`() = runTest(dispatcher) {
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef) = ChapterContent(
                "Split", listOf(ComicPage(0, "wide", 2000, 1400)),
            )
        }
        val viewModel = ReaderViewModel(
            ChapterRef.Remote(chapterKey), provider, splitPreference = perComicSplit(rightToLeft = true),
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        // The right half starts at 45% of the width, so it is the page shown first.
        assertEquals(listOf(900, 0), state.pages.map { it.crop?.leftPx })
        assertTrue(state.splitRightToLeft)
    }

    @Test
    fun `saved overlap widens the shared gutter`() = runTest(dispatcher) {
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef) = ChapterContent(
                "Split", listOf(ComicPage(0, "wide", 2000, 1400)),
            )
        }
        val viewModel = ReaderViewModel(
            ChapterRef.Remote(chapterKey), provider, splitPreference = perComicSplit(overlap = 0.1f),
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(1200, state.pages[0].crop?.widthPx) // left half reaches 60%
        assertEquals(800, state.pages[1].crop?.leftPx) // right half starts at 40%
        assertEquals(0.1f, state.splitOverlapFraction, 1e-6f)
    }

    @Test
    fun `changing the gutter overlap resplits in place without reloading the chapter`() = runTest(dispatcher) {
        var loads = 0
        val savedOverlaps = mutableListOf<Float>()
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef): ChapterContent {
                loads++
                return ChapterContent("Split", listOf(ComicPage(0, "wide", 2000, 1400)))
            }
        }
        val preference = object : SplitPreferenceRepository {
            override suspend fun isSplitEnabled(chapterRef: ChapterRef) = true
            override suspend fun setSplitEnabled(chapterRef: ChapterRef, enabled: Boolean) = Unit
            override suspend fun isPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int) = false
            override suspend fun setPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int, excluded: Boolean) = Unit
            override suspend fun setOverlapFraction(
                comicRef: dev.veneranative.core.model.ComicRef,
                fraction: Float,
            ) {
                savedOverlaps += fraction
            }
        }
        val viewModel = ReaderViewModel(ChapterRef.Remote(chapterKey), provider, splitPreference = preference)
        advanceUntilIdle()
        assertEquals(1, loads)
        assertEquals(listOf(0, 900), viewModel.state.value.pages.map { it.crop?.leftPx })

        viewModel.onAction(ReaderAction.SetSplitOverlap(0.1f))
        runCurrent()

        // Re-splitting is pure geometry over dimensions that are already known, so the reader must
        // never fall back to the loading screen and must never reload the chapter.
        assertEquals(ReaderStatus.Ready, viewModel.state.value.status)
        val pages = viewModel.state.value.pages
        assertEquals(1200, pages[0].crop?.widthPx)
        assertEquals(800, pages[1].crop?.leftPx)

        advanceUntilIdle()
        assertEquals(1, loads)
        assertEquals(listOf(0.1f), savedOverlaps)
    }

    @Test
    fun `changing the half order keeps the visible page without reloading`() = runTest(dispatcher) {
        var loads = 0
        val provider = object : PageProvider {
            override suspend fun loadChapter(chapter: ChapterRef): ChapterContent {
                loads++
                return ChapterContent(
                    "Split",
                    listOf(ComicPage(0, "wide", 2000, 1400), ComicPage(1, "wide-2", 2000, 1400)),
                )
            }
        }
        val viewModel = ReaderViewModel(
            ChapterRef.Remote(chapterKey), provider, splitPreference = perComicSplit(),
        )
        advanceUntilIdle()
        assertEquals(listOf(0, 900, 0, 900), viewModel.state.value.pages.map { it.crop?.leftPx })

        viewModel.onAction(ReaderAction.PageShown(2))
        viewModel.onAction(ReaderAction.SetSplitOrder(true))
        runCurrent()

        val state = viewModel.state.value
        assertEquals(ReaderStatus.Ready, state.status)
        assertEquals(listOf(900, 0, 900, 0), state.pages.map { it.crop?.leftPx })
        // The anchor follows the source page, so the reader stays on the same content.
        assertEquals(2, state.currentPageIndex)
        assertEquals(1, state.pages[state.currentPageIndex].sourceIndex)
        advanceUntilIdle()
        assertEquals(1, loads)
    }
}

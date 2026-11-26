package dev.veneranative.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageSizeState
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.image.decode.PageImageDecoder
import dev.veneranative.core.image.tiling.DecodeStrategy
import dev.veneranative.core.image.tiling.DecodedPageImage
import dev.veneranative.core.image.tiling.PageDecodeRequest
import dev.veneranative.core.image.tiling.PageRegion
import dev.veneranative.core.image.tiling.PageTile
import dev.veneranative.core.image.tiling.PageViewport
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pages = List(3) { index ->
        ComicPage(
            index = index,
            imageRef = "test://$index",
            widthPx = 1080,
            heightPx = 1440,
        )
    }

    @Test fun titleBarDetailsButtonInvokesNavigationCallback() {
        val opens = AtomicInteger()
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(chapterTitle = "当前话", pages = pages,
                    currentChapterPageCount = pages.size, status = ReaderStatus.Ready),
                onAction = {}, onBack = {}, onOpenComicDetails = { opens.incrementAndGet() },
            )
        }
        composeRule.onNodeWithTag("reader-canvas").performTouchInput { click(center) }
        composeRule.onNodeWithTag("reader-open-details").assertIsDisplayed().performClick()
        assertEquals(1, opens.get())
    }

    @Test
    fun centerLongPressShowsFullscreenReaderControls() {
        val next = ChapterRef.Remote(
            ChapterKey(ComicKey(SourceId("test-source"), RemoteComicId("comic")), RemoteChapterId("next")),
        )
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "当前话",
                    pages = pages,
                    currentChapterPageCount = pages.size,
                    nextChapter = next,
                    status = ReaderStatus.Ready,
                ),
                onAction = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("reader-canvas").performTouchInput {
            down(center)
            advanceEventTime(1_100)
            up()
        }
        composeRule.onNodeWithContentDescription("上一话").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("下一话").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 3 页").assertIsDisplayed()
    }

    @Test
    fun shortTapDoesNotShowControlsAndTapHidesThemImmediately() {
        val next = ChapterRef.Remote(
            ChapterKey(ComicKey(SourceId("test-source"), RemoteComicId("comic")), RemoteChapterId("next")),
        )
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "当前话",
                    pages = pages,
                    currentChapterPageCount = pages.size,
                    nextChapter = next,
                    status = ReaderStatus.Ready,
                ),
                onAction = {},
                onBack = {},
            )
        }
        val canvas = composeRule.onNodeWithTag("reader-canvas")
        canvas.performTouchInput { click(center) }
        composeRule.onAllNodesWithTag("reader-tool-overlay").assertCountEquals(0)

        canvas.performTouchInput {
            down(center)
            advanceEventTime(1_100)
            up()
        }
        composeRule.onNodeWithContentDescription("上一话").assertIsDisplayed()

        canvas.performTouchInput { click(Offset(8f, 8f)) }
        composeRule.onAllNodesWithTag("reader-tool-overlay").assertCountEquals(0)
    }

    @Test
    fun doubleTapTogglesReaderZoom() {
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "当前话",
                    pages = pages,
                    currentChapterPageCount = pages.size,
                    status = ReaderStatus.Ready,
                ),
                onAction = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("reader-canvas").performTouchInput {
            down(center)
            up()
            advanceEventTime(80)
            down(center)
            up()
        }
        composeRule.onNodeWithTag("reader-zoomed").assertIsDisplayed()

        composeRule.onNodeWithTag("reader-canvas").performTouchInput {
            down(center)
            up()
            advanceEventTime(80)
            down(center)
            up()
        }
        composeRule.onNodeWithTag("reader-not-zoomed").assertIsDisplayed()
    }

    @Test
    fun secondTapDownCancelsSingleTapEvenWhenReleasePassesDoubleTapTimeout() {
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "当前话",
                    pages = pages,
                    currentChapterPageCount = pages.size,
                    status = ReaderStatus.Ready,
                ),
                onAction = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("reader-canvas").performTouchInput {
            down(center)
            up()
            advanceEventTime(360)
            down(center)
            advanceEventTime(120)
            up()
        }

        composeRule.onNodeWithTag("reader-zoomed").assertIsDisplayed()
        composeRule.onAllNodesWithTag("reader-tool-overlay").assertCountEquals(0)
    }

    @Test
    fun pinchZoomIsAppliedByReaderCanvasWithoutScrolling() {
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "当前话",
                    pages = pages,
                    currentChapterPageCount = pages.size,
                    status = ReaderStatus.Ready,
                ),
                onAction = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("reader-canvas").performTouchInput {
            val first = center - Offset(40f, 0f)
            val second = center + Offset(40f, 0f)
            down(pointerId = 1, position = first)
            down(pointerId = 2, position = second)
            moveBy(pointerId = 1, delta = Offset(-24f, 0f))
            moveBy(pointerId = 2, delta = Offset(24f, 0f))
            up(pointerId = 2)
            up(pointerId = 1)
        }
        composeRule.onNodeWithTag("reader-zoomed").assertIsDisplayed()
    }

    @Test
    fun zoomDoesNotStartAnotherTileDecode() {
        val decodeCount = AtomicInteger()
        val decoder = object : PageImageDecoder {
            override val strategy = DecodeStrategy.Region
            override fun plan(page: ComicPage, viewport: PageViewport, zoom: Float, continuous: Boolean) = listOf(
                PageTile(PageRegion(0, 0, page.widthPx, page.heightPx), viewport.widthPx, viewport.heightPx),
            )
            override suspend fun decode(request: PageDecodeRequest): DecodedPageImage {
                decodeCount.incrementAndGet()
                val bitmap = Bitmap.createBitmap(
                    request.targetWidthPx.coerceAtLeast(1),
                    request.targetHeightPx.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                return DecodedPageImage(bitmap, bitmap.byteCount, 1, 1, strategy)
            }
        }

        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "当前话",
                    pages = listOf(pages.first()),
                    currentChapterPageCount = 1,
                    status = ReaderStatus.Ready,
                ),
                onAction = {},
                onBack = {},
                decoderFactory = { decoder },
            )
        }
        composeRule.waitUntil(5_000) { decodeCount.get() > 0 }
        val initialDecodeCount = decodeCount.get()

        composeRule.onNodeWithTag("reader-canvas").performTouchInput {
            down(center)
            up()
            advanceEventTime(80)
            down(center)
            up()
        }

        composeRule.onNodeWithTag("reader-zoomed").assertIsDisplayed()
        composeRule.waitForIdle()
        assertEquals(initialDecodeCount, decodeCount.get())
    }

    @Test
    fun showsChapterTitleAndPageCounter() {
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "Chapter 7",
                    pages = pages,
                    currentChapterPageCount = pages.size,
                    status = ReaderStatus.Ready,
                ),
                onAction = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("reader-canvas").performTouchInput { click(center) }
        composeRule.onNodeWithText("Chapter 7").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()
    }

    @Test
    fun statusScrimShowsPinnedTimeChapterAndPage() {
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "第 7 话",
                    pages = pages,
                    currentChapterPageCount = pages.size,
                    status = ReaderStatus.Ready,
                ),
                onAction = {}, onBack = {}, clock = { "21:47" },
            )
        }

        composeRule.onNodeWithTag("reader-status-scrim").assertIsDisplayed()
        composeRule.onNodeWithText("21:47").assertIsDisplayed()
        composeRule.onNodeWithText("第 7 话").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()
    }

    @Test
    fun controlPanelRisesAboveStatusScrimWithoutCoveringIt() {
        val next = ChapterRef.Remote(
            ChapterKey(ComicKey(SourceId("test-source"), RemoteComicId("comic")), RemoteChapterId("next")),
        )
        val state = mutableStateOf(
            ReaderUiState(
                chapterTitle = "当前话",
                pages = pages,
                currentChapterPageCount = pages.size,
                nextChapter = next,
                status = ReaderStatus.Ready,
            ),
        )
        composeRule.setContent {
            ReaderScreen(
                state = state.value,
                onAction = {}, onBack = {}, clock = { "21:47" },
            )
        }
        composeRule.onNodeWithTag("reader-status-scrim").assertIsDisplayed()

        composeRule.onNodeWithTag("reader-canvas").performTouchInput {
            down(center)
            advanceEventTime(1_100)
            up()
        }
        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithTag("reader-tool-overlay").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("reader-tool-overlay").assertIsDisplayed()
        // The pill must remain on screen, and the sheet must end above its top edge.
        composeRule.onNodeWithTag("reader-status-scrim").assertIsDisplayed()
        val scrim = composeRule.onNodeWithTag("reader-status-scrim").getBoundsInRoot()
        val sheet = composeRule.onNodeWithTag("reader-tool-overlay").getBoundsInRoot()
        check(sheet.bottom <= scrim.top + 1.dp) {
            "control sheet bottom ${sheet.bottom} must stay above scrim top ${scrim.top}"
        }
    }

    @Test
    fun statusScrimStaysVisibleOnEndOfChaptersNotice() {
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "当前话",
                    pages = pages,
                    currentChapterPageCount = pages.size,
                    endOfChaptersReached = true,
                    status = ReaderStatus.Ready,
                ),
                onAction = {}, onBack = {}, clock = { "21:47" },
            )
        }

        composeRule.onNodeWithTag("reader-end-of-chapters").assertIsDisplayed()
        composeRule.onNodeWithTag("reader-status-scrim").assertIsDisplayed()
        val scrim = composeRule.onNodeWithTag("reader-status-scrim").getBoundsInRoot()
        val notice = composeRule.onNodeWithTag("reader-end-of-chapters").getBoundsInRoot()
        check(notice.bottom <= scrim.top + 1.dp) {
            "notice bottom ${notice.bottom} must stay above scrim top ${scrim.top}"
        }
    }

    @Test
    fun longPressNearEdgeOpensPanelWithNinetyPercentHotspot() {
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "当前话",
                    pages = pages,
                    currentChapterPageCount = pages.size,
                    status = ReaderStatus.Ready,
                ),
                onAction = {}, onBack = {},
            )
        }

        // 8% of the canvas: outside the old 60x40 hotspot but inside the new 90% one.
        composeRule.onNodeWithTag("reader-canvas").performTouchInput {
            down(Offset(width * 0.08f, height * 0.5f))
            advanceEventTime(1_100)
            up()
        }
        composeRule.onNodeWithContentDescription("上一话").assertIsDisplayed()
    }

    @Test
    fun offersRetryWhenLoadingFails() {
        var retried = false
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(status = ReaderStatus.Failed),
                onAction = { if (it == ReaderAction.Retry) retried = true },
                onBack = {},
            )
        }

        composeRule.onNodeWithText("重试").performClick()

        assertTrue(retried)
    }

    @Test
    fun failedPageInLaterChapterShowsChapterLocalNumber() {
        val failedPages = List(4) { index ->
            ComicPage(
                index = index,
                imageRef = "test://$index",
                widthPx = 1080,
                heightPx = 1440,
                sizeState = if (index == 2) PageSizeState.Failed else PageSizeState.Ready,
            )
        }
        val retried = mutableListOf<Int>()
        val decoder = object : PageImageDecoder {
            override val strategy = DecodeStrategy.Region
            override fun plan(page: ComicPage, viewport: PageViewport, zoom: Float, continuous: Boolean) =
                listOf(PageTile(PageRegion(0, 0, page.widthPx, page.heightPx), viewport.widthPx, viewport.heightPx))
            override suspend fun decode(request: PageDecodeRequest): DecodedPageImage = awaitCancellation()
            override suspend fun predecode(request: PageDecodeRequest) = Unit
        }
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "Chapter 2",
                    pages = failedPages,
                    currentPageIndex = 2,
                    currentChapterStartIndex = 2,
                    currentChapterPageCount = 2,
                    chapterPageRanges = listOf(0..1, 2..3),
                    status = ReaderStatus.Ready,
                ),
                onAction = { action -> if (action is ReaderAction.RetryPage) retried += action.index },
                onBack = {},
                decoderFactory = { decoder },
            )
        }

        composeRule.onNodeWithText("重试第 1 页").performClick()

        assertEquals(listOf(2), retried)
    }

    @Test
    fun emitsDirectionChange() {
        var requested: ReadingDirection? = null
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "Chapter 1",
                    pages = pages,
                    status = ReaderStatus.Ready,
                ),
                onAction = { if (it is ReaderAction.ChangeDirection) requested = it.direction },
                onBack = {},
            )
        }

        composeRule.onNodeWithText("从右到左").performClick()

        assertEquals(ReadingDirection.RightToLeft, requested)
    }

    @Test
    fun predecodesThreePagesAheadAndBehindWithoutDecodingTheVisiblePage() {
        val preloadPages = List(7) { index ->
            ComicPage(
                index = index,
                imageRef = "test://$index",
                widthPx = 1080,
                heightPx = 1440,
                sizeState = PageSizeState.Ready,
            )
        }
        val warmedPaths = mutableListOf<String>()
        val plannedModes = mutableListOf<Boolean>()
        val decoder = object : PageImageDecoder {
            override val strategy = DecodeStrategy.Region
            override fun plan(page: ComicPage, viewport: PageViewport, zoom: Float, continuous: Boolean): List<PageTile> {
                plannedModes += continuous
                return listOf(PageTile(PageRegion(0, 0, page.widthPx, page.heightPx), viewport.widthPx, viewport.heightPx))
            }
            override suspend fun decode(request: PageDecodeRequest): DecodedPageImage = awaitCancellation()
            override suspend fun predecode(request: PageDecodeRequest) {
                warmedPaths += request.path
            }
        }
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    pages = preloadPages,
                    currentPageIndex = 3,
                    direction = ReadingDirection.LeftToRight,
                    status = ReaderStatus.Ready,
                ),
                onAction = {},
                onBack = {},
                decoderFactory = { decoder },
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { warmedPaths.size == 6 }
        assertTrue((0..2).all { "test://$it" in warmedPaths })
        assertTrue((4..6).all { "test://$it" in warmedPaths })
        assertTrue("the visible page is decoded by its normal tile", "test://3" !in warmedPaths)
        assertTrue("paged predecode must use the same tiling mode as visible pages", plannedModes.isNotEmpty() && plannedModes.all { !it })
    }

    @Test
    fun requestsNextChapterAppendWhenTheReaderReachesTheEnd() {
        val nextChapter = ChapterRef.Remote(
            ChapterKey(ComicKey(SourceId("test-source"), RemoteComicId("comic")), RemoteChapterId("chapter-2")),
        )
        var openedNext = false
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "Chapter 1",
                    pages = listOf(ComicPage(0, "test://last", 1080, 300, sizeState = PageSizeState.Ready)),
                    direction = ReadingDirection.LeftToRight,
                    nextChapter = nextChapter,
                    status = ReaderStatus.Ready,
                ),
                onAction = { if (it == ReaderAction.LoadNextChapter) openedNext = true },
                onBack = {},
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { openedNext }
    }

    @Test
    fun verticalEndDetectionUpdatesWhenLastPageFinishesLoading() {
        val nextChapter = ChapterRef.Remote(
            ChapterKey(ComicKey(SourceId("test-source"), RemoteComicId("comic")), RemoteChapterId("chapter-2")),
        )
        val state = mutableStateOf(
            ReaderUiState(
                chapterTitle = "Chapter 1",
                pages = listOf(
                    ComicPage(0, "test://last", 1080, 12_000, sizeState = PageSizeState.Pending),
                ),
                nextChapter = nextChapter,
                status = ReaderStatus.Ready,
            ),
        )
        var openedNext = false
        composeRule.setContent {
            ReaderScreen(
                state = state.value,
                onAction = { if (it == ReaderAction.LoadNextChapter) openedNext = true },
                onBack = {},
            )
        }

        val reader = composeRule.onNode(hasScrollAction())
        repeat(20) { reader.performTouchInput { swipeUp() } }
        composeRule.waitForIdle()
        assertTrue("a pending page cannot end the chapter", !openedNext)

        state.value = state.value.copy(
            pages = state.value.pages.map { it.copy(sizeState = PageSizeState.Ready) },
        )

        composeRule.waitUntil(timeoutMillis = 5_000) { openedNext }
    }

    @Test
    fun showsLoaderWhileLoading() {
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(status = ReaderStatus.Loading),
                onAction = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("0 / 0").assertIsDisplayed()
    }

    @Test
    fun decodedPageImageKeepsItsAspectRatioInsideTheTileBounds() {
        val page = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.Red.toArgb())
        }.asImageBitmap()
        composeRule.setContent {
            Box(Modifier.size(100.dp).background(Color.Blue).testTag("page-tile")) {
                FittedPageImage(page, contentDescription = "test page", modifier = Modifier.fillMaxSize())
            }
        }

        val pixels = composeRule.onNodeWithTag("page-tile").captureToImage().toPixelMap()
        assertEquals("letterboxed area should remain visible", Color.Blue, pixels[pixels.width / 10, pixels.height / 2])
        assertEquals("page pixels should stay centered and visible", Color.Red, pixels[pixels.width / 2, pixels.height / 2])
    }

    @Test
    fun restoringLastShortPageDoesNotReportPreviousPage() {
        val shownPages = mutableListOf<Int>()
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "Last page",
                    pages = pages,
                    currentPageIndex = 2,
                    status = ReaderStatus.Ready,
                ),
                onAction = { if (it is ReaderAction.PageShown) shownPages += it.index },
                onBack = {},
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("3 / 3").assertIsDisplayed()
        assertTrue("initial layout must not overwrite the restored page", shownPages.none { it < 2 })
    }

    @Test
    fun longChapterCanAdvanceReturnAndScrollTallPages() {
        val state = mutableStateOf(ReaderUiState(
            chapterTitle = "Synthetic long chapter",
            pages = List(3) { index ->
                ComicPage(index = index, imageRef = "test://long/$index", widthPx = 1080, heightPx = 12_000)
            },
            direction = ReadingDirection.LeftToRight,
            status = ReaderStatus.Ready,
        ))
        composeRule.setContent {
            ReaderScreen(
                state = state.value,
                onAction = { action ->
                    state.value = when (action) {
                        is ReaderAction.PageShown -> state.value.copy(currentPageIndex = action.index)
                        is ReaderAction.ChangeDirection -> state.value.copy(direction = action.direction)
                        else -> state.value
                    }
                },
                onBack = {},
            )
        }

        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()
        val pagedVerticalList = composeRule.onNode(hasScrollAction())
        pagedVerticalList.assertIsDisplayed()
        pagedVerticalList.performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertEquals("a tall page scrolls before the user flips pages", 0, state.value.currentPageIndex)
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(1, state.value.currentPageIndex)
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(2, state.value.currentPageIndex)

        repeat(2) {
            composeRule.onRoot().performTouchInput { swipeRight() }
            composeRule.waitForIdle()
        }
        assertEquals(0, state.value.currentPageIndex)

        composeRule.onNodeWithText("竖向").performClick()
        composeRule.waitForIdle()
        assertEquals(ReadingDirection.Vertical, state.value.direction)
        val verticalList = composeRule.onNode(hasScrollAction())
        repeat(20) {
            if (state.value.currentPageIndex == 0) {
                verticalList.performTouchInput { swipeUp() }
                composeRule.waitForIdle()
            }
        }
        assertTrue("a tall local page should respond to vertical scroll gestures", state.value.currentPageIndex > 0)
    }

    @Test
    fun endOfChaptersNoticeStaysOnScreenInsteadOfLivingAtTheCanvasEnd() {
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "当前话",
                    pages = pages,
                    currentChapterPageCount = pages.size,
                    endOfChaptersReached = true,
                    status = ReaderStatus.Ready,
                ),
                onAction = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("reader-end-of-chapters").assertIsDisplayed()
        composeRule.onNodeWithText("已到最后一话").assertIsDisplayed()
    }
}

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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.image.decode.PageImageDecoder
import dev.veneranative.core.image.tiling.DecodeStrategy
import dev.veneranative.core.image.tiling.DecodedPageImage
import dev.veneranative.core.image.tiling.PageDecodeRequest
import dev.veneranative.core.image.tiling.PageRegion
import dev.veneranative.core.image.tiling.PageTile
import dev.veneranative.core.image.tiling.PageViewport
import kotlinx.coroutines.awaitCancellation
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

    @Test
    fun showsChapterTitleAndPageCounter() {
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    chapterTitle = "Chapter 7",
                    pages = pages,
                    status = ReaderStatus.Ready,
                ),
                onAction = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Chapter 7").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()
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
    fun predecodesOnlyTheImmediatePagesAroundTheCurrentPage() {
        val warmedPaths = mutableListOf<String>()
        val decoder = object : PageImageDecoder {
            override val strategy = DecodeStrategy.Region
            override fun plan(page: ComicPage, viewport: PageViewport, zoom: Float, continuous: Boolean) = listOf(
                PageTile(PageRegion(0, 0, page.widthPx, page.heightPx), viewport.widthPx, viewport.heightPx),
            )
            override suspend fun decode(request: PageDecodeRequest): DecodedPageImage = awaitCancellation()
            override suspend fun predecode(request: PageDecodeRequest) {
                warmedPaths += request.path
            }
        }
        composeRule.setContent {
            ReaderScreen(
                state = ReaderUiState(
                    pages = pages,
                    currentPageIndex = 1,
                    direction = ReadingDirection.LeftToRight,
                    status = ReaderStatus.Ready,
                ),
                onAction = {},
                onBack = {},
                decoderFactory = { decoder },
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { warmedPaths.size == 2 }
        assertTrue(warmedPaths.contains("test://0"))
        assertTrue(warmedPaths.contains("test://2"))
        assertTrue("the visible page is decoded by its normal tile", "test://1" !in warmedPaths)
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
}

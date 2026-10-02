package dev.veneranative.feature.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import dev.veneranative.core.model.ComicPage
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

        composeRule.onNodeWithText("Retry").performClick()

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

        composeRule.onNodeWithText("RTL").performClick()

        assertEquals(ReadingDirection.RightToLeft, requested)
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

        composeRule.onNodeWithText("Vertical").performClick()
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

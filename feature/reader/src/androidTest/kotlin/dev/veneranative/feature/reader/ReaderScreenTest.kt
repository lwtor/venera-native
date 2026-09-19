package dev.veneranative.feature.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}

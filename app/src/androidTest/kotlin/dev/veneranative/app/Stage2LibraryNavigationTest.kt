package dev.veneranative.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/** Exercises the real app graph and route transitions on an Android device. */
class Stage2LibraryNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeOpensDownloadsAndLocalLibraryTabs() {
        composeRule.onNodeWithText("Library").performClick()
        composeRule.onNodeWithText("Downloads").performClick()
        composeRule.onNodeWithText("No downloads yet.").assertIsDisplayed()

        composeRule.onNodeWithText("Local").performClick()
        composeRule.onNodeWithText("Import directory").assertIsDisplayed()
        composeRule.onNodeWithText("Import CBZ / ZIP / 7z").assertIsDisplayed()
        composeRule.onNodeWithText("No local directories imported.").assertIsDisplayed()
    }
}

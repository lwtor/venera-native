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
        composeRule.onNodeWithText("书架").performClick()
        composeRule.onNodeWithText("下载").performClick()
        composeRule.onNodeWithText("暂无下载任务。").assertIsDisplayed()

        composeRule.onNodeWithText("本地").performClick()
        composeRule.onNodeWithText("导入文件夹").assertIsDisplayed()
        composeRule.onNodeWithText("导入 CBZ / ZIP / 7z").assertIsDisplayed()
        composeRule.onNodeWithText("尚未导入本地漫画。").assertIsDisplayed()
    }

    @Test
    fun systemBackFromLibraryReturnsHome() {
        composeRule.onNodeWithText("书架").performClick()
        composeRule.onNodeWithText("下载").assertIsDisplayed()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithText("你的下一段漫画旅程").assertIsDisplayed()
    }
}

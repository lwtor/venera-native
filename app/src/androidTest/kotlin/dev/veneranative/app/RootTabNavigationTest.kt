package dev.veneranative.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class RootTabNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun rootTabsSwitchContentAndKeepTheBottomBarVisible() {
        composeRule.onNodeWithTag("root_tab_home").assertIsSelected()


        composeRule.onNodeWithTag("root_tab_library").performClick()
        composeRule.onNodeWithTag("root_tab_library").assertIsSelected()
        composeRule.onNodeWithText("收藏").assertIsDisplayed()

        composeRule.onNodeWithTag("root_tab_profile").performClick()
        composeRule.onNodeWithTag("root_tab_profile").assertIsSelected()
        composeRule.onNodeWithTag("profile_about").performClick()
        composeRule.onNodeWithText("构建版本").assertIsDisplayed()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("root_tab_profile").assertIsSelected()

        composeRule.onNodeWithTag("root_tab_home").performClick()
        composeRule.onNodeWithTag("root_tab_home").assertIsSelected()
        composeRule.onNodeWithText("搜索漫画、作者或来源").assertIsDisplayed()
    }
}

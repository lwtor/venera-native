package dev.veneranative.feature.sources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Screen states as the user sees them.
 *
 * "No sources" and "could not read sources" must stay distinguishable, so both are asserted.
 */
class SourcesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `loading shows progress`() {
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(status = SourcesStatus.Loading),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(LOADING_TAG).assertIsDisplayed()
    }

    @Test
    fun `an empty list explains how to install a source`() {
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(status = SourcesStatus.Ready),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("No sources installed yet.").assertIsDisplayed()
    }

    @Test
    fun `a failed load shows the message and a retry that fires the action`() {
        val actions = mutableListOf<SourcesAction>()
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(
                        status = SourcesStatus.Failed,
                        message = "The source list could not be read.",
                    ),
                    onAction = { actions += it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("The source list could not be read.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertEquals(listOf(SourcesAction.Retry), actions)
    }

    @Test
    fun `an installed source shows its name and version`() {
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(status = SourcesStatus.Ready, sources = listOf(SOURCE)),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Demo Source").assertIsDisplayed()
        composeRule.onNodeWithText("Version 1.2.3").assertIsDisplayed()
    }

    @Test
    fun `install stays disabled until a location is typed`() {
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(status = SourcesStatus.Ready),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Install").assertIsNotEnabled()
    }

    private companion object {
        val SOURCE = InstalledSource(
            sourceId = SourceId("demo"),
            name = "Demo Source",
            version = "1.2.3",
            enabled = true,
            origin = "/tmp/demo.js",
        )
    }
}

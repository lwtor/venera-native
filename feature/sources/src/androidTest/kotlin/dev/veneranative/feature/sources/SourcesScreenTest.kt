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
import dev.veneranative.data.source.SourceCatalogEntry
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

        composeRule.onNodeWithText("尚未安装漫画源。请从上方选择，或安装本地脚本。").assertIsDisplayed()
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

        composeRule.onNodeWithText("无法读取已安装的漫画源。").assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()

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
        composeRule.onNodeWithText("版本 1.2.3").assertIsDisplayed()
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

        composeRule.onNodeWithText("已安装").assertIsNotEnabled()
    }

    @Test
    fun `catalog entry is visible and install dispatches its resolved script URL`() {
        val actions = mutableListOf<SourcesAction>()
        val entry = SourceCatalogEntry("MangaDex", "manga_dex", "1.2.0", "Public source", "https://cdn.example/manga_dex.js")
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(
                        status = SourcesStatus.Ready,
                        catalogStatus = CatalogStatus.Ready,
                        catalogEntries = listOf(entry),
                    ),
                    onAction = { actions += it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("MangaDex").assertIsDisplayed()
        composeRule.onNodeWithTag(CATALOG_INSTALL_TAG).performClick()

        assertEquals(listOf(SourcesAction.InstallCatalogEntry(entry)), actions)
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

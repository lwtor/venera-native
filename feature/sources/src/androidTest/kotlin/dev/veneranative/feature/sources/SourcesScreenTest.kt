package dev.veneranative.feature.sources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.SourceCatalogEntry
import dev.veneranative.data.source.defaultVeneraSourceCatalogUrl
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

        composeRule.onNodeWithTag(INSTALLED_SECTION_TAG).performClick()
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

        composeRule.onNodeWithTag(INSTALLED_SECTION_TAG).performClick()
        composeRule.onNodeWithText("还没有安装漫画源。打开“发现来源”选择安装，或点击右上角 + 导入文件。").assertIsDisplayed()
        composeRule.onNodeWithText("发现漫画源").performClick()
        composeRule.onNodeWithText("在线目录").assertIsDisplayed()
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

        composeRule.onNodeWithTag(INSTALLED_SECTION_TAG).performClick()
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

        composeRule.onNodeWithTag(INSTALLED_SECTION_TAG).performClick()
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

        composeRule.onNodeWithTag(AVAILABLE_SECTION_TAG).performClick()
        composeRule.onNodeWithText("手动安装").performClick()
        composeRule.onNodeWithTag(MANUAL_INSTALL_TAG).assertIsNotEnabled()
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

        composeRule.onNodeWithTag(AVAILABLE_SECTION_TAG).performClick()
        composeRule.onNodeWithText("MangaDex").assertIsDisplayed()
        composeRule.onNodeWithTag(CATALOG_INSTALL_TAG).performClick()

        assertEquals(listOf(SourcesAction.InstallCatalogEntry(entry)), actions)
    }

    @Test
    fun `source from another catalog shows a switch action without an update label`() {
        val actions = mutableListOf<SourcesAction>()
        val currentCatalog = "https://catalog.example/active/index.json"
        val otherCatalog = "https://catalog.example/other/index.json"
        val entry = SourceCatalogEntry("MangaDex", "manga_dex", "3.0.0", null, "https://cdn.example/manga.js", currentCatalog)
        val installed = SOURCE.copy(sourceId = SourceId("manga_dex"), name = "MangaDex", catalogLocation = otherCatalog)
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(
                        status = SourcesStatus.Ready,
                        sources = listOf(installed),
                        catalogStatus = CatalogStatus.Ready,
                        catalogLocation = currentCatalog,
                        catalogEntries = listOf(entry),
                    ),
                    onAction = { actions += it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AVAILABLE_SECTION_TAG).performClick()
        composeRule.onNodeWithText("已在其他目录安装").assertIsDisplayed()
        composeRule.onNodeWithText("切换").performClick()
        composeRule.onAllNodesWithText("更新至 3.0.0").assertCountEquals(0)
        assertEquals(listOf(SourcesAction.InstallCatalogEntry(entry)), actions)
    }

    @Test
    fun `catalog history is hidden until switching and current directory is shown alone`() {
        val actions = mutableListOf<SourcesAction>()
        val currentCatalog = defaultVeneraSourceCatalogUrl()
        val otherCatalog = "https://catalog.example/other/index.json"
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(
                        status = SourcesStatus.Ready,
                        catalogStatus = CatalogStatus.Ready,
                        catalogLocation = currentCatalog,
                        catalogHistory = listOf(currentCatalog, otherCatalog),
                    ),
                    onAction = { actions += it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AVAILABLE_SECTION_TAG).performClick()
        composeRule.onNodeWithText("当前漫画源目录").assertIsDisplayed()
        composeRule.onAllNodesWithText("历史目录").assertCountEquals(0)
        composeRule.onNodeWithText("切换").performClick()
        composeRule.onNodeWithText(otherCatalog).performClick()

        assertEquals(listOf(SourcesAction.SelectCatalogLocation(otherCatalog)), actions)
    }

    @Test
    fun `adding a catalog opens a dialog and immediately loads the entered address`() {
        val actions = mutableListOf<SourcesAction>()
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(status = SourcesStatus.Ready, catalogStatus = CatalogStatus.Ready),
                    onAction = { actions += it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AVAILABLE_SECTION_TAG).performClick()
        composeRule.onNodeWithText("添加").performClick()
        composeRule.onNodeWithText("目录地址").performTextInput("https://catalog.example/new/index.json")
        composeRule.onNodeWithText("添加并使用").performClick()

        assertEquals(
            listOf(
                SourcesAction.CatalogLocationChanged("https://catalog.example/new/index.json"),
                SourcesAction.RefreshCatalog,
            ),
            actions,
        )
    }

    @Test
    fun `installed sources live in their own full-height section`() {
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(status = SourcesStatus.Ready, sources = listOf(SOURCE)),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(INSTALLED_SECTION_TAG).performClick()
        composeRule.onNodeWithText("Demo Source").assertIsDisplayed()
    }

    @Test
    fun `update all banner appears with two outdated sources and dispatches the batch action`() {
        val actions = mutableListOf<SourcesAction>()
        val catalog = defaultVeneraSourceCatalogUrl()
        val a = SOURCE.copy(sourceId = SourceId("a"), name = "A", catalogLocation = catalog)
        val b = SOURCE.copy(sourceId = SourceId("b"), name = "B", catalogLocation = catalog)
        val entries = listOf(
            SourceCatalogEntry("A", "a", "2.0.0", null, "https://cdn.example/a.js", catalog),
            SourceCatalogEntry("B", "b", "2.0.0", null, "https://cdn.example/b.js", catalog),
        )
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(
                        status = SourcesStatus.Ready,
                        sources = listOf(a, b),
                        catalogStatus = CatalogStatus.Ready,
                        catalogLocation = catalog,
                        catalogEntries = entries,
                    ),
                    onAction = { actions += it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(INSTALLED_SECTION_TAG).performClick()
        composeRule.onNodeWithTag(UPDATE_ALL_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("全部更新").performClick()

        assertEquals(listOf(SourcesAction.UpdateAllOutdated), actions)
    }

    @Test
    fun `the switch toggles in place while tapping the card opens its settings page`() {
        val actions = mutableListOf<SourcesAction>()
        val opened = mutableListOf<SourceId>()
        composeRule.setContent {
            VeneraNativeTheme {
                SourcesScreen(
                    state = SourcesUiState(
                        status = SourcesStatus.Ready,
                        sources = listOf(SOURCE),
                        configuration = null,
                    ),
                    onAction = { actions += it },
                    onBack = {},
                    onOpenSettings = { opened += it },
                )
            }
        }

        composeRule.onNodeWithTag(INSTALLED_SECTION_TAG).performClick()
        // The switch only enables/disables; it never navigates to the settings page.
        composeRule.onNodeWithTag(SOURCE_ENABLED_TAG).performClick()
        assertEquals(listOf(SourcesAction.SetEnabled(SOURCE.sourceId, false)), actions)
        assertEquals(emptyList<SourceId>(), opened)

        // The name block is the settings entry now, and it routes instead of expanding in place.
        composeRule.onNodeWithText("Demo Source").performClick()
        assertEquals(listOf(SOURCE.sourceId), opened)
        assertEquals(listOf(SourcesAction.SetEnabled(SOURCE.sourceId, false)), actions)
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

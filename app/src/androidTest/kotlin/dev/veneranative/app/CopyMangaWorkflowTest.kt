package dev.veneranative.app

import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.paging.PagingSource
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veneranative.data.source.InstallOutcome
import dev.veneranative.data.source.SourceCatalogResult
import dev.veneranative.data.source.defaultVeneraSourceCatalogUrl
import dev.veneranative.source.api.SearchRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real-network acceptance for catalog → CopyManga install → typed search → parsed comic results. */
@RunWith(AndroidJUnit4::class)
class CopyMangaWorkflowTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun installFromCatalogAndSearchRealCopyMangaResults() {
        runBlocking {
            val graph = androidx.lifecycle.ViewModelProvider(composeRule.activity)[AppGraph::class.java]
            val comics = withTimeout(180_000) {
                Log.i(TAG, "Loading upstream source index")
                val catalog = graph.sourceCatalogRepository.load(defaultVeneraSourceCatalogUrl())
                check(catalog is SourceCatalogResult.Success) { "Could not load the upstream source catalog." }
                val entry = catalog.entries.first {
                    it.name == "拷贝漫画" && it.key == "copy_manga" && it.scriptUrl.endsWith("/copy_manga.js")
                }

                Log.i(TAG, "Downloading and installing CopyManga")
                when (val install = graph.sourceRepository.install(entry.scriptUrl)) {
                    is InstallOutcome.Success -> Log.i(TAG, "Installed upstream source ${install.installed.sourceId}")
                    is InstallOutcome.Failure -> error("CopyManga install failed: ${install.error}; ${install.detail}")
                }

                Log.i(TAG, "Starting typed comic search")
                val source = graph.catalog.searchableSources().firstOrNull { it.sourceId.value == "copy_manga" }
                    ?: error("Installed CopyManga was not exposed as a searchable source.")
                val result = graph.catalog.search(
                    SearchRequest(sourceId = source.sourceId, keyword = "海贼王"),
                ).load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
                val page = result as? PagingSource.LoadResult.Page
                    ?: error("CopyManga search did not return a page: $result")
                page.data
            }

            assertTrue("Expected live CopyManga search results", comics.isNotEmpty())
            Log.i(TAG, "CopyManga workflow returned ${comics.size} real comics; first=${comics.first().title}")

            // Also drive the shipped Compose screen so the test covers the visible source picker,
            // keyword form, paging request and rendered result row, not only the data contract.
            composeRule.onNodeWithText("搜索漫画").performClick()
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodesWithText("Keyword").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Keyword").performTextInput("海贼王")
            composeRule.onNode(hasText("Search") and hasClickAction()).performClick()
            composeRule.waitUntil(60_000) {
                comics.any { title ->
                    composeRule.onAllNodesWithText(title.title).fetchSemanticsNodes().isNotEmpty()
                }
            }
            composeRule.onNodeWithText(comics.first().title).assertIsDisplayed()
            // Keep the source installed so the same real-data flow remains available in the app after
            // this acceptance run. This test does not remove user data or uninstall the app.
        }
    }

    private companion object {
        const val TAG = "CopyMangaWorkflow"
    }
}

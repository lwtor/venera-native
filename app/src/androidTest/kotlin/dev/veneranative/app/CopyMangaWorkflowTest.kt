package dev.veneranative.app

import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veneranative.data.source.InstallOutcome
import dev.veneranative.data.source.SourceCatalogResult
import dev.veneranative.data.source.defaultVeneraSourceCatalogUrl
import dev.veneranative.feature.details.DetailsScreen
import dev.veneranative.feature.details.DetailsStatus
import dev.veneranative.feature.details.DetailsUiState
import dev.veneranative.source.api.SearchRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real-network acceptance for catalog → CopyManga install → typed search → rendered details. */
@RunWith(AndroidJUnit4::class)
class CopyMangaWorkflowTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun installFromCatalogAndSearchRealCopyMangaResults() {
        runBlocking {
            val graph = androidx.lifecycle.ViewModelProvider(composeRule.activity)[AppGraph::class.java]
            val detail = withTimeout(240_000) {
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
                val comic = page.data.firstOrNull { it.title != "海贼王" }
                    ?: error("CopyManga search returned no detail result distinct from the keyword.")
                Log.i(TAG, "Loading real detail for ${comic.title}")
                when (val outcome = graph.catalog.detail(comic.key)) {
                    is dev.veneranative.source.api.SourceOutcome.Success -> outcome.value
                    is dev.veneranative.source.api.SourceOutcome.Failure -> error("CopyManga detail failed: ${outcome.error}")
                }
            }

            assertTrue("Expected source chapter metadata in the live detail", detail.chapters.isNotEmpty())
            Log.i(TAG, "CopyManga detail returned ${detail.chapters.size} chapters for ${detail.comic.title}")

            // Render the production detail screen with the actual parsed source response. This
            // keeps the real-network assertion independent of the activity's previously saved route.
            composeRule.setContent {
                DetailsScreen(
                    state = DetailsUiState(status = DetailsStatus.Ready, detail = detail, sourceName = "拷贝漫画"),
                    onAction = {},
                    onOpenChapter = {},
                    onBack = {},
                )
            }
            composeRule.onNodeWithText("作者").assertIsDisplayed()
            composeRule.onNodeWithTag("details-content").performScrollToNode(hasText("章节", substring = true))
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodesWithText("阅读").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("阅读").assertIsDisplayed()
        }
    }

    private companion object {
        const val TAG = "CopyMangaWorkflow"
    }
}

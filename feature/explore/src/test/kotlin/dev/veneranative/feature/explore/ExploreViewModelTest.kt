package dev.veneranative.feature.explore

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.ExploreKind
import dev.veneranative.core.model.ExplorePage
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceCapabilities
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.data.comic.PageKey
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val catalog = FakeComicCatalog()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the first declared page is selected so the screen has content`() = runTest(dispatcher) {
        catalog.explorable = listOf(source("a"))
        catalog.capabilityResponses = mapOf(
            SourceId("a") to capabilities(page("Popular"), page("Updates")),
        )

        val viewModel = ExploreViewModel(catalog)
        advanceUntilIdle()

        assertEquals(ExploreStatus.Ready, viewModel.state.value.status)
        assertEquals(SourceId("a"), viewModel.state.value.selectedSourceId)
        assertEquals(listOf("Popular", "Updates"), viewModel.state.value.pages.map { it.key })
        assertEquals("Popular", viewModel.state.value.selectedPageKey)
    }

    @Test
    fun `a source without explore pages says so instead of failing`() = runTest(dispatcher) {
        catalog.explorable = listOf(source("a"))
        catalog.capabilityResponses = mapOf(SourceId("a") to capabilities())

        val viewModel = ExploreViewModel(catalog)
        advanceUntilIdle()

        assertEquals(ExploreStatus.Ready, viewModel.state.value.status)
        assertTrue(!viewModel.state.value.hasPages)
        assertEquals("This source declares no explore pages.", viewModel.state.value.message)
    }

    @Test
    fun `no explorable source at all is a ready state with an empty list`() = runTest(dispatcher) {
        val viewModel = ExploreViewModel(catalog)
        advanceUntilIdle()

        assertEquals(ExploreStatus.Ready, viewModel.state.value.status)
        assertTrue(!viewModel.state.value.hasSources)
        assertNull(viewModel.state.value.selectedPageKey)
    }

    @Test
    fun `the selection describes the request the source will receive`() = runTest(dispatcher) {
        catalog.explorable = listOf(source("a"))
        catalog.capabilityResponses = mapOf(
            SourceId("a") to capabilities(page("Popular"), page("Updates")),
        )
        val viewModel = ExploreViewModel(catalog)
        advanceUntilIdle()

        assertEquals("Popular", viewModel.state.value.toRequest()?.pageKey)
        assertEquals(SourceId("a"), viewModel.state.value.toRequest()?.sourceId)

        viewModel.onAction(ExploreAction.PageSelected("Updates"))

        assertEquals("Updates", viewModel.state.value.toRequest()?.pageKey)
    }

    @Test
    fun `switching source reloads its pages and drops the old selection`() = runTest(dispatcher) {
        catalog.explorable = listOf(source("a"), source("b"))
        catalog.capabilityResponses = mapOf(
            SourceId("a") to capabilities(page("Popular")),
            SourceId("b") to capabilities(page("Recent")),
        )
        val viewModel = ExploreViewModel(catalog)
        advanceUntilIdle()

        viewModel.onAction(ExploreAction.SourceSelected(SourceId("b")))
        advanceUntilIdle()

        assertEquals(listOf("Recent"), viewModel.state.value.pages.map { it.key })
        assertEquals("Recent", viewModel.state.value.selectedPageKey)
    }

    @Test
    fun `a failed source list can be retried`() = runTest(dispatcher) {
        catalog.failList = true
        val viewModel = ExploreViewModel(catalog)
        advanceUntilIdle()
        assertEquals(ExploreStatus.Failed, viewModel.state.value.status)

        catalog.failList = false
        catalog.explorable = listOf(source("a"))
        catalog.capabilityResponses = mapOf(SourceId("a") to capabilities(page("Popular")))
        viewModel.onAction(ExploreAction.Retry)
        advanceUntilIdle()

        assertEquals(ExploreStatus.Ready, viewModel.state.value.status)
        assertEquals(listOf("Popular"), viewModel.state.value.pages.map { it.key })
    }

    private fun page(title: String) = ExplorePage.of(title, ExploreKind.MULTI_PAGE)

    private fun capabilities(vararg pages: ExplorePage) = SourceOutcome.Success(
        SourceCapabilities(
            supported = setOf(SourceCapability.EXPLORE),
            explorePages = pages.toList(),
        ),
    )

    private fun source(id: String) = InstalledSource(
        sourceId = SourceId(id),
        name = id,
        version = "1",
        enabled = true,
        origin = "/tmp/$id.js",
    )

    private class FakeComicCatalog : ComicCatalog {
        var explorable: List<InstalledSource> = emptyList()
        var capabilityResponses: Map<SourceId, SourceOutcome<SourceCapabilities>> = emptyMap()
        var failList: Boolean = false
        val exploreRequests = mutableListOf<ExploreRequest>()

        override suspend fun searchableSources(): List<InstalledSource> = emptyList()

        override suspend fun explorableSources(): List<InstalledSource> {
            if (failList) throw IllegalStateException("the source list is unreadable")
            return explorable
        }

        override suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities> =
            capabilityResponses[sourceId]
                ?: SourceOutcome.Failure(SourceRuntimeError.SourceNotLoaded(sourceId))

        override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> =
            error("not used by the explore screen")

        override suspend fun enabledSource(sourceId: SourceId): InstalledSource? = null

        override fun explore(request: ExploreRequest): PagingSource<PageKey, ExploreItem> {
            exploreRequests += request
            return EmptyPagingSource()
        }

        override fun search(request: SearchRequest): PagingSource<PageKey, Comic> =
            error("not used by the explore screen")
    }

    private class EmptyPagingSource : PagingSource<PageKey, ExploreItem>() {
        override fun getRefreshKey(state: PagingState<PageKey, ExploreItem>): PageKey = PageKey.Start

        override suspend fun load(params: LoadParams<PageKey>): LoadResult<PageKey, ExploreItem> =
            LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
    }
}

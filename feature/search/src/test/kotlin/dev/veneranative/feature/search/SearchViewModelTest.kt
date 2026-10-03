package dev.veneranative.feature.search

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.veneranative.core.model.SourcePage
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.FilterOption
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceCapabilities
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceFilter
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.data.comic.PageKey
import dev.veneranative.data.search.SearchHistoryRepository
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

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
    fun `the catalog decides which sources can search`() = runTest(dispatcher) {
        catalog.searchable = listOf(source("a"), source("b"))

        val viewModel = SearchViewModel(catalog)
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), viewModel.state.value.sources.map { it.sourceId.value })
        assertEquals(SourceId("a"), viewModel.state.value.selectedSourceId)
    }

    @Test
    fun `no searchable source is a ready state with an empty list`() = runTest(dispatcher) {
        val viewModel = SearchViewModel(catalog)
        advanceUntilIdle()

        assertEquals(SearchStatus.Ready, viewModel.state.value.status)
        assertTrue(!viewModel.state.value.hasSources)
        assertNull(viewModel.state.value.selectedSourceId)
    }

    @Test
    fun `a failed source list can be retried`() = runTest(dispatcher) {
        catalog.failList = true
        val viewModel = SearchViewModel(catalog)
        advanceUntilIdle()
        assertEquals(SearchStatus.Failed, viewModel.state.value.status)

        catalog.failList = false
        catalog.searchable = listOf(source("a"))
        viewModel.onAction(SearchAction.Retry)
        advanceUntilIdle()

        assertEquals(SearchStatus.Ready, viewModel.state.value.status)
        assertEquals(1, viewModel.state.value.sources.size)
    }

    @Test
    fun `a form without a keyword describes no request`() = runTest(dispatcher) {
        catalog.searchable = listOf(source("a"))
        val viewModel = SearchViewModel(catalog)
        advanceUntilIdle()

        viewModel.onAction(SearchAction.KeywordChanged("   "))
        assertNull(viewModel.state.value.toRequest())

        viewModel.onAction(SearchAction.KeywordChanged("frieren"))
        assertTrue(viewModel.state.value.toRequest() != null)
    }

    @Test
    fun `the form describes the request the source will receive`() = runTest(dispatcher) {
        catalog.searchable = listOf(source("a"), source("b"))
        val viewModel = SearchViewModel(catalog)
        advanceUntilIdle()

        viewModel.onAction(SearchAction.SourceSelected(SourceId("b")))
        viewModel.onAction(SearchAction.KeywordChanged("  frieren  "))
        advanceUntilIdle()

        val request = viewModel.state.value.toRequest()
        assertEquals(SourceId("b"), request?.sourceId)
        assertEquals("frieren", request?.keyword)
    }

    @Test
    fun `filters come from the selected source and follow the selection`() = runTest(dispatcher) {
        catalog.searchable = listOf(source("a"), source("b"))
        catalog.capabilityResponses = mapOf(
            SourceId("a") to capabilities(
                filters = listOf(select("sort")),
            ),
            SourceId("b") to capabilities(filters = listOf(select("year"))),
        )
        val viewModel = SearchViewModel(catalog)
        advanceUntilIdle()
        assertEquals(listOf("sort"), viewModel.state.value.filters.map { it.key })

        viewModel.onAction(SearchAction.SourceSelected(SourceId("b")))
        advanceUntilIdle()

        assertEquals(listOf("year"), viewModel.state.value.filters.map { it.key })
    }

    @Test
    fun `a source that cannot report capabilities simply offers no filters`() = runTest(dispatcher) {
        catalog.searchable = listOf(source("a"))
        catalog.capabilityResponses = mapOf(
            SourceId("a") to SourceOutcome.Failure(SourceRuntimeError.SourceNotLoaded(SourceId("a"))),
        )

        val viewModel = SearchViewModel(catalog)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.filters.isEmpty())
        assertEquals(SearchStatus.Ready, viewModel.state.value.status)
    }

    @Test
    fun `aggregate search keeps a failed source isolated from the other source`() = runTest(dispatcher) {
        catalog.searchable = listOf(source("a"), source("b"))
        catalog.searchFailures += SourceId("b")
        val viewModel = SearchViewModel(catalog)
        advanceUntilIdle()

        viewModel.onAction(SearchAction.KeywordChanged("kagurabachi"))
        viewModel.onAction(SearchAction.AggregateToggled)
        viewModel.onAction(SearchAction.Submit)
        advanceUntilIdle()

        val rows = viewModel.aggregateResults.value
        assertEquals(listOf("a", "b"), rows.map { it.sourceId.value })
        assertTrue(!rows.first().hasError)
        assertTrue(!rows.first().isLoading)
        assertTrue(rows.last().hasError)
        assertEquals(listOf("a", "b"), catalog.searchRequests.map { it.sourceId.value })
    }

    @Test
    fun `submitting opens the result page and returning preserves the search form`() = runTest(dispatcher) {
        catalog.searchable = listOf(source("a"))
        val viewModel = SearchViewModel(catalog)
        advanceUntilIdle()
        viewModel.onAction(SearchAction.KeywordChanged("frieren"))
        viewModel.onAction(SearchAction.Submit)

        assertTrue(viewModel.state.value.hasSubmitted)
        viewModel.onAction(SearchAction.EditSearch)

        assertTrue(!viewModel.state.value.hasSubmitted)
        assertEquals("frieren", viewModel.state.value.keyword)
    }

    @Test
    fun `initial search history repository is observed`() = runTest(dispatcher) {
        catalog.searchable = listOf(source("a"))
        val history = FakeSearchHistoryRepository(listOf("frieren", "kagurabachi"))
        val viewModel = SearchViewModel(catalog, initialHistoryRepository = history)
        advanceUntilIdle()

        assertEquals(listOf("frieren", "kagurabachi"), viewModel.state.value.searchHistory)
    }

    @Test
    fun `selecting history while aggregate mode is enabled searches every source`() = runTest(dispatcher) {
        catalog.searchable = listOf(source("a"), source("b"))
        val viewModel = SearchViewModel(catalog)
        advanceUntilIdle()

        viewModel.onAction(SearchAction.AggregateToggled)
        viewModel.onAction(SearchAction.HistorySelected("kagurabachi"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.aggregateSearch)
        assertTrue(viewModel.state.value.hasSubmitted)
        assertEquals(listOf("a", "b"), catalog.searchRequests.map { it.sourceId.value })
    }

    @Test
    fun `source selection is ignored while aggregate mode is enabled`() = runTest(dispatcher) {
        catalog.searchable = listOf(source("a"), source("b"))
        val viewModel = SearchViewModel(catalog)
        advanceUntilIdle()
        assertEquals(SourceId("a"), viewModel.state.value.selectedSourceId)

        viewModel.onAction(SearchAction.AggregateToggled)
        viewModel.onAction(SearchAction.SourceSelected(SourceId("b")))

        assertTrue(viewModel.state.value.aggregateSearch)
        assertEquals(SourceId("a"), viewModel.state.value.selectedSourceId)
    }

    private fun capabilities(
        filters: List<SourceFilter> = emptyList(),
    ) = SourceOutcome.Success(
        SourceCapabilities(supported = setOf(SourceCapability.SEARCH), searchFilters = filters),
    )

    private fun select(key: String) = SourceFilter.Select(
        key = key,
        label = key,
        options = listOf(FilterOption("0", "any")),
    )

    private fun source(id: String) = InstalledSource(
        sourceId = SourceId(id),
        name = id,
        version = "1",
        enabled = true,
        origin = "/tmp/$id.js",
    )

    private class FakeComicCatalog : ComicCatalog {
        var searchable: List<InstalledSource> = emptyList()
        var capabilityResponses: Map<SourceId, SourceOutcome<SourceCapabilities>> = emptyMap()
        var failList: Boolean = false
        val searchFailures = mutableSetOf<SourceId>()
        val searchRequests = mutableListOf<SearchRequest>()

        override suspend fun searchableSources(): List<InstalledSource> {
            if (failList) throw IllegalStateException("the source list is unreadable")
            return searchable
        }

        override suspend fun explorableSources(): List<InstalledSource> = emptyList()

        override suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities> =
            capabilityResponses[sourceId]
                ?: SourceOutcome.Failure(SourceRuntimeError.SourceNotLoaded(sourceId))

        override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> =
            error("not used by the search screen")

        override suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<SourcePage>> =
            error("not used by this screen")

        override suspend fun enabledSource(sourceId: SourceId): InstalledSource? = null

        override fun explore(request: ExploreRequest): PagingSource<PageKey, ExploreItem> =
            error("not used by the search screen")

        override fun search(request: SearchRequest): PagingSource<PageKey, Comic> {
            searchRequests += request
            return EmptyPagingSource(fail = request.sourceId in searchFailures)
        }
    }

    private class FakeSearchHistoryRepository(private val terms: List<String>) : SearchHistoryRepository {
        override fun observeRecent(): Flow<List<String>> = flowOf(terms)
        override suspend fun record(keyword: String) = Unit
        override suspend fun remove(keyword: String) = Unit
        override suspend fun clear() = Unit
    }

    private class EmptyPagingSource(private val fail: Boolean = false) : PagingSource<PageKey, Comic>() {
        override fun getRefreshKey(state: PagingState<PageKey, Comic>): PageKey = PageKey.Start

        override suspend fun load(params: LoadParams<PageKey>): LoadResult<PageKey, Comic> = if (fail) {
            LoadResult.Error(IllegalStateException("source fixture failure"))
        } else {
            LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
        }
    }
}

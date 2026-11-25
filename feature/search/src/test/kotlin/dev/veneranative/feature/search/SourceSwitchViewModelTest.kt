package dev.veneranative.feature.search

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceCapabilities
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourcePage
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.data.comic.PageKey
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
class SourceSwitchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val catalog = FakeCatalog()
    private val comic = ComicKey(SourceId("broken"), RemoteComicId("42"))
    private val found = Comic(ComicKey(SourceId("other"), RemoteComicId("7")), title = "Frieren")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `the switch searches every source except the one the comic is on`() = runTest(dispatcher) {
        catalog.installed = listOf(source("broken"), source("other"), source("third"))
        catalog.results = mapOf(SourceId("other") to listOf(found))

        val model = SourceSwitchViewModel(catalog, comic, "Frieren")

        advanceUntilIdle()
        assertEquals("Frieren", model.state.value.keyword)
        assertEquals(listOf(SourceId("other"), SourceId("third")), model.state.value.candidates.map { it.sourceId })
        assertEquals(listOf(SourceId("other"), SourceId("third")), model.rows.value.map { it.sourceId })
        assertEquals(listOf(found), model.rows.value.first().comics)
        assertTrue(model.rows.value.none { it.isLoading })
        assertEquals("Frieren", catalog.searchRequests.first().keyword)
    }

    @Test fun `a page opened without a title waits for one instead of searching everything`() = runTest(dispatcher) {
        catalog.installed = listOf(source("broken"), source("other"))

        val model = SourceSwitchViewModel(catalog, comic, null)
        advanceUntilIdle()

        assertEquals(emptyList<SearchRequest>(), catalog.searchRequests)
        model.search()
        assertEquals("请输入漫画标题后搜索。", model.state.value.message)
    }

    @Test fun `a wrong seed can be corrected and searched again`() = runTest(dispatcher) {
        catalog.installed = listOf(source("broken"), source("other"))
        val model = SourceSwitchViewModel(catalog, comic, "葬送的芙莉")
        advanceUntilIdle()

        model.onKeywordChanged("Frieren")
        model.search()
        advanceUntilIdle()

        assertEquals("Frieren", catalog.searchRequests.last().keyword)
    }

    @Test fun `coming back to the results finds the list where it was left`() = runTest(dispatcher) {
        catalog.installed = listOf(source("broken"), source("other"))
        val model = SourceSwitchViewModel(catalog, comic, "Frieren")
        advanceUntilIdle()

        model.keepScrollSlot(OVERVIEW_SCROLL_SLOT, index = 3, offset = 128)
        model.keepScrollSlot(rowScrollSlot(SourceId("other")), index = 5, offset = 40)

        // A rebuilt page asks for the same slots again; the row of one source must not move with
        // another's, or the card the user was about to tap would not be there when they return.
        assertEquals(ScrollSlot(3, 128), model.scrollSlot(OVERVIEW_SCROLL_SLOT))
        assertEquals(ScrollSlot(5, 40), model.scrollSlot(rowScrollSlot(SourceId("other"))))
        assertEquals(ScrollSlot(), model.scrollSlot(rowScrollSlot(SourceId("broken"))))
    }

    @Test fun `searching again starts at the top because the results are new`() = runTest(dispatcher) {
        catalog.installed = listOf(source("broken"), source("other"))
        val model = SourceSwitchViewModel(catalog, comic, "Frieren")
        advanceUntilIdle()
        model.keepScrollSlot(OVERVIEW_SCROLL_SLOT, index = 4, offset = 90)
        model.keepScrollSlot(rowScrollSlot(SourceId("other")), index = 2, offset = 10)
        model.openSource(SourceId("other"))
        model.keepScrollSlot(SOURCE_SCROLL_SLOT_PREFIX + "other", index = 6, offset = 0)

        model.onKeywordChanged("Frieren 2")
        model.search()
        advanceUntilIdle()

        assertEquals(ScrollSlot(), model.scrollSlot(OVERVIEW_SCROLL_SLOT))
        assertEquals(ScrollSlot(), model.scrollSlot(rowScrollSlot(SourceId("other"))))
        assertEquals(ScrollSlot(), model.scrollSlot(SOURCE_SCROLL_SLOT_PREFIX + "other"))
    }

    @Test fun `reopening a source starts its full list at the top`() = runTest(dispatcher) {
        catalog.installed = listOf(source("broken"), source("other"))
        val model = SourceSwitchViewModel(catalog, comic, "Frieren")
        advanceUntilIdle()
        model.keepScrollSlot(SOURCE_SCROLL_SLOT_PREFIX + "other", index = 7, offset = 12)

        model.openSource(SourceId("other"))
        advanceUntilIdle()

        assertEquals(ScrollSlot(), model.scrollSlot(SOURCE_SCROLL_SLOT_PREFIX + "other"))
    }

    @Test fun `opening one source lists its own results without touching the others`() = runTest(dispatcher) {
        catalog.installed = listOf(source("broken"), source("other"))
        val model = SourceSwitchViewModel(catalog, comic, "Frieren")
        advanceUntilIdle()

        model.openSource(SourceId("other"))
        advanceUntilIdle()

        assertEquals(SourceId("other"), model.state.value.openedSourceId)
        assertEquals(SourceId("other"), catalog.searchRequests.last().sourceId)
        // The overview rows stay put, so closing the full list does not re-search.
        assertEquals(1, model.rows.value.size)
        model.closeSource()
        assertNull(model.state.value.openedSourceId)
    }

    private fun source(id: String) = InstalledSource(
        sourceId = SourceId(id),
        name = id,
        version = "1",
        enabled = true,
        origin = "/tmp/$id.js",
    )

    private class FakeCatalog : ComicCatalog {
        var installed: List<InstalledSource> = emptyList()
        var results: Map<SourceId, List<Comic>> = emptyMap()
        val searchRequests = mutableListOf<SearchRequest>()

        override fun sourceChanges(): Flow<Long> = flowOf(0L)

        override suspend fun searchableSources(): List<InstalledSource> = installed

        override suspend fun explorableSources(): List<InstalledSource> = emptyList()

        override suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities> =
            error("not used by this screen")

        override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> =
            error("not used by this screen")

        override suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<SourcePage>> =
            error("not used by this screen")

        override suspend fun enabledSource(sourceId: SourceId): InstalledSource? =
            installed.firstOrNull { it.sourceId == sourceId }

        override fun explore(request: ExploreRequest): PagingSource<PageKey, ExploreItem> =
            error("not used by this screen")

        override fun search(request: SearchRequest): PagingSource<PageKey, Comic> {
            searchRequests += request
            return FixturePagingSource(results[request.sourceId].orEmpty())
        }
    }

    private class FixturePagingSource(private val comics: List<Comic>) : PagingSource<PageKey, Comic>() {
        override fun getRefreshKey(state: PagingState<PageKey, Comic>): PageKey = PageKey.Start

        override suspend fun load(params: LoadParams<PageKey>): LoadResult<PageKey, Comic> =
            LoadResult.Page(data = comics, prevKey = null, nextKey = null)
    }
}

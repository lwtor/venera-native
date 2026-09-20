package dev.veneranative.data.comic

import androidx.paging.PagingSource
import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.PagedResult
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceCapabilities
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourcePage
import dev.veneranative.data.source.InstallOutcome
import dev.veneranative.data.source.SourceRepository
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceCore
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paging source is the part features never see, so its behaviour is pinned here: which cursor a
 * load asks for, what an end of list looks like, and that a failure arrives as a domain error rather
 * than as a string.
 */
class ComicCatalogTest {

    private val sourceId = SourceId("source-a")

    @Test
    fun `refreshing a search asks for the first page without inventing a cursor`() = runTest {
        val core = FakeSourceCore().apply { items = listOf(comic("c1")) }
        val paging = SearchPagingSource(core, SearchRequest(sourceId = sourceId, keyword = "x"))

        val result = paging.load(refresh(key = PageKey.Start))

        assertEquals(listOf("c1"), (result as PagingSource.LoadResult.Page).data.map { it.key.remoteId.value })
        assertNull(core.requests.single().cursor)
    }

    @Test
    fun `appending passes the cursor the source returned`() = runTest {
        val core = FakeSourceCore().apply {
            items = listOf(comic("c1"))
            next = PageCursor.Page(2)
        }
        val paging = SearchPagingSource(core, SearchRequest(sourceId = sourceId, keyword = "x"))

        val first = paging.load(refresh(key = PageKey.Start)) as PagingSource.LoadResult.Page
        val cursor = first.nextKey as PageKey.At
        core.next = null
        paging.load(append(key = cursor))

        assertEquals(PageCursor.Page(2), core.requests.last().cursor)
        assertEquals(2, core.requests.size)
    }

    @Test
    fun `the last page advertises no next key`() = runTest {
        val core = FakeSourceCore().apply { next = null }
        val paging = SearchPagingSource(core, SearchRequest(sourceId = sourceId, keyword = "x"))

        val result = paging.load(refresh(key = PageKey.Start)) as PagingSource.LoadResult.Page

        assertNull(result.nextKey)
        assertNull(result.prevKey)
    }

    @Test
    fun `a cursor source keeps its token through paging`() = runTest {
        val core = FakeSourceCore().apply { next = PageCursor.Token("t2") }
        val paging = SearchPagingSource(core, SearchRequest(sourceId = sourceId, keyword = "x"))

        val first = paging.load(refresh(key = PageKey.Start)) as PagingSource.LoadResult.Page
        val cursor = first.nextKey as PageKey.At
        paging.load(append(key = cursor))

        assertEquals(PageCursor.Token("t2"), core.requests.last().cursor)
    }

    @Test
    fun `a source failure becomes a load error carrying the domain error`() = runTest {
        val failure = SourceRuntimeError.Timeout(timeoutMillis = 10_000)
        val core = FakeSourceCore().apply { error = failure }
        val paging = SearchPagingSource(core, SearchRequest(sourceId = sourceId, keyword = "x"))

        val result = paging.load(refresh(key = PageKey.Start))

        val error = (result as PagingSource.LoadResult.Error).throwable
        assertEquals(failure, (error as SourceLoadException).error)
    }

    @Test
    fun `an explore source pages like a search`() = runTest {
        val core = FakeSourceCore().apply {
            exploreItems = listOf(ExploreItem.Comics(listOf(comic("c1"))))
            next = PageCursor.Page(2)
        }
        val paging = ExplorePagingSource(core, ExploreRequest(sourceId = sourceId, pageKey = "Popular"))

        val result = paging.load(refresh(key = PageKey.Start)) as PagingSource.LoadResult.Page

        assertEquals(1, result.data.size)
        assertEquals(PageKey.At(PageCursor.Page(2)), result.nextKey)
        assertNull(core.exploreRequests.single().cursor)
    }

    @Test
    fun `only installed, enabled and capable sources are offered`() = runTest {
        val searchable = source("searchable")
        val explorable = source("explorable")
        val disabled = source("disabled", enabled = false)
        val broken = source("broken")
        val core = FakeSourceCore(
            declaredCapabilities = mapOf(
                searchable.sourceId to capabilities(SourceCapability.SEARCH),
                explorable.sourceId to capabilities(SourceCapability.EXPLORE),
                disabled.sourceId to capabilities(SourceCapability.SEARCH),
                broken.sourceId to SourceOutcome.Failure(
                    SourceRuntimeError.SourceNotLoaded(broken.sourceId),
                ),
            ),
        )
        val catalog = DefaultComicCatalog(
            sources = FakeSourceRepository(listOf(searchable, explorable, disabled, broken)),
            core = core,
        )

        assertEquals(
            listOf(searchable.sourceId),
            catalog.searchableSources().map { it.sourceId },
        )
        assertEquals(
            listOf(explorable.sourceId),
            catalog.explorableSources().map { it.sourceId },
        )
    }

    @Test
    fun `details are passed through unchanged`() = runTest {
        val detail = ComicDetail(comic = comic("c1"), description = "desc")
        val core = FakeSourceCore().apply { detailResponse = SourceOutcome.Success(detail) }
        val catalog = DefaultComicCatalog(FakeSourceRepository(emptyList()), core)

        val outcome = catalog.detail(detail.comic.key)

        assertEquals(SourceOutcome.Success(detail), outcome)
    }

    @Test
    fun `a source failure reaches the caller as the same domain error`() = runTest {
        val failure = SourceRuntimeError.UnsupportedCapability(SourceCapability.DETAIL)
        val core = FakeSourceCore().apply { detailResponse = SourceOutcome.Failure(failure) }
        val catalog = DefaultComicCatalog(FakeSourceRepository(emptyList()), core)

        assertEquals(SourceOutcome.Failure(failure), catalog.detail(comic("c1").key))
    }

    @Test
    fun `a disabled source is not a reachable source`() = runTest {
        val enabled = source("enabled")
        val disabled = source("disabled", enabled = false)
        val catalog = DefaultComicCatalog(
            sources = FakeSourceRepository(listOf(enabled, disabled)),
            core = FakeSourceCore(),
        )

        assertEquals(enabled, catalog.enabledSource(enabled.sourceId))
        assertNull(catalog.enabledSource(disabled.sourceId))
        assertNull(catalog.enabledSource(SourceId("never-installed")))
    }

    @Test
    fun `reachability is read from the installed list, not from the engine`() = runTest {
        // A source whose capabilities cannot be read is still installed: the screen needs to tell
        // "the source is gone" apart from "the source could not answer".
        val unreadable = source("unreadable")
        val catalog = DefaultComicCatalog(
            sources = FakeSourceRepository(listOf(unreadable)),
            core = FakeSourceCore(),
        )

        assertEquals(unreadable, catalog.enabledSource(unreadable.sourceId))
    }

    private fun source(id: String, enabled: Boolean = true) = InstalledSource(
        sourceId = SourceId(id),
        name = id,
        version = "1",
        enabled = enabled,
        origin = "/tmp/$id.js",
    )

    private fun capabilities(vararg capability: SourceCapability) =
        SourceOutcome.Success(SourceCapabilities(supported = capability.toSet()))

    private fun comic(id: String) = Comic(
        key = ComicKey(sourceId, RemoteComicId(id)),
        title = id,
    )

    private fun refresh(key: PageKey) = PagingSource.LoadParams.Refresh(
        key = key,
        loadSize = 20,
        placeholdersEnabled = false,
    )

    private fun append(key: PageKey) = PagingSource.LoadParams.Append(
        key = key,
        loadSize = 20,
        placeholdersEnabled = false,
    )

    private class FakeSourceRepository(private val sources: List<InstalledSource>) : SourceRepository {
        override suspend fun installed(): List<InstalledSource> = sources

        override suspend fun install(location: String): InstallOutcome =
            error("not used in these tests")

        override suspend fun setEnabled(sourceId: SourceId, enabled: Boolean): Boolean = false

        override suspend fun uninstall(sourceId: SourceId): Boolean = false
    }

    private class FakeSourceCore(
        private val declaredCapabilities: Map<SourceId, SourceOutcome<SourceCapabilities>> = emptyMap(),
    ) : SourceCore {
        val requests = mutableListOf<SearchRequest>()
        val exploreRequests = mutableListOf<ExploreRequest>()

        var items: List<Comic> = emptyList()
        var exploreItems: List<ExploreItem> = emptyList()
        var next: PageCursor? = null
        var error: SourceRuntimeError? = null
        var detailResponse: SourceOutcome<ComicDetail>? = null

        override suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities> =
            declaredCapabilities[sourceId]
                ?: SourceOutcome.Failure(SourceRuntimeError.SourceNotLoaded(sourceId))

        override suspend fun explore(request: ExploreRequest): SourceOutcome<PagedResult<ExploreItem>> {
            exploreRequests += request
            error?.let { return SourceOutcome.Failure(it) }
            return SourceOutcome.Success(PagedResult(items = exploreItems, next = next))
        }

        override suspend fun search(request: SearchRequest): SourceOutcome<PagedResult<Comic>> {
            requests += request
            error?.let { return SourceOutcome.Failure(it) }
            return SourceOutcome.Success(PagedResult(items = items, next = next))
        }

        override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> =
            detailResponse
                ?: SourceOutcome.Failure(SourceRuntimeError.SourceNotLoaded(comicKey.sourceId))

        override suspend fun chapters(comicKey: ComicKey): SourceOutcome<List<Chapter>> =
            SourceOutcome.Failure(SourceRuntimeError.SourceNotLoaded(comicKey.sourceId))

        override suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<SourcePage>> =
            SourceOutcome.Failure(
                SourceRuntimeError.SourceNotLoaded(chapterKey.comicKey.sourceId),
            )
    }
}

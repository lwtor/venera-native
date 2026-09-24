package dev.veneranative.feature.details

import androidx.paging.PagingSource
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceCapabilities
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourcePage
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.chaptersOf
import dev.veneranative.core.model.groupedChaptersOf
import dev.veneranative.data.collection.DEFAULT_SHELF_FOLDER_ID
import dev.veneranative.data.collection.FavoriteItem
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.data.comic.PageKey
import dev.veneranative.data.download.DownloadRepository
import dev.veneranative.data.download.DownloadTask
import dev.veneranative.data.download.DownloadPage
import dev.veneranative.data.download.DownloadError
import dev.veneranative.data.download.RecoveryReport
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val catalog = FakeCatalog()
    private val collection = FakeCollectionRepository()
    private val comicKey = ComicKey(SourceId("s"), RemoteComicId("c1"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `details and chapters arrive together and reach the screen`() = runTest(dispatcher) {
        catalog.source = installed("s", name = "Source S")
        catalog.detailResponse = SourceOutcome.Success(detail(title = "Frieren"))

        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()

        assertEquals(DetailsStatus.Ready, viewModel.state.value.status)
        assertEquals("Frieren", viewModel.state.value.title)
        assertEquals("Source S", viewModel.state.value.sourceName)
        assertEquals(listOf("Chapter 1", "Chapter 2"), viewModel.state.value.visibleChapters.map { it.title })
    }

    @Test fun `download chapter action resolves source pages and enqueues remote reference`() = runTest(dispatcher) {
        catalog.source = installed("s", name = "Source S")
        catalog.detailResponse = SourceOutcome.Success(detail(title = "Frieren"))
        catalog.pagesResponse = SourceOutcome.Success(listOf(SourcePage(0, "https://page/1"), SourcePage(1, "https://page/2")))
        val downloads = RecordingDownloadRepository()
        val viewModel = DetailsViewModel(catalog, comicKey, collection, downloads)
        advanceUntilIdle()
        val chapter = viewModel.state.value.detail!!.chapters.first()
        viewModel.onAction(DetailsAction.DownloadChapter(chapter.key))
        advanceUntilIdle()
        assertEquals(ChapterRef.Remote(chapter.key), downloads.enqueuedChapter)
        assertEquals(listOf("https://page/1", "https://page/2"), downloads.enqueuedPages.map { it.imageRef })
        assertEquals(1, viewModel.state.value.downloadQueueVersion)
    }

    @Test
    fun `a comic whose source is gone is unavailable rather than failed`() = runTest(dispatcher) {
        // The source was uninstalled between the search result and opening the comic, so the engine
        // would only ever say "not loaded" — which is not the reason the reader needs to hear.
        catalog.source = null

        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()

        assertEquals(DetailsStatus.SourceUnavailable, viewModel.state.value.status)
        assertNull(viewModel.state.value.message)
        assertEquals(0, catalog.detailCalls)
    }

    @Test
    fun `a source failure is reported as product copy`() = runTest(dispatcher) {
        catalog.source = installed("s")
        catalog.detailResponse = SourceOutcome.Failure(
            SourceRuntimeError.ScriptExecution("TypeError: cannot read property 'x' of undefined"),
        )

        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()

        assertEquals(DetailsStatus.Failed, viewModel.state.value.status)
        val message = viewModel.state.value.message.orEmpty()
        assertTrue(message.isNotEmpty())
        assertFalse("an engine diagnostic must not reach the screen", message.contains("TypeError"))
    }

    @Test
    fun `a source that cannot show details says so`() = runTest(dispatcher) {
        catalog.source = installed("s")
        catalog.detailResponse = SourceOutcome.Failure(
            SourceRuntimeError.UnsupportedCapability(SourceCapability.DETAIL),
        )

        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()

        assertEquals(DetailsStatus.Failed, viewModel.state.value.status)
        assertEquals("This source cannot show comic details.", viewModel.state.value.message)
    }

    @Test
    fun `a comic without chapters is a partial result, not a failure`() = runTest(dispatcher) {
        catalog.source = installed("s")
        catalog.detailResponse = SourceOutcome.Success(
            ComicDetail(comic = Comic(comicKey, title = "Frieren")),
        )

        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()

        assertEquals(DetailsStatus.Ready, viewModel.state.value.status)
        assertTrue(viewModel.state.value.hasNoChapters)
        assertTrue(viewModel.state.value.groups.isEmpty())
    }

    @Test
    fun `a failed load can be retried`() = runTest(dispatcher) {
        catalog.source = installed("s")
        catalog.detailResponse = SourceOutcome.Failure(SourceRuntimeError.Timeout(10_000))
        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()
        assertEquals(DetailsStatus.Failed, viewModel.state.value.status)

        catalog.detailResponse = SourceOutcome.Success(detail(title = "Frieren"))
        viewModel.onAction(DetailsAction.Retry)
        advanceUntilIdle()

        assertEquals(DetailsStatus.Ready, viewModel.state.value.status)
        assertEquals(2, catalog.detailCalls)
    }

    @Test
    fun `a refresh keeps a group that still exists and drops one that does not`() = runTest(dispatcher) {
        catalog.source = installed("s")
        catalog.detailResponse = SourceOutcome.Success(detail(title = "Frieren", grouped = true))
        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()
        assertEquals(listOf("EN", "JP"), viewModel.state.value.groups)

        viewModel.onAction(DetailsAction.GroupSelected("JP"))
        assertEquals("JP", viewModel.state.value.selectedGroup)

        viewModel.onAction(DetailsAction.Refresh)
        advanceUntilIdle()
        assertEquals("JP", viewModel.state.value.selectedGroup)

        // The source dropped that group in the meantime, so the selection cannot survive.
        catalog.detailResponse = SourceOutcome.Success(detail(title = "Frieren"))
        viewModel.onAction(DetailsAction.Refresh)
        advanceUntilIdle()

        assertNull(viewModel.state.value.selectedGroup)
    }

    @Test
    fun `changing the order is a display choice and can be reverted`() = runTest(dispatcher) {
        catalog.source = installed("s")
        catalog.detailResponse = SourceOutcome.Success(detail(title = "Frieren"))
        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()

        viewModel.onAction(DetailsAction.OrderSelected(ChapterOrder.Reversed))
        assertEquals(listOf("Chapter 2", "Chapter 1"), viewModel.state.value.visibleChapters.map { it.title })

        viewModel.onAction(DetailsAction.OrderSelected(ChapterOrder.SourceOrder))
        assertEquals(listOf("Chapter 1", "Chapter 2"), viewModel.state.value.visibleChapters.map { it.title })
        assertEquals(1, catalog.detailCalls)
    }

    @Test
    fun `keeping a comic puts it on the shelf and the shelf says so`() = runTest(dispatcher) {
        catalog.source = installed("s")
        catalog.detailResponse = SourceOutcome.Success(detail(title = "Frieren"))
        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isFavorite)

        viewModel.onAction(DetailsAction.ToggleFavorite)
        advanceUntilIdle()

        // What the screen shows and what the shelf holds are the same fact: the flag is read back
        // from the shelf, not remembered from the tap.
        assertTrue(viewModel.state.value.isFavorite)
        assertTrue(collection.contains(ComicRef.Remote(comicKey)))
        assertEquals(DEFAULT_SHELF_FOLDER_ID, collection.added.single().folderId)
    }

    @Test
    fun `a comic already on the shelf shows as kept and can be removed`() = runTest(dispatcher) {
        catalog.source = installed("s")
        catalog.detailResponse = SourceOutcome.Success(detail(title = "Frieren"))
        collection.seed(
            FavoriteItem(
                ref = ComicRef.Remote(comicKey),
                title = "Frieren",
                folderId = DEFAULT_SHELF_FOLDER_ID,
                addedAtEpochMillis = 1_000L,
            ),
        )
        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isFavorite)

        viewModel.onAction(DetailsAction.ToggleFavorite)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isFavorite)
        assertFalse(collection.contains(ComicRef.Remote(comicKey)))
        assertEquals(listOf(ComicRef.Remote(comicKey)), collection.removed)
    }

    @Test
    fun `keeping a comic records the chapters the source listed`() = runTest(dispatcher) {
        catalog.source = installed("s")
        catalog.detailResponse = SourceOutcome.Success(detail(title = "Frieren"))
        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()

        viewModel.onAction(DetailsAction.ToggleFavorite)
        advanceUntilIdle()

        // An update check can only compare against what the source said now, so this is the baseline
        // it will compare against later.
        val snapshot = collection.added.single().snapshot
        assertEquals("Frieren", snapshot.title)
        assertEquals(2, snapshot.chapterCount)
        assertEquals("2", snapshot.latestChapterId)
    }

    @Test
    fun `a comic whose source listed no chapters is kept with nothing to compare yet`() =
        runTest(dispatcher) {
            catalog.source = installed("s")
            catalog.detailResponse = SourceOutcome.Success(
                ComicDetail(comic = Comic(comicKey, title = "Frieren")),
            )
            val viewModel = DetailsViewModel(catalog, comicKey, collection)
            advanceUntilIdle()

            viewModel.onAction(DetailsAction.ToggleFavorite)
            advanceUntilIdle()

            // Zero would mean "empty", which the next real answer would read as an update.
            assertNull(collection.added.single().snapshot.chapterCount)
            assertTrue(viewModel.state.value.isFavorite)
        }

    @Test
    fun `a shelf that refuses the write says so in product copy`() = runTest(dispatcher) {
        catalog.source = installed("s")
        catalog.detailResponse = SourceOutcome.Success(detail(title = "Frieren"))
        collection.addError = RuntimeException("disk on fire")
        val viewModel = DetailsViewModel(catalog, comicKey, collection)
        advanceUntilIdle()

        viewModel.onAction(DetailsAction.ToggleFavorite)
        advanceUntilIdle()

        val message = viewModel.state.value.shelfMessage.orEmpty()
        assertTrue(message.isNotEmpty())
        assertFalse("storage diagnostics must not reach the screen", message.contains("disk on fire"))
        assertFalse(viewModel.state.value.isFavorite)
    }

    @Test
    fun `without a shelf there is nothing to keep a comic in`() = runTest(dispatcher) {
        catalog.source = installed("s")
        catalog.detailResponse = SourceOutcome.Success(detail(title = "Frieren"))
        val viewModel = DetailsViewModel(catalog, comicKey, null)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.hasShelf)
        viewModel.onAction(DetailsAction.ToggleFavorite)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isFavorite)
        assertTrue(collection.added.isEmpty())
    }

    private fun detail(title: String, grouped: Boolean = false) = ComicDetail(
        comic = Comic(comicKey, title = title),
        chapters = if (grouped) {
            groupedChaptersOf(
                comicKey,
                linkedMapOf(
                    "EN" to linkedMapOf("en1" to "Chapter 1", "en2" to "Chapter 2"),
                    "JP" to linkedMapOf("jp1" to "第1話"),
                ),
            )
        } else {
            chaptersOf(comicKey, linkedMapOf("1" to "Chapter 1", "2" to "Chapter 2"))
        },
    )

    private fun installed(id: String, name: String = id) = InstalledSource(
        sourceId = SourceId(id),
        name = name,
        version = "1",
        enabled = true,
        origin = "/tmp/$id.js",
    )

    private class FakeCatalog : ComicCatalog {
        var source: InstalledSource? = null
        var detailResponse: SourceOutcome<ComicDetail> =
            SourceOutcome.Failure(SourceRuntimeError.Internal("no response configured"))
        var detailCalls: Int = 0
        var pagesResponse: SourceOutcome<List<SourcePage>> = SourceOutcome.Success(emptyList())

        override suspend fun searchableSources(): List<InstalledSource> = emptyList()

        override suspend fun explorableSources(): List<InstalledSource> = emptyList()

        override suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities> =
            SourceOutcome.Failure(SourceRuntimeError.SourceNotLoaded(sourceId))

        override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> {
            detailCalls++
            return detailResponse
        }

        override suspend fun enabledSource(sourceId: SourceId): InstalledSource? =
            source?.takeIf { it.sourceId == sourceId }

        override suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<SourcePage>> =
            pagesResponse

        override fun explore(request: ExploreRequest): PagingSource<PageKey, ExploreItem> =
            error("not used by the details screen")

        override fun search(request: SearchRequest): PagingSource<PageKey, Comic> =
            error("not used by the details screen")
    }

    private class RecordingDownloadRepository : DownloadRepository {
        var enqueuedChapter: ChapterRef? = null
        var enqueuedPages: List<SourcePage> = emptyList()
        override fun observeTasks(): Flow<List<DownloadTask>> = emptyFlow()
        override fun observeTask(chapter: ChapterRef): Flow<DownloadTask?> = emptyFlow()
        override suspend fun enqueue(chapter: ChapterRef, title: String, pages: List<SourcePage>, comicTitle: String?) {
            enqueuedChapter = chapter
            enqueuedPages = pages
        }
        override suspend fun pause(chapter: ChapterRef) = Unit
        override suspend fun resume(chapter: ChapterRef) = Unit
        override suspend fun cancel(chapter: ChapterRef) = Unit
        override suspend fun retryFailed(chapter: ChapterRef) = Unit
        override suspend fun recover(workerId: String) = RecoveryReport(workerId, 0, 0, 0, 0, emptyList())
        override suspend fun isCompleteOffline(chapter: ChapterRef) = false
        override suspend fun pagesOf(chapter: ChapterRef) = emptyList<DownloadPage>()
        override suspend fun queuedPages(limit: Int) = emptyList<DownloadPage>()
        override suspend fun markRunning(chapter: ChapterRef, index: Int) = false
        override suspend fun markPaused(chapter: ChapterRef, index: Int) = false
        override suspend fun markSucceeded(chapter: ChapterRef, index: Int, relativePath: String, bytes: Long) = false
        override suspend fun markFailed(chapter: ChapterRef, index: Int, error: DownloadError) = false
    }
}

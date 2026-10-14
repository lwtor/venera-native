package dev.veneranative.feature.search

import androidx.paging.PagingSource
import dev.veneranative.core.model.*
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.data.comic.PageKey
import dev.veneranative.source.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResultStatusTest {
    @Test fun `copy row with existing tags still supplements missing status only once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val key = ComicKey(SourceId("copy_manga"), RemoteComicId("fixture"))
            val original = Comic(key, "Fixture", tags = listOf("Existing"))
            var calls = 0
            val catalog = object : ComicCatalog {
                override suspend fun comicListInfo(comic: Comic): SourceOutcome<Comic> {
                    calls++
                    return SourceOutcome.Success(comic.copy(publicationStatus = ComicPublicationStatus.Completed))
                }
                override suspend fun enabledSource(sourceId: SourceId) = InstalledSource(sourceId, "Fixture", "1", true, "fixture")
                override suspend fun searchableSources() = emptyList<InstalledSource>()
                override suspend fun explorableSources() = emptyList<InstalledSource>()
                override suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities> = error("unused")
                override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> = error("must not load full details")
                override suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<SourcePage>> = error("unused")
                override fun search(request: SearchRequest): PagingSource<PageKey, Comic> = error("unused")
                override fun explore(request: ExploreRequest): PagingSource<PageKey, ExploreItem> = error("unused")
            }
            val model = AuthorWorksViewModel(catalog, key, "Fixture", TagNavigation.Search("Fixture"))
            runCurrent()
            model.onResultVisible(original)
            runCurrent()
            assertEquals(ComicPublicationStatus.Completed, model.resultInfo.value[key]?.publicationStatus)
            assertEquals(listOf("Existing"), model.resultTags.value[key])
            model.onResultVisible(original)
            runCurrent()
            assertEquals(1, calls)
            val search = SearchViewModel(catalog)
            runCurrent()
            search.onResultVisible(original)
            runCurrent()
            assertEquals(ComicPublicationStatus.Completed, search.resultInfo.value[key]?.publicationStatus)
            search.onResultVisible(original)
            runCurrent()
            assertEquals(2, calls) // Each result ViewModel has one visible-row request, never a loop.
        } finally { Dispatchers.resetMain() }
    }
}

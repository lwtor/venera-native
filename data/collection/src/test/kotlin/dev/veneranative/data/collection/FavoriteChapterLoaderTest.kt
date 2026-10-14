package dev.veneranative.data.collection

import dev.veneranative.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteChapterLoaderTest {
    @Test fun missingVisibleCatalogsLoadAtMostTwoAndCancelOutsideViewport() = runTest {
        val dao = FakeFavoriteDao()
        val started = mutableListOf<ComicKey>()
        val keys = (1..3).map { ComicKey(SourceId("fixture"), RemoteComicId(it.toString())) }
        val gates = keys.associateWith { CompletableDeferred<Unit>() }
        val probe = object : RemoteChapterProbe {
            override suspend fun chapterSnapshot(comicKey: ComicKey): ChapterSnapshot {
                started += comicKey
                gates.getValue(comicKey).await()
                return ChapterSnapshot(1, "last", chapters = listOf(Chapter(ChapterKey(comicKey, RemoteChapterId("last")), "第111话", 0, "A")))
            }
        }
        val repository = DefaultCollectionRepository(dao, UpdateMarker(probe))
        keys.forEach { repository.addToFolders(ComicRef.Remote(it), emptySet(), ComicSnapshot("Comic")) }
        val items = repository.observeItems(null, ShelfSort.Title).first().sortedBy { (it.ref as ComicRef.Remote).key.remoteId.value }
        val loader = FavoriteChapterLoader(repository, this)
        items.forEach(loader::visible)
        runCurrent()
        assertEquals(keys.take(2), started)
        assertEquals("加载章节…", favoriteChapterLabel(items[0], loader.states.value[items[0].ref]))
        loader.hidden(items[2].ref) // Queued rows must not make a source request.
        loader.hidden(items[0].ref) // Active requests cancel as well.
        gates.getValue(keys[1]).complete(Unit)
        runCurrent()
        assertEquals(2, started.size)
        val loaded = repository.observeItem(items[1].ref).first()!!
        assertEquals("111话", favoriteChapterLabel(loaded, null))
        loader.visible(items[1]) // Stale UI snapshots cannot duplicate a completed load.
        runCurrent()
        assertEquals(2, started.size)
        assertTrue(repository.ensureChapters(items[1].ref)) // Stored directory also skips the probe.
        assertEquals(2, started.size)
    }

    @Test fun unavailableDirectoryEndsLoadingWithoutInventingMergedTotals() = runTest {
        val dao = FakeFavoriteDao()
        var calls = 0
        val probe = object : RemoteChapterProbe {
            override suspend fun chapterSnapshot(comicKey: ComicKey): ChapterSnapshot? { calls++; return null }
        }
        val repository = DefaultCollectionRepository(dao, UpdateMarker(probe))
        val ref = ComicRef.Remote(ComicKey(SourceId("fixture"), RemoteComicId("one")))
        repository.addToFolders(ref, emptySet(), ComicSnapshot("Comic", chapterCount = 330))
        val item = repository.observeItem(ref).first()!!
        val loader = FavoriteChapterLoader(repository, this)
        loader.visible(item); runCurrent()
        assertEquals(FavoriteCatalogState.Failed, loader.states.value[ref])
        assertEquals("章节未获取", favoriteChapterLabel(item, loader.states.value[ref]))
        loader.visible(item); runCurrent()
        assertEquals(1, calls)
        assertTrue(repository.observeItem(ref).first()!!.chapters.isEmpty())
        loader.hidden(ref); loader.visible(item); runCurrent()
        assertEquals(2, calls)
    }
}

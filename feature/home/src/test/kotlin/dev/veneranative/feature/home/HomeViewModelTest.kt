package dev.veneranative.feature.home

import androidx.lifecycle.ViewModelStore
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.core.model.LOCAL_REF_NAMESPACE
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.collection.ComicSnapshot
import dev.veneranative.data.collection.FavoriteFolder
import dev.veneranative.data.collection.FavoriteItem
import dev.veneranative.data.collection.ShelfSort
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.history.ReadingHistoryEntry
import dev.veneranative.data.history.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Test
    fun `resume destination preserves local and remote chapter identity`() {
        val local = ReadingHistoryEntry(
            comicKey = ComicKey(SourceId(LOCAL_REF_NAMESPACE), RemoteComicId("local-comic")),
            comicTitle = "Local comic",
            chapterId = RemoteChapterId("local-chapter"),
            chapterTitle = "Chapter 1",
            coverUrl = null,
            pageIndex = 0,
            pageCount = 1,
            updatedAtEpochMillis = 1,
        )
        val remote = local.copy(
            comicKey = ComicKey(SourceId("fixture"), RemoteComicId("remote-comic")),
        )

        assertEquals(
            ChapterRef.Local(LocalComicId("local-comic"), LocalChapterId("local-chapter")),
            local.toChapterRef(),
        )
        assertEquals(
            ChapterRef.Remote(ChapterKey(remote.comicKey, remote.chapterId)),
            remote.toChapterRef(),
        )
    }

    @Test
    fun `home follows recent reading and collection changes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val key = ComicKey(SourceId("fixture"), RemoteComicId("comic-1"))
            val history = MutableStateFlow(emptyList<ReadingHistoryEntry>())
            val favorites = MutableStateFlow(emptyList<FavoriteItem>())
            val viewModel = HomeViewModel(
                history = FakeHistoryRepository(history),
                collection = FakeCollectionRepository(favorites),
                local = null,
            )
            val viewModelStore = ViewModelStore().apply { put("home", viewModel) }
            val observed = mutableListOf<HomeUiState>()
            val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect(observed::add)
            }
            runCurrent()
            assertTrue(observed.last().historyAvailable)
            assertTrue(observed.last().favoritesAvailable)
            assertEquals(emptyList<ReadingHistoryEntry>(), observed.last().recentReading)

            history.value = listOf(
                ReadingHistoryEntry(
                    comicKey = key,
                    comicTitle = "Fixture comic",
                    chapterId = RemoteChapterId("chapter-1"),
                    chapterTitle = "Chapter 1",
                    coverUrl = null,
                    pageIndex = 2,
                    pageCount = 10,
                    updatedAtEpochMillis = 90,
                ),
                ReadingHistoryEntry(
                    comicKey = key,
                    comicTitle = "Fixture comic",
                    chapterId = RemoteChapterId("chapter-2"),
                    chapterTitle = "Chapter 2",
                    coverUrl = null,
                    pageIndex = 1,
                    pageCount = 10,
                    updatedAtEpochMillis = 100,
                ),
                ReadingHistoryEntry(
                    comicKey = ComicKey(SourceId("fixture"), RemoteComicId("comic-2")),
                    comicTitle = "Another comic",
                    chapterId = RemoteChapterId("chapter-3"),
                    chapterTitle = "Chapter 3",
                    coverUrl = null,
                    pageIndex = 0,
                    pageCount = 10,
                    updatedAtEpochMillis = 80,
                ),
            )
            favorites.value = listOf(
                FavoriteItem(
                    ref = ComicRef.Remote(key),
                    title = "Fixture comic",
                    folderId = "default",
                    addedAtEpochMillis = 100,
                    hasUpdate = true,
                ),
            )
            runCurrent()

            assertEquals(listOf("chapter-2", "chapter-3"), observed.last().recentReading.map { it.chapterId.value })
            assertEquals(listOf("Fixture comic", "Another comic"), observed.last().recentReading.map { it.comicTitle })
            assertEquals("Fixture comic", observed.last().favorites.single().title)
            assertTrue(observed.last().favorites.single().hasUpdate)
            subscription.cancelAndJoin()
            viewModelStore.clear()
            runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeHistoryRepository(
        private val recent: Flow<List<ReadingHistoryEntry>>,
    ) : HistoryRepository {
        override fun observeRecent(limit: Int): Flow<List<ReadingHistoryEntry>> = recent
        override fun observeComicHistory(comicKey: ComicKey): Flow<List<ReadingHistoryEntry>> = emptyFlow()
        override suspend fun record(entry: ReadingHistoryEntry) = Unit
        override suspend fun progress(comicKey: ComicKey): ReadingProgress? = null
        override suspend fun remove(comicKey: ComicKey) = Unit
    }

    private class FakeCollectionRepository(
        private val items: Flow<List<FavoriteItem>>,
    ) : CollectionRepository {
        override fun observeFolders(): Flow<List<FavoriteFolder>> = MutableStateFlow(emptyList())
        override fun observeItems(folderId: String?, sort: ShelfSort): Flow<List<FavoriteItem>> = items
        override fun observeItem(ref: ComicRef): Flow<FavoriteItem?> = MutableStateFlow(null)
        override suspend fun createFolder(name: String): String = error("Not used in this test")
        override suspend fun renameFolder(id: String, name: String) = error("Not used in this test")
        override suspend fun deleteFolder(id: String) = error("Not used in this test")
        override suspend fun add(ref: ComicRef, folderId: String, snapshot: ComicSnapshot) = error("Not used in this test")
        override suspend fun remove(ref: ComicRef) = error("Not used in this test")
        override suspend fun moveTo(ref: ComicRef, folderId: String) = error("Not used in this test")
        override suspend fun clearUpdate(ref: ComicRef) = error("Not used in this test")
        override suspend fun refreshUpdates(): Int = error("Not used in this test")
    }
}

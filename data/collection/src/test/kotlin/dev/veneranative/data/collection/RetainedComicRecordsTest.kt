package dev.veneranative.data.collection

import dev.veneranative.core.database.*
import dev.veneranative.core.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetainedComicRecordsTest {
    private val key = ComicKey(SourceId("fixture"), RemoteComicId("one"))
    private val ref = ComicRef.Remote(key)
    private var now = 1_000L
    private val favorites = FakeFavoriteDao()
    private val records = FakeComicRecordDao()
    private val store = CollectionRecordStore(records, favorites, { now }, io = kotlinx.coroutines.Dispatchers.Unconfined)
    private var sourceCalls = 0
    private var forceSeen = false
    private var answer: ChapterSnapshot? = null
    private val probe = object : RemoteChapterProbe {
        override suspend fun chapterSnapshot(comicKey: ComicKey): ChapterSnapshot? { sourceCalls++; return answer }
        override suspend fun refreshSnapshot(comicKey: ComicKey, force: Boolean): ChapterSnapshot? { forceSeen = force; return chapterSnapshot(comicKey) }
    }
    private fun detail(title: String = "Comic", cover: String = "https://example.org/old", count: Int = 2): ComicDetail = ComicDetail(
        comic = Comic(key, title, coverUrl = cover, authors = listOf("Fixture author")), description = "Fixture summary",
        chapters = (1..count).map { Chapter(ChapterKey(key, RemoteChapterId("c$it")), "第${it}话", it - 1, "A") })
    private fun snapshot(value: ComicDetail): ComicSnapshot = ComicSnapshot(value.comic.title, coverRef = value.comic.coverUrl,
        chapterCount = value.chapters.size, latestChapterId = value.chapters.last().key.remoteId.value, chapters = value.chapters, detail = value)
    private val covers = object : ComicCoverStorage {
        var failOld = false
        override suspend fun retain(key: ComicKey, url: String, force: Boolean): Long? = if (failOld && url.endsWith("old")) null else now
        override suspend fun available(key: ComicKey): Boolean = !failOld
    }
    private fun repository(): DefaultCollectionRepository = DefaultCollectionRepository(favorites, UpdateMarker(probe), { now }, records = store, covers = covers)

    @Test fun successfulMetadataAndDirectoryRemainLocalUntilExpiryAndFailuresBackOff() = runTest {
        val repository = repository()
        repository.addToFolders(ref, emptySet(), snapshot(detail()))
        repository.refreshExpired()
        assertEquals(0, sourceCalls)
        now += ComicRefreshPolicy.TTL_MILLIS
        val newer = detail("New title", count = 3)
        answer = ChapterSnapshot(3, "c3", chapters = newer.chapters, detail = newer)
        repository.refreshExpired()
        assertEquals(1, sourceCalls)
        assertEquals("New title", repository.observeItem(ref).first()!!.title)
        assertEquals(3, records.chapters("fixture", "one").size)
        val lastSuccess = store.find(ref)!!.metadataRefreshedAt
        now += ComicRefreshPolicy.TTL_MILLIS
        answer = null
        repository.refreshExpired()
        assertEquals(2, sourceCalls)
        assertEquals(lastSuccess, store.find(ref)!!.metadataRefreshedAt)
        assertEquals("New title", store.find(ref)!!.title)
        repository.refreshExpired()
        assertEquals(2, sourceCalls)
        now += ComicRefreshPolicy.FAILURE_DELAY_MILLIS
        repository.refreshExpired()
        assertEquals(3, sourceCalls)
        assertTrue(records.chapters("fixture", "one").isNotEmpty())
    }
    @Test fun missingCoverRepairsAnExpiredUrlEvenWhenDirectoryAndMetadataAreFresh() = runTest {
        val repository = repository()
        repository.addToFolders(ref, emptySet(), snapshot(detail()))
        covers.failOld = true
        val newer = detail(cover = "https://example.org/new")
        answer = ChapterSnapshot(2, "c2", chapters = newer.chapters, detail = newer)
        assertTrue(repository.ensureChapters(ref))
        assertEquals(1, sourceCalls)
        assertEquals("https://example.org/new", repository.observeItem(ref).first()!!.coverRef)
        assertTrue(repository.observeItem(ref).first()!!.hasStoredCover)
    }
    @Test fun legacyVersionDirectoryNormalizesWithoutNetworkAndUnfavoriteRetainsResources() = runTest {
        val repository = repository()
        val groups = groupedChaptersOf(key, linkedMapOf("A" to mapOf("same" to "第110话"), "B" to mapOf("same" to "第111话")))
        repository.addToFolders(ref, emptySet(), ComicSnapshot("Comic", chapters = groups))
        assertEquals(0, sourceCalls)
        assertEquals(2, records.chapters("fixture", "one").size)
        assertEquals("111话", favoriteChapterLabel(repository.observeItem(ref).first()!!, null))
        repository.remove(ref)
        assertNotNull(store.find(ref))
        assertEquals(2, records.chapters("fixture", "one").size)
        assertNull(repository.observeItem(ref).first())
    }
    @Test fun foregroundMaintenanceRechecksExpiryAndStopsWhenLifecycleCancels() = runTest {
        val repository = repository()
        repository.addToFolders(ref, emptySet(), snapshot(detail()))
        answer = ChapterSnapshot(2, "c2", chapters = detail().chapters, detail = detail())
        val job = launch { repository.maintainWhileActive() }
        runCurrent()
        assertEquals(0, sourceCalls)
        now += ComicRefreshPolicy.TTL_MILLIS
        advanceTimeBy(ComicRefreshPolicy.FAILURE_DELAY_MILLIS)
        runCurrent()
        assertEquals(1, sourceCalls)
        job.cancelAndJoin()
        now += ComicRefreshPolicy.TTL_MILLIS
        advanceTimeBy(ComicRefreshPolicy.FAILURE_DELAY_MILLIS)
        runCurrent()
        assertEquals(1, sourceCalls)
    }
    @Test fun manualRefreshBypassesFreshnessAndRequestsSourceRefresh() = runTest {
        val repository = repository()
        repository.addToFolders(ref, emptySet(), snapshot(detail()))
        val refreshed = detail()
        answer = ChapterSnapshot(2, "c2", chapters = refreshed.chapters, detail = refreshed)
        repository.refreshUpdates()
        assertEquals(1, sourceCalls)
        assertTrue(forceSeen)
    }
}

internal class FakeComicRecordDao : ComicRecordDao {
    private val records = MutableStateFlow<List<ComicRecordEntity>>(emptyList())
    private val directory = MutableStateFlow<List<ComicChapterEntity>>(emptyList())
    override fun observeFavorites(): Flow<List<ComicRecordEntity>> = records
    override fun observeFavoriteChapters(): Flow<List<ComicChapterEntity>> = directory
    override suspend fun record(sourceId: String, comicId: String) = records.value.firstOrNull { it.sourceId == sourceId && it.comicId == comicId }
    override suspend fun chapters(sourceId: String, comicId: String) = directory.value.filter { it.sourceId == sourceId && it.comicId == comicId }.sortedBy { it.position }
    override suspend fun upsert(record: ComicRecordEntity) { records.value = records.value.filterNot { it.sourceId == record.sourceId && it.comicId == record.comicId } + record }
    override suspend fun upsertChapters(chapters: List<ComicChapterEntity>) { directory.value = directory.value + chapters }
    override suspend fun deleteChapters(sourceId: String, comicId: String) { directory.value = directory.value.filterNot { it.sourceId == sourceId && it.comicId == comicId } }
    override suspend fun dueFavorites(now: Long) = records.value.filter { it.nextRefreshAt <= now }
    override suspend fun schedule(sourceId: String, comicId: String, attemptAt: Long, nextAt: Long) { record(sourceId, comicId)?.let { upsert(it.copy(lastAttemptAt = attemptAt, nextRefreshAt = nextAt)) } }
    override suspend fun coverFetched(sourceId: String, comicId: String, fetchedAt: Long) { record(sourceId, comicId)?.let { upsert(it.copy(coverFetchedAt = fetchedAt)) } }
}

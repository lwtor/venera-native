package dev.veneranative.data.collection

import androidx.room.withTransaction
import dev.veneranative.core.database.DEFAULT_FOLDER_ID
import dev.veneranative.core.database.FavoriteDao
import dev.veneranative.core.database.FavoriteEntryEntity
import dev.veneranative.core.database.FavoriteFolderEntity
import dev.veneranative.core.database.FavoriteMembershipEntity
import dev.veneranative.core.database.VeneraDatabase
import dev.veneranative.core.model.ComicRef
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import dev.veneranative.core.model.ComicCoverStorage
import dev.veneranative.core.model.ComicDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import java.util.UUID
import java.util.Locale

/**
 * The shelf over Room.
 *
 * Every read is a `Flow` straight from the database and every write goes through the DAO, so there
 * is no in-memory copy of the collection that could disagree with it: a folder renamed here is
 * visible to the shelf immediately, and survives the process being recreated.
 *
 * Re-adding a comic keeps the original timestamp. All is represented by the favorite row itself;
 * optional user collections are memberships, so deleting a collection never loses a favorite.
 */
class DefaultCollectionRepository internal constructor(
    private val dao: FavoriteDao,
    private val updateMarker: UpdateMarker,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val historyDao: dev.veneranative.core.database.ReadingHistoryDao? = null,
    private val records: CollectionRecordStore? = null,
    private val covers: ComicCoverStorage? = null,
    private val maintenanceScope: CoroutineScope? = null,
) : CollectionRepository {

    constructor(database: VeneraDatabase, probe: RemoteChapterProbe, covers: ComicCoverStorage? = null,
        maintenanceScope: CoroutineScope? = null, clock: () -> Long = System::currentTimeMillis) : this(
        dao = database.favoriteDao(),
        historyDao = database.readingHistoryDao(),
        updateMarker = UpdateMarker(probe),
        clock = clock, records = CollectionRecordStore(database, clock), covers = covers, maintenanceScope = maintenanceScope,
        transaction = { operation -> database.withTransaction { operation() } },
    )

    private val refreshLock = Mutex()
    private val permits = Semaphore(2)
    private val comicLocks = Array(32) { Mutex() }
    private fun lock(ref: ComicRef) = comicLocks[(ref.hashCode() and Int.MAX_VALUE) % comicLocks.size]
    private val recordSnapshots = records?.snapshots ?: kotlinx.coroutines.flow.flowOf(emptyMap())
    private fun FavoriteItem.withRecord(snapshot: StoredComic?): FavoriteItem = snapshot?.let {
        copy(title = it.record.title, subtitle = it.record.subtitle, coverRef = it.record.coverUrl?.takeIf(String::isNotBlank),
            lastUpdated = it.record.sourceUpdatedAt, chapters = it.chapters, storedCoverAt = it.record.coverFetchedAt)
    } ?: this

    private val recentHistory = (historyDao?.observeRecent(Int.MAX_VALUE) ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .map { rows -> rows.associateBy { it.sourceId to it.comicId } }
    private fun FavoriteItem.withReading(rows: Map<Pair<String, String>, dev.veneranative.core.database.ReadingHistoryEntity>): FavoriteItem {
        val latest = rows[ref.refSource() to ref.refComic()]
        // Reading the final page of the chapter that raised the update marker acknowledges it.
        // Keep the persisted flag in sync as well as projecting it away immediately, so a later
        // shelf refresh does not resurrect an already-read marker.
        val acknowledgedUpdate = ref is ComicRef.Remote && hasUpdate && latestChapterId != null &&
            latest != null && latest.chapterId == latestChapterId &&
            latest.pageCount > 0 && latest.pageIndex >= latest.pageCount - 1
        if (acknowledgedUpdate) {
            maintenanceScope?.launch { dao.clearUpdate(ref.refSource(), ref.refComic()) }
        }
        return copy(
            hasUpdate = hasUpdate && !acknowledgedUpdate,
            readingChapter = latest?.let { dev.veneranative.core.model.ComicReadingChapter(it.chapterId, it.chapterTitle, it.chapterGroup) },
        )
    }

    override fun observeFolders(): Flow<List<FavoriteFolder>> =
        dao.observeFolders().map { rows -> rows.map { it.toDomain() } }

    /**
     * Favourites in the order [sort] asks for.
     *
     * The order comes from the query, not from sorting a list afterwards: the database returns rows
     * already ordered, so a shelf of any size costs the same and the order is the one SQLite
     * guarantees (including how it places a comic that was never read).
     */
    override fun observeItems(folderId: String?, sort: ShelfSort): Flow<List<FavoriteItem>> =
        when (sort) {
            ShelfSort.AddedAt -> dao.observeByAddedAt(folderId)
            ShelfSort.Title -> dao.observeByTitle(folderId)
            ShelfSort.LastRead -> dao.observeByLastRead(folderId)
            ShelfSort.Updated -> dao.observeByUpdate(folderId)
        }.combine(dao.observeMemberships()) { rows, memberships ->
            val grouped = memberships.groupBy { it.refSource to it.refComic }
            rows.mapNotNull { row -> row.toDomainOrNull(includeLegacyChapters = records == null)?.copy(
                folderIds = grouped[row.refSource to row.refComic]?.map { it.folderId }?.toSet().orEmpty(),
            ) }
        }.combine(recordSnapshots) { items, snapshots -> items.map { it.withRecord(snapshots[it.ref.refSource() to it.ref.refComic()]) } }
        .combine(recentHistory) { items, history -> items.map { it.withReading(history) } }

    override fun observeItem(ref: ComicRef): Flow<FavoriteItem?> =
        dao.observeEntry(ref.refSource(), ref.refComic()).combine(dao.observeMemberships()) { row, memberships ->
            row?.toDomainOrNull(includeLegacyChapters = records == null)?.copy(folderIds = memberships.asSequence()
                .filter { it.refSource == ref.refSource() && it.refComic == ref.refComic() }
                .map { it.folderId }.toSet())
        }.combine(recordSnapshots) { item, snapshots -> item?.withRecord(snapshots[ref.refSource() to ref.refComic()]) }
        .combine(recentHistory) { item, history -> item?.withReading(history) }

    override suspend fun createFolder(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "folder name must not be blank" }
        var id = ""
        transaction {
            requireAvailableName(trimmed)
            val folder = FavoriteFolderEntity(
                folderId = "folder-${UUID.randomUUID()}",
                name = trimmed,
                sortOrder = (dao.maxFolderSortOrder() ?: -1) + 1,
                removable = true,
            )
            dao.insertFolder(folder)
            id = folder.folderId
        }
        return id
    }

    override suspend fun renameFolder(id: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "folder name must not be blank" }
        transaction {
            if (dao.folder(id) == null) return@transaction
            requireAvailableName(trimmed, exceptId = id)
            dao.renameFolder(id, trimmed)
        }
    }

    private suspend fun requireAvailableName(name: String, exceptId: String? = null) {
        val normalized = name.lowercase(Locale.ROOT)
        if (normalized == "全部" || normalized == "all" ||
            dao.folders().any { it.folderId != exceptId && it.name.lowercase(Locale.ROOT) == normalized }
        ) throw CollectionNameConflictException()
    }

    override suspend fun deleteFolder(id: String) {
        val folder = dao.folder(id) ?: return
        // Old callers may still pass the former default id; All itself is never a folder row.
        if (!folder.removable) return
        dao.deleteFolder(id)
    }

    override suspend fun add(ref: ComicRef, folderId: String, snapshot: ComicSnapshot) {
        addToFolders(ref, setOf(folderId), snapshot)
    }

    override suspend fun addToFolders(ref: ComicRef, folderIds: Set<String>, snapshot: ComicSnapshot) {
        transaction {
            val existing = dao.entry(ref.refSource(), ref.refComic())
            dao.upsertEntry(
            FavoriteEntryEntity(
                refSource = ref.refSource(),
                refComic = ref.refComic(),
                folderId = "", // Historical column; membership now lives in favorite_membership.
                title = snapshot.title,
                subtitle = snapshot.subtitle,
                coverRef = snapshot.coverRef,
                addedAt = existing?.addedAt ?: clock(),
                lastReadAt = existing?.lastReadAt,
                chapterCount = snapshot.chapterCount ?: existing?.chapterCount,
                latestChapterId = snapshot.latestChapterId ?: existing?.latestChapterId,
                hasUpdate = existing?.hasUpdate ?: false,
                updatedAt = existing?.updatedAt,
                lastUpdated = snapshot.lastUpdated ?: existing?.lastUpdated,
                chaptersJson = snapshot.chapters?.let(::encodeChapterCatalog) ?: existing?.chaptersJson,
            ),
            )
            setFolders(ref, folderIds)
            dao.entry(ref.refSource(), ref.refComic())?.let { records?.seed(it) }
            records?.saveSnapshot(ref, snapshot)
        }
        requestCoverRepair(ref)
    }

    override suspend fun ensureChapters(ref: ComicRef): Boolean = lock(ref).withLock {
        permits.withPermit {
            val stored = dao.entry(ref.refSource(), ref.refComic()) ?: return@withPermit false
            records?.seed(stored)
            val before = records?.find(ref)
            val backedOff = before != null && ComicRefreshPolicy.backedOff(before.lastAttemptAt, before.nextRefreshAt, clock())
            val coverOkay = !backedOff && repairCover(ref)
            val known = records?.directory(ref) ?: decodeChapterCatalog(stored.chaptersJson)
            if (known.isNotEmpty() && coverOkay) return@withPermit true
            if (backedOff) return@withPermit known.isNotEmpty()
            val record = records?.find(ref)
            if (record != null && !ComicRefreshPolicy.due(record.nextRefreshAt, clock()) && coverOkay) return@withPermit known.isNotEmpty()
            refreshOne(ref, force = false)
            (records?.directory(ref) ?: decodeChapterCatalog(dao.entry(ref.refSource(), ref.refComic())?.chaptersJson)).isNotEmpty()
        }
    }

    override suspend fun updateChapters(ref: ComicRef, chapters: List<dev.veneranative.core.model.Chapter>) {
        if (chapters.isEmpty()) return
        dao.updateChapterCatalog(ref.refSource(), ref.refComic(), encodeChapterCatalog(chapters))
        records?.saveDirectory(ref, chapters)
    }
    override suspend fun updateDetails(ref: ComicRef, detail: ComicDetail) {
        lock(ref).withLock {
            val favorite = dao.entry(ref.refSource(), ref.refComic()) ?: return
            records?.seed(favorite)
            if (records != null) records.saveDetail(ref, detail) else updateChapters(ref, detail.chapters)
        }
        requestCoverRepair(ref)
    }
    private fun requestCoverRepair(ref: ComicRef) {
        if (covers != null) maintenanceScope?.launch {
            lock(ref).withLock { permits.withPermit { repairCover(ref) } }
        }
    }
    private suspend fun repairCover(ref: ComicRef, force: Boolean = false): Boolean {
        val key = (ref as? ComicRef.Remote)?.key ?: return true
        val storage = covers ?: return true
        val url = (records?.find(ref)?.coverUrl ?: dao.entry(ref.refSource(), ref.refComic())?.coverRef)?.takeIf(String::isNotBlank) ?: return true
        return try {
            val at = storage.retain(key, url, force)
            if (at != null) records?.coverFetched(ref, at)
            at != null || storage.available(key)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    override suspend fun remove(ref: ComicRef) {
        dao.deleteEntry(ref.refSource(), ref.refComic())
    }

    override suspend fun moveTo(ref: ComicRef, folderId: String) {
        if (dao.folder(folderId) == null) return
        setFolders(ref, setOf(folderId))
    }

    override suspend fun setFolders(ref: ComicRef, folderIds: Set<String>) {
        transaction {
            val valid = folderIds.filter { it != DEFAULT_FOLDER_ID && dao.folder(it) != null }
            dao.deleteMemberships(ref.refSource(), ref.refComic())
            valid.forEach { id -> dao.insertMembership(FavoriteMembershipEntity(ref.refSource(), ref.refComic(), id)) }
        }
    }

    override suspend fun clearUpdate(ref: ComicRef) {
        dao.clearUpdate(ref.refSource(), ref.refComic())
    }

    /**
     * Compares every remote comic against its source.
     *
     * The snapshot is written whether or not the comic changed: a marker that is cleared and then
     * re-raised on the next refresh because the stored snapshot was left behind is the bug this
     * avoids. Only a genuinely new chapter raises the flag again.
     */
    override suspend fun maintainWhileActive() {
        while (currentCoroutineContext().isActive) {
            refreshExpired()
            delay(ComicRefreshPolicy.FAILURE_DELAY_MILLIS)
        }
    }
    override suspend fun refreshUpdates(): Int = refresh(force = true)
    override suspend fun refreshExpired(): Int = refresh(force = false)
    private suspend fun refresh(force: Boolean): Int {
        if (force) refreshLock.lock() else if (!refreshLock.tryLock()) return 0
        try {
            val favorites = dao.entries().sortedByDescending { it.addedAt }
            favorites.forEach { records?.seed(it) }
            var marked = 0
            coroutineScope {
                for (batch in favorites.chunked(2)) {
                    marked += batch.mapNotNull { row -> row.toDomainOrNull(includeLegacyChapters = false)?.ref as? ComicRef.Remote }.map { ref -> async {
                        lock(ref).withLock { permits.withPermit { refreshOne(ref, force) } }
                    } }.map { it.await() }.sum()
                }
            }
            return marked
        } finally { refreshLock.unlock() }
    }
    /** Called only inside the per-comic lock and shared network concurrency permit. */
    private suspend fun refreshOne(ref: ComicRef, force: Boolean): Int {
        val row = dao.entry(ref.refSource(), ref.refComic()) ?: return 0
        val item = row.toDomainOrNull(includeLegacyChapters = false) ?: return 0
        if (ref !is ComicRef.Remote) return 0
        val record = records?.find(ref)
        if (!force && record != null && ComicRefreshPolicy.backedOff(record.lastAttemptAt, record.nextRefreshAt, clock())) return 0
        val coverOkay = repairCover(ref)
        if (!force && record != null && !ComicRefreshPolicy.due(record.nextRefreshAt, clock()) && coverOkay) return 0
        try {
            val remote = updateMarker.chapterSnapshot(ref, force)
            currentCoroutineContext().ensureActive()
            if (remote == null) { records?.schedule(ref, failed = true); return 0 }
            val state = updateMarker.evaluateSnapshot(item, remote)
            remote.detail?.let { records?.saveDetail(ref, it) }
            remote.lastUpdated?.let { dao.updateLastUpdated(row.refSource, row.refComic, it) }
            if (remote.detail == null || records == null) remote.chapters?.let { updateChapters(ref, it) }
            if (remote.chapterCount > 0 && (state is UpdateState.Updated || row.chapterCount != remote.chapterCount || row.latestChapterId != remote.latestChapterId)) dao.applySnapshot(row.refSource, row.refComic, remote.chapterCount,
                remote.latestChapterId, row.hasUpdate || state is UpdateState.Updated,
                if (state is UpdateState.Updated) clock() else row.updatedAt ?: 0)
            records?.schedule(ref, failed = false)
            if (!repairCover(ref, force = true)) records?.schedule(ref, failed = true)
            return if (state is UpdateState.Updated) 1 else 0
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (records == null) throw failure
            records.schedule(ref, failed = true)
            return 0
        }
    }
}

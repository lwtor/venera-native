package dev.veneranative.data.collection

import androidx.room.withTransaction
import dev.veneranative.core.database.DEFAULT_FOLDER_ID
import dev.veneranative.core.database.FavoriteDao
import dev.veneranative.core.database.FavoriteEntryEntity
import dev.veneranative.core.database.FavoriteFolderEntity
import dev.veneranative.core.database.VeneraDatabase
import dev.veneranative.core.model.ComicRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The shelf over Room.
 *
 * Every read is a `Flow` straight from the database and every write goes through the DAO, so there
 * is no in-memory copy of the collection that could disagree with it: a folder renamed here is
 * visible to the shelf immediately, and survives the process being recreated.
 *
 * Two rules shape the writes. Adding a comic twice is idempotent and keeps the original "added at",
 * because re-adding is how the UI refreshes metadata and must not reorder the shelf. Deleting a
 * folder moves its comics to the default folder instead of dropping them, because a folder is
 * organisation the user can rebuild and a lost favourite is not.
 */
class DefaultCollectionRepository internal constructor(
    private val dao: FavoriteDao,
    private val updateMarker: UpdateMarker,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
) : CollectionRepository {

    constructor(database: VeneraDatabase, probe: RemoteChapterProbe) : this(
        dao = database.favoriteDao(),
        updateMarker = UpdateMarker(probe),
        transaction = { operation -> database.withTransaction { operation() } },
    )

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
        }.map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    override fun observeItem(ref: ComicRef): Flow<FavoriteItem?> =
        dao.observeEntry(ref.refSource(), ref.refComic()).map { row -> row?.toDomainOrNull() }

    override suspend fun createFolder(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "folder name must not be blank" }
        val folder = FavoriteFolderEntity(
            folderId = "folder-${UUID.randomUUID()}",
            name = trimmed,
            sortOrder = (dao.maxFolderSortOrder() ?: -1) + 1,
            removable = true,
        )
        dao.insertFolder(folder)
        return folder.folderId
    }

    override suspend fun renameFolder(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dao.renameFolder(id, trimmed)
    }

    override suspend fun deleteFolder(id: String) {
        val folder = dao.folder(id) ?: return
        // The default folder is where comics with no folder live; removing it would orphan them.
        if (!folder.removable) return
        transaction {
            dao.moveEntriesTo(fromFolderId = id, toFolderId = DEFAULT_FOLDER_ID)
            dao.deleteFolder(id)
        }
    }

    override suspend fun add(ref: ComicRef, folderId: String, snapshot: ComicSnapshot) {
        // A comic filed into a folder that no longer exists goes to the default one rather than
        // disappearing: the row is what the user asked to keep.
        val target = dao.folder(folderId)?.folderId ?: DEFAULT_FOLDER_ID
        val existing = dao.entry(ref.refSource(), ref.refComic())
        dao.upsertEntry(
            FavoriteEntryEntity(
                refSource = ref.refSource(),
                refComic = ref.refComic(),
                folderId = target,
                title = snapshot.title,
                subtitle = snapshot.subtitle,
                coverRef = snapshot.coverRef,
                addedAt = existing?.addedAt ?: clock(),
                lastReadAt = existing?.lastReadAt,
                chapterCount = snapshot.chapterCount ?: existing?.chapterCount,
                latestChapterId = snapshot.latestChapterId ?: existing?.latestChapterId,
                hasUpdate = existing?.hasUpdate ?: false,
                updatedAt = existing?.updatedAt,
            ),
        )
    }

    override suspend fun remove(ref: ComicRef) {
        dao.deleteEntry(ref.refSource(), ref.refComic())
    }

    override suspend fun moveTo(ref: ComicRef, folderId: String) {
        if (dao.folder(folderId) == null) return
        dao.moveEntry(refSource = ref.refSource(), refComic = ref.refComic(), folderId = folderId)
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
    override suspend fun refreshUpdates(): Int {
        val now = clock()
        var marked = 0
        for (entity in dao.entries()) {
            val item = entity.toDomainOrNull() ?: continue
            // An imported comic has no remote to ask.
            if (item.ref !is ComicRef.Remote) continue
            val source = entity.refSource
            val comic = entity.refComic
            when (val state = updateMarker.evaluate(item)) {
                UpdateState.Unknown -> Unit

                is UpdateState.Unchanged -> {
                    if (entity.differsFrom(state.snapshot)) {
                        dao.applySnapshot(
                            refSource = source,
                            refComic = comic,
                            chapterCount = state.snapshot.chapterCount,
                            latestChapterId = state.snapshot.latestChapterId,
                            hasUpdate = entity.hasUpdate,
                            updatedAt = entity.updatedAt ?: 0L,
                        )
                    }
                }

                is UpdateState.Updated -> {
                    dao.applySnapshot(
                        refSource = source,
                        refComic = comic,
                        chapterCount = state.snapshot.chapterCount,
                        latestChapterId = state.snapshot.latestChapterId,
                        hasUpdate = true,
                        updatedAt = now,
                    )
                    marked++
                }
            }
        }
        return marked
    }

    private fun FavoriteEntryEntity.differsFrom(snapshot: ChapterSnapshot): Boolean =
        chapterCount != snapshot.chapterCount || latestChapterId != snapshot.latestChapterId
}

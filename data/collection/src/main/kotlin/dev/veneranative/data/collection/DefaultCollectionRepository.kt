package dev.veneranative.data.collection

import androidx.room.withTransaction
import dev.veneranative.core.database.DEFAULT_FOLDER_ID
import dev.veneranative.core.database.FavoriteDao
import dev.veneranative.core.database.FavoriteEntryEntity
import dev.veneranative.core.database.FavoriteFolderEntity
import dev.veneranative.core.database.FavoriteMembershipEntity
import dev.veneranative.core.database.VeneraDatabase
import dev.veneranative.core.model.ComicRef
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
        }.combine(dao.observeMemberships()) { rows, memberships ->
            val grouped = memberships.groupBy { it.refSource to it.refComic }
            rows.mapNotNull { row -> row.toDomainOrNull()?.copy(
                folderIds = grouped[row.refSource to row.refComic]?.map { it.folderId }?.toSet().orEmpty(),
            ) }
        }

    override fun observeItem(ref: ComicRef): Flow<FavoriteItem?> =
        dao.observeEntry(ref.refSource(), ref.refComic()).combine(dao.observeMemberships()) { row, memberships ->
            row?.toDomainOrNull()?.copy(folderIds = memberships.asSequence()
                .filter { it.refSource == ref.refSource() && it.refComic == ref.refComic() }
                .map { it.folderId }.toSet())
        }

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
            ),
            )
            setFolders(ref, folderIds)
        }
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

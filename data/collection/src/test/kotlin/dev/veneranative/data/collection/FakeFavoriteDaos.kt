package dev.veneranative.data.collection

import dev.veneranative.core.database.FavoriteDao
import dev.veneranative.core.database.FavoriteEntryEntity
import dev.veneranative.core.database.FavoriteFolderEntity
import dev.veneranative.core.model.ComicKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory stand-ins for the shelf DAO and the remote probe, so repository and marker logic can run
 * on the JVM.
 *
 * They reproduce what the real queries do — the four orders included — because the ordering is the
 * behaviour under test, not an implementation detail of SQLite. What they cannot reproduce is
 * checked by `FavoriteDaoTest` on a real database.
 */
internal class FakeFavoriteDao : FavoriteDao {

    val folders = MutableStateFlow<List<FavoriteFolderEntity>>(emptyList())
    val entries = MutableStateFlow<List<FavoriteEntryEntity>>(emptyList())

    override fun observeFolders(): Flow<List<FavoriteFolderEntity>> = folders.map { rows ->
        rows.sortedWith(compareBy<FavoriteFolderEntity> { it.sortOrder }.thenBy { it.name })
    }

    override suspend fun folder(folderId: String): FavoriteFolderEntity? =
        folders.value.firstOrNull { it.folderId == folderId }

    override suspend fun maxFolderSortOrder(): Int? = folders.value.maxOfOrNull { it.sortOrder }

    override suspend fun insertFolder(folder: FavoriteFolderEntity) {
        folders.value = folders.value.filterNot { it.folderId == folder.folderId } + folder
    }

    override suspend fun renameFolder(folderId: String, name: String) {
        folders.value = folders.value.map { if (it.folderId == folderId) it.copy(name = name) else it }
    }

    override suspend fun deleteFolder(folderId: String) {
        folders.value = folders.value.filterNot { it.folderId == folderId }
    }

    override fun observeByAddedAt(folderId: String?): Flow<List<FavoriteEntryEntity>> =
        entries.map { rows -> rows.visible(folderId).sortedByDescending { it.addedAt } }

    override fun observeByTitle(folderId: String?): Flow<List<FavoriteEntryEntity>> =
        entries.map { rows ->
            rows.visible(folderId)
                .sortedWith(compareBy<FavoriteEntryEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.title })
        }

    override fun observeByLastRead(folderId: String?): Flow<List<FavoriteEntryEntity>> =
        entries.map { rows ->
            rows.visible(folderId).sortedWith(
                compareByDescending<FavoriteEntryEntity> { it.lastReadAt ?: 0L }
                    .thenByDescending { it.addedAt },
            )
        }

    override fun observeByUpdate(folderId: String?): Flow<List<FavoriteEntryEntity>> =
        entries.map { rows ->
            rows.visible(folderId).sortedWith(
                compareByDescending<FavoriteEntryEntity> { if (it.hasUpdate) 1 else 0 }
                    .thenByDescending { it.updatedAt ?: 0L }
                    .thenByDescending { it.addedAt },
            )
        }

    override suspend fun upsertEntry(entry: FavoriteEntryEntity) {
        entries.value = entries.value.filterNot { it.isSameComicAs(entry) } + entry
    }

    override suspend fun entry(refSource: String, refComic: String): FavoriteEntryEntity? =
        entries.value.firstOrNull { it.refSource == refSource && it.refComic == refComic }

    override suspend fun entries(): List<FavoriteEntryEntity> = entries.value

    override suspend fun deleteEntry(refSource: String, refComic: String) {
        entries.value = entries.value.filterNot { it.refSource == refSource && it.refComic == refComic }
    }

    override suspend fun moveEntry(refSource: String, refComic: String, folderId: String) {
        entries.value = entries.value.map { row ->
            if (row.refSource == refSource && row.refComic == refComic) row.copy(folderId = folderId) else row
        }
    }

    override suspend fun moveEntriesTo(fromFolderId: String, toFolderId: String) {
        entries.value = entries.value.map { row ->
            if (row.folderId == fromFolderId) row.copy(folderId = toFolderId) else row
        }
    }

    override suspend fun clearUpdate(refSource: String, refComic: String) {
        entries.value = entries.value.map { row ->
            if (row.refSource == refSource && row.refComic == refComic) row.copy(hasUpdate = false) else row
        }
    }

    override suspend fun applySnapshot(
        refSource: String,
        refComic: String,
        chapterCount: Int?,
        latestChapterId: String?,
        hasUpdate: Boolean,
        updatedAt: Long,
    ) {
        entries.value = entries.value.map { row ->
            if (row.refSource == refSource && row.refComic == refComic) {
                row.copy(
                    chapterCount = chapterCount,
                    latestChapterId = latestChapterId,
                    hasUpdate = hasUpdate,
                    updatedAt = updatedAt,
                )
            } else {
                row
            }
        }
    }

    private fun List<FavoriteEntryEntity>.visible(folderId: String?): List<FavoriteEntryEntity> =
        if (folderId == null) this else filter { it.folderId == folderId }

    private fun FavoriteEntryEntity.isSameComicAs(other: FavoriteEntryEntity) =
        refSource == other.refSource && refComic == other.refComic
}

/** Answers with whatever a test registered; a missing key means "the source could not answer". */
internal class FakeRemoteChapterProbe : RemoteChapterProbe {

    private val snapshots = mutableMapOf<ComicKey, ChapterSnapshot?>()
    var calls: Int = 0
        private set

    operator fun set(comicKey: ComicKey, snapshot: ChapterSnapshot?) {
        snapshots[comicKey] = snapshot
    }

    override suspend fun chapterSnapshot(comicKey: ComicKey): ChapterSnapshot? {
        calls++
        return snapshots[comicKey]
    }
}

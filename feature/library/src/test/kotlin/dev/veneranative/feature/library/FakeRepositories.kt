package dev.veneranative.feature.library

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.collection.ComicSnapshot
import dev.veneranative.data.collection.FavoriteFolder
import dev.veneranative.data.collection.FavoriteItem
import dev.veneranative.data.collection.ShelfSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * An in-memory shelf, so the screen's behaviour can be checked on the JVM.
 *
 * It counts how many times [observeItems] was called because "a new folder or sort means exactly one
 * new query" is a real requirement: a screen that re-sorted a list it already held would pass every
 * assertion about what is shown while quietly breaking the "the database is the only copy" rule.
 */
internal class FakeCollectionRepository : CollectionRepository {

    val folders = MutableStateFlow<List<FavoriteFolder>>(emptyList())
    val items = MutableStateFlow<List<FavoriteItem>>(emptyList())

    var observeItemsError: Throwable? = null
    var refreshUpdatesResult: Int = 0
    var refreshUpdatesError: Throwable? = null

    val observeItemQueries = mutableListOf<Pair<String?, ShelfSort>>()
    val createdFolders = mutableListOf<String>()
    val renamedFolders = mutableListOf<Pair<String, String>>()
    val deletedFolders = mutableListOf<String>()
    val removed = mutableListOf<ComicRef>()
    val moved = mutableListOf<Pair<ComicRef, String>>()
    val clearedUpdates = mutableListOf<ComicRef>()

    override fun observeFolders(): Flow<List<FavoriteFolder>> = folders

    override fun observeItems(folderId: String?, sort: ShelfSort): Flow<List<FavoriteItem>> = flow {
        observeItemQueries += folderId to sort
        val error = observeItemsError
        if (error != null) throw error
        emitAll(items.map { rows -> if (folderId == null) rows else rows.filter { it.folderId == folderId } })
    }

    override fun observeItem(ref: ComicRef): Flow<FavoriteItem?> =
        items.map { rows -> rows.firstOrNull { it.ref == ref } }

    override suspend fun createFolder(name: String): String {
        createdFolders += name
        val id = "folder-${createdFolders.size}"
        folders.value = folders.value + FavoriteFolder(id = id, name = name, sortOrder = folders.value.size, removable = true)
        return id
    }

    override suspend fun renameFolder(id: String, name: String) {
        renamedFolders += id to name
        folders.value = folders.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun deleteFolder(id: String) {
        deletedFolders += id
        folders.value = folders.value.filterNot { it.id == id }
    }

    override suspend fun add(ref: ComicRef, folderId: String, snapshot: ComicSnapshot) = Unit

    override suspend fun remove(ref: ComicRef) {
        removed += ref
    }

    override suspend fun moveTo(ref: ComicRef, folderId: String) {
        moved += ref to folderId
    }

    override suspend fun clearUpdate(ref: ComicRef) {
        clearedUpdates += ref
    }

    override suspend fun refreshUpdates(): Int {
        refreshUpdatesError?.let { throw it }
        return refreshUpdatesResult
    }
}

internal fun comicRef(comicId: String): ComicRef =
    ComicRef.Remote(ComicKey(SourceId("source-a"), RemoteComicId(comicId)))

internal fun favoriteItem(
    comicId: String,
    title: String = comicId,
    folderId: String = "default",
    hasUpdate: Boolean = false,
): FavoriteItem = FavoriteItem(
    ref = comicRef(comicId),
    title = title,
    folderId = folderId,
    addedAtEpochMillis = 1_000L,
    hasUpdate = hasUpdate,
)

internal fun favoriteFolder(
    id: String,
    name: String = id,
    removable: Boolean = true,
): FavoriteFolder = FavoriteFolder(id = id, name = name, sortOrder = 0, removable = removable)

package dev.veneranative.feature.details

import dev.veneranative.core.model.ComicRef
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.collection.ComicSnapshot
import dev.veneranative.data.collection.FavoriteFolder
import dev.veneranative.data.collection.FavoriteItem
import dev.veneranative.data.collection.ShelfSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory shelf for the details screen's tests.
 *
 * It answers [observeItem] from what was actually written, so "the screen shows kept" and "the shelf
 * holds the comic" are checked against the same thing instead of against a flag the screen set.
 */
internal class FakeCollectionRepository : CollectionRepository {

    private val entries = MutableStateFlow<Map<ComicRef, FavoriteItem>>(emptyMap())

    var addError: Throwable? = null
    var removeError: Throwable? = null

    val added = mutableListOf<AddedToShelf>()
    val removed = mutableListOf<ComicRef>()

    override fun observeFolders(): Flow<List<FavoriteFolder>> = MutableStateFlow(emptyList())

    override fun observeItems(folderId: String?, sort: ShelfSort): Flow<List<FavoriteItem>> =
        MutableStateFlow(emptyList())

    override fun observeItem(ref: ComicRef): Flow<FavoriteItem?> = entries.map { it[ref] }

    override suspend fun createFolder(name: String): String = error("not used by the details screen")

    override suspend fun renameFolder(id: String, name: String) = Unit

    override suspend fun deleteFolder(id: String) = Unit

    override suspend fun add(ref: ComicRef, folderId: String, snapshot: ComicSnapshot) {
        addError?.let { throw it }
        added += AddedToShelf(ref, folderId, snapshot)
        entries.value = entries.value + (
            ref to FavoriteItem(
                ref = ref,
                title = snapshot.title,
                subtitle = snapshot.subtitle,
                coverRef = snapshot.coverRef,
                folderId = folderId,
                addedAtEpochMillis = 1_000L,
                chapterCount = snapshot.chapterCount,
                latestChapterId = snapshot.latestChapterId,
            )
        )
    }

    override suspend fun remove(ref: ComicRef) {
        removeError?.let { throw it }
        removed += ref
        entries.value = entries.value - ref
    }

    override suspend fun moveTo(ref: ComicRef, folderId: String) = Unit

    override suspend fun clearUpdate(ref: ComicRef) = Unit

    override suspend fun refreshUpdates(): Int = 0

    /** What a later screen would find on the shelf. */
    fun contains(ref: ComicRef): Boolean = entries.value.containsKey(ref)

    /** Puts a comic on the shelf the way a previous visit would have. */
    fun seed(item: FavoriteItem) {
        entries.value = entries.value + (item.ref to item)
    }
}

internal data class AddedToShelf(
    val ref: ComicRef,
    val folderId: String,
    val snapshot: ComicSnapshot,
)

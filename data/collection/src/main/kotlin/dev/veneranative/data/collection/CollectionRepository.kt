package dev.veneranative.data.collection

import dev.veneranative.core.model.ComicRef
import kotlinx.coroutines.flow.Flow

/** A folder the user files favourites into. */
data class FavoriteFolder(
    val id: String,
    val name: String,
    val sortOrder: Int,
    /** False only for the seeded default folder, which is what keeps comics from being orphaned. */
    val removable: Boolean,
)

/** One comic on the shelf. */
data class FavoriteItem(
    val ref: ComicRef,
    val title: String,
    val subtitle: String? = null,
    /** Remote cover URL, or the local reference of an imported comic's cover. */
    val coverRef: String? = null,
    val folderId: String,
    val addedAtEpochMillis: Long,
    val lastReadAtEpochMillis: Long? = null,
    /** Chapter count of the last snapshot an update check took. */
    val chapterCount: Int? = null,
    val latestChapterId: String? = null,
    val hasUpdate: Boolean = false,
)

/** How the shelf orders comics. The database answers with the rows already in this order. */
enum class ShelfSort { AddedAt, Title, LastRead, Updated }

/**
 * What the caller knew about a comic when it was added to the shelf.
 *
 * Passing it in keeps the repository from calling a source behind the caller's back: whoever shows
 * the "add to shelf" button already loaded the comic and its chapters.
 */
data class ComicSnapshot(
    val title: String,
    val subtitle: String? = null,
    val coverRef: String? = null,
    val chapterCount: Int? = null,
    val latestChapterId: String? = null,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
    }
}

/**
 * The user's shelf: folders, the comics in them and whether those comics changed upstream.
 *
 * Room is the only place this lives. Everything the UI shows is a `Flow` of rows, so a process
 * restart, a background refresh and a change made on another screen all arrive the same way, and
 * no screen has to keep its own copy of the collection in sync.
 */
interface CollectionRepository {

    /** All folders, in their display order. */
    fun observeFolders(): Flow<List<FavoriteFolder>>

    /** Favourites of one folder, or of every folder when [folderId] is null, already sorted. */
    fun observeItems(folderId: String?, sort: ShelfSort): Flow<List<FavoriteItem>>

    /** The new folder's id. */
    suspend fun createFolder(name: String): String

    suspend fun renameFolder(id: String, name: String)

    /** Deleting a folder keeps its comics: they fall back to the default folder. */
    suspend fun deleteFolder(id: String)

    /** Adds a comic, or refreshes its metadata when it is already on the shelf. */
    suspend fun add(ref: ComicRef, folderId: String, snapshot: ComicSnapshot)

    suspend fun remove(ref: ComicRef)

    suspend fun moveTo(ref: ComicRef, folderId: String)

    suspend fun clearUpdate(ref: ComicRef)

    /** Asks each remote comic's source for its chapters; returns how many gained chapters. */
    suspend fun refreshUpdates(): Int
}

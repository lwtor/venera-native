package dev.veneranative.feature.library

import dev.veneranative.core.model.ComicRef
import dev.veneranative.data.collection.FavoriteFolder
import dev.veneranative.data.collection.FavoriteItem
import dev.veneranative.data.collection.ShelfSort
import dev.veneranative.data.local.LocalComic
import dev.veneranative.data.local.LocalChapter
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.data.download.DownloadTask
import dev.veneranative.core.model.ChapterRef

/** A folder the user is naming or renaming; [folderId] is null while creating a new one. */
data class FolderEditor(
    val folderId: String?,
    val draft: String,
)

/**
 * The four states the shelf can be in.
 *
 * `Empty` is its own state rather than "ready with no rows": the shelf has to be able to say
 * "nothing here yet" instead of showing a blank grid that looks broken.
 */
enum class LibraryStatus { Loading, Empty, Ready, Failed }
enum class LibraryTab { Favorites, Downloads, Local }

/** Everything the library screen needs to render. */
data class LibraryUiState(
    val status: LibraryStatus = LibraryStatus.Loading,
    val tab: LibraryTab = LibraryTab.Favorites,
    val localComics: List<LocalComic> = emptyList(),
    val localChapters: Map<LocalComicId, List<LocalChapter>> = emptyMap(),
    val downloads: List<DownloadTask> = emptyList(),
    /** Increments after a resume or retry has reached persistent storage. */
    val downloadQueueVersion: Int = 0,
    val folders: List<FavoriteFolder> = emptyList(),
    /** Null shows every folder. */
    val selectedFolderId: String? = null,
    val sort: ShelfSort = ShelfSort.AddedAt,
    val items: List<FavoriteItem> = emptyList(),
    /** Set while the create/rename dialog is open. */
    val folderEditor: FolderEditor? = null,
    /** Product copy for the last outcome; never a lower layer's wording. */
    val message: String? = null,
)

/** What the user did. Actions name intent, not the storage call behind it. */
sealed interface LibraryAction {

    /** Null selects every folder. */
    data class SelectFolder(val folderId: String?) : LibraryAction

    data class ChangeSort(val sort: ShelfSort) : LibraryAction

    /** Opens the create dialog when [folderId] is null, otherwise the rename dialog. */
    data class EditFolder(val folderId: String?) : LibraryAction

    data class FolderDraftChanged(val draft: String) : LibraryAction

    data object ConfirmFolderEditor : LibraryAction

    data object DismissFolderEditor : LibraryAction

    data class DeleteFolder(val folderId: String) : LibraryAction

    data class RemoveItem(val ref: ComicRef) : LibraryAction

    data class MoveItem(val ref: ComicRef, val folderId: String) : LibraryAction

    data class ClearUpdate(val ref: ComicRef) : LibraryAction

    data object RefreshUpdates : LibraryAction

    data object Retry : LibraryAction

    data class SelectTab(val tab: LibraryTab) : LibraryAction

    data object RequestLocalImport : LibraryAction
    data object RequestArchiveImport : LibraryAction
    data class PauseDownload(val chapter: ChapterRef) : LibraryAction
    data class ResumeDownload(val chapter: ChapterRef) : LibraryAction
    data class CancelDownload(val chapter: ChapterRef) : LibraryAction
    data class RetryDownload(val chapter: ChapterRef) : LibraryAction

    data class ImportTree(val uri: String) : LibraryAction
    data class ImportArchive(val uri: String) : LibraryAction

    data class RemoveLocalComic(val id: dev.veneranative.core.model.LocalComicId) : LibraryAction

    data object DismissMessage : LibraryAction
}

package dev.veneranative.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.collection.CollectionNameConflictException
import dev.veneranative.data.collection.ShelfSort
import dev.veneranative.data.local.LocalComicRepository
import dev.veneranative.data.local.LocalImportResult
import dev.veneranative.data.download.DownloadRepository
import dev.veneranative.data.settings.ScreenPreferenceRepository
import dev.veneranative.core.model.ChapterRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the shelf screen.
 *
 * It keeps no collection of its own: folders and comics are observed from the repository, and a
 * changed folder or sort order re-subscribes to a query rather than re-sorting a list the screen
 * happens to be holding. That is what makes a change made anywhere else show up here, and what makes
 * the shelf identical after the process is recreated.
 *
 * Failures become product copy here, as everywhere else: an unreadable shelf is something the user
 * can retry, and "no comics yet" is a different answer from "the shelf could not be read".
 */
class LibraryViewModel(
    private val repository: CollectionRepository,
    private val localRepository: LocalComicRepository? = null,
    private val downloads: DownloadRepository? = null,
    screenPreferences: ScreenPreferenceRepository? = null,
    private val initialTab: LibraryTab = LibraryTab.Favorites,
) : ViewModel() {

    private var screenPreferences: ScreenPreferenceRepository? = screenPreferences

    private val _state = MutableStateFlow(LibraryUiState(tab = initialTab))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** The current query. Distinct values only, so re-selecting the same folder costs nothing. */
    private val selection = MutableStateFlow(LibrarySelection())
    private var itemsJob: Job? = null
    private var folderJob: Job? = null
    private var preferenceRestoreJob: Job? = null

    init {
        restorePreferences()
        observeLocalComics()
        observeDownloads()
    }

    fun attachScreenPreferences(repository: ScreenPreferenceRepository) {
        if (screenPreferences === repository) return
        screenPreferences = repository
        restorePreferences()
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            is LibraryAction.SelectFolder -> selectFolder(action.folderId)
            is LibraryAction.ChangeSort -> changeSort(action.sort)

            is LibraryAction.EditFolder -> openFolderEditor(action.folderId)
            is LibraryAction.FolderDraftChanged ->
                _state.update { it.copy(folderEditor = it.folderEditor?.copy(draft = action.draft)) }

            LibraryAction.ConfirmFolderEditor -> confirmFolderEditor()
            LibraryAction.DismissFolderEditor -> _state.update {
                if (it.folderSaving) it else it.copy(folderEditor = null)
            }
            is LibraryAction.RequestDeleteFolder -> _state.update { current ->
                current.copy(pendingDeleteFolderId = action.folderId.takeIf { id ->
                    current.folders.any { it.id == id && it.removable }
                })
            }
            LibraryAction.DismissDeleteFolder -> _state.update { it.copy(pendingDeleteFolderId = null) }
            LibraryAction.ConfirmDeleteFolder -> {
                val id = _state.value.pendingDeleteFolderId ?: return
                _state.update { it.copy(pendingDeleteFolderId = null) }
                runSafely { repository.deleteFolder(id) }
            }

            LibraryAction.RequestRemoveFavorite -> _state.update { it.copy(confirmRemoveFavorite = it.selectedFavorite != null) }
            LibraryAction.DismissRemoveFavorite -> _state.update { it.copy(confirmRemoveFavorite = false) }
            LibraryAction.ConfirmRemoveFavorite -> {
                val ref = _state.value.selectedFavorite?.ref ?: return
                _state.update { it.copy(selectedFavorite = null, selectedFavoriteFolders = emptySet(), confirmRemoveFavorite = false) }
                runSafely { repository.remove(ref) }
            }
            is LibraryAction.ToggleFavoriteFolder -> _state.update { current ->
                if (current.folders.none { it.id == action.folderId }) current
                else current.copy(selectedFavoriteFolders = current.selectedFavoriteFolders.let { ids ->
                    if (action.folderId in ids) ids - action.folderId else ids + action.folderId
                })
            }
            LibraryAction.SaveFavoriteFolders -> {
                val ref = _state.value.selectedFavorite?.ref ?: return
                val ids = _state.value.selectedFavoriteFolders
                _state.update { it.copy(selectedFavorite = null, selectedFavoriteFolders = emptySet()) }
                runSafely { repository.setFolders(ref, ids) }
            }
            is LibraryAction.ClearUpdate -> {
                _state.update { it.copy(selectedFavorite = null) }
                runSafely { repository.clearUpdate(action.ref) }
            }
            is LibraryAction.ShowFavoriteActions -> _state.update { state ->
                val item = state.items.firstOrNull { it.ref == action.ref }
                state.copy(selectedFavorite = item, selectedFavoriteFolders = item?.folderIds.orEmpty(), confirmRemoveFavorite = false)
            }
            LibraryAction.DismissFavoriteActions -> _state.update {
                it.copy(selectedFavorite = null, selectedFavoriteFolders = emptySet(), confirmRemoveFavorite = false)
            }

            LibraryAction.RefreshUpdates -> refreshUpdates()
            LibraryAction.ToggleFavoriteSearch -> _state.update {
                it.copy(
                    favoriteSearchVisible = !it.favoriteSearchVisible,
                    favoriteQuery = if (it.favoriteSearchVisible) "" else it.favoriteQuery,
                )
            }
            is LibraryAction.FavoriteQueryChanged -> _state.update { it.copy(favoriteQuery = action.query) }
            LibraryAction.Retry -> startItems()
            is LibraryAction.SelectTab -> {
                _state.update { it.copy(
                    tab = action.tab,
                    favoriteSearchVisible = false,
                    favoriteQuery = "",
                    selectedFavorite = null,
                    downloadComicKey = null,
                    downloadSelectionMode = false,
                    selectedDownloadChapters = emptySet(),
                    confirmRemoveDownloads = false,
                ) }
            }
            LibraryAction.RequestLocalImport -> Unit
            LibraryAction.RequestArchiveImport -> Unit
            is LibraryAction.ImportTree -> importTree(action.uri)
            is LibraryAction.RemoveLocalComic -> localRepository?.let { repo -> runSafely { repo.remove(action.id) } }
            is LibraryAction.ImportArchive -> importArchive(action.uri)
            is LibraryAction.PauseDownload -> downloadTask { it.pause(action.chapter) }
            is LibraryAction.ResumeDownload -> downloadTask(scheduleOnSuccess = true) { it.resume(action.chapter) }
            is LibraryAction.CancelDownload -> downloadTask { it.cancel(action.chapter) }
            is LibraryAction.RetryDownload -> downloadTask(scheduleOnSuccess = true) { it.retryFailed(action.chapter) }
            is LibraryAction.OpenDownloadedComic -> _state.update { current ->
                if (current.downloads.any { DownloadComicGroupKey.from(it.chapter) == action.comicKey }) {
                    current.copy(downloadComicKey = action.comicKey, downloadSelectionMode = false, selectedDownloadChapters = emptySet())
                } else current
            }
            LibraryAction.BackFromDownloadedComic -> _state.update {
                it.copy(downloadComicKey = null, downloadSelectionMode = false, selectedDownloadChapters = emptySet())
            }
            LibraryAction.ToggleDownloadSelectionMode -> _state.update {
                it.copy(
                    downloadSelectionMode = !it.downloadSelectionMode,
                    selectedDownloadChapters = emptySet(),
                    confirmRemoveDownloads = false,
                )
            }
            is LibraryAction.ToggleDownloadComicSelection -> _state.update { current ->
                val chapters = current.downloads
                    .filter { DownloadComicGroupKey.from(it.chapter) == action.comicKey }
                    .map { it.chapter }
                    .toSet()
                current.copy(selectedDownloadChapters = current.selectedDownloadChapters.let { selected ->
                    if (chapters.isNotEmpty() && chapters.all { it in selected }) selected - chapters else selected + chapters
                })
            }
            is LibraryAction.ToggleDownloadChapterSelection -> _state.update { current ->
                if (current.downloads.none { it.chapter == action.chapter }) current
                else current.copy(selectedDownloadChapters = current.selectedDownloadChapters.let { selected ->
                    if (action.chapter in selected) selected - action.chapter else selected + action.chapter
                })
            }
            LibraryAction.RequestRemoveSelectedDownloads -> _state.update {
                it.copy(confirmRemoveDownloads = it.selectedDownloadChapters.isNotEmpty())
            }
            LibraryAction.DismissRemoveSelectedDownloads -> _state.update { it.copy(confirmRemoveDownloads = false) }
            LibraryAction.ConfirmRemoveSelectedDownloads -> removeSelectedDownloads()
            LibraryAction.DismissMessage -> _state.update { it.copy(message = null) }
        }
    }

    private fun observeLocalComics() {
        val local = localRepository ?: return
        viewModelScope.launch {
            try {
                local.observeComics().collect { comics ->
                    val chapters = comics.associate { comic -> comic.id to local.observeChapters(comic.id).first() }
                    _state.update { it.copy(localComics = comics, localChapters = chapters) }
                }
            }
            catch (failure: Throwable) { failUnlessCancelled(failure); _state.update { it.copy(message = "无法读取本地文件夹。") } }
        }
    }

    private fun observeDownloads() {
        val repo = downloads ?: return
        viewModelScope.launch {
            repo.observeTasks().collect { tasks -> _state.update { it.copy(downloads = tasks) } }
        }
    }

    private fun downloadTask(
        scheduleOnSuccess: Boolean = false,
        operation: suspend (DownloadRepository) -> Unit,
    ) {
        val repo = downloads ?: return
        viewModelScope.launch {
            try {
                operation(repo)
                if (scheduleOnSuccess) _state.update { it.copy(downloadQueueVersion = it.downloadQueueVersion + 1) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(message = "无法更新下载任务。") }
            }
        }
    }

    private fun removeSelectedDownloads() {
        val repo = downloads ?: return
        val selected = _state.value.selectedDownloadChapters
        if (selected.isEmpty()) return
        _state.update { it.copy(confirmRemoveDownloads = false) }
        viewModelScope.launch {
            val failed = linkedSetOf<ChapterRef>()
            for (chapter in selected) {
                try {
                    repo.cancel(chapter)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed += chapter
                }
            }
            _state.update { current ->
                if (failed.isEmpty()) {
                    current.copy(
                        downloadSelectionMode = false,
                        selectedDownloadChapters = emptySet(),
                        message = null,
                    )
                } else {
                    current.copy(
                        downloadSelectionMode = true,
                        selectedDownloadChapters = failed,
                        message = "${selected.size - failed.size} 项已移除，${failed.size} 项移除失败，请重试。",
                    )
                }
            }
        }
    }

    private fun importArchive(uri: String) {
        val repo = localRepository ?: return
        viewModelScope.launch {
            when (val result = repo.importArchive(uri)) {
                is LocalImportResult.Imported -> _state.update { it.copy(message = "压缩包已导入。") }
                LocalImportResult.Empty -> _state.update { it.copy(message = "压缩包中没有找到可读取的图片。") }
                LocalImportResult.PermissionLost -> _state.update { it.copy(message = "未获得压缩包访问权限，请重新选择。") }
                LocalImportResult.Unavailable -> _state.update { it.copy(message = "无法读取压缩包，请确认文件为受支持的 ZIP 或 7z 格式。") }
            }
        }
    }

    private fun importTree(uri: String) {
        val local = localRepository ?: return
        viewModelScope.launch {
            val result = try {
                local.importTree(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LocalImportResult.Unavailable
            }
            when (result) {
                is LocalImportResult.Imported -> _state.update { it.copy(message = "文件夹已导入。") }
                LocalImportResult.Empty -> _state.update { it.copy(message = "文件夹中没有找到可读取的图片。") }
                LocalImportResult.PermissionLost -> _state.update { it.copy(message = "未获得文件夹访问权限，请重新选择。") }
                LocalImportResult.Unavailable -> _state.update { it.copy(message = "无法导入此文件夹。") }
            }
        }
    }

    private fun observeFolders() {
        if (folderJob?.isActive == true) return
        folderJob = viewModelScope.launch {
            runCatching {
                repository.observeFolders().collect { folders ->
                    val selected = _state.value.selectedFolderId?.takeIf { id -> folders.any { it.id == id } }
                    if (selected != _state.value.selectedFolderId) {
                        _state.update { it.copy(folders = folders, selectedFolderId = selected) }
                        selection.update { it.copy(folderId = selected) }
                        persist(PREF_FOLDER, selected ?: ALL_FOLDERS)
                    } else {
                        _state.update { it.copy(folders = folders) }
                    }
                }
            }.onFailure { failure -> failUnlessCancelled(failure) }
        }
    }

    private fun restorePreferences() {
        preferenceRestoreJob?.cancel()
        preferenceRestoreJob = viewModelScope.launch {
            val preferences = screenPreferences
            val storedFolder = runCatching { preferences?.get(PREF_FOLDER) }.getOrNull()
            val folderId = storedFolder?.takeUnless { it == ALL_FOLDERS }
            val sort = runCatching { preferences?.get(PREF_SORT) }.getOrNull()?.let { value ->
                ShelfSort.entries.firstOrNull { it.name == value }
            } ?: ShelfSort.AddedAt
            selection.value = LibrarySelection(folderId, sort)
            _state.update { it.copy(tab = initialTab, selectedFolderId = folderId, sort = sort) }
            observeFolders()
            startItems()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startItems() {
        itemsJob?.cancel()
        itemsJob = viewModelScope.launch {
            _state.update { it.copy(status = LibraryStatus.Loading) }
            runCatching {
                selection
                    // A StateFlow only emits values that changed, so re-selecting the same folder
                    // never re-runs the query below.
                    .flatMapLatest { (folderId, sort) -> repository.observeItems(folderId, sort) }
                    .collect { items ->
                        _state.update { current ->
                            current.copy(
                                status = if (items.isEmpty()) LibraryStatus.Empty else LibraryStatus.Ready,
                                items = items,
                            )
                        }
                    }
            }.onFailure { failure ->
                failUnlessCancelled(failure)
                _state.update { current ->
                    current.copy(status = LibraryStatus.Failed, message = "无法读取书架内容。")
                }
            }
        }
    }

    private fun selectFolder(folderId: String?) {
        if (_state.value.selectedFolderId == folderId) return
        _state.update { it.copy(selectedFolderId = folderId) }
        selection.update { it.copy(folderId = folderId) }
        persist(PREF_FOLDER, folderId ?: ALL_FOLDERS)
    }

    private fun changeSort(sort: ShelfSort) {
        if (_state.value.sort == sort) return
        _state.update { it.copy(sort = sort) }
        selection.update { it.copy(sort = sort) }
        persist(PREF_SORT, sort.name)
    }

    private fun persist(key: String, value: String) {
        val preferences = screenPreferences ?: return
        viewModelScope.launch { runCatching { preferences.put(key, value) } }
    }

    private fun openFolderEditor(folderId: String?) {
        if (_state.value.folderSaving) return
        val draft = _state.value.folders.firstOrNull { it.id == folderId }?.name.orEmpty()
        _state.update { it.copy(folderEditor = FolderEditor(folderId = folderId, draft = draft), message = null) }
    }

    private fun confirmFolderEditor() {
        val editor = _state.value.folderEditor ?: return
        if (_state.value.folderSaving) return
        val name = editor.draft.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(message = "请输入收藏夹名称。") }
            return
        }
        _state.update { it.copy(folderSaving = true, message = null) }
        viewModelScope.launch {
            runCatching {
                if (editor.folderId == null) repository.createFolder(name)
                else {
                    repository.renameFolder(editor.folderId, name)
                    null
                }
            }.onSuccess { createdId ->
                _state.update { current ->
                    current.copy(
                        folderEditor = null,
                        folderSaving = false,
                        message = null,
                        selectedFavoriteFolders = if (createdId != null && current.selectedFavorite != null) {
                            current.selectedFavoriteFolders + createdId
                        } else current.selectedFavoriteFolders,
                    )
                }
            }.onFailure { failure ->
                failUnlessCancelled(failure)
                _state.update { current ->
                    current.copy(
                        folderSaving = false,
                        message = if (failure is CollectionNameConflictException) {
                            "已有同名收藏夹，请换个名称。"
                        } else {
                            "无法保存此更改。"
                        },
                    )
                }
            }
        }
    }

    private fun refreshUpdates() {
        _state.update { it.copy(message = null) }
        viewModelScope.launch {
            val marked = runCatching { repository.refreshUpdates() }.getOrNull()
            _state.update { current ->
                current.copy(
                    message = when (marked) {
                        null -> "无法检查书架更新。"
                        0 -> "没有发现新章节。"
                        1 -> "1 部漫画有新章节。"
                        else -> "$marked 部漫画有新章节。"
                    },
                )
            }
        }
    }

    /** A write the user asked for, where a failure is a message rather than a crashed screen. */
    private fun runSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { failure ->
                    failUnlessCancelled(failure)
                    _state.update { current ->
                        current.copy(
                            message = if (failure is CollectionNameConflictException) {
                                "已有同名收藏夹，请换个名称。"
                            } else {
                                "无法保存此更改。"
                            },
                        )
                    }
                }
        }
    }

    /**
     * Leaving the screen is not a failure the user should be told about, so cancellation travels on
     * instead of being reported as copy; everything else is something the user can act on.
     */
    private fun failUnlessCancelled(failure: Throwable) {
        if (failure is CancellationException) throw failure
    }

    private data class LibrarySelection(
        val folderId: String? = null,
        val sort: ShelfSort = ShelfSort.AddedAt,
    )

    private companion object {
        const val PREF_FOLDER = "library.favorite.folder"
        const val PREF_SORT = "library.favorite.sort"
        const val ALL_FOLDERS = "@all"
    }
}

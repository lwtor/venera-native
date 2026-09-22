package dev.veneranative.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.collection.ShelfSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** The current query. Distinct values only, so re-selecting the same folder costs nothing. */
    private val selection = MutableStateFlow(LibrarySelection())
    private var itemsJob: Job? = null

    init {
        observeFolders()
        startItems()
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            is LibraryAction.SelectFolder -> selectFolder(action.folderId)
            is LibraryAction.ChangeSort -> changeSort(action.sort)

            is LibraryAction.EditFolder -> openFolderEditor(action.folderId)
            is LibraryAction.FolderDraftChanged ->
                _state.update { it.copy(folderEditor = it.folderEditor?.copy(draft = action.draft)) }

            LibraryAction.ConfirmFolderEditor -> confirmFolderEditor()
            LibraryAction.DismissFolderEditor -> _state.update { it.copy(folderEditor = null) }
            is LibraryAction.DeleteFolder -> runSafely { repository.deleteFolder(action.folderId) }

            is LibraryAction.RemoveItem -> runSafely { repository.remove(action.ref) }
            is LibraryAction.MoveItem -> runSafely { repository.moveTo(action.ref, action.folderId) }
            is LibraryAction.ClearUpdate -> runSafely { repository.clearUpdate(action.ref) }

            LibraryAction.RefreshUpdates -> refreshUpdates()
            LibraryAction.Retry -> startItems()
            LibraryAction.DismissMessage -> _state.update { it.copy(message = null) }
        }
    }

    private fun observeFolders() {
        viewModelScope.launch {
            runCatching {
                repository.observeFolders().collect { folders ->
                    _state.update { it.copy(folders = folders) }
                }
            }.onFailure { failure -> failUnlessCancelled(failure) }
        }
    }

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
                    current.copy(status = LibraryStatus.Failed, message = "The shelf could not be read.")
                }
            }
        }
    }

    private fun selectFolder(folderId: String?) {
        if (_state.value.selectedFolderId == folderId) return
        _state.update { it.copy(selectedFolderId = folderId) }
        selection.update { it.copy(folderId = folderId) }
    }

    private fun changeSort(sort: ShelfSort) {
        if (_state.value.sort == sort) return
        _state.update { it.copy(sort = sort) }
        selection.update { it.copy(sort = sort) }
    }

    private fun openFolderEditor(folderId: String?) {
        val draft = _state.value.folders.firstOrNull { it.id == folderId }?.name.orEmpty()
        _state.update { it.copy(folderEditor = FolderEditor(folderId = folderId, draft = draft)) }
    }

    private fun confirmFolderEditor() {
        val editor = _state.value.folderEditor ?: return
        val name = editor.draft.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(message = "Give the folder a name.") }
            return
        }
        _state.update { it.copy(folderEditor = null) }
        runSafely {
            if (editor.folderId == null) {
                repository.createFolder(name)
            } else {
                repository.renameFolder(editor.folderId, name)
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
                        null -> "The shelf could not be checked for updates."
                        0 -> "No new chapters found."
                        1 -> "1 comic has new chapters."
                        else -> "$marked comics have new chapters."
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
                    _state.update { current -> current.copy(message = "That change could not be saved.") }
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
}

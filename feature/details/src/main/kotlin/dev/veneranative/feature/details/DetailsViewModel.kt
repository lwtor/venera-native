package dev.veneranative.feature.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.collection.ComicSnapshot
import dev.veneranative.data.collection.DEFAULT_SHELF_FOLDER_ID
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Loads one comic's details, owns the chapter list's presentation and keeps the comic on the shelf.
 *
 * Which is checked first matters: a comic whose source was uninstalled or switched off must be told
 * apart from a source that failed to answer, because only the second one is worth retrying.
 *
 * The shelf is nullable on purpose. The assembly layer builds it from the database, which is not
 * there for the first moments of the process, and a comic should still be readable while that
 * happens — so until it arrives the screen simply offers no way to keep the comic.
 */
class DetailsViewModel(
    private val catalog: ComicCatalog,
    private val comicKey: ComicKey,
    private val collection: CollectionRepository?,
) : ViewModel() {

    private val comicRef = ComicRef.Remote(comicKey)

    private val _state = MutableStateFlow(DetailsUiState(hasShelf = collection != null))
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    init {
        load()
        observeShelf()
    }

    fun onAction(action: DetailsAction) {
        when (action) {
            DetailsAction.Retry, DetailsAction.Refresh -> load()

            is DetailsAction.GroupSelected -> _state.update { it.copy(selectedGroup = action.group) }

            is DetailsAction.OrderSelected -> _state.update { it.copy(order = action.order) }

            DetailsAction.ToggleFavorite -> toggleFavorite()
        }
    }

    /**
     * Watches whether this comic is on the shelf.
     *
     * The answer comes from the shelf, not from what this screen last wrote, so removing the comic
     * from the shelf itself is visible here without the two having to know about each other.
     */
    private fun observeShelf() {
        val repository = collection ?: return
        viewModelScope.launch {
            runCatching {
                repository.observeItem(comicRef).collect { item ->
                    _state.update { it.copy(isFavorite = item != null) }
                }
            }.onFailure { failure -> failUnlessCancelled(failure) }
        }
    }

    private fun toggleFavorite() {
        val repository = collection ?: return
        // Nothing to keep before the source answered: a title is the least a shelf row must have.
        val detail = _state.value.detail ?: return
        viewModelScope.launch {
            _state.update { it.copy(shelfMessage = null) }
            runCatching {
                if (_state.value.isFavorite) {
                    repository.remove(comicRef)
                } else {
                    repository.add(comicRef, DEFAULT_SHELF_FOLDER_ID, detail.toSnapshot())
                }
            }.onFailure { failure ->
                failUnlessCancelled(failure)
                _state.update { it.copy(shelfMessage = "That change could not be saved.") }
            }
        }
    }

    private fun load() {
        _state.update { it.copy(status = DetailsStatus.Loading, message = null) }
        viewModelScope.launch {
            val source = catalog.enabledSource(comicKey.sourceId)
            if (source == null) {
                _state.update {
                    it.copy(
                        status = DetailsStatus.SourceUnavailable,
                        detail = null,
                        sourceName = null,
                        message = null,
                    )
                }
                return@launch
            }

            when (val outcome = catalog.detail(comicKey)) {
                is SourceOutcome.Success -> _state.update { current ->
                    current.copy(
                        status = DetailsStatus.Ready,
                        detail = outcome.value,
                        sourceName = source.name,
                        // A refresh can drop the group the user had selected.
                        selectedGroup = current.selectedGroup?.takeIf { group ->
                            outcome.value.chapters.any { it.group == group }
                        },
                        message = null,
                    )
                }

                is SourceOutcome.Failure -> _state.update {
                    it.copy(
                        status = DetailsStatus.Failed,
                        sourceName = source.name,
                        message = outcome.error.toDetailsMessage(),
                    )
                }
            }
        }
    }

    /**
     * Leaving the screen is not a failure the user should be told about, so cancellation travels on
     * instead of being reported as copy.
     */
    private fun failUnlessCancelled(failure: Throwable) {
        if (failure is CancellationException) throw failure
    }
}

/**
 * What the shelf is told about a comic when it is kept.
 *
 * Chapter facts are recorded only when the source actually listed chapters: a comic with none is
 * "unknown" rather than "empty", and reporting zero would make the next real answer look like an
 * update. "Last" is whatever the source ended with, exactly what an update check compares against.
 */
internal fun ComicDetail.toSnapshot(): ComicSnapshot = ComicSnapshot(
    title = comic.title,
    subtitle = comic.subtitle,
    coverRef = comic.coverUrl,
    chapterCount = chapters.size.takeIf { it > 0 },
    latestChapterId = chapters.lastOrNull()?.key?.remoteId?.value,
)

/**
 * Product copy for a failed load.
 *
 * Exhaustive on purpose, with generic wording as the fallback: a lower layer's diagnostic text is
 * written for whoever debugs the source, not for the person reading the comic.
 */
internal fun SourceRuntimeError.toDetailsMessage(): String = when (this) {
    is SourceRuntimeError.UnsupportedCapability -> "This source cannot show comic details."
    is SourceRuntimeError.SourceNotLoaded -> "That source is no longer loaded."
    is SourceRuntimeError.Timeout -> "The source took too long to answer."
    is SourceRuntimeError.Cancelled -> "Loading was cancelled."
    is SourceRuntimeError.EngineUnavailable -> "Comic sources are unavailable on this device."
    is SourceRuntimeError.EngineTerminated ->
        "The source engine stopped; open the comic again to reload it."

    is SourceRuntimeError.RuntimeClosed -> "Comic sources are unavailable on this device."

    is SourceRuntimeError.InvalidPackage,
    is SourceRuntimeError.InvalidCall,
    is SourceRuntimeError.ScriptSyntax,
    is SourceRuntimeError.ScriptExecution,
    is SourceRuntimeError.Internal,
    -> "The source could not answer."
}

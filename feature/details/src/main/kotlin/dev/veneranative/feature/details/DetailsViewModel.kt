package dev.veneranative.feature.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Loads one comic's details and owns the chapter list's presentation.
 *
 * Which is checked first matters: a comic whose source was uninstalled or switched off must be told
 * apart from a source that failed to answer, because only the second one is worth retrying.
 */
class DetailsViewModel(
    private val catalog: ComicCatalog,
    private val comicKey: ComicKey,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailsUiState())
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun onAction(action: DetailsAction) {
        when (action) {
            DetailsAction.Retry, DetailsAction.Refresh -> load()

            is DetailsAction.GroupSelected -> _state.update { it.copy(selectedGroup = action.group) }

            is DetailsAction.OrderSelected -> _state.update { it.copy(order = action.order) }
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
}

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

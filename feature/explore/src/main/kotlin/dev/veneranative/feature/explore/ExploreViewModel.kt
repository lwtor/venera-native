package dev.veneranative.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.ExplorePage
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SourceOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the explore form: which source, which of its pages, and the paged content of that page.
 *
 * Only sources that declare explore are offered, and the pages are whatever the selected source
 * declares — the app never assumes a tab exists. Changing either rebuilds the paging stream, because
 * a different page is a different list rather than more of the same one.
 */
class ExploreViewModel(
    private val catalog: ComicCatalog,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : ViewModel() {

    private val _state = MutableStateFlow(ExploreUiState())
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()

    private val page = MutableStateFlow<ExplorePage?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val results: Flow<PagingData<ExploreItem>> = page
        .flatMapLatest { current ->
            val sourceId = _state.value.selectedSourceId
            if (current == null || sourceId == null) {
                flowOf(PagingData.empty())
            } else {
                val request = exploreRequest(sourceId, current)
                Pager(
                    config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
                    pagingSourceFactory = { catalog.explore(request) },
                ).flow
            }
        }
        .cachedIn(viewModelScope)

    init {
        loadSources()
    }

    fun onAction(action: ExploreAction) {
        when (action) {
            is ExploreAction.SourceSelected -> selectSource(action.sourceId)

            is ExploreAction.PageSelected -> selectPage(action.pageKey)

            ExploreAction.Retry -> loadSources()
        }
    }

    private fun loadSources() {
        _state.update { it.copy(status = ExploreStatus.Loading, message = null) }
        viewModelScope.launch {
            runCatching { catalog.explorableSources() }
                .onSuccess { sources ->
                    val current = _state.value.selectedSourceId
                    val selected = current?.takeIf { id -> sources.any { it.sourceId == id } }
                        ?: sources.firstOrNull()?.sourceId
                    _state.update {
                        it.copy(
                            status = ExploreStatus.Ready,
                            sources = sources,
                            selectedSourceId = selected,
                            pages = emptyList(),
                            selectedPageKey = null,
                            message = null,
                        )
                    }
                    page.value = null
                    selected?.let { sourceId -> loadPages(sourceId) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            status = ExploreStatus.Failed,
                            message = "The source list could not be read.",
                        )
                    }
                }
        }
    }

    private fun selectSource(sourceId: SourceId) {
        if (_state.value.selectedSourceId == sourceId) return
        _state.update {
            it.copy(
                selectedSourceId = sourceId,
                pages = emptyList(),
                selectedPageKey = null,
                message = null,
            )
        }
        page.value = null
        loadPages(sourceId)
    }

    /**
     * A source's explore pages are part of what it declares. A source whose capabilities cannot be
     * read ends up with no pages, which the screen reports as such instead of as a source failure.
     */
    private fun loadPages(sourceId: SourceId) {
        viewModelScope.launch {
            val pages = when (val outcome = catalog.capabilities(sourceId)) {
                is SourceOutcome.Success -> outcome.value.explorePages
                is SourceOutcome.Failure -> emptyList()
            }
            if (_state.value.selectedSourceId != sourceId) return@launch

            val selected = pages.firstOrNull()
            _state.update {
                it.copy(
                    pages = pages,
                    selectedPageKey = selected?.key,
                    message = if (pages.isEmpty()) "This source declares no explore pages." else null,
                )
            }
            page.value = selected
        }
    }

    private fun selectPage(pageKey: String) {
        val selected = _state.value.pages.firstOrNull { it.key == pageKey } ?: return
        if (_state.value.selectedPageKey == selected.key) return
        _state.update { it.copy(selectedPageKey = selected.key, message = null) }
        page.value = selected
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}

/**
 * The request the current selection describes.
 *
 * A pure function so the selection rules — one source, one of *its* pages — are testable without a
 * Paging presenter, which is what consumes the results.
 */
internal fun ExploreUiState.toRequest(): ExploreRequest? {
    val sourceId = selectedSourceId ?: return null
    val page = selectedPage ?: return null
    return exploreRequest(sourceId, page)
}

internal fun exploreRequest(sourceId: SourceId, page: ExplorePage): ExploreRequest =
    ExploreRequest(sourceId = sourceId, pageKey = page.key)

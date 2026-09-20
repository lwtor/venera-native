package dev.veneranative.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceFilter
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.source.api.SearchRequest
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
 * Owns the search form and the paged results.
 *
 * Only sources that declare `search` are offered, and the list is read from the catalog rather than
 * assumed: a source that cannot search must not appear in a search picker at all.
 *
 * The results are a separate stream that is rebuilt whenever a search is submitted. Loading, empty
 * and error states of the list belong to Paging, so the screen reads them from the list itself
 * instead of this state duplicating them.
 */
class SearchViewModel(
    private val catalog: ComicCatalog,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val query = MutableStateFlow<SearchRequest?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val results: Flow<PagingData<Comic>> = query
        .flatMapLatest { request ->
            if (request == null) {
                flowOf(PagingData.empty())
            } else {
                Pager(
                    config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
                    pagingSourceFactory = { catalog.search(request) },
                ).flow
            }
        }
        .cachedIn(viewModelScope)

    init {
        loadSources()
    }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.SourceSelected -> selectSource(action.sourceId)

            is SearchAction.KeywordChanged -> _state.update { it.copy(keyword = action.value) }

            SearchAction.Submit -> submit()

            SearchAction.Retry -> loadSources()
        }
    }

    private fun loadSources() {
        _state.update { it.copy(status = SearchStatus.Loading, message = null) }
        viewModelScope.launch {
            runCatching { catalog.searchableSources() }
                .onSuccess { sources ->
                    val current = _state.value.selectedSourceId
                    val selected = current?.takeIf { id -> sources.any { it.sourceId == id } }
                        ?: sources.firstOrNull()?.sourceId
                    _state.update {
                        it.copy(
                            status = SearchStatus.Ready,
                            sources = sources,
                            selectedSourceId = selected,
                            filters = emptyList(),
                            message = null,
                        )
                    }
                    selected?.let { sourceId -> loadFilters(sourceId) }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            status = SearchStatus.Failed,
                            message = "The source list could not be read.",
                        )
                    }
                }
        }
    }

    private fun selectSource(sourceId: SourceId) {
        _state.update {
            it.copy(selectedSourceId = sourceId, filters = emptyList(), message = null)
        }
        loadFilters(sourceId)
    }

    /**
     * A source's filters are part of what it declares, so they are read once per selection. A source
     * whose capabilities cannot be read simply offers no filters; that must not block searching.
     */
    private fun loadFilters(sourceId: SourceId) {
        viewModelScope.launch {
            val filters = when (val outcome = catalog.capabilities(sourceId)) {
                is SourceOutcome.Success ->
                    if (outcome.value.supports(SourceCapability.SEARCH)) {
                        outcome.value.searchFilters
                    } else {
                        emptyList()
                    }

                is SourceOutcome.Failure -> emptyList()
            }
            // The selection may have moved on while this was in flight.
            _state.update { current ->
                if (current.selectedSourceId == sourceId) current.copy(filters = filters) else current
            }
        }
    }

    private fun submit() {
        query.value = _state.value.toRequest() ?: return
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}

/**
 * The request the form describes, or null when it is not submittable.
 *
 * Kept as a pure function so the rule "no keyword, no request" is testable without a Paging
 * presenter, which is what actually consumes the results.
 */
internal fun SearchUiState.toRequest(): SearchRequest? {
    val sourceId = selectedSourceId ?: return null
    val keyword = keyword.trim().takeIf(String::isNotEmpty) ?: return null
    return SearchRequest(sourceId = sourceId, keyword = keyword, filters = filterSelection)
}

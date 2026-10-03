package dev.veneranative.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.FilterSelection
import dev.veneranative.core.model.SourceFilter
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.data.comic.PageKey
import dev.veneranative.data.search.SearchHistoryRepository
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    initialHistoryRepository: SearchHistoryRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val query = MutableStateFlow<SearchRequest?>(null)
    private var historyRepository: SearchHistoryRepository? = initialHistoryRepository
    private var historyJob: kotlinx.coroutines.Job? = null
    private val _aggregateResults = MutableStateFlow<List<AggregateSearchResult>>(emptyList())
    val aggregateResults: StateFlow<List<AggregateSearchResult>> = _aggregateResults.asStateFlow()
    private var aggregateJob: kotlinx.coroutines.Job? = null

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
        initialHistoryRepository?.let(::attachSearchHistory)
    }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.SourceSelected -> if (!_state.value.aggregateSearch) selectSource(action.sourceId)

            SearchAction.AggregateToggled -> {
                _state.update { it.copy(aggregateSearch = !it.aggregateSearch) }
                if (_state.value.hasSubmitted) submit()
            }

            SearchAction.EditSearch -> {
                aggregateJob?.cancel()
                query.value = null
                _aggregateResults.value = emptyList()
                _state.update { it.copy(hasSubmitted = false) }
            }

            is SearchAction.KeywordChanged -> _state.update { it.copy(keyword = action.value) }

            is SearchAction.FilterSelected -> _state.update { current ->
                val values = current.filterSelection.values.toMutableMap()
                values[action.key] = action.values
                current.copy(filterSelection = FilterSelection(values))
            }

            is SearchAction.HistorySelected -> {
                _state.update { it.copy(keyword = action.keyword) }
                submit()
            }

            is SearchAction.HistoryRemoved -> historyRepository?.let { repository ->
                viewModelScope.launch { repository.remove(action.keyword) }
            }

            SearchAction.HistoryCleared -> historyRepository?.let { repository ->
                viewModelScope.launch { repository.clear() }
            }

            is SearchAction.AggregatedSourceSelected -> openSourceResults(action.sourceId)

            SearchAction.Submit -> submit()

            SearchAction.Retry -> loadSources()
        }
    }

    fun attachSearchHistory(repository: SearchHistoryRepository) {
        if (historyRepository === repository && historyJob?.isActive == true) return
        historyRepository = repository
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            repository.observeRecent().collect { terms -> _state.update { it.copy(searchHistory = terms) } }
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
                            message = "无法读取漫画源列表。",
                        )
                    }
                }
        }
    }

    private fun selectSource(sourceId: SourceId) {
        if (!_state.value.aggregateSearch) {
            aggregateJob?.cancel()
            query.value = null
            _aggregateResults.value = emptyList()
        }
        _state.update {
            it.copy(
                selectedSourceId = sourceId,
                filters = emptyList(),
                filterSelection = FilterSelection.Empty,
                hasSubmitted = if (it.aggregateSearch) it.hasSubmitted else false,
                message = null,
            )
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
        val state = _state.value
        val request = state.toRequest() ?: return
        _state.update { it.copy(hasSubmitted = true) }
        historyRepository?.let { repository -> viewModelScope.launch { repository.record(request.keyword) } }
        if (state.aggregateSearch) {
            query.value = null
            searchAll(request.keyword)
        } else {
            aggregateJob?.cancel()
            _aggregateResults.value = emptyList()
            query.value = request
        }
    }

    private fun openSourceResults(sourceId: SourceId) {
        val current = _state.value
        val request = current.copy(selectedSourceId = sourceId, aggregateSearch = false, filterSelection = FilterSelection.Empty).toRequest()
            ?: return
        aggregateJob?.cancel()
        _aggregateResults.value = emptyList()
        _state.update { it.copy(selectedSourceId = sourceId, aggregateSearch = false, filterSelection = FilterSelection.Empty, hasSubmitted = true) }
        loadFilters(sourceId)
        query.value = request
    }

    private fun searchAll(keyword: String) {
        aggregateJob?.cancel()
        val sources = _state.value.sources
        _aggregateResults.value = sources.map { AggregateSearchResult(it.sourceId, it.name) }
        aggregateJob = viewModelScope.launch {
            val semaphore = Semaphore(AGGREGATE_CONCURRENCY)
            coroutineScope {
                sources.map { source ->
                    async {
                        semaphore.withPermit {
                            val row = runCatching {
                                val pagingSource = catalog.search(SearchRequest(sourceId = source.sourceId, keyword = keyword, filters = FilterSelection.Empty))
                                val load = pagingSource.load(
                                    PagingSource.LoadParams.Refresh(PageKey.Start, AGGREGATE_PREVIEW_SIZE, false),
                                )
                                when (load) {
                                    is PagingSource.LoadResult.Page -> AggregateSearchResult(source.sourceId, source.name, load.data, false, false)
                                    is PagingSource.LoadResult.Error -> throw load.throwable
                                    is PagingSource.LoadResult.Invalid -> AggregateSearchResult(source.sourceId, source.name, emptyList(), false, false)
                                }
                            }.getOrElse {
                                if (it is kotlinx.coroutines.CancellationException) throw it
                                AggregateSearchResult(source.sourceId, source.name, emptyList(), false, true)
                            }
                            _aggregateResults.update { rows -> rows.map { if (it.sourceId == source.sourceId) row else it } }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val AGGREGATE_PREVIEW_SIZE = 8
        const val AGGREGATE_CONCURRENCY = 3
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

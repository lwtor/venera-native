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
import dev.veneranative.data.comic.SourceLoadException
import dev.veneranative.data.search.SearchHistoryRepository
import dev.veneranative.data.settings.ScreenPreferenceRepository
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
    screenPreferences: ScreenPreferenceRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val query = MutableStateFlow<SearchRequest?>(null)
    private var historyRepository: SearchHistoryRepository? = initialHistoryRepository
    private var historyJob: kotlinx.coroutines.Job? = null
    private val _aggregateResults = MutableStateFlow<List<AggregateSearchResult>>(emptyList())
    val aggregateResults: StateFlow<List<AggregateSearchResult>> = _aggregateResults.asStateFlow()
    private var aggregateJob: kotlinx.coroutines.Job? = null
    private var userChangedSelection = false
    private var screenPreferences: ScreenPreferenceRepository? = screenPreferences

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
        loadSources(restoreSavedSelection = screenPreferences != null)
        initialHistoryRepository?.let(::attachSearchHistory)
    }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.SourceSelected -> if (!_state.value.aggregateSearch) selectSource(action.sourceId)

            SearchAction.AggregateToggled -> {
                userChangedSelection = true
                _state.update { it.copy(aggregateSearch = !it.aggregateSearch) }
                persistSelection()
                if (_state.value.hasSubmitted) submit()
            }

            SearchAction.EditSearch -> {
                aggregateJob?.cancel()
                query.value = null
                _aggregateResults.value = emptyList()
                _state.update { it.copy(hasSubmitted = false, aggregateSourceResultsId = null) }
            }

            is SearchAction.KeywordChanged -> _state.update { it.copy(keyword = action.value) }

            is SearchAction.FilterSelected -> {
                _state.update { current ->
                    val values = current.filterSelection.values.toMutableMap()
                    values[action.key] = action.values
                    current.copy(filterSelection = FilterSelection(values))
                }
                persistFilters(_state.value.selectedSourceId, _state.value.filterSelection)
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

            SearchAction.AggregateSourceResultsBack -> {
                _state.update { it.copy(aggregateSourceResultsId = null) }
                query.value = null
            }

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

    fun attachScreenPreferences(repository: ScreenPreferenceRepository) {
        if (screenPreferences === repository) return
        screenPreferences = repository
        loadSources(restoreSavedSelection = true)
    }

    private fun loadSources(restoreSavedSelection: Boolean = false) {
        _state.update { it.copy(status = SearchStatus.Loading, message = null) }
        viewModelScope.launch {
            runCatching { catalog.searchableSources() }
                .onSuccess { sources ->
                    val current = _state.value.selectedSourceId
                    val restoredId = if (restoreSavedSelection && !userChangedSelection) {
                        screenPreferences?.get(PREF_SOURCE)?.let(::decodeSourceId)
                    } else null
                    val selected = restoredId?.takeIf { id -> sources.any { it.sourceId == id } }
                        ?: current?.takeIf { id -> sources.any { it.sourceId == id } }
                        ?: sources.firstOrNull()?.sourceId
                    val aggregate = if (!restoreSavedSelection || userChangedSelection) _state.value.aggregateSearch
                    else screenPreferences?.get(PREF_AGGREGATE)?.toBooleanStrictOrNull() ?: false
                    _state.update {
                        it.copy(
                            status = SearchStatus.Ready,
                            sources = sources,
                            selectedSourceId = selected,
                            aggregateSearch = aggregate,
                            filters = emptyList(),
                            message = null,
                        )
                    }
                    selected?.let { sourceId ->
                        persistSelection(selected, aggregate)
                        loadFilters(sourceId)
                    }
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
        userChangedSelection = true
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
        persistSelection(sourceId)
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
            val storedSelection = screenPreferences?.get(filterPreferenceKey(sourceId))?.let(::decodeFilterSelection)
            _state.update { current ->
                if (current.selectedSourceId == sourceId) {
                    val restored = if (current.filterSelection.values.isEmpty()) {
                        storedSelection?.let { validateSelection(filters, it) } ?: current.filterSelection
                    } else current.filterSelection
                    current.copy(filters = filters, filterSelection = restored)
                } else current
            }
        }
    }

    private fun persistSelection(sourceId: SourceId? = _state.value.selectedSourceId, aggregate: Boolean = _state.value.aggregateSearch) {
        val preferences = screenPreferences ?: return
        viewModelScope.launch {
            runCatching {
                sourceId?.let { preferences.put(PREF_SOURCE, encode(it.value)) }
                preferences.put(PREF_AGGREGATE, aggregate.toString())
            }
        }
    }

    private fun persistFilters(sourceId: SourceId?, selection: FilterSelection) {
        val preferences = screenPreferences ?: return
        sourceId ?: return
        viewModelScope.launch { runCatching { preferences.put(filterPreferenceKey(sourceId), encodeFilterSelection(selection)) } }
    }

    private fun filterPreferenceKey(sourceId: SourceId): String = "$PREF_FILTERS.${encode(sourceId.value)}"

    private fun encodeFilterSelection(selection: FilterSelection): String = selection.values.entries.joinToString("&") { (key, values) ->
        "${encode(key)}=${values.joinToString(",", transform = ::encode)}"
    }

    private fun decodeFilterSelection(encoded: String): FilterSelection = runCatching {
        FilterSelection(encoded.split('&').filter(String::isNotEmpty).associate { item ->
            val parts = item.split('=', limit = 2)
            decode(parts[0]) to parts.getOrElse(1) { "" }.takeIf(String::isNotEmpty)?.split(',')?.map(::decode).orEmpty()
        })
    }.getOrDefault(FilterSelection.Empty)

    private fun validateSelection(filters: List<SourceFilter>, saved: FilterSelection): FilterSelection {
        val valid = filters.associateBy { it.key }
        return FilterSelection(saved.values.mapNotNull { (key, values) ->
            val filter = valid[key] ?: return@mapNotNull null
            val options = when (filter) {
                is SourceFilter.Select -> filter.options
                is SourceFilter.MultiSelect -> filter.options
                is SourceFilter.Dropdown -> filter.options
            }.map { it.value }.toSet()
            val accepted = values.filter { it in options }.let {
                if (filter is SourceFilter.MultiSelect) it else it.take(1)
            }
            if (accepted.isEmpty() && values.isNotEmpty()) null else key to accepted
        }.toMap())
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    private fun decodeSourceId(value: String): SourceId? = runCatching { SourceId(decode(value)) }.getOrNull()

    private fun submit() {
        val state = _state.value
        val request = state.toRequest() ?: return
        _state.update { it.copy(hasSubmitted = true, aggregateSourceResultsId = null) }
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
        if (!current.aggregateSearch || sourceId !in current.sources.map { it.sourceId }) return
        val keyword = current.keyword.trim().takeIf(String::isNotEmpty) ?: return
        val request = SearchRequest(sourceId = sourceId, keyword = keyword, filters = FilterSelection.Empty)
        _state.update { it.copy(aggregateSourceResultsId = sourceId) }
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
                            }.getOrElse { failure ->
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                val detail = ((failure as? SourceLoadException)?.error?.message ?: failure.message)
                                    ?.filterNot(Char::isISOControl)
                                    ?.take(180)
                                AggregateSearchResult(
                                    source.sourceId,
                                    source.name,
                                    emptyList(),
                                    false,
                                    true,
                                    detail,
                                )
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
        const val PREF_SOURCE = "search.source"
        const val PREF_AGGREGATE = "search.aggregate"
        const val PREF_FILTERS = "search.filters"
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

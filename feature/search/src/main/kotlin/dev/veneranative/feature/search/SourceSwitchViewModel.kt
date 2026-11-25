package dev.veneranative.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.FilterSelection
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.source.api.SearchRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Looks for the comic being read on the other installed sources.
 *
 * Deliberately the same shape as [SearchViewModel]: one keyword, one fan-out through the shared
 * aggregate search, one preview row per source that fills in as it answers, and an optional full
 * list for a single source. The difference is only where the keyword comes from and what happens
 * after a tap — a switch opens the candidate, and the candidate page is what settles it.
 */
class SourceSwitchViewModel(
    private val catalog: ComicCatalog,
    private val comicKey: ComicKey,
    seedTitle: String?,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : ViewModel() {

    private val _state = MutableStateFlow(SourceSwitchUiState(keyword = seedTitle.orEmpty()))
    val state: StateFlow<SourceSwitchUiState> = _state.asStateFlow()

    private val _rows = MutableStateFlow<List<AggregateSearchResult>>(emptyList())
    val rows: StateFlow<List<AggregateSearchResult>> = _rows.asStateFlow()

    private var searchJob: Job? = null
    private val openQuery = MutableStateFlow<SearchRequest?>(null)

    /** Paged results of the source opened from the overview; empty while the overview is showing. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val openResults: Flow<PagingData<Comic>> = openQuery
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

    /**
     * Where each scrollable area was left, keyed by the area itself.
     *
     * Kept out of [state] on purpose: scroll positions change on every frame, and they are only read
     * when an area is built, so publishing them would recompose the page for nothing.
     */
    private val scrollSlots = mutableMapOf<String, ScrollSlot>()

    init {
        viewModelScope.launch {
            runCatching { catalog.searchableSources() }
                .onSuccess { installed ->
                    val candidates = installed.filter { it.sourceId != comicKey.sourceId }
                    _state.update { it.copy(candidates = candidates, ready = true) }
                    if (_state.value.keyword.isNotBlank()) search()
                }
                .onFailure {
                    _state.update { it.copy(ready = true, message = "暂时无法读取漫画源列表。") }
                }
        }
    }

    fun onKeywordChanged(value: String) {
        _state.update { it.copy(keyword = value, message = null) }
    }

    /** Runs one search across every candidate source, from the first page. */
    fun search() {
        val state = _state.value
        val keyword = state.keyword.trim()
        if (keyword.isBlank()) {
            _state.update { it.copy(message = "请输入漫画标题后搜索。") }
            return
        }
        if (state.candidates.isEmpty()) {
            _state.update { it.copy(message = "没有其他可用的漫画源。") }
            return
        }
        searchJob?.cancel()
        openQuery.value = null
        _state.update { it.copy(openedSourceId = null, message = null) }
        // New results, so the old position would point at a list that no longer exists.
        scrollSlots.keys.toList().forEach(scrollSlots::remove)
        searchJob = searchEverySource(viewModelScope, catalog, state.candidates, keyword, _rows)
    }

    fun openSource(sourceId: SourceId) {
        val keyword = _state.value.keyword.trim().takeIf(String::isNotEmpty) ?: return
        if (_state.value.candidates.none { it.sourceId == sourceId }) return
        _state.update { it.copy(openedSourceId = sourceId, message = null) }
        scrollSlots.remove(SOURCE_SCROLL_SLOT_PREFIX + sourceId.value)
        openQuery.value = SearchRequest(sourceId = sourceId, keyword = keyword, filters = FilterSelection.Empty)
    }

    fun closeSource() {
        _state.update { it.copy(openedSourceId = null) }
        openQuery.value = null
    }

    /** Where [slot] was left; the overview and each source's full list keep their own. */
    fun scrollSlot(slot: String): ScrollSlot = scrollSlots[slot] ?: ScrollSlot()

    fun keepScrollSlot(slot: String, index: Int, offset: Int) {
        scrollSlots[slot] = ScrollSlot(index, offset)
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}

/** The grouped overview's own scroll position. */
internal const val OVERVIEW_SCROLL_SLOT = "overview"

/** Prefix of a single source's full list; the source id completes it. */
internal const val SOURCE_SCROLL_SLOT_PREFIX = "source:"

/** One slot per source row of the overview, so scrolling one row does not move the others. */
internal fun rowScrollSlot(sourceId: SourceId): String = "$OVERVIEW_SCROLL_SLOT:row:${sourceId.value}"

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
import kotlinx.coroutines.CancellationException
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
 * list for a single source. The difference is only where the keyword comes from and what a tap does
 * — a switch rebinds the shelf entry to the picked source before the assembly layer opens it.
 */
class SourceSwitchViewModel(
    private val catalog: ComicCatalog,
    private val comicKey: ComicKey,
    seedTitle: String?,
    /** Moves the shelf entry from the source being left to the one being picked. */
    private val moveShelfEntry: suspend (from: ComicKey, to: ComicKey) -> Unit,
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
     * The key the shelf entry currently points at.
     *
     * The user can come back to this page and pick again, so after the first switch the entry lives
     * under the previous choice; rebinding from the source being left would find nothing and leave
     * the shelf pointing at a comic the user has moved on from.
     */
    private var shelfOwner: ComicKey = comicKey

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
        searchJob = searchEverySource(viewModelScope, catalog, state.candidates, keyword, _rows)
    }

    fun openSource(sourceId: SourceId) {
        val keyword = _state.value.keyword.trim().takeIf(String::isNotEmpty) ?: return
        if (_state.value.candidates.none { it.sourceId == sourceId }) return
        _state.update { it.copy(openedSourceId = sourceId, message = null) }
        openQuery.value = SearchRequest(sourceId = sourceId, keyword = keyword, filters = FilterSelection.Empty)
    }

    fun closeSource() {
        _state.update { it.copy(openedSourceId = null) }
        openQuery.value = null
    }

    /**
     * Takes one result as the choice.
     *
     * A comic that was never kept has nothing to migrate, which is a normal outcome. A *failed*
     * rebind is not: navigating away would leave the shelf pointing at the old, broken source with
     * nothing on screen saying so, so the switch stops and stays retryable.
     */
    fun choose(comic: Comic) {
        if (_state.value.choosing) return
        _state.update { it.copy(choosing = true, message = null) }
        viewModelScope.launch {
            val rebound = try {
                moveShelfEntry(shelfOwner, comic.key)
                shelfOwner = comic.key
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!rebound) {
                _state.update { it.copy(choosing = false, message = "无法把收藏迁移到新来源，请重试。") }
                return@launch
            }
            _state.update { it.copy(choosing = false, chosen = SourceSwitchChoice(comic.key, comic.title)) }
        }
    }

    fun choiceConsumed() {
        _state.update { it.copy(chosen = null) }
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}

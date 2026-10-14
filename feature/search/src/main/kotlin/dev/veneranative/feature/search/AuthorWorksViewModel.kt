package dev.veneranative.feature.search

import dev.veneranative.core.model.SourceFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.FilterSelection
import dev.veneranative.core.model.FilterValue
import dev.veneranative.core.model.TagNavigation
import dev.veneranative.data.comic.ComicCatalog
import dev.veneranative.source.api.CategoryRequest
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A result list scoped to the source and exact target returned by its author callback. */
class AuthorWorksViewModel(
    private val catalog: ComicCatalog,
    private val origin: ComicKey,
    private val author: String,
    private val target: TagNavigation,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(keyword = author, hasSubmitted = true))
    val state = _state.asStateFlow()
    private val ready = MutableStateFlow<Pair<SearchRequest?, CategoryRequest?>?>(null)
    private var loadJob: kotlinx.coroutines.Job? = null
    private val resultInfoLoader = ResultInfoLoader(catalog, viewModelScope)
    val resultInfo = resultInfoLoader.info
    val resultTags = resultInfoLoader.tags
    fun onResultVisible(comic: Comic) {
        if (_state.value.status == SearchStatus.Ready && comic.key.sourceId == origin.sourceId) resultInfoLoader.visible(comic)
    }
    fun onResultHidden(key: ComicKey) = resultInfoLoader.hidden(key)
    @OptIn(ExperimentalCoroutinesApi::class)
    val results = ready.flatMapLatest { request ->
        if (request == null) flowOf(PagingData.empty<Comic>()) else Pager(
            PagingConfig(pageSize = 30, enablePlaceholders = false),
            pagingSourceFactory = {
                request.first?.let(catalog::search) ?: catalog.category(requireNotNull(request.second))
            },
        ).flow
    }.cachedIn(viewModelScope)

    init { retry() }

    fun retry() {
        loadJob?.cancel()
        ready.value = null
        _state.value = SearchUiState(keyword = author, hasSubmitted = true)
        loadJob = viewModelScope.launch {
            try {
                val source = catalog.enabledSource(origin.sourceId)
                if (source == null) {
                    _state.value = _state.value.copy(status = SearchStatus.Failed, message = "此漫画源已卸载或停用。")
                    return@launch
                }
                val filters = when (val outcome = catalog.relatedWorksFilters(origin.sourceId, target)) {
                    is SourceOutcome.Failure -> {
                        _state.value = _state.value.copy(status = SearchStatus.Failed, message = "无法加载相关作品，请重试。")
                        return@launch
                    }
                    is SourceOutcome.Success -> outcome.value
                }
                val request = authorWorksRequest(origin, target, filters)
                _state.value = _state.value.copy(status = SearchStatus.Ready, sources = listOf(source), selectedSourceId = origin.sourceId)
                ready.value = request
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(status = SearchStatus.Failed, message = "无法加载相关作品，请重试。")
            }
        }
    }
}

internal fun authorWorksRequest(origin: ComicKey, target: TagNavigation, filters: List<SourceFilter>): Pair<SearchRequest?, CategoryRequest?> = when (target) {
    is TagNavigation.Search -> SearchRequest(origin.sourceId, target.keyword, optionsOverride = target.options.takeIf { it.isNotEmpty() }, authorId = target.authorId) to null
    is TagNavigation.Category -> {
        val options = if (filters.isEmpty()) target.options else filters.mapIndexed { index, filter ->
            target.options.getOrNull(index) ?: (filter.selectionValue(FilterSelection.Empty) as? FilterValue.Single)?.value.orEmpty()
        }
        null to CategoryRequest(origin.sourceId, target.keyword, target.param, options)
    }
}

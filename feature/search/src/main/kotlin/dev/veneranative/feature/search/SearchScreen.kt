package dev.veneranative.feature.search

import dev.veneranative.core.designsystem.component.ComicHorizontalCard
import dev.veneranative.core.designsystem.component.ComicCoverCard
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.SourceFilter
import dev.veneranative.data.comic.SourceLoadException
import dev.veneranative.source.api.SourceRuntimeError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onAction: (SearchAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state.status) {
                SearchStatus.Loading -> {
                    SearchForm(state, onAction, sourcesLoading = true)
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("正在准备漫画源…", style = MaterialTheme.typography.bodyMedium)
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) { SearchHistory(state, onAction) }
                    }
                }
                SearchStatus.Failed -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.error_source_list))
                        Button(onClick = { onAction(SearchAction.Retry) }) { Text(stringResource(R.string.retry)) }
                    }
                }
                SearchStatus.Ready -> if (!state.hasSources) Centered {
                    Text(stringResource(R.string.no_search_source), style = MaterialTheme.typography.titleMedium)
                } else {
                    SearchForm(state, onAction)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        SearchHistory(state, onAction)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultScreen(
    state: SearchUiState,
    results: LazyPagingItems<Comic>,
    aggregateResults: List<AggregateSearchResult>,
    onAction: (SearchAction) -> Unit,
    onOpenComic: (ComicKey) -> Unit,
    onBackToSearch: () -> Unit,
    modifier: Modifier = Modifier,
    resultTags: Map<ComicKey, List<String>> = emptyMap(),
    onResultVisible: (Comic) -> Unit = {},
    onResultHidden: (ComicKey) -> Unit = {},
    resultInfo: Map<ComicKey, Comic> = emptyMap(),
) {
    BackHandler(onBack = onBackToSearch)
    val showingSourceResults = state.aggregateSourceResultsId != null
    val title = if (showingSourceResults) {
        "${state.aggregateSourceResultsSource?.name.orEmpty()} · ${state.keyword}"
    } else if (state.aggregateSearch) {
        stringResource(R.string.aggregate_results)
    } else {
        "${state.selectedSource?.name.orEmpty()} · ${state.keyword}"
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBackToSearch) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showingSourceResults) Results(results, onOpenComic, resultTags, onResultVisible, onResultHidden, resultInfo)
            else if (state.aggregateSearch) AggregateResults(aggregateResults, onAction, onOpenComic)
            else Results(results, onOpenComic, resultTags, onResultVisible, onResultHidden, resultInfo)
        }
    }
}

@Composable
private fun SearchForm(
    state: SearchUiState,
    onAction: (SearchAction) -> Unit,
    sourcesLoading: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.search_in), style = MaterialTheme.typography.labelLarge)
        if (sourcesLoading && state.sources.isEmpty()) {
            Text("正在读取可用来源…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.sources.forEach { source ->
                    val selected = state.aggregateSearch || source.sourceId == state.selectedSourceId
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (!state.aggregateSearch) onAction(SearchAction.SourceSelected(source.sourceId))
                        },
                        modifier = Modifier.height(40.dp),
                        leadingIcon = { SelectionMark(selected) },
                        label = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = { onAction(SearchAction.KeywordChanged(it)) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.keyword)) },
                singleLine = true,
            )
            Button(onClick = { onAction(SearchAction.Submit) }, enabled = state.canSubmit) { Text(stringResource(R.string.search)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.aggregateSearch, { onAction(SearchAction.AggregateToggled) })
            Text(stringResource(R.string.aggregate_search))
        }
        Column(Modifier.animateContentSize()) {
            if (!state.aggregateSearch) state.filters.forEach { filter -> FilterOptions(filter, state, onAction) }
        }
    }
}

@Composable
private fun FilterOptions(filter: SourceFilter, state: SearchUiState, onAction: (SearchAction) -> Unit) {
    val selected = state.filterSelection.selected(filter.key)
    Column {
        Text(filter.label, style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (filter) {
                is SourceFilter.Select -> filter.options.forEach { option ->
                    val active = (selected.firstOrNull() ?: filter.defaultValue) == option.value
                    FilterChip(active, { onAction(SearchAction.FilterSelected(filter.key, listOf(option.value))) }, modifier = Modifier.height(40.dp), leadingIcon = { SelectionMark(active) }, label = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
                is SourceFilter.Dropdown -> {
                    val allSelected = if (state.filterSelection.values.containsKey(filter.key)) selected.isEmpty() else filter.defaultValue == null
                    FilterChip(
                        selected = allSelected,
                        onClick = { onAction(SearchAction.FilterSelected(filter.key, emptyList())) },
                        modifier = Modifier.height(40.dp),
                        leadingIcon = { SelectionMark(allSelected) },
                        label = { Text(stringResource(R.string.filter_any), maxLines = 1) },
                    )
                    filter.options.forEach { option ->
                        val active = if (state.filterSelection.values.containsKey(filter.key)) selected.firstOrNull() else filter.defaultValue
                        val isSelected = active == option.value
                        FilterChip(isSelected, { onAction(SearchAction.FilterSelected(filter.key, listOf(option.value))) }, modifier = Modifier.height(40.dp), leadingIcon = { SelectionMark(isSelected) }, label = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
                is SourceFilter.MultiSelect -> filter.options.forEach { option ->
                    val current = if (state.filterSelection.values.containsKey(filter.key)) selected else filter.defaultValues
                    val active = option.value in current
                    FilterChip(active, {
                        val next = if (option.value in current) current - option.value else current + option.value
                        onAction(SearchAction.FilterSelected(filter.key, next))
                    }, modifier = Modifier.height(40.dp), leadingIcon = { SelectionMark(active) }, label = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (selected) Text("✓", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SearchHistory(state: SearchUiState, onAction: (SearchAction) -> Unit) {
    if (state.searchHistory.isEmpty()) {
        Centered { Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge) }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onAction(SearchAction.HistoryCleared) }) { Text(stringResource(R.string.clear_history)) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(state.searchHistory, key = { it }) { term ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onAction(SearchAction.HistorySelected(term)) }, modifier = Modifier.weight(1f)) {
                        Text(term, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onAction(SearchAction.HistoryRemoved(term)) }) { Text("×") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AggregateResults(rows: List<AggregateSearchResult>, onAction: (SearchAction) -> Unit, onOpenComic: (ComicKey) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(rows, key = { it.sourceId.value }) { row ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.sourceName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onAction(SearchAction.AggregatedSourceSelected(row.sourceId)) }) { Text(stringResource(R.string.view_all)) }
                }
                when {
                    row.isLoading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    row.hasError -> Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.source_search_failed), color = MaterialTheme.colorScheme.error)
                        row.errorDetail?.let { detail ->
                            Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    row.comics.isEmpty() -> Text(stringResource(R.string.no_results), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(row.comics, key = { it.key.remoteId.value }) { comic ->
                            ComicCoverCard(
                                title = comic.title,
                                request = comic.coverUrl?.let { ComicImageRequest(it, sourceId = comic.key.sourceId, variant = "comic-cover") },
                                lastUpdated = comic.lastUpdated, sourceLabel = row.sourceName,
                                onClick = { onOpenComic(comic.key) }, modifier = Modifier.width(128.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Results(
    results: LazyPagingItems<Comic>, onOpenComic: (ComicKey) -> Unit,
    resultTags: Map<ComicKey, List<String>>, onResultVisible: (Comic) -> Unit, onResultHidden: (ComicKey) -> Unit,
    resultInfo: Map<ComicKey, Comic>,
) {
    when (val refresh = results.loadState.refresh) {
        is LoadState.Loading -> Centered { CircularProgressIndicator(Modifier.testTag(SEARCH_LOADING_TAG)) }
        is LoadState.Error -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(refresh.localizedMessage(), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { results.retry() }) { Text(stringResource(R.string.retry)) }
            }
        }
        else -> if (results.itemCount == 0) Centered { Text(stringResource(R.string.no_results), style = MaterialTheme.typography.titleMedium) }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results.itemCount, key = { index -> results.peek(index)?.key?.remoteId?.value ?: index }) { index ->
                results[index]?.let { comic ->
                    DisposableEffect(comic.key) {
                        onResultVisible(comic)
                        onDispose { onResultHidden(comic.key) }
                    }
                    val info = resultInfo[comic.key] ?: comic
                    ComicHorizontalCard(
                        title = comic.title,
                        request = comic.coverUrl?.let { ComicImageRequest(it, sourceId = comic.key.sourceId, variant = "search-result") },
                        onClick = { onOpenComic(comic.key) },
                        badge = info.publicationStatus?.let { if (it == dev.veneranative.core.model.ComicPublicationStatus.Completed) "已完结" else "连载中" },
                        badgeColor = if (info.publicationStatus == dev.veneranative.core.model.ComicPublicationStatus.Ongoing)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.72f),
                        supportingContent = {
                            val authors = info.authors.takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: comic.subtitle
                            authors?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            Text(info.lastUpdated.orEmpty(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        bottomContent = { ComicResultTags(comic.tags.takeIf { it.any(String::isNotBlank) } ?: resultTags[comic.key].orEmpty()) },
                    )
                }
            }
            if (results.loadState.append is LoadState.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            (results.loadState.append as? LoadState.Error)?.let { error ->
                item {
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error.localizedMessage(), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { results.retry() }) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComicResultTags(tags: List<String>) {
    val visibleTags = tags.filter(String::isNotBlank).distinct()
    if (visibleTags.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().height(22.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleTags.forEach { tag ->
            Surface(shape = RoundedCornerShape(5.dp), color = Color.Transparent,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))) {
                Text(
                    tag, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

@Composable
private fun LoadState.Error.localizedMessage(): String {
    val domain = (error as? SourceLoadException)?.error
    return when (domain) {
        is SourceRuntimeError.UnsupportedCapability -> stringResource(R.string.error_search_unsupported)
        is SourceRuntimeError.SourceNotLoaded -> stringResource(R.string.error_source_unavailable)
        is SourceRuntimeError.Timeout -> stringResource(R.string.error_source_timeout)
        is SourceRuntimeError.Cancelled -> stringResource(R.string.error_search_cancelled)
        is SourceRuntimeError.EngineUnavailable -> stringResource(R.string.error_engine_unavailable)
        is SourceRuntimeError.EngineTerminated -> stringResource(R.string.error_engine_restarted)
        else -> stringResource(R.string.error_source_generic)
    }
}

internal const val SEARCH_LOADING_TAG = "search-loading"

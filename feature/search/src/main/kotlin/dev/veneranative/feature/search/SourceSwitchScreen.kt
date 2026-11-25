package dev.veneranative.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.SourceId

/**
 * Finds the comic being read on another source, as a page rather than a panel.
 *
 * Everything the search screen does for a keyword, this does for a title: a search box that starts
 * filled in, one preview row per source that fills in as each answers, a way into any single
 * source's full list, and results that survive a trip into a candidate and back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSwitchScreen(
    state: SourceSwitchUiState,
    rows: List<AggregateSearchResult>,
    results: LazyPagingItems<Comic>,
    onKeywordChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenSource: (SourceId) -> Unit,
    onCloseSource: () -> Unit,
    onChooseComic: (Comic) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openedSourceId = state.openedSourceId
    // A source's full list is a level inside this page, so back closes it before it leaves the page.
    val back: () -> Unit = if (openedSourceId != null) onCloseSource else onBack
    BackHandler(onBack = back)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.openedSourceName?.let { "$it · ${state.keyword}" } ?: "切换漫画源",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (openedSourceId == null) {
                            Text(
                                text = "同一部作品在其他来源的结果",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchBox(
                keyword = state.keyword,
                onKeywordChanged = onKeywordChanged,
                onSearch = onSearch,
            )
            if (openedSourceId == null) {
                Text(
                    text = "点选作品即切换到该来源，并打开它的详情页。",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.choosing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    }

                    state.candidates.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有其他可用的漫画源。", style = MaterialTheme.typography.titleMedium)
                    }

                    openedSourceId != null -> Results(results, onOpenComic = onChooseComic)

                    else -> AggregateSourceResults(
                        rows = rows,
                        onOpenComic = onChooseComic,
                        onOpenSource = onOpenSource,
                    )
                }
            }
        }
    }
}

/**
 * The search box doubles as the way to correct a wrong seed: sources often name the same work
 * slightly differently, and the title inherited from the comic is only a guess.
 */
@Composable
private fun SearchBox(
    keyword: String,
    onKeywordChanged: (String) -> Unit,
    onSearch: () -> Unit,
) {
    OutlinedTextField(
        value = keyword,
        onValueChange = onKeywordChanged,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        label = { Text("漫画标题") },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
        trailingIcon = {
            IconButton(
                onClick = onSearch,
                modifier = Modifier.semantics { contentDescription = "搜索其他漫画源" },
            ) { Icon(Icons.Filled.Search, contentDescription = null) }
        },
    )
}

package dev.veneranative.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.comic.SourceLoadException
import dev.veneranative.source.api.SourceRuntimeError

/**
 * Stateless search screen: renders [state] and the paged [results], sends [onAction].
 *
 * The list's own states come from Paging: a failed page is retried on the list, not by rebuilding
 * the screen, and a partially loaded list keeps showing what it already has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    results: LazyPagingItems<Comic>,
    onAction: (SearchAction) -> Unit,
    onOpenComic: (ComicKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when (state.status) {
                SearchStatus.Loading -> Centered { CircularProgressIndicator() }

                SearchStatus.Failed -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = state.message ?: "The source list could not be read.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { onAction(SearchAction.Retry) }) { Text("Retry") }
                    }
                }

                SearchStatus.Ready -> if (!state.hasSources) {
                    Centered {
                        Text(
                            text = "No installed source can search.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                } else {
                    SearchForm(state = state, onAction = onAction)
                    Results(results = results, onOpenComic = onOpenComic)
                }
            }
        }
    }
}

@Composable
private fun SearchForm(
    state: SearchUiState,
    onAction: (SearchAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.sources.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.sources.forEach { source ->
                    FilterChip(
                        selected = source.sourceId == state.selectedSourceId,
                        onClick = { onAction(SearchAction.SourceSelected(source.sourceId)) },
                        label = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = { onAction(SearchAction.KeywordChanged(it)) },
                modifier = Modifier.weight(1f),
                label = { Text("Keyword") },
                singleLine = true,
            )
            Button(
                onClick = { onAction(SearchAction.Submit) },
                enabled = state.canSubmit,
            ) {
                Text("Search")
            }
        }
    }
}

@Composable
private fun Results(
    results: LazyPagingItems<Comic>,
    onOpenComic: (ComicKey) -> Unit,
) {
    val refresh = results.loadState.refresh
    when {
        refresh is LoadState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.testTag(SEARCH_LOADING_TAG))
        }

        refresh is LoadState.Error -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = refresh.toMessage(),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { results.retry() }) { Text("Retry") }
            }
        }

        results.itemCount == 0 -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "No results.", style = MaterialTheme.typography.titleMedium)
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(count = results.itemCount, key = { index -> results.peek(index)?.key?.remoteId?.value ?: index }) { index ->
                results[index]?.let { comic ->
                    ComicRow(comic = comic, onOpen = { onOpenComic(comic.key) })
                }
            }
            if (results.loadState.append is LoadState.Loading) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun ComicRow(
    comic: Comic,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = comic.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            comic.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * Product copy for a failed page.
 *
 * The domain error travels inside [SourceLoadException], so the screen decides what the user reads
 * instead of printing an exception message.
 */
internal fun LoadState.Error.toMessage(): String {
    val domain = (error as? SourceLoadException)?.error
    return when (domain) {
        is SourceRuntimeError.UnsupportedCapability -> "This source does not support search."
        is SourceRuntimeError.SourceNotLoaded -> "That source is no longer loaded."
        is SourceRuntimeError.Timeout -> "The source took too long to answer."
        is SourceRuntimeError.Cancelled -> "The search was cancelled."
        is SourceRuntimeError.EngineUnavailable -> "Comic sources are unavailable on this device."
        is SourceRuntimeError.EngineTerminated -> "The source engine stopped; search again to reload it."

        else -> domain?.message ?: "The source could not answer."
    }
}

internal const val SEARCH_LOADING_TAG = "search-loading"

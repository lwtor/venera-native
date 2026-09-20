package dev.veneranative.feature.explore

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
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.data.comic.SourceLoadException
import dev.veneranative.source.api.SourceRuntimeError

/**
 * Stateless explore screen: renders [state] and the paged [results], sends [onAction].
 *
 * A source's explore page can be a single comic list, several titled sections in one response, or a
 * mix of both, so the list renders [ExploreItem] as it comes instead of flattening it into one shape
 * the source never promised.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: ExploreUiState,
    results: LazyPagingItems<ExploreItem>,
    onAction: (ExploreAction) -> Unit,
    onOpenComic: (ComicKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.selectedSourceNameOr("Explore")) },
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
                ExploreStatus.Loading -> Centered { CircularProgressIndicator() }

                ExploreStatus.Failed -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = state.message ?: "The source list could not be read.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { onAction(ExploreAction.Retry) }) { Text("Retry") }
                    }
                }

                ExploreStatus.Ready -> when {
                    !state.hasSources -> Centered {
                        Text(
                            text = "No installed source has explore pages.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    !state.hasPages -> Centered {
                        Text(
                            text = state.message ?: "This source declares no explore pages.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    else -> {
                        PageSelector(state = state, onAction = onAction)
                        Content(results = results, onOpenComic = onOpenComic)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageSelector(
    state: ExploreUiState,
    onAction: (ExploreAction) -> Unit,
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
                        onClick = { onAction(ExploreAction.SourceSelected(source.sourceId)) },
                        label = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }

        if (state.pages.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.pages.forEach { page ->
                    FilterChip(
                        selected = page.key == state.selectedPageKey,
                        onClick = { onAction(ExploreAction.PageSelected(page.key)) },
                        label = { Text(page.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Content(
    results: LazyPagingItems<ExploreItem>,
    onOpenComic: (ComicKey) -> Unit,
) {
    val refresh = results.loadState.refresh
    when {
        refresh is LoadState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.testTag(EXPLORE_LOADING_TAG))
        }

        refresh is LoadState.Error -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = refresh.toMessage(), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { results.retry() }) { Text("Retry") }
            }
        }

        results.itemCount == 0 -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Nothing to show here.", style = MaterialTheme.typography.titleMedium)
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(count = results.itemCount) { index ->
                results[index]?.let { item ->
                    ExploreBlock(item = item, onOpenComic = onOpenComic)
                }
            }
            if (results.loadState.append is LoadState.Loading) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun ExploreBlock(
    item: ExploreItem,
    onOpenComic: (ComicKey) -> Unit,
) {
    when (item) {
        is ExploreItem.Comics -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.comics.forEach { comic -> ComicRow(comic = comic, onOpen = { onOpenComic(comic.key) }) }
        }

        is ExploreItem.Section -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            item.comics.forEach { comic -> ComicRow(comic = comic, onOpen = { onOpenComic(comic.key) }) }
        }
    }
}

@Composable
private fun ComicRow(
    comic: Comic,
    onOpen: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
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

private fun ExploreUiState.selectedSourceNameOr(fallback: String): String =
    selectedSourceId?.let { id -> sources.firstOrNull { it.sourceId == id }?.name } ?: fallback

/**
 * Product copy for a failed page.
 *
 * Wording lives with the feature that shows it, so explore and search phrase the same failure for
 * their own context rather than sharing a string table the user never sees as a whole.
 */
internal fun LoadState.Error.toMessage(): String {
    val domain = (error as? SourceLoadException)?.error
    return when (domain) {
        is SourceRuntimeError.UnsupportedCapability -> "This source has no explore pages."
        is SourceRuntimeError.SourceNotLoaded -> "That source is no longer loaded."
        is SourceRuntimeError.Timeout -> "The source took too long to answer."
        is SourceRuntimeError.Cancelled -> "Loading was cancelled."
        is SourceRuntimeError.EngineUnavailable -> "Comic sources are unavailable on this device."
        is SourceRuntimeError.EngineTerminated -> "The source engine stopped; open the page again to reload it."

        else -> domain?.message ?: "The source could not answer."
    }
}

internal const val EXPLORE_LOADING_TAG = "explore-loading"

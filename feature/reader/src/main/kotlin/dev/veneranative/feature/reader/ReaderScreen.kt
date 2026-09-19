package dev.veneranative.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.ComicPage

/**
 * Stateless reader rendering. Every input comes from [state]; every intent leaves as [onAction].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onAction: (ReaderAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.chapterTitle.ifEmpty { "Reader" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${state.currentPageNumber} / ${state.pageCount}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
        bottomBar = {
            if (state.status == ReaderStatus.Ready) {
                DirectionBar(
                    direction = state.direction,
                    onDirectionChange = { onAction(ReaderAction.ChangeDirection(it)) },
                )
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (state.status) {
                ReaderStatus.Loading -> CircularProgressIndicator()

                ReaderStatus.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "This chapter could not be loaded.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { onAction(ReaderAction.Retry) }) { Text("Retry") }
                }

                ReaderStatus.Ready -> PageContent(state = state, onAction = onAction)
            }
        }
    }
}

@Composable
private fun PageContent(state: ReaderUiState, onAction: (ReaderAction) -> Unit) {
    if (state.pageCount == 0) {
        Text(
            text = "This chapter has no pages.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    when (state.direction) {
        ReadingDirection.Vertical -> ContinuousPages(state = state, onAction = onAction)
        ReadingDirection.LeftToRight,
        ReadingDirection.RightToLeft,
        -> SinglePagePager(state = state, onAction = onAction)
    }
}

/** Continuous vertical scrolling, the default reading mode for long strips. */
@Composable
private fun ContinuousPages(state: ReaderUiState, onAction: (ReaderAction) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPageIndex)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { onAction(ReaderAction.PageShown(it)) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(state.pages, key = { it.index }) { page ->
            PagePlaceholder(page)
        }
    }
}

/** One page per screen horizontally; RTL is expressed by reversing the layout direction. */
@Composable
private fun SinglePagePager(state: ReaderUiState, onAction: (ReaderAction) -> Unit) {
    val pagerState = rememberPagerState(
        initialPage = state.currentPageIndex,
        pageCount = { state.pageCount },
    )
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect { onAction(ReaderAction.PageShown(it)) }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = state.direction == ReadingDirection.RightToLeft,
    ) { index ->
        PagePlaceholder(page = state.pages[index])
    }
}

/**
 * Reserves the page box from its descriptor so scrolling stays stable before any decoding exists.
 * The descriptor carries no bitmap, and this composable never allocates one: image loading is
 * deliberately out of S0-05 scope.
 */
@Composable
private fun PagePlaceholder(page: ComicPage, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .aspectRatio(page.aspectRatio)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Page ${page.index + 1}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DirectionBar(
    direction: ReadingDirection,
    onDirectionChange: (ReadingDirection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        ReadingDirection.entries.forEach { candidate ->
            TextButton(onClick = { onDirectionChange(candidate) }) {
                Text(
                    text = candidate.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (candidate == direction) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun ReadingDirection.label(): String = when (this) {
    ReadingDirection.Vertical -> "Vertical"
    ReadingDirection.LeftToRight -> "LTR"
    ReadingDirection.RightToLeft -> "RTL"
}

@Preview(showBackground = true)
@Composable
private fun ReaderScreenPreview() {
    VeneraNativeTheme {
        ReaderScreen(
            state = ReaderUiState(
                chapterTitle = "Chapter 1",
                pages = List(3) { index ->
                    ComicPage(
                        index = index,
                        imageRef = "preview://$index",
                        widthPx = 1080,
                        heightPx = 1440,
                    )
                },
                status = ReaderStatus.Ready,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

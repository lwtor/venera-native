package dev.veneranative.feature.details

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.image.compose.ComicImage
import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.SourceId


/**
 * Stateless details screen: renders [state] and sends [onAction].
 *
 * The chapter list is whatever the source declared, including its order and its groups: some sources
 * publish one flat list, others several translations or volumes, so the headers and the group chips
 * appear only when the source actually grouped something.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    state: DetailsUiState,
    onAction: (DetailsAction) -> Unit,
    onOpenChapter: (ChapterKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifEmpty { "Comic" }) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when (state.status) {
                DetailsStatus.Loading -> Centered { CircularProgressIndicator(modifier = Modifier.testTag(DETAILS_LOADING_TAG)) }

                DetailsStatus.SourceUnavailable -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "This comic's source is no longer installed or enabled.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = onBack) { Text("Back") }
                    }
                }

                DetailsStatus.Failed -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = state.message ?: "The source could not answer.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { onAction(DetailsAction.Retry) }) { Text("Retry") }
                    }
                }

                DetailsStatus.Ready -> Content(
                    state = state,
                    onAction = onAction,
                    onOpenChapter = onOpenChapter,
                )
            }
        }
    }
}

@Composable
private fun Content(
    state: DetailsUiState,
    onAction: (DetailsAction) -> Unit,
    onOpenChapter: (ChapterKey) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(state) }

        state.detail?.description?.takeIf { it.isNotBlank() }?.let { description ->
            item {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (state.hasNoChapters) {
            item {
                Text(
                    text = "This source returned no chapters.",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            return@LazyColumn
        }

        item { ChapterControls(state = state, onAction = onAction) }

        val withHeaders = state.groupsTheList
        var previousGroup: String? = null
        state.visibleChapters.forEach { chapter ->
            val group = chapter.group
            if (withHeaders && group != null && group != previousGroup) {
                item(key = "group:$group") { GroupHeader(group) }
            }
            if (withHeaders) previousGroup = group ?: previousGroup

            item(key = chapter.key.remoteId.value) {
                ChapterRow(chapter = chapter, onOpen = { onOpenChapter(chapter.key) })
            }
        }
    }
}

@Composable
private fun Header(state: DetailsUiState) {
    val comic = state.detail?.comic ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Cover(title = comic.title, coverUrl = comic.coverUrl, sourceId = comic.key.sourceId)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = comic.title, style = MaterialTheme.typography.titleLarge)

            comic.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            state.sourceName?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (comic.tags.isNotEmpty()) {
                Text(
                    text = comic.tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.detail?.metadata?.forEach { (key, value) ->
                Text(
                    text = "$key: $value",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The cover slot at its final size, rendered by the image pipeline.
 *
 * A cover is a URL that may need the source's headers, so it goes through `ComicImage` rather than
 * through a plain image composable: the request carries the source id, which is what lets the auth
 * provider attach that source's cookies. When the source gave no cover — or the assembly layer has
 * not provided an image loader — the slot still shows the title instead of an empty box.
 */
@Composable
private fun Cover(
    title: String,
    coverUrl: String?,
    sourceId: SourceId,
) {
    Surface(
        modifier = Modifier.size(width = 96.dp, height = 144.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        CoverPlaceholder(title = title, coverUrl = coverUrl, sourceId = sourceId)
    }
}

@Composable
private fun CoverPlaceholder(
    title: String,
    coverUrl: String?,
    sourceId: SourceId,
) {
    if (coverUrl == null) {
        CoverTitle(title = title)
        return
    }
    ComicImage(
        request = ComicImageRequest(url = coverUrl, sourceId = sourceId, variant = COVER_VARIANT),
        contentDescription = title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        placeholder = { CoverTitle(title = title) },
    )
}

@Composable
private fun CoverTitle(title: String) {
    Box(
        modifier = Modifier.padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChapterControls(
    state: DetailsUiState,
    onAction: (DetailsAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChapterOrder.entries.forEach { order ->
                FilterChip(
                    selected = state.order == order,
                    onClick = { onAction(DetailsAction.OrderSelected(order)) },
                    label = { Text(order.label()) },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { onAction(DetailsAction.Refresh) }) { Text("Refresh") }
        }

        if (state.groups.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.selectedGroup == null,
                    onClick = { onAction(DetailsAction.GroupSelected(null)) },
                    label = { Text("All") },
                )
                state.groups.forEach { group ->
                    FilterChip(
                        selected = state.selectedGroup == group,
                        onClick = { onAction(DetailsAction.GroupSelected(group)) },
                        label = { Text(group, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    onOpen: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = (chapter.index + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun ChapterOrder.label(): String = when (this) {
    ChapterOrder.SourceOrder -> "Source order"
    ChapterOrder.Reversed -> "Reversed"
}

internal const val DETAILS_LOADING_TAG = "details-loading"

/** Keeps a cover's cache entry apart from a page that happens to reuse the same URL. */
private const val COVER_VARIANT = "cover"

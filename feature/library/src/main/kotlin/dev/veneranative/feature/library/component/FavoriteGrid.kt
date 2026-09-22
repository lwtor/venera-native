package dev.veneranative.feature.library.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.image.compose.ComicImage
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.comicKeyOrNull
import dev.veneranative.data.collection.FavoriteFolder
import dev.veneranative.data.collection.FavoriteItem

private const val COVER_VARIANT = "shelf-cover"

/**
 * The shelf itself: covers in a grid, with the two things the user does to a comic right on the
 * card — dismissing an update marker, and moving or removing it through the overflow menu.
 */
@Composable
fun FavoriteGrid(
    items: List<FavoriteItem>,
    folders: List<FavoriteFolder>,
    onOpenComic: (ComicKey) -> Unit,
    onClearUpdate: (ComicRef) -> Unit,
    onMove: (ComicRef, String) -> Unit,
    onRemove: (ComicRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = items.size, key = { index -> items[index].ref.toString() }) { index ->
            FavoriteCard(
                item = items[index],
                folders = folders,
                onOpenComic = onOpenComic,
                onClearUpdate = onClearUpdate,
                onMove = onMove,
                onRemove = onRemove,
            )
        }
    }
}

@Composable
private fun FavoriteCard(
    item: FavoriteItem,
    folders: List<FavoriteFolder>,
    onOpenComic: (ComicKey) -> Unit,
    onClearUpdate: (ComicRef) -> Unit,
    onMove: (ComicRef, String) -> Unit,
    onRemove: (ComicRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        // Only a comic that came from a source can be opened; an imported one has no details screen
        // to go to yet.
        onClick = { item.ref.comicKeyOrNull()?.let(onOpenComic) },
    ) {
        Box {
            Column(modifier = Modifier.padding(8.dp)) {
                Cover(
                    item = item,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.hasUpdate) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "New chapters",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onClearUpdate(item.ref) }) {
                            Text("Mark read")
                        }
                    }
                }
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                TextButton(onClick = { menuExpanded = true }) {
                    Text("···")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    folders.forEach { folder ->
                        if (folder.id != item.folderId) {
                            DropdownMenuItem(
                                text = { Text("Move to ${folder.name}") },
                                onClick = {
                                    menuExpanded = false
                                    onMove(item.ref, folder.id)
                                },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Remove from shelf") },
                        onClick = {
                            menuExpanded = false
                            onRemove(item.ref)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The cover, rendered by the shared comic image pipeline.
 *
 * A local comic has no source id, and the pipeline cannot fetch a cover without one, so those fall
 * back to the title placeholder instead of asking for something that is not a URL.
 */
@Composable
private fun Cover(
    item: FavoriteItem,
    modifier: Modifier = Modifier,
) {
    val comicKey = item.ref.comicKeyOrNull()
    val cover = item.coverRef
    val request = if (comicKey != null && cover != null) {
        ComicImageRequest(url = cover, sourceId = comicKey.sourceId, variant = COVER_VARIANT)
    } else {
        null
    }

    ComicImage(
        request = request,
        contentDescription = item.title,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = { CoverPlaceholder(title = item.title) },
    )
}

@Composable
private fun CoverPlaceholder(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp),
        )
    }
}

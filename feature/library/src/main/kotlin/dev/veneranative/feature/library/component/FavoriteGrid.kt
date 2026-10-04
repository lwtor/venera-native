package dev.veneranative.feature.library.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.veneranative.data.collection.FavoriteItem

private const val COVER_VARIANT = "shelf-cover"

/** Three-column cover shelf. Tap opens a comic; long-press opens its action sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoriteGrid(
    items: List<FavoriteItem>,
    onOpenComic: (ComicKey) -> Unit,
    onLongPress: (ComicRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = items.size, key = { index -> items[index].ref.toString() }) { index ->
            val item = items[index]
            val comicKey = item.ref.comicKeyOrNull()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { comicKey?.let(onOpenComic) },
                        onLongClick = { onLongPress(item.ref) },
                    ),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box {
                    Cover(
                        item = item,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.75f)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    if (item.hasUpdate) {
                        Surface(
                            color = MaterialTheme.colorScheme.error,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(bottomEnd = 8.dp),
                            modifier = Modifier.align(Alignment.TopStart),
                        ) {
                            Text(
                                text = "更新",
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondaryLabel = item.subtitle?.takeIf(String::isNotBlank)
                if (secondaryLabel != null) {
                    Text(
                        text = secondaryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Cover(item: FavoriteItem, modifier: Modifier = Modifier) {
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
private fun CoverPlaceholder(title: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(4.dp),
        )
    }
}

package dev.veneranative.feature.library.component

import dev.veneranative.core.designsystem.component.comicSourceLabel
import dev.veneranative.core.designsystem.component.ComicCoverCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.comicKeyOrNull
import dev.veneranative.data.collection.FavoriteItem

/** Three-column cover shelf. Tap opens a comic; long-press opens its action sheet. */
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
            val coverRef = item.coverRef
            ComicCoverCard(
                title = item.title,
                request = if (comicKey != null && coverRef != null)
                    ComicImageRequest(coverRef, sourceId = comicKey.sourceId, variant = "comic-cover") else null,
                lastUpdated = item.lastUpdated,
                sourceLabel = comicSourceLabel(comicKey?.sourceId?.value),
                onClick = { comicKey?.let(onOpenComic) },
                onLongClick = { onLongPress(item.ref) },
                hasUpdate = item.hasUpdate,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

package dev.veneranative.feature.library.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.veneranative.data.collection.FavoriteFolder

/**
 * The folder filter: "all" plus one chip per folder.
 *
 * Selecting null is how the screen asks for the whole collection, which is why the folder id here is
 * nullable rather than a sentinel value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderChips(
    folders: List<FavoriteFolder>,
    selectedFolderId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(
                selected = selectedFolderId == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
            )
        }
        items(count = folders.size, key = { index -> folders[index].id }) { index ->
            val folder = folders[index]
            FilterChip(
                selected = folder.id == selectedFolderId,
                onClick = { onSelect(folder.id) },
                label = { Text(folder.name) },
            )
        }
    }
}

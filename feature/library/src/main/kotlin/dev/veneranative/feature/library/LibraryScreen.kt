package dev.veneranative.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.LocalComicId
import dev.veneranative.data.collection.FavoriteFolder
import dev.veneranative.data.collection.FavoriteItem
import dev.veneranative.data.collection.ShelfSort
import dev.veneranative.feature.library.component.FavoriteGrid
import dev.veneranative.feature.library.component.FolderChips

/**
 * The library screen: the favourites shelf.
 *
 * It renders [LibraryUiState] and sends [LibraryAction]s and nothing else — no repository, no
 * sorting, no dialog state of its own. Folder management acts on the selected folder, so creating,
 * renaming and deleting are three obvious buttons instead of a gesture the user has to discover.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    onAction: (LibraryAction) -> Unit,
    onOpenComic: (ComicKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedFolder = state.folders.firstOrNull { it.id == state.selectedFolderId }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { onAction(LibraryAction.RefreshUpdates) }) {
                        Text("Check updates")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            state.message?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onAction(LibraryAction.DismissMessage) }) { Text("OK") }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TextButton(onClick = { onAction(LibraryAction.SelectTab(LibraryTab.Favorites)) }) { Text("Favorites") }
                TextButton(onClick = { onAction(LibraryAction.SelectTab(LibraryTab.Local)) }) { Text("Local") }
            }

            if (state.tab == LibraryTab.Favorites) {
            FolderChips(
                folders = state.folders,
                selectedFolderId = state.selectedFolderId,
                onSelect = { folderId -> onAction(LibraryAction.SelectFolder(folderId)) },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onAction(LibraryAction.EditFolder(null)) }) { Text("New folder") }
                TextButton(
                    enabled = selectedFolder != null,
                    onClick = {
                        selectedFolder?.let { onAction(LibraryAction.EditFolder(it.id)) }
                    },
                ) { Text("Rename") }
                TextButton(
                    enabled = selectedFolder?.removable == true,
                    onClick = {
                        selectedFolder?.let { onAction(LibraryAction.DeleteFolder(it.id)) }
                    },
                ) { Text("Delete") }
            }

            ShelfSortChips(
                sort = state.sort,
                onSortChange = { sort -> onAction(LibraryAction.ChangeSort(sort)) },
            )

            when (state.status) {
                LibraryStatus.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                LibraryStatus.Empty -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text("Nothing on the shelf yet.") }

                LibraryStatus.Failed -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Text(state.message ?: "The shelf could not be read.")
                    Button(onClick = { onAction(LibraryAction.Retry) }) { Text("Try again") }
                }

                LibraryStatus.Ready -> FavoriteGrid(
                    items = state.items,
                    folders = state.folders,
                    onOpenComic = onOpenComic,
                    onClearUpdate = { ref -> onAction(LibraryAction.ClearUpdate(ref)) },
                    onMove = { ref, folderId -> onAction(LibraryAction.MoveItem(ref, folderId)) },
                    onRemove = { ref -> onAction(LibraryAction.RemoveItem(ref)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Button(onClick = { onAction(LibraryAction.RequestLocalImport) }, modifier = Modifier.padding(16.dp)) { Text("Import directory") }
                    if (state.localComics.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No local directories imported.") }
                    else LazyColumn(Modifier.fillMaxSize()) {
                        items(state.localComics, key = { it.id.value }) { comic ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(comic.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${comic.chapterCount} chapters", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { onAction(LibraryAction.RemoveLocalComic(comic.id)) }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
    }

    state.folderEditor?.let { editor ->
        AlertDialog(
            onDismissRequest = { onAction(LibraryAction.DismissFolderEditor) },
            title = { Text(if (editor.folderId == null) "New folder" else "Rename folder") },
            text = {
                OutlinedTextField(
                    value = editor.draft,
                    onValueChange = { draft -> onAction(LibraryAction.FolderDraftChanged(draft)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { onAction(LibraryAction.ConfirmFolderEditor) }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { onAction(LibraryAction.DismissFolderEditor) }) { Text("Cancel") }
            },
        )
    }
}

/** The four shelf orders, as chips: picking one re-runs the query rather than re-sorting a list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfSortChips(
    sort: ShelfSort,
    onSortChange: (ShelfSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count = ShelfSort.entries.size, key = { index -> ShelfSort.entries[index].name }) { index ->
            val option = ShelfSort.entries[index]
            FilterChip(
                selected = option == sort,
                onClick = { onSortChange(option) },
                label = { Text(option.label()) },
            )
        }
    }
}

private fun ShelfSort.label(): String = when (this) {
    ShelfSort.AddedAt -> "Recently added"
    ShelfSort.Title -> "Title"
    ShelfSort.LastRead -> "Last read"
    ShelfSort.Updated -> "Updated"
}

@Preview(showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    VeneraNativeTheme {
        LibraryScreen(
            state = LibraryUiState(
                status = LibraryStatus.Ready,
                folders = listOf(
                    FavoriteFolder(id = "default", name = "Default", sortOrder = 0, removable = false),
                    FavoriteFolder(id = "reading", name = "Reading", sortOrder = 1, removable = true),
                ),
                items = listOf(
                    FavoriteItem(
                        ref = ComicRef.Local(LocalComicId("local-1")),
                        title = "An imported comic",
                        folderId = "default",
                        addedAtEpochMillis = 1_000L,
                    ),
                ),
            ),
            onAction = {},
            onOpenComic = {},
            onBack = {},
        )
    }
}

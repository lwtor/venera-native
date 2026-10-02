package dev.veneranative.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.veneranative.core.designsystem.VeneraNativeTheme
import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.SourceCatalogEntry

/**
 * Stateless sources screen: renders [state] and sends [onAction].
 *
 * All four states are explicit — loading, failed with retry, ready but empty, and ready with a list
 * — because "no sources" and "could not read sources" are different problems for the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseScript: () -> Unit = {},
) {
    var pendingUninstall by remember { mutableStateOf<InstalledSource?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            InstallRow(state = state, onAction = onAction, onChooseScript = onChooseScript)
            state.message?.let { message ->
                MessageRow(message = message, onDismiss = { onAction(SourcesAction.DismissMessage) })
            }
            CatalogHeader(state = state, onAction = onAction)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state.catalogStatus) {
                    CatalogStatus.Idle -> item { Text("Source catalog is not configured.") }
                    CatalogStatus.Loading -> item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.testTag(CATALOG_LOADING_TAG))
                            Text("Loading source catalog…")
                        }
                    }
                    CatalogStatus.Failed -> item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Could not load the catalog. Check the URL or network and retry.", modifier = Modifier.weight(1f))
                            TextButton(onClick = { onAction(SourcesAction.RefreshCatalog) }) { Text("Retry") }
                        }
                    }
                    CatalogStatus.Ready -> if (state.catalogEntries.isEmpty()) {
                        item { Text("No usable sources in this catalog.") }
                    } else {
                        items(state.catalogEntries, key = { it.scriptUrl }) { entry ->
                            CatalogSourceRow(
                                entry = entry,
                                installed = entry.key?.let { key -> state.sources.any { it.sourceId.value == key } } == true,
                                installing = state.installing,
                                onInstall = { onAction(SourcesAction.InstallCatalogEntry(entry)) },
                            )
                        }
                    }
                }
                item { Text("Installed sources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                when (state.status) {
                    SourcesStatus.Loading -> item { CircularProgressIndicator(modifier = Modifier.testTag(LOADING_TAG)) }
                    SourcesStatus.Failed -> item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Installed sources could not be read.", modifier = Modifier.weight(1f))
                            TextButton(onClick = { onAction(SourcesAction.Retry) }) { Text("Retry") }
                        }
                    }
                    SourcesStatus.Ready -> if (state.isEmpty) {
                        item { Text("No sources installed yet. Choose a source above or install a local script.") }
                    } else {
                        items(state.sources, key = { "installed-${it.sourceId.value}" }) { source ->
                            SourceRow(
                                source = source,
                                busy = source.sourceId in state.busySourceIds,
                                onEnabledChange = { enabled -> onAction(SourcesAction.SetEnabled(source.sourceId, enabled)) },
                                onUninstall = { pendingUninstall = source },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingUninstall?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("Remove source?") },
            text = { Text("${source.name} and its stored script will be removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUninstall = null
                        onAction(SourcesAction.Uninstall(source.sourceId))
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CatalogHeader(state: SourcesUiState, onAction: (SourcesAction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Venera source catalog", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.catalogLocation,
            onValueChange = { onAction(SourcesAction.CatalogLocationChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Catalog JSON URL") },
            singleLine = true,
            enabled = state.catalogStatus != CatalogStatus.Loading,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state.catalogStatus) {
                    CatalogStatus.Idle -> "Catalog not loaded"
                    CatalogStatus.Loading -> "Fetching source list…"
                    CatalogStatus.Ready -> "${state.catalogEntries.size} sources available"
                    CatalogStatus.Failed -> "Catalog unavailable"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { onAction(SourcesAction.RefreshCatalog) },
                enabled = state.catalogStatus != CatalogStatus.Loading && state.catalogLocation.isNotBlank(),
            ) { Text("Refresh") }
        }
    }
}

@Composable
private fun CatalogSourceRow(
    entry: SourceCatalogEntry,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                entry.version?.let { Text("Version $it", style = MaterialTheme.typography.bodySmall) }
                entry.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(
                onClick = onInstall,
                modifier = Modifier.testTag(CATALOG_INSTALL_TAG),
                enabled = !installing && !installed,
            ) {
                Text(if (installed) "Installed" else if (installing) "Installing…" else "Install")
            }
        }
    }
}

@Composable
private fun InstallRow(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
    onChooseScript: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.installLocation,
            onValueChange = { onAction(SourcesAction.InstallLocationChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Source script path or file URL") },
            singleLine = true,
            enabled = !state.installing,
        )
        Button(
            onClick = { onAction(SourcesAction.Install) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canInstall,
        ) {
            Text(if (state.installing) "Installing…" else "Install")
        }
        TextButton(onClick = onChooseScript, enabled = !state.installing) {
            Text("Choose JavaScript file")
        }
        if (state.installing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SourceRow(
    source: InstalledSource,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Version ${source.version}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = source.origin,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = onEnabledChange,
                enabled = !busy,
            )
            TextButton(onClick = onUninstall, enabled = !busy) { Text("Remove") }
        }
    }
}

@Composable
private fun MessageRow(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

internal const val LOADING_TAG = "sources-loading"
internal const val CATALOG_LOADING_TAG = "source-catalog-loading"
internal const val CATALOG_INSTALL_TAG = "source-catalog-install"

@Preview(showBackground = true)
@Composable
private fun SourcesScreenPreview() {
    VeneraNativeTheme {
        SourcesScreen(
            state = SourcesUiState(
                status = SourcesStatus.Ready,
                sources = listOf(
                    InstalledSource(
                        sourceId = SourceId("demo"),
                        name = "Demo Source",
                        version = "1.0.0",
                        enabled = true,
                        origin = "content://demo/source.js",
                    ),
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

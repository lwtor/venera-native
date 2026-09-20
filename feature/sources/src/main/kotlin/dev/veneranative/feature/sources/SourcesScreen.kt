package dev.veneranative.feature.sources

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
            InstallRow(state = state, onAction = onAction)
            state.message?.let { message ->
                MessageRow(message = message, onDismiss = { onAction(SourcesAction.DismissMessage) })
            }

            when (state.status) {
                SourcesStatus.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.testTag(LOADING_TAG))
                }

                SourcesStatus.Failed -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = state.message ?: "The source list could not be read.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { onAction(SourcesAction.Retry) }) { Text("Retry") }
                    }
                }

                SourcesStatus.Ready -> if (state.isEmpty) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "No sources installed yet.",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Install a comic source by entering its file path above.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.sources, key = { it.sourceId.value }) { source ->
                            SourceRow(
                                source = source,
                                busy = source.sourceId in state.busySourceIds,
                                onEnabledChange = { enabled ->
                                    onAction(SourcesAction.SetEnabled(source.sourceId, enabled))
                                },
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
private fun InstallRow(
    state: SourcesUiState,
    onAction: (SourcesAction) -> Unit,
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
                        origin = "/sdcard/demo.js",
                    ),
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}

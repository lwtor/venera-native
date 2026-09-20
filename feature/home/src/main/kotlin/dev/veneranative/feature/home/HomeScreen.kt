package dev.veneranative.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.veneranative.core.designsystem.VeneraNativeTheme

/**
 * Home screen.
 *
 * It exposes navigation as callbacks instead of depending on the target features: the assembly layer
 * decides where "sources" and "reader" actually are.
 */
@Composable
fun HomeRoute(
    onOpenReader: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    HomeScreen(
        onOpenReader = onOpenReader,
        onOpenSources = onOpenSources,
        onOpenExplore = onOpenExplore,
        onOpenSearch = onOpenSearch,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    onOpenReader: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Venera Native") }) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Android-native foundation is ready",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Install a comic source, then browse or search it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenSources) {
                Text("Sources")
            }
            Button(onClick = onOpenExplore) {
                Text("Explore")
            }
            Button(onClick = onOpenSearch) {
                Text("Search")
            }
            Button(onClick = onOpenReader) {
                Text("Open reader prototype")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    VeneraNativeTheme {
        HomeScreen(onOpenReader = {}, onOpenSources = {}, onOpenExplore = {}, onOpenSearch = {})
    }
}

package dev.veneranative.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.TagNavigation
import dev.veneranative.data.comic.ComicCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorWorksRoute(
    catalog: ComicCatalog,
    origin: ComicKey,
    author: String,
    target: TagNavigation,
    onOpenComic: (ComicKey) -> Unit,
    onBack: () -> Unit,
) {
    val model: AuthorWorksViewModel = viewModel(key = "author-works:$origin:$author:$target") {
        AuthorWorksViewModel(catalog, origin, author, target)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val resultTags by model.resultTags.collectAsStateWithLifecycle()
    val results = model.results.collectAsLazyPagingItems()
    if (state.status == SearchStatus.Ready) {
        SearchResultScreen(
            state, results, emptyList(), {}, onOpenComic, onBack,
            resultTags = resultTags, onResultVisible = model::onResultVisible, onResultHidden = model::onResultHidden,
        )
    } else {
        Scaffold(topBar = {
            TopAppBar(title = { Text("相关作品 · $author") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            })
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.status == SearchStatus.Loading) CircularProgressIndicator() else {
                    Text(state.message ?: "无法加载相关作品。")
                    Button(onClick = model::retry) { Text("重试") }
                }
            }
        }
    }
}

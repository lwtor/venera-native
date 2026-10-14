package dev.veneranative.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.data.collection.CollectionRepository
import dev.veneranative.data.collection.ShelfSort
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.local.LocalComicRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

class HomeViewModel(
    history: HistoryRepository?,
    collection: CollectionRepository?,
    local: LocalComicRepository?,
) : ViewModel() {

    private val chapterLoader = collection?.let { dev.veneranative.data.collection.FavoriteChapterLoader(it, viewModelScope) }
    fun onFavoriteVisible(item: dev.veneranative.data.collection.FavoriteItem) { chapterLoader?.visible(item) }
    fun onFavoriteHidden(ref: dev.veneranative.core.model.ComicRef) { chapterLoader?.hidden(ref) }

    private val recent = history?.observeRecent(5) ?: flowOf(emptyList())
    private val favorites = collection?.observeItems(folderId = null, sort = ShelfSort.Updated)
        ?: flowOf(emptyList())
    private val localComics = local?.observeComics() ?: flowOf(emptyList())

    val state = combine(recent, favorites, localComics, chapterLoader?.states ?: flowOf(emptyMap())) { recentItems, favoriteItems, localItems, catalogStates ->
        HomeUiState(
            recentReading = recentItems
                .sortedByDescending { it.updatedAtEpochMillis }
                .distinctBy { it.comicKey },
            favorites = favoriteItems,
            catalogStates = catalogStates,
            localComics = localItems,
            historyAvailable = history != null,
            favoritesAvailable = collection != null,
            localLibraryAvailable = local != null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = HomeUiState(
            historyAvailable = history != null,
            favoritesAvailable = collection != null,
            localLibraryAvailable = local != null,
        ),
    )
}

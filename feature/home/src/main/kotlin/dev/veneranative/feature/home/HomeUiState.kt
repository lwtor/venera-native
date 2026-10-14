package dev.veneranative.feature.home

import dev.veneranative.data.collection.FavoriteItem
import dev.veneranative.data.history.ReadingHistoryEntry
import dev.veneranative.data.local.LocalComic

data class HomeUiState(
    val recentReading: List<ReadingHistoryEntry> = emptyList(),
    val favorites: List<FavoriteItem> = emptyList(),
    val catalogStates: Map<dev.veneranative.core.model.ComicRef, dev.veneranative.data.collection.FavoriteCatalogState> = emptyMap(),
    val localComics: List<LocalComic> = emptyList(),
    val historyAvailable: Boolean = false,
    val favoritesAvailable: Boolean = false,
    val localLibraryAvailable: Boolean = false,
)

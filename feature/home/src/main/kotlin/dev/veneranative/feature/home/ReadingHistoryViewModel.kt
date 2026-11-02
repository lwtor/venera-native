package dev.veneranative.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.ComicKey
import dev.veneranative.data.history.HistoryRepository
import dev.veneranative.data.history.ReadingHistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReadingHistoryUiState(
    val entries: List<ReadingHistoryEntry> = emptyList(),
    val deleting: Set<ComicKey> = emptySet(),
    val error: String? = null,
)

class ReadingHistoryViewModel(private val history: HistoryRepository) : ViewModel() {
    private val deleting = MutableStateFlow<Set<ComicKey>>(emptySet())
    private val error = MutableStateFlow<String?>(null)

    val state = combine(history.observeAllRecent(), deleting, error) { entries, deletingKeys, message ->
        ReadingHistoryUiState(entries = entries, deleting = deletingKeys, error = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingHistoryUiState())

    fun remove(entry: ReadingHistoryEntry) {
        val key = entry.comicKey
        if (key in deleting.value) return
        deleting.value = deleting.value + key
        error.value = null
        viewModelScope.launch {
            try {
                history.remove(key)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                error.value = "删除阅读记录失败，请重试。"
            } finally {
                deleting.value = deleting.value - key
            }
        }
    }

    fun dismissError() {
        error.value = null
    }
}

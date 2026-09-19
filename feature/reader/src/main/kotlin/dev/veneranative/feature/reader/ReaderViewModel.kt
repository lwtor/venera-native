package dev.veneranative.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.ChapterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns reader state and prefetch scheduling.
 *
 * Prefetching is expressed in page indices rather than images, so the scheduling logic can be
 * tested without any decoding pipeline in place.
 */
class ReaderViewModel(
    private val chapter: ChapterKey,
    private val provider: PageProvider,
    private val prefetchRadius: Int = DEFAULT_PREFETCH_RADIUS,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val prefetched = mutableSetOf<Int>()

    init {
        load()
    }

    fun onAction(action: ReaderAction) {
        when (action) {
            ReaderAction.Retry -> load()
            is ReaderAction.PageShown -> showPage(action.index)
            is ReaderAction.ChangeDirection -> _state.update { it.copy(direction = action.direction) }
        }
    }

    private fun load() {
        _state.update { it.copy(status = ReaderStatus.Loading) }
        viewModelScope.launch {
            try {
                val content = provider.loadChapter(chapter)
                prefetched.clear()
                _state.update { current ->
                    current.copy(
                        chapterTitle = content.title,
                        pages = content.pages,
                        currentPageIndex = current.currentPageIndex
                            .coerceIn(0, content.pages.lastIndex.coerceAtLeast(0)),
                        status = ReaderStatus.Ready,
                    )
                }
                prefetchAround(_state.value.currentPageIndex)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(status = ReaderStatus.Failed) }
            }
        }
    }

    private fun showPage(index: Int) {
        val pages = _state.value.pages
        if (pages.isEmpty()) return
        val target = index.coerceIn(0, pages.lastIndex)
        if (target == _state.value.currentPageIndex) return
        _state.update { it.copy(currentPageIndex = target) }
        prefetchAround(target)
    }

    private fun prefetchAround(center: Int) {
        val pages = _state.value.pages
        if (pages.isEmpty()) return
        val first = (center - prefetchRadius).coerceAtLeast(0)
        val last = (center + prefetchRadius).coerceAtMost(pages.lastIndex)
        for (index in first..last) {
            if (!prefetched.add(index)) continue
            viewModelScope.launch {
                try {
                    provider.prefetch(pages[index])
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // A failed warm-up must not break reading; the page still loads on demand.
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_PREFETCH_RADIUS = 1
    }
}

package dev.veneranative.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.PageProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


/**
 * Owns reader state and prefetch scheduling.
 *
 * Prefetching is expressed in page indices rather than images, so the scheduling logic can be
 * tested without any decoding pipeline in place.
 */
class ReaderViewModel(
    private val chapter: ChapterKey,
    private val provider: PageProvider,
    /** Page to open at; a resumed session starts where it was left. */
    private val startPageIndex: Int = 0,
    /** Reported whenever the visible page changes, for throttled persistence. Null disables saving. */
    private val recorder: ChapterPageRecorder? = null,
    private val prefetchRadius: Int = DEFAULT_PREFETCH_RADIUS,
) : ViewModel() {

    /** Persists where a reader is, one call per page turn. */
    fun interface ChapterPageRecorder {
        suspend fun record(chapterTitle: String, pageIndex: Int, pageCount: Int)
    }

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
                val resumedIndex = startPageIndex.coerceIn(0, content.pages.lastIndex.coerceAtLeast(0))
                _state.update { current ->
                    current.copy(
                        chapterTitle = content.title,
                        pages = content.pages,
                        currentPageIndex = resumedIndex,
                        status = ReaderStatus.Ready,
                    )
                }
                prefetchAround(resumedIndex)
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
        recorder?.let { sink ->
            viewModelScope.launch {
                runCatching { sink.record(_state.value.chapterTitle, target, pages.size) }
            }
        }
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

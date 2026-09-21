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
    private val pageJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

    init {
        load()
    }

    fun onAction(action: ReaderAction) {
        when (action) {
            ReaderAction.Retry -> load()
            is ReaderAction.RetryPage -> {
                prefetched.remove(action.index)
                resolvePage(action.index)
            }
            is ReaderAction.PageShown -> showPage(action.index)
            is ReaderAction.ChangeDirection -> _state.update { it.copy(direction = action.direction) }
        }
    }

    private fun load() {
        _state.update { it.copy(status = ReaderStatus.Loading) }
        viewModelScope.launch {
            try {
                val content = provider.loadChapter(chapter)
                pageJobs.values.forEach { it.cancel() }
                pageJobs.clear()
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
        pageJobs.filterKeys { it !in first..last }.values.forEach { it.cancel() }
        for (index in first..last) resolvePage(index)
    }

    private fun resolvePage(index: Int) {
        val page = _state.value.pages.getOrNull(index) ?: return
        if (index in prefetched || pageJobs[index]?.isActive == true) return
        pageJobs[index] = viewModelScope.launch {
            try {
                if (page.sizeState == dev.veneranative.core.model.PageSizeState.Ready) {
                    provider.prefetch(page)
                } else {
                    updatePage(index, page.copy(sizeState = dev.veneranative.core.model.PageSizeState.Pending))
                    updatePage(index, provider.resolve(page))
                }
                prefetched.add(index)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (page.sizeState != dev.veneranative.core.model.PageSizeState.Ready) {
                    updatePage(index, page.copy(sizeState = dev.veneranative.core.model.PageSizeState.Failed))
                }
            }
        }
    }

    private fun updatePage(index: Int, page: dev.veneranative.core.model.ComicPage) {
        _state.update { current ->
            current.copy(pages = current.pages.mapIndexed { position, old -> if (position == index) page else old })
        }
    }

    private companion object {
        const val DEFAULT_PREFETCH_RADIUS = 1
    }
}

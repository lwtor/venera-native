package dev.veneranative.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
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
    private val chapter: ChapterRef,
    private val provider: PageProvider,
    /** Page to open at; a resumed session starts where it was left. */
    private val startPageIndex: Int = 0,
    /** Reported whenever the visible page changes, for throttled persistence. Null disables saving. */
    private val progress: dev.veneranative.core.model.ReaderProgress? = null,
    private val prefetchRadius: Int = DEFAULT_PREFETCH_RADIUS,
) : ViewModel() {

    private var content: dev.veneranative.core.model.ChapterContent? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val prefetched = mutableSetOf<Int>()
    private val pageJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

    constructor(
        chapter: ChapterKey,
        provider: PageProvider,
        startPageIndex: Int = 0,
        progress: dev.veneranative.core.model.ReaderProgress? = null,
        prefetchRadius: Int = DEFAULT_PREFETCH_RADIUS,
    ) : this(ChapterRef.Remote(chapter), provider, startPageIndex, progress, prefetchRadius)

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
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val resumePage = progress?.resumePage() ?: startPageIndex
                val content = provider.loadChapter(chapter)
                this@ReaderViewModel.content = content
                pageJobs.values.forEach { it.cancel() }
                pageJobs.clear()
                prefetched.clear()
                val resumedIndex = resumePage.coerceIn(0, content.pages.lastIndex.coerceAtLeast(0))
                _state.update { current ->
                    current.copy(
                        chapterTitle = content.title,
                        pages = content.pages,
                        currentPageIndex = resumedIndex,
                        status = ReaderStatus.Ready,
                    )
                }
                progress?.record(content, resumedIndex)
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
        content?.let { progress?.record(it, target) }
        prefetchAround(target)
    }

    private fun prefetchAround(center: Int) {
        val pages = _state.value.pages
        if (pages.isEmpty()) return
        val first = (center - prefetchRadius).coerceAtLeast(0)
        val last = (center + prefetchRadius).coerceAtMost(pages.lastIndex)
        pageJobs.keys.filter { it !in first..last }.forEach { pageJobs.remove(it)?.cancel() }
        prefetched.removeAll { it !in first..last }
        for (index in first..last) resolvePage(index)
    }

    private fun resolvePage(index: Int) {
        val page = _state.value.pages.getOrNull(index) ?: return
        if (index in prefetched || pageJobs[index]?.isActive == true) return
        pageJobs[index] = viewModelScope.launch {
            try {
                if (page.sizeState == dev.veneranative.core.model.PageSizeState.Ready && chapter is ChapterRef.Local) {
                    // A bounded SAF cache may have evicted this page while it was off screen.
                    updatePage(index, page.copy(sizeState = dev.veneranative.core.model.PageSizeState.Pending))
                    updatePage(index, provider.resolve(page))
                } else if (page.sizeState == dev.veneranative.core.model.PageSizeState.Ready) {
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

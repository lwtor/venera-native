package dev.veneranative.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.PageSizeState
import dev.veneranative.core.model.ReaderProgress
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
    private val progress: ReaderProgress? = null,
    private val prefetchRadius: Int = DEFAULT_PREFETCH_RADIUS,
    private val progressFactory: (ChapterRef) -> ReaderProgress? = { ref -> progress.takeIf { ref == chapter } },
) : ViewModel() {

    private data class ChapterSegment(
        val chapter: ChapterRef,
        val content: ChapterContent,
        val startPageIndex: Int,
        val progress: ReaderProgress?,
    ) {
        val endPageIndex: Int get() = startPageIndex + content.pages.lastIndex
    }

    private val chapters = mutableListOf<ChapterSegment>()
    private val loadedChapters = mutableSetOf<ChapterRef>()
    private var loadJob: kotlinx.coroutines.Job? = null
    private var nextChapterLoadJob: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val prefetched = mutableSetOf<Int>()
    private val pageJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

    constructor(
        chapter: ChapterKey,
        provider: PageProvider,
        startPageIndex: Int = 0,
        progress: ReaderProgress? = null,
        prefetchRadius: Int = DEFAULT_PREFETCH_RADIUS,
        progressFactory: (ChapterRef) -> ReaderProgress? = { ref -> progress.takeIf { ref == ChapterRef.Remote(chapter) } },
    ) : this(ChapterRef.Remote(chapter), provider, startPageIndex, progress, prefetchRadius, progressFactory)

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
            ReaderAction.LoadNextChapter -> loadNextChapter()
            ReaderAction.RetryNextChapter -> {
                _state.update { it.copy(nextChapterLoadFailed = false) }
                loadNextChapter()
            }
        }
    }

    private fun load() {
        _state.update { it.copy(status = ReaderStatus.Loading) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                chapters.clear()
                loadedChapters.clear()
                nextChapterLoadJob?.cancel()
                val chapterProgress = progressFactory(chapter)
                val resumePage = chapterProgress?.resumePage() ?: startPageIndex
                val content = provider.loadChapter(chapter)
                pageJobs.values.forEach { it.cancel() }
                pageJobs.clear()
                prefetched.clear()
                val resumedIndex = resumePage.coerceIn(0, content.pages.lastIndex.coerceAtLeast(0))
                chapters += ChapterSegment(chapter, content, 0, chapterProgress)
                loadedChapters += chapter
                _state.update { current ->
                    current.copy(
                        chapterTitle = content.title,
                        pages = content.pages.mapIndexed { index, page -> page.copy(index = index) },
                        currentPageIndex = resumedIndex,
                        nextChapter = content.nextChapter,
                        isLoadingNextChapter = false,
                        nextChapterLoadFailed = false,
                        status = ReaderStatus.Ready,
                    )
                }
                chapterProgress?.record(content, resumedIndex)
                prefetchAround(resumedIndex)
                content.nextChapter?.let { next ->
                    viewModelScope.launch { provider.prefetchChapter(ChapterRef.Remote(next.key)) }
                }
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
        val segment = chapters.lastOrNull { target in it.startPageIndex..it.endPageIndex }
        _state.update { it.copy(currentPageIndex = target, chapterTitle = segment?.content?.title ?: it.chapterTitle) }
        segment?.let { loaded ->
            loaded.progress?.record(loaded.content, target - loaded.startPageIndex)
        }
        prefetchAround(target)
    }

    private fun loadNextChapter() {
        val next = _state.value.nextChapter ?: return
        val nextRef = ChapterRef.Remote(next.key)
        if (nextRef in loadedChapters || nextChapterLoadJob?.isActive == true) return
        _state.update { it.copy(isLoadingNextChapter = true, nextChapterLoadFailed = false) }
        nextChapterLoadJob = viewModelScope.launch {
            try {
                val content = provider.loadChapter(nextRef)
                if (content.pages.isEmpty()) throw IllegalStateException("Chapter has no pages")
                val startIndex = _state.value.pages.size
                val segmentProgress = progressFactory(nextRef)
                chapters += ChapterSegment(nextRef, content, startIndex, segmentProgress)
                loadedChapters += nextRef
                val appended = content.pages.mapIndexed { offset, page -> page.copy(index = startIndex + offset) }
                _state.update { current ->
                    current.copy(
                        pages = current.pages + appended,
                        nextChapter = content.nextChapter,
                        isLoadingNextChapter = false,
                        nextChapterLoadFailed = false,
                    )
                }
                content.nextChapter?.let { following ->
                    viewModelScope.launch { provider.prefetchChapter(ChapterRef.Remote(following.key)) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(isLoadingNextChapter = false, nextChapterLoadFailed = true) }
            }
        }
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
                val localChapter = chapters.lastOrNull { index in it.startPageIndex..it.endPageIndex }?.chapter is ChapterRef.Local
                if (page.sizeState == PageSizeState.Ready && localChapter) {
                    // A bounded SAF cache may have evicted this page while it was off screen.
                    updatePage(index, page.copy(sizeState = dev.veneranative.core.model.PageSizeState.Pending))
                    updatePage(index, provider.resolve(page))
                } else if (page.sizeState == PageSizeState.Ready) {
                    provider.prefetch(page)
                } else {
                    updatePage(index, page.copy(sizeState = PageSizeState.Pending))
                    updatePage(index, provider.resolve(page))
                }
                prefetched.add(index)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (page.sizeState != PageSizeState.Ready) {
                    updatePage(index, page.copy(sizeState = PageSizeState.Failed))
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

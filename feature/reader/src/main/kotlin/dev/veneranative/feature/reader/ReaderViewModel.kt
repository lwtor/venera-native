package dev.veneranative.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageCrop
import dev.veneranative.core.model.PageProvider
import dev.veneranative.core.model.PageSizeState
import dev.veneranative.core.model.PageSplit
import dev.veneranative.core.model.ReaderProgress
import dev.veneranative.core.model.SplitPreferenceRepository
import dev.veneranative.core.model.Chapter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay


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
    private val chapterLoadTimeoutMillis: Long = 120_000L,
    private val pageLoadTimeoutMillis: Long = 30_000L,
    /** Per-chapter split preferences; null disables splitting and keeps the reader behaviour unchanged. */
    private val splitPreference: SplitPreferenceRepository? = null,
) : ViewModel() {

    private data class ChapterSegment(
        val chapter: ChapterRef,
        val content: ChapterContent,
        val startPageIndex: Int,
        val progress: ReaderProgress?,
        /** Number of display pages this chapter occupies; differs from `content.pages.size` when split. */
        val displayPageCount: Int,
        /** Split preference of this chapter, replayed into the UI state when it becomes visible. */
        val splitEnabled: Boolean = false,
        val excludedPages: Set<Int> = emptySet(),
        /** Per-comic split tuning, replayed alongside [splitEnabled] when this chapter is shown. */
        val splitOverlapFraction: Float = PageSplit.DEFAULT_OVERLAP_FRACTION,
        val splitRightToLeft: Boolean = false,
        /**
         * Source pages carrying real dimensions, kept so split settings can be re-applied without
         * reloading the chapter. Falls back to [content]'s pages when nothing was resolved.
         */
        val sourcePages: List<ComicPage> = content.pages,
        /** Whether [sourcePages] holds provider-resolved dimensions rather than placeholders. */
        val sizesResolved: Boolean = false,
    ) {
        val endPageIndex: Int get() = startPageIndex + displayPageCount - 1
    }

    private val chapters = mutableListOf<ChapterSegment>()
    private val loadedChapters = mutableSetOf<ChapterRef>()
    private var loadJob: kotlinx.coroutines.Job? = null
    private var nextChapterLoadJob: kotlinx.coroutines.Job? = null
    private var previousChapterLoadJob: kotlinx.coroutines.Job? = null
    private var splitResolveJob: kotlinx.coroutines.Job? = null

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
            is ReaderAction.SeekPage -> seekPage(action.chapterPageIndex)
            ReaderAction.LoadPreviousChapter -> loadPreviousChapter()
            ReaderAction.AutoLoadPreviousChapter -> loadPreviousChapter(preserveVisiblePage = true)
            ReaderAction.RetryPreviousChapter -> loadPreviousChapter()
            ReaderAction.NavigateNextChapter -> navigateNextChapter()
            is ReaderAction.ChangeDirection -> _state.update { it.copy(direction = action.direction) }
            ReaderAction.LoadNextChapter -> loadNextChapter()
            ReaderAction.RetryNextChapter -> {
                _state.update { it.copy(nextChapterLoadFailed = false) }
                loadNextChapter()
            }
            ReaderAction.ToggleSplit -> toggleSplit()
            is ReaderAction.SetPageSplit -> setPageSplit(action.sourcePageIndex, action.split)
            is ReaderAction.SetSplitOverlap -> setSplitOverlap(action.fraction)
            is ReaderAction.SetSplitOrder -> setSplitOrder(action.rightToLeft)
        }
    }

    private fun load(overrideStartSourceIndex: Int? = null) {
        _state.update { it.copy(status = ReaderStatus.Loading) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                chapters.clear()
                loadedChapters.clear()
                nextChapterLoadJob?.cancel()
                previousChapterLoadJob?.cancel()
                val chapterProgress = progressFactory(chapter)
                val resumePage = overrideStartSourceIndex
                    ?: withTimeoutOrNull(10_000L) { chapterProgress?.resumePage() } ?: startPageIndex
                val content = loadChapterBounded(chapter)
                if (content.pages.isEmpty()) throw IllegalStateException("Chapter has no pages")
                pageJobs.values.forEach { it.cancel() }
                pageJobs.clear()
                prefetched.clear()
                val resumedIndex = resumePage.coerceIn(0, content.pages.lastIndex.coerceAtLeast(0))
                val splitEnabled = splitPreference?.isSplitEnabled(chapter) == true
                val excluded = if (splitEnabled) excludedSourcePages(chapter, content.pages.size) else emptySet()
                val sizedSource = if (splitEnabled) resolveSourceSizes(content.pages) else content.pages
                val overlapFraction = splitPreference?.overlapFraction(chapter.comicRef)
                    ?: PageSplit.DEFAULT_OVERLAP_FRACTION
                val rightToLeft = splitPreference?.isRightToLeft(chapter.comicRef) == true
                val displayPages = splitPages(sizedSource, splitEnabled, excluded, overlapFraction, rightToLeft)
                val displayIndex = displayPages.indexOfFirst { it.sourceIndex == resumedIndex }.coerceAtLeast(0)
                chapters += ChapterSegment(
                    chapter, content, 0, chapterProgress, displayPages.size, splitEnabled, excluded,
                    overlapFraction, rightToLeft, sizedSource, splitEnabled,
                )
                loadedChapters += chapter
                _state.update { current ->
                    current.copy(
                        chapterTitle = content.title,
                        pages = displayPages,
                        currentPageIndex = displayIndex,
                        currentChapterStartIndex = 0,
                        currentChapterPageCount = displayPages.size,
                        previousChapter = content.previousChapter,
                        nextChapterStartIndex = null,
                        nextChapter = content.nextChapter,
                        isLoadingNextChapter = false,
                        nextChapterLoadFailed = false,
                        isLoadingPreviousChapter = false,
                        previousChapterLoadFailed = false,
                        endOfChaptersReached = false,
                        splitEnabled = splitEnabled,
                        excludedPages = excluded,
                        splitOverlapFraction = overlapFraction,
                        splitRightToLeft = rightToLeft,
                        status = ReaderStatus.Ready,
                    )
                }
                chapterProgress?.record(content, resumedIndex)
                prefetchAround(displayIndex)
                content.nextChapter?.let { next ->
                    viewModelScope.launch { provider.prefetchChapter(next.toChapterRef()) }
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
        if (target == _state.value.currentPageIndex) {
            if (_state.value.endOfChaptersReached) {
                _state.update { it.copy(endOfChaptersReached = false) }
            }
            return
        }
        val segment = chapters.lastOrNull { target in it.startPageIndex..it.endPageIndex }
        _state.update { current ->
            current.copy(
                currentPageIndex = target,
                chapterTitle = segment?.content?.title ?: current.chapterTitle,
                currentChapterStartIndex = segment?.startPageIndex ?: current.currentChapterStartIndex,
                currentChapterPageCount = segment?.displayPageCount ?: current.currentChapterPageCount,
                previousChapter = segment?.content?.previousChapter,
                nextChapter = segment?.content?.nextChapter,
                nextChapterStartIndex = segment?.let { currentSegment ->
                    chapters.firstOrNull { it.startPageIndex == currentSegment.endPageIndex + 1 }?.startPageIndex
                },
                endOfChaptersReached = if (target < pages.lastIndex) false else current.endOfChaptersReached,
            )
        }
        segment?.let { loaded ->
            // Crossing into another chapter must replay that chapter's split settings, otherwise the
            // toolbar keeps showing the previous chapter's toggle while the pages are un-split.
            _state.update {
                it.copy(
                    splitEnabled = loaded.splitEnabled,
                    excludedPages = loaded.excludedPages,
                    splitOverlapFraction = loaded.splitOverlapFraction,
                    splitRightToLeft = loaded.splitRightToLeft,
                )
            }
            loaded.progress?.record(loaded.content, pages[target].sourceIndex)
        }
        prefetchAround(target)
    }

    private fun seekPage(chapterPageIndex: Int) {
        val state = _state.value
        if (state.currentChapterPageCount == 0) return
        showPage(state.currentChapterStartIndex + chapterPageIndex.coerceIn(0, state.currentChapterPageCount - 1))
    }

    private fun navigateNextChapter() {
        val current = _state.value
        val loadedStart = current.nextChapterStartIndex
        if (loadedStart != null) {
            showPage(loadedStart)
            return
        }
        if (current.nextChapter == null) {
            _state.update { it.copy(endOfChaptersReached = true) }
        } else {
            loadNextChapter(navigateAfterLoad = true)
        }
    }

    private fun loadNextChapter(navigateAfterLoad: Boolean = false) {
        val next = _state.value.nextChapter ?: run {
            _state.update { it.copy(endOfChaptersReached = true) }
            return
        }
        val nextRef = next.toChapterRef()
        if (nextRef in loadedChapters || nextChapterLoadJob?.isActive == true) return
        _state.update { it.copy(isLoadingNextChapter = true, nextChapterLoadFailed = false) }
        nextChapterLoadJob = viewModelScope.launch {
            try {
                val content = loadChapterBounded(nextRef)
                if (content.pages.isEmpty()) throw IllegalStateException("Chapter has no pages")
                val startIndex = _state.value.pages.size
                val segmentProgress = progressFactory(nextRef)
                val splitEnabled = splitPreference?.isSplitEnabled(nextRef) == true
                val excluded = if (splitEnabled) excludedSourcePages(nextRef, content.pages.size) else emptySet()
                val sizedSource = if (splitEnabled) resolveSourceSizes(content.pages) else content.pages
                val overlapFraction = splitPreference?.overlapFraction(nextRef.comicRef)
                    ?: PageSplit.DEFAULT_OVERLAP_FRACTION
                val rightToLeft = splitPreference?.isRightToLeft(nextRef.comicRef) == true
                val displayPages = splitPages(sizedSource, splitEnabled, excluded, overlapFraction, rightToLeft)
                chapters += ChapterSegment(
                    nextRef, content, startIndex, segmentProgress, displayPages.size, splitEnabled, excluded,
                    overlapFraction, rightToLeft, sizedSource, splitEnabled,
                )
                loadedChapters += nextRef
                val appended = displayPages.mapIndexed { offset, page -> page.copy(index = startIndex + offset) }
                _state.update { current ->
                    val visibleSegment = chapters.lastOrNull {
                        current.currentPageIndex in it.startPageIndex..it.endPageIndex
                    }
                    current.copy(
                        pages = current.pages + appended,
                        currentPageIndex = if (navigateAfterLoad) startIndex else current.currentPageIndex,
                        currentChapterStartIndex = if (navigateAfterLoad) startIndex else current.currentChapterStartIndex,
                        currentChapterPageCount = if (navigateAfterLoad) displayPages.size else current.currentChapterPageCount,
                        nextChapterStartIndex = if (navigateAfterLoad) null else visibleSegment?.let { segment ->
                            chapters.firstOrNull { it.startPageIndex == segment.endPageIndex + 1 }?.startPageIndex
                        },
                        chapterTitle = if (navigateAfterLoad) content.title else current.chapterTitle,
                        previousChapter = if (navigateAfterLoad) content.previousChapter else visibleSegment?.content?.previousChapter,
                        nextChapter = if (navigateAfterLoad) content.nextChapter else visibleSegment?.content?.nextChapter,
                        isLoadingNextChapter = false,
                        nextChapterLoadFailed = false,
                        endOfChaptersReached = false,
                        splitEnabled = if (navigateAfterLoad) splitEnabled else current.splitEnabled,
                        excludedPages = if (navigateAfterLoad) excluded else current.excludedPages,
                        splitOverlapFraction = if (navigateAfterLoad) overlapFraction else current.splitOverlapFraction,
                        splitRightToLeft = if (navigateAfterLoad) rightToLeft else current.splitRightToLeft,
                    )
                }
                if (navigateAfterLoad) segmentProgress?.record(content, 0)
                content.nextChapter?.let { following ->
                    viewModelScope.launch { provider.prefetchChapter(following.toChapterRef()) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(isLoadingNextChapter = false, nextChapterLoadFailed = true) }
            }
        }
    }

    private fun loadPreviousChapter(preserveVisiblePage: Boolean = false) {
        val previous = _state.value.previousChapter ?: return
        val previousRef = previous.toChapterRef()
        if (previousRef in loadedChapters) {
            if (preserveVisiblePage) return
            val segment = chapters.firstOrNull { it.chapter == previousRef } ?: return
            showPage(segment.startPageIndex + (segment.displayPageCount - 1))
            return
        }
        if (previousChapterLoadJob?.isActive == true) return
        _state.update { it.copy(isLoadingPreviousChapter = true, previousChapterLoadFailed = false) }
        previousChapterLoadJob = viewModelScope.launch {
            try {
                val content = loadChapterBounded(previousRef)
                if (content.pages.isEmpty()) throw IllegalStateException("Chapter has no pages")
                val splitEnabled = splitPreference?.isSplitEnabled(previousRef) == true
                val excluded = if (splitEnabled) excludedSourcePages(previousRef, content.pages.size) else emptySet()
                val sizedSource = if (splitEnabled) resolveSourceSizes(content.pages) else content.pages
                val overlapFraction = splitPreference?.overlapFraction(previousRef.comicRef)
                    ?: PageSplit.DEFAULT_OVERLAP_FRACTION
                val rightToLeft = splitPreference?.isRightToLeft(previousRef.comicRef) == true
                val displayPages = splitPages(sizedSource, splitEnabled, excluded, overlapFraction, rightToLeft)
                val count = displayPages.size
                val currentState = _state.value
                val shiftedPages = currentState.pages.map { it.copy(index = it.index + count) }
                val preservedSegmentStart = currentState.currentChapterStartIndex + count
                chapters.replaceAll { segment -> segment.copy(startPageIndex = segment.startPageIndex + count) }
                val segmentProgress = progressFactory(previousRef)
                chapters.add(
                    0,
                    ChapterSegment(
                        previousRef, content, 0, segmentProgress, count, splitEnabled, excluded,
                        overlapFraction, rightToLeft, sizedSource, splitEnabled,
                    ),
                )
                loadedChapters += previousRef
                _state.update { current ->
                    current.copy(
                        pages = displayPages.mapIndexed { index, page -> page.copy(index = index) } + shiftedPages,
                        currentPageIndex = if (preserveVisiblePage) current.currentPageIndex + count else count - 1,
                        currentChapterStartIndex = if (preserveVisiblePage) preservedSegmentStart else 0,
                        currentChapterPageCount = if (preserveVisiblePage) current.currentChapterPageCount else count,
                        chapterTitle = if (preserveVisiblePage) current.chapterTitle else content.title,
                        previousChapter = if (preserveVisiblePage) current.previousChapter else content.previousChapter,
                        nextChapterStartIndex = if (preserveVisiblePage) {
                            chapters.firstOrNull { it.startPageIndex == preservedSegmentStart + current.currentChapterPageCount }
                                ?.startPageIndex
                        } else count,
                        nextChapter = if (preserveVisiblePage) current.nextChapter else content.nextChapter,
                        isLoadingPreviousChapter = false,
                        previousChapterLoadFailed = false,
                        splitEnabled = if (preserveVisiblePage) current.splitEnabled else splitEnabled,
                        excludedPages = if (preserveVisiblePage) current.excludedPages else excluded,
                        splitOverlapFraction = if (preserveVisiblePage) {
                            current.splitOverlapFraction
                        } else {
                            overlapFraction
                        },
                        splitRightToLeft = if (preserveVisiblePage) current.splitRightToLeft else rightToLeft,
                    )
                }
                segmentProgress?.record(content, content.pages.lastIndex)
                pageJobs.values.forEach { it.cancel() }
                pageJobs.clear()
                prefetched.clear()
                prefetchAround(if (preserveVisiblePage) count else count - 1)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(isLoadingPreviousChapter = false, previousChapterLoadFailed = true) }
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
            val localChapter = chapters.lastOrNull { index in it.startPageIndex..it.endPageIndex }?.chapter is ChapterRef.Local
            try {
                if (page.crop != null) {
                    // A split half already has its final size; only warm the source image. Never
                    // re-resolve it, or the returned full size would overwrite the half width.
                    withTimeoutOrNull(pageLoadTimeoutMillis) { provider.prefetch(page) }
                } else if (page.sizeState == PageSizeState.Ready && localChapter) {
                    // A bounded SAF cache may have evicted this page while it was off screen.
                    updatePage(index, page.copy(sizeState = dev.veneranative.core.model.PageSizeState.Pending))
                    updatePage(index, resolvePageBounded(page))
                } else if (page.sizeState == PageSizeState.Ready) {
                    withTimeoutOrNull(pageLoadTimeoutMillis) { provider.prefetch(page) }
                } else {
                    updatePage(index, page.copy(sizeState = PageSizeState.Pending))
                    updatePage(index, resolvePageBounded(page))
                }
                prefetched.add(index)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (page.crop == null && (page.sizeState != PageSizeState.Ready || localChapter)) {
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

    private suspend fun loadChapterBounded(ref: ChapterRef): ChapterContent =
        withTimeoutOrNull(chapterLoadTimeoutMillis) { provider.loadChapter(ref) }
            ?: throw java.io.IOException("Chapter load timed out")

    private suspend fun resolvePageBounded(page: ComicPage): ComicPage {
        repeat(3) { attempt ->
            try {
                return withTimeoutOrNull(pageLoadTimeoutMillis) { provider.resolve(page) }
                    ?: throw java.io.IOException("Page load timed out")
            } catch (failure: java.io.IOException) {
                if (attempt == 2) throw failure
                delay((attempt + 1) * 500L)
            }
        }
        error("Unreachable retry state")
    }

    /**
     * Maps source pages to display pages, splitting double pages into left/right halves when
     * enabled and not manually excluded. Display indices are always the contiguous `0..N-1` of the
     * result; `sourceIndex` stays the original source position for progress and exclusions.
     *
     * [overlapFraction] and [rightToLeft] come from the comic's saved split settings.
     */
    private fun splitPages(
        source: List<ComicPage>,
        enabled: Boolean,
        excluded: Set<Int>,
        overlapFraction: Float,
        rightToLeft: Boolean,
    ): List<ComicPage> {
        val normalized = source.mapIndexed { index, page -> page.copy(index = index, sourceIndex = index) }
        if (!enabled) return normalized
        val result = mutableListOf<ComicPage>()
        for (page in normalized) {
            if (page.sourceIndex in excluded || !PageSplit.isDoublePage(page.widthPx, page.heightPx)) {
                result += page.copy(index = result.size)
            } else {
                for (half in PageSplit.halves(page.widthPx, page.heightPx, overlapFraction, rightToLeft)) {
                    result += page.copy(
                        index = result.size,
                        widthPx = half.widthPx,
                        crop = PageCrop(
                            sourceWidthPx = page.widthPx,
                            sourceHeightPx = page.heightPx,
                            leftPx = half.leftPx,
                            topPx = 0,
                            widthPx = half.widthPx,
                            heightPx = page.heightPx,
                        ),
                    )
                }
            }
        }
        return result
    }

    /** Reads which source pages are manually excluded from splitting for [chapterRef]. */
    private suspend fun excludedSourcePages(chapterRef: ChapterRef, pageCount: Int): Set<Int> {
        val preference = splitPreference ?: return emptySet()
        return buildSet {
            for (index in 0 until pageCount) {
                if (preference.isPageExcluded(chapterRef, index)) add(index)
            }
        }
    }

    /**
     * Resolves the real dimensions of every source page so the aspect-ratio split decision can be
     * made. Concurrent but bounded; a page that fails keeps its placeholder and stays un-split.
     */
    private suspend fun resolveSourceSizes(pages: List<ComicPage>): List<ComicPage> {
        val semaphore = Semaphore(MAX_SPLIT_RESOLVE_CONCURRENCY)
        return coroutineScope {
            pages.map { page ->
                async {
                    semaphore.withPermit {
                        try {
                            withTimeoutOrNull(pageLoadTimeoutMillis) { provider.resolve(page) } ?: page
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            page
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun segmentIndexOfPage(pageIndex: Int): Int =
        chapters.indexOfLast { pageIndex in it.startPageIndex..it.endPageIndex }

    private fun currentAnchorSourceIndex(): Int =
        _state.value.pages.getOrNull(_state.value.currentPageIndex)?.sourceIndex ?: 0

    /**
     * Rebuilds every loaded chapter's display pages from the cached source pages, using each
     * segment's own split settings, then re-anchors the viewport on [anchorSourceIndex].
     *
     * Split settings only change geometry, and the source dimensions are already known, so this must
     * stay a pure in-memory recomputation: routing it through [load] would drop the reader back to
     * [ReaderStatus.Loading] and re-resolve the whole chapter, which can hang on a slow source.
     */
    private fun rebuildSplitPages(segmentIndex: Int, anchorSourceIndex: Int) {
        val pages = mutableListOf<ComicPage>()
        val starts = IntArray(chapters.size)
        val displays = ArrayList<List<ComicPage>>(chapters.size)
        chapters.forEachIndexed { index, segment ->
            val display = splitPages(
                segment.sourcePages,
                segment.splitEnabled,
                segment.excludedPages,
                segment.splitOverlapFraction,
                segment.splitRightToLeft,
            )
            starts[index] = pages.size
            displays += display
            display.forEach { page -> pages += page.copy(index = pages.size) }
        }
        for (index in chapters.indices) {
            chapters[index] = chapters[index].copy(
                startPageIndex = starts[index],
                displayPageCount = displays[index].size,
            )
        }
        val target = segmentIndex.coerceIn(0, chapters.lastIndex)
        val offset = displays[target].indexOfFirst { it.sourceIndex == anchorSourceIndex }.coerceAtLeast(0)
        val pageIndex = starts[target] + offset
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        prefetched.clear()
        _state.update { current ->
            current.copy(
                pages = pages,
                currentPageIndex = pageIndex,
                currentChapterStartIndex = starts[target],
                currentChapterPageCount = displays[target].size,
                nextChapterStartIndex = chapters.getOrNull(target + 1)
                    ?.takeIf { it.startPageIndex == starts[target] + displays[target].size }
                    ?.startPageIndex,
                // The toolbar and the long-press dialog both read these from the UI state, so they
                // must be derived from the visible segment after every rebuild.
                splitEnabled = chapters[target].splitEnabled,
                excludedPages = chapters[target].excludedPages,
                splitOverlapFraction = chapters[target].splitOverlapFraction,
                splitRightToLeft = chapters[target].splitRightToLeft,
                endOfChaptersReached = false,
            )
        }
        prefetchAround(pageIndex)
    }

    private fun toggleSplit() {
        val segmentIndex = segmentIndexOfPage(_state.value.currentPageIndex)
        if (segmentIndex < 0) return
        val segment = chapters[segmentIndex]
        val enabled = !segment.splitEnabled
        val anchor = currentAnchorSourceIndex()
        chapters[segmentIndex] = segment.copy(splitEnabled = enabled)
        _state.update { it.copy(splitEnabled = enabled) }
        if (!enabled) {
            rebuildSplitPages(segmentIndex, anchor)
            viewModelScope.launch { splitPreference?.setSplitEnabled(segment.chapter, false) }
            return
        }
        // Turning the split on needs real page dimensions. Resolve them once in the background while
        // the reader keeps showing the un-split pages, then swap the display list in place. Never enter
        // the full-screen loading state for this.
        splitResolveJob?.cancel()
        splitResolveJob = viewModelScope.launch {
            try {
                val index = chapters.indexOfFirst { it.chapter == segment.chapter }
                if (index < 0) return@launch
                val current = chapters[index]
                val sized = if (current.sizesResolved) {
                    current.sourcePages
                } else {
                    resolveSourceSizes(current.content.pages)
                }
                chapters[index] = chapters[index].copy(sourcePages = sized, sizesResolved = true)
                splitPreference?.setSplitEnabled(segment.chapter, true)
                rebuildSplitPages(index, currentAnchorSourceIndex())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Leave the pages un-split; the toolbar toggle still reflects the saved preference.
            }
        }
    }

    /**
     * Excludes or re-includes one source page. Takes the desired end state instead of flipping the
     * current one, so the caller's view of the exclusion set cannot invert the result.
     */
    private fun setPageSplit(sourcePageIndex: Int, split: Boolean) {
        val segmentIndex = segmentIndexOfPage(_state.value.currentPageIndex)
        if (segmentIndex < 0) return
        val segment = chapters[segmentIndex]
        if (segment.sourcePages.none { it.sourceIndex == sourcePageIndex }) return
        val anchor = currentAnchorSourceIndex()
        val excluded = if (split) {
            segment.excludedPages - sourcePageIndex
        } else {
            segment.excludedPages + sourcePageIndex
        }
        chapters[segmentIndex] = segment.copy(excludedPages = excluded)
        rebuildSplitPages(segmentIndex, anchor)
        viewModelScope.launch { splitPreference?.setPageExcluded(segment.chapter, sourcePageIndex, !split) }
    }

    private fun setSplitOverlap(fraction: Float) {
        val segmentIndex = segmentIndexOfPage(_state.value.currentPageIndex)
        if (segmentIndex < 0) return
        val anchor = currentAnchorSourceIndex()
        // The setting is per comic, so every loaded segment of this comic must pick it up.
        chapters.replaceAll { segment ->
            if (segment.chapter.comicRef == chapter.comicRef) segment.copy(splitOverlapFraction = fraction) else segment
        }
        _state.update { it.copy(splitOverlapFraction = fraction) }
        rebuildSplitPages(segmentIndex, anchor)
        viewModelScope.launch { splitPreference?.setOverlapFraction(chapter.comicRef, fraction) }
    }

    private fun setSplitOrder(rightToLeft: Boolean) {
        val segmentIndex = segmentIndexOfPage(_state.value.currentPageIndex)
        if (segmentIndex < 0) return
        val anchor = currentAnchorSourceIndex()
        chapters.replaceAll { segment ->
            if (segment.chapter.comicRef == chapter.comicRef) segment.copy(splitRightToLeft = rightToLeft) else segment
        }
        _state.update { it.copy(splitRightToLeft = rightToLeft) }
        rebuildSplitPages(segmentIndex, anchor)
        viewModelScope.launch { splitPreference?.setRightToLeft(chapter.comicRef, rightToLeft) }
    }

    private companion object {
        const val DEFAULT_PREFETCH_RADIUS = 3
        const val MAX_SPLIT_RESOLVE_CONCURRENCY = 8
    }
}

private fun Chapter.toChapterRef(): ChapterRef.Remote = ChapterRef.Remote(key, group)

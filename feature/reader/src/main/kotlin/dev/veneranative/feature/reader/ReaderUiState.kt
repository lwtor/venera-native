package dev.veneranative.feature.reader

import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageSplit

/** How pages are laid out and in which order they advance. */
enum class ReadingDirection {
    Vertical,
    LeftToRight,
    RightToLeft,
}

/** Load progress of the current chapter. Failures stay domain-level, never raw exception text. */
sealed interface ReaderStatus {
    data object Loading : ReaderStatus
    data object Ready : ReaderStatus
    data object Failed : ReaderStatus
}

/**
 * Complete renderable state of the reader.
 *
 * It only holds page descriptors, never decoded pixels, so it is safe to keep in Compose state.
 */
data class ReaderUiState(
    val chapterTitle: String = "",
    val pages: List<ComicPage> = emptyList(),
    val currentPageIndex: Int = 0,
    val currentChapterStartIndex: Int = 0,
    val currentChapterPageCount: Int = 0,
    /**
     * Global page ranges of every chapter currently on the canvas, in reading order.
     *
     * [ComicPage.index] addresses the flattened canvas, so it keeps growing as chapters are
     * appended or prepended. Any user-facing page number must instead be relative to the chapter
     * that owns the page; without these ranges a failed page in chapter 2 is reported as page 33
     * while the toolbar says 16 / 22.
     */
    val chapterPageRanges: List<IntRange> = emptyList(),
    val previousChapter: ChapterRef? = null,
    val nextChapterStartIndex: Int? = null,
    val nextChapter: ChapterRef? = null,
    val isLoadingNextChapter: Boolean = false,
    val nextChapterLoadFailed: Boolean = false,
    val isLoadingPreviousChapter: Boolean = false,
    val previousChapterLoadFailed: Boolean = false,
    val endOfChaptersReached: Boolean = false,
    /**
     * Whether the source actually resolved this chapter's neighbours.
     *
     * False means a null [nextChapter] is "unknown", not "finished": the chapter-order request
     * failed while the pages themselves loaded (copy_manga fetches the whole comic plus every
     * chapter group to answer it, which its rate limiter rejects far sooner than a page read).
     */
    val neighborsResolved: Boolean = true,
    /** Last page reached with an unresolved neighbour lookup: offer a retry instead of "last chapter". */
    val nextChapterUnknown: Boolean = false,
    val direction: ReadingDirection = ReadingDirection.Vertical,
    val status: ReaderStatus = ReaderStatus.Loading,
    /**
     * Short reason behind [ReaderStatus.Failed], straight from the failing layer.
     *
     * The generic line tells the reader nothing when the cause is a source-side rate limit or a
     * script error, and reproducing it elsewhere needs the very log that is missing. Keeping the
     * first 160 characters here makes the failure diagnosable on the device that hit it.
     */
    val failureDetail: String? = null,
    val splitEnabled: Boolean = false,
    val excludedPages: Set<Int> = emptySet(),
    /** Gutter overlap per side of a split page, shared by every chapter of the current comic. */
    val splitOverlapFraction: Float = PageSplit.DEFAULT_OVERLAP_FRACTION,
    /** Whether split pages show the right half before the left half for the current comic. */
    val splitRightToLeft: Boolean = false,
) {
    val pageCount: Int get() = pages.size

    /** 1-based page number for display; 0 while nothing is loaded. */
    val currentPageNumber: Int get() = if (pages.isEmpty()) 0 else currentPageIndex - currentChapterStartIndex + 1
    val currentChapterEndIndex: Int get() = currentChapterStartIndex + currentChapterPageCount - 1

    /** 1-based chapter-local display number of a flattened canvas page. */
    fun pageNumber(index: Int): Int {
        val range = chapterPageRanges.firstOrNull { index in it } ?: return index + 1
        return index - range.first + 1
    }
}

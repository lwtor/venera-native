package dev.veneranative.feature.reader

import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.Chapter

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
    val previousChapter: Chapter? = null,
    val nextChapterStartIndex: Int? = null,
    val nextChapter: Chapter? = null,
    val isLoadingNextChapter: Boolean = false,
    val nextChapterLoadFailed: Boolean = false,
    val isLoadingPreviousChapter: Boolean = false,
    val previousChapterLoadFailed: Boolean = false,
    val direction: ReadingDirection = ReadingDirection.Vertical,
    val status: ReaderStatus = ReaderStatus.Loading,
) {
    val pageCount: Int get() = pages.size

    /** 1-based page number for display; 0 while nothing is loaded. */
    val currentPageNumber: Int get() = if (pages.isEmpty()) 0 else currentPageIndex - currentChapterStartIndex + 1
    val currentChapterEndIndex: Int get() = currentChapterStartIndex + currentChapterPageCount - 1
}

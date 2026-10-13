package dev.veneranative.feature.details

internal fun chapterRowIsVisible(offset: Int, rowHeight: Int, viewportHeight: Int, toolbarHeight: Int): Boolean =
    rowHeight > 0 && offset < viewportHeight && offset + rowHeight > toolbarHeight

/** The directory title and filter controls, rather than the page's hero. */
internal fun DetailsUiState.chapterDirectoryListIndex(): Int? {
    val detail = detail ?: return null
    if (!hasChapters) return null
    return 2 + // Hero and comic information precede the directory controls.
        (if (status == DetailsStatus.Failed) 1 else 0) +
        (if (detail.thumbnails.isNotEmpty()) 1 else 0) +
        (if (detail.sourceUrl?.isHttpUrl() == true) 1 else 0)
}

/** Negative scroll offsets place the row below the viewport's start. */
internal fun chapterCenterScrollOffset(viewportHeight: Int, toolbarHeight: Int, rowHeight: Int): Int {
    val top = toolbarHeight.coerceIn(0, viewportHeight.coerceAtLeast(0))
    return -(top + ((viewportHeight - top - rowHeight).coerceAtLeast(0) / 2))
}

/** LazyColumn index, including the hero, metadata, optional content and version headings. */
internal fun DetailsUiState.currentChapterListIndex(): Int? {
    val chapter = currentReadingChapter ?: return null
    val rowIndex = buildChapterListEntries(filteredChapters, groupsTheList).indexOfFirst { entry ->
        entry is ChapterListEntry.Row && chapter in entry.chapters
    }
    if (rowIndex < 0) return null
    return (chapterDirectoryListIndex() ?: return null) + 1 + rowIndex
}

package dev.veneranative.feature.details

/** LazyColumn index, including the hero, metadata, optional content and version headings. */
internal fun DetailsUiState.currentChapterListIndex(): Int? {
    val chapter = currentReadingChapter ?: return null
    val detail = detail ?: return null
    val rowIndex = buildChapterListEntries(filteredChapters, groupsTheList).indexOfFirst { entry ->
        entry is ChapterListEntry.Row && chapter in entry.chapters
    }
    if (rowIndex < 0) return null
    val prefixItems = 3 + // Hero, comic information, chapter controls.
        (if (status == DetailsStatus.Failed) 1 else 0) +
        (if (detail.thumbnails.isNotEmpty()) 1 else 0) +
        (if (detail.sourceUrl?.isHttpUrl() == true) 1 else 0)
    return prefixItems + rowIndex
}

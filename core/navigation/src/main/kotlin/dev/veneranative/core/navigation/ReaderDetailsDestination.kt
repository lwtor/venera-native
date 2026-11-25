package dev.veneranative.core.navigation

import dev.veneranative.core.model.ChapterRef

/**
 * The details screen the reader shows when the user asks for the comic it is reading.
 *
 * A reader covers its entry page, so it remembers where it came from in `readerOrigin`. Opening
 * details from there reuses that answer: if the reader itself came from a details screen, the new
 * one inherits the list *that* screen returned to, so back never lands on a duplicate details page.
 */
fun readerDetailsDestination(
    reader: AppRoute.Reader,
    readerOrigin: AppRoute,
): AppRoute.ComicDetails? {
    val chapter = reader.chapter as? ChapterRef.Remote ?: return null
    return AppRoute.ComicDetails(
        comicKey = chapter.key.comicKey,
        backTo = if (readerOrigin is AppRoute.ComicDetails) readerOrigin.backTo else readerOrigin,
    )
}

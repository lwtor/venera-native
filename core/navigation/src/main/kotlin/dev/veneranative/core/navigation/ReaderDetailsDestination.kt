package dev.veneranative.core.navigation

import dev.veneranative.core.model.ChapterRef

data class ReaderDetailsDestination(val route: AppRoute.ComicDetails, val detailsOrigin: AppRoute)

/** Replaces the reader: reuse matching details, otherwise return new details to the reader's entry. */
fun readerDetailsDestination(
    reader: AppRoute.Reader,
    readerOrigin: AppRoute,
    previousDetailsOrigin: AppRoute,
): ReaderDetailsDestination? {
    val chapter = reader.chapter as? ChapterRef.Remote ?: return null
    val details = AppRoute.ComicDetails(chapter.key.comicKey)
    return if (readerOrigin == details) ReaderDetailsDestination(details, previousDetailsOrigin)
    else ReaderDetailsDestination(details, readerOrigin)
}

package dev.veneranative.core.navigation

import dev.veneranative.core.model.ChapterRef

/** The destination shared by toolbar and system back navigation. Null lets Home exit normally. */
fun backDestination(
    route: AppRoute,
    detailsOrigin: AppRoute,
    readerOrigin: AppRoute? = null,
): AppRoute? = when (route) {
    AppRoute.Home -> null
    AppRoute.Library, AppRoute.Sources, AppRoute.Backup -> AppRoute.Home
    AppRoute.Downloads -> AppRoute.Library
    is AppRoute.Explore, is AppRoute.Search -> AppRoute.Home
    is AppRoute.ComicDetails -> detailsOrigin
    is AppRoute.Reader -> readerOrigin ?: when (val chapter = route.chapter) {
        is ChapterRef.Local -> AppRoute.Library
        is ChapterRef.Remote -> AppRoute.ComicDetails(chapter.key.comicKey)
    }
}

package dev.veneranative.core.navigation

import dev.veneranative.core.model.ChapterRef

/**
 * The destination shared by toolbar and system back navigation. Null lets Home exit normally.
 *
 * Details answer from their own [AppRoute.ComicDetails.backTo] rather than from navigation state
 * held beside the route: the screen is reachable from every list in the app, and a single "current
 * origin" variable was overwritten by whichever entry came next — a comic opened from search could
 * end up returning home. A reader still needs [readerOrigin], because its route has no room for the
 * page it covers.
 */
fun backDestination(
    route: AppRoute,
    readerOrigin: AppRoute? = null,
): AppRoute? = when (route) {
    AppRoute.Home -> null
    AppRoute.Library, AppRoute.Profile, AppRoute.ReadingHistory -> AppRoute.Home
    AppRoute.Sources -> AppRoute.Profile
    AppRoute.SourceComicSearch -> AppRoute.Sources
    AppRoute.Backup, AppRoute.About -> AppRoute.Profile
    AppRoute.Downloads -> AppRoute.Library
    is AppRoute.Explore, is AppRoute.Search -> AppRoute.Home
    is AppRoute.AuthorWorks -> AppRoute.ComicDetails(route.origin, backTo = route.returnOrigin)
    is AppRoute.ComicDetails -> route.backTo ?: AppRoute.Home
    is AppRoute.Reader -> readerOrigin ?: when (val chapter = route.chapter) {
        is ChapterRef.Local -> AppRoute.Library
        is ChapterRef.Remote -> AppRoute.ComicDetails(chapter.key.comicKey)
    }
}

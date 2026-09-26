package dev.veneranative.core.navigation

import dev.veneranative.core.model.ChapterRef

/** The destination shared by toolbar and system back navigation. Null lets Home exit normally. */
fun backDestination(route: AppRoute, detailsOrigin: AppRoute): AppRoute? = when (route) {
    AppRoute.Home -> null
    AppRoute.Library, AppRoute.Sources -> AppRoute.Home
    is AppRoute.Explore, is AppRoute.Search -> AppRoute.Home
    is AppRoute.ComicDetails -> detailsOrigin
    is AppRoute.Reader -> when (val chapter = route.chapter) {
        is ChapterRef.Local -> AppRoute.Library
        is ChapterRef.Remote -> AppRoute.ComicDetails(chapter.key.comicKey)
    }
}

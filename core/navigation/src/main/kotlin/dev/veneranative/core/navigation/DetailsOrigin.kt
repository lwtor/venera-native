package dev.veneranative.core.navigation

/** Keeps the list page that opened details while a reader temporarily sits on top of it. */
fun detailsOriginAfterNavigation(current: AppRoute, next: AppRoute, previousOrigin: AppRoute): AppRoute =
    if (next is AppRoute.ComicDetails && current !is AppRoute.ComicDetails && current !is AppRoute.Reader) {
        current
    } else {
        previousOrigin
    }

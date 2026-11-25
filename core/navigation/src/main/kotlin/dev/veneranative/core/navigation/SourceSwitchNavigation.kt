package dev.veneranative.core.navigation

/**
 * The search page a comic's "switch source" action opens.
 *
 * The switch is a search, so it gets a destination of its own instead of a panel over the comic:
 * the results keep their place while the user opens a candidate, and back walks the chain the user
 * actually built — comic, its results, the comic picked from them, its results, and so on.
 */
fun sourceSwitchDestination(from: AppRoute.ComicDetails, title: String?): AppRoute.SourceSwitch =
    AppRoute.SourceSwitch(
        comicKey = from.comicKey,
        title = title?.takeIf { it.isNotBlank() } ?: from.title,
        backTo = from,
    )

/**
 * Whether this page was opened from the results of a source switch rather than from a list, the
 * reader or a link.
 *
 * Such a page exists to compare sources: the comic is already on the shelf under the source it was
 * migrated from, so the page shows the switch action where the shelf action normally sits.
 */
fun AppRoute.ComicDetails.openedBySourceSwitch(): Boolean = backTo is AppRoute.SourceSwitch

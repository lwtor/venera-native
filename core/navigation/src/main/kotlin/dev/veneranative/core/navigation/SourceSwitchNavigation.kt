package dev.veneranative.core.navigation

import dev.veneranative.core.model.ComicKey

/**
 * The search page a comic's "switch source" action opens.
 *
 * The switch is a search, so it gets a destination of its own instead of a panel over the comic:
 * the results keep their place while the user opens a candidate, and back walks the chain the user
 * actually built — comic, its results, the comic picked from them.
 */
fun sourceSwitchDestination(from: AppRoute.ComicDetails, title: String?): AppRoute.SourceSwitch =
    AppRoute.SourceSwitch(
        comicKey = from.comicKey,
        title = title?.takeIf { it.isNotBlank() } ?: from.title,
        backTo = from,
    )

/** The source a switch started from, or null when this page was not reached through one. */
fun AppRoute.ComicDetails.sourceSwitchOrigin(): ComicKey? =
    (backTo as? AppRoute.SourceSwitch)?.comicKey

/**
 * Whether this page is one of a switch's results being looked at.
 *
 * Such a page is a candidate, not a decision: the shelf entry still points at the source the user
 * came from, so the page offers the commit that settles it and nothing else.
 */
fun AppRoute.ComicDetails.openedBySourceSwitch(): Boolean = sourceSwitchOrigin() != null

/**
 * The page a confirmed candidate becomes: the same comic, off the switch chain.
 *
 * It keeps the page's own back target, so back from a confirmed switch leaves for wherever the
 * original comic would have left for, instead of walking back through candidates the user has
 * already decided about. Returns null when the page is not a candidate.
 */
fun sourceSwitchCommit(candidate: AppRoute.ComicDetails): AppRoute.ComicDetails? {
    val results = candidate.backTo as? AppRoute.SourceSwitch ?: return null
    val origin = results.backTo as? AppRoute.ComicDetails
    return AppRoute.ComicDetails(
        comicKey = candidate.comicKey,
        title = candidate.title,
        backTo = origin?.backTo,
    )
}

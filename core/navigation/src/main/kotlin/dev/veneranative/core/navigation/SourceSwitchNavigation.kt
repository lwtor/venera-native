package dev.veneranative.core.navigation

import dev.veneranative.core.model.ComicKey

/**
 * The page opened when the user picks another source for the comic they are looking at.
 *
 * A switch is a comparison, not an edit: the user wants to see the same work on a different source
 * and may well go back to the page they left. The new page therefore carries the current one as its
 * [AppRoute.ComicDetails.backTo], so back returns there instead of jumping to whatever list the
 * current page itself returned to — the failure the user hit was landing on Home after switching.
 *
 * The earlier version treated the switch as a replacement and inherited the current page's own
 * `backTo`, which erased the page the user was on and made the app look like it had no history.
 */
fun sourceSwitchDestination(
    current: AppRoute.ComicDetails,
    comicKey: ComicKey,
    title: String?,
): AppRoute.ComicDetails = AppRoute.ComicDetails(comicKey, title, backTo = current)

/**
 * Whether this page was opened by switching source rather than from a list, the reader or a link.
 *
 * Such a page exists to compare sources: the comic is already on the shelf under the source it was
 * migrated from, so the page shows the switch action where the shelf action normally sits.
 */
fun AppRoute.ComicDetails.openedBySourceSwitch(): Boolean = backTo is AppRoute.ComicDetails

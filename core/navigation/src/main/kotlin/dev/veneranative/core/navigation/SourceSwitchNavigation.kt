package dev.veneranative.core.navigation

import dev.veneranative.core.model.ComicKey

/**
 * The page opened when the user picks another source for the comic they are looking at.
 *
 * The new page carries the current one as its [AppRoute.ComicDetails.backTo], so back returns to
 * where the switch began instead of to whatever list that page itself returned to. The current page
 * is marked with [AppRoute.ComicDetails.showSourceSwitch] on the way, because "where the switch
 * began" is its candidate list: coming back has to land on the results the user was choosing from,
 * not on the comic page those results are drawn over.
 */
fun sourceSwitchDestination(
    current: AppRoute.ComicDetails,
    comicKey: ComicKey,
    title: String?,
): AppRoute.ComicDetails = AppRoute.ComicDetails(
    comicKey = comicKey,
    title = title,
    backTo = current.copy(showSourceSwitch = true),
)

/**
 * Whether this page was opened by switching source rather than from a list, the reader or a link.
 *
 * Such a page exists to compare sources: the comic is already on the shelf under the source it was
 * migrated from, so the page shows the switch action where the shelf action normally sits.
 */
fun AppRoute.ComicDetails.openedBySourceSwitch(): Boolean = backTo is AppRoute.ComicDetails

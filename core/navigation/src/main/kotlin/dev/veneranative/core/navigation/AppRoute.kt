package dev.veneranative.core.navigation

import dev.veneranative.core.model.TagNavigation
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.SourceId

/**
 * A destination, with its arguments as types.
 *
 * Features emit routes instead of naming screens, so a feature never has to know which other
 * feature will render them (feature-to-feature dependencies are forbidden) and a missing argument
 * is a compile error rather than a blank screen. The assembly layer maps a route to a composable.
 *
 * This is deliberately a plain sealed type rather than a navigation library's key: the app's
 * navigation state is a value the assembly layer owns, and the only thing features need is a way to
 * say "here is where the user wants to go, and here is what that destination needs".
 */
sealed interface AppRoute {

    /** The start destination. */
    data object Home : AppRoute

    /** Installed source management. */
    data object Sources : AppRoute

    /** Search source catalogs for a specific comic without embedding the form in source lists. */
    data object SourceComicSearch : AppRoute

    /**
     * Parameters and maintenance of one installed source.
     *
     * A destination rather than an expanding card: the source list is for scanning and reordering,
     * and a page here can be as tall as the script's settings need without pushing its neighbours
     * off screen. [sourceId] is nullable so a restored link whose source was removed still opens a
     * page that says so, instead of being dropped on a silent blank screen.
     */
    data class SourceSettings(val sourceId: SourceId?) : AppRoute

    /** The shelf: favourite folders, downloads and imported comics. */
    data object Library : AppRoute

    /** Full list of comics with saved reading progress. */
    data object ReadingHistory : AppRoute

    /** A standalone download manager outside the main bottom navigation. */
    data object Downloads : AppRoute

    /** Encrypted GitHub backup and restore. */
    data object Backup : AppRoute

    /** Personal account, sync status and settings entries. */
    data object Profile : AppRoute

    /** Installed app version information. */
    data object About : AppRoute

    /** Browse a source's declared explore pages. */
    data class Explore(val sourceId: String?) : AppRoute

    /** Search inside one source. */
    data class Search(val sourceId: String?) : AppRoute

    data class AuthorWorks(val origin: ComicKey, val author: String, val target: TagNavigation, val returnOrigin: AppRoute = Home) : AppRoute

    /**
     * One comic's details.
     *
     * The key carries the source, so a comic can only be opened in the context it came from: two
     * sources may use the same remote id for different comics.
     *
     * [title] is a hint from the list the user tapped. Details normally replace it with the source's
     * answer, but a failed or removed source leaves no title to search other sources with, so the
     * hint is carried here rather than read from a shelf the comic may never have been on.
     *
     * [backTo] is the page that opened this one, carried on the route instead of in a separate piece
     * of navigation state. A details screen can be reached from six different lists, from the reader
     * and from a source switch page; keeping that answer inside the route means it survives process
     * death and cannot be overwritten by the next entry.
     */
    data class ComicDetails(
        val comicKey: ComicKey,
        val title: String? = null,
        val backTo: AppRoute? = null,
    ) : AppRoute

    /**
     * Searching the other installed sources for a comic the user is already looking at.
     *
     * A full destination rather than a panel inside details: it is a search, so it has to behave
     * like one — keep its results while the user opens a candidate, come back to them, and let the
     * user edit the title it was seeded with. [comicKey] is the source being left, which is never a
     * candidate for itself; [title] is the seed for the search box.
     */
    data class SourceSwitch(
        val comicKey: ComicKey,
        val title: String? = null,
        val backTo: AppRoute? = null,
    ) : AppRoute

    /** The reader, showing one chapter. */
    data class Reader(val chapter: ChapterRef) : AppRoute
}

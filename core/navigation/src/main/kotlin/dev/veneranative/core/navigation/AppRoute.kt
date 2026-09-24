package dev.veneranative.core.navigation

import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicKey

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

    /** The shelf: favourite folders, downloads and imported comics. */
    data object Library : AppRoute

    /** Browse a source's declared explore pages. */
    data class Explore(val sourceId: String?) : AppRoute

    /** Search inside one source. */
    data class Search(val sourceId: String?) : AppRoute

    /**
     * One comic's details.
     *
     * The key carries the source, so a comic can only be opened in the context it came from: two
     * sources may use the same remote id for different comics.
     */
    data class ComicDetails(val comicKey: ComicKey) : AppRoute

    /** The reader, showing one chapter. */
    data class Reader(val chapter: ChapterRef) : AppRoute
}

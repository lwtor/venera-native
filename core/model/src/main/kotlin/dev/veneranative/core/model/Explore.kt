package dev.veneranative.core.model

/**
 * How an explore page returns its comics.
 *
 * The three kinds are not interchangeable: upstream paginates them differently and the page
 * parameter has a different base for each, so the kind travels with the page instead of being
 * guessed by callers.
 */
enum class ExploreKind {
    /** Sections of comics, no pagination. */
    MULTI_PART,

    /** A flat comic list with a reported page count; the page number is 1-based. */
    MULTI_PAGE,

    /** A mix of comic lists and sections; the page index is 0-based and the page count is optional. */
    MIXED,
}

/**
 * One explore tab declared by a source.
 *
 * Upstream identifies explore pages by their title, and requires titles to be unique, so the title
 * doubles as the key.
 */
data class ExplorePage(
    val key: String,
    val title: String,
    val kind: ExploreKind,
) {
    init {
        require(key.isNotBlank()) { "explore page key must not be blank" }
        require(title.isNotBlank()) { "explore page title must not be blank" }
    }

    companion object {
        /** Explore pages are addressed by title upstream, so the default key is the title. */
        fun of(title: String, kind: ExploreKind): ExplorePage =
            ExplorePage(key = title, title = title, kind = kind)
    }
}

/**
 * One entry of an explore page.
 *
 * `mixed` pages may contain both shapes in the same response, which is why this is a sealed type
 * rather than a flat comic list with an optional section title.
 */
sealed interface ExploreItem {
    /** A list of comics without its own heading. */
    data class Comics(val comics: List<Comic>) : ExploreItem

    /** A titled section; [viewMore] is an opaque source value used to open the full list. */
    data class Section(
        val title: String,
        val comics: List<Comic>,
        val viewMore: String? = null,
    ) : ExploreItem
}

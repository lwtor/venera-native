package dev.veneranative.core.model

/**
 * How to ask a source for the next page.
 *
 * The upstream source protocol has two mutually exclusive pagination shapes — page numbers and
 * opaque cursors — so both are modelled instead of being forced into one. Which page numbers are
 * 0-based and which are 1-based is a source-adapter concern, not a domain concern.
 */
sealed interface PageCursor {
    /** Page-numbered sources. The first page of a request is [number] = 1 unless the source says otherwise. */
    data class Page(val number: Int) : PageCursor {
        init {
            require(number >= 0) { "page number must be >= 0" }
        }
    }

    /** Cursor sources: the token is opaque and only meaningful to the source. */
    data class Token(val value: String) : PageCursor {
        init {
            require(value.isNotEmpty()) { "cursor token must not be empty" }
        }
    }
}

/**
 * One page of results.
 *
 * "There is no next page" is always expressed as `next == null`, whichever pagination shape the
 * source uses; [totalPages] is only informational and stays null for cursor sources.
 */
data class PagedResult<T>(
    val items: List<T>,
    val next: PageCursor? = null,
    val totalPages: Int? = null,
) {
    val hasMore: Boolean get() = next != null

    companion object {
        /**
         * Builds a page-numbered result.
         *
         * A page-numbered source reports its page count; the last page must not advertise a next
         * cursor, otherwise callers loop forever on the final page.
         */
        fun <T> page(items: List<T>, pageNumber: Int, totalPages: Int?): PagedResult<T> {
            require(pageNumber >= 0) { "pageNumber must be >= 0" }
            val hasNextPage = totalPages != null && pageNumber < totalPages
            return PagedResult(
                items = items,
                next = if (hasNextPage) PageCursor.Page(pageNumber + 1) else null,
                totalPages = totalPages,
            )
        }

        /**
         * Builds a cursor result: a null token from the source means there is no next page.
         */
        fun <T> cursor(items: List<T>, nextToken: String?): PagedResult<T> = PagedResult(
            items = items,
            next = nextToken?.takeIf(String::isNotEmpty)?.let(PageCursor::Token),
        )
    }
}

package dev.veneranative.source.api.protocol

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreKind
import dev.veneranative.core.model.ExplorePage
import dev.veneranative.core.model.FilterValue
import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.SourceFilter
import dev.veneranative.core.model.encodeFilterSelection
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SearchRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * One protocol-level invocation: which member of the source to call and with which argument array.
 *
 * Function names and argument shapes are the upstream contract, not our invention, so they live
 * here instead of inside the engine binding. Everything is checked against a real source in
 * `venera-configs`; see ADR-0007 §4.
 */
data class SourceProtocolCall(
    val member: String,
    val argumentsJson: String,
)

/**
 * Encodes [dev.veneranative.source.api.SourceCore] requests into upstream source calls.
 *
 * The rules that are easy to get wrong, and therefore pinned by tests:
 *
 * - search options are an **array aligned with the source's `optionList`**, not an object;
 * - a `multi-select` filter is a **JSON string** inside that array, a `dropdown` without a choice is
 *   `null`, and a filter that must not be sent is `null` as well;
 * - explore page numbers are **1-based** for `multiPageComicList` and **0-based** for `mixed`.
 */
object SourceProtocol {

    const val MEMBER_LOAD_INFO = "loadInfo"
    const val MEMBER_LOAD_EP = "loadEp"
    const val MEMBER_SEARCH_LOAD = "search.load"
    const val MEMBER_SEARCH_LOAD_NEXT = "search.loadNext"
    const val MEMBER_EXPLORE_LOAD = "explore.load"

    private const val FIRST_PAGE = 1
    private const val FIRST_MIXED_INDEX = 0

    fun loadInfo(comicKey: ComicKey): SourceProtocolCall =
        call(MEMBER_LOAD_INFO, JsonPrimitive(comicKey.remoteId.value))

    /**
     * `loadEp(comicId, epId)`. The chapter id is required: sources reject an empty one, and
     * [ChapterKey] cannot hold a blank id either.
     */
    fun loadEp(chapterKey: ChapterKey): SourceProtocolCall = call(
        MEMBER_LOAD_EP,
        JsonPrimitive(chapterKey.comicKey.remoteId.value),
        JsonPrimitive(chapterKey.remoteId.value),
    )

    /**
     * Search, either page-numbered or cursor-based.
     *
     * A token cursor maps to `search.loadNext`, which upstream sources only implement when they have
     * no page-numbered `load`; an implementation must therefore prefer the cursor the caller got
     * back rather than inventing one.
     */
    fun search(filters: List<SourceFilter>, request: SearchRequest): SourceProtocolCall {
        val options = JsonArray(
            encodeFilterSelection(filters, request.filters).map(::encodeFilterValue),
        )
        return when (val cursor = request.cursor) {
            is PageCursor.Token -> call(
                MEMBER_SEARCH_LOAD_NEXT,
                JsonPrimitive(request.keyword),
                options,
                JsonPrimitive(cursor.value),
            )

            else -> call(
                MEMBER_SEARCH_LOAD,
                JsonPrimitive(request.keyword),
                options,
                JsonPrimitive(pageNumber(cursor, firstPage = FIRST_PAGE)),
            )
        }
    }

    /**
     * Explore: `load(page)` where the page argument depends on the page kind.
     *
     * `multiPartPage` is a single page and receives null; `mixed` indexes pages from zero. Sending
     * the wrong base silently shifts the whole list by one page, so the kind decides, not the caller.
     */
    fun explore(page: ExplorePage, request: ExploreRequest): SourceProtocolCall {
        val pageArgument: JsonElement = when (page.kind) {
            ExploreKind.MULTI_PART -> JsonNull

            ExploreKind.MULTI_PAGE -> JsonPrimitive(pageNumber(request.cursor, firstPage = FIRST_PAGE))

            ExploreKind.MIXED -> JsonPrimitive(pageNumber(request.cursor, firstPage = FIRST_MIXED_INDEX))
        }
        return call(MEMBER_EXPLORE_LOAD, JsonPrimitive(page.key), pageArgument)
    }

    /** A filter value as it appears inside the positional options array. */
    fun encodeFilterValue(value: FilterValue?): JsonElement = when (value) {
        null -> JsonNull

        is FilterValue.Single -> JsonPrimitive(value.value)

        // Upstream hands multi-select values over as a JSON string, not as an array.
        is FilterValue.Multiple -> JsonPrimitive(
            JsonArray(value.values.map(::JsonPrimitive)).toString(),
        )

        FilterValue.Unselected -> JsonNull
    }

    private fun pageNumber(cursor: PageCursor?, firstPage: Int): Int =
        (cursor as? PageCursor.Page)?.number ?: firstPage

    private fun call(member: String, vararg arguments: JsonElement): SourceProtocolCall =
        SourceProtocolCall(member = member, argumentsJson = JsonArray(arguments.toList()).toString())
}

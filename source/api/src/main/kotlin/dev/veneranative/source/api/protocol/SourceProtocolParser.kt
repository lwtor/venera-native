package dev.veneranative.source.api.protocol

import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.ExploreKind
import dev.veneranative.core.model.ExplorePage
import dev.veneranative.core.model.PagedResult
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourcePage
import dev.veneranative.core.model.chaptersOf
import dev.veneranative.core.model.groupedChaptersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses source responses into domain models.
 *
 * Parsing is lenient on purpose: a source that omits an optional field or returns one malformed
 * entry must not fail the whole page. Required fields are `id` and `title` for a comic, and a comic
 * without them is dropped rather than replaced by a placeholder.
 *
 * Shapes follow a real source (ADR-0007 §4.3): `{comics, maxPage}` for lists, `ComicDetails` with a
 * possibly grouped `chapters` map for details, and `{images}` for a chapter.
 */
object SourceProtocolParser {

    private val json = Json

    fun parseComicList(sourceId: SourceId, payload: String, pageNumber: Int): PagedResult<Comic> {
        val root = payload.objectOrNull() ?: return PagedResult(emptyList())
        val comics = root["comics"].arrayOrNull().orEmpty().mapNotNull { parseComic(sourceId, it) }
        return PagedResult.page(comics, pageNumber, root["maxPage"].intOrNull())
    }

    fun parseComic(sourceId: SourceId, element: JsonElement): Comic? {
        val entry = element.objectOrNull() ?: return null
        val id = entry["id"].stringOrNull() ?: return null
        val title = entry["title"].stringOrNull() ?: return null
        return Comic(
            key = ComicKey(sourceId, RemoteComicId(id)),
            title = title,
            // Upstream uses both spellings for the same field.
            subtitle = entry["subtitle"].stringOrNull() ?: entry["subTitle"].stringOrNull(),
            coverUrl = entry["cover"].stringOrNull(),
            tags = entry["tags"].flattenedTags(),
            maxPage = entry["maxPage"].intOrNull(),
            language = entry["language"].stringOrNull(),
        )
    }

    fun parseComicDetail(comicKey: ComicKey, payload: String): ComicDetail? {
        val root = payload.objectOrNull() ?: return null
        val title = root["title"].stringOrNull() ?: return null
        return ComicDetail(
            // The requested key wins over the response: identity must stay consistent even when a
            // source omits `id` or echoes a different one.
            comic = Comic(
                key = comicKey,
                title = title,
                subtitle = root["subtitle"].stringOrNull() ?: root["subTitle"].stringOrNull(),
                coverUrl = root["cover"].stringOrNull(),
                tags = root["tags"].flattenedTags(),
                maxPage = root["maxPage"].intOrNull(),
                language = root["language"].stringOrNull(),
            ),
            description = root["description"].stringOrNull(),
            chapters = parseChapters(comicKey, root["chapters"]),
            thumbnails = root["thumbnails"].stringList(),
        )
    }

    /**
     * Chapters come in two shapes. The flat one maps chapter id to title; the grouped one maps a
     * group name (for example `"Volume 1 - EN"`) to its own chapter map, and mixes non-chapter keys
     * such as a latest-chapter marker into the same level.
     *
     * A value that is an object is a group, a value that is a scalar is either a chapter (flat shape)
     * or a marker (grouped shape), which is why the presence of any object value decides the shape.
     */
    fun parseChapters(comicKey: ComicKey, element: JsonElement?): List<Chapter> {
        val container = element.objectOrNull() ?: return emptyList()
        val groups = container.mapNotNull { (name, value) ->
            value.objectOrNull()?.let { group -> name to group }
        }
        if (groups.isNotEmpty()) {
            return groupedChaptersOf(
                comicKey = comicKey,
                chapterGroups = groups.associate { (name, group) ->
                    name to group.mapNotNull { (id, title) -> title.stringOrNull()?.let { id to it } }.toMap()
                },
            )
        }
        return chaptersOf(
            comicKey = comicKey,
            chapterTitles = container.mapNotNull { (id, title) -> title.stringOrNull()?.let { id to it } }.toMap(),
        )
    }

    /**
     * `{comics, next}`: the cursor-shaped list response, which is what `loadNext` forms return.
     * A null or empty token means the source has no further pages.
     */
    fun parseCursorComicList(sourceId: SourceId, payload: String): PagedResult<Comic> {
        val root = payload.objectOrNull() ?: return PagedResult(emptyList())
        return PagedResult.cursor(parseComics(sourceId, root["comics"]), root["next"].stringOrNull())
    }

    /**
     * The cursor-shaped explore response.
     *
     * `mixed` pages carry their items under `data`; page-numbered pages under `comics`. A
     * `multiPartPage` is a single page by definition, so it has no cursor to return.
     */
    fun parseExplorePageCursor(
        sourceId: SourceId,
        page: ExplorePage,
        payload: String,
    ): PagedResult<ExploreItem> {
        val root = payload.objectOrNull() ?: return PagedResult(emptyList())
        val items = when (page.kind) {
            ExploreKind.MULTI_PAGE -> listOf(ExploreItem.Comics(parseComics(sourceId, root["comics"])))
            ExploreKind.MIXED -> parseMixedItems(sourceId, root["data"])
            ExploreKind.MULTI_PART -> return PagedResult(items = parseSections(sourceId, payload))
        }
        return PagedResult.cursor(items, root["next"].stringOrNull())
    }

    /** `{images: [...]}`: sources return URLs, so pages carry no dimensions. */
    fun parseImages(payload: String): List<SourcePage> {
        val root = payload.objectOrNull() ?: return emptyList()
        return root["images"].stringList().mapIndexed { index, url -> SourcePage(index = index, imageRef = url) }
    }

    fun parseExplorePage(
        sourceId: SourceId,
        page: ExplorePage,
        payload: String,
        pageNumber: Int,
    ): PagedResult<ExploreItem> = when (page.kind) {
        ExploreKind.MULTI_PAGE -> {
            val root = payload.objectOrNull() ?: return PagedResult(emptyList())
            PagedResult.page(
                items = listOf(ExploreItem.Comics(parseComics(sourceId, root["comics"]))),
                pageNumber = pageNumber,
                totalPages = root["maxPage"].intOrNull(),
            )
        }

        ExploreKind.MULTI_PART -> PagedResult(items = parseSections(sourceId, payload))

        ExploreKind.MIXED -> {
            val root = payload.objectOrNull() ?: return PagedResult(emptyList())
            PagedResult.page(
                items = parseMixedItems(sourceId, root["data"]),
                pageNumber = pageNumber,
                totalPages = root["maxPage"].intOrNull(),
            )
        }
    }

    private fun parseMixedItems(sourceId: SourceId, element: JsonElement?): List<ExploreItem> =
        element.arrayOrNull().orEmpty().mapNotNull { item ->
            item.arrayOrNull()?.let { ExploreItem.Comics(parseComics(sourceId, it)) }
                ?: item.objectOrNull()?.let { section -> sectionOrNull(sourceId, section) }
        }

    private fun parseSections(sourceId: SourceId, payload: String): List<ExploreItem> =
        json.parseToJsonElement(payload).arrayOrNull().orEmpty()
            .mapNotNull { element -> element.objectOrNull()?.let { sectionOrNull(sourceId, it) } }

    private fun sectionOrNull(sourceId: SourceId, section: JsonObject): ExploreItem.Section? {
        val title = section["title"].stringOrNull() ?: return null
        return ExploreItem.Section(
            title = title,
            comics = parseComics(sourceId, section["comics"]),
            // `viewMore` is a jump instruction object; Stage 1 keeps it opaque instead of guessing.
            viewMore = section["viewMore"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.toString(),
        )
    }

    private fun parseComics(sourceId: SourceId, element: JsonElement?): List<Comic> =
        element.arrayOrNull().orEmpty().mapNotNull { parseComic(sourceId, it) }

    /** A response payload that is not valid JSON is treated as an empty result, never as a crash. */
    private fun String.objectOrNull(): JsonObject? =
        runCatching { json.parseToJsonElement(this) }.getOrNull() as? JsonObject

    private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonElement?.stringList(): List<String> =
        arrayOrNull().orEmpty().mapNotNull { it.stringOrNull() }

    /** List results carry a flat tag array; detail results carry a map of tag groups. */
    private fun JsonElement?.flattenedTags(): List<String> = when (this) {
        is JsonArray -> mapNotNull { it.stringOrNull() }
        is JsonObject -> values.flatMap { it.stringList() }
        else -> emptyList()
    }
}

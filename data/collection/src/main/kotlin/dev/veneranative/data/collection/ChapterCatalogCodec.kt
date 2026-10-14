package dev.veneranative.data.collection

import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.ComicChapterEntry
import kotlinx.serialization.json.*

internal fun encodeChapterCatalog(chapters: List<Chapter>): String = buildJsonArray {
    chapters.forEach { chapter -> add(buildJsonObject {
        put("id", chapter.key.remoteId.value)
        put("title", chapter.title)
        put("version", chapter.group?.let(::JsonPrimitive) ?: JsonNull)
    }) }
}.toString()

/** Old/malformed optional catalogs are unknown, not a fabricated merged count. */
internal fun decodeChapterCatalog(value: String?): List<ComicChapterEntry> {
    if (value == null) return emptyList()
    return try {
        Json.parseToJsonElement(value).jsonArray.map { item ->
            val row = item.jsonObject
            ComicChapterEntry(row.getValue("id").jsonPrimitive.content, row.getValue("title").jsonPrimitive.content,
                row["version"]?.jsonPrimitive?.contentOrNull)
        }
    } catch (_: IllegalArgumentException) { emptyList() }
    catch (_: NoSuchElementException) { emptyList() }
}

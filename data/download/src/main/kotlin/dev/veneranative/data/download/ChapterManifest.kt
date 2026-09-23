package dev.veneranative.data.download

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One page as recorded in `chapter.json`. */
data class ManifestPage(
    val index: Int,
    val imageRef: String,
    val fileName: String,
)

/**
 * What a chapter's directory claims it contains.
 *
 * Written when a chapter is enqueued and read when the database cannot be trusted — the rows can be
 * lost to a restore or a downgrade, while the files are what the user actually has. That makes this
 * file the authority recovery rebuilds from, which is why it is written atomically like a page.
 */
data class ChapterManifest(
    val sourceId: String,
    val comicId: String,
    val chapterId: String,
    val title: String,
    val comicTitle: String? = null,
    val pages: List<ManifestPage>,
)

object ChapterManifestCodec {

    private const val VERSION: Int = 1

    fun encode(manifest: ChapterManifest): String = buildJsonObject {
        put("version", JsonPrimitive(VERSION))
        put("source", JsonPrimitive(manifest.sourceId))
        put("comic", JsonPrimitive(manifest.comicId))
        put("chapter", JsonPrimitive(manifest.chapterId))
        put("title", JsonPrimitive(manifest.title))
        manifest.comicTitle?.let { put("comicTitle", JsonPrimitive(it)) }
        put("pages", pagesArray(manifest.pages))
    }.toString()

    /** Null when the file is unreadable: a corrupt manifest is skipped, never guessed at. */
    fun decode(text: String): ChapterManifest? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        val pages = root["pages"]?.jsonArray?.map { entry ->
            val page = entry.jsonObject
            ManifestPage(
                index = page.getValue("index").jsonPrimitive.content.toInt(),
                imageRef = page.getValue("imageRef").jsonPrimitive.content,
                fileName = page.getValue("file").jsonPrimitive.content,
            )
        }.orEmpty()
        ChapterManifest(
            sourceId = root.getValue("source").jsonPrimitive.content,
            comicId = root.getValue("comic").jsonPrimitive.content,
            chapterId = root.getValue("chapter").jsonPrimitive.content,
            title = root.getValue("title").jsonPrimitive.content,
            comicTitle = root["comicTitle"]?.jsonPrimitive?.contentOrNull,
            pages = pages,
        )
    }.getOrNull()

    private fun pagesArray(pages: List<ManifestPage>): JsonArray = buildJsonArray {
        pages.forEach { page ->
            add(
                buildJsonObject {
                    put("index", JsonPrimitive(page.index))
                    put("imageRef", JsonPrimitive(page.imageRef))
                    put("file", JsonPrimitive(page.fileName))
                },
            )
        }
    }
}

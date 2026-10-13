package dev.veneranative.source.core

import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.PagedResult
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.TagNavigation
import dev.veneranative.source.api.protocol.SourceProtocolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Komiic 1.0.3 lacks onClickTag; its public frontend resolves authors before fetching their works. */
internal object KomiicAuthorCompatibility {
    const val SOURCE_KEY = "Komiic"

    fun resolveCall(author: String) = query(
        "searchComicAndAuthorQuery", "keyword", author,
        "query searchComicAndAuthorQuery(\$keyword: String!) { searchComicsAndAuthors(keyword: \$keyword) { authors { id name chName enName } } }",
    )

    fun worksCall(authorId: String) = query(
        "comicsByAuthor", "authorId", authorId,
        "query comicsByAuthor(\$authorId: ID!) { getComicsByAuthor(authorId: \$authorId) { id title imageUrl authors { name } } }",
    )

    fun resolve(payload: String, author: String): TagNavigation.Search? {
        val result = data(payload)["searchComicsAndAuthors"] as? JsonObject ?: error("Invalid author lookup")
        val authors = result["authors"] as? JsonArray ?: error("Invalid author list")
        val ids = authors.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val matches = listOf("name", "chName", "enName").any { item[it].text()?.trim() == author.trim() }
            item["id"].text()?.takeIf { matches && it.isNotBlank() }
        }.distinct()
        // Do not silently choose a partial match or an ambiguous author with the same name.
        return ids.singleOrNull()?.let { TagNavigation.Search(author, authorId = it) }
    }

    fun works(sourceId: SourceId, payload: String): PagedResult<Comic> {
        val entries = data(payload)["getComicsByAuthor"] as? JsonArray ?: error("Invalid author works")
        val comics = entries.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val id = item["id"].text()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = item["title"].text()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val authors = item["authors"] as? JsonArray
            Comic(ComicKey(sourceId, RemoteComicId(id)), title, subtitle = (authors?.firstOrNull() as? JsonObject)?.get("name").text(), coverUrl = item["imageUrl"].text())
        }.distinctBy { it.key }
        // The official author endpoint returns the whole list and has no pagination arguments.
        return PagedResult(comics)
    }

    private fun query(operation: String, variable: String, value: String, document: String): SourceProtocolCall {
        val body = JsonObject(mapOf("operationName" to JsonPrimitive(operation), "variables" to JsonObject(mapOf(variable to JsonPrimitive(value))), "query" to JsonPrimitive(document)))
        // Use the installed source's transport, preserving its session, cookies and configured URL.
        return SourceProtocolCall("queryJson", JsonArray(listOf(body)).toString())
    }

    private fun data(payload: String): JsonObject {
        val root = Json.parseToJsonElement(payload) as? JsonObject ?: error("Invalid GraphQL response")
        require((root["errors"] as? JsonArray).isNullOrEmpty())
        return root["data"] as? JsonObject ?: error("Missing GraphQL data")
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull
}

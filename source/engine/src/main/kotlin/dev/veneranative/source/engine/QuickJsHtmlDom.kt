package dev.veneranative.source.engine

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.text.Charsets.UTF_8

/**
 * Synchronous, value-only HTML parsing bridge for the upstream source API.
 *
 * QuickJS source functions call HtmlDocument synchronously, so this capability is intentionally a
 * local parser binding rather than an asynchronous SourceHostApi method. Only bounded strings and
 * JSON values cross the JS boundary; Jsoup objects never do.
 */
internal class QuickJsHtmlDom(
    private val maxHtmlBytes: Int = DEFAULT_MAX_HTML_BYTES,
    private val maxNodesPerInvocation: Int = DEFAULT_MAX_NODES_PER_INVOCATION,
) {
    private val invocations = LinkedHashMap<String, InvocationDom>()

    @Synchronized
    fun invoke(requestJson: String): String {
        require(requestJson.length <= MAX_REQUEST_CHARS) { "HTML request is too large." }
        val request = JSONObject(requestJson)
        val invocationId = request.optString("invocationId")
        require(invocationId.isNotBlank() && invocationId.length <= MAX_INVOCATION_ID_CHARS) {
            "HTML request has no active invocation."
        }
        val action = request.optString("action")
        val invocation = invocations.getOrPut(invocationId) { InvocationDom() }
        return try {
            when (action) {
                "create" -> create(invocation, request)
                "query", "queryAll" -> query(invocation, request, action == "queryAll")
                "getElementById" -> getElementById(invocation, request)
                "text" -> JSONObject().put("value", element(invocation, request).text())
                "attributes" -> attributes(element(invocation, request))
                "innerHtml" -> JSONObject().put("value", element(invocation, request).html())
                "classNames" -> JSONObject().put("value", JSONArray().apply {
                    element(invocation, request).classNames().forEach { put(it) }
                })
                "id" -> nullableString(element(invocation, request).id().ifBlank { null })
                "localName" -> JSONObject().put("value", element(invocation, request).normalName())
                "children" -> handles(invocation, element(invocation, request).children())
                "parent", "previousSibling", "nextSibling" -> sibling(invocation, request, action)
                "dispose" -> {
                    invocations.remove(invocationId)
                    JSONObject()
                }
                else -> throw IllegalArgumentException("Unsupported HTML operation.")
            }.toString()
        } catch (failure: JSONException) {
            invocations.remove(invocationId)
            throw IllegalArgumentException("Invalid HTML request.", failure)
        }
    }

    private fun create(invocation: InvocationDom, request: JSONObject): JSONObject {
        val html = request.optString("html")
        val htmlBytes = html.toByteArray(UTF_8).size
        require(invocation.totalHtmlBytes + htmlBytes <= maxHtmlBytes) {
            "HTML content exceeds the per-call limit."
        }
        require(invocation.documents.size < MAX_DOCUMENTS_PER_INVOCATION) {
            "Too many HTML documents in one source call."
        }
        val document = Jsoup.parse(html)
        val parsedNodes = document.allElements.size
        require(invocation.totalNodes + parsedNodes <= maxNodesPerInvocation) {
            "HTML documents have too many elements for one source call."
        }
        invocation.totalHtmlBytes += htmlBytes
        invocation.totalNodes += parsedNodes
        val documentId = "d${++invocation.nextId}"
        invocation.documents[documentId] = document
        return JSONObject().put("documentId", documentId)
    }

    private fun query(invocation: InvocationDom, request: JSONObject, all: Boolean): JSONObject {
        val root = root(invocation, request)
        val selector = request.optString("selector")
        require(selector.isNotBlank() && selector.length <= MAX_SELECTOR_CHARS) {
            "CSS selector is empty or too long."
        }
        val matches = root.select(selector)
        return if (all) handles(invocation, matches) else {
            JSONObject().put("elementId", matches.firstOrNull()?.let { handle(invocation, it) })
        }
    }

    private fun getElementById(invocation: InvocationDom, request: JSONObject): JSONObject {
        val id = request.optString("id")
        val found = root(invocation, request).getAllElements().firstOrNull { it.id() == id }
        return JSONObject().put("elementId", found?.let { handle(invocation, it) })
    }

    private fun sibling(invocation: InvocationDom, request: JSONObject, action: String): JSONObject {
        val current = element(invocation, request)
        val sibling = when (action) {
            "parent" -> current.parent()
            "previousSibling" -> current.previousElementSibling()
            else -> current.nextElementSibling()
        }
        return JSONObject().put("elementId", sibling?.let { handle(invocation, it) })
    }

    private fun attributes(element: Element): JSONObject = JSONObject().apply {
        element.attributes().forEach { put(it.key.lowercase(), it.value) }
    }

    private fun root(invocation: InvocationDom, request: JSONObject): Element {
        val documentId = request.optString("documentId")
        val elementId = request.optString("elementId")
        return if (elementId.isNotBlank()) {
            invocation.elements[elementId] ?: throw IllegalArgumentException("HTML element is no longer available.")
        } else {
            invocation.documents[documentId] ?: throw IllegalArgumentException("HTML document is no longer available.")
        }
    }

    private fun element(invocation: InvocationDom, request: JSONObject): Element =
        invocation.elements[request.optString("elementId")]
            ?: throw IllegalArgumentException("HTML element is no longer available.")

    private fun handles(invocation: InvocationDom, elements: Iterable<Element>): JSONObject =
        JSONObject().put("elementIds", JSONArray().apply { elements.forEach { put(handle(invocation, it)) } })

    private fun handle(invocation: InvocationDom, element: Element): String {
        require(invocation.elements.size < maxNodesPerInvocation) { "HTML document has too many elements." }
        val id = "e${++invocation.nextId}"
        invocation.elements[id] = element
        return id
    }

    private fun nullableString(value: String?): JSONObject = JSONObject().put("value", value)

    private class InvocationDom {
        var nextId = 0
        var totalHtmlBytes = 0
        var totalNodes = 0
        val documents = LinkedHashMap<String, Element>()
        val elements = LinkedHashMap<String, Element>()
    }

    companion object {
        const val DEFAULT_MAX_HTML_BYTES = 1_048_576
        const val DEFAULT_MAX_NODES_PER_INVOCATION = 25_000
        private const val MAX_REQUEST_CHARS = 1_100_000
        private const val MAX_DOCUMENTS_PER_INVOCATION = 256
        private const val MAX_SELECTOR_CHARS = 2_048
        private const val MAX_INVOCATION_ID_CHARS = 128
    }
}

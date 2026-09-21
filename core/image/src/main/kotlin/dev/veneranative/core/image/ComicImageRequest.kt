package dev.veneranative.core.image

import dev.veneranative.core.model.SourceId

/** How the image bytes are requested. Sources serve some images only through a form POST. */
enum class ComicImageMethod { GET, POST }

/** Payload of a POST request. GET requests carry none. */
sealed interface ComicImageBody {

    /** `application/x-www-form-urlencoded` fields. */
    data class Form(val fields: Map<String, String>) : ComicImageBody

    /** An already encoded payload, for sources that expect JSON or a raw upload. */
    data class Bytes(val contentType: String?, val content: ByteArray) : ComicImageBody
}

/**
 * One comic image request.
 *
 * Everything that can change the response is part of the value: method, body, headers, referer and
 * the source partition. Cookies are **not** stored here: they are resolved through
 * [ComicImageAuthProvider] once per pipeline operation, so its disk key and HTTP request agree and
 * one identity can never be served another identity's cached image.
 */
data class ComicImageRequest(
    val url: String,
    val sourceId: SourceId? = null,
    val method: ComicImageMethod = ComicImageMethod.GET,
    val headers: Map<String, String> = emptyMap(),
    val body: ComicImageBody? = null,
    val referer: String? = null,
    /** Distinguishes otherwise identical requests, e.g. a cover and a page from the same URL. */
    val variant: String? = null,
) {
    init {
        require(url.isNotBlank()) { "url must not be blank" }
        require(method == ComicImageMethod.POST || body == null) { "only POST may carry a body" }
    }
}

/**
 * Supplies the authentication-bearing headers for a request.
 *
 * The same instance is used by [ComicImageKeyer] and by the pipeline that fetches the bytes, which
 * is what makes "different auth never reuses the same cache entry" true: the key is derived from
 * exactly the headers the fetch will send.
 */
fun interface ComicImageAuthProvider {

    /** Headers to attach: Cookie, Authorization, Referer, source-specific tokens. */
    fun headersFor(sourceId: SourceId?, url: String): Map<String, String>
}

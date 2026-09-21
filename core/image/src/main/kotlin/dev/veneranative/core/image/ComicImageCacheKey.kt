package dev.veneranative.core.image

import java.security.MessageDigest

/**
 * Stable cache key for [ComicImageRequest].
 *
 * Rules, in order of importance:
 *
 * 1. Two requests that can produce different bytes must never share a key: url, method, body digest
 *    and every *response-affecting* header are canonicalised into the key.
 * 2. Volatile, non-response-affecting headers (User-Agent, Accept-Language, telemetry) must not
 *    fragment the cache, so they are excluded by [RESPONSE_AFFECTING_HEADERS].
 * 3. Cookies and tokens resolved at fetch time are folded in through [authHeaders], so switching
 *    account or session can never serve another identity's cached image.
 * 4. Requests are partitioned by source id and [ComicImageRequest.variant], because two sources
 *    can publish the same URL with different bytes.
 *
 * Known limitation: only the request side is covered, the response's `Vary` header is not parsed.
 * That is recorded in `docs/adr/0004-large-image-strategy.md` section 7.3.
 */
internal object ComicImageCacheKey {

    /** Lower-cased header names that can change the bytes a server returns. */
    val RESPONSE_AFFECTING_HEADERS: Set<String> = setOf(
        "accept",
        "authorization",
        "cookie",
        "range",
        "referer",
        "x-auth-token",
        "x-requested-with",
    )

    fun of(request: ComicImageRequest, authHeaders: Map<String, String>): String {
        val canonical = buildString {
            append(KEY_VERSION).append('|')
            append(request.method.name).append('|')
            append(request.url).append('|')
            append(request.sourceId?.value.orEmpty()).append('|')
            append(request.variant.orEmpty()).append('|')
            append("hdr:")
            canonicalHeaders(request.headers + authHeaders + refererHeader(request))
                .forEach { (name, value) -> append(name).append('=').append(value).append(';') }
            append('|').append("body:")
            append(request.body?.let { sha256Hex(canonicalBytes(it)) } ?: NO_BODY)
        }
        return "comic_" + sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    /** Lower-cases, filters and sorts, so header order and casing cannot split the cache. */
    private fun canonicalHeaders(headers: Map<String, String>): List<Pair<String, String>> =
        headers.mapKeys { (name, _) -> name.lowercase() }
            .filterKeys { name -> name in RESPONSE_AFFECTING_HEADERS }
            .toSortedMap()
            .map { (name, value) -> name to value }

    private fun refererHeader(request: ComicImageRequest): Map<String, String> =
        request.referer?.let { referer -> mapOf("referer" to referer) } ?: emptyMap()

    /** A deterministic byte representation of a body, so equal bodies hash equally. */
    private fun canonicalBytes(body: ComicImageBody): ByteArray = when (body) {
        is ComicImageBody.Bytes ->
            ("bytes|${body.contentType.orEmpty()}|").toByteArray(Charsets.UTF_8) + body.content

        is ComicImageBody.Form -> body.fields.entries
            .sortedBy { (name, _) -> name }
            .joinToString(separator = "&", prefix = "form|") { (name, value) -> "$name=$value" }
            .toByteArray(Charsets.UTF_8)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private const val KEY_VERSION = "v1"
    private const val NO_BODY = "-"
}

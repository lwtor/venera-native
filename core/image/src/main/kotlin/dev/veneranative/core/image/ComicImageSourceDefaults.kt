package dev.veneranative.core.image

import dev.veneranative.core.model.SourceId

/** Source-specific browser headers required by some image hosts for cover requests. */
object ComicImageSourceDefaults {
    fun headersFor(sourceId: SourceId?): Map<String, String> = when (sourceId?.value) {
        IKM_SOURCE_ID -> IKM_THUMBNAIL_HEADERS
        else -> emptyMap()
    }

    private const val IKM_SOURCE_ID = "ikmmh"
    private val IKM_THUMBNAIL_HEADERS = mapOf(
        "User-Agent" to IKM_MOBILE_USER_AGENT,
        "Accept" to IKM_ACCEPT,
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "Referer" to "https://www.ikmmh.com",
    )

    private const val IKM_MOBILE_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
    private const val IKM_ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
}

package dev.veneranative.source.network

import dev.veneranative.core.model.SourceId
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class PerSourceCookieJarRegistry {
    private val jars = ConcurrentHashMap<SourceId, CookieJar>()

    fun forSource(sourceId: SourceId): CookieJar =
        jars.computeIfAbsent(sourceId) { InMemoryCookieJar() }

    fun clear(sourceId: SourceId) {
        jars.remove(sourceId)
    }
}

internal class InMemoryCookieJar : CookieJar {
    private val cookies = LinkedHashMap<CookieKey, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        removeExpired(now)
        cookies.forEach { cookie ->
            val key = CookieKey(cookie.name, cookie.domain, cookie.path)
            if (cookie.expiresAt <= now) {
                this.cookies.remove(key)
            } else {
                this.cookies[key] = cookie
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        removeExpired(System.currentTimeMillis())
        return cookies.values.filter { cookie -> cookie.matches(url) }
    }

    private fun removeExpired(now: Long) {
        cookies.entries.removeAll { (_, cookie) -> cookie.expiresAt <= now }
    }

    private data class CookieKey(
        val name: String,
        val domain: String,
        val path: String,
    )
}

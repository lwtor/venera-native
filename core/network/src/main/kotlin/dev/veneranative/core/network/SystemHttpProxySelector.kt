package dev.veneranative.core.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

data class SystemHttpProxy(
    val host: String,
    val port: Int,
    val exclusions: List<String> = emptyList(),
)

/** Resolves the current OS proxy for each request, with the platform selector as a fallback. */
class SystemHttpProxySelector(
    private val currentProxy: () -> SystemHttpProxy?,
    private val fallback: ProxySelector? = ProxySelector.getDefault(),
) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        val host = uri.host ?: return fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
        val proxy = runCatching(currentProxy).getOrNull()
            ?: return fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
        if (proxy.host.isBlank() || proxy.port !in 1..65535 || proxy.exclusions.any { host.matchesProxyExclusion(it) }) {
            return listOf(Proxy.NO_PROXY)
        }
        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
        fallback?.connectFailed(uri, sa, ioe)
    }

    private fun String.matchesProxyExclusion(rule: String): Boolean {
        val host = lowercase().trimEnd('.')
        val normalizedRule = rule.trim().lowercase().trimEnd('.')
        if (normalizedRule.isEmpty()) return false
        val wildcardSuffix = normalizedRule.removePrefix("*").removePrefix(".")
        return if (normalizedRule.startsWith('*')) {
            host == wildcardSuffix || host.endsWith(".$wildcardSuffix")
        } else {
            host == normalizedRule
        }
    }
}

package dev.veneranative.app

import android.content.Context
import android.net.ConnectivityManager
import dev.veneranative.core.network.SystemHttpProxy
import dev.veneranative.core.network.SystemHttpProxySelector
import java.net.ProxySelector

/** Reads the default network proxy dynamically, including proxies published by VPN networks. */
internal fun androidDefaultProxySelector(context: Context): ProxySelector {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    return SystemHttpProxySelector(
        currentProxy = {
            val proxyInfo = connectivityManager.defaultProxy ?: return@SystemHttpProxySelector null
            // Android's platform selector evaluates PAC files. Let it handle PAC rather than
            // treating the PAC server itself as a forward proxy.
            if (!proxyInfo.pacFileUrl.toString().isNullOrBlank()) return@SystemHttpProxySelector null
            SystemHttpProxy(
                host = proxyInfo.host.orEmpty(),
                port = proxyInfo.port,
                exclusions = proxyInfo.exclusionList?.toList().orEmpty(),
            )
        },
    )
}

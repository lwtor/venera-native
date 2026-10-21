package dev.veneranative.source.network

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps

/** Uses authenticated public DNS answers as a fallback for the 爱看漫 host only. */
internal class IkmhDnsFallback(
    private val systemDns: Dns = Dns.SYSTEM,
    private val fallbackDns: Dns,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.lowercase() !in TARGET_HOSTS) return systemDns.lookup(hostname)

        val systemAddresses = runCatching { systemDns.lookup(hostname) }.getOrDefault(emptyList())
        val fallbackAddresses = runCatching { fallbackDns.lookup(hostname) }.getOrDefault(emptyList())
        val addresses = (fallbackAddresses + systemAddresses).distinct()
        if (addresses.isEmpty()) throw UnknownHostException(hostname)
        return addresses
    }

    companion object {
        private val TARGET_HOSTS = setOf("www.ikmmh.com", "ikmmh.com")

        fun create(client: OkHttpClient): IkmhDnsFallback {
            val secureDnsClient = DnsOverHttps.Builder()
                .client(client)
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
                    InetAddress.getByAddress(byteArrayOf(8, 8, 4, 4)),
                )
                .includeServiceMetadata(false)
                .build()
            return IkmhDnsFallback(fallbackDns = secureDnsClient)
        }
    }
}

package dev.veneranative.source.network

import dev.veneranative.core.model.SourceId
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol

/** Emits stage-level transport diagnostics without recording URLs, headers, or payloads. */
internal class SourceNetworkTraceListener(
    private val sourceId: SourceId,
    private val method: String,
    private val host: String,
    private val emit: (String) -> Unit,
) : EventListener() {
    private val startedAtNanos = System.nanoTime()

    @Volatile
    private var lastStage: String = "callStart"

    override fun callStart(call: Call) = trace("callStart")

    override fun dnsStart(call: Call, domainName: String) = trace("dnsStart", "domain=$domainName")

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) =
        trace("dnsEnd", "domain=$domainName addresses=${inetAddressList.joinToString(",") { it.hostAddress.orEmpty() }}")

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        trace(
            "connectStart",
            "address=${inetSocketAddress.hostString}:${inetSocketAddress.port} proxy=${proxy.type()}${proxy.address()?.let { "@$it" }.orEmpty()}",
        )

    override fun secureConnectStart(call: Call) = trace("tlsStart")

    override fun secureConnectEnd(call: Call, handshake: Handshake?) =
        trace("tlsEnd", "version=${handshake?.tlsVersion} cipher=${handshake?.cipherSuite}")

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) = trace("connectEnd", "protocol=$protocol")

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) = trace("connectFailed", "protocol=$protocol error=${ioe.safeMessage()}")

    override fun connectionAcquired(call: Call, connection: Connection) =
        trace(
            "connectionAcquired",
            "protocol=${connection.protocol()} route=${connection.route().socketAddress}",
        )

    override fun requestHeadersStart(call: Call) = trace("requestHeadersStart")

    override fun requestHeadersEnd(call: Call, request: okhttp3.Request) = trace("requestHeadersEnd")

    override fun requestBodyStart(call: Call) = trace("requestBodyStart")

    override fun requestBodyEnd(call: Call, byteCount: Long) = trace("requestBodyEnd", "bytes=$byteCount")

    override fun responseHeadersStart(call: Call) = trace("responseHeadersStart")

    override fun responseHeadersEnd(call: Call, response: okhttp3.Response) =
        trace("responseHeadersEnd", "status=${response.code} protocol=${response.protocol}")

    override fun responseBodyStart(call: Call) = trace("responseBodyStart")

    override fun responseBodyEnd(call: Call, byteCount: Long) = trace("responseBodyEnd", "bytes=$byteCount")

    override fun callEnd(call: Call) = trace("callEnd")

    override fun callFailed(call: Call, ioe: IOException) =
        trace("callFailed", "lastStage=$lastStage error=${ioe.safeMessage()}")

    private fun trace(stage: String, details: String = "") {
        lastStage = stage
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        val suffix = if (details.isBlank()) "" else " $details"
        runCatching { emit("source=${sourceId.value} method=$method host=$host +${elapsedMillis}ms stage=$stage$suffix") }
    }

    private fun IOException.safeMessage(): String =
        message.orEmpty()
            .replace(Regex("https?://\\S+"), "<url>")
            .replace(Regex("([?&][^=\\s]+)=([^&\\s]+)"), "$1=<redacted>")
            .filterNot(Char::isISOControl)
            .take(120)
            .ifBlank { javaClass.simpleName }
}

package dev.veneranative.source.network

object SensitiveDataRedactor {
    private val sensitiveHeaderNames =
        setOf("authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key")

    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            if (isSensitiveHeader(name)) REDACTED else value
        }

    fun isSensitiveHeader(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized in sensitiveHeaderNames ||
            normalized.contains("token") ||
            normalized.contains("secret") ||
            normalized.contains("password")
    }

    const val REDACTED = "<redacted>"
}

package dev.veneranative.source.engine

import dev.veneranative.source.api.SourcePackage
import java.security.MessageDigest

internal object SourcePackageValidator {
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    fun validate(source: SourcePackage): String? {
        if (source.version.isBlank()) return "Source version must not be blank."
        if (source.script.isBlank()) return "Source script must not be blank."
        if (!sha256Pattern.matches(source.sha256)) {
            return "Source SHA-256 must contain exactly 64 hexadecimal characters."
        }

        val actual = sha256(source.script)
        if (!actual.equals(source.sha256, ignoreCase = true)) {
            return "Source SHA-256 does not match the script."
        }
        return null
    }

    fun sha256(script: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(script.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

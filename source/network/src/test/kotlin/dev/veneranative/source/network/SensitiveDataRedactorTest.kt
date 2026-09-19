package dev.veneranative.source.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SensitiveDataRedactorTest {
    @Test
    fun sensitiveHeadersAreRedactedWithoutChangingSafeValues() {
        val token = "fixture-secret-token"
        val redacted =
            SensitiveDataRedactor.redactHeaders(
                mapOf(
                    "Authorization" to "Bearer $token",
                    "X-Access-Token" to token,
                    "X-Trace" to "safe",
                ),
            )

        assertEquals(SensitiveDataRedactor.REDACTED, redacted["Authorization"])
        assertEquals(SensitiveDataRedactor.REDACTED, redacted["X-Access-Token"])
        assertEquals("safe", redacted["X-Trace"])
        assertFalse(redacted.toString().contains(token))
    }
}

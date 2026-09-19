package dev.veneranative.source.engine

import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourcePackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SourcePackageValidatorTest {
    @Test
    fun sha256MatchesKnownUtf8Digest() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SourcePackageValidator.sha256("abc"),
        )
    }

    @Test
    fun validPackageIsAccepted() {
        val script = "function answer() { return 42; }"
        val source =
            SourcePackage(
                sourceId = SourceId("fixture"),
                version = "1",
                script = script,
                sha256 = SourcePackageValidator.sha256(script),
            )

        assertNull(SourcePackageValidator.validate(source))
    }

    @Test
    fun mismatchedDigestIsRejected() {
        val source =
            SourcePackage(
                sourceId = SourceId("fixture"),
                version = "1",
                script = "function answer() { return 42; }",
                sha256 = "0".repeat(64),
            )

        assertNotNull(SourcePackageValidator.validate(source))
    }
}

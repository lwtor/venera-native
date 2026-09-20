package dev.veneranative.source.engine

import dev.veneranative.source.api.SourceMetadataResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metadata is read by running the script, so these tests pin what a script must declare and what
 * happens when it declares nothing usable.
 */
class QuickJsMetadataReaderTest {

    private val reader = QuickJsMetadataReader()

    @Test
    fun `reads the fields a source declares about itself`() = runBlocking {
        val result =
            reader.read(
                """
                const key = "manga_dex";
                const name = "MangaDex";
                const version = "1.2.0";
                const minAppVersion = "1.6.0";
                """.trimIndent(),
            )

        val metadata = (result as SourceMetadataResult.Success).metadata
        assertEquals("manga_dex", metadata.sourceId.value)
        assertEquals("MangaDex", metadata.name)
        assertEquals("1.2.0", metadata.version)
        assertEquals("1.6.0", metadata.minAppVersion)
    }

    @Test
    fun `minAppVersion is optional`() = runBlocking {
        val result =
            reader.read(
                """
                const key = "local";
                const name = "Local";
                const version = "0.1";
                """.trimIndent(),
            )

        val metadata = (result as SourceMetadataResult.Success).metadata
        assertNull(metadata.minAppVersion)
    }

    @Test
    fun `a script without a key is invalid rather than engine-unavailable`() = runBlocking {
        val result = reader.read("""const name = "Nameless"; const version = "1";""")

        val invalid = result as SourceMetadataResult.Invalid
        assertTrue(invalid.reason.contains("key"))
    }

    @Test
    fun `a script that does not parse is invalid`() = runBlocking {
        val result = reader.read("function broken( {")

        assertTrue(result is SourceMetadataResult.Invalid)
    }

    @Test
    fun `a blank script is invalid`() = runBlocking {
        val result = reader.read("   ")

        assertTrue(result is SourceMetadataResult.Invalid)
    }

    @Test
    fun `a script that declares a non-string key is invalid`() = runBlocking {
        val result = reader.read("""const key = 7; const name = "N"; const version = "1";""")

        val invalid = result as SourceMetadataResult.Invalid
        assertTrue(invalid.reason.contains("key"))
    }
}

package dev.veneranative.source.engine

import dev.veneranative.source.api.SourceMetadataResult
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metadata is read by instantiating the source class, so these tests pin what a script must declare
 * and what happens when it declares nothing usable.
 */
class QuickJsMetadataReaderTest {

    private val reader = QuickJsMetadataReader()

    @Test
    fun `reads the fields a source declares about itself`() = runBlocking {
        val result =
            reader.read(
                """
                class MangaDex extends ComicSource {
                  constructor() {
                    super();
                    this.name = "MangaDex";
                    this.key = "manga_dex";
                    this.version = "1.2.0";
                    this.minAppVersion = "1.6.0";
                  }
                }
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
                class Local extends ComicSource {
                  constructor() {
                    super();
                    this.name = "Local";
                    this.key = "local";
                    this.version = "0.1";
                  }
                }
                """.trimIndent(),
            )

        val metadata = (result as SourceMetadataResult.Success).metadata
        assertNull(metadata.minAppVersion)
    }

    @Test
    fun `a script without the class convention is invalid`() = runBlocking {
        val result = reader.read("""const key = "local"; const name = "Local"; const version = "1";""")

        val invalid = result as SourceMetadataResult.Invalid
        assertTrue(invalid.reason.contains("ComicSource"))
    }

    @Test
    fun `a script that throws while constructing is invalid`() = runBlocking {
        val result =
            reader.read(
                """
                class Broken extends ComicSource {
                  constructor() {
                    super();
                    throw new Error("nope");
                  }
                }
                """.trimIndent(),
            )

        assertTrue(result is SourceMetadataResult.Invalid)
    }

    @Test
    fun `a script that does not parse is invalid`() = runBlocking {
        val result = reader.read("class Broken extends ComicSource {")

        assertTrue(result is SourceMetadataResult.Invalid)
    }

    @Test
    fun `a blank script is invalid`() = runBlocking {
        val result = reader.read("   ")

        assertTrue(result is SourceMetadataResult.Invalid)
    }

    @Test(timeout = 5_000L)
    fun `non terminating metadata returns at its configured deadline`() = runBlocking {
        val reader = QuickJsMetadataReader(timeoutMillis = 100L)
        val elapsed = measureTimeMillis {
            val result = reader.read(
                """
                class Stuck extends ComicSource {
                  constructor() {
                    super();
                    while (true) {}
                  }
                }
                """.trimIndent(),
            )
            assertTrue(result is SourceMetadataResult.Invalid)
        }
        assertTrue("metadata timeout took ${elapsed}ms", elapsed < 2_000L)
    }

    @Test
    fun `a key outside the upstream alphabet is invalid`() = runBlocking {
        val result =
            reader.read(
                """
                class Odd extends ComicSource {
                  constructor() {
                    super();
                    this.name = "Odd";
                    this.key = "not-a-key";
                    this.version = "1";
                  }
                }
                """.trimIndent(),
            )

        val invalid = result as SourceMetadataResult.Invalid
        assertTrue(invalid.reason.contains("key"))
    }
}

package dev.veneranative.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class LocalPageCacheTest {
    @Test fun materializesAndEvictsOldPages() {
        val dir = Files.createTempDirectory("page-cache").toFile()
        val cache = LocalPageCache(dir, maxBytes = 6, maxEntryBytes = 6)
        val first = cache.materialize("one") { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
        cache.materialize("two") { ByteArrayInputStream(byteArrayOf(5, 6, 7, 8)) }
        assertFalse(first.exists())
        assertEquals(4, dir.listFiles { f -> f.extension == "page" }!!.single().length())
        dir.deleteRecursively()
    }

    @Test fun rejectsOversizeWithoutLeavingPartialFile() {
        val dir = Files.createTempDirectory("page-cache").toFile()
        val cache = LocalPageCache(dir, maxBytes = 5, maxEntryBytes = 4)
        runCatching { cache.materialize("big") { ByteArrayInputStream(ByteArray(5)) } }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        dir.deleteRecursively()
    }
}

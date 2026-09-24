package dev.veneranative.core.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod

class ArchiveIndexTest {
    @Test fun readsEntriesInArchiveAndOpensNamedEntry() {
        val file = Files.createTempFile("fixture", ".cbz")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            listOf("10.jpg" to byteArrayOf(10), "2.jpg" to byteArrayOf(2)).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        val reader = CommonsArchiveReader.open(ArchiveFormat.Zip, FileChannel.open(file, StandardOpenOption.READ))
        reader.use {
            assertEquals(listOf("10.jpg", "2.jpg"), it.entries().map(ArchiveEntry::name))
            assertArrayEquals(byteArrayOf(2), it.openEntry("2.jpg").use { stream -> stream.readBytes() })
        }
        Files.deleteIfExists(file)
    }

    @Test fun rejectsUnknownAndSortsNaturally() {
        assertEquals(null, ArchiveFormat.from("comic.rar"))
        assertTrue(listOf("10.jpg", "2.jpg", "1.jpg").sortedWith(NaturalOrderComparator) == listOf("1.jpg", "2.jpg", "10.jpg"))
    }

    @Test fun readsSevenZipEntry() {
        val file = Files.createTempFile("fixture", ".7z")
        SevenZOutputFile(file.toFile()).use { archive ->
            archive.setContentCompression(SevenZMethod.COPY)
            val entry = SevenZArchiveEntry().apply { name = "chapter/1.jpg" }
            archive.putArchiveEntry(entry); archive.write(byteArrayOf(7, 8, 9)); archive.closeArchiveEntry()
        }
        CommonsArchiveReader.open(ArchiveFormat.SevenZip, FileChannel.open(file, StandardOpenOption.READ)).use { reader ->
            assertEquals(listOf("chapter/1.jpg"), reader.entries().map(ArchiveEntry::name))
            assertArrayEquals(byteArrayOf(7, 8, 9), reader.openEntry("chapter/1.jpg").use { it.readBytes() })
        }
        Files.deleteIfExists(file)
    }
}

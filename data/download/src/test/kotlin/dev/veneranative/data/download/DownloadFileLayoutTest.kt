package dev.veneranative.data.download

import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadFileLayoutTest {

    @get:Rule val folder = TemporaryFolder()

    private fun layout(): DownloadFileLayout = DownloadFileLayout(folder.root)

    private fun File.siblings(): List<File> = parentFile!!.listFiles()!!.toList()

    @Test
    fun `the same ids always give the same path`() {
        assertEquals(layout().relativePathOf("s", "c", "ch", 3), layout().relativePathOf("s", "c", "ch", 3))
    }

    @Test
    fun `different ids never share a directory`() {
        val layout = layout()

        assertFalse(layout.relativePathOf("s", "c", "ch-1", 0) == layout.relativePathOf("s", "c", "ch-2", 0))
        assertFalse(layout.relativePathOf("s", "c-1", "ch", 0) == layout.relativePathOf("s", "c-2", "ch", 0))
    }

    @Test
    fun `an id that would escape the directory cannot`() {
        val layout = layout()
        val hostile = "../../../../etc/passwd"

        val path = layout.relativePathOf(hostile, "c/../..", "ch/../../..", 0)

        assertFalse(path.contains(".."))
        assertEquals(File(folder.root, path).canonicalPath, layout.absoluteOf(path).canonicalPath)
        assertTrue(layout.isInsideRoot(layout.absoluteOf(path)))
    }

    @Test
    fun `an id of any length still gives a path of the same length`() {
        val layout = layout()
        val short = layout.relativePathOf("a", "b", "c", 0)
        val long = layout.relativePathOf("a".repeat(4_096), "b".repeat(4_096), "c".repeat(4_096), 0)

        assertEquals(short.length, long.length)
    }

    @Test
    fun `page names sort in page order`() {
        val names = (0..11).map { layout().pageFileName(it) }

        assertEquals(names, names.sorted())
    }

    @Test
    fun `relative and absolute paths round-trip`() {
        val layout = layout()
        val path = layout.relativePathOf("s", "c", "ch", 7)

        assertEquals(path, layout.relativeOf(layout.absoluteOf(path)))
    }

    @Test
    fun `a page appears at its final name only once it is complete`() = runTest {
        val layout = layout()
        val target = layout.pageFile("s", "c", "ch", 0)
        val seen = mutableListOf<Boolean>()

        val written = layout.writeAtomically(target) { sink ->
            seen += target.isFile
            sink.write(pngBytes(8, 12))
        }

        assertEquals(pngBytes(8, 12).size.toLong(), written)
        assertEquals(listOf(false), seen)
        assertTrue(target.isFile)
        assertEquals(emptyList<File>(), target.siblings().filter { it.name.endsWith(".part") })
    }

    @Test
    fun `a write that fails leaves neither a page nor a part file`() = runTest {
        val layout = layout()
        val target = layout.pageFile("s", "c", "ch", 0)

        runCatching {
            layout.writeAtomically(target) { sink ->
                sink.write("half a page".toByteArray())
                throw IOException("ENOSPC")
            }
        }

        assertFalse(target.exists())
        assertTrue(target.siblings().none { it.name.endsWith(".part") })
    }

    @Test(expected = IllegalStateException::class)
    fun `writing outside the download root is refused`() = runTest {
        val layout = layout()

        layout.writeAtomically(File(folder.root.parentFile, "escaped.bin"), byteArrayOf(1, 2, 3))
    }

    @Test
    fun `manifests and pages are found wherever they sit under the root`() = runTest {
        val layout = layout()
        layout.writeAtomically(layout.manifestFile("s", "c", "ch-1"), byteArrayOf())
        layout.writeAtomically(layout.manifestFile("s", "c", "ch-2"), byteArrayOf())
        layout.writeAtomically(layout.pageFile("s", "c", "ch-1", 0), byteArrayOf())

        assertEquals(2, layout.manifestFiles().size)
        assertEquals(1, layout.pageFiles().size)
    }

    @Test
    fun `cancelling a chapter removes its directory`() = runTest {
        val layout = layout()
        val manifest = layout.manifestFile("s", "c", "ch")
        layout.writeAtomically(manifest, byteArrayOf())
        layout.writeAtomically(layout.pageFile("s", "c", "ch", 0), byteArrayOf())

        layout.deleteChapterFiles("s", "c", "ch")

        assertFalse(manifest.exists())
        assertEquals(emptyList<File>(), layout.pageFiles())
    }
}

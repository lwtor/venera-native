package dev.veneranative.data.source

import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourcePackageStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val store by lazy { SourcePackageStore(File(temporaryFolder.root, "sources")) }

    @Test
    fun `a written package can be read back`() {
        store.write(source(id = "source-a", version = "1.0.0", script = "class A {}"))

        val stored = store.read(SourceId("source-a"))

        requireNotNull(stored)
        assertEquals("1.0.0", stored.installed.version)
        assertEquals("class A {}", stored.script)
        assertEquals(HASH, stored.sha256)
        assertEquals(listOf("source-a"), store.list().map { it.sourceId.value })
    }

    @Test
    fun `installing the same id again replaces the entry instead of duplicating it`() {
        store.write(source(id = "source-a", version = "1.0.0", script = "old"))
        store.write(source(id = "source-a", version = "2.0.0", script = "new"))

        val entries = store.list()

        assertEquals(1, entries.size)
        assertEquals("2.0.0", entries.single().version)
        assertEquals("new", store.read(SourceId("source-a"))?.script)
    }

    @Test
    fun `enabling and disabling only affects the named source`() {
        store.write(source(id = "source-a", version = "1", script = "a"))
        store.write(source(id = "source-b", version = "1", script = "b"))

        assertTrue(store.setEnabled(SourceId("source-a"), enabled = false))

        assertFalse(store.read(SourceId("source-a"))!!.installed.enabled)
        assertTrue(store.read(SourceId("source-b"))!!.installed.enabled)
        assertFalse(store.setEnabled(SourceId("missing"), enabled = false))
    }

    @Test
    fun `removing a package deletes its files and its entry`() {
        store.write(source(id = "source-a", version = "1", script = "a"))

        assertTrue(store.remove(SourceId("source-a")))

        assertNull(store.read(SourceId("source-a")))
        assertTrue(store.list().isEmpty())
        assertTrue(temporaryFolder.root.walkTopDown().none { it.name == "source.js" })
        assertFalse(store.remove(SourceId("source-a")))
    }

    @Test
    fun `the list survives a new store instance`() {
        store.write(source(id = "source-a", version = "1", script = "a"))

        val reloaded = SourcePackageStore(File(temporaryFolder.root, "sources")).list()

        assertEquals(listOf("source-a"), reloaded.map { it.sourceId.value })
        assertEquals(listOf("Source A"), reloaded.map { it.name })
    }

    @Test
    fun `a malformed index entry does not hide the valid ones`() {
        store.write(source(id = "source-a", version = "1", script = "a"))
        File(temporaryFolder.root, "sources/index.json")
            .writeText("""{"sources":[{"name":"broken"},{"sourceId":"source-b","name":"B","version":"1"}]}""")

        val entries = store.list()

        assertEquals(listOf("source-b"), entries.map { it.sourceId.value })
    }

    @Test
    fun `a hostile source id cannot escape the storage root`() {
        val hostile = SourceId("../../escaped")

        store.write(source(id = hostile.value, version = "1", script = "a"))

        assertTrue(store.read(hostile) != null)
        assertTrue(
            "the package must stay inside the storage root",
            File(temporaryFolder.root, "sources").walkTopDown().any { it.isFile },
        )
        assertFalse(File(temporaryFolder.root, "escaped").exists())
        assertFalse(File(temporaryFolder.root.parentFile, "escaped").exists())
    }

    @Test
    fun `failed index commit preserves the previous script after restart`() {
        val root = File(temporaryFolder.root, "sources")
        store.write(source("source-a", "1", "old"))
        val failing = SourcePackageStore(root, beforeIndexCommit = { throw java.io.IOException("disk full") })
        assertTrue(runCatching { failing.write(source("source-a", "2", "new")) }.isFailure)
        val restored = SourcePackageStore(root).read(SourceId("source-a"))!!
        assertEquals("1", restored.installed.version)
        assertEquals("old", restored.script)
        assertEquals(HASH, restored.sha256)
    }

    private fun source(id: String, version: String, script: String) = StoredSource(
        installed = InstalledSource(
            sourceId = SourceId(id),
            name = "Source A",
            version = version,
            enabled = true,
            origin = "file:///tmp/$id.js",
        ),
        script = script,
        sha256 = HASH,
    )

    private companion object {
        val HASH = "0".repeat(64)
    }
}

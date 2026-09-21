package dev.veneranative.data.source

import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourceMetadata
import dev.veneranative.source.api.SourceMetadataReader
import dev.veneranative.source.api.SourceMetadataResult
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Install management behaviour.
 *
 * The rule that matters most is that storage is only written after the runtime accepted a package:
 * a failed install must leave the previous, working version exactly as it was.
 */
class SourceRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sourceId = SourceId("source-a")
    private val runtime = FakeSourceScriptRuntime()
    private val metadataReader = FakeMetadataReader()
    private val fetcher = FakeScriptFetcher()
    // Lazy: the temporary folder rule only creates the directory after the test instance exists.
    private val store by lazy { SourcePackageStore(File(temporaryFolder.root, "sources")) }
    private val repository: SourceRepository by lazy {
        DefaultSourceRepository(
            store = store,
            runtime = runtime,
            metadataReader = metadataReader,
            fetcher = fetcher,
        )
    }

    @Test
    fun `installing a script stores it, loads it and reports metadata`() = runTest {
        givenScript(version = "1.0.0", script = "class A {}")

        val outcome = repository.install(LOCATION)

        val installed = (outcome as InstallOutcome.Success).installed
        assertEquals("Source A", installed.name)
        assertEquals("1.0.0", installed.version)
        assertTrue(installed.enabled)
        assertEquals(LOCATION, installed.origin)
        assertEquals(listOf("source-a"), runtime.installedPackages.map { it.sourceId.value })
        assertEquals(listOf("source-a"), repository.installed().map { it.sourceId.value })
        val storedHash = store.read(sourceId)?.sha256
        assertTrue("expected a sha-256 hash, got $storedHash", storedHash != null && HASH_PATTERN.matches(storedHash))
    }

    @Test
    fun `an upgrade replaces the stored version and loads the new one`() = runTest {
        givenScript(version = "1.0.0", script = "class A {}")
        repository.install(LOCATION)
        givenScript(version = "2.0.0", script = "class A2 {}")

        val outcome = repository.install(LOCATION)

        assertEquals("2.0.0", (outcome as InstallOutcome.Success).installed.version)
        assertEquals("class A2 {}", store.read(sourceId)?.script)
        assertEquals(listOf("1.0.0", "2.0.0"), runtime.installedPackages.map { it.version })
        assertEquals(1, repository.installed().size)
    }

    @Test
    fun `a rejected package leaves the previous version working`() = runTest {
        givenScript(version = "1.0.0", script = "class A {}")
        repository.install(LOCATION)
        givenScript(version = "2.0.0", script = "class A2 {}")
        runtime.rejectedSourceIds += sourceId

        val outcome = repository.install(LOCATION)

        assertEquals(SourceInstallError.Rejected, (outcome as InstallOutcome.Failure).error)
        assertEquals("1.0.0", store.read(sourceId)?.installed?.version)
        assertEquals("class A {}", store.read(sourceId)?.script)
        assertEquals("1.0.0", repository.installed().single().version)
    }

    @Test
    fun `a script whose metadata cannot be read is not installed`() = runTest {
        givenScript(version = "1.0.0", script = "class A {}")
        metadataReader.result = SourceMetadataResult.Invalid("missing key")

        val outcome = repository.install(LOCATION)

        val failure = outcome as InstallOutcome.Failure
        assertEquals(SourceInstallError.InvalidMetadata, failure.error)
        // The lower layer's wording is kept for diagnostics but is not what the UI renders.
        assertEquals("missing key", failure.detail)
        assertTrue(repository.installed().isEmpty())
        assertTrue(runtime.installedPackages.isEmpty())
    }

    @Test
    fun `a device without a source engine is reported as unavailable`() = runTest {
        givenScript(version = "1.0.0", script = "class A {}")
        metadataReader.result = SourceMetadataResult.EngineUnavailable("no engine on this device")

        val outcome = repository.install(LOCATION)

        assertEquals(
            SourceInstallError.EngineUnavailable,
            (outcome as InstallOutcome.Failure).error,
        )
        assertTrue(repository.installed().isEmpty())
    }

    @Test
    fun `an unreadable location fails without touching storage`() = runTest {
        fetcher.failure = "No source script at this location."

        val outcome = repository.install(LOCATION)

        assertEquals(
            SourceInstallError.LocationUnreadable,
            (outcome as InstallOutcome.Failure).error,
        )
        assertTrue(repository.installed().isEmpty())
    }

    @Test
    fun `disabling unloads the source and enabling loads it again`() = runTest {
        givenScript(version = "1.0.0", script = "class A {}")
        repository.install(LOCATION)
        runtime.installedPackages.clear()

        assertTrue(repository.setEnabled(sourceId, enabled = false))
        assertEquals(listOf(sourceId), runtime.unloadedSourceIds)
        assertFalse(repository.installed().single().enabled)
        assertTrue(runtime.installedPackages.isEmpty())

        assertTrue(repository.setEnabled(sourceId, enabled = true))
        assertEquals(listOf(sourceId), runtime.installedPackages.map { it.sourceId })
        assertTrue(repository.installed().single().enabled)
    }

    @Test
    fun `enable and disable report unknown sources instead of pretending success`() = runTest {
        assertFalse(repository.setEnabled(sourceId, enabled = false))
        assertFalse(repository.uninstall(sourceId))
    }

    @Test
    fun `uninstalling removes the package and unloads it`() = runTest {
        givenScript(version = "1.0.0", script = "class A {}")
        repository.install(LOCATION)

        val removed = repository.uninstall(sourceId)

        assertTrue(removed)
        assertTrue(repository.installed().isEmpty())
        assertEquals(listOf(sourceId), runtime.unloadedSourceIds)
    }

    @Test
    fun `a failed store write unloads a source that was not installed before`() = runTest {
        // Pointing the store at a regular file makes every write fail, which is the only way to
        // reach the rollback path deterministically.
        val brokenRoot = temporaryFolder.newFile("not-a-directory")
        val brokenRepository = DefaultSourceRepository(
            store = SourcePackageStore(brokenRoot),
            runtime = runtime,
            metadataReader = metadataReader,
            fetcher = fetcher,
        )
        givenScript(version = "1.0.0", script = "class A {}")

        val outcome = brokenRepository.install(LOCATION)

        assertEquals(
            SourceInstallError.StorageFailed,
            (outcome as InstallOutcome.Failure).error,
        )
        assertEquals(listOf(sourceId), runtime.unloadedSourceIds)
    }

    @Test
    fun `failed upgrade does not reactivate a disabled source`() = runTest {
        givenScript("1", "old")
        repository.install(LOCATION)
        repository.setEnabled(sourceId, false)
        val failing = DefaultSourceRepository(
            SourcePackageStore(File(temporaryFolder.root, "sources"), beforeIndexCommit = {
                throw java.io.IOException("disk full")
            }), runtime, metadataReader, fetcher,
        )
        givenScript("2", "new")
        assertTrue(failing.install(LOCATION) is InstallOutcome.Failure)
        assertFalse(store.read(sourceId)!!.installed.enabled)
        assertEquals("old", store.read(sourceId)!!.script)
        assertEquals(sourceId, runtime.unloadedSourceIds.last())
    }

    private fun givenScript(version: String, script: String) {
        fetcher.scripts[LOCATION] = script
        metadataReader.result = SourceMetadataResult.Success(
            SourceMetadata(sourceId = sourceId, name = "Source A", version = version),
        )
    }

    private class FakeMetadataReader : SourceMetadataReader {
        var result: SourceMetadataResult = SourceMetadataResult.Invalid("no metadata configured")

        override suspend fun read(script: String): SourceMetadataResult = result
    }

    private class FakeScriptFetcher : SourceScriptFetcher {
        val scripts = mutableMapOf<String, String>()
        var failure: String? = null

        override suspend fun fetch(location: String): FetchedScript {
            failure?.let { return FetchedScript.Failure(it) }
            val script = scripts[location] ?: return FetchedScript.Failure("No source script at this location.")
            return FetchedScript.Success(script)
        }
    }

    private companion object {
        const val LOCATION = "file:///tmp/source.js"
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}

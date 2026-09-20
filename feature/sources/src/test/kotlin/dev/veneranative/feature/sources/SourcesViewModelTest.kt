package dev.veneranative.feature.sources

import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.InstallOutcome
import dev.veneranative.data.source.SourceInstallError
import dev.veneranative.data.source.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourcesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeSourceRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `installed sources are loaded into the ready state`() = runTest(dispatcher) {
        repository.sources = listOf(source("a"), source("b"))

        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        assertEquals(SourcesStatus.Ready, viewModel.state.value.status)
        assertEquals(listOf("a", "b"), viewModel.state.value.sources.map { it.sourceId.value })
    }

    @Test
    fun `an install without a location does nothing`() = runTest(dispatcher) {
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.Install)
        advanceUntilIdle()

        assertTrue(repository.installLocations.isEmpty())
        assertTrue(!viewModel.state.value.installing)
    }

    @Test
    fun `a successful install clears the field and names the installed source`() = runTest(dispatcher) {
        repository.outcome = InstallOutcome.Success(source("a", name = "Source A"))
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SourcesAction.InstallLocationChanged("/tmp/source.js"))

        viewModel.onAction(SourcesAction.Install)
        advanceUntilIdle()

        assertEquals(listOf("/tmp/source.js"), repository.installLocations)
        assertEquals("", viewModel.state.value.installLocation)
        assertEquals("Source A installed.", viewModel.state.value.message)
        assertTrue(!viewModel.state.value.installing)
    }

    @Test
    fun `a failed install keeps what the user typed and shows product copy`() = runTest(dispatcher) {
        repository.outcome = InstallOutcome.Failure(
            error = SourceInstallError.InvalidMetadata,
            detail = "com.example.Failure: missing key at line 3",
        )
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SourcesAction.InstallLocationChanged("/tmp/source.js"))

        viewModel.onAction(SourcesAction.Install)
        advanceUntilIdle()

        assertEquals("/tmp/source.js", viewModel.state.value.installLocation)
        // The lower layer's detail must not reach the user.
        assertEquals("That script does not declare a usable source.", viewModel.state.value.message)
    }

    @Test
    fun `a list failure offers a retry that recovers`() = runTest(dispatcher) {
        repository.listFailure = IllegalStateException("storage unreadable")
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        assertEquals(SourcesStatus.Failed, viewModel.state.value.status)
        assertEquals("The source list could not be read.", viewModel.state.value.message)

        repository.listFailure = null
        repository.sources = listOf(source("a"))
        viewModel.onAction(SourcesAction.Retry)
        advanceUntilIdle()

        assertEquals(SourcesStatus.Ready, viewModel.state.value.status)
        assertEquals(1, viewModel.state.value.sources.size)
    }

    @Test
    fun `enabling a source reloads the list with the new state`() = runTest(dispatcher) {
        repository.sources = listOf(source("a", enabled = true))
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.SetEnabled(SourceId("a"), enabled = false))
        advanceUntilIdle()

        assertEquals(listOf(SourceId("a") to false), repository.enabledChanges)
        assertTrue(!viewModel.state.value.sources.single().enabled)
        assertTrue(viewModel.state.value.busySourceIds.isEmpty())
    }

    @Test
    fun `uninstalling drops the source and reports it`() = runTest(dispatcher) {
        repository.sources = listOf(source("a"))
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.Uninstall(SourceId("a")))
        advanceUntilIdle()

        assertEquals(listOf(SourceId("a")), repository.uninstalled)
        assertTrue(viewModel.state.value.sources.isEmpty())
        assertEquals("Source removed.", viewModel.state.value.message)
    }

    @Test
    fun `acting on a source that is gone reports it instead of pretending success`() = runTest(dispatcher) {
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.SetEnabled(SourceId("missing"), enabled = true))
        advanceUntilIdle()

        assertEquals("That source is no longer installed.", viewModel.state.value.message)
    }

    @Test
    fun `dismissing a message clears it`() = runTest(dispatcher) {
        repository.sources = listOf(source("a"))
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SourcesAction.Uninstall(SourceId("a")))
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.DismissMessage)

        assertNull(viewModel.state.value.message)
    }

    private fun source(id: String, name: String = "Source $id", enabled: Boolean = true) = InstalledSource(
        sourceId = SourceId(id),
        name = name,
        version = "1.0.0",
        enabled = enabled,
        origin = "/tmp/$id.js",
    )

    private class FakeSourceRepository : SourceRepository {
        var sources: List<InstalledSource> = emptyList()
        var outcome: InstallOutcome = InstallOutcome.Failure(SourceInstallError.LocationUnreadable)
        var listFailure: Throwable? = null
        val installLocations = mutableListOf<String>()
        val enabledChanges = mutableListOf<Pair<SourceId, Boolean>>()
        val uninstalled = mutableListOf<SourceId>()

        override suspend fun installed(): List<InstalledSource> {
            listFailure?.let { throw it }
            return sources
        }

        override suspend fun install(location: String): InstallOutcome {
            installLocations += location
            return outcome
        }

        override suspend fun setEnabled(sourceId: SourceId, enabled: Boolean): Boolean {
            enabledChanges += sourceId to enabled
            val exists = sources.any { it.sourceId == sourceId }
            sources = sources.map { if (it.sourceId == sourceId) it.copy(enabled = enabled) else it }
            return exists
        }

        override suspend fun uninstall(sourceId: SourceId): Boolean {
            uninstalled += sourceId
            val exists = sources.any { it.sourceId == sourceId }
            sources = sources.filterNot { it.sourceId == sourceId }
            return exists
        }
    }
}

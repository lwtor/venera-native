package dev.veneranative.feature.sources

import dev.veneranative.core.model.InstalledSource
import dev.veneranative.core.model.SourceId
import dev.veneranative.data.source.InstallOutcome
import dev.veneranative.data.source.SourceInstallError
import dev.veneranative.data.source.SourceRepository
import dev.veneranative.data.source.SourceCatalogEntry
import dev.veneranative.data.source.SourceCatalogRepository
import dev.veneranative.data.source.SourceCatalogResult
import dev.veneranative.data.source.defaultVeneraSourceCatalogUrl
import dev.veneranative.data.source.SourceLoginOutcome
import dev.veneranative.data.source.SourceLoginCheckOutcome
import dev.veneranative.data.settings.ScreenPreferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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
    fun `expansion checks login in background and manual retry joins the same request`() = runTest(dispatcher) {
        val installed = source("a")
        repository.sources = listOf(installed)
        repository.configurationResult = dev.veneranative.core.model.InstalledSourceConfiguration(
            source = installed,
            settings = emptyList(),
            settingValues = emptyMap(),
            supportsPasswordLogin = true,
            supportsLogout = false,
            supportsLoginValidation = true,
            registerWebsite = null,
            loggedIn = true,
        )
        val gate = CompletableDeferred<SourceLoginCheckOutcome>()
        repository.loginCheckGate = gate
        val viewModel = SourcesViewModel(repository)
        runCurrent()

        viewModel.onAction(SourcesAction.OpenConfiguration(installed.sourceId))
        runCurrent()
        assertEquals(1, repository.loginCheckCalls)
        assertTrue(installed.sourceId in viewModel.state.value.loginCheckInProgressSourceIds)

        viewModel.onAction(SourcesAction.CheckLogin(installed.sourceId))
        runCurrent()
        assertEquals(1, repository.loginCheckCalls)
        assertTrue(installed.sourceId in viewModel.state.value.manualLoginCheckSourceIds)

        gate.complete(SourceLoginCheckOutcome.Invalid)
        advanceUntilIdle()
        assertEquals(SourceLoginCheckOutcome.Invalid, viewModel.state.value.loginCheckOutcomes[installed.sourceId])
        assertTrue(installed.sourceId !in viewModel.state.value.loginCheckInProgressSourceIds)
    }

    @Test
    fun `a setting change shows at once and is written after the quiet period`() = runTest(dispatcher) {
        val installed = source("a")
        repository.sources = listOf(installed)
        repository.configurationResult = dev.veneranative.core.model.InstalledSourceConfiguration(
            source = installed,
            settings = emptyList(),
            settingValues = mapOf("quality" to "\"sd\""),
            supportsPasswordLogin = false,
            supportsLogout = false,
            supportsLoginValidation = false,
            registerWebsite = null,
            loggedIn = false,
        )
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SourcesAction.OpenConfiguration(installed.sourceId))
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.SetSettingValue(installed.sourceId, "quality", "\"hd\""))

        // No save button: the control's value has to be true immediately.
        assertEquals("\"hd\"", viewModel.state.value.configuration?.settingValues?.get("quality"))
        assertTrue(repository.writtenSettings.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf("quality" to "\"hd\""), repository.writtenSettings.map { it.second to it.third })
        assertTrue(!viewModel.state.value.settingsSaving)
    }

    @Test
    fun `a burst of edits writes each key once, with the last value`() = runTest(dispatcher) {
        val installed = source("a")
        repository.sources = listOf(installed)
        repository.configurationResult = dev.veneranative.core.model.InstalledSourceConfiguration(
            source = installed,
            settings = emptyList(),
            settingValues = mapOf("quality" to "\"sd\"", "interval" to "\"500\""),
            supportsPasswordLogin = false,
            supportsLogout = false,
            supportsLoginValidation = false,
            registerWebsite = null,
            loggedIn = false,
        )
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SourcesAction.OpenConfiguration(installed.sourceId))
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.SetSettingValue(installed.sourceId, "quality", "\"h\""))
        viewModel.onAction(SourcesAction.SetSettingValue(installed.sourceId, "quality", "\"hd\""))
        viewModel.onAction(SourcesAction.SetSettingValue(installed.sourceId, "interval", "\"800\""))
        advanceUntilIdle()

        assertEquals(
            listOf("quality" to "\"hd\"", "interval" to "\"800\""),
            repository.writtenSettings.map { it.second to it.third },
        )
    }

    @Test
    fun `a refused write puts the control back and says so in place`() = runTest(dispatcher) {
        val installed = source("a")
        repository.sources = listOf(installed)
        repository.configurationResult = dev.veneranative.core.model.InstalledSourceConfiguration(
            source = installed,
            settings = emptyList(),
            settingValues = mapOf("quality" to "\"sd\""),
            supportsPasswordLogin = false,
            supportsLogout = false,
            supportsLoginValidation = false,
            registerWebsite = null,
            loggedIn = false,
        )
        repository.settingWriteResult = false
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SourcesAction.OpenConfiguration(installed.sourceId))
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.SetSettingValue(installed.sourceId, "quality", "\"hd\""))
        advanceUntilIdle()

        assertEquals("\"sd\"", viewModel.state.value.configuration?.settingValues?.get("quality"))
        assertEquals("这项设置没能保存，请重试。", viewModel.state.value.settingsMessage)
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
        assertEquals("Source A 已安装。", viewModel.state.value.message)
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
        assertEquals("此脚本未声明有效的漫画源。", viewModel.state.value.message)
    }

    @Test
    fun `a list failure offers a retry that recovers`() = runTest(dispatcher) {
        repository.listFailure = IllegalStateException("storage unreadable")
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        assertEquals(SourcesStatus.Failed, viewModel.state.value.status)
        assertEquals("无法读取漫画源列表。", viewModel.state.value.message)

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
        assertEquals("漫画源已移除。", viewModel.state.value.message)
    }

    @Test
    fun `acting on a source that is gone reports it instead of pretending success`() = runTest(dispatcher) {
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.SetEnabled(SourceId("missing"), enabled = true))
        advanceUntilIdle()

        assertEquals("此漫画源已不在已安装列表中。", viewModel.state.value.message)
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

    @Test
    fun `login success is reported while the configuration remains open`() = runTest(dispatcher) {
        repository.loginOutcome = SourceLoginOutcome.Success
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.Login(SourceId("a"), "reader", "secret"))
        advanceUntilIdle()

        assertEquals("登录成功。", viewModel.state.value.message)
        assertTrue(!viewModel.state.value.accountActionInProgress)
    }

    @Test
    fun `login failure is reported while the configuration remains open`() = runTest(dispatcher) {
        repository.loginOutcome = SourceLoginOutcome.Failed
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.Login(SourceId("a"), "reader", "wrong"))
        advanceUntilIdle()

        assertEquals("登录失败，请检查账号密码或网络。", viewModel.state.value.message)
        assertTrue(!viewModel.state.value.accountActionInProgress)
    }

    @Test
    fun `login validity check reports a source confirmed expired session`() = runTest(dispatcher) {
        repository.loginCheckOutcome = SourceLoginCheckOutcome.Invalid
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(SourcesAction.CheckLogin(SourceId("a")))
        advanceUntilIdle()

        assertEquals(SourceLoginCheckOutcome.Invalid, viewModel.state.value.loginCheckOutcome)
        // The result is now carried by the outcome the "登录状态" row renders as a chip; a banner
        // message on top of it said the same thing twice.
        assertNull(viewModel.state.value.message)
        assertTrue(!viewModel.state.value.accountActionInProgress)
    }

    @Test
    fun `catalog entries load and install by their resolved script URL`() = runTest(dispatcher) {
        val entry = SourceCatalogEntry(
            "MangaDex", "manga_dex", "1.2.0", null, "https://cdn.example/manga_dex.js", defaultVeneraSourceCatalogUrl(),
        )
        val catalog = FakeSourceCatalogRepository(SourceCatalogResult.Success(listOf(entry)))
        repository.outcome = InstallOutcome.Success(source("manga_dex", "MangaDex"))
        val viewModel = SourcesViewModel(repository, catalog)
        advanceUntilIdle()

        assertEquals(CatalogStatus.Ready, viewModel.state.value.catalogStatus)
        assertEquals(listOf(entry), viewModel.state.value.catalogEntries)
        assertEquals(listOf(viewModel.state.value.catalogLocation), catalog.locations)

        viewModel.onAction(SourcesAction.InstallCatalogEntry(entry))
        advanceUntilIdle()

        assertEquals(listOf(entry.scriptUrl), repository.installLocations)
        assertEquals("MangaDex 已安装。", viewModel.state.value.message)
    }

    @Test
    fun `catalog version differences are shown and can update an installed source`() = runTest(dispatcher) {
        val installed = source("manga_dex", name = "MangaDex", version = "1.0.0")
            .copy(catalogLocation = defaultVeneraSourceCatalogUrl())
        repository.sources = listOf(installed)
        val update = SourceCatalogEntry(
            "MangaDex", "manga_dex", "2.0.0", null, "https://cdn.example/manga_dex.js", defaultVeneraSourceCatalogUrl(),
        )
        val catalog = FakeSourceCatalogRepository(SourceCatalogResult.Success(listOf(update)))
        val viewModel = SourcesViewModel(repository, catalog)
        repository.outcome = InstallOutcome.Success(installed.copy(version = "2.0.0"))
        advanceUntilIdle()

        assertEquals(update, viewModel.state.value.catalogUpdateFor(installed))
        assertEquals(1, viewModel.state.value.catalogUpdateCount)

        viewModel.onAction(SourcesAction.InstallCatalogEntry(update))
        advanceUntilIdle()

        assertEquals(listOf(update.scriptUrl), repository.installLocations)
        assertEquals(listOf(update.catalogLocation), repository.catalogInstallLocations.map { it.second })
        assertEquals("2.0.0", viewModel.state.value.sources.single().version)
        assertEquals("MangaDex 已更新至版本 2.0.0。", viewModel.state.value.message)
    }

    @Test
    fun `update all installs every outdated source from the catalog`() = runTest(dispatcher) {
        val catalog = defaultVeneraSourceCatalogUrl()
        val a = source("a", name = "A", version = "1.0.0").copy(catalogLocation = catalog)
        val b = source("b", name = "B", version = "1.0.0").copy(catalogLocation = catalog)
        repository.sources = listOf(a, b)
        val catalogRepo = FakeSourceCatalogRepository(SourceCatalogResult.Success(listOf(
            SourceCatalogEntry("A", "a", "2.0.0", null, "https://cdn.example/a.js", catalog),
            SourceCatalogEntry("B", "b", "2.0.0", null, "https://cdn.example/b.js", catalog),
        )))
        repository.outcome = InstallOutcome.Success(a.copy(version = "2.0.0"))
        val viewModel = SourcesViewModel(repository, catalogRepo)
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.catalogUpdateCount)

        viewModel.onAction(SourcesAction.UpdateAllOutdated)
        advanceUntilIdle()

        assertEquals(2, repository.catalogInstallLocations.size)
        assertTrue(!viewModel.state.value.installing)
        assertEquals("已更新 2 个来源。", viewModel.state.value.message)
    }

    @Test
    fun `update all ignores stale installs and aborts when nothing is outdated`() = runTest(dispatcher) {
        val catalog = defaultVeneraSourceCatalogUrl()
        repository.sources = listOf(source("a", version = "1.0.0").copy(catalogLocation = catalog))
        val catalogRepo = FakeSourceCatalogRepository(SourceCatalogResult.Success(emptyList()))
        val viewModel = SourcesViewModel(repository, catalogRepo)
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.catalogUpdateCount)

        viewModel.onAction(SourcesAction.UpdateAllOutdated)
        advanceUntilIdle()

        assertTrue(repository.catalogInstallLocations.isEmpty())
        assertTrue(!viewModel.state.value.installing)
    }

    @Test
    fun `same source in another catalog offers a switch instead of an update`() = runTest(dispatcher) {
        val firstCatalog = "https://catalog.example/first/index.json"
        val secondCatalog = "https://catalog.example/second/index.json"
        val installed = source("manga_dex", name = "MangaDex", version = "1.0.0").copy(catalogLocation = firstCatalog)
        repository.sources = listOf(installed)
        val otherCatalogCopy = SourceCatalogEntry(
            "MangaDex", "manga_dex", "3.0.0", null, "https://cdn.example/second/manga_dex.js", secondCatalog,
        )
        val catalog = FakeSourceCatalogRepository(SourceCatalogResult.Success(listOf(otherCatalogCopy)))
        val viewModel = SourcesViewModel(repository, catalog)
        // Load the second catalog to establish it as the active directory.
        viewModel.onAction(SourcesAction.CatalogLocationChanged(secondCatalog))
        viewModel.onAction(SourcesAction.RefreshCatalog)
        advanceUntilIdle()

        assertNull(viewModel.state.value.catalogUpdateFor(installed))
        assertEquals(0, viewModel.state.value.catalogUpdateCount)
        assertTrue(viewModel.state.value.isFromDifferentCatalog(otherCatalogCopy))

        repository.outcome = InstallOutcome.Success(source("manga_dex", name = "MangaDex", version = "3.0.0"))
        viewModel.onAction(SourcesAction.InstallCatalogEntry(otherCatalogCopy))
        advanceUntilIdle()

        assertEquals(listOf(secondCatalog), repository.catalogInstallLocations.map { it.second })
        assertEquals(secondCatalog, viewModel.state.value.sources.single().catalogLocation)
        assertEquals("MangaDex 已切换到此目录。", viewModel.state.value.message)
    }

    @Test
    fun `successfully applied catalog is restored and loaded after recreation`() = runTest(dispatcher) {
        val preferences = FakePreferences()
        val catalog = FakeSourceCatalogRepository(SourceCatalogResult.Success(emptyList()))
        val first = SourcesViewModel(repository, catalog, preferences)
        advanceUntilIdle()
        val customUrl = "https://example.com/sources/index.json"
        first.onAction(SourcesAction.CatalogLocationChanged(customUrl))
        first.onAction(SourcesAction.RefreshCatalog)
        advanceUntilIdle()

        val restored = SourcesViewModel(repository, catalog, preferences)
        advanceUntilIdle()
        assertEquals(customUrl, restored.state.value.catalogLocation)
        assertEquals(customUrl, catalog.locations.last())
    }

    @Test
    fun `successful catalog locations are kept in most recently used history`() = runTest(dispatcher) {
        val preferences = FakePreferences()
        val catalog = FakeSourceCatalogRepository(SourceCatalogResult.Success(emptyList()))
        val model = SourcesViewModel(repository, catalog, preferences)
        advanceUntilIdle()
        val firstUrl = "https://example.com/first/index.json"
        val secondUrl = "https://example.com/second/index.json"

        model.onAction(SourcesAction.CatalogLocationChanged(firstUrl))
        model.onAction(SourcesAction.RefreshCatalog)
        advanceUntilIdle()
        model.onAction(SourcesAction.CatalogLocationChanged(secondUrl))
        model.onAction(SourcesAction.RefreshCatalog)
        advanceUntilIdle()

        assertEquals(listOf(secondUrl, firstUrl), model.state.value.catalogHistory.take(2))
        assertTrue(preferences.get("sources.catalog.history").orEmpty().contains(secondUrl))

        val restored = SourcesViewModel(repository, catalog, preferences)
        advanceUntilIdle()
        assertEquals(model.state.value.catalogHistory, restored.state.value.catalogHistory)
    }

    @Test
    fun `selecting a catalog history item immediately loads it and moves it to the top`() = runTest(dispatcher) {
        val preferences = FakePreferences()
        val catalog = FakeSourceCatalogRepository(SourceCatalogResult.Success(emptyList()))
        val model = SourcesViewModel(repository, catalog, preferences)
        advanceUntilIdle()
        val firstUrl = "https://example.com/first/index.json"
        val secondUrl = "https://example.com/second/index.json"
        model.onAction(SourcesAction.CatalogLocationChanged(firstUrl))
        model.onAction(SourcesAction.RefreshCatalog)
        advanceUntilIdle()
        model.onAction(SourcesAction.CatalogLocationChanged(secondUrl))
        model.onAction(SourcesAction.RefreshCatalog)
        advanceUntilIdle()

        model.onAction(SourcesAction.SelectCatalogLocation(firstUrl))
        advanceUntilIdle()

        assertEquals(firstUrl, catalog.locations.last())
        assertEquals(firstUrl, model.state.value.catalogHistory.first())
        assertEquals(CatalogStatus.Ready, model.state.value.catalogStatus)
    }

    @Test
    fun `failed catalog change does not replace last successful address`() = runTest(dispatcher) {
        val preferences = FakePreferences()
        val catalog = FakeSourceCatalogRepository(SourceCatalogResult.Success(emptyList()))
        val first = SourcesViewModel(repository, catalog, preferences)
        advanceUntilIdle()
        val customUrl = "https://example.com/good/index.json"
        first.onAction(SourcesAction.CatalogLocationChanged(customUrl))
        first.onAction(SourcesAction.RefreshCatalog)
        advanceUntilIdle()
        catalog.result = SourceCatalogResult.Failure(dev.veneranative.data.source.SourceCatalogFailure.Network)
        first.onAction(SourcesAction.CatalogLocationChanged("https://example.com/bad/index.json"))
        first.onAction(SourcesAction.RefreshCatalog)
        advanceUntilIdle()

        val restored = SourcesViewModel(repository, catalog, preferences)
        advanceUntilIdle()
        assertEquals(customUrl, restored.state.value.catalogLocation)
        assertTrue(restored.state.value.catalogHistory.none { it.contains("bad/index.json") })
    }

    @Test
    fun `late preferences do not overwrite an address the user is editing`() = runTest(dispatcher) {
        val preferences = FakePreferences()
        preferences.put("sources.catalog.location", "https://example.com/saved.json")
        val catalog = FakeSourceCatalogRepository(SourceCatalogResult.Success(emptyList()))
        val model = SourcesViewModel(repository, catalog)
        advanceUntilIdle()
        model.onAction(SourcesAction.CatalogLocationChanged("https://example.com/editing.json"))
        model.attachScreenPreferences(preferences)
        advanceUntilIdle()
        assertEquals("https://example.com/editing.json", model.state.value.catalogLocation)
    }

    @Test
    fun `catalog applied before preferences are ready is saved when they attach`() = runTest(dispatcher) {
        val preferences = FakePreferences()
        val catalog = FakeSourceCatalogRepository(SourceCatalogResult.Success(emptyList()))
        val model = SourcesViewModel(repository, catalog)
        advanceUntilIdle()
        val customUrl = "https://example.com/new.json"
        model.onAction(SourcesAction.CatalogLocationChanged(customUrl))
        model.onAction(SourcesAction.RefreshCatalog)
        advanceUntilIdle()
        model.attachScreenPreferences(preferences)
        advanceUntilIdle()
        val restored = SourcesViewModel(repository, catalog, preferences)
        advanceUntilIdle()
        assertEquals(customUrl, restored.state.value.catalogLocation)
    }

    private class FakePreferences : ScreenPreferenceRepository {
        private val values = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = values[key]
        override suspend fun put(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    @Test
    fun `reorder applies to state before the disk write completes`() = runTest(dispatcher) {
        repository.sources = listOf(source("a"), source("b"), source("c"))
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(
            SourcesAction.ReorderSources(listOf(SourceId("b"), SourceId("a"), SourceId("c"))),
        )

        // Optimistic: the order is visible immediately, without waiting for the write.
        assertEquals(
            listOf("b", "a", "c"),
            viewModel.state.value.sources.map { it.sourceId.value },
        )
        advanceUntilIdle()
        assertEquals(
            listOf(SourceId("b"), SourceId("a"), SourceId("c")),
            repository.reorderRequests.single(),
        )
    }

    @Test
    fun `a failed reorder restores the previous order`() = runTest(dispatcher) {
        repository.sources = listOf(source("a"), source("b"))
        val viewModel = SourcesViewModel(repository)
        advanceUntilIdle()
        repository.reorderResult = false

        viewModel.onAction(SourcesAction.ReorderSources(listOf(SourceId("b"), SourceId("a"))))
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), viewModel.state.value.sources.map { it.sourceId.value })
    }

    private fun source(
        id: String,
        name: String = "Source $id",
        enabled: Boolean = true,
        version: String = "1.0.0",
    ) = InstalledSource(
        sourceId = SourceId(id),
        name = name,
        version = version,
        enabled = enabled,
        origin = "/tmp/$id.js",
    )

    private class FakeSourceRepository : SourceRepository {
        var sources: List<InstalledSource> = emptyList()
        var reorderResult = true
        val reorderRequests = mutableListOf<List<SourceId>>()
        var outcome: InstallOutcome = InstallOutcome.Failure(SourceInstallError.LocationUnreadable)
        var listFailure: Throwable? = null
        var loginOutcome: SourceLoginOutcome = SourceLoginOutcome.Unsupported
        var loginCheckOutcome: SourceLoginCheckOutcome = SourceLoginCheckOutcome.Unsupported
        var configurationResult: dev.veneranative.core.model.InstalledSourceConfiguration? = null
        var loginCheckGate: CompletableDeferred<SourceLoginCheckOutcome>? = null
        var loginCheckCalls = 0
        val installLocations = mutableListOf<String>()
        val catalogInstallLocations = mutableListOf<Pair<String, String>>()
        val enabledChanges = mutableListOf<Pair<SourceId, Boolean>>()
        val uninstalled = mutableListOf<SourceId>()

        override suspend fun installed(): List<InstalledSource> {
            listFailure?.let { throw it }
            return sources
        }

        override suspend fun reorder(sourceIds: List<SourceId>): Boolean {
            reorderRequests += sourceIds
            return reorderResult
        }

        override suspend fun configuration(sourceId: SourceId) = configurationResult

        var settingWriteResult = true
        val writtenSettings = mutableListOf<Triple<SourceId, String, String>>()

        override suspend fun updateSetting(sourceId: SourceId, key: String, valueJson: String): Boolean {
            writtenSettings += Triple(sourceId, key, valueJson)
            return settingWriteResult
        }

        override suspend fun install(location: String): InstallOutcome {
            installLocations += location
            (outcome as? InstallOutcome.Success)?.let { installed ->
                sources = sources.filterNot { it.sourceId == installed.installed.sourceId } + installed.installed
            }
            return outcome
        }

        override suspend fun installFromCatalog(location: String, catalogLocation: String): InstallOutcome {
            catalogInstallLocations += location to catalogLocation
            val result = install(location)
            if (result is InstallOutcome.Success) {
                val installed = result.installed.copy(catalogLocation = catalogLocation)
                sources = sources.filterNot { it.sourceId == installed.sourceId } + installed
                return InstallOutcome.Success(installed)
            }
            return result
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

        override suspend fun login(sourceId: SourceId, account: String, password: String) = loginOutcome
        override suspend fun checkLogin(sourceId: SourceId): SourceLoginCheckOutcome {
            loginCheckCalls++
            return loginCheckGate?.await() ?: loginCheckOutcome
        }
    }

    private class FakeSourceCatalogRepository(
        var result: SourceCatalogResult,
    ) : SourceCatalogRepository {
        val locations = mutableListOf<String>()
        override suspend fun load(location: String): SourceCatalogResult {
            locations += location
            return result
        }
    }
}

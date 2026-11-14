package dev.veneranative.feature.profile

import dev.veneranative.data.update.ApkInstallResult
import dev.veneranative.data.update.ApkDownloadProgress
import dev.veneranative.data.update.GitHubRelease
import dev.veneranative.data.update.GitHubReleaseAsset
import dev.veneranative.data.update.GitHubReleaseException
import dev.veneranative.data.update.SelfUpdateCheck
import dev.veneranative.data.update.SelfUpdateGateway
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun checkReportsNewReleaseAndKeepsItForConfirmation() = runTest(dispatcher) {
        val gateway = FakeGateway(check = SelfUpdateCheck.Available(RELEASE, APK, 1_006_000))
        val model = AboutViewModel(gateway, installedVersionCode = 1_005_000, workerDispatcher = dispatcher)
        model.onAction(AboutAction.CheckForUpdate)
        runCurrent()
        val state = model.state.value
        assertEquals(AboutUpdatePhase.CONFIRM, state.phase)
        assertEquals(RELEASE, state.release)
        assertEquals(APK, state.apk)
        assertEquals("1.6.0", state.newVersionName)
        assertEquals(1_006_000, state.newVersionCode)
        assertEquals(1, gateway.checks)
    }

    @Test fun checkReportsUpToDate() = runTest(dispatcher) {
        val model = AboutViewModel(FakeGateway(check = SelfUpdateCheck.UpToDate), 1_005_000, dispatcher)
        model.onAction(AboutAction.CheckForUpdate)
        runCurrent()
        assertEquals(AboutUpdatePhase.IDLE, model.state.value.phase)
        assertTrue(model.state.value.upToDate)
    }

    @Test fun checkMapsMissingReleaseAndNetworkErrorsToDistinctProblems() = runTest(dispatcher) {
        val missing = AboutViewModel(
            FakeGateway(check = GitHubReleaseException(404, "no published release")), 1_005_000, dispatcher,
        )
        missing.onAction(AboutAction.CheckForUpdate)
        runCurrent()
        assertEquals(AboutUpdateProblem.NO_RELEASE, missing.state.value.problem)

        val unreachable = AboutViewModel(
            FakeGateway(check = java.io.IOException("offline")), 1_005_000, dispatcher,
        )
        unreachable.onAction(AboutAction.CheckForUpdate)
        runCurrent()
        assertEquals(AboutUpdateProblem.NETWORK, unreachable.state.value.problem)
        assertEquals(AboutUpdatePhase.IDLE, unreachable.state.value.phase)
    }

    @Test fun checkMapsReleaseWithoutUsableApkToProblem() = runTest(dispatcher) {
        val model = AboutViewModel(FakeGateway(check = SelfUpdateCheck.NoInstallableApk), 1_005_000, dispatcher)
        model.onAction(AboutAction.CheckForUpdate)
        runCurrent()
        assertEquals(AboutUpdateProblem.NO_INSTALLABLE_APK, model.state.value.problem)
    }

    @Test fun updateWithoutInstallPermissionWaitsForTheGrantAndResumesAfterwards() = runTest(dispatcher) {
        val gateway = FakeGateway(check = SelfUpdateCheck.Available(RELEASE, APK, 1_006_000), canInstall = false)
        val model = AboutViewModel(gateway, 1_005_000, dispatcher)
        model.onAction(AboutAction.CheckForUpdate)
        runCurrent()
        model.onAction(AboutAction.StartUpdate)
        runCurrent()
        assertEquals(AboutUpdatePhase.AWAIT_PERMISSION, model.state.value.phase)
        assertEquals(0, gateway.downloads)

        model.onAction(AboutAction.OpenInstallPermission)
        assertEquals(1, gateway.permissionPagesOpened)

        // The user grants the permission and returns: the flow continues on its own.
        gateway.canInstall = true
        model.onAction(AboutAction.Resume)
        runCurrent()
        assertEquals(AboutUpdatePhase.INSTALLED, model.state.value.phase)
        assertEquals(1, gateway.downloads)
        assertEquals(1, gateway.installs)
    }

    @Test fun updateDownloadsInstallsAndReportsCompletion() = runTest(dispatcher) {
        val gateway = FakeGateway(
            check = SelfUpdateCheck.Available(RELEASE, APK, 1_006_000),
            progressBytes = listOf(1024, 2048, 3072),
        )
        val model = AboutViewModel(gateway, 1_005_000, dispatcher)
        model.onAction(AboutAction.CheckForUpdate)
        runCurrent()
        model.onAction(AboutAction.StartUpdate)
        runCurrent()
        val state = model.state.value
        assertEquals(AboutUpdatePhase.INSTALLED, state.phase)
        assertEquals(3072L, state.downloadProgress?.bytesDownloaded)
        assertEquals(listOf(1024L, 2048L, 3072L), gateway.reportedProgress)
    }

    @Test fun downloadFailureMapsToDownloadProblemAndInstallFailureToInstallProblem() = runTest(dispatcher) {
        val downloadFailure = AboutViewModel(
            FakeGateway(check = SelfUpdateCheck.Available(RELEASE, APK, 1_006_000), downloadError = java.io.IOException("reset")),
            1_005_000, dispatcher,
        )
        downloadFailure.onAction(AboutAction.CheckForUpdate)
        runCurrent()
        downloadFailure.onAction(AboutAction.StartUpdate)
        runCurrent()
        assertEquals(AboutUpdateProblem.DOWNLOAD, downloadFailure.state.value.problem)
        assertEquals(AboutUpdatePhase.IDLE, downloadFailure.state.value.phase)

        val installFailure = AboutViewModel(
            FakeGateway(
                check = SelfUpdateCheck.Available(RELEASE, APK, 1_006_000),
                installResult = ApkInstallResult.Failure(1, "aborted"),
            ),
            1_005_000, dispatcher,
        )
        installFailure.onAction(AboutAction.CheckForUpdate)
        runCurrent()
        installFailure.onAction(AboutAction.StartUpdate)
        runCurrent()
        assertEquals(AboutUpdateProblem.INSTALL, installFailure.state.value.problem)
        assertEquals(AboutUpdatePhase.IDLE, installFailure.state.value.phase)
    }

    @Test fun cancelDuringDownloadResetsAndAllowsRechecking() = runTest(dispatcher) {
        val gateway = FakeGateway(check = SelfUpdateCheck.Available(RELEASE, APK, 1_006_000))
        val model = AboutViewModel(gateway, 1_005_000, dispatcher)
        model.onAction(AboutAction.CheckForUpdate)
        runCurrent()
        model.onAction(AboutAction.StartUpdate)
        runCurrent()
        model.onAction(AboutAction.CancelUpdate)
        assertEquals(AboutUpdatePhase.IDLE, model.state.value.phase)
        assertNull(model.state.value.release)
        assertFalse(model.state.value.upToDate)

        gateway.check = SelfUpdateCheck.UpToDate
        model.onAction(AboutAction.CheckForUpdate)
        runCurrent()
        assertTrue(model.state.value.upToDate)
    }

    private companion object {
        val APK = GitHubReleaseAsset(
            name = "venera-native-v1.6.0-1006000.apk",
            downloadUrl = "https://example.com/venera-native-v1.6.0-1006000.apk",
            sizeBytes = 3072,
            contentType = "application/vnd.android.package-archive",
        )
        val RELEASE = GitHubRelease(
            tagName = "v1.6.0",
            name = "Venera Native 1.6.0",
            notes = "Self-updates.",
            publishedAt = "2026-11-14T03:00:00Z",
            assets = listOf(APK),
        )
    }

    private class FakeGateway(
        var check: Any,
        var canInstall: Boolean = true,
        var installResult: ApkInstallResult = ApkInstallResult.Success,
        var downloadError: Exception? = null,
        var progressBytes: List<Long> = emptyList(),
    ) : SelfUpdateGateway {
        var checks = 0
        var downloads = 0
        var installs = 0
        var permissionPagesOpened = 0
        val reportedProgress = mutableListOf<Long>()

        override fun canRequestInstalls(): Boolean = canInstall

        override fun openInstallPermissionSettings() { permissionPagesOpened++ }

        override suspend fun checkForUpdate(installedVersionCode: Long): SelfUpdateCheck {
            checks++
            return when (val value = check) {
                is SelfUpdateCheck -> value
                is Throwable -> throw value
                else -> error("unsupported fake check: $value")
            }
        }

        override suspend fun download(apk: GitHubReleaseAsset, onProgress: (ApkDownloadProgress) -> Unit): File {
            downloads++
            downloadError?.let { throw it }
            progressBytes.forEach { bytes ->
                onProgress(ApkDownloadProgress(bytes, 3072))
                reportedProgress.add(bytes)
            }
            return File("update.apk")
        }

        override suspend fun install(apk: File): ApkInstallResult {
            installs++
            return installResult
        }
    }
}

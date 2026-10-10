package dev.veneranative.feature.backup

import dev.veneranative.core.backup.BackupCategory
import dev.veneranative.core.backup.BackupSnapshot
import dev.veneranative.data.backup.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun repeatedSubmitUsesOneCapturedRequestAndClearsPasswordOnSuccess() = runTest(dispatcher) {
        val service = FakeBackup()
        val model = BackupViewModel(service, dispatcher)
        model.onAction(BackupAction.PasswordChanged("password"))
        model.onAction(BackupAction.CreateBackup)
        model.onAction(BackupAction.CreateBackup)
        model.onAction(BackupAction.PasswordChanged("changed"))
        runCurrent()
        assertEquals(1, service.backups)
        assertEquals("password", service.receivedPassword)
        service.result.complete(BackupSnapshot(createdAtEpochMillis = 1, categories = emptySet()))
        runCurrent()
        assertFalse(model.state.value.busy)
        assertEquals("", model.state.value.password)
        assertNotNull(model.state.value.message)
    }

    @Test fun restoreRequiresConfirmationAndFailureDoesNotShowSuccess() = runTest(dispatcher) {
        val service = FakeBackup()
        val model = BackupViewModel(service, dispatcher)
        model.onAction(BackupAction.PasswordChanged("password"))
        model.onAction(BackupAction.Restore)
        runCurrent()
        assertEquals(0, service.restores)
        model.onAction(BackupAction.RequestRestore)
        assertTrue(model.state.value.confirmRestore)
        model.onAction(BackupAction.Restore)
        runCurrent()
        assertEquals(1, service.restores)
        service.result.completeExceptionally(IllegalArgumentException("invalid ciphertext"))
        runCurrent()
        assertNull(model.state.value.message)
        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.busy)
    }

    @Test fun disconnectFromAnotherPageDisablesSubmission() = runTest(dispatcher) {
        val service = FakeBackup()
        val model = BackupViewModel(service, dispatcher)
        model.onAction(BackupAction.PasswordChanged("password"))
        runCurrent()
        service.account.value = GitHubAccountState()
        runCurrent()
        assertFalse(model.state.value.canSubmit)
        model.onAction(BackupAction.CreateBackup)
        runCurrent()
        assertEquals(0, service.backups)
    }

    private class FakeBackup : GitHubBackupService {
        override val account = MutableStateFlow(GitHubAccountState(connected = true))
        val result = CompletableDeferred<BackupSnapshot>()
        var backups = 0
        var restores = 0
        var receivedPassword = ""
        override suspend fun createBackup(categories: Set<BackupCategory>, password: CharArray): BackupSnapshot {
            backups++
            receivedPassword = password.concatToString()
            return result.await()
        }
        override suspend fun restoreBackup(password: CharArray, categories: Set<BackupCategory>, mode: RestoreMode): BackupSnapshot {
            restores++
            return result.await()
        }
        override suspend fun refreshProfile() = Unit
        override suspend fun beginAuthorization(): GitHubDeviceAuthorization = error("unused")
        override suspend fun pollAuthorization(deviceCode: String): GitHubDevicePollResult = error("unused")
        override fun disconnect() { account.value = GitHubAccountState() }
    }
}

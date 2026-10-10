package dev.veneranative.feature.profile

import dev.veneranative.data.backup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun repeatedConnectStartsOnlyOneFlowAndCancelStopsPolling() = runTest(dispatcher) {
        val service = FakeAccount()
        val model = ProfileViewModel(service, nowEpochMillis = { 0 }, workerDispatcher = dispatcher)
        model.onAction(ProfileAction.Connect)
        model.onAction(ProfileAction.Connect)
        runCurrent()
        assertEquals(1, service.starts)
        assertNotNull(model.state.value.authorization)
        model.onAction(ProfileAction.CancelAuthorization)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, service.polls)
        assertNull(model.state.value.authorization)
        assertFalse(model.state.value.busy)
    }

    @Test fun authorizationUpdatesProfileAndDisconnectRequiresExplicitAction() = runTest(dispatcher) {
        val service = FakeAccount(authorized = true)
        val model = ProfileViewModel(service, nowEpochMillis = { 0 }, workerDispatcher = dispatcher)
        model.onAction(ProfileAction.Connect)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("Reader", model.state.value.account.profile?.displayName)
        assertNull(model.state.value.authorization)
        model.onAction(ProfileAction.RequestDisconnect)
        assertTrue(model.state.value.confirmDisconnect)
        assertTrue(service.account.value.connected)
        model.onAction(ProfileAction.CancelDisconnect)
        assertTrue(service.account.value.connected)
        model.onAction(ProfileAction.Disconnect)
        runCurrent()
        assertFalse(model.state.value.account.connected)
    }

    private class FakeAccount(private val authorized: Boolean = false) : GitHubAccountService {
        override val account = MutableStateFlow(GitHubAccountState())
        var starts = 0
        var polls = 0
        override suspend fun beginAuthorization(): GitHubDeviceAuthorization {
            starts++
            return GitHubDeviceAuthorization("device", "CODE", "https://github.com/login/device", 60_000, 5)
        }
        override suspend fun pollAuthorization(deviceCode: String): GitHubDevicePollResult {
            polls++
            if (!authorized) return GitHubDevicePollResult.Pending
            account.value = GitHubAccountState(connected = true)
            return GitHubDevicePollResult.Authorized(GitHubUserToken("fake", 1, "fake", 1))
        }
        override suspend fun refreshProfile() {
            account.value = account.value.copy(profile = GitHubProfile(1, "reader", "Reader", null))
        }
        override fun disconnect() { account.value = GitHubAccountState() }
    }
}

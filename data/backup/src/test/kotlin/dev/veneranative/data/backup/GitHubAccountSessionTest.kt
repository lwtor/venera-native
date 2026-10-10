package dev.veneranative.data.backup

import org.junit.Assert.*
import org.junit.Test

class GitHubAccountSessionTest {
    private val profile = GitHubProfile(1, "reader", "Reader", null)

    @Test fun successfulReceiptSurvivesRecreationAndProfileRefresh() {
        val store = MemoryStore()
        val session = GitHubAccountSession(store, true) { 1234 }
        session.updateProfile(profile)
        session.completed(GitHubSyncOperation.Backup, profile.id)
        val restored = GitHubAccountSession(store, true)
        restored.updateProfile(profile.copy(name = "New name"))
        assertEquals(GitHubSyncRecord(1234, GitHubSyncOperation.Backup), restored.state.value.lastSync)
    }

    @Test fun switchingAccountsAndDisconnectClearThePreviousReceipt() {
        val session = GitHubAccountSession(MemoryStore(), true) { 10 }
        session.updateProfile(profile)
        session.completed(GitHubSyncOperation.Restore, profile.id)
        session.updateProfile(profile.copy(id = 2))
        assertNull(session.state.value.lastSync)
        session.disconnect()
        session.updateProfile(profile)
        session.completed(GitHubSyncOperation.Backup, profile.id)
        assertEquals(GitHubAccountState(), session.state.value)
    }

    @Test fun lateCompletionForOldIdentityDoesNotOverwriteCurrentAccount() {
        val session = GitHubAccountSession(MemoryStore(), true)
        session.updateProfile(profile.copy(id = 2))
        session.completed(GitHubSyncOperation.Backup, profile.id)
        assertNull(session.state.value.lastSync)
    }

    @Test fun nicknameFallsBackToLogin() {
        assertEquals("reader", profile.copy(name = null).displayName)
        assertEquals("reader", profile.copy(name = " ").displayName)
    }

    private class MemoryStore : GitHubAccountStore {
        var value = GitHubAccountState()
        override fun read() = value
        override fun write(state: GitHubAccountState) { value = state }
        override fun clear() { value = GitHubAccountState() }
    }
}

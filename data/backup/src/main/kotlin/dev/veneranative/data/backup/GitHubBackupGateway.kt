package dev.veneranative.data.backup

import android.content.Context
import dev.veneranative.core.backup.BackupArchiveCodec
import dev.veneranative.core.backup.BackupCategory
import dev.veneranative.core.backup.BackupSelection
import dev.veneranative.core.backup.BackupSnapshot
import okhttp3.OkHttpClient

/** App-facing workflow: OAuth, private repository provisioning, encryption and selective restore. */
class GitHubBackupGateway(
    private val api: GitHubAppApi,
    private val tokenManager: GitHubTokenManager,
    private val repository: BackupRepository,
) {
    suspend fun beginAuthorization() = api.beginDeviceAuthorization()

    suspend fun pollAuthorization(deviceCode: String) = api.pollDeviceAuthorization(deviceCode).also { result ->
        if (result is GitHubDevicePollResult.Authorized) tokenManager.save(result.token)
    }

    suspend fun createBackup(categories: Set<BackupCategory>, password: CharArray): BackupSnapshot {
        val accessToken = tokenManager.accessToken()
        val githubRepository = backupRepository(accessToken)
        val snapshot = repository.createSnapshot(BackupSelection(categories))
        val archive = BackupArchiveCodec.encode(snapshot, password)
        try {
            api.uploadSnapshot(accessToken, githubRepository, archive)
        } finally {
            archive.fill(0)
        }
        return snapshot
    }

    suspend fun restoreBackup(
        password: CharArray,
        categories: Set<BackupCategory>,
        mode: RestoreMode = RestoreMode.Merge,
    ): BackupSnapshot {
        val accessToken = tokenManager.accessToken()
        val githubRepository = backupRepository(accessToken)
        val archive = api.downloadSnapshot(accessToken, githubRepository)
        val snapshot = try {
            BackupArchiveCodec.decode(archive, password)
        } finally {
            archive.fill(0)
        }
        repository.restore(snapshot, categories.intersect(snapshot.categories), mode)
        return snapshot
    }

    val isAuthorized: Boolean get() = tokenManager.isAuthorized()

    fun disconnect() = tokenManager.disconnect()

    private suspend fun backupRepository(accessToken: String): GitHubBackupRepositoryRef {
        val owner = api.authenticatedUserLogin(accessToken)
        return api.findPrivateRepository(accessToken, owner, REPOSITORY_NAME)
            ?: api.createPrivateRepository(accessToken, REPOSITORY_NAME)
    }

    private companion object {
        const val REPOSITORY_NAME = "venera-native-backup"
    }
}

/** Creates the per-Client-ID service; a GitHub App client ID is public configuration, not a secret. */
class GitHubBackupGatewayFactory(
    context: Context,
    private val httpClient: OkHttpClient,
    private val repository: BackupRepository,
    private val clientId: String,
) {
    private val tokenStore = AndroidGitHubTokenStore(context.applicationContext)

    fun hasAuthorization(): Boolean = tokenStore.read() != null

    fun create(): GitHubBackupGateway {
        val api = GitHubAppApi(httpClient, clientId)
        return GitHubBackupGateway(api, GitHubTokenManager(api, tokenStore), repository)
    }
}

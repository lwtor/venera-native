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
    private val accountSession: GitHubAccountSession,
) : GitHubBackupService {
    override val account get() = accountSession.state
    private val accountGeneration = java.util.concurrent.atomic.AtomicLong()

    override suspend fun refreshProfile() {
        val generation = accountGeneration.get()
        if (!isAuthorized) {
            accountSession.disconnect()
            throw GitHubAuthorizationRequiredException()
        }
        val profile = try {
            api.authenticatedUserProfile(tokenManager.accessToken())
        } catch (failure: Exception) {
            if (failure is GitHubAuthorizationRequiredException ||
                (failure is GitHubApiException && failure.statusCode == 401)) {
                disconnect()
                throw GitHubAuthorizationRequiredException()
            }
            throw failure
        }
        if (generation == accountGeneration.get()) accountSession.updateProfile(profile)
    }

    override suspend fun beginAuthorization() = api.beginDeviceAuthorization()

    override suspend fun pollAuthorization(deviceCode: String) = api.pollDeviceAuthorization(deviceCode).also { result ->
        if (result is GitHubDevicePollResult.Authorized) {
            tokenManager.save(result.token)
            accountGeneration.incrementAndGet()
            accountSession.authorized()
        }
    }

    override suspend fun createBackup(categories: Set<BackupCategory>, password: CharArray): BackupSnapshot {
        refreshProfile()
        val accountId = checkNotNull(account.value.profile).id
        val accessToken = tokenManager.accessToken()
        val githubRepository = backupRepository(accessToken)
        val snapshot = repository.createSnapshot(BackupSelection(categories))
        val archive = BackupArchiveCodec.encode(snapshot, password)
        try {
            api.uploadSnapshot(accessToken, githubRepository, archive)
        } finally {
            archive.fill(0)
        }
        accountSession.completed(GitHubSyncOperation.Backup, accountId)
        return snapshot
    }

    override suspend fun restoreBackup(
        password: CharArray,
        categories: Set<BackupCategory>,
        mode: RestoreMode,
    ): BackupSnapshot {
        refreshProfile()
        val accountId = checkNotNull(account.value.profile).id
        val accessToken = tokenManager.accessToken()
        val githubRepository = backupRepository(accessToken)
        val archive = api.downloadSnapshot(accessToken, githubRepository)
        val snapshot = try {
            BackupArchiveCodec.decode(archive, password)
        } finally {
            archive.fill(0)
        }
        val selected = categories.intersect(snapshot.categories)
        require(selected.isNotEmpty()) { "No selected category exists in the backup" }
        repository.restore(snapshot, selected, mode)
        accountSession.completed(GitHubSyncOperation.Restore, accountId)
        return snapshot
    }

    val isAuthorized: Boolean get() = tokenManager.isAuthorized()

    override fun disconnect() {
        accountGeneration.incrementAndGet()
        tokenManager.disconnect()
        accountSession.disconnect()
    }

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
    private val accountStore = AndroidGitHubAccountStore(context.applicationContext.filesDir)
    private val service = run {
        val api = GitHubAppApi(httpClient, clientId)
        GitHubBackupGateway(api, GitHubTokenManager(api, tokenStore), repository,
            GitHubAccountSession(accountStore, hasAuthorization()))
    }

    fun hasAuthorization(): Boolean = tokenStore.read() != null

    fun create(): GitHubBackupGateway = service
}

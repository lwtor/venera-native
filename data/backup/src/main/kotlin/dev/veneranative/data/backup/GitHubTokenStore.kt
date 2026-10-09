package dev.veneranative.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

interface GitHubTokenStore {
    fun read(): GitHubUserToken?
    fun write(token: GitHubUserToken)
    fun clear()
}

/** Keeps OAuth access/refresh tokens encrypted at rest with a device Android Keystore key. */
class AndroidGitHubTokenStore(context: Context) : GitHubTokenStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(): GitHubUserToken? {
        val encoded = preferences.getString(TOKEN_BLOB, null) ?: return null
        return runCatching {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            require(blob.size > NONCE_BYTES)
            val nonce = blob.copyOfRange(0, NONCE_BYTES)
            val ciphertext = blob.copyOfRange(NONCE_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD)
            }
            JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8)).let { json ->
                GitHubUserToken(
                    accessToken = json.getString("accessToken"),
                    accessTokenExpiresAtEpochMillis = json.getLong("accessTokenExpiresAt"),
                    refreshToken = json.getString("refreshToken"),
                    refreshTokenExpiresAtEpochMillis = json.getLong("refreshTokenExpiresAt"),
                )
            }
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    override fun write(token: GitHubUserToken) {
        val plain = JSONObject()
            .put("accessToken", token.accessToken)
            .put("accessTokenExpiresAt", token.accessTokenExpiresAtEpochMillis)
            .put("refreshToken", token.refreshToken)
            .put("refreshTokenExpiresAt", token.refreshTokenExpiresAtEpochMillis)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
            updateAAD(AAD)
        }
        val nonce = cipher.iv
        val encrypted = cipher.doFinal(plain)
        val blob = ByteArray(nonce.size + encrypted.size)
        nonce.copyInto(blob)
        encrypted.copyInto(blob, nonce.size)
        check(preferences.edit().putString(TOKEN_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
            "Could not save GitHub authorization"
        }
    }

    @Synchronized
    override fun clear() {
        preferences.edit().remove(TOKEN_BLOB).commit()
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "github_backup_auth"
        const val TOKEN_BLOB = "encrypted_user_token"
        const val KEY_ALIAS = "venera_native_github_backup_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val NONCE_BYTES = 12
        val AAD = "venera-native-github-backup-token-v1".toByteArray(Charsets.UTF_8)
    }
}

class GitHubTokenManager(
    private val api: GitHubAppApi,
    private val store: GitHubTokenStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = kotlinx.coroutines.sync.Mutex()

    fun isAuthorized(): Boolean = store.read() != null

    suspend fun accessToken(): String = mutex.withLock {
        val current = store.read() ?: throw GitHubAuthorizationRequiredException()
        if (current.accessTokenExpiresAtEpochMillis > nowEpochMillis() + TOKEN_REFRESH_SKEW_MILLIS) {
            return@withLock current.accessToken
        }
        if (current.refreshTokenExpiresAtEpochMillis <= nowEpochMillis()) {
            store.clear()
            throw GitHubAuthorizationRequiredException()
        }
        val refreshed = runCatching { api.refreshUserToken(current.refreshToken) }
            .getOrElse {
                if (it is GitHubApiException && it.statusCode in 400..499) store.clear()
                throw it
            }
        store.write(refreshed)
        refreshed.accessToken
    }

    fun save(token: GitHubUserToken) = store.write(token)
    fun disconnect() = store.clear()

    private companion object {
        const val TOKEN_REFRESH_SKEW_MILLIS = 60_000L
    }
}

class GitHubAuthorizationRequiredException : IllegalStateException("GitHub authorization is required")

private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLock(action: suspend () -> T): T {
    lock()
    return try {
        action()
    } finally {
        unlock()
    }
}

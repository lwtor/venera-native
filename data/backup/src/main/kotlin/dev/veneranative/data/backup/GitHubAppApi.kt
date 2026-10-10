package dev.veneranative.data.backup

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GitHubDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtEpochMillis: Long,
    val intervalSeconds: Long,
)

data class GitHubUserToken(
    val accessToken: String,
    val accessTokenExpiresAtEpochMillis: Long,
    val refreshToken: String,
    val refreshTokenExpiresAtEpochMillis: Long,
) {
    override fun toString(): String = "GitHubUserToken(accessToken=REDACTED, refreshToken=REDACTED)"
}

sealed interface GitHubDevicePollResult {
    data object Pending : GitHubDevicePollResult
    data class WaitLonger(val additionalSeconds: Long) : GitHubDevicePollResult
    data object Expired : GitHubDevicePollResult
    data object Denied : GitHubDevicePollResult
    data class Authorized(val token: GitHubUserToken) : GitHubDevicePollResult
}

data class GitHubBackupRepositoryRef(
    val id: Long,
    val owner: String,
    val name: String,
)

class GitHubApiException(val statusCode: Int, message: String) : IOException(message)

/** GitHub App Device Flow and single encrypted snapshot file operations. */
class GitHubAppApi(
    private val httpClient: OkHttpClient,
    private val clientId: String,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(clientId.isNotBlank()) { "GitHub App Client ID is not configured" }
    }

    suspend fun beginDeviceAuthorization(): GitHubDeviceAuthorization {
        val body = FormBody.Builder().add("client_id", clientId).build()
        val json = requestJson(
            Request.Builder().url("https://github.com/login/device/code")
                .header("Accept", "application/json").post(body).build(),
        )
        val expiresIn = json.getLong("expires_in")
        return GitHubDeviceAuthorization(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            expiresAtEpochMillis = nowEpochMillis() + expiresIn * 1_000,
            intervalSeconds = json.optLong("interval", 5).coerceAtLeast(5),
        )
    }

    suspend fun pollDeviceAuthorization(deviceCode: String): GitHubDevicePollResult {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", DEVICE_GRANT_TYPE)
            .build()
        val json = requestJson(
            Request.Builder().url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json").post(body).build(),
        )
        return when (json.optString("error")) {
            "authorization_pending" -> GitHubDevicePollResult.Pending
            "slow_down" -> GitHubDevicePollResult.WaitLonger(5)
            "expired_token", "incorrect_device_code" -> GitHubDevicePollResult.Expired
            "access_denied" -> GitHubDevicePollResult.Denied
            else -> if (json.has("access_token")) GitHubDevicePollResult.Authorized(json.toUserToken())
                else throw IOException("GitHub authorization failed: ${json.optString("error", "unknown_error")}")
        }
    }

    suspend fun refreshUserToken(refreshToken: String): GitHubUserToken {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        return requestJson(
            Request.Builder().url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json").post(body).build(),
        ).toUserToken()
    }

    suspend fun createPrivateRepository(accessToken: String, name: String): GitHubBackupRepositoryRef {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid GitHub repository name" }
        val payload = JSONObject()
            .put("name", name)
            .put("description", "Encrypted Venera Native personal backup")
            .put("private", true)
            .put("auto_init", true)
        val json = requestJson(
            apiRequest("https://api.github.com/user/repos", accessToken)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build(),
        )
        require(json.optBoolean("private")) { "GitHub did not create a private repository" }
        return GitHubBackupRepositoryRef(
            id = json.getLong("id"),
            owner = json.getJSONObject("owner").getString("login"),
            name = json.getString("name"),
        )
    }

    suspend fun authenticatedUserProfile(accessToken: String): GitHubProfile {
        val json = requestJson(apiRequest("https://api.github.com/user", accessToken).get().build())
        return GitHubProfile(
            id = json.getLong("id"),
            login = json.getString("login"),
            name = json.optString("name").takeIf { it.isNotBlank() && it != "null" },
            avatarUrl = json.optString("avatar_url").takeIf { it.startsWith("https://") },
        )
    }

    suspend fun authenticatedUserLogin(accessToken: String): String = authenticatedUserProfile(accessToken).login

    suspend fun findPrivateRepository(accessToken: String, owner: String, name: String): GitHubBackupRepositoryRef? {
        val response = request(
            apiRequest("https://api.github.com/repos/${segment(owner)}/${segment(name)}", accessToken)
                .get().build(),
        )
        return response.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw apiError(it)
            val json = it.body?.string()?.let(::JSONObject) ?: throw IOException("GitHub returned an empty response")
            if (!json.optBoolean("private")) throw IOException("The selected backup repository must be private")
            GitHubBackupRepositoryRef(json.getLong("id"), json.getJSONObject("owner").getString("login"), json.getString("name"))
        }
    }

    suspend fun uploadSnapshot(accessToken: String, repository: GitHubBackupRepositoryRef, archive: ByteArray) {
        require(archive.size <= MAX_ARCHIVE_BYTES) { "Encrypted backup exceeds 50 MB" }
        val path = "venera-backup.vnbak"
        val contentUrl = contentUrl(repository, path)
        val existing = request(
            apiRequest(contentUrl, accessToken)
                .header("Accept", "application/vnd.github.object+json").get().build(),
        )
        val currentSha = existing.use {
            when (it.code) {
                200 -> it.body?.string()?.let { json -> JSONObject(json).optString("sha").takeIf(String::isNotBlank) }
                    ?: throw IOException("GitHub did not return the backup file version")
                404 -> null
                else -> throw apiError(it)
            }
        }
        val payload = JSONObject()
            .put("message", "Update encrypted Venera Native backup")
            .put("content", Base64.getEncoder().encodeToString(archive))
        if (currentSha != null) payload.put("sha", currentSha)
        requestJson(
            apiRequest(contentUrl, accessToken)
                .put(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build(),
        )
    }

    suspend fun downloadSnapshot(accessToken: String, repository: GitHubBackupRepositoryRef): ByteArray {
        val response = request(
            apiRequest(contentUrl(repository, "venera-backup.vnbak"), accessToken)
                .header("Accept", "application/vnd.github.raw+json").get().build(),
        )
        return response.use {
            if (it.code == 404) throw GitHubApiException(404, "No backup exists in this repository")
            if (!it.isSuccessful) throw apiError(it)
            val bytes = it.body?.bytes() ?: throw IOException("GitHub returned an empty backup")
            require(bytes.size <= MAX_ARCHIVE_BYTES) { "Encrypted backup exceeds 50 MB" }
            bytes
        }
    }

    private fun apiRequest(url: String, accessToken: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer $accessToken")
        .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
        .header("User-Agent", "Venera-Native-Android")

    private suspend fun requestJson(request: Request): JSONObject {
        val response = request(request)
        return response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw apiError(it, body)
            JSONObject(body)
        }
    }

    private suspend fun request(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private fun apiError(response: Response): GitHubApiException {
        val body = response.body?.string().orEmpty()
        return apiError(response, body)
    }

    private fun apiError(response: Response, body: String): GitHubApiException {
        val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
            ?.takeIf(String::isNotBlank) ?: "GitHub request failed"
        val acceptedPermissions = response.header("X-Accepted-GitHub-Permissions")
        val endpoint = "${response.request.method} ${response.request.url.encodedPath}"
        val diagnostic = buildString {
            append("$endpoint (${response.code}): $message")
            if (!acceptedPermissions.isNullOrBlank()) append(". Required permission: $acceptedPermissions")
        }
        return GitHubApiException(response.code, diagnostic)
    }

    private fun contentUrl(repository: GitHubBackupRepositoryRef, path: String) =
        "https://api.github.com/repos/${segment(repository.owner)}/${segment(repository.name)}/contents/$path"

    private fun segment(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun JSONObject.toUserToken(): GitHubUserToken {
        val accessExpires = optLong("expires_in", DEFAULT_ACCESS_TOKEN_SECONDS)
        val refreshExpires = optLong("refresh_token_expires_in", DEFAULT_REFRESH_TOKEN_SECONDS)
        return GitHubUserToken(
            accessToken = getString("access_token"),
            accessTokenExpiresAtEpochMillis = nowEpochMillis() + accessExpires * 1_000,
            refreshToken = getString("refresh_token"),
            refreshTokenExpiresAtEpochMillis = nowEpochMillis() + refreshExpires * 1_000,
        )
    }

    companion object {
        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        private const val DEFAULT_ACCESS_TOKEN_SECONDS = 8 * 60 * 60L
        private const val DEFAULT_REFRESH_TOKEN_SECONDS = 183 * 24 * 60 * 60L
        private const val MAX_ARCHIVE_BYTES = 50 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

package dev.veneranative.data.update

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GitHubReleaseException(val statusCode: Int, message: String) : IOException(message)

data class GitHubReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val contentType: String?,
)

data class GitHubRelease(
    val tagName: String,
    val name: String?,
    val notes: String?,
    val publishedAt: String?,
    val assets: List<GitHubReleaseAsset>,
)

/** Anonymous access to the GitHub Releases API used by the in-app self-update flow. */
class GitHubReleaseApi(
    private val httpClient: OkHttpClient,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
) {
    init {
        require(apiBaseUrl.startsWith("http")) { "GitHub API base URL must be absolute" }
    }

    /** Returns the latest published (non-draft, non-prerelease) release of the repository. */
    suspend fun latestRelease(owner: String, repository: String): GitHubRelease {
        val request = Request.Builder()
            .url("$apiBaseUrl/repos/${segment(owner)}/${segment(repository)}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val response = awaitResponse(httpClient, request)
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw releaseError(it.code, body)
            return parseRelease(JSONObject(body))
        }
    }

    private fun releaseError(statusCode: Int, body: String): GitHubReleaseException {
        val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
            ?.takeIf(String::isNotBlank)
        return when {
            statusCode == 404 -> GitHubReleaseException(404, "no published release")
            message != null -> GitHubReleaseException(statusCode, message)
            else -> GitHubReleaseException(statusCode, "GitHub release request failed")
        }
    }

    companion object {
        private const val DEFAULT_API_BASE_URL = "https://api.github.com"
        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val USER_AGENT = "Venera-Native-Android"
    }
}

internal fun parseRelease(json: JSONObject): GitHubRelease {
    val tagName = json.optString("tag_name").takeIf { it.isNotBlank() }
        ?: throw IOException("GitHub release is missing tag_name")
    val assets = json.optJSONArray("assets")?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            val asset = array.optJSONObject(index) ?: return@mapNotNull null
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.isBlank() || !url.startsWith("http")) return@mapNotNull null
            GitHubReleaseAsset(
                name = name,
                downloadUrl = url,
                sizeBytes = asset.optLong("size", 0L),
                contentType = asset.optString("content_type").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }.orEmpty()
    return GitHubRelease(
        tagName = tagName,
        name = json.optString("name").takeIf { it.isNotBlank() && it != "null" },
        notes = json.optString("body").takeIf { it.isNotBlank() && it != "null" },
        publishedAt = json.optString("published_at").takeIf { it.isNotBlank() && it != "null" },
        assets = assets,
    )
}

internal fun segment(value: String) =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

/** Bridges OkHttp's callback API to coroutines; cancelling the coroutine cancels the call. */
internal suspend fun awaitResponse(httpClient: OkHttpClient, request: Request): Response =
    suspendCancellableCoroutine { continuation ->
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

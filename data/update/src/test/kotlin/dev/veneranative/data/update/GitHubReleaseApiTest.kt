package dev.veneranative.data.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseApiTest {

    @Test
    fun `parses the latest release with its assets`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(LATEST_RELEASE_JSON).build())
            val api = GitHubReleaseApi(OkHttpClient(), server.url("/").toString().removeSuffix("/"))

            val release = api.latestRelease("lwtor", "venera-native")

            assertEquals("v1.6.0", release.tagName)
            assertEquals("Venera Native 1.6.0", release.name)
            assertEquals("In-app self-updates.", release.notes?.trim())
            assertEquals("2026-11-14T03:00:00Z", release.publishedAt)
            assertEquals(2, release.assets.size)
            val apk = release.assets.first()
            assertEquals("venera-native-v1.6.0-1006000.apk", apk.name)
            assertEquals("https://example.com/venera-native-v1.6.0-1006000.apk", apk.downloadUrl)
            assertEquals(23_310_336L, apk.sizeBytes)
            assertEquals(ReleaseSelection.APK_CONTENT_TYPE, apk.contentType)
            assertEquals("application/zip", release.assets[1].contentType)
        }
    }

    @Test
    fun `maps a 404 to a no-published-release error`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(404)
                    .body("""{"message":"Not Found","documentation_url":"https://docs.github.com"}""")
                    .build(),
            )
            val api = GitHubReleaseApi(OkHttpClient(), server.url("/").toString().removeSuffix("/"))

            val failure = runCatching { api.latestRelease("lwtor", "venera-native") }.exceptionOrNull()

            assertTrue(failure is GitHubReleaseException)
            assertEquals(404, (failure as GitHubReleaseException).statusCode)
        }
    }

    @Test
    fun `maps a server error with the github message`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .body("""{"message":"rate limit exceeded"}""")
                    .build(),
            )
            val api = GitHubReleaseApi(OkHttpClient(), server.url("/").toString().removeSuffix("/"))

            val failure = runCatching { api.latestRelease("lwtor", "venera-native") }.exceptionOrNull()

            assertTrue(failure is GitHubReleaseException)
            assertEquals(503, (failure as GitHubReleaseException).statusCode)
            assertTrue(failure.message!!.contains("rate limit"))
        }
    }

    private companion object {
        val LATEST_RELEASE_JSON = """
            {
              "tag_name": "v1.6.0",
              "name": "Venera Native 1.6.0",
              "body": "In-app self-updates. ",
              "published_at": "2026-11-14T03:00:00Z",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "venera-native-v1.6.0-1006000.apk",
                  "browser_download_url": "https://example.com/venera-native-v1.6.0-1006000.apk",
                  "size": 23310336,
                  "content_type": "application/vnd.android.package-archive"
                },
                {
                  "name": "source-code.zip",
                  "browser_download_url": "https://example.com/source-code.zip",
                  "size": 1024,
                  "content_type": "application/zip"
                }
              ]
            }
        """.trimIndent()
    }
}

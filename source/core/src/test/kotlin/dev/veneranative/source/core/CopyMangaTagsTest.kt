package dev.veneranative.source.core

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceHostApi
import dev.veneranative.source.api.SourceHostRequest
import dev.veneranative.source.api.SourceHostResult
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceCall
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.engine.QuickJsRuntime
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyMangaTagsTest {
    @Test fun `tags use only fixed metadata endpoint and never load chapters or account state`() = runBlocking {
        val calls = mutableListOf<SourceHostRequest>()
        val host = object : SourceHostApi {
            override fun isMethodAllowed(method: String) = method == "http.request"
            override suspend fun invoke(request: SourceHostRequest): SourceHostResult {
                calls += request
                return SourceHostResult.Success(request.requestId, JSONObject().put("statusCode", 200).put("headers", JSONObject())
                    .put("body", """{"results":{"comic":{"theme":[{"name":"Adventure"},{"name":""},{"name":"Adventure"},{"name":"Fantasy"}]}}}""").toString())
            }
        }
        val runtime = QuickJsRuntime(hostApi = host)
        val script = """
            class Fixture extends ComicSource {
              name = 'Fixture'; key = 'copy_manga'; version = '1';
              apiUrl = 'https://fixture.invalid';
              get headers() { return {'X-Fixture': 'configured'}; }
              getReqID() { return 'fixture-id'; }
              comic = {loadInfo: async () => { throw new Error('full details must not load'); }};
            }
        """.trimIndent()
        try {
            val id = SourceId("copy_manga")
            val digest = MessageDigest.getInstance("SHA-256").digest(script.toByteArray()).joinToString("") { "%02x".format(it) }
            assertTrue(runtime.install(SourcePackage(id, "1", script, digest)) is SourceInstallResult.Installed)
            val result = EngineSourceCore(runtime).comicTags(ComicKey(id, RemoteComicId("a/b"))) as SourceOutcome.Success
            assertEquals(listOf("Adventure", "Fantasy"), result.value)
            val payload = JSONObject(calls.single().payloadJson)
            assertEquals("https://fixture.invalid/api/v3/comic2/a%2Fb?in_mainland=true&request_id=fixture-id&platform=3", payload.getString("url"))
            assertEquals("configured", payload.getJSONObject("headers").getString("X-Fixture"))
            // The operation accepts a comic ID, never an arbitrary URL/path/header request.
            val rejected = runtime.invoke(SourceCall.InvokeFunction(
                "invalid-metadata-argument", id, "__venera.copyMangaTags",
                """[{"url":"https://other.invalid","path":"/account"}]""",
            ))
            assertTrue(rejected is SourceResult.Failure)
            assertEquals(1, calls.size)
        } finally { runtime.close() }
    }
}

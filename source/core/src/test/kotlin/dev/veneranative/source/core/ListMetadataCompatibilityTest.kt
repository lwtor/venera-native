package dev.veneranative.source.core

import dev.veneranative.core.model.ComicPublicationStatus
import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.*
import dev.veneranative.source.engine.QuickJsRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ListMetadataCompatibilityTest {
    @Test fun `same search response preserves all authors and status without leaking into next call or other sources`() = runBlocking {
        for (key in listOf("copy_manga", "other")) {
            var requests = 0
            val host = object : SourceHostApi {
                override fun isMethodAllowed(method: String) = method == "http.request"
                override suspend fun invoke(request: SourceHostRequest): SourceHostResult {
                    requests++
                    val body = """{"results":{"list":[{"path_word":"fixture","name":"Fixture","author":[{"name":"A"},{"name":"B"}],"status":{"display":"已完结"},"datetime_updated":"2026-10-14"}]}}"""
                    return SourceHostResult.Success(request.requestId, org.json.JSONObject().put("statusCode", 200)
                        .put("headers", org.json.JSONObject()).put("body", body).toString())
                }
            }
            val runtime = QuickJsRuntime(hostApi = host)
            val script = """
                class Fixture extends ComicSource {
                    name = 'Fixture'; key = '$key'; version = '1';
                    search = {load: async (keyword, options, page) => {
                        if (page > 1) return {comics: [{id: 'fixture', title: 'Cached', subTitle: 'A'}], maxPage: 2};
                        const response = await Network.get('https://fixture.invalid/list', {});
                        const raw = JSON.parse(response.body).results.list[0];
                        return {comics: [{id: raw.path_word, title: raw.name, subTitle: raw.author[0].name}], maxPage: 2};
                    }};
                }
            """.trimIndent()
            try {
                val id = SourceId(key)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(script.toByteArray()).joinToString("") { "%02x".format(it) }
                assertTrue(runtime.install(SourcePackage(id, "1", script, digest)) is SourceInstallResult.Installed)
                val core = EngineSourceCore(runtime)
                val first = (core.search(SearchRequest(id, "fixture")) as SourceOutcome.Success).value.items.single()
                assertEquals(if (key == "copy_manga") listOf("A", "B") else emptyList<String>(), first.authors)
                assertEquals(if (key == "copy_manga") ComicPublicationStatus.Completed else null, first.publicationStatus)
                val next = (core.search(SearchRequest(id, "fixture", cursor = PageCursor.Page(2))) as SourceOutcome.Success).value.items.single()
                assertTrue(next.authors.isEmpty())
                assertNull(next.publicationStatus)
                assertEquals(1, requests)
            } finally { runtime.close() }
        }
    }
}

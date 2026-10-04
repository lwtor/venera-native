package dev.veneranative.source.engine

import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.SourceCall
import dev.veneranative.source.api.SourceHostApi
import dev.veneranative.source.api.SourceHostError
import dev.veneranative.source.api.SourceHostRequest
import dev.veneranative.source.api.SourceHostResult
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.api.SourceRuntimeError
import dev.veneranative.source.api.SourceScriptRuntime
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owned runtime, exercised on the JVM (no device).
 *
 * Fixtures follow the upstream source convention — `class X extends ComicSource` with members
 * reached by path — so these tests fail if the runtime drifts away from what real sources expect.
 *
 * `runBlocking` rather than `runTest` on purpose: timeout and cancellation must be measured against
 * real engine work, and the test scheduler's virtual clock would fire timeouts before the engine has
 * done anything.
 */
class QuickJsRuntimeTest {

    @Test
    fun `a source instance is created, registered and callable`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    registryCheck() {
                      return { registered: ComicSource.sources[this.key] === this };
                    }
                    """.trimIndent(),
                ),
            )

            val result = JSONObject(runtime.invokeSuccess(sourceId, "registryCheck", "[]"))

            assertTrue("the instance should be the registry entry", result.getBoolean("registered"))
        }
    }

    @Test
    fun `JM parseComic can construct and return upstream Comic values`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val script = buildString {
                appendLine("class JM extends ComicSource {")
                appendLine("  constructor() {")
                appendLine("    super();")
                appendLine("    this.name = \"禁漫天堂\";")
                appendLine("    this.key = \"jm\";")
                appendLine("    this.version = \"1.4.0\";")
                appendLine("  }")
                appendLine("  parseComic(comic) {")
                appendLine("    return new Comic({ id: String(comic.id), title: comic.name, subtitle: comic.author,")
                appendLine("      cover: `https://images.example/\${comic.id}.jpg`, tags: [comic.category.title],")
                appendLine("      description: comic.description });")
                appendLine("  }")
                appendLine("  search = { load: async () => ({ comics: [this.parseComic({")
                appendLine("    id: 42, name: \"测试作品\", author: \"测试作者\", category: { title: \"短篇\" },")
                appendLine("    description: \"来源漫画\" })], maxPage: 1 }) };")
                appendLine("}")
            }
            val sourceId = SourceId("jm")
            val install = runtime.install(
                SourcePackage(sourceId, version = "1.4.0", script = script, sha256 = sha256(script)),
            )
            assertTrue("expected install, got $install", install is SourceInstallResult.Installed)

            val result = JSONObject(runtime.invokeSuccess(sourceId, "search.load"))
            val comic = result.getJSONArray("comics").getJSONObject(0)
            assertEquals("42", comic.getString("id"))
            assertEquals("测试作品", comic.getString("title"))
            assertEquals("测试作者", comic.getString("subtitle"))
            assertEquals("https://images.example/42.jpg", comic.getString("cover"))
            assertEquals("短篇", comic.getJSONArray("tags").getString(0))
            assertEquals("来源漫画", comic.getString("description"))
            assertEquals(1, result.getInt("maxPage"))
        }
    }

    @Test
    fun `source fallback servers initialize api domains before async init`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    static fallbackServers = ["api-one.example", "api-two.example"];
                    static apiDomains;
                    firstApiDomain() { return this.constructor.apiDomains[0]; }
                    """.trimIndent(),
                ),
            )

            assertEquals("\"api-one.example\"", runtime.invokeSuccess(sourceId, "firstApiDomain"))
        }
    }

    @Test
    fun `a member is called on the object that declares it`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    counter = {
                      value: 0,
                      bump: function () {
                        this.value += 1;
                        return this.value;
                      }
                    };
                    """.trimIndent(),
                ),
            )

            // The counter lives on the declaring object, so the second call must see the first
            // call's effect. A runtime that called the member with `this = globalThis` would reset
            // it and return 1 twice.
            assertEquals("1", runtime.invokeSuccess(sourceId, "counter.bump"))
            assertEquals("2", runtime.invokeSuccess(sourceId, "counter.bump"))
        }
    }

    @Test
    fun `init runs while the source is installed`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    init() {
                      this.initialised = true;
                    }
                    wasInitialised() {
                      return { value: this.initialised === true };
                    }
                    """.trimIndent(),
                ),
            )

            val result = JSONObject(runtime.invokeSuccess(sourceId, "wasInitialised", "[]"))

            assertTrue(result.getBoolean("value"))
        }
    }

    @Test
    fun `init host calls carry an installation invocation id`() = runBlocking {
        val host = RecordingHostApi { request -> respondWithBody(request.requestId, 200, "{}") }
        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            runtime.installSource(
                fixtureSource(
                    """
                    async init() { await fetch("https://example.com/init"); }
                    known() { return true; }
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("source-install", host.requests.single().invocationId)
    }

    @Test
    fun `optional initializer network failure does not reject an install`() = runBlocking {
        val host = object : SourceHostApi {
            override fun isMethodAllowed(method: String) = method == "http.request"

            override suspend fun invoke(request: SourceHostRequest): SourceHostResult =
                SourceHostResult.Failure(
                    requestId = request.requestId,
                    error = SourceHostError(
                        SourceHostError.Code.NETWORK_CONNECTION,
                        "Network request failed.",
                        retryable = true,
                    ),
                )
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    async init() {
                      const response = await fetch("https://example.com/optional-discovery");
                      this.initialized = response.status === 0;
                    }
                    isInitialized() { return this.initialized === true; }
                    """.trimIndent(),
                ),
            )

            assertEquals("true", runtime.invokeSuccess(sourceId, "isInitialized", "[]"))
        }
    }

    @Test
    fun `setTimeout waits through the allow-listed cancellable timer host`() = runBlocking {
        val host = RecordingHostApi { request ->
            if (request.method == "timer.sleep") {
                SourceHostResult.Success(request.requestId, "{}")
            } else {
                SourceHostResult.Failure(
                    request.requestId,
                    SourceHostError(SourceHostError.Code.INVALID_REQUEST, "Unexpected method.", false),
                )
            }
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    async waitForTimer() {
                      await new Promise(resolve => setTimeout(resolve, 25));
                      return true;
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals("true", runtime.invokeSuccess(sourceId, "waitForTimer", "[]"))
            val timerRequest = host.requests.single { it.method == "timer.sleep" }
            assertTrue(timerRequest.payloadJson.contains("\"delayMillis\":25"))
            assertTrue(timerRequest.invocationId.isNotBlank())
        }
    }

    @Test
    fun `the declared settings default answers loadSetting`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    settings = { quality: { default: "high" } };
                    quality() {
                      return { value: this.loadSetting("quality") };
                    }
                    """.trimIndent(),
                ),
            )

            val result = JSONObject(runtime.invokeSuccess(sourceId, "quality", "[]"))

            assertEquals("high", result.getString("value"))
        }
    }

    @Test
    fun `JM keeps the source declared default API line`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val script = fixtureSource(
                """
                settings = { apiDomain: { default: "1" } };
                apiDomain() { return this.loadSetting("apiDomain"); }
                """.trimIndent(),
                key = "jm",
            )
            val sourceId = SourceId("jm")
            val install = runtime.install(
                SourcePackage(sourceId, version = "1", script = script, sha256 = sha256(script)),
            )
            assertTrue("expected install, got $install", install is SourceInstallResult.Installed)

            assertEquals("\"1\"", runtime.invokeSuccess(sourceId, "apiDomain", "[]"))
        }
    }

    @Test
    fun `source initialization can finish an HTTP-backed domain refresh`() = runBlocking {
        val host = RecordingHostApi { request ->
            delay(5_100)
            respondWithBody(request.requestId, 200, "domain list")
        }
        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val script = fixtureSource(
                """
                async init() { await fetch("https://domains.example/list"); }
                currentDomain() { return "ready"; }
                """.trimIndent(),
                key = "jm",
            )
            val sourceId = SourceId("jm")

            val install = runtime.install(
                SourcePackage(sourceId, version = "1", script = script, sha256 = sha256(script)),
            )

            assertTrue("expected init to complete, got $install", install is SourceInstallResult.Installed)
            assertEquals("\"ready\"", runtime.invokeSuccess(sourceId, "currentDomain"))
        }
        assertEquals(1, host.requests.size)
    }

    @Test
    fun `JM startup decrypts refreshed domains and searches through the next API line`() = runBlocking {
        val encryptedDomains = "\uFEFF" + encryptJmConfig("""{"Server":["jm-a.example","jm-b.example"]}""")
        val host = RecordingHostApi { request ->
            val payload = JSONObject(request.payloadJson)
            when {
                payload.getString("url") == "https://domains.example/newsvr-2025.txt" ->
                    respondWithBody(request.requestId, 200, encryptedDomains)
                payload.getString("url").startsWith("https://jm-a.example/") ->
                    respondWithBody(request.requestId, 404, "not found")
                payload.getString("url").startsWith("https://jm-b.example/") ->
                    respondWithBody(request.requestId, 200, "mock-search-ok")
                else -> error("Unexpected JM request: ${payload.getString("url")}")
            }
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val script = fixtureSource(
                """
                static fallbackServers = ["fallback-a.example", "fallback-b.example"];
                static apiDomains;
                settings = { refreshDomainsOnStart: { default: true }, apiDomain: { default: "1" } };
                async init() {
                  if (this.loadSetting("refreshDomainsOnStart")) await this.refreshApiDomains();
                }
                async refreshApiDomains() {
                  const response = await fetch("https://domains.example/newsvr-2025.txt");
                  if (!response.ok) return;
                  const secret = "diosfjckwpqpdfjkvnqQjsik";
                  const key = Convert.encodeUtf8(Convert.hexEncode(Convert.md5(Convert.encodeUtf8(secret))));
                  const encrypted = Convert.decodeBase64(await response.text());
                  const text = Convert.decodeUtf8(Convert.decryptAesEcb(encrypted, key));
                  const config = JSON.parse(text);
                  this.constructor.apiDomains = config.Server.slice(0, 4);
                }
                get baseUrl() {
                  const index = parseInt(this.loadSetting("apiDomain"), 10) - 1;
                  return "https://" + this.constructor.apiDomains[index];
                }
                async search(keyword, order, timestamp) {
                  const query = encodeURIComponent(keyword).replace(/%20/g, "+");
                  const url = `${'$'}{this.baseUrl}/search?search_query=${'$'}{query}&o=${'$'}{order}`;
                  const token = Convert.hexEncode(Convert.md5(Convert.encodeUtf8(`${'$'}{timestamp}18comicAPPContent`)));
                  const response = await Network.get(url, {
                    "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
                    "Accept-Encoding": "gzip, deflate, br, zstd",
                    "Referer": "https://localhost/",
                    "token": token,
                    "tokenparam": `${'$'}{timestamp},2.0.16`
                  });
                  if (response.status !== 200) throw new Error("bad status " + response.status);
                  return response.body;
                }
                """.trimIndent(),
                key = "jm",
            )
            val sourceId = SourceId("jm")
            val install = runtime.install(
                SourcePackage(sourceId, version = "1", script = script, sha256 = sha256(script)),
            )
            assertTrue("expected install, got $install", install is SourceInstallResult.Installed)

            assertEquals("\"mock-search-ok\"", runtime.invokeSuccess(sourceId, "search", "[\"海贼王\",\"mr\",1760000000]"))
        }

        assertEquals(3, host.requests.size)
        val configRequest = JSONObject(host.requests[0].payloadJson)
        assertEquals("https://domains.example/newsvr-2025.txt", configRequest.getString("url"))
        val initialSearch = JSONObject(host.requests[1].payloadJson)
        assertEquals(
            "https://jm-a.example/search?search_query=%E6%B5%B7%E8%B4%BC%E7%8E%8B&o=mr",
            initialSearch.getString("url"),
        )
        val headers = initialSearch.getJSONObject("headers")
        assertEquals("1760000000,2.0.16", headers.getString("tokenparam"))
        assertEquals("969efa5931c54a87cfe1db70e240a2e5", headers.getString("token"))
        assertTrue(headers.getString("User-Agent").contains("Android 13"))
        assertEquals(
            "https://jm-b.example/search?search_query=%E6%B5%B7%E8%B4%BC%E7%8E%8B&o=mr",
            JSONObject(host.requests[2].payloadJson).getString("url"),
        )
    }

    @Test
    fun `Komiic GraphQL search posts the configured request and parses mocked results`() = runBlocking {
        val host = RecordingHostApi { request ->
            val payload = JSONObject(request.payloadJson)
            assertEquals("POST", payload.getString("method"))
            respondWithBody(
                request.requestId,
                200,
                """{"data":{"searchComicsAndAuthors":{"comics":[{"id":"c-1","title":"舞舞舞"}]}}}""",
            )
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val script = fixtureSource(
                """
                queryJson(query) {
                  return Network.post("https://komiic.com/api/query", {
                    "Referer": "https://komiic.com/",
                    "User-Agent": "Mozilla/5.0 Chrome/120.0.0.0 Safari/537.36",
                    "Content-Type": "application/json"
                  }, query).then(response => {
                    if (response.status !== 200) throw new Error("status " + response.status);
                    return JSON.parse(response.body);
                  });
                }
                search(keyword) {
                  return this.queryJson({
                    operationName: "searchComicAndAuthorQuery",
                    variables: { keyword: keyword },
                    query: "query searchComicAndAuthorQuery(${'$'}keyword: String!) { searchComicsAndAuthors(keyword: ${'$'}keyword) { comics { id title } } }"
                  }).then(result => result.data.searchComicsAndAuthors.comics.map(comic => comic.title));
                }
                """.trimIndent(),
            )
            val sourceId = runtime.installSource(script)
            assertEquals("[\"舞舞舞\"]", runtime.invokeSuccess(sourceId, "search", "[\"舞舞舞\"]"))
        }

        assertEquals(1, host.requests.size)
        val request = JSONObject(host.requests.single().payloadJson)
        assertEquals("https://komiic.com/api/query", request.getString("url"))
        val headers = request.getJSONObject("headers")
        assertEquals("application/json", headers.getString("Content-Type"))
        assertEquals("https://komiic.com/", headers.getString("Referer"))
        val body = JSONObject(request.getString("body"))
        assertEquals("searchComicAndAuthorQuery", body.getString("operationName"))
        assertEquals("舞舞舞", body.getJSONObject("variables").getString("keyword"))
        assertTrue(body.getString("query").contains("searchComicsAndAuthors"))
    }

    @Test
    fun `JM GET retries other API lines after DNS failure and 404`() = runBlocking {
        val host = RecordingHostApi { request ->
            val url = JSONObject(request.payloadJson).getString("url")
            when {
                url.contains("api-two.example") ->
                    SourceHostResult.Failure(
                        request.requestId,
                        SourceHostError(
                            SourceHostError.Code.NETWORK_CONNECTION,
                            "DNS lookup failed.",
                            retryable = true,
                        ),
                    )
                url.contains("api-one.example") -> respondWithBody(request.requestId, 404, "not found")
                else -> respondWithBody(request.requestId, 200, "works")
            }
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val script = fixtureSource(
                """
                static fallbackServers = ["api-one.example", "api-two.example", "api-three.example"];
                static apiDomains;
                settings = { apiDomain: { default: "1" } };
                get baseUrl() {
                  return `https://${'$'}{this.constructor.apiDomains[parseInt(this.loadSetting("apiDomain")) - 1]}`;
                }
                search() {
                  return Network.get(`${'$'}{this.baseUrl}/search`).then(response => response.body);
                }
                """.trimIndent(),
                key = "jm",
            )
            val sourceId = SourceId("jm")
            val install = runtime.install(
                SourcePackage(sourceId, version = "1", script = script, sha256 = sha256(script)),
            )
            assertTrue("expected install, got $install", install is SourceInstallResult.Installed)

            val result = runtime.invokeSuccess(sourceId, "search", "[]")
            assertTrue(result.contains("works"))
        }

        assertEquals(3, host.requests.size)
    }

    @Test
    fun `a source can replace a setting value during initialization`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    settings = { apiHost: { default: "api.example" } };
                    init() { this.settings.apiHost = "api2.example"; }
                    currentApiHost() { return this.loadSetting("apiHost"); }
                    """.trimIndent(),
                ),
            )

            assertEquals("\"api2.example\"", runtime.invokeSuccess(sourceId, "currentApiHost", "[]"))
        }
    }

    @Test
    fun `a source awaits fetch and reads a json body`() = runBlocking {
        val recordedUrls = CopyOnWriteArrayList<String>()
        val host = RecordingHostApi { request ->
            val payload = JSONObject(request.payloadJson)
            recordedUrls += payload.getString("url")
            respondWithBody(request.requestId, 200, """{"items":[7,8]}""")
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    search = {
                      load: async function (keyword) {
                        const response = await fetch(
                          "https://example.com/search?q=" + encodeURIComponent(keyword)
                        );
                        const data = await response.json();
                        return { ok: response.ok, status: response.status, count: data.items.length };
                      }
                    };
                    """.trimIndent(),
                ),
            )

            val result = runtime.invokeSuccess(sourceId, "search.load", """["cats and dogs"]""")
            val decoded = JSONObject(result)

            assertTrue("expected ok, got $result", decoded.getBoolean("ok"))
            assertEquals(200, decoded.getInt("status"))
            assertEquals(2, decoded.getInt("count"))
            assertEquals(1, recordedUrls.size)
            assertTrue(
                "keyword should reach the host: ${recordedUrls.single()}",
                recordedUrls.single().contains("cats%20and%20dogs"),
            )
        }
    }

    @Test
    fun `Network post serializes object bodies as json`() = runBlocking {
        val requestBodies = CopyOnWriteArrayList<String?>()
        val host = RecordingHostApi { request ->
            requestBodies += JSONObject(request.payloadJson).getString("body")
            respondWithBody(request.requestId, 200, "{}")
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    sendGraphQl() {
                      return Network.post(
                        "https://example.com/graphql",
                        { "Content-Type": "application/json" },
                        { operationName: "search", variables: { keyword: "漫画" } }
                      ).then(response => response.status);
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals("200", runtime.invokeSuccess(sourceId, "sendGraphQl"))
        }

        assertEquals(
            """{"operationName":"search","variables":{"keyword":"漫画"}}""",
            requestBodies.single(),
        )
    }

    @Test
    fun `Convert exposes md5 and AES ECB decryption used by source scripts`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    cryptoVectors() {
                      const key = new Uint8Array([
                        0x60,0x3d,0xeb,0x10,0x15,0xca,0x71,0xbe,0x2b,0x73,0xae,0xf0,0x85,0x7d,0x77,0x81,
                        0x1f,0x35,0x2c,0x07,0x3b,0x61,0x08,0xd7,0x2d,0x98,0x10,0xa3,0x09,0x14,0xdf,0xf4
                      ]);
                      const ciphertext = new Uint8Array([
                        0xf3,0xee,0xd1,0xbd,0xb5,0xd2,0xa0,0x3c,0x06,0x4b,0x5a,0x7e,0x3d,0xb1,0x81,0xf8
                      ]);
                      return {
                        md5: Convert.hexEncode(Convert.md5(Convert.encodeUtf8("abc"))),
                        aes: Convert.hexEncode(Convert.decryptAesEcb(ciphertext, key))
                      };
                    }
                    """.trimIndent(),
                ),
            )

            val result = JSONObject(runtime.invokeSuccess(sourceId, "cryptoVectors"))
            assertEquals("900150983cd24fb0d6963f7d28e17f72", result.getString("md5"))
            assertEquals("6bc1bee22e409f96e93d7e117393172a", result.getString("aes"))
        }
    }

    @Test
    fun `source result serialization preserves nested Maps as ordered objects`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    comic = {
                      loadInfo() {
                        return {
                          title: "T",
                          chapters: new Map([
                            ["Volume 2", new Map([["ch2", "Chapter 2"], ["ch1", "Chapter 1"]])],
                            ["Volume 1", new Map([["ch0", "Chapter 0"]])]
                          ])
                        };
                      }
                    };
                    """.trimIndent(),
                ),
            )

            val result = JSONObject(runtime.invokeSuccess(sourceId, "comic.loadInfo", "[\"comic-id\"]"))
            val chapters = result.getJSONObject("chapters")
            assertEquals(listOf("Volume 2", "Volume 1"), chapters.keys().asSequence().toList())
            assertEquals(listOf("ch2", "ch1"), chapters.getJSONObject("Volume 2").keys().asSequence().toList())
            assertEquals("Chapter 0", chapters.getJSONObject("Volume 1").getString("ch0"))
        }
    }

    @Test
    fun `copy manga signing helpers match the upstream sha256 hmac contract`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    sign() {
                      var key = Convert.decodeBase64("a2V5");
                      var message = Convert.encodeUtf8("The quick brown fox jumps over the lazy dog");
                      return {
                        signature: Convert.hmacString(key, message, "sha256"),
                        roundTrip: Convert.decodeUtf8(Convert.decodeBase64(Convert.encodeBase64(message))),
                        random: randomInt(4, 4)
                      };
                    }
                    """.trimIndent(),
                ),
            )

            val result = JSONObject(runtime.invokeSuccess(sourceId, "sign", "[]"))
            assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", result.getString("signature"))
            assertEquals("The quick brown fox jumps over the lazy dog", result.getString("roundTrip"))
            assertEquals(4, result.getInt("random"))
        }
    }

    @Test
    fun `a binary request body survives as its own text`() = runBlocking {
        val sentBodies = CopyOnWriteArrayList<String>()
        val host = RecordingHostApi { request ->
            val payload = JSONObject(request.payloadJson)
            sentBodies += payload.get("body").toString()
            respondWithBody(request.requestId, 201, "created")
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    comic = {
                      loadEp: async function (id, ep) {
                        const response = await fetch("https://example.com/comment", {
                          method: "POST",
                          headers: { "Content-Type": "text/plain; charset=utf-8" },
                          body: Convert.encodeUtf8(id + "=" + ep)
                        });
                        return { status: response.status };
                      }
                    };
                    """.trimIndent(),
                ),
            )

            val result = runtime.invokeSuccess(sourceId, "comic.loadEp", """["标题","中文 ✓"]""")

            assertEquals(201, JSONObject(result).getInt("status"))
            assertEquals(listOf("标题=中文 ✓"), sentBodies)
        }
    }

    @Test
    fun `console output is redacted before it reaches the host log`() = runBlocking {
        val logs = CopyOnWriteArrayList<Pair<String, String>>()
        val runtime = QuickJsRuntime(logSink = { level, message -> logs += level to message })

        withRuntime(runtime) { active ->
            val sourceId = active.installSource(
                fixtureSource(
                    """
                    shout() {
                      console.warn("Authorization: secret-token", { password: "hidden" });
                      return "done";
                    }
                    """.trimIndent(),
                ),
            )
            active.invokeSuccess(sourceId, "shout", "[]")
        }

        assertEquals(1, logs.size)
        assertEquals("warn", logs.single().first)
        assertEquals(QuickJsHostBridge.REDACTED_LOG_MESSAGE, logs.single().second)
    }

    @Test
    fun `the app locale is exposed to sources`() = runBlocking {
        withRuntime(QuickJsRuntime(appLocale = "zh_CN")) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource("""locale() { return APP.locale; }"""),
            )

            assertEquals("\"zh_CN\"", runtime.invokeSuccess(sourceId, "locale", "[]"))
        }
    }

    @Test
    fun `source compatibility version enables grouped chapters without claiming later APIs`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """chapterShape() { return this.isAppVersionAfter("1.3.0") ? "grouped" : "flat"; }""",
                ),
            )

            assertEquals("\"grouped\"", runtime.invokeSuccess(sourceId, "chapterShape", "[]"))
        }
        withRuntime(QuickJsRuntime(appVersion = "1.3.0")) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """chapterShape() { return this.isAppVersionAfter("1.3.0") ? "grouped" : "flat"; }""",
                ),
            )

            assertEquals("\"flat\"", runtime.invokeSuccess(sourceId, "chapterShape", "[]"))
        }
    }

    @Test
    fun `an unknown member fails without killing the source`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(fixtureSource("""known() { return 1; }"""))

            val result = runtime.invoke(SourceCall.InvokeFunction("unknown-1", sourceId, "search.load"))

            val error = (result as SourceResult.Failure).error
            assertTrue("expected ScriptExecution, got $error", error is SourceRuntimeError.ScriptExecution)
            assertTrue(error.message.contains("Unknown source member"))
        }
    }

    @Test
    fun `a source failure becomes a script execution error`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource("""fail() { throw new Error("boom"); }"""),
            )
            val result = runtime.invoke(SourceCall.InvokeFunction("fail-1", sourceId, "fail"))

            val error = (result as SourceResult.Failure).error
            assertTrue("expected ScriptExecution, got $error", error is SourceRuntimeError.ScriptExecution)
            assertTrue(error.message.contains("boom"))
        }
    }

    @Test
    fun `an uncaught source error resets mutated singleton state before the next call`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    failAfterMutation() {
                      this.counter = 99;
                      throw new Error("temporary detail failure");
                    }
                    readCounter() { return this.counter || 0; }
                    """.trimIndent(),
                ),
            )

            val failed = runtime.invoke(SourceCall.InvokeFunction("failure-1", sourceId, "failAfterMutation"))
            assertTrue("expected source failure, got $failed", failed is SourceResult.Failure)

            assertEquals("0", runtime.invokeSuccess(sourceId, "readCounter"))
        }
    }

    @Test
    fun `a host method outside the allow list is rejected`() = runBlocking {
        val host = RecordingHostApi { request -> respondWithBody(request.requestId, 200, "{}") }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """read() { return veneraHost.call("files.read", { path: "/etc" }); }""",
                ),
            )
            val result = runtime.invoke(SourceCall.InvokeFunction("read-1", sourceId, "read"))

            val error = (result as SourceResult.Failure).error
            assertTrue("expected ScriptExecution, got $error", error is SourceRuntimeError.ScriptExecution)
            assertTrue(error.message.contains("not allowed"))
            assertEquals(0, host.requests.size)
        }
    }

    @Test(timeout = ENGINE_TEST_TIMEOUT_MILLIS)
    fun `a non-terminating call times out`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    spin() { while (true) { } }
                    add(left, right) { return { sum: left + right }; }
                    """.trimIndent(),
                ),
            )

            val startedAt = System.nanoTime()
            val result =
                runtime.invoke(
                    SourceCall.InvokeFunction(
                        callId = "spin-1",
                        sourceId = sourceId,
                        functionName = "spin",
                        timeoutMillis = 2_000,
                    ),
                )
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L

            val error = (result as SourceResult.Failure).error
            assertTrue("expected Timeout, got $error", error is SourceRuntimeError.Timeout)
            assertTrue(
                "interrupting a busy JavaScript evaluation should finish promptly (took ${elapsedMillis}ms)",
                elapsedMillis < 2_800,
            )
            assertSourceUsableAfterInterrupt(runtime, sourceId)
        }
    }

    @Test(timeout = ENGINE_TEST_TIMEOUT_MILLIS)
    fun `cancelling an in-engine call interrupts evaluation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val host = RecordingHostApi { request ->
            entered.complete(Unit)
            respondWithBody(request.requestId, 200, "{}")
        }
        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(
                fixtureSource(
                    """
                    spin = {
                      load: async function () {
                        await fetch("https://example.com/before-spin");
                        while (true) { }
                      }
                    };
                    add(left, right) { return { sum: left + right }; }
                    """.trimIndent(),
                ),
            )
            val pending = async {
                runtime.invoke(
                    SourceCall.InvokeFunction(
                        callId = "spin-cancel",
                        sourceId = sourceId,
                        functionName = "spin.load",
                        timeoutMillis = 30_000,
                    ),
                )
            }

            entered.await()
            // Let the resolved host call return into JavaScript before cancelling the CPU-bound loop.
            delay(100)
            val cancellationStartedAt = System.nanoTime()
            runtime.cancel("spin-cancel")
            val result = withTimeoutOrNull(FOLLOW_UP_WAIT_MILLIS) { pending.await() }
            val cancellationElapsedMillis =
                (System.nanoTime() - cancellationStartedAt) / 1_000_000L

            assertTrue("the in-engine cancellation should return promptly", result != null)
            assertTrue(
                "in-engine cancellation should not exhaust the interrupt grace period " +
                    "(took ${cancellationElapsedMillis}ms)",
                cancellationElapsedMillis < 800,
            )
            val error = (result as SourceResult.Failure).error
            assertTrue("expected Cancelled, got $error", error is SourceRuntimeError.Cancelled)
            assertSourceUsableAfterInterrupt(runtime, sourceId)
        }
    }

    @Test(timeout = ENGINE_TEST_TIMEOUT_MILLIS)
    fun `cancelling a call reports Cancelled`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val hostCancelled = CompletableDeferred<Unit>()
        val host = RecordingHostApi { _ ->
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                hostCancelled.complete(Unit)
            }
        }

        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(fetchSource())
            val pending =
                async {
                    runtime.invoke(
                        SourceCall.InvokeFunction(
                            callId = "slow-1",
                            sourceId = sourceId,
                            functionName = "search.load",
                            argumentsJson = """["slow"]""",
                            timeoutMillis = 30_000,
                        ),
                    )
                }

            entered.await()
            runtime.cancel("slow-1")
            val result = pending.await()

            val error = (result as SourceResult.Failure).error
            assertTrue("expected Cancelled, got $error", error is SourceRuntimeError.Cancelled)
            // SourceHostApi requires a cancelled invocation to cancel its child operations.
            assertTrue(
                "the host request should be cancelled with the call",
                withTimeoutOrNull(HOST_CANCELLATION_WAIT_MILLIS) { hostCancelled.await() } != null,
            )
        }
    }

    @Test(timeout = ENGINE_TEST_TIMEOUT_MILLIS)
    fun `a second call waits for the same source and runs after the first is cancelled`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val host = RecordingHostApi { request ->
            if (request.invocationId == "first") {
                entered.complete(Unit)
                awaitCancellation()
            } else {
                respondWithBody(request.requestId, 200, "{}")
            }
        }
        withRuntime(QuickJsRuntime(hostApi = host)) { runtime ->
            val sourceId = runtime.installSource(fetchSource())
            val first = async {
                runtime.invoke(SourceCall.InvokeFunction("first", sourceId, "search.load", "[\"slow\"]", 30_000))
            }
            entered.await()
            val second = async {
                runtime.invoke(SourceCall.InvokeFunction("second", sourceId, "search.load", "[\"next\"]"))
            }
            // The queued invocation must remain pending until the previous source call releases
            // the single-threaded source session.
            kotlinx.coroutines.yield()
            assertTrue("the second call must wait instead of failing Busy", !second.isCompleted)
            runtime.cancel("first")
            assertTrue(first.await() is SourceResult.Failure)
            assertTrue("the queued call should run after cancellation", second.await() is SourceResult.Success)
        }
    }

    @Test
    fun `an unloaded source is no longer callable`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val sourceId = runtime.installSource(fixtureSource("""known() { return 1; }"""))
            runtime.unload(sourceId)

            val result = runtime.invoke(SourceCall.InvokeFunction("after-1", sourceId, "known"))

            val error = (result as SourceResult.Failure).error
            assertTrue("expected SourceNotLoaded, got $error", error is SourceRuntimeError.SourceNotLoaded)
        }
    }

    @Test
    fun `a package whose hash does not match is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val script = fixtureSource("""known() { return 1; }""")
            val result =
                runtime.install(
                    SourcePackage(
                        sourceId = SourceId("fixture"),
                        version = "1",
                        script = script,
                        sha256 = sha256("$script "),
                    ),
                )

            val failure = result as SourceInstallResult.Failed
            assertTrue(failure.error is SourceRuntimeError.InvalidPackage)
        }
    }

    @Test
    fun `a script that declares another key is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val script = fixtureSource("""known() { return 1; }""", key = "something_else")
            val result =
                runtime.install(
                    SourcePackage(
                        sourceId = SourceId("fixture"),
                        version = "1",
                        script = script,
                        sha256 = sha256(script),
                    ),
                )

            val failure = result as SourceInstallResult.Failed
            assertTrue("expected InvalidPackage, got ${failure.error}", failure.error is SourceRuntimeError.InvalidPackage)
            assertTrue(failure.error.message.contains("something_else"))
        }
    }

    @Test
    fun `a script that does not follow the class convention is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val result = runtime.installSourceResult("""function add(left, right) { return left + right; }""")

            val failure = result as SourceInstallResult.Failed
            assertTrue(
                "expected a package error, got ${failure.error}",
                failure.error is SourceRuntimeError.InvalidPackage,
            )
        }
    }

    @Test
    fun `an unusable key is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val result =
                runtime.installSourceResult(
                    fixtureSource("""known() { return 1; }""", key = "not-a-key"),
                )

            val failure = result as SourceInstallResult.Failed
            assertTrue("expected a package error, got ${failure.error}", failure.error is SourceRuntimeError.InvalidPackage)
        }
    }

    @Test
    fun `a script that throws while loading is rejected`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val script =
                """
                class BrokenSource extends ComicSource {
                  constructor() {
                    super();
                    this.key = "fixture";
                    this.name = "Broken";
                    this.version = "1";
                    throw new Error("no init");
                  }
                }
                """.trimIndent()
            val result = runtime.installSourceResult(script)

            val failure = result as SourceInstallResult.Failed
            assertTrue(
                "expected a package error, got ${failure.error}",
                failure.error is SourceRuntimeError.InvalidPackage,
            )
        }
    }

    @Test
    fun `a script with a syntax error is reported as such`() = runBlocking {
        withRuntime(QuickJsRuntime()) { runtime ->
            val result =
                runtime.installSourceResult(
                    """
                    class BrokenSource extends ComicSource {
                      constructor() {
                        super();
                        this.key = "fixture";
                    }
                    """.trimIndent(),
                )

            val failure = result as SourceInstallResult.Failed
            assertTrue(
                "expected ScriptSyntax, got ${failure.error}",
                failure.error is SourceRuntimeError.ScriptSyntax,
            )
        }
    }

    private suspend fun withRuntime(runtime: QuickJsRuntime, block: suspend (QuickJsRuntime) -> Unit) {
        try {
            block(runtime)
        } finally {
            runtime.close()
        }
    }

    private suspend fun SourceScriptRuntime.installSource(script: String): SourceId {
        val result = installSourceResult(script)
        assertTrue("expected install, got $result", result is SourceInstallResult.Installed)
        return (result as SourceInstallResult.Installed).sourceId
    }

    private suspend fun SourceScriptRuntime.installSourceResult(
        script: String,
    ): SourceInstallResult =
        install(
            SourcePackage(
                sourceId = SourceId(FIXTURE_KEY),
                version = "1",
                script = script,
                sha256 = sha256(script),
            ),
        )

    private suspend fun SourceScriptRuntime.invokeSuccess(
        sourceId: SourceId,
        member: String,
        argumentsJson: String = "[]",
    ): String {
        val result =
            invoke(SourceCall.InvokeFunction(member + "-call", sourceId, member, argumentsJson))
        assertTrue("expected success, got $result", result is SourceResult.Success)
        return (result as SourceResult.Success).json
    }

    /** A timed-out source stays installed and can answer again after its interrupted engine rebuilds. */
    private suspend fun assertSourceUsableAfterInterrupt(runtime: QuickJsRuntime, sourceId: SourceId) {
        val followUp =
            withTimeoutOrNull(FOLLOW_UP_WAIT_MILLIS) {
                runCatching {
                    runtime.invokeSuccess(sourceId, "add", "[1,2]")
                }
            }
        assertTrue("the source should answer again after a timeout", followUp?.isSuccess == true)
    }

    private class RecordingHostApi(
        private val respond: suspend (SourceHostRequest) -> SourceHostResult,
    ) : SourceHostApi {
        val requests = CopyOnWriteArrayList<SourceHostRequest>()

        override fun isMethodAllowed(method: String): Boolean = method in setOf("http.request", "timer.sleep", "timer.cancel")

        override suspend fun invoke(request: SourceHostRequest): SourceHostResult {
            requests += request
            return respond(request)
        }
    }

    private companion object {
        const val ENGINE_TEST_TIMEOUT_MILLIS = 120_000L
        const val FOLLOW_UP_WAIT_MILLIS = 15_000L
        const val HOST_CANCELLATION_WAIT_MILLIS = 10_000L
        const val FIXTURE_KEY = "fixture"

        /**
         * A source script shaped the way upstream sources are, with [body] as extra members.
         *
         * Built line by line rather than interpolated into one indented raw string: the body brings
         * its own indentation, which would make `trimIndent` a no-op and leave the class declaration
         * indented — and an indented declaration is rejected by the upstream convention.
         */
        fun fixtureSource(body: String, key: String = FIXTURE_KEY): String =
            buildString {
                appendLine("class FixtureSource extends ComicSource {")
                appendLine("  constructor() {")
                appendLine("    super();")
                appendLine("    this.name = \"Fixture\";")
                appendLine("    this.key = \"$key\";")
                appendLine("    this.version = \"1\";")
                appendLine("  }")
                appendLine()
                appendLine(body)
                appendLine("}")
            }

        fun fetchSource(): String = fixtureSource(
            """
            search = {
              load: async function (keyword) {
                const response = await fetch("https://example.com/search?q=" + keyword);
                return { status: response.status };
              }
            };
            """.trimIndent(),
        )

        fun respondWithBody(requestId: String, statusCode: Int, body: String): SourceHostResult =
            SourceHostResult.Success(
                requestId = requestId,
                resultJson =
                    JSONObject()
                        .put("statusCode", statusCode)
                        .put(
                            "headers",
                            JSONObject().put("Content-Type", JSONArray().put("application/json")),
                        )
                        .put("body", body)
                        .toString(),
            )

        fun encryptJmConfig(clearText: String): String {
            val secret = "diosfjckwpqpdfjkvnqQjsik"
            val digest = MessageDigest.getInstance("MD5").digest(secret.toByteArray(Charsets.UTF_8))
            val hexKey = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hexKey.toByteArray(Charsets.UTF_8), "AES"))
            return Base64.getEncoder().encodeToString(cipher.doFinal(clearText.toByteArray(Charsets.UTF_8)))
        }

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

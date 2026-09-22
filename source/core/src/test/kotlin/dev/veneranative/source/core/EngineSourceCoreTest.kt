package dev.veneranative.source.core

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.ExploreKind
import dev.veneranative.core.model.FilterSelection
import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceFilter
import dev.veneranative.core.model.SourceId
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.api.SourceRuntimeError
import dev.veneranative.source.engine.QuickJsRuntime
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adapter against a real engine and a source written the way upstream sources are.
 *
 * These are the assertions S1-01 promised to reuse from `FakeSourceCore`: failures travel as values,
 * a missing capability is not a source failure, and pagination keeps the shape the source declared.
 * What they add is the part only a real engine can show — that capability discovery and member calls
 * work on an actual `class X extends ComicSource`.
 */
class EngineSourceCoreTest {

    @Test
    fun `repository demo source exposes the complete stage one protocol`() = runBlocking {
        val scriptFile = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .map { java.io.File(it, "tools/test-sources/demo_comic_source.js") }
            .first { it.isFile }
        val script = scriptFile.readText()
        val id = SourceId("demo_comic_source")
        val runtime = QuickJsRuntime()
        try {
            assertTrue(runtime.install(SourcePackage(id, "2", script, sha256(script))) is SourceInstallResult.Installed)
            val core = EngineSourceCore(runtime)
            val capabilities = (core.capabilities(id) as SourceOutcome.Success).value
            assertTrue(capabilities.supports(SourceCapability.EXPLORE))
            assertTrue(capabilities.supports(SourceCapability.SEARCH))
            assertTrue(capabilities.supports(SourceCapability.DETAIL))
            assertTrue(capabilities.supports(SourceCapability.PAGES))
            val comicKey = ComicKey(id, RemoteComicId("c1"))
            assertTrue(core.detail(comicKey) is SourceOutcome.Success)
            val pages = core.pages(ChapterKey(comicKey, RemoteChapterId("ch1"))) as SourceOutcome.Success
            assertEquals(3, pages.value.size)
            assertTrue(pages.value.all { it.imageRef.startsWith("http://127.0.0.1:8765/") })
        } finally { runtime.close() }
    }

    private val sourceId = SourceId(FIXTURE_KEY)

    @Test
    fun `declared capabilities are read from the source`() = runBlocking {
        withCore(FULL_SOURCE) { core ->
            val capabilities = core.capabilitiesSuccess()

            assertEquals(
                setOf(
                    SourceCapability.EXPLORE,
                    SourceCapability.SEARCH,
                    SourceCapability.DETAIL,
                    SourceCapability.CHAPTERS,
                    SourceCapability.PAGES,
                ),
                capabilities.supported,
            )
            assertEquals(
                listOf("Popular" to ExploreKind.MULTI_PAGE, "Sections" to ExploreKind.MULTI_PART),
                capabilities.explorePages.map { it.title to it.kind },
            )
        }
    }

    @Test
    fun `declared search filters become typed filters in declaration order`() = runBlocking {
        withCore(FULL_SOURCE) { core ->
            val filters = core.capabilitiesSuccess().searchFilters

            assertEquals(listOf("sort", "genre", "status"), filters.map { it.key })
            val sort = filters[0] as SourceFilter.Select
            assertEquals(listOf("0" to "time", "1" to "popular"), sort.options.map { it.value to it.label })
            assertEquals("0", sort.defaultValue)
            assertTrue(filters[1] is SourceFilter.MultiSelect)
            assertTrue(filters[2] is SourceFilter.Dropdown)
        }
    }

    @Test
    fun `a page numbered explore page keeps its page numbers`() = runBlocking {
        withCore(FULL_SOURCE) { core ->
            val first = core.exploreSuccess(ExploreRequest(sourceId, "Popular"))
            val third = core.exploreSuccess(
                ExploreRequest(sourceId, "Popular", cursor = PageCursor.Page(3)),
            )

            assertEquals(listOf("Comic 1"), first.titlesOf())
            assertEquals(listOf("Comic 3"), third.titlesOf())
            assertEquals(PageCursor.Page(2), first.next)
            assertEquals(3, first.totalPages)
        }
    }

    @Test
    fun `a mixed explore page is indexed from zero`() = runBlocking {
        withCore(MIXED_SOURCE) { core ->
            val first = core.exploreSuccess(ExploreRequest(sourceId, "Frontpage"))

            // The fixture echoes the page argument into the comic id, so the base is observable.
            assertEquals(listOf("x0"), first.comicsOf())
            assertEquals(PageCursor.Page(1), first.next)
            val second = core.exploreSuccess(ExploreRequest(sourceId, "Frontpage", cursor = first.next))
            assertEquals(listOf("x1"), second.comicsOf())
            assertEquals(PageCursor.Page(2), second.next)
            val last = core.exploreSuccess(ExploreRequest(sourceId, "Frontpage", cursor = second.next))
            assertEquals(listOf("x2"), last.comicsOf())
            assertNull(last.next)
        }
    }

    @Test
    fun `a part page has no pagination`() = runBlocking {
        withCore(FULL_SOURCE) { core ->
            val page = core.exploreSuccess(ExploreRequest(sourceId, "Sections"))

            assertEquals(1, page.items.size)
            assertEquals("Hot", (page.items.single() as ExploreItem.Section).title)
            assertNull(page.next)
        }
    }

    @Test
    fun `search sends the declared filters positionally`() = runBlocking {
        withCore(FULL_SOURCE) { core ->
            val outcome = core.searchSuccess(
                SearchRequest(
                    sourceId = sourceId,
                    keyword = "frieren",
                    cursor = PageCursor.Page(1),
                    filters = FilterSelection(mapOf("sort" to listOf("1"), "genre" to listOf("comedy"))),
                ),
            )

            // The fixture answers with the options array it received.
            assertEquals(
                """["1","[\"comedy\"]",null]""",
                outcome.items.single().title,
            )
            assertEquals(2, outcome.totalPages)
            assertEquals(PageCursor.Page(2), outcome.next)
        }
    }

    @Test
    fun `a cursor search passes the token back and reports the next one`() = runBlocking {
        withCore(CURSOR_SEARCH_SOURCE) { core ->
            val first = core.searchSuccess(SearchRequest(sourceId = sourceId, keyword = "x"))
            val second = core.searchSuccess(
                SearchRequest(sourceId = sourceId, keyword = "x", cursor = PageCursor.Token("t2")),
            )
            val last = core.searchSuccess(
                SearchRequest(sourceId = sourceId, keyword = "x", cursor = PageCursor.Token("t3")),
            )

            assertEquals("null", first.items.single().title)
            assertEquals(PageCursor.Token("t2"), first.next)
            assertEquals("t2", second.items.single().title)
            assertNull(last.next)
        }
    }

    @Test
    fun `load wins when a source declares both loaders`() = runBlocking {
        withCore(BOTH_SEARCH_SOURCE) { core ->
            val outcome = core.searchSuccess(
                SearchRequest(sourceId = sourceId, keyword = "x", cursor = PageCursor.Token("ignored")),
            )

            // Upstream ignores loadNext when load exists (ADR-0007 §2.2).
            assertEquals("load", outcome.items.single().key.remoteId.value)
        }
    }

    @Test
    fun `detail carries the chapters and pages come from loadEp`() = runBlocking {
        withCore(FULL_SOURCE) { core ->
            val comicKey = ComicKey(sourceId, RemoteComicId("c1"))
            val detail = when (val outcome = core.detail(comicKey)) {
                is SourceOutcome.Success -> outcome.value
                is SourceOutcome.Failure -> error("expected detail, got ${outcome.error}")
            }
            assertEquals("Comic c1", detail.comic.title)
            assertEquals(listOf("Chapter 2", "Chapter 1"), detail.chapters.map { it.title })

            val chapters = when (val outcome = core.chapters(comicKey)) {
                is SourceOutcome.Success -> outcome.value
                is SourceOutcome.Failure -> error("expected chapters, got ${outcome.error}")
            }
            assertEquals(detail.chapters, chapters)

            val chapterKey = ChapterKey(comicKey, RemoteChapterId("ch-1"))
            val pages = when (val outcome = core.pages(chapterKey)) {
                is SourceOutcome.Success -> outcome.value
                is SourceOutcome.Failure -> error("expected pages, got ${outcome.error}")
            }
            assertEquals(
                listOf("https://img/c1/ch-1/1.jpg", "https://img/c1/ch-1/2.jpg"),
                pages.map { it.imageRef },
            )
        }
    }

    @Test
    fun `a capability the source does not declare is unsupported`() = runBlocking {
        withCore(SEARCH_ONLY_SOURCE) { core ->
            val explore = core.explore(ExploreRequest(sourceId, "Popular"))
            val pages = core.pages(
                ChapterKey(ComicKey(sourceId, RemoteComicId("c1")), RemoteChapterId("ch-1")),
            )

            assertUnsupported(explore, SourceCapability.EXPLORE)
            assertUnsupported(pages, SourceCapability.PAGES)
        }
    }

    @Test
    fun `a source failure is reported as a value`() = runBlocking {
        withCore(FAILING_SOURCE) { core ->
            val outcome = core.search(SearchRequest(sourceId = sourceId, keyword = "x"))

            val failure = outcome as SourceOutcome.Failure
            assertTrue("expected a script failure, got ${failure.error}", failure.error is SourceRuntimeError.ScriptExecution)
        }
    }

    @Test
    fun `a source that is not loaded is reported as such`() = runBlocking {
        withCore(FULL_SOURCE) { core ->
            val outcome = core.capabilities(SourceId("someone-else"))

            val failure = outcome as SourceOutcome.Failure
            assertTrue(failure.error is SourceRuntimeError.SourceNotLoaded)
        }
    }

    @Test
    fun `an unknown explore page is an invalid call, not a source failure`() = runBlocking {
        withCore(FULL_SOURCE) { core ->
            val outcome = core.explore(ExploreRequest(sourceId, "Missing"))

            val failure = outcome as SourceOutcome.Failure
            assertTrue(failure.error is SourceRuntimeError.InvalidCall)
        }
    }

    @Test
    fun `reinstall refreshes capabilities on the same core`() = runBlocking {
        val runtime = QuickJsRuntime()
        try {
            runtime.install(SourcePackage(sourceId, "1", FULL_SOURCE, sha256(FULL_SOURCE)))
            val core = EngineSourceCore(runtime)
            assertTrue(core.capabilitiesSuccess().supports(SourceCapability.EXPLORE))
            runtime.install(SourcePackage(sourceId, "2", SEARCH_ONLY_SOURCE, sha256(SEARCH_ONLY_SOURCE)))
            assertTrue(!core.capabilitiesSuccess().supports(SourceCapability.EXPLORE))
        } finally { runtime.close() }
    }

    private suspend fun withCore(script: String, block: suspend (EngineSourceCore) -> Unit) {
        val runtime = QuickJsRuntime()
        try {
            val installed =
                runtime.install(
                    SourcePackage(
                        sourceId = sourceId,
                        version = "1",
                        script = script,
                        sha256 = sha256(script),
                    ),
                )
            assertTrue("expected install, got $installed", installed is SourceInstallResult.Installed)
            block(EngineSourceCore(runtime))
        } finally {
            runtime.close()
        }
    }

    private suspend fun EngineSourceCore.capabilitiesSuccess() =
        when (val outcome = capabilities(sourceId)) {
            is SourceOutcome.Success -> outcome.value
            is SourceOutcome.Failure -> error("expected capabilities, got ${outcome.error}")
        }

    private suspend fun EngineSourceCore.exploreSuccess(request: ExploreRequest) =
        when (val outcome = explore(request)) {
            is SourceOutcome.Success -> outcome.value
            is SourceOutcome.Failure -> error("expected explore, got ${outcome.error}")
        }

    private suspend fun EngineSourceCore.searchSuccess(request: SearchRequest) =
        when (val outcome = search(request)) {
            is SourceOutcome.Success -> outcome.value
            is SourceOutcome.Failure -> error("expected search, got ${outcome.error}")
        }

    private fun dev.veneranative.core.model.PagedResult<ExploreItem>.comicsOf(): List<String> =
        items.flatMap { item ->
            when (item) {
                is ExploreItem.Comics -> item.comics.map { it.key.remoteId.value }
                is ExploreItem.Section -> item.comics.map { it.key.remoteId.value }
            }
        }

    private fun dev.veneranative.core.model.PagedResult<ExploreItem>.titlesOf(): List<String> =
        items.flatMap { item ->
            when (item) {
                is ExploreItem.Comics -> item.comics.map { it.title }
                is ExploreItem.Section -> item.comics.map { it.title }
            }
        }

    private fun assertUnsupported(outcome: SourceOutcome<*>, capability: SourceCapability) {
        val failure = outcome as SourceOutcome.Failure
        val error = failure.error
        assertTrue("expected UnsupportedCapability, got $error", error is SourceRuntimeError.UnsupportedCapability)
        assertEquals(capability, (error as SourceRuntimeError.UnsupportedCapability).capability)
    }

    private companion object {
        const val FIXTURE_KEY = "fixture"

        val FULL_SOURCE: String = source(
            """
            explore = [
              {
                title: "Popular",
                type: "multiPageComicList",
                load: async function (page) {
                  return { comics: [{ id: "c1", title: "Comic " + page }], maxPage: 3 };
                }
              },
              {
                title: "Sections",
                type: "multiPartPage",
                load: async function () {
                  return [{ title: "Hot", comics: [{ id: "h1", title: "Hot 1" }] }];
                }
              }
            ];

            search = {
              optionList: [
                { type: "select", label: "sort", options: ["0-time", "1-popular"], default: "0" },
                { type: "multi-select", label: "genre", options: ["action-Action", "comedy-Comedy"] },
                { type: "dropdown", label: "status", options: ["done-Done"] }
              ],
              load: async function (keyword, options, page) {
                return {
                  comics: [{ id: keyword + "-" + page, title: JSON.stringify(options) }],
                  maxPage: 2
                };
              }
            };

            comic = {
              loadInfo: async function (id) {
                return {
                  title: "Comic " + id,
                  description: "A fixture comic.",
                  chapters: { "ch-2": "Chapter 2", "ch-1": "Chapter 1" }
                };
              },
              loadEp: async function (id, ep) {
                return { images: ["https://img/" + id + "/" + ep + "/1.jpg", "https://img/" + id + "/" + ep + "/2.jpg"] };
              }
            };
            """.trimIndent(),
        )

        val MIXED_SOURCE: String = source(
            """
            explore = [{
              title: "Frontpage",
              type: "mixed",
              load: async function (page) {
                return { data: [[{ id: "x" + page, title: "X" }]], maxPage: 2 };
              }
            }];
            """.trimIndent(),
        )

        val CURSOR_SEARCH_SOURCE: String = source(
            """
            search = {
              loadNext: async function (keyword, options, next) {
                if (next === null) {
                  return { comics: [{ id: "c1", title: "null" }], next: "t2" };
                }
                return {
                  comics: [{ id: "c2", title: next }],
                  next: next === "t2" ? "t3" : null
                };
              }
            };
            """.trimIndent(),
        )

        val BOTH_SEARCH_SOURCE: String = source(
            """
            search = {
              load: async function (keyword, options, page) {
                return { comics: [{ id: "load", title: "from load" }], maxPage: 1 };
              },
              loadNext: async function (keyword, options, next) {
                return { comics: [{ id: "loadNext", title: "from loadNext" }], next: "t9" };
              }
            };
            """.trimIndent(),
        )

        val SEARCH_ONLY_SOURCE: String = source(
            """
            search = {
              load: async function (keyword, options, page) {
                return { comics: [], maxPage: 1 };
              }
            };
            """.trimIndent(),
        )

        val FAILING_SOURCE: String = source(
            """
            search = {
              load: async function (keyword, options, page) {
                throw new Error("search is broken");
              }
            };
            """.trimIndent(),
        )

        /** A source script in the upstream shape, with [body] as extra members. */
        fun source(body: String): String = buildString {
            appendLine("class FixtureSource extends ComicSource {")
            appendLine("  constructor() {")
            appendLine("    super();")
            appendLine("    this.name = \"Fixture\";")
            appendLine("    this.key = \"$FIXTURE_KEY\";")
            appendLine("    this.version = \"1\";")
            appendLine("  }")
            appendLine()
            appendLine(body)
            appendLine("}")
        }

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

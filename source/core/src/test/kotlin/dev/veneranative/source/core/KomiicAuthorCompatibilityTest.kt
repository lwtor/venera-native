package dev.veneranative.source.core

import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.TagNavigation
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceInstallResult
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourcePackage
import dev.veneranative.source.engine.QuickJsRuntime
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KomiicAuthorCompatibilityTest {
    @Test fun `author works retain all names and completed state`() {
        val result = KomiicAuthorCompatibility.works(SourceId("Komiic"),
            """{"data":{"getComicsByAuthor":[{"id":"fixture","title":"Fixture","authors":[{"name":"A"},{"name":"B"}],"status":"END","dateUpdated":"2026-10-14"}]}}""")
        assertEquals(listOf("A", "B"), result.items.single().authors)
        assertEquals(dev.veneranative.core.model.ComicPublicationStatus.Completed, result.items.single().publicationStatus)
        assertEquals("2026-10-14", result.items.single().lastUpdated)
    }
    @Test fun `author lookup matches aliases but rejects ambiguous names`() {
        assertEquals(TagNavigation.Search("Alias", authorId = "7"), KomiicAuthorCompatibility.resolve(
            """{"data":{"searchComicsAndAuthors":{"authors":[{"id":"7","name":"Original","chName":"Alias"}]}}}""", "Alias",
        ))
        assertNull(KomiicAuthorCompatibility.resolve(
            """{"data":{"searchComicsAndAuthors":{"authors":[{"id":"7","name":"A"},{"id":"8","name":"A"}]}}}""", "A",
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `graphql errors are not reported as an empty works list`() {
        KomiicAuthorCompatibility.works(SourceId("Komiic"), """{"errors":[{"message":"fixture failure"}],"data":{"getComicsByAuthor":[]}}""")
    }

    @Test fun `missing tag callback resolves exact author id and queries that authors comics`() = runBlocking {
        withSource { core, id ->
            assertTrue((core.capabilities(id) as SourceOutcome.Success).value.canNavigateTags)
            val key = ComicKey(id, RemoteComicId("comic"))
            val target = (core.tagNavigation(key, "作者", "A\"B") as SourceOutcome.Success).value as TagNavigation.Search
            assertEquals("author-7", target.authorId)
            val works = (core.search(SearchRequest(id, target.keyword, authorId = target.authorId)) as SourceOutcome.Success).value
            assertEquals(listOf("work-1"), works.items.map { it.key.remoteId.value })
            assertEquals("https://fixture.invalid/cover", works.items.single().coverUrl)
            assertEquals(listOf("Genre", "Adventure"), works.items.single().tags)
            assertNull(works.next)
            assertNull((core.tagNavigation(key, "标签", "A\"B") as SourceOutcome.Success).value)
            assertNull((core.tagNavigation(key, "作者", "Missing") as SourceOutcome.Success).value)
        }
    }

    @Test fun `explicit source callback keeps priority and other sources do not gain fallback`() = runBlocking {
        withSource(callback = "onClickTag: () => ({action: 'search', keyword: 'explicit'}),") { core, id ->
            assertEquals(TagNavigation.Search("explicit"), (core.tagNavigation(ComicKey(id, RemoteComicId("c")), "作者", "A") as SourceOutcome.Success).value)
        }
        withSource(key = "Other") { core, id ->
            assertTrue(!(core.capabilities(id) as SourceOutcome.Success).value.canNavigateTags)
            assertNull((core.tagNavigation(ComicKey(id, RemoteComicId("c")), "作者", "A") as SourceOutcome.Success).value)
        }
    }

    @Test fun `Komiic tags use declared category id and positional defaults`() = runBlocking {
        withSource { core, id ->
            val target = (core.tagNavigation(ComicKey(id, RemoteComicId("c")), "标签", "Genre") as SourceOutcome.Success).value as TagNavigation.Category
            assertEquals(TagNavigation.Category("Genre", "category-7"), target)
            val filters = (core.relatedWorksFilters(id, target) as SourceOutcome.Success).value
            val options = filters.map { (it.selectionValue(dev.veneranative.core.model.FilterSelection.Empty) as dev.veneranative.core.model.FilterValue.Single).value }
            assertEquals(listOf("updated", ""), options)
            val page = (core.category(dev.veneranative.source.api.CategoryRequest(id, target.keyword, target.param, options)) as SourceOutcome.Success).value
            assertEquals("category-7:updated::1", page.items.single().title)
            assertEquals(dev.veneranative.core.model.PageCursor.Page(2), page.next)
        }
    }

    private suspend fun withSource(key: String = "Komiic", callback: String = "", block: suspend (EngineSourceCore, SourceId) -> Unit) {
        val script = """
            class Fixture extends ComicSource {
              name = 'Fixture'; key = '$key'; version = '1';
              comic = {$callback loadInfo: async () => ({title: 'Fixture'})};
              search = {load: async () => { throw new Error('ordinary search must not run'); }};
              category = {parts: [{type: 'fixed', itemType: 'category', categories: ['All', 'Genre'], categoryParams: ['0', 'category-7']}]};
              categoryComics = {
                optionList: [{options: ['updated-Updated']}, {options: ['-All', 'end-End'], showWhen: null}],
                load: async (category, param, options, page) => ({comics: [{id: 'category-work', title: param + ':' + options.join(':') + ':' + page}], maxPage: 2})
              };
              queryJson = async (request) => {
                if (request.operationName === 'searchComicAndAuthorQuery') {
                  return {data: {searchComicsAndAuthors: {authors: request.variables.keyword === 'A"B' ? [{id: 'author-7', name: 'A"B'}, {id: 'wrong', name: 'Partial A"B'}] : []}}};
                }
                if (request.operationName === 'comicsByAuthor' && request.variables.authorId === 'author-7') {
                  if (!request.query.includes('categories')) throw new Error('missing category fields');
                  return {data: {getComicsByAuthor: [{id: 'work-1', title: 'Work', imageUrl: 'https://fixture.invalid/cover', authors: [{name: 'A"B'}], categories: [{name: 'Genre'}, {name: 'Adventure'}, {name: ''}, {name: 'Genre'}]}]}};
                }
                throw new Error('unexpected operation');
              };
            }
        """.trimIndent()
        val runtime = QuickJsRuntime()
        try {
            val id = SourceId(key)
            val digest = MessageDigest.getInstance("SHA-256").digest(script.toByteArray()).joinToString("") { "%02x".format(it) }
            assertTrue(runtime.install(SourcePackage(id, "1", script, digest)) is SourceInstallResult.Installed)
            block(EngineSourceCore(runtime), id)
        } finally { runtime.close() }
    }
}

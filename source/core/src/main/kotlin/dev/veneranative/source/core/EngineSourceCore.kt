package dev.veneranative.source.core

import dev.veneranative.source.api.CategoryRequest
import dev.veneranative.source.api.protocol.SourceOperationKind
import dev.veneranative.core.model.TagNavigation
import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.Comic
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.ExploreItem
import dev.veneranative.core.model.ExploreKind
import dev.veneranative.core.model.ExplorePage
import dev.veneranative.core.model.FilterOption
import dev.veneranative.core.model.PageCursor
import dev.veneranative.core.model.PagedResult
import dev.veneranative.core.model.SourceCapabilities
import dev.veneranative.core.model.SourceCapability
import dev.veneranative.core.model.SourceFilter
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourcePage
import dev.veneranative.source.api.ExploreRequest
import dev.veneranative.source.api.SearchRequest
import dev.veneranative.source.api.SourceCall
import dev.veneranative.source.api.SourceCore
import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceResult
import dev.veneranative.source.api.SourceRuntimeError
import dev.veneranative.source.api.SourceScriptRuntime
import dev.veneranative.source.api.protocol.SourceProtocol
import dev.veneranative.source.api.protocol.SourceProtocolCall
import dev.veneranative.source.api.protocol.SourceProtocolParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * [SourceCore] over a [SourceScriptRuntime]: typed operations in, upstream calls out.
 *
 * What a source declares is read through the current engine instance's structure probe. The declarations decide the call
 * shape, the way upstream does:
 *
 * - `load` wins over `loadNext` (ADR-0007 §2.2), so a cursor is only used when the source declares
 *   no page-numbered loader;
 * - explore pages are addressed by their **position** in the declared array, which is why the call
 *   member is `explore.<index>.load` rather than a name;
 * - a capability the source does not declare is answered as unsupported, never attempted
 *   (ADR-0007 §2.1).
 */
class EngineSourceCore(
    private val runtime: SourceScriptRuntime,
    private val timeoutMillis: Long = DEFAULT_SOURCE_OPERATION_TIMEOUT_MILLIS,
    private val chapterLoadTimeoutMillis: Long = DEFAULT_CHAPTER_LOAD_TIMEOUT_MILLIS,
    private val onCallFailure: (SourceId, String, String) -> Unit = { _, _, _ -> },
    waitBeforeRetry: suspend (Int) -> Unit = { kotlinx.coroutines.delay(it * 500L) },
) : SourceCore {

    private val sequence = AtomicLong(0)
    private val executor = SourceCallExecutor(runtime, waitBeforeRetry)

    override suspend fun tagNavigation(comicKey: ComicKey, namespace: String, tag: String): SourceOutcome<TagNavigation?> {
        val description = when (val result = describe(comicKey.sourceId)) {
            is SourceOutcome.Failure -> return result
            is SourceOutcome.Success -> result.value
        }
        if (!description.capabilities.canNavigateTags) return SourceOutcome.Success(null)
        if (description.usesKomiicTagFallback) {
            if (namespace == "标签") return komiicCategoryTag(comicKey.sourceId, tag)
            if (namespace != "作者" || tag.isBlank()) return SourceOutcome.Success(null)
            return when (val result = invoke(comicKey.sourceId, KomiicAuthorCompatibility.resolveCall(tag))) {
                is SourceOutcome.Failure -> result
                is SourceOutcome.Success -> parseAuthorResponse { KomiicAuthorCompatibility.resolve(result.value, tag) }
            }
        }
        return when (val result = invoke(comicKey.sourceId, SourceProtocol.onClickTag(namespace, tag))) {
            is SourceOutcome.Failure -> result
            is SourceOutcome.Success -> SourceOutcome.Success(SourceProtocolParser.parseTagNavigation(result.value))
        }
    }

    override suspend fun relatedWorksFilters(sourceId: SourceId, target: TagNavigation): SourceOutcome<List<SourceFilter>> {
        if (target is TagNavigation.Search && target.authorId != null) return SourceOutcome.Success(emptyList())
        if (target is TagNavigation.Search) {
            return when (val result = capabilities(sourceId)) {
                is SourceOutcome.Failure -> result
                is SourceOutcome.Success -> SourceOutcome.Success(result.value.searchFilters)
            }
        }
        target as TagNavigation.Category
        val category = when (val result = probe(sourceId, listOf("categoryComics"))) {
            is SourceOutcome.Failure -> return result
            is SourceOutcome.Success -> result.value
        }
        if (!category.entry("load").isCallable()) return SourceOutcome.Failure(SourceRuntimeError.InvalidCall("Category loader is unavailable."))
        val optionList = if (category.entry("optionLoader").isCallable()) {
            val call = SourceProtocolCall("categoryComics.optionLoader", JsonArray(listOf(JsonPrimitive(target.keyword), target.param?.let(::JsonPrimitive) ?: JsonNull)).toString(), SourceOperationKind.READ)
            when (val result = invoke(sourceId, call)) {
                is SourceOutcome.Failure -> return result
                is SourceOutcome.Success -> runCatching { SourceShape.parse(kotlinx.serialization.json.Json.parseToJsonElement(result.value)) }.getOrNull()
                    ?: return SourceOutcome.Failure(SourceRuntimeError.ScriptExecution("Invalid category options."))
            }
        } else {
            // Probe separately so nested option arrays are not cut off by the shape depth limit.
            when (val result = probe(sourceId, listOf("categoryComics", "optionList"))) {
                is SourceOutcome.Failure -> return result
                is SourceOutcome.Success -> result.value
            }
        }
        if (category.entry("optionLoader").isCallable() && optionList !is SourceShape.ListOf) {
            return SourceOutcome.Failure(SourceRuntimeError.ScriptExecution("Invalid category options."))
        }
        val filters = optionList.items().mapIndexedNotNull { index, option ->
            val hidden = option.entry("notShowWhen").items().any { it.text() == target.keyword }
            val shown = (option.entry("showWhen") as? SourceShape.ListOf)?.items()?.any { it.text() == target.keyword } ?: true
            if (!category.entry("optionLoader").isCallable() && (hidden || !shown)) return@mapIndexedNotNull null
            val choices = option.entry("options").items().mapNotNull { declared ->
                val text = declared.text() ?: return@mapNotNull null
                val separator = text.indexOf('-')
                if (separator < 0) null else text.substring(0, separator) to text.substring(separator + 1)
            }.toMap()
            // An empty category value is valid upstream; keep it as the positional default.
            val options = choices.filterKeys { it.isNotEmpty() }.map { (value, label) -> FilterOption(value, label) }
            if (choices.isEmpty()) null else SourceFilter.Select("category-option-$index", option.entry("label").text().orEmpty(), options, choices.keys.first())
        }
        return SourceOutcome.Success(filters)
    }

    override suspend fun category(request: CategoryRequest): SourceOutcome<PagedResult<Comic>> {
        val shape = when (val result = probe(request.sourceId, listOf("categoryComics", "load"))) {
            is SourceOutcome.Failure -> return result
            is SourceOutcome.Success -> result.value
        }
        if (!shape.isCallable()) return SourceOutcome.Failure(SourceRuntimeError.InvalidCall("Category loader is unavailable."))
        return when (val result = invoke(request.sourceId, SourceProtocol.category(request))) {
            is SourceOutcome.Failure -> result
            is SourceOutcome.Success -> SourceOutcome.Success(SourceProtocolParser.parseComicList(request.sourceId, result.value, (request.cursor as? PageCursor.Page)?.number ?: FIRST_PAGE))
        }
    }


    override suspend fun capabilities(sourceId: SourceId): SourceOutcome<SourceCapabilities> =
        when (val described = describe(sourceId)) {
            is SourceOutcome.Success -> SourceOutcome.Success(described.value.capabilities)
            is SourceOutcome.Failure -> described
        }

    override suspend fun explore(request: ExploreRequest): SourceOutcome<PagedResult<ExploreItem>> {
        val description = when (val described = describe(request.sourceId)) {
            is SourceOutcome.Success -> described.value
            is SourceOutcome.Failure -> return described
        }
        // A source without explore support is "unsupported", which is a product state; only a source
        // that declares explore but not this page is an invalid call.
        if (!description.capabilities.supports(SourceCapability.EXPLORE)) {
            return SourceOutcome.unsupported(SourceCapability.EXPLORE)
        }
        val page = description.capabilities.explorePage(request.pageKey)
            ?: return unknownPage(request.pageKey)
        val declared = description.explorePages[page.key] ?: return unknownPage(request.pageKey)

        val call =
            if (declared.usesLoadNext) {
                SourceProtocolCall(
                    member = "explore.${declared.index}.loadNext",
                    argumentsJson = JsonArray(listOf(cursorTokenOrNull(request.cursor))).toString(),
                    operationKind = SourceOperationKind.READ,
                )
            } else {
                // The protocol knows how a page's argument depends on its kind; only the member path
                // has to name the position this source declared.
                SourceProtocol.explore(page, request)
                    .copy(member = "explore.${declared.index}.load")
            }

        return when (val payload = invoke(request.sourceId, call)) {
            is SourceOutcome.Failure -> payload

            is SourceOutcome.Success -> SourceOutcome.Success(
                if (declared.usesLoadNext) {
                    SourceProtocolParser.parseExplorePageCursor(request.sourceId, page, payload.value)
                } else {
                    SourceProtocolParser.parseExplorePage(
                        sourceId = request.sourceId,
                        page = page,
                        payload = payload.value,
                        pageNumber = (request.cursor as? PageCursor.Page)?.number
                            ?: if (page.kind == ExploreKind.MIXED) 0 else FIRST_PAGE,
                    )
                },
            )
        }
    }

    override suspend fun search(request: SearchRequest): SourceOutcome<PagedResult<Comic>> {
        val description = when (val described = describe(request.sourceId)) {
            is SourceOutcome.Success -> described.value
            is SourceOutcome.Failure -> return described
        }
        val capabilities = description.capabilities
        if (!capabilities.supports(SourceCapability.SEARCH)) {
            return SourceOutcome.unsupported(SourceCapability.SEARCH)
        }

        request.authorId?.let { authorId ->
            if (request.sourceId.value != KomiicAuthorCompatibility.SOURCE_KEY || authorId.isBlank()) {
                return SourceOutcome.Failure(SourceRuntimeError.InvalidCall("Author lookup is unavailable."))
            }
            return when (val result = invoke(request.sourceId, KomiicAuthorCompatibility.worksCall(authorId))) {
                is SourceOutcome.Failure -> result
                is SourceOutcome.Success -> parseAuthorResponse { KomiicAuthorCompatibility.works(request.sourceId, result.value) }
            }
        }

        val pageNumber = (request.cursor as? PageCursor.Page)?.number ?: FIRST_PAGE
        val token = (request.cursor as? PageCursor.Token)?.value
        return if (description.searchUsesLoad) {
            val call = SourceProtocol.searchLoad(capabilities.searchFilters, request, pageNumber)
            when (val payload = invoke(request.sourceId, call)) {
                is SourceOutcome.Failure -> payload
                is SourceOutcome.Success -> SourceOutcome.Success(
                    SourceProtocolParser.parseComicList(request.sourceId, payload.value, pageNumber),
                )
            }
        } else {
            val call = SourceProtocol.searchLoadNext(capabilities.searchFilters, request, token)
            when (val payload = invoke(request.sourceId, call)) {
                is SourceOutcome.Failure -> payload
                is SourceOutcome.Success -> SourceOutcome.Success(
                    SourceProtocolParser.parseCursorComicList(request.sourceId, payload.value),
                )
            }
        }
    }

    override suspend fun detail(comicKey: ComicKey): SourceOutcome<ComicDetail> {
        val description = when (val described = describe(comicKey.sourceId)) {
            is SourceOutcome.Success -> described.value
            is SourceOutcome.Failure -> return described
        }
        if (!description.capabilities.supports(SourceCapability.DETAIL)) {
            return SourceOutcome.unsupported(SourceCapability.DETAIL)
        }

        return when (val payload = invoke(comicKey.sourceId, SourceProtocol.loadInfo(comicKey))) {
            is SourceOutcome.Failure -> payload

            is SourceOutcome.Success ->
                SourceProtocolParser.parseComicDetail(comicKey, payload.value)
                    ?.let { SourceOutcome.Success(it) }
                    ?: SourceOutcome.Failure(
                        SourceRuntimeError.ScriptExecution("Source returned no comic details."),
                    )
        }
    }

    override suspend fun comicTagsBatch(keys: List<ComicKey>): SourceOutcome<Map<ComicKey, List<String>>> {
        if (keys.isEmpty()) return SourceOutcome.Success(emptyMap())
        if (keys.size > 6 || keys.any { it.sourceId.value != "copy_manga" }) {
            return SourceOutcome.Failure(SourceRuntimeError.InvalidCall("Invalid tag batch."))
        }
        return when (val result = invoke(keys.first().sourceId, SourceProtocol.copyMangaTagsBatch(keys))) {
            is SourceOutcome.Failure -> result
            is SourceOutcome.Success -> SourceProtocolParser.parseContentTagsBatch(result.value)?.let { tags ->
                SourceOutcome.Success(keys.mapNotNull { key -> tags[key.remoteId.value]?.let { key to it } }.toMap())
            } ?: SourceOutcome.Failure(SourceRuntimeError.ScriptExecution("Invalid comic tag metadata."))
        }
    }

    override suspend fun comicTags(comicKey: ComicKey): SourceOutcome<List<String>> {
        if (comicKey.sourceId.value != "copy_manga") return super<SourceCore>.comicTags(comicKey)
        return when (val result = invoke(comicKey.sourceId, SourceProtocol.copyMangaTags(comicKey))) {
            is SourceOutcome.Failure -> result
            is SourceOutcome.Success -> SourceProtocolParser.parseContentTags(result.value)?.let { SourceOutcome.Success(it) }
                ?: SourceOutcome.Failure(SourceRuntimeError.ScriptExecution("Invalid comic tag metadata."))
        }
    }

    override suspend fun chapters(comicKey: ComicKey): SourceOutcome<List<Chapter>> {
        val description = when (val described = describe(comicKey.sourceId)) {
            is SourceOutcome.Success -> described.value
            is SourceOutcome.Failure -> return described
        }
        if (!description.capabilities.supports(SourceCapability.CHAPTERS)) {
            return SourceOutcome.unsupported(SourceCapability.CHAPTERS)
        }
        // Chapters arrive with the detail response (ADR-0007 §4.1); asking twice would be a second
        // round trip for data already fetched.
        return when (val outcome = detail(comicKey)) {
            is SourceOutcome.Success -> SourceOutcome.Success(outcome.value.chapters)
            is SourceOutcome.Failure -> outcome
        }
    }

    override suspend fun pages(chapterKey: ChapterKey): SourceOutcome<List<SourcePage>> {
        val sourceId = chapterKey.comicKey.sourceId
        val description = when (val described = describe(sourceId)) {
            is SourceOutcome.Success -> described.value
            is SourceOutcome.Failure -> return described
        }
        if (!description.capabilities.supports(SourceCapability.PAGES)) {
            return SourceOutcome.unsupported(SourceCapability.PAGES)
        }

        return when (val payload = invoke(sourceId, SourceProtocol.loadEp(chapterKey))) {
            is SourceOutcome.Failure -> payload
            is SourceOutcome.Success -> {
                val pages = SourceProtocolParser.parseImages(payload.value)
                if (!description.usesImageLoadConfig) return SourceOutcome.Success(pages)
                SourceOutcome.Success(pages.map { page ->
                    when (val config = invoke(sourceId, SourceProtocol.onImageLoad(page.imageRef, chapterKey))) {
                        is SourceOutcome.Success -> page.copy(
                            headers = SourceProtocolParser.parseImageHeaders(config.value),
                            reverseHorizontalBands = SourceProtocolParser.parseReverseHorizontalBands(config.value),
                        )
                        is SourceOutcome.Failure -> page
                    }
                })
            }
        }
    }

    private suspend fun describe(sourceId: SourceId): SourceOutcome<SourceDescription> {
        // Probe the current runtime instance; reinstall and in-flight probes cannot cache stale shapes.

        val probed = mutableMapOf<String, SourceShape?>()
        for (path in PROBE_PATHS) {
            when (val result = probe(sourceId, path)) {
                is SourceOutcome.Success -> probed[path.joinToString(".")] = result.value
                is SourceOutcome.Failure -> return result
            }
        }

        val explore = probed["explore"]
        val search = probed["search"]
        val comic = probed["comic"]

        val pages = mutableListOf<ExplorePage>()
        val declaredPages = mutableMapOf<String, ExplorePageRuntime>()
        explore.items().forEachIndexed { index, item ->
            val title = item.entry("title").text() ?: return@forEachIndexed
            val kind = exploreKind(item.entry("type").text()) ?: return@forEachIndexed
            val page = ExplorePage.of(title, kind)
            pages += page
            // A page declared twice keeps the first position, which is the one upstream would call
            // through its own index scan.
            declaredPages.putIfAbsent(
                page.key,
                ExplorePageRuntime(index = index, usesLoadNext = !item.entry("load").isCallable()),
            )
        }

        val searchUsesLoad = search.entry("load").isCallable()
        val supported = buildSet {
            if (pages.isNotEmpty()) add(SourceCapability.EXPLORE)
            if (searchUsesLoad || search.entry("loadNext").isCallable()) add(SourceCapability.SEARCH)
            if (comic.entry("loadInfo").isCallable()) {
                add(SourceCapability.DETAIL)
                add(SourceCapability.CHAPTERS)
            }
            if (comic.entry("loadEp").isCallable()) add(SourceCapability.PAGES)
        }

        val usesKomiicTagFallback = if (sourceId.value == KomiicAuthorCompatibility.SOURCE_KEY &&
            !comic.entry("onClickTag").isCallable() && SourceCapability.SEARCH in supported) {
            when (val result = probe(sourceId, listOf("queryJson"))) {
                is SourceOutcome.Failure -> return result
                is SourceOutcome.Success -> result.value.isCallable()
            }
        } else false

        val description =
            SourceDescription(
                capabilities = SourceCapabilities(
                    supported = supported,
                    explorePages = pages,
                    searchFilters = searchFilters(probed["search.optionList"]),
                    canNavigateTags = comic.entry("onClickTag").isCallable() || usesKomiicTagFallback,
                ),
                explorePages = declaredPages,
                searchUsesLoad = searchUsesLoad,
                usesImageLoadConfig = comic.entry("onImageLoad").isCallable(),
                usesKomiicTagFallback = usesKomiicTagFallback,
            )
        return SourceOutcome.Success(description)
    }

    private suspend fun probe(sourceId: SourceId, path: List<String>): SourceOutcome<SourceShape?> {
        val pathJson = JsonArray(path.map(::JsonPrimitive)).toString()
        val argumentsJson = JsonArray(listOf<JsonElement>(JsonPrimitive(pathJson))).toString()
        return when (val invoked = invoke(sourceId, SourceProtocolCall(PROBE_MEMBER, argumentsJson, SourceOperationKind.READ))) {
            is SourceOutcome.Success -> SourceOutcome.Success(SourceShape.decode(invoked.value))
            is SourceOutcome.Failure -> invoked
        }
    }

    private suspend fun invoke(sourceId: SourceId, call: SourceProtocolCall): SourceOutcome<String> {
        val result =
            executor.execute(call.operationKind) {
                SourceCall.InvokeFunction(
                    callId = "core-${sequence.incrementAndGet()}",
                    sourceId = sourceId,
                    functionName = call.member,
                    argumentsJson = call.argumentsJson,
                    timeoutMillis = if (call.member == SourceProtocol.MEMBER_LOAD_EP) {
                        chapterLoadTimeoutMillis
                    } else if (call.member == PROBE_MEMBER) {
                        DEFAULT_PROBE_TIMEOUT_MILLIS
                    } else {
                        timeoutMillis
                    },
                )
            }
        return when (result) {
            is SourceResult.Success -> SourceOutcome.Success(result.json)

            is SourceResult.Failure -> {
                // A source that is not loaded may have been reinstalled since it was described.
                if (result.error !is SourceRuntimeError.Cancelled) {
                    runCatching {
                        onCallFailure(
                            sourceId,
                            call.member,
                            result.error.javaClass.simpleName.ifBlank { "SourceRuntimeError" },
                        )
                    }
                }
                SourceOutcome.Failure(result.error)
            }
        }
    }

    /** Upstream splits a declared option at its first `-` into the value and the shown label. */
    private fun searchFilters(optionList: SourceShape?): List<SourceFilter> =
        optionList.items().mapNotNull { option ->
            val label = option.entry("label").text() ?: return@mapNotNull null
            val options = option.entry("options").items().mapNotNull { declared ->
                val text = declared.text() ?: return@mapNotNull null
                val separator = text.indexOf('-')
                if (separator <= 0) {
                    FilterOption(value = text, label = text)
                } else {
                    FilterOption(
                        value = text.substring(0, separator),
                        label = text.substring(separator + 1),
                    )
                }
            }
            if (options.isEmpty()) return@mapNotNull null

            // Upstream filter entries carry no key; they are identified by their position, so the
            // label is the closest stable identity our model can hold.
            val defaultValue = option.entry("default").text()
            when (option.entry("type").text() ?: FILTER_TYPE_SELECT) {
                FILTER_TYPE_MULTI_SELECT ->
                    SourceFilter.MultiSelect(key = label, label = label, options = options)

                FILTER_TYPE_DROPDOWN ->
                    SourceFilter.Dropdown(
                        key = label,
                        label = label,
                        options = options,
                        defaultValue = defaultValue,
                    )

                else ->
                    SourceFilter.Select(
                        key = label,
                        label = label,
                        options = options,
                        defaultValue = defaultValue,
                    )
            }
        }

    private fun exploreKind(declared: String?): ExploreKind? = when (declared) {
        "multiPageComicList" -> ExploreKind.MULTI_PAGE
        "mixed" -> ExploreKind.MIXED
        "multiPartPage", "singlePageWithMultiPart" -> ExploreKind.MULTI_PART
        else -> null
    }

    private fun cursorTokenOrNull(cursor: PageCursor?): JsonElement =
        (cursor as? PageCursor.Token)?.value?.let(::JsonPrimitive) ?: JsonNull

    private fun unknownPage(pageKey: String): SourceOutcome.Failure =
        SourceOutcome.Failure(SourceRuntimeError.InvalidCall("Unknown explore page '$pageKey'."))

    private data class SourceDescription(
        val capabilities: SourceCapabilities,
        val explorePages: Map<String, ExplorePageRuntime>,
        val searchUsesLoad: Boolean,
        val usesImageLoadConfig: Boolean,
        val usesKomiicTagFallback: Boolean,
    )

    private fun <T> parseAuthorResponse(parse: () -> T): SourceOutcome<T> = try {
        SourceOutcome.Success(parse())
    } catch (_: Exception) {
        SourceOutcome.Failure(SourceRuntimeError.ScriptExecution("Invalid author response."))
    }

    private suspend fun komiicCategoryTag(sourceId: SourceId, tag: String): SourceOutcome<TagNavigation?> {
        val loader = when (val result = probe(sourceId, listOf("categoryComics", "load"))) {
            is SourceOutcome.Failure -> return result
            is SourceOutcome.Success -> result.value
        }
        if (!loader.isCallable()) return SourceOutcome.Success(null)
        val parts = when (val result = probe(sourceId, listOf("category", "parts"))) {
            is SourceOutcome.Failure -> return result
            is SourceOutcome.Success -> result.value
        }
        val targets = parts.items().flatMap { part ->
            if (part.entry("type").text() != "fixed" || part.entry("itemType").text() != "category") return@flatMap emptyList()
            val params = part.entry("categoryParams").items()
            part.entry("categories").items().mapIndexedNotNull { index, category ->
                val param = params.getOrNull(index).text()?.takeIf(String::isNotBlank)
                if (category.text() == tag && param != null) TagNavigation.Category(tag, param) else null
            }
        }.distinct()
        return SourceOutcome.Success(targets.singleOrNull())
    }

    private data class ExplorePageRuntime(val index: Int, val usesLoadNext: Boolean)

    private companion object {
        const val DEFAULT_SOURCE_OPERATION_TIMEOUT_MILLIS = 75_000L
        const val DEFAULT_PROBE_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_CHAPTER_LOAD_TIMEOUT_MILLIS = 60_000L
        const val FIRST_PAGE = 1
        const val PROBE_MEMBER = "__venera.probe"
        const val FILTER_TYPE_SELECT = "select"
        const val FILTER_TYPE_MULTI_SELECT = "multi-select"
        const val FILTER_TYPE_DROPDOWN = "dropdown"

        /** One probe per declaration the contract exposes, kept small on purpose. */
        val PROBE_PATHS = listOf(
            listOf("explore"),
            listOf("search"),
            listOf("search", "optionList"),
            listOf("comic"),
        )
    }
}

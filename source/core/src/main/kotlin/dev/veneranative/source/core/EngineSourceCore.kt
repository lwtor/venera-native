package dev.veneranative.source.core

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
    private val timeoutMillis: Long = SourceCall.DEFAULT_TIMEOUT_MILLIS,
) : SourceCore {

    private val sequence = AtomicLong(0)


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
            is SourceOutcome.Success -> SourceOutcome.Success(SourceProtocolParser.parseImages(payload.value))
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

        val description =
            SourceDescription(
                capabilities = SourceCapabilities(
                    supported = supported,
                    explorePages = pages,
                    searchFilters = searchFilters(probed["search.optionList"]),
                ),
                explorePages = declaredPages,
                searchUsesLoad = searchUsesLoad,
            )
        return SourceOutcome.Success(description)
    }

    private suspend fun probe(sourceId: SourceId, path: List<String>): SourceOutcome<SourceShape?> {
        val pathJson = JsonArray(path.map(::JsonPrimitive)).toString()
        val argumentsJson = JsonArray(listOf<JsonElement>(JsonPrimitive(pathJson))).toString()
        return when (val invoked = invoke(sourceId, SourceProtocolCall(PROBE_MEMBER, argumentsJson))) {
            is SourceOutcome.Success -> SourceOutcome.Success(SourceShape.decode(invoked.value))
            is SourceOutcome.Failure -> invoked
        }
    }

    private suspend fun invoke(sourceId: SourceId, call: SourceProtocolCall): SourceOutcome<String> {
        val result =
            runtime.invoke(
                SourceCall.InvokeFunction(
                    callId = "core-${sequence.incrementAndGet()}",
                    sourceId = sourceId,
                    functionName = call.member,
                    argumentsJson = call.argumentsJson,
                    timeoutMillis = timeoutMillis,
                ),
            )
        return when (result) {
            is SourceResult.Success -> SourceOutcome.Success(result.json)

            is SourceResult.Failure -> {
                // A source that is not loaded may have been reinstalled since it was described.
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
    )

    private data class ExplorePageRuntime(val index: Int, val usesLoadNext: Boolean)

    private companion object {
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

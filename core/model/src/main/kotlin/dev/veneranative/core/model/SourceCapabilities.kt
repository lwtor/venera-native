package dev.veneranative.core.model

/**
 * A capability a source may or may not implement.
 *
 * Upstream sources declare capabilities by simply implementing the corresponding members, so
 * anything missing must surface as "this feature is unavailable for this source" instead of as an
 * error on the whole source.
 */
enum class SourceCapability {
    EXPLORE,
    SEARCH,
    DETAIL,
    CHAPTERS,
    PAGES,
}

/**
 * What a loaded source can do, together with the filters it declares.
 *
 * [filters] applies to search; explore filters belong to the individual explore page once explore
 * pages are modelled.
 */
data class SourceCapabilities(
    val supported: Set<SourceCapability>,
    val filters: List<SourceFilter> = emptyList(),
) {
    fun supports(capability: SourceCapability): Boolean = capability in supported

    companion object {
        val None = SourceCapabilities(supported = emptySet())
    }
}

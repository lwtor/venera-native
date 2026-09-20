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
 * What a loaded source can do, together with what it declares.
 *
 * [explorePages] is empty when the source has no explore support; [searchFilters] applies to search,
 * because explore filters belong to the individual explore page.
 */
data class SourceCapabilities(
    val supported: Set<SourceCapability>,
    val explorePages: List<ExplorePage> = emptyList(),
    val searchFilters: List<SourceFilter> = emptyList(),
) {
    fun supports(capability: SourceCapability): Boolean = capability in supported

    /** The explore page registered under [key], or null when the source does not declare it. */
    fun explorePage(key: String): ExplorePage? = explorePages.firstOrNull { it.key == key }

    companion object {
        val None = SourceCapabilities(supported = emptySet())
    }
}

package dev.veneranative.core.model

/** One choice of a source filter, in the upstream `"value-text"` form. */
data class FilterOption(
    val value: String,
    val label: String,
) {
    init {
        require(value.isNotEmpty()) { "filter option value must not be empty" }
    }
}

/**
 * A filter a source declares for explore or search.
 *
 * The three kinds are not interchangeable: upstream passes their values to the source in different
 * shapes, which [selectionValue] reproduces.
 */
sealed interface SourceFilter {
    val key: String
    val label: String

    /** Exactly one value. */
    data class Select(
        override val key: String,
        override val label: String,
        val options: List<FilterOption>,
        val defaultValue: String? = null,
    ) : SourceFilter

    /** Zero or more values. */
    data class MultiSelect(
        override val key: String,
        override val label: String,
        val options: List<FilterOption>,
        val defaultValues: List<String> = emptyList(),
    ) : SourceFilter

    /** At most one value, and "nothing selected" is a meaningful state. */
    data class Dropdown(
        override val key: String,
        override val label: String,
        val options: List<FilterOption>,
        val defaultValue: String? = null,
    ) : SourceFilter

    /**
     * The value to send for this filter, or null when the filter must not be sent at all.
     *
     * Falls back to the declared default when the user has not chosen anything.
     */
    fun selectionValue(selection: FilterSelection): FilterValue? {
        val chosen = selection.values[key].orEmpty()
        return when (this) {
            is Select -> (chosen.firstOrNull() ?: defaultValue)?.let(FilterValue::Single)

            is MultiSelect -> FilterValue.Multiple(
                if (selection.values.containsKey(key)) chosen else defaultValues,
            )

            is Dropdown -> (chosen.firstOrNull() ?: defaultValue)
                ?.let(FilterValue::Single)
                ?: FilterValue.Unselected
        }
    }
}

/** What the user picked, keyed by filter key. */
data class FilterSelection(val values: Map<String, List<String>> = emptyMap()) {
    fun selected(key: String): List<String> = values[key].orEmpty()

    companion object {
        val Empty = FilterSelection()
    }
}

/**
 * Encodes a selection into the upstream `options` payload.
 *
 * Upstream passes search options as an array aligned with the source's `optionList`, so the order
 * comes from the declared filters — never from the selection map, which has no order. A null entry
 * means "do not send this filter".
 */
fun encodeFilterSelection(
    filters: List<SourceFilter>,
    selection: FilterSelection,
): List<FilterValue?> = filters.map { filter -> filter.selectionValue(selection) }

/**
 * The protocol shape of a filter value.
 *
 * Upstream encodes these three cases differently, and the difference is observable from the source
 * script, so it must survive the whole call path.
 */
sealed interface FilterValue {
    /** Sent as a string. */
    data class Single(val value: String) : FilterValue

    /**
     * Sent as an array serialised into a JSON string.
     *
     * Upstream hands `multi-select` values to the source as a JSON string, not as an array.
     */
    data class Multiple(val values: List<String>) : FilterValue

    /** Sent as JSON null, which is how upstream reports an unselected dropdown. */
    data object Unselected : FilterValue
}

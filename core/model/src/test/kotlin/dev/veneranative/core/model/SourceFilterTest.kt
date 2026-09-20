package dev.veneranative.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Filter value semantics taken from the upstream source protocol (see ADR-0007).
 *
 * The three filter kinds are observable from the script because upstream hands them over in
 * different shapes, so these rules must not be "simplified" later.
 */
class SourceFilterTest {

    private val sort = SourceFilter.Select(
        key = "sort",
        label = "sort",
        options = listOf(FilterOption("0", "time"), FilterOption("1", "popular")),
        defaultValue = "0",
    )

    private val genres = SourceFilter.MultiSelect(
        key = "genre",
        label = "genre",
        options = listOf(FilterOption("action", "Action"), FilterOption("comedy", "Comedy")),
        defaultValues = listOf("action"),
    )

    private val status = SourceFilter.Dropdown(
        key = "status",
        label = "status",
        options = listOf(FilterOption("ongoing", "Ongoing"), FilterOption("done", "Done")),
    )

    @Test
    fun `a select sends the chosen value as a single value`() {
        val value = sort.selectionValue(FilterSelection(mapOf("sort" to listOf("1"))))

        assertEquals(FilterValue.Single("1"), value)
    }

    @Test
    fun `a select falls back to its default`() {
        assertEquals(FilterValue.Single("0"), sort.selectionValue(FilterSelection.Empty))
    }

    @Test
    fun `a select without a default is not sent at all`() {
        val noDefault = SourceFilter.Select(key = "x", label = "x", options = emptyList())

        assertNull(noDefault.selectionValue(FilterSelection.Empty))
    }

    @Test
    fun `a multi select sends every chosen value in order`() {
        val value = genres.selectionValue(FilterSelection(mapOf("genre" to listOf("comedy", "action"))))

        assertEquals(FilterValue.Multiple(listOf("comedy", "action")), value)
    }

    @Test
    fun `a multi select falls back to its defaults only when the filter was never touched`() {
        assertEquals(FilterValue.Multiple(listOf("action")), genres.selectionValue(FilterSelection.Empty))

        // The user cleared the selection: the empty choice is deliberate, not a missing value.
        val cleared = FilterSelection(mapOf("genre" to emptyList()))
        assertEquals(FilterValue.Multiple(emptyList()), genres.selectionValue(cleared))
    }

    @Test
    fun `an unselected dropdown is sent as null rather than omitted`() {
        assertEquals(FilterValue.Unselected, status.selectionValue(FilterSelection.Empty))
    }

    @Test
    fun `a dropdown sends its chosen value`() {
        val value = status.selectionValue(FilterSelection(mapOf("status" to listOf("done"))))

        assertEquals(FilterValue.Single("done"), value)
    }

    @Test
    fun `the payload follows the declared filter order, not the selection order`() {
        val selection = FilterSelection(
            linkedMapOf(
                "status" to listOf("done"),
                "sort" to listOf("1"),
                "genre" to listOf("comedy", "action"),
            ),
        )

        val payload = encodeFilterSelection(listOf(sort, genres, status), selection)

        assertEquals(
            listOf(
                FilterValue.Single("1"),
                FilterValue.Multiple(listOf("comedy", "action")),
                FilterValue.Single("done"),
            ),
            payload,
        )
    }

    @Test
    fun `a filter that is not sent occupies its slot as null`() {
        val payload = encodeFilterSelection(listOf(sort, status), FilterSelection.Empty)

        // The dropdown explicitly reports "unselected" instead of being cancelled out.
        assertEquals(listOf(FilterValue.Single("0"), FilterValue.Unselected), payload)
    }

    @Test
    fun `a value chosen outside the declared options is still forwarded`() {
        // Sources accept arbitrary values for some filters, so the model must not silently drop them.
        val value = genres.selectionValue(FilterSelection(mapOf("genre" to listOf("isekai"))))

        assertEquals(FilterValue.Multiple(listOf("isekai")), value)
    }
}

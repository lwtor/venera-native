package dev.veneranative.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderComparatorTest {
    @Test fun numericRunsSortNaturally() {
        assertEquals(listOf("1", "2", "10"), listOf("10", "2", "1").sortedWith(NaturalOrderComparator))
        assertEquals(listOf("a1", "a1b", "a2"), listOf("a2", "a1b", "a1").sortedWith(NaturalOrderComparator))
        assertEquals(listOf("v01", "v02", "v10"), listOf("v10", "v02", "v01").sortedWith(NaturalOrderComparator))
    }
    @Test fun emptyAndEqualValuesAreStable() {
        assertEquals(listOf("", "A", "a"), listOf("A", "a", "").sortedWith(NaturalOrderComparator))
        assertEquals(-1, NaturalOrderComparator.compare("Chapter 2", "chapter 10"))
    }
}

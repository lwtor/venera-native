package dev.veneranative.core.model

import org.junit.Test

class TagNavigationTest {
    @Test(expected = IllegalArgumentException::class)
    fun `search target requires a nonblank keyword`() { TagNavigation.Search(" ") }

    @Test(expected = IllegalArgumentException::class)
    fun `category target requires a nonblank category`() { TagNavigation.Category("") }
}

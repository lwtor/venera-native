package dev.veneranative.core.model

/** A source decides how a detail tag opens related works; raw source values are preserved. */
sealed interface TagNavigation {
    val keyword: String
    val options: List<String>
    data class Search(override val keyword: String, override val options: List<String> = emptyList(), val authorId: String? = null) : TagNavigation {
        init { require(keyword.isNotBlank()); require(authorId == null || authorId.isNotBlank()) }
    }
    data class Category(override val keyword: String, val param: String? = null, override val options: List<String> = emptyList()) : TagNavigation {
        init { require(keyword.isNotBlank()) }
    }
}

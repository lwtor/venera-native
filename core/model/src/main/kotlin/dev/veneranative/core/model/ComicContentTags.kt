package dev.veneranative.core.model

/** Content categories only; author, status and update groups are separate fields. */
fun ComicDetail.contentTags(): List<String> {
    val groups = tagGroups.filterKeys { it.lowercase() in setOf("tag", "tags", "genre", "genres", "theme", "themes", "category", "categories", "标签", "標籤", "题材", "題材", "主题", "主題", "类型", "類型", "分类", "分類") }
    return (groups.values.flatten() + comic.tags).map(String::trim).filter(String::isNotBlank).distinct()
}

package dev.veneranative.core.model

/** Compact source directory entries, retaining version identity and names rather than summed counts. */
data class ComicChapterEntry(val id: String, val title: String, val version: String?)
data class ComicReadingChapter(val id: String, val title: String, val version: String?)

private val numberedChapter = Regex("""^(?:第\s*)?(\d+(?:\.\d+)?)\s*[话話章回](?:\s|$|[^\d]).*""")
private val englishChapter = Regex("""^(?:chapter|ch\.?|episode|ep\.?)\s*(\d+(?:\.\d+)?)(?:\s|$|[^\d]).*""", RegexOption.IGNORE_CASE)
private val bareChapter = Regex("""^\d+(?:\.\d+)?$""")

private fun chapterNumber(title: String): java.math.BigDecimal? {
    val text = title.trim()
    val value = numberedChapter.matchEntire(text)?.groupValues?.get(1)
        ?: englishChapter.matchEntire(text)?.groupValues?.get(1)
        ?: text.takeIf { bareChapter.matches(it) }
    return value?.toBigDecimalOrNull()
}

/** Unread: highest numbered chapter across versions. Read: exclusively the reader's version. */
fun comicChapterProgressLabel(chapters: List<ComicChapterEntry>, reading: ComicReadingChapter?): String {
    val versions = chapters.groupBy { it.version }
    val candidates = if (reading == null) chapters else {
        if (reading.version != null) versions[reading.version].orEmpty()
        else {
            val matchedVersions = chapters.filter { it.id == reading.id }.map { it.version }.distinct()
            when {
                matchedVersions.size == 1 -> versions[matchedVersions.single()].orEmpty()
                versions.size == 1 -> versions.values.single()
                else -> emptyList()
            }
        }
    }
    val number = candidates.mapNotNull { chapterNumber(it.title) }.maxOrNull()
    val total = when {
        number != null -> "共${number.stripTrailingZeros().toPlainString()}话"
        candidates.isEmpty() -> "总章节未知"
        else -> "共${candidates.groupBy { it.version }.values.maxOf { it.size }}章"
    }
    if (reading == null) return total
    val current = chapterNumber(reading.title)?.let { "第${it.stripTrailingZeros().toPlainString()}话" } ?: reading.title
    return "$current / $total"
}

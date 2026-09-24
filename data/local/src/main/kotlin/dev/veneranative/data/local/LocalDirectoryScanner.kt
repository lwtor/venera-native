package dev.veneranative.data.local

/** Small immutable tree snapshot so directory classification and ordering remain JVM-testable. */
data class TreeNode(
    val uri: String,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long = 0,
    val children: List<TreeNode> = emptyList(),
)

data class ScannedPage(val uri: String, val name: String, val sizeBytes: Long)
data class ScannedChapter(val id: String, val title: String, val entryName: String?, val pages: List<ScannedPage>)
data class ScannedTree(val title: String, val coverUri: String?, val chapters: List<ScannedChapter>)

class LocalDirectoryScanner {
    fun scan(root: TreeNode): ScannedTree {
        require(root.directory) { "selected document is not a directory" }
        val files = root.children.filter { !it.directory && isImage(it.name) }
        val dirs = root.children.filter { it.directory }
        val chapterNodes = (if (files.any { !isCover(it.name) }) listOf(root) else emptyList()) +
            dirs.sortedWith(compareBy(NaturalOrderComparator) { it.name })
        val chapters = chapterNodes.mapNotNull { chapter ->
            val pageNodes = (if (chapter == root) root.children else chapter.children)
                .filter { !it.directory && isImage(it.name) && !(chapter == root && isCover(it.name)) }
                .sortedWith(compareBy(NaturalOrderComparator) { it.name })
            if (pageNodes.isEmpty()) null else ScannedChapter(
                id = stableId(chapter.uri),
                title = if (chapter == root) root.name.ifBlank { "Chapter" } else chapter.name,
                entryName = if (chapter == root) null else chapter.name,
                pages = pageNodes.map { ScannedPage(it.uri, it.name, it.sizeBytes) },
            )
        }
        val coverUri = LocalCoverResolver.resolve(root.children, chapters)
        return ScannedTree(root.name.ifBlank { "Local comic" }, coverUri, chapters)
    }

    private fun isCover(name: String): Boolean = name.substringBeforeLast('.', "").equals("cover", true)

    private fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
        internal fun stableId(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}

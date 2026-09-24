package dev.veneranative.data.local

/** Picks a root cover by name, otherwise the first naturally ordered page in chapter order. */
object LocalCoverResolver {
    fun resolve(rootChildren: List<TreeNode>, chapters: List<ScannedChapter>): String? =
        rootChildren.firstOrNull { !it.directory && it.name.substringBeforeLast('.', "").equals("cover", true) }?.uri
            ?: chapters.firstOrNull()?.pages?.firstOrNull()?.uri
}

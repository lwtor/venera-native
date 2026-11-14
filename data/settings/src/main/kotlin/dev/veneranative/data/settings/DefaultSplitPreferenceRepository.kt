package dev.veneranative.data.settings

import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicRef
import dev.veneranative.core.model.PageSplit
import dev.veneranative.core.model.SplitPreferenceRepository
import dev.veneranative.core.model.stableId

/**
 * [SplitPreferenceRepository] backed by the same small string store used for screen preferences.
 *
 * Reusing [ScreenPreferenceRepository] keeps the split feature on the existing Room table and lets
 * the encoding logic stay a plain, JVM-testable mapping from a [ChapterRef] or [ComicRef] to stable
 * string keys. Chapter settings are keyed by the chapter, while the gutter overlap and half order
 * are keyed by the comic so every chapter of the same comic splits the same way.
 */
class DefaultSplitPreferenceRepository(
    private val preferences: ScreenPreferenceRepository,
) : SplitPreferenceRepository {

    override suspend fun isSplitEnabled(chapterRef: ChapterRef): Boolean =
        preferences.get(enabledKey(chapterRef)) == ENABLED_VALUE

    override suspend fun setSplitEnabled(chapterRef: ChapterRef, enabled: Boolean) {
        val key = enabledKey(chapterRef)
        if (enabled) preferences.put(key, ENABLED_VALUE) else preferences.remove(key)
    }

    override suspend fun isPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int): Boolean =
        preferences.get(excludeKey(chapterRef, sourcePageIndex)) == ENABLED_VALUE

    override suspend fun setPageExcluded(chapterRef: ChapterRef, sourcePageIndex: Int, excluded: Boolean) {
        val key = excludeKey(chapterRef, sourcePageIndex)
        if (excluded) preferences.put(key, ENABLED_VALUE) else preferences.remove(key)
    }

    override suspend fun overlapFraction(comicRef: ComicRef): Float =
        preferences.get(overlapKey(comicRef))?.toFloatOrNull() ?: PageSplit.DEFAULT_OVERLAP_FRACTION

    override suspend fun setOverlapFraction(comicRef: ComicRef, fraction: Float) {
        preferences.put(overlapKey(comicRef), fraction.toString())
    }

    override suspend fun isRightToLeft(comicRef: ComicRef): Boolean =
        preferences.get(orderKey(comicRef)) == ENABLED_VALUE

    override suspend fun setRightToLeft(comicRef: ComicRef, rightToLeft: Boolean) {
        preferences.put(orderKey(comicRef), if (rightToLeft) ENABLED_VALUE else DISABLED_VALUE)
    }

    private fun enabledKey(chapterRef: ChapterRef): String = "split:${chapterRef.stableId()}:enabled"

    private fun excludeKey(chapterRef: ChapterRef, sourcePageIndex: Int): String =
        "split:${chapterRef.stableId()}:exclude:$sourcePageIndex"

    private fun overlapKey(comicRef: ComicRef): String = "split:comic:${comicRef.stableId()}:overlap"

    private fun orderKey(comicRef: ComicRef): String = "split:comic:${comicRef.stableId()}:r2l"

    private companion object {
        const val ENABLED_VALUE = "1"
        const val DISABLED_VALUE = "0"
    }
}

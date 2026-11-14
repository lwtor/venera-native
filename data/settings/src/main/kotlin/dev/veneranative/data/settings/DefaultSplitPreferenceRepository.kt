package dev.veneranative.data.settings

import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.SplitPreferenceRepository
import dev.veneranative.core.model.stableId

/**
 * [SplitPreferenceRepository] backed by the same small string store used for screen preferences.
 *
 * Reusing [ScreenPreferenceRepository] keeps the split feature on the existing Room table and lets
 * the encoding logic stay a plain, JVM-testable mapping from a [ChapterRef] to stable string keys.
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

    private fun enabledKey(chapterRef: ChapterRef): String = "split:${chapterRef.stableId()}:enabled"

    private fun excludeKey(chapterRef: ChapterRef, sourcePageIndex: Int): String =
        "split:${chapterRef.stableId()}:exclude:$sourcePageIndex"

    private companion object {
        const val ENABLED_VALUE = "1"
    }
}

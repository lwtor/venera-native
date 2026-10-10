package dev.veneranative.feature.details

import dev.veneranative.core.model.Chapter
import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicDetail
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.data.collection.FavoriteFolder

enum class FavoriteDialog { Add, Remove }

/**
 * Everything the details screen renders.
 *
 * Chapters arrive together with the details — the source answers both in one call — so there is no
 * separate chapter status: a comic either has details or it does not.
 */
data class DetailsUiState(
    val status: DetailsStatus = DetailsStatus.Loading,
    val detail: ComicDetail? = null,
    /** Name of the source the comic came from, once it is known to be installed and enabled. */
    val sourceName: String? = null,
    /** Last chapter recorded by the reader, if this comic has reading history. */
    val lastReadChapterId: RemoteChapterId? = null,
    /** A pending, consumable request to reveal the current chapter in the directory. */
    val chapterLocationRequest: Long? = null,
    /** Chapters whose persisted position reached the final page. */
    val readChapterIds: Set<RemoteChapterId> = emptySet(),
    /** Chapters whose full download has completed for this exact source version. */
    val downloadedChapters: Set<ChapterRef.Remote> = emptySet(),
    /** The group the list is narrowed to; null shows every group. */
    val selectedGroup: String? = null,
    val order: ChapterOrder = ChapterOrder.SourceOrder,
    val chapterQuery: String = "",
    val descriptionExpanded: Boolean = false,
    val isChapterSelectionMode: Boolean = false,
    val selectedChapters: Set<ChapterKey> = emptySet(),
    val isBatchDownloading: Boolean = false,
    /**
     * Whether the comic is on the user's shelf. Read from the shelf rather than remembered from the
     * last tap, so removing it there shows up here.
     */
    val isFavorite: Boolean = false,
    val favoriteFolders: List<FavoriteFolder> = emptyList(),
    val favoriteDialog: FavoriteDialog? = null,
    val favoriteFolderSelection: Set<String> = emptySet(),
    val newFavoriteFolderDraft: String? = null,
    val favoriteSaving: Boolean = false,
    /** False until the assembly layer hands the screen a shelf: until then there is none to use. */
    val hasShelf: Boolean = false,
    /** Product copy when keeping or removing the comic failed; never a lower layer's wording. */
    val shelfMessage: String? = null,
    val downloadMessage: String? = null,
    val downloadQueueVersion: Int = 0,
    /** Product copy for the last failure; never a lower layer's wording. */
    val message: String? = null,
) {
    val title: String get() = detail?.comic?.title.orEmpty()

    private val chapters: List<Chapter> get() = detail?.chapters.orEmpty()

    // Matches the reader's existing resume behavior; history currently stores no version group.
    val currentReadingChapter: Chapter?
        get() = chapters.firstOrNull { it.key.remoteId == lastReadChapterId }

    /** Groups the source declared, in the order it declared them. */
    val groups: List<String> get() = chapters.mapNotNull { it.group }.distinct()

    val hasChapters: Boolean get() = chapters.isNotEmpty()

    /** The source answered but listed no chapters: a partial result, not a failure. */
    val hasNoChapters: Boolean get() = status == DetailsStatus.Ready && !hasChapters

    /** True when the list needs group headers, which it does only while every group is shown. */
    val groupsTheList: Boolean get() = selectedGroup == null && groups.isNotEmpty()

    /**
     * The chapters to render.
     *
     * The order is the source's own unless the user asked for the reverse. `Chapter.index` is a
     * position in the source's order and some sources publish newest-first, so re-sorting by title or
     * number would present an order the source never described.
     */
    val visibleChapters: List<Chapter>
        get() {
            val group = selectedGroup
            val narrowed = if (group == null) chapters else chapters.filter { it.group == group }
            return when (order) {
                ChapterOrder.SourceOrder -> narrowed
                ChapterOrder.Reversed -> narrowed.reversed()
            }
        }

    val filteredChapters: List<Chapter>
        get() = chapterQuery.trim().takeIf { it.isNotEmpty() }?.let { query ->
            visibleChapters.filter { it.title.contains(query, ignoreCase = true) }
        } ?: visibleChapters

    val selectableChapters: List<Chapter>
        get() = filteredChapters.filterNot(::isDownloaded)

    /** Legacy downloads had no version group; infer one only when this chapter key is unambiguous. */
    fun isDownloaded(chapter: Chapter): Boolean {
        val ref = ChapterRef.Remote(chapter.key, chapter.group)
        if (ref in downloadedChapters) return true
        if (chapter.group == null || downloadedChapters.none {
                it.key == chapter.key && it.group == null
            }
        ) return false
        return chapters.count { it.key == chapter.key } == 1
    }
}

enum class DetailsStatus {
    Loading,
    Ready,

    /** The source could not answer; retrying is meaningful. */
    Failed,

    /** The source was uninstalled or switched off; only going back is meaningful. */
    SourceUnavailable,
}

/** Display order of the chapter list. */
enum class ChapterOrder {
    SourceOrder,
    Reversed,
}

package dev.veneranative.data.local

import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import kotlinx.coroutines.flow.Flow

enum class LocalKind { Directory, Archive }
data class LocalComic(
    val id: LocalComicId, val title: String, val kind: LocalKind, val rootUri: String,
    val coverPath: String?, val chapterCount: Int, val addedAtEpochMillis: Long,
)
data class LocalChapter(val id: LocalChapterId, val comicId: LocalComicId, val title: String, val index: Int)
data class LocalPage(val comicId: LocalComicId, val chapterId: LocalChapterId, val index: Int, val uri: String, val name: String, val sizeBytes: Long)
data class SafGrant(val uri: String, val kind: LocalKind, val grantedAtEpochMillis: Long)
sealed interface LocalImportResult {
    data class Imported(val comic: LocalComic, val pageCount: Int) : LocalImportResult
    data object Empty : LocalImportResult
    data object PermissionLost : LocalImportResult
    data object Unavailable : LocalImportResult
}
interface LocalComicRepository {
    fun observeComics(): Flow<List<LocalComic>>
    fun observeChapters(comicId: LocalComicId): Flow<List<LocalChapter>>
    suspend fun pages(comicId: LocalComicId, chapterId: LocalChapterId): List<LocalPage>
    suspend fun importTree(uri: String): LocalImportResult
    suspend fun remove(comicId: LocalComicId)
    suspend fun refresh(comicId: LocalComicId)
    suspend fun grants(): List<SafGrant>
    suspend fun releaseGrant(uri: String)
}

package dev.veneranative.data.local

import androidx.room.withTransaction
import dev.veneranative.core.database.LocalChapterEntity
import dev.veneranative.core.database.LocalComicEntity
import dev.veneranative.core.database.LocalDao
import dev.veneranative.core.database.LocalGrantEntity
import dev.veneranative.core.database.LocalPageEntity
import dev.veneranative.core.database.VeneraDatabase
import dev.veneranative.core.model.LocalChapterId
import dev.veneranative.core.model.LocalComicId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DefaultLocalComicRepository(
    private val database: VeneraDatabase,
    private val access: SafTreeAccess,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scanner: LocalDirectoryScanner = LocalDirectoryScanner(),
) : LocalComicRepository {
    private val dao: LocalDao get() = database.localDao()

    override fun observeComics(): Flow<List<LocalComic>> = dao.observeComics().map { rows -> rows.mapNotNull { it.toDomain() } }
    override fun observeChapters(comicId: LocalComicId): Flow<List<LocalChapter>> =
        dao.observeChapters(comicId.value).map { rows -> rows.map { LocalChapter(LocalChapterId(it.chapterId), comicId, it.title, it.sortIndex) } }
    override suspend fun pages(comicId: LocalComicId, chapterId: LocalChapterId): List<LocalPage> =
        withContext(Dispatchers.IO) { dao.pages(comicId.value, chapterId.value).map { LocalPage(comicId, chapterId, it.pageIndex, it.entryName, it.displayName, it.sizeBytes) } }

    override suspend fun importTree(uri: String): LocalImportResult = withContext(Dispatchers.IO) {
        val root = try { access.read(uri) } catch (_: SecurityException) { return@withContext LocalImportResult.PermissionLost }
            ?: return@withContext LocalImportResult.Unavailable
        val tree = scanner.scan(root)
        if (tree.chapters.isEmpty()) return@withContext LocalImportResult.Empty
        try { access.take(uri) } catch (_: SecurityException) { return@withContext LocalImportResult.PermissionLost }
        val comicId = LocalDirectoryScanner.stableId(uri)
        val existing = dao.comic(comicId)
        val comic = LocalComicEntity(comicId, tree.title, LocalKind.Directory.name, uri, tree.coverUri,
            tree.chapters.size, existing?.addedAt ?: clock())
        database.withTransaction {
            dao.upsertGrant(LocalGrantEntity(uri, "tree", existing?.addedAt ?: clock()))
            dao.deletePages(comicId)
            dao.deleteChapters(comicId)
            dao.upsertComic(comic)
            val chapters = tree.chapters.mapIndexed { index, chapter ->
                LocalChapterEntity(comicId, chapter.id, chapter.title, index, chapter.entryName)
            }
            dao.upsertChapters(chapters)
            dao.upsertPages(tree.chapters.flatMap { chapter -> chapter.pages.mapIndexed { index, page ->
                LocalPageEntity(comicId, chapter.id, index, page.uri, page.name, page.sizeBytes)
            } })
        }
        LocalImportResult.Imported(comic.toDomain()!!, tree.chapters.sumOf { it.pages.size })
    }

    override suspend fun remove(comicId: LocalComicId) = withContext(Dispatchers.IO) {
        val comic = dao.comic(comicId.value) ?: return@withContext
        database.withTransaction {
            dao.deletePages(comicId.value); dao.deleteChapters(comicId.value); dao.deleteComic(comicId.value)
            if (dao.comicForGrant(comic.rootUri) == null) dao.deleteGrant(comic.rootUri)
        }
        if (dao.comicForGrant(comic.rootUri) == null) runCatching { access.release(comic.rootUri) }
    }

    override suspend fun refresh(comicId: LocalComicId) = withContext(Dispatchers.IO) {
        val comic = dao.comic(comicId.value) ?: return@withContext
        val tree = try { access.read(comic.rootUri) } catch (_: SecurityException) { return@withContext }
        if (tree == null || scanner.scan(tree).chapters.isEmpty()) {
            remove(comicId)
        } else {
            importTree(comic.rootUri)
        }
    }
    override suspend fun grants(): List<SafGrant> = dao.grants().map {
        SafGrant(it.uri, if (it.kind == "archive") LocalKind.Archive else LocalKind.Directory, it.grantedAt)
    }
    override suspend fun releaseGrant(uri: String) = withContext(Dispatchers.IO) {
        if (dao.comicForGrant(uri) != null) return@withContext
        runCatching { access.release(uri) }
        dao.deleteGrant(uri)
    }
}

private fun LocalComicEntity.toDomain(): LocalComic? = runCatching {
    LocalComic(LocalComicId(comicId), title, LocalKind.valueOf(kind), rootUri, coverPath, chapterCount, addedAt)
}.getOrNull()

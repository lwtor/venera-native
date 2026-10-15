package dev.veneranative.core.image

import android.graphics.BitmapFactory
import dev.veneranative.core.model.ComicKey
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Same source/comic hashes as existing downloads. Never renames or deletes chapter directories. */
class ComicResourceStore(
    private val root: File,
    private val validate: (File) -> Boolean = ::validCoverImage,
) {
    fun comicDir(key: ComicKey): File = File(root, "${stableResourceId(key.sourceId.value)}/${stableResourceId(key.remoteId.value)}")
    private fun coverDir(key: ComicKey): File = File(comicDir(key), "covers").also {
        require(it.canonicalPath.startsWith(root.canonicalPath + File.separator))
    }
    fun openCover(key: ComicKey): ComicImageFile? {
        val file = File(coverDir(key), "cover.image")
        return if (file.isFile && validate(file)) ComicImageFile(file, null) else null
    }
    fun fetchedAt(key: ComicKey, url: String): Long? = try {
        val lines = File(coverDir(key), "source.sha256").readLines()
        if (lines.firstOrNull() == digest(url)) lines.getOrNull(1)?.toLongOrNull() else null
    } catch (_: java.io.IOException) { null }

    suspend fun saveCover(key: ComicKey, url: String, source: File, fetchedAt: Long): Boolean {
        val dir = coverDir(key)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val part = File.createTempFile("cover-", ".part", dir)
        try {
            FileOutputStream(part).use { sink ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_COVER_BYTES) return false
                        sink.write(buffer, 0, count)
                    }
                }
                sink.fd.sync()
            }
            if (!validate(part)) return false
            currentCoroutineContext().ensureActive()
            // Same-directory atomic replace: readers keep either the old or the new complete image.
            Files.move(part.toPath(), File(dir, "cover.image").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            val metadata = File.createTempFile("source-", ".part", dir)
            try {
                metadata.writeText(digest(url) + "\n" + fetchedAt)
                Files.move(metadata.toPath(), File(dir, "source.sha256").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { metadata.delete() }
            return true
        } finally { part.delete() }
    }
    companion object { const val MAX_COVER_BYTES = 8L * 1024 * 1024 }
}

fun stableResourceId(value: String): String = digest(value).take(16)
private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun validCoverImage(file: File): Boolean {
    if (file.length() !in 1..ComicResourceStore.MAX_COVER_BYTES) return false
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 32_000_000) return false
    val sample = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1) }
    val decoded = BitmapFactory.decodeFile(file.path, sample) ?: return false
    decoded.recycle()
    return true
}

class PersistentComicCoverStorage(
    private val resources: ComicResourceStore,
    private val pipeline: ComicImagePipeline,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : dev.veneranative.core.model.ComicCoverStorage {
    private val locks = Array(32) { Mutex() }
    override suspend fun available(key: ComicKey): Boolean = withContext(io) { resources.openCover(key)?.use { true } ?: false }
    override suspend fun retain(key: ComicKey, url: String, force: Boolean): Long? = withContext(io) {
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (!force && available(key)) resources.fetchedAt(key, url)?.let { return@withLock it }
            val now = clock()
            val variant = if (force) "saved-cover-$now" else "comic-cover"
            val request = ComicImageRequest(url, sourceId = key.sourceId, variant = variant)
            try {
                val image = pipeline.cachedFileOf(request) ?: return@withLock null
                image.use { if (resources.saveCover(key, url, it.file, now)) now else null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { null }
        }
    }
}

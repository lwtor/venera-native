package dev.veneranative.data.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Where a downloaded page lives, and the only writer allowed to put one there.
 *
 * Directory names are hashes of the ids rather than the ids themselves: source, comic and chapter
 * ids come from scripts and contain slashes, dots and characters no filesystem agrees about, while a
 * hex hash is the same length everywhere and cannot escape the directory it belongs in.
 *
 * Writes go to a `.part` file and are renamed only once the bytes are on disk. A page is either the
 * file at its final name or nothing at all — never a half-written file that a later scan would
 * happily count as a finished page.
 */
class DownloadFileLayout(
    private val root: File,
) {

    fun comicDir(sourceId: String, comicId: String): File =
        File(root, "${stableId(sourceId)}/${stableId(comicId)}")

    fun chapterDir(sourceId: String, comicId: String, chapterId: String): File =
        File(comicDir(sourceId, comicId), "chapters/${stableId(chapterId)}")

    fun pagesDir(sourceId: String, comicId: String, chapterId: String): File =
        File(chapterDir(sourceId, comicId, chapterId), PAGES_DIR)

    fun manifestFile(sourceId: String, comicId: String, chapterId: String): File =
        File(chapterDir(sourceId, comicId, chapterId), MANIFEST_NAME)

    fun pageFile(sourceId: String, comicId: String, chapterId: String, index: Int): File =
        File(pagesDir(sourceId, comicId, chapterId), pageFileName(index))

    /** The path as stored: relative, so it survives the download root moving. */
    fun relativePathOf(sourceId: String, comicId: String, chapterId: String, index: Int): String =
        "${stableId(sourceId)}/${stableId(comicId)}/chapters/${stableId(chapterId)}" +
            "/$PAGES_DIR/${pageFileName(index)}"

    fun absoluteOf(relativePath: String): File = File(root, relativePath)

    /** The inverse of [absoluteOf], used to report a file without leaking where it lives. */
    fun relativeOf(file: File): String =
        file.absolutePath.removePrefix("${root.absolutePath}/")

    fun pageFileName(index: Int): String = "p${index.toString().padStart(5, '0')}$PAGE_EXTENSION"

    /** Every `chapter.json` under the root, which is what lets recovery rebuild a lost database. */
    fun manifestFiles(): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.name == MANIFEST_NAME }
            .toList()

    /** Every page file under the root, complete or not. */
    fun pageFiles(): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(PAGE_EXTENSION) }
            .toList()

    /** Removes a chapter's directory: a cancelled download leaves nothing behind. */
    fun deleteChapterFiles(sourceId: String, comicId: String, chapterId: String) {
        chapterDir(sourceId, comicId, chapterId).deleteRecursively()
    }

    /**
     * Writes [bytes] to [target] atomically and returns how many bytes landed.
     *
     * The caller only ever sees the file after the rename, so a crash mid-write shows up as no file
     * rather than as a short page.
     */
    suspend fun writeAtomically(target: File, bytes: ByteArray): Long =
        writeAtomically(target) { sink -> sink.write(bytes) }

    /**
     * Suspending because the bytes usually come from a network call: the writer takes the stream
     * while that call is in flight rather than after it has been buffered into memory.
     */
    suspend fun writeAtomically(target: File, write: suspend (OutputStream) -> Unit): Long {
        val part = partFileOf(requireInsideRoot(target))
        target.parentFile?.mkdirs()
        var written = 0L
        try {
            FileOutputStream(part).use { raw ->
                val counting = CountingOutputStream(raw.buffered())
                write(counting)
                counting.flush()
                // The bytes have to be on disk before anything is allowed to call them a page.
                raw.fd.sync()
                written = counting.count
            }
            if (!part.renameTo(target)) throw IOException("could not move $part into place")
        } finally {
            // A leftover .part is not a page: it is debris from a run that never finished.
            if (part.exists() && !part.delete()) Unit
        }
        return written
    }

    /** Rejects a path that would write outside the download root. */
    fun isInsideRoot(file: File): Boolean =
        file.canonicalPath.startsWith(root.canonicalPath + File.separator)

    /** A short, filesystem-safe name for any id, however long or hostile. */
    fun stableId(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }.substring(0, HASH_LENGTH)
    }

    private fun requireInsideRoot(target: File): File {
        check(isInsideRoot(target)) { "refusing to write outside of the download root: $target" }
        return target
    }

    private fun partFileOf(target: File): File = File(target.parentFile, "${target.name}$PART_SUFFIX")

    private class CountingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {

        var count: Long = 0L
            private set

        override fun write(byte: Int) {
            delegate.write(byte)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            delegate.write(bytes, offset, length)
            count += length
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    private companion object {
        const val PAGES_DIR = "pages"
        const val MANIFEST_NAME = "chapter.json"
        const val PAGE_EXTENSION = ".bin"
        const val PART_SUFFIX = ".part"
        const val HASH_LENGTH = 16
    }
}

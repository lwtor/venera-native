package dev.veneranative.data.local

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Bounded materialization for SAF and archive pages; returned files remain valid until evicted. */
class LocalPageCache(private val directory: File, private val maxBytes: Long = 256L * 1024 * 1024, private val maxEntryBytes: Long = 100L * 1024 * 1024) {
    init { require(maxBytes > 0 && maxEntryBytes > 0 && maxEntryBytes <= maxBytes) }

    @Synchronized
    fun materialize(key: String, source: () -> InputStream): File {
        directory.mkdirs()
        val target = File(directory, digest(key) + ".page")
        if (target.isFile && target.length() in 1..maxEntryBytes) { target.setLastModified(System.currentTimeMillis()); return target }
        target.delete()
        val part = File(directory, target.name + ".part")
        try {
            var size = 0L
            source().use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        require(size <= maxEntryBytes) { "page exceeds cache entry limit" }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            require(size > 0) { "empty page" }
            if (!part.renameTo(target)) throw java.io.IOException("cache commit failed")
            evict(except = target)
            return target
        } catch (failure: Throwable) {
            part.delete()
            throw failure
        }
    }

    private fun evict(except: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "page" }.orEmpty()
        var total = files.sumOf { it.length() }
        files.filter { it != except }.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return
            total -= file.length(); file.delete()
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(16).joinToString("") { "%02x".format(it) }
}

package dev.veneranative.app

import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** Bounded app-private diagnostics for JM page fetch, sizing, and decode failures. */
internal class LocalJmReaderLog(
    filesDirectory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val directory = File(filesDirectory, "diagnostics")
    private val current = File(directory, FILE_NAME)
    private val previous = File(directory, "$FILE_NAME.1")

    @Synchronized
    fun append(event: String) {
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) return
            if (current.length() >= maxBytes) {
                previous.delete()
                if (!current.renameTo(previous)) current.delete()
            }
            FileOutputStream(current, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.append(Instant.now().toString())
                writer.append(' ')
                writer.append(event.filterNot(Char::isISOControl))
                writer.newLine()
            }
        }
    }

    internal fun currentFile(): File = current

    private companion object {
        const val FILE_NAME = "jm-reader.log"
        const val DEFAULT_MAX_BYTES = 512 * 1024L
    }
}

package dev.veneranative.app

import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** App-private bounded diagnostics for downloaded chapter matching and local reader decisions. */
internal class LocalOfflineReaderLog(
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

    private companion object {
        const val FILE_NAME = "offline-reader.log"
        const val DEFAULT_MAX_BYTES = 512 * 1024L
    }
}

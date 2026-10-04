package dev.veneranative.app

import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** Small rotating, app-private diagnostic log for Komiic transport failures. */
internal class LocalKomiicNetworkLog(
    private val filesDirectory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val logDirectory = File(filesDirectory, DIRECTORY_NAME)
    private val logFile = File(logDirectory, FILE_NAME)
    private val previousFile = File(logDirectory, "$FILE_NAME.1")

    @Synchronized
    fun append(message: String) {
        runCatching {
            if (!logDirectory.exists() && !logDirectory.mkdirs()) return
            if (logFile.length() >= maxBytes) {
                previousFile.delete()
                if (!logFile.renameTo(previousFile)) logFile.delete()
            }
            FileOutputStream(logFile, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.append(Instant.now().toString())
                writer.append(' ')
                writer.append(message.filterNot(Char::isISOControl))
                writer.newLine()
            }
        }
    }

    internal fun currentFile(): File = logFile

    private companion object {
        const val DIRECTORY_NAME = "diagnostics"
        const val FILE_NAME = "komiic-network.log"
        const val DEFAULT_MAX_BYTES = 512 * 1024L
    }
}

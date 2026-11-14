package dev.veneranative.data.update

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

class ApkDownloadException(message: String) : IOException(message)

data class ApkDownloadProgress(val bytesDownloaded: Long, val totalBytes: Long?) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toDouble() / it).toFloat() }
}

/**
 * Streams a release APK to a local file with byte-level progress. The bytes go to a `.part`
 * sibling first and are moved into place only after the declared length and the ZIP magic are
 * verified, mirroring the atomic pattern of the comic download pipeline without its
 * download-root restriction.
 */
class ApkDownloader(
    baseClient: OkHttpClient,
    private val progressIntervalBytes: Long = DEFAULT_PROGRESS_INTERVAL_BYTES,
) {
    private val httpClient = baseClient.newBuilder()
        // One APK download may legitimately take minutes; the call has no overall deadline and
        // each individual read gets a generous per-read timeout instead.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Downloads [url] into [target]; returns [target] once the file is verified and in place. */
    suspend fun download(url: String, target: File, onProgress: (ApkDownloadProgress) -> Unit = {}): File {
        val parent = target.parentFile ?: throw ApkDownloadException("target has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw ApkDownloadException("cannot create ${parent.name}")
        }
        val part = File(parent, target.name + ".part")
        try {
            val response = awaitResponse(httpClient, Request.Builder().url(url).get().build())
            response.use {
                if (!it.isSuccessful) throw ApkDownloadException("download failed with HTTP ${it.code}")
                val body = it.body ?: throw ApkDownloadException("download returned an empty body")
                val expectedBytes = body.contentLength()
                val knownTotal = expectedBytes.takeIf { value -> value > 0 }
                var copied = 0L
                var reportedBytes = 0L
                try {
                    FileOutputStream(part).use { raw ->
                        BufferedOutputStream(raw, IO_BUFFER_BYTES).use { sink ->
                            val source = body.source()
                            val buffer = ByteArray(IO_BUFFER_BYTES)
                            while (true) {
                                val read = source.read(buffer)
                                if (read == -1) break
                                sink.write(buffer, 0, read)
                                copied += read
                                if (copied - reportedBytes >= progressIntervalBytes) {
                                    reportedBytes = copied
                                    onProgress(ApkDownloadProgress(copied, knownTotal))
                                }
                            }
                            sink.flush()
                        }
                        raw.fd.let { fd ->
                            // Best-effort durability: a cache APK does not need power-loss
                            // guarantees, and some file systems (FUSE sandboxes, exotic mounts)
                            // reject fsync outright.
                            runCatching { fd.sync() }
                        }
                    }
                } catch (error: IOException) {
                    throw ApkDownloadException("the download stream failed: ${error.message}")
                }
                onProgress(ApkDownloadProgress(copied, knownTotal))
                if (expectedBytes > 0 && copied != expectedBytes) {
                    throw ApkDownloadException("truncated download: received $copied of $expectedBytes bytes")
                }
                verifyApkHeader(part)
                moveIntoPlace(part, target)
            }
        } finally {
            if (part.exists()) part.delete()
        }
        return target
    }

    private fun verifyApkHeader(file: File) {
        val header = ByteArray(ZIP_LOCAL_FILE_HEADER.size)
        val read = FileInputStream(file).use { it.read(header) }
        if (read != header.size || !header.contentEquals(ZIP_LOCAL_FILE_HEADER)) {
            throw ApkDownloadException("the downloaded file is not an Android package")
        }
    }

    private fun moveIntoPlace(part: File, target: File) {
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val READ_TIMEOUT_SECONDS = 120L
        private const val DEFAULT_PROGRESS_INTERVAL_BYTES = 256L * 1024
        // APK files are ZIP archives and always start with a local file header.
        private val ZIP_LOCAL_FILE_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}

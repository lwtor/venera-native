package dev.veneranative.data.update

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

open class ApkDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A failure establishing the connection; retrying through the other network channel may help. */
private class ApkConnectException(message: String, cause: Throwable) : ApkDownloadException(message, cause)

data class ApkDownloadProgress(val bytesDownloaded: Long, val totalBytes: Long?) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toDouble() / it).toFloat() }
}

/**
 * Streams a release APK to a local file with byte-level progress and resumable downloads.
 *
 * The bytes land in a `.part` sibling; if a download is interrupted, the next attempt requests the
 * missing range (`Range: bytes=<resumed>-`) so it continues from where it left off instead of
 * starting over. GitHub's release CDN answers range requests with `206 Partial Content`.
 *
 * The download first tries the device's system proxy (which is how the user's VPN accelerates the
 * browser), then falls back to a direct connection when the proxy fails before any progress is
 * made — the two paths are tried per attempt so an unstable proxy does not leave the download
 * permanently stuck.
 */
class ApkDownloader(
    baseClient: OkHttpClient,
    private val progressIntervalBytes: Long = DEFAULT_PROGRESS_INTERVAL_BYTES,
) {
    private val viaProxyClient = baseClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val directClient = baseClient.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Downloads [url] into [target], resuming a partial `.part` if one already exists. */
    suspend fun download(url: String, target: File, onProgress: (ApkDownloadProgress) -> Unit = {}): File {
        val parent = target.parentFile ?: throw ApkDownloadException("target has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw ApkDownloadException("cannot create ${parent.name}")
        }
        val part = File(parent, target.name + ".part")
        val startBytes = if (part.isFile) part.length() else 0L

        // On success `moveIntoPlace` renames the `.part` into `target`, so nothing is left behind.
        // On a transfer failure the `.part` is deliberately kept: the next attempt resumes from its
        // length instead of starting over. The one exception is a body that is not an APK at all —
        // that is garbage (e.g. an HTML error page served as 200), and resuming it would only
        // append valid bytes to junk, so it is discarded below.
        downloadInto(url, part, startBytes, onProgress)
        try {
            verifyApkHeader(part)
        } catch (error: ApkDownloadException) {
            part.delete()
            throw error
        }
        moveIntoPlace(part, target)
        return target
    }

    /** Tries the proxy channel first, then the direct channel when the proxy makes no progress. */
    private suspend fun downloadInto(
        url: String,
        part: File,
        startBytes: Long,
        onProgress: (ApkDownloadProgress) -> Unit,
    ) {
        val request = Request.Builder().url(url).get().apply {
            if (startBytes > 0) header("Range", "bytes=$startBytes-")
        }.build()

        val attempts = listOf(viaProxyClient, directClient)
        var lastError: ApkDownloadException? = null
        for ((index, client) in attempts.withIndex()) {
            try {
                streamFrom(client, request, part, startBytes, onProgress)
                return
            } catch (error: ApkDownloadException) {
                lastError = error
                // Only a connect failure justifies switching channels, and only while no bytes
                // have been written. HTTP errors and mid-stream failures must not be replayed.
                val canFallback = error is ApkConnectException && index < attempts.lastIndex && part.length() <= startBytes
                if (!canFallback) throw error
            }
        }
        throw lastError ?: ApkDownloadException("download failed")
    }

    private suspend fun streamFrom(
        client: OkHttpClient,
        request: Request,
        part: File,
        startBytes: Long,
        onProgress: (ApkDownloadProgress) -> Unit,
    ) {
        val response = try {
            awaitResponse(client, request)
        } catch (error: IOException) {
            if (error is SocketTimeoutException) {
                throw ApkConnectException("the download timed out while connecting: ${error.message}", error)
            }
            throw ApkConnectException("could not reach the download server: ${error.message}", error)
        }
        response.use {
            val code = it.code
            if (code != 200 && code != 206) throw ApkDownloadException("download failed with HTTP $code")

            val body = it.body ?: throw ApkDownloadException("download returned an empty body")
            val totalBytes = parseTotalBytes(it.header("Content-Range"), body.contentLength())
            // The server may ignore our range and send the whole file (200): restart from zero.
            val offset = if (code == 206) startBytes else 0L
            var copied = offset

            RandomAccessFile(part, "rw").use { file ->
                file.seek(offset)
                val source = body.source()
                val buffer = ByteArray(IO_BUFFER_BYTES)
                var lastReported = copied
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    file.write(buffer, 0, read)
                    copied += read
                    if (copied - lastReported >= progressIntervalBytes) {
                        lastReported = copied
                        onProgress(ApkDownloadProgress(copied, totalBytes))
                    }
                }
            }
            onProgress(ApkDownloadProgress(copied, totalBytes))
            if (totalBytes != null && totalBytes > 0 && copied != totalBytes) {
                throw ApkDownloadException("truncated download: received $copied of $totalBytes bytes")
            }
        }
    }

    private fun parseTotalBytes(contentRange: String?, contentLength: Long): Long? {
        // "bytes 0-499/500" -> 500, falling back to Content-Length when absent.
        return contentRange?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
            ?: contentLength.takeIf { it > 0 }
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
            java.nio.file.Files.move(
                part.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                part.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 120L
        private const val DEFAULT_PROGRESS_INTERVAL_BYTES = 256L * 1024
        // APK files are ZIP archives and always start with a local file header.
        private val ZIP_LOCAL_FILE_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}

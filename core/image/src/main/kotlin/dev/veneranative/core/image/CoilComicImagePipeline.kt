package dev.veneranative.core.image

import android.graphics.BitmapFactory
import coil3.disk.DiskCache
import dev.veneranative.core.model.ImageSize
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import kotlinx.coroutines.sync.withLock

/**
 * [ComicImagePipeline] over the app's shared OkHttp client and a Coil [DiskCache].
 *
 * Disk caching is explicit here rather than delegated to Coil's network stack: the network goes
 * through `:core:network`'s client (shared connection pool, dispatcher and timeouts), and the cache
 * key comes from [ComicImageCacheKey], which is the only place that decides whether two requests may
 * share bytes.
 */
class CoilComicImagePipeline(
    private val client: OkHttpClient,
    private val diskCache: DiskCache,
    private val auth: ComicImageAuthProvider,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val headerBytes: Int = DEFAULT_HEADER_BYTES,
) : ComicImagePipeline {

    private val locks = Array(32) { kotlinx.coroutines.sync.Mutex() }

    override suspend fun cachedFileOf(request: ComicImageRequest): ComicImageFile? {
        // Freeze authentication once: key and HTTP must describe the exact same identity.
        val headers = auth.headersFor(request.sourceId, request.url).toMap()
        val key = ComicImageCacheKey.of(request, headers)
        var acquired: ComicImageFile? = null
        try {
            return withContext(Dispatchers.IO) {
                locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                    (readSnapshot(key) ?: download(request, headers, key)).also { acquired = it }
                }
            }
        } catch (failure: kotlinx.coroutines.CancellationException) {
            acquired?.close()
            throw failure
        } catch (_: IOException) { return null }
    }

    override suspend fun sizeOf(request: ComicImageRequest): ImageSize? =
        cachedFileOf(request)?.use { file ->
            withContext(Dispatchers.IO) { headerParserSizeOf(file) ?: boundsSizeOf(file.file) }
        }

    private fun readSnapshot(key: String): ComicImageFile? = diskCache.openSnapshot(key)?.asFile()

    private fun DiskCache.Snapshot.asFile(mimeType: String? = null): ComicImageFile =
        ComicImageFile(File(data.toString()), mimeType) { close() }

    private suspend fun download(request: ComicImageRequest, headers: Map<String, String>, key: String): ComicImageFile? {
        val http = httpRequest(request, headers) ?: return null
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val call = client.newCall(http)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val file = try { response.use { executed ->
                        if (!executed.isSuccessful || executed.body.contentLength() > maxBytes) return@use null
                        val editor = diskCache.openEditor(key) ?: return@use null
                        var committed = false
                        try {
                            diskCache.fileSystem.sink(editor.data).buffer().use { sink ->
                                val source = executed.body.source()
                                val buffer = okio.Buffer()
                                var total = 0L
                                while (true) {
                                    val count = source.read(buffer, minOf(8192L, maxBytes - total + 1))
                                    if (count == -1L) break
                                    total += count
                                    if (total > maxBytes) throw IOException("Image exceeds byte limit")
                                    sink.write(buffer, count)
                                }
                            }
                            if (call.isCanceled()) throw IOException("Cancelled")
                            val snapshot = editor.commitAndOpenSnapshot()
                            committed = true
                            snapshot?.asFile(executed.header("Content-Type"))
                        } finally { if (!committed) editor.abort() }
                    } } catch (_: IOException) { null }
                    continuation.resume(file) { _, value, _ -> value?.close() }
                }
            })
        }
    }

    /**
     * Reads only the head of the file: a comic page can be tens of megabytes, and every format we
     * care about declares its size in the first few dozen bytes.
     */
    private fun headerParserSizeOf(file: ComicImageFile): ImageSize? {
        val head = file.file.inputStream().use { stream ->
            val buffer = ByteArray(headerBytes)
            val read = stream.read(buffer)
            if (read <= 0) return null
            buffer.copyOf(read)
        }
        return ImageSizeHeaderParser.parse(head)
    }

    /** Fallback for formats the header parser does not recognise; costs one bounds-only decode. */
    private fun boundsSizeOf(file: File): ImageSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return ImageSize(widthPx = options.outWidth, heightPx = options.outHeight)
    }

    /**
     * The request the source asked for, or null when the reference is not one we can fetch.
     *
     * Sources occasionally hand out non-http references (data URIs, protocol-relative or plain
     * broken strings). That is a bad page, not a broken chapter, so it is reported as unresolvable
     * instead of thrown.
     */
    private fun httpRequest(request: ComicImageRequest, authHeaders: Map<String, String>): Request? {
        val url = request.url.toHttpUrlOrNull() ?: return null
        val headers = request.headers +
            authHeaders +
            (request.referer?.let { referer -> mapOf("Referer" to referer) } ?: emptyMap())
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        when (request.method) {
            ComicImageMethod.GET -> builder.get()
            ComicImageMethod.POST -> builder.post(request.body.toRequestBody())
        }
        return builder.build()
    }

    private fun ComicImageBody?.toRequestBody(): RequestBody = when (this) {
        null -> EMPTY_BODY
        is ComicImageBody.Bytes -> content.toRequestBody(contentType?.toMediaTypeOrNull())
        is ComicImageBody.Form -> okhttp3.FormBody.Builder().apply {
            fields.toSortedMap().forEach { (name, value) -> add(name, value) }
        }.build()
    }

    private companion object {
        const val DEFAULT_HEADER_BYTES = 64 * 1024
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
        const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}

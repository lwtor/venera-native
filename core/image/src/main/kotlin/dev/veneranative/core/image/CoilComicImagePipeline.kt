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

    override suspend fun cachedFileOf(request: ComicImageRequest): ComicImageFile? = withContext(Dispatchers.IO) {
        val key = keyOf(request)
        readSnapshot(key)?.let { return@withContext it }
        download(request, key)
    }

    override suspend fun sizeOf(request: ComicImageRequest): ImageSize? = withContext(Dispatchers.IO) {
        val file = cachedFileOf(request) ?: return@withContext null
        headerParserSizeOf(file) ?: boundsSizeOf(file.file)
    }

    private fun keyOf(request: ComicImageRequest): String =
        ComicImageCacheKey.of(request, auth.headersFor(request.sourceId, request.url))

    private fun readSnapshot(key: String): ComicImageFile? {
        val snapshot = diskCache.openSnapshot(key) ?: return null
        return try {
            ComicImageFile(file = File(snapshot.data.toString()), mimeType = null)
        } finally {
            snapshot.close()
        }
    }

    private fun download(request: ComicImageRequest, key: String): ComicImageFile? {
        val call = client.newCall(httpRequest(request) ?: return null)
        val response = try {
            call.execute()
        } catch (_: IOException) {
            return null
        }
        return response.use { executed ->
            if (!executed.isSuccessful) return null
            val body = executed.body ?: return null
            val length = body.contentLength()
            if (length > maxBytes) return null
            val editor = diskCache.openEditor(key) ?: return null
            val mimeType = executed.header("Content-Type")
            try {
                diskCache.fileSystem.sink(editor.data).buffer().use { sink ->
                    body.source().use { source -> sink.writeAll(source) }
                }
                editor.commit()
            } catch (failure: IOException) {
                editor.abort()
                return null
            }
            ComicImageFile(file = File(editor.data.toString()), mimeType = mimeType)
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
    private fun httpRequest(request: ComicImageRequest): Request? {
        val url = request.url.toHttpUrlOrNull() ?: return null
        val headers = request.headers +
            auth.headersFor(request.sourceId, request.url) +
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
        is ComicImageBody.Form -> fields.entries
            .sortedBy { (name, _) -> name }
            .joinToString(separator = "&") { (name, value) -> "$name=$value" }
            .toRequestBody(FORM_CONTENT_TYPE.toMediaTypeOrNull())
    }

    private companion object {
        const val DEFAULT_HEADER_BYTES = 64 * 1024
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
        const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}

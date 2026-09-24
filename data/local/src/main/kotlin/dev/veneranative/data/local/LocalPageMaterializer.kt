package dev.veneranative.data.local

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream

fun interface LocalPageSource { fun open(page: LocalPage): InputStream }

class DefaultLocalPageSource(
    private val resolver: ContentResolver,
    private val archives: LocalArchiveAccess,
) : LocalPageSource {
    override fun open(page: LocalPage): InputStream = when (page.kind) {
        LocalKind.Directory -> resolver.openInputStream(Uri.parse(page.uri))
            ?: throw java.io.IOException("Local page is unavailable")
        LocalKind.Archive -> {
            val archive = archives.open(page.rootUri)
            val entry = try { archive.openEntry(page.uri) } catch (failure: Throwable) { archive.close(); throw failure }
            object : java.io.FilterInputStream(entry) {
                override fun close() { try { super.close() } finally { archive.close() } }
            }
        }
    }
}

class LocalPageMaterializer(private val cache: LocalPageCache, private val source: LocalPageSource) {
    fun materialize(page: LocalPage): String = cache.materialize(
        "${page.rootUri}|${page.uri}|${page.sizeBytes}",
    ) { source.open(page) }.absolutePath
}

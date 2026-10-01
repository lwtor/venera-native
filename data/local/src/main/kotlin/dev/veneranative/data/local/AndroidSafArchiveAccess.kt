package dev.veneranative.data.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.veneranative.core.archive.AndroidArchiveOpener
import dev.veneranative.core.archive.ArchiveReadException

class AndroidSafArchiveAccess(private val context: Context) : LocalArchiveAccess {
    private val resolver = context.contentResolver
    override fun displayName(uri: String): String? =
        DocumentFile.fromSingleUri(context, Uri.parse(uri))?.name?.takeIf(String::isNotBlank)

    override fun open(uri: String) = try {
        val parsed = Uri.parse(uri)
        val name = displayName(uri) ?: parsed.lastPathSegment.orEmpty().substringAfterLast('/')
        AndroidArchiveOpener(resolver).open(parsed, name, resolver.getType(parsed))
    } catch (e: ArchiveReadException) { throw e }
}

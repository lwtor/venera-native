package dev.veneranative.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

interface SafTreeAccess {
    fun take(uri: String)
    fun release(uri: String)
    fun read(uri: String): TreeNode?
}

class AndroidSafTreeAccess(private val context: Context) : SafTreeAccess {
    override fun take(uri: String) {
        context.contentResolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    override fun release(uri: String) {
        context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    override fun read(uri: String): TreeNode? {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return null
        return root.toNode(0)
    }
    private fun DocumentFile.toNode(depth: Int): TreeNode = TreeNode(
        uri = uri.toString(),
        name = name.orEmpty(),
        directory = isDirectory,
        sizeBytes = length(),
        children = if (isDirectory && depth < 2) listFiles().map { it.toNode(depth + 1) } else emptyList(),
    )
}

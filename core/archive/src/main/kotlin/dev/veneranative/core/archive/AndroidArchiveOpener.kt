package dev.veneranative.core.archive

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.SeekableByteChannel

class AndroidArchiveOpener(private val resolver: ContentResolver) {
    fun open(uri: Uri, displayName: String, mimeType: String? = null): ArchiveReader {
        val format = ArchiveFormat.from(displayName, mimeType)
            ?: throw ArchiveReadException(ArchiveError.Unsupported(displayName))
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw ArchiveReadException(ArchiveError.Io)
        return try {
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            CommonsArchiveReader.open(format, OwnedChannel(channel, descriptor))
        } catch (e: IOException) {
            runCatching { descriptor.close() }
            throw ArchiveReadException(ArchiveError.Io, e)
        }
    }

    /** Closing FileInputStream closes the shared fd; descriptor.close is intentionally idempotent. */
    private class OwnedChannel(
        private val delegate: SeekableByteChannel,
        private val descriptor: ParcelFileDescriptor,
    ) : SeekableByteChannel by delegate {
        override fun close() {
            try { delegate.close() } finally { descriptor.close() }
        }
    }
}

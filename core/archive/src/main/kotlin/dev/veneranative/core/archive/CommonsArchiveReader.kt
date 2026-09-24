package dev.veneranative.core.archive

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets

/** Commons Compress adapter. The caller transfers ownership of [channel] to this reader. */
class CommonsArchiveReader private constructor(
    private val zip: ZipFile?,
    private val seven: SevenZFile?,
    private val channel: SeekableByteChannel,
) : ArchiveReader {
    override fun entries(): List<ArchiveEntry> = guarded {
        zip?.entries?.asSequence()?.map { ArchiveEntry(it.name, it.size.coerceAtLeast(0), it.isDirectory) }?.toList()
            ?: seven!!.entries.map { ArchiveEntry(it.name, it.size.coerceAtLeast(0), it.isDirectory) }
    }

    override fun openEntry(name: String): InputStream = guarded {
        zip?.let { archive ->
            val entry = archive.getEntry(name) ?: throw ArchiveReadException(ArchiveError.EntryMissing(name))
            if (entry.isDirectory) throw ArchiveReadException(ArchiveError.EntryMissing(name))
            archive.getInputStream(entry)
        } ?: run {
            // 7z streams are sequential; locate the exact entry in this reader before opening it.
            val entry = seven!!.entries.firstOrNull { it.name == name }
                ?: throw ArchiveReadException(ArchiveError.EntryMissing(name))
            if (entry.isDirectory) throw ArchiveReadException(ArchiveError.EntryMissing(name))
            seven.getInputStream(entry)
        }
    }

    override fun close() { runCatching { zip?.close() }; runCatching { seven?.close() }; runCatching { channel.close() } }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: ArchiveReadException) {
        throw e
    } catch (e: org.apache.commons.compress.archivers.ArchiveException) {
        throw ArchiveReadException(ArchiveError.Corrupt, e)
    } catch (e: java.io.IOException) {
        throw ArchiveReadException(ArchiveError.Corrupt, e)
    } catch (e: RuntimeException) {
        throw ArchiveReadException(ArchiveError.Corrupt, e)
    }

    companion object {
        fun open(format: ArchiveFormat, channel: SeekableByteChannel): CommonsArchiveReader = try {
            when (format) {
                ArchiveFormat.Zip -> CommonsArchiveReader(
                    ZipFile.builder().setSeekableByteChannel(channel).setCharset(StandardCharsets.UTF_8).get(), null, channel,
                )
                ArchiveFormat.SevenZip -> CommonsArchiveReader(null, SevenZFile.builder().setSeekableByteChannel(channel).setMaxMemoryLimitKb(131_072).get(), channel)
            }
        } catch (e: Exception) {
            runCatching { channel.close() }
            throw ArchiveReadException(ArchiveError.Corrupt, e)
        }
    }
}

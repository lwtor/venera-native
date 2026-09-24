package dev.veneranative.core.archive

import java.io.InputStream

interface ArchiveReader : AutoCloseable {
    fun entries(): List<ArchiveEntry>
    fun openEntry(name: String): InputStream
}

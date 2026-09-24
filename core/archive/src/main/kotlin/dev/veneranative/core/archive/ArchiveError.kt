package dev.veneranative.core.archive

sealed interface ArchiveError {
    data class Unsupported(val name: String) : ArchiveError
    data object Corrupt : ArchiveError
    data class EntryMissing(val name: String) : ArchiveError
    data object Io : ArchiveError
}

class ArchiveReadException(val error: ArchiveError, cause: Throwable? = null) : Exception(error.toString(), cause)

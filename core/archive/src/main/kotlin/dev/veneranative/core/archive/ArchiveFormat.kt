package dev.veneranative.core.archive

enum class ArchiveFormat(val extension: String) {
    Zip("zip"), SevenZip("7z");

    companion object {
        fun from(name: String, mimeType: String? = null): ArchiveFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "zip" || ext == "cbz" || mimeType.equals("application/zip", true) -> Zip
                ext == "7z" || ext == "cb7" || mimeType.equals("application/x-7z-compressed", true) -> SevenZip
                else -> null
            }
        }
    }
}

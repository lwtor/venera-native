package dev.veneranative.core.image

import dev.veneranative.core.model.ImageSize

/**
 * Reads an image's pixel size from its header bytes.
 *
 * Pure Kotlin on purpose: a comic page is tens of megabytes and the size is declared in the first
 * few dozen, so the reader can reserve space without decoding, and the parser is unit testable on
 * the JVM instead of only on a device. Formats it does not recognise return null and the caller
 * falls back to `BitmapFactory.inJustDecodeBounds`.
 *
 * Covered: JPEG (SOF0-SOF15), PNG, WebP (VP8 / VP8L / VP8X) and GIF.
 */
object ImageSizeHeaderParser {

    /** Parses [bytes] as an image header; null when the format is unknown or truncated. */
    fun parse(bytes: ByteArray): ImageSize? {
        if (bytes.isEmpty()) return null
        return parsePng(bytes)
            ?: parseGif(bytes)
            ?: parseWebp(bytes)
            ?: parseJpeg(bytes)
    }

    private fun parsePng(bytes: ByteArray): ImageSize? {
        if (!bytes.matchesAt(PNG_SIGNATURE, offset = 0)) return null
        // 8-byte signature, then the IHDR chunk: 4-byte length, 4-byte type, width, height.
        if (bytes.size < PNG_IHDR_SIZE_OFFSET + 8) return null
        if (!bytes.matchesAt(PNG_IHDR_TYPE, offset = PNG_IHDR_TYPE_OFFSET)) return null
        val widthPx = bytes.readInt32BigEndian(PNG_IHDR_SIZE_OFFSET)
        val heightPx = bytes.readInt32BigEndian(PNG_IHDR_SIZE_OFFSET + 4)
        return sizeOrNull(widthPx, heightPx)
    }

    private fun parseGif(bytes: ByteArray): ImageSize? {
        if (bytes.size < GIF_HEADER_SIZE) return null
        val signature = bytes.matchesAt(GIF87A, offset = 0) || bytes.matchesAt(GIF89A, offset = 0)
        if (!signature) return null
        // Logical screen descriptor: width and height are little endian.
        val widthPx = bytes.readInt16LittleEndian(6)
        val heightPx = bytes.readInt16LittleEndian(8)
        return sizeOrNull(widthPx, heightPx)
    }

    private fun parseWebp(bytes: ByteArray): ImageSize? {
        if (bytes.size < WEBP_CHUNK_HEADER_SIZE) return null
        if (!bytes.matchesAt(RIFF, offset = 0) || !bytes.matchesAt(WEBP, offset = 8)) return null
        return when {
            bytes.matchesAt(VP8_LOSSY, offset = 12) -> parseVp8(bytes)
            bytes.matchesAt(VP8_LOSSLESS, offset = 12) -> parseVp8L(bytes)
            bytes.matchesAt(VP8_EXTENDED, offset = 12) -> parseVp8X(bytes)
            else -> null
        }
    }

    /** Lossy WebP: after the frame tag the canvas size is two 14-bit little endian fields. */
    private fun parseVp8(bytes: ByteArray): ImageSize? {
        if (bytes.size < WEBP_CHUNK_HEADER_SIZE + 10) return null
        val offset = WEBP_CHUNK_HEADER_SIZE
        val widthPx = bytes.readInt16LittleEndian(offset + 3) and VP8_DIMENSION_MASK
        val heightPx = bytes.readInt16LittleEndian(offset + 5) and VP8_DIMENSION_MASK
        return sizeOrNull(widthPx, heightPx)
    }

    /** Lossless WebP: width and height minus one, packed into 14 bits each. */
    private fun parseVp8L(bytes: ByteArray): ImageSize? {
        if (bytes.size < WEBP_CHUNK_HEADER_SIZE + 5) return null
        val offset = WEBP_CHUNK_HEADER_SIZE
        val b0 = bytes[offset + 1].toInt() and 0xFF
        val b1 = bytes[offset + 2].toInt() and 0xFF
        val b2 = bytes[offset + 3].toInt() and 0xFF
        val b3 = bytes[offset + 4].toInt() and 0xFF
        val widthPx = (((b1 and 0x3F) shl 8) or b0) + 1
        val heightPx = (((b3 and 0x0F) shl 10) or (b2 shl 2) or (b1 shr 6)) + 1
        return sizeOrNull(widthPx, heightPx)
    }

    /** Extended WebP: the canvas size minus one is stored as two 24-bit little endian fields. */
    private fun parseVp8X(bytes: ByteArray): ImageSize? {
        if (bytes.size < WEBP_CHUNK_HEADER_SIZE + 10) return null
        val offset = WEBP_CHUNK_HEADER_SIZE
        val widthPx = bytes.readInt24LittleEndian(offset + 4) + 1
        val heightPx = bytes.readInt24LittleEndian(offset + 7) + 1
        return sizeOrNull(widthPx, heightPx)
    }

    /**
     * JPEG: walks the marker segments until a Start Of Frame marker, which carries the size.
     * Markers without a payload (SOI, EOI, RSTn, TEM) are skipped by length.
     */
    private fun parseJpeg(bytes: ByteArray): ImageSize? {
        if (bytes.size < 4) return null
        if (bytes[0].unsigned() != JPEG_MARKER_PREFIX || bytes[1].unsigned() != JPEG_SOI) return null
        var offset = 2
        while (offset + 3 < bytes.size) {
            if (bytes[offset].toInt() and 0xFF != 0xFF) return null
            val marker = bytes[offset + 1].toInt() and 0xFF
            offset += 2
            when {
                marker in JPEG_SOF_MARKERS -> {
                    // Segment: 2-byte length, 1-byte precision, 2-byte height, 2-byte width.
                    if (offset + 7 >= bytes.size) return null
                    val heightPx = bytes.readInt16BigEndian(offset + 3)
                    val widthPx = bytes.readInt16BigEndian(offset + 5)
                    return sizeOrNull(widthPx, heightPx)
                }

                marker == JPEG_SOI || marker == JPEG_EOI || marker == JPEG_TEM ||
                    marker in JPEG_RESTART_MARKERS -> Unit

                else -> {
                    val segmentLength = bytes.readInt16BigEndian(offset)
                    if (segmentLength < 2) return null
                    offset += segmentLength
                }
            }
        }
        return null
    }

    private fun sizeOrNull(widthPx: Int, heightPx: Int): ImageSize? =
        if (widthPx > 0 && heightPx > 0) ImageSize(widthPx = widthPx, heightPx = heightPx) else null

    private fun ByteArray.matchesAt(signature: ByteArray, offset: Int): Boolean {
        if (size - offset < signature.size) return false
        for (index in signature.indices) {
            if (this[offset + index] != signature[index]) return false
        }
        return true
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private fun ByteArray.readInt16BigEndian(offset: Int): Int {
        val high = this[offset].toInt() and 0xFF
        val low = this[offset + 1].toInt() and 0xFF
        return (high shl 8) or low
    }

    private fun ByteArray.readInt16LittleEndian(offset: Int): Int {
        val low = this[offset].toInt() and 0xFF
        val high = this[offset + 1].toInt() and 0xFF
        return (high shl 8) or low
    }

    private fun ByteArray.readInt32BigEndian(offset: Int): Int {
        var value = 0
        for (index in 0 until 4) {
            value = (value shl 8) or (this[offset + index].toInt() and 0xFF)
        }
        return value
    }

    private fun ByteArray.readInt24LittleEndian(offset: Int): Int {
        var value = 0
        for (index in 2 downTo 0) {
            value = (value shl 8) or (this[offset + index].toInt() and 0xFF)
        }
        return value
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_IHDR_TYPE = byteArrayOf(0x49, 0x48, 0x44, 0x52)
    private val GIF87A = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
    private val GIF89A = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
    private val RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46)
    private val WEBP = byteArrayOf(0x57, 0x45, 0x42, 0x50)
    private val VP8_LOSSY = byteArrayOf(0x56, 0x50, 0x38, 0x20)
    private val VP8_LOSSLESS = byteArrayOf(0x56, 0x50, 0x38, 0x4C)
    private val VP8_EXTENDED = byteArrayOf(0x56, 0x50, 0x38, 0x58)

    private const val PNG_IHDR_TYPE_OFFSET = 12
    private const val PNG_IHDR_SIZE_OFFSET = 16
    private const val GIF_HEADER_SIZE = 10
    /** Four bytes "VP8x" plus the four byte chunk size. */
    private const val WEBP_CHUNK_HEADER_SIZE = 20
    private const val VP8_DIMENSION_MASK = 0x3FFF

    private const val JPEG_MARKER_PREFIX = 0xFF
    private const val JPEG_SOI = 0xD8
    private const val JPEG_EOI = 0xD9
    private const val JPEG_TEM = 0x01
    /** DHT (0xC4), JPG (0xC8) and DAC (0xCC) are not Start Of Frame markers. */
    private val JPEG_SOF_MARKERS = intArrayOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
    private val JPEG_RESTART_MARKERS = 0xD0..0xD7
}

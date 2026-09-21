package dev.veneranative.core.image

import dev.veneranative.core.model.ImageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Header parsing for the sizes the reader needs before it can lay a page out.
 *
 * Runs on the JVM because it is pure byte arithmetic: no framework, no device. Formats that are not
 * recognised return null and the caller falls back to `BitmapFactory.inJustDecodeBounds`.
 */
class ImageSizeHeaderParserTest {

    @Test
    fun parsesPng() {
        assertEquals(ImageSize(2, 3), ImageSizeHeaderParser.parse(png(widthPx = 2, heightPx = 3)))
    }

    @Test
    fun parsesGif() {
        assertEquals(ImageSize(640, 480), ImageSizeHeaderParser.parse(gif(widthPx = 640, heightPx = 480)))
    }

    @Test
    fun parsesLossyWebp() {
        assertEquals(ImageSize(300, 400), ImageSizeHeaderParser.parse(vp8(widthPx = 300, heightPx = 400)))
    }

    @Test
    fun parsesLosslessWebp() {
        assertEquals(ImageSize(300, 400), ImageSizeHeaderParser.parse(vp8L(widthPx = 300, heightPx = 400)))
    }

    @Test
    fun parsesExtendedWebp() {
        assertEquals(ImageSize(300, 400), ImageSizeHeaderParser.parse(vp8X(widthPx = 300, heightPx = 400)))
    }

    @Test
    fun parsesJpegWithLeadingSegments() {
        // An APP0/JFIF segment and a quantization table come before the Start Of Frame marker.
        val bytes = jpegStart() +
            segment(marker = 0xE0, payload = "JFIF\u0000".toByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0)) +
            segment(marker = 0xDB, payload = ByteArray(65)) +
            sof(marker = 0xC0, widthPx = 1080, heightPx = 16000)

        assertEquals(ImageSize(1080, 16000), ImageSizeHeaderParser.parse(bytes))
    }

    @Test
    fun parsesProgressiveJpeg() {
        val bytes = jpegStart() + sof(marker = 0xC2, widthPx = 800, heightPx = 600)

        assertEquals(ImageSize(800, 600), ImageSizeHeaderParser.parse(bytes))
    }

    @Test
    fun anUnknownFormatIsNotGuessed() {
        assertNull(ImageSizeHeaderParser.parse(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)))
    }

    @Test
    fun aTruncatedHeaderIsNotGuessed() {
        assertNull(ImageSizeHeaderParser.parse(png(widthPx = 4, heightPx = 4).copyOf(20)))
    }

    private fun jpegStart(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun sof(marker: Int, widthPx: Int, heightPx: Int): ByteArray {
        // Segment payload: precision, height (2 bytes), width (2 bytes), component count.
        val payload = byteArrayOf(8) +
            byteArrayOf((heightPx shr 8).toByte(), heightPx.toByte()) +
            byteArrayOf((widthPx shr 8).toByte(), widthPx.toByte()) +
            byteArrayOf(3)
        return segment(marker, payload)
    }

    private fun png(widthPx: Int, heightPx: Int): ByteArray {
        val ihdr = "IHDR".toByteArray() + int32(widthPx) + int32(heightPx) + byteArrayOf(8, 6, 0, 0, 0)
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            int32(ihdr.size - 4) + ihdr + ByteArray(32)
    }

    private fun gif(widthPx: Int, heightPx: Int): ByteArray =
        "GIF89a".toByteArray() + int16Le(widthPx) + int16Le(heightPx) + ByteArray(6)

    private fun vp8(widthPx: Int, heightPx: Int): ByteArray {
        val chunk = ByteArray(10)
        chunk[3] = (widthPx and 0x3FFF).toByte()
        chunk[4] = ((widthPx shr 8) and 0x3F).toByte()
        chunk[5] = (heightPx and 0x3FFF).toByte()
        chunk[6] = ((heightPx shr 8) and 0x3F).toByte()
        return riffChunk("VP8 ", chunk)
    }

    private fun vp8L(widthPx: Int, heightPx: Int): ByteArray {
        val width = widthPx - 1
        val height = heightPx - 1
        val chunk = byteArrayOf(
            0x2F,
            (width and 0xFF).toByte(),
            (((width shr 8) and 0x3F) or ((height and 0x3) shl 6)).toByte(),
            ((height shr 2) and 0xFF).toByte(),
            ((height shr 10) and 0x0F).toByte(),
            0,
            0,
            0,
            0,
            0,
        )
        return riffChunk("VP8L", chunk)
    }

    private fun vp8X(widthPx: Int, heightPx: Int): ByteArray {
        val width = widthPx - 1
        val height = heightPx - 1
        val chunk = byteArrayOf(0x00, 0, 0, 0) + int24Le(width) + int24Le(height) + byteArrayOf(0, 0, 0)
        return riffChunk("VP8X", chunk)
    }

    private fun riffChunk(fourCc: String, payload: ByteArray): ByteArray =
        "RIFF".toByteArray() + int32Le(payload.size + 8) + "WEBP".toByteArray() +
            fourCc.toByteArray() + int32Le(payload.size) + payload

    private fun int32(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    private fun int32Le(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value shr 8).toByte(),
        (value shr 16).toByte(),
        (value shr 24).toByte(),
    )

    private fun int24Le(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value shr 8).toByte(),
        (value shr 16).toByte(),
    )

    private fun int16Le(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte())
}

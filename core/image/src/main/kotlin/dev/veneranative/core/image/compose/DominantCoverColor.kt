package dev.veneranative.core.image.compose

/** Quantized RGB histogram, bounded to the 32x32 sample; transparent pixels do not vote. */
internal fun dominantCoverColor(pixels: IntArray): Int? {
    val counts = IntArray(512)
    val red = IntArray(512)
    val green = IntArray(512)
    val blue = IntArray(512)
    for (pixel in pixels) {
        if ((pixel ushr 24) < 128) continue
        val r = (pixel ushr 16) and 255
        val g = (pixel ushr 8) and 255
        val b = pixel and 255
        val index = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
        counts[index]++; red[index] += r; green[index] += g; blue[index] += b
    }
    val winner = counts.indices.maxByOrNull { counts[it] } ?: return null
    val count = counts[winner]
    if (count == 0) return null
    return (255 shl 24) or ((red[winner] / count) shl 16) or ((green[winner] / count) shl 8) or (blue[winner] / count)
}

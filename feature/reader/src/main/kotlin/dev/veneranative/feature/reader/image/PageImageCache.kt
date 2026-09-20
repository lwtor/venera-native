package dev.veneranative.feature.reader.image

import android.graphics.Bitmap

/**
 * Bounded LRU cache for decoded page tiles, accounted in bytes rather than in entry count.
 *
 * Bitmaps are only dropped from the map, never recycled: a bitmap that is still referenced by a
 * composition must stay drawable, and recycling it would crash the next draw. Recycling would also
 * make the S0-06 measurements meaningless, because freed native memory is not immediately visible.
 */
class PageImageCache(private val maxBytes: Long) {

    private val lock = Any()
    private val entries = LinkedHashMap<String, Bitmap>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    /** Current accounted bytes. Only meaningful for measurement and diagnostics. */
    val sizeBytes: Long
        get() = synchronized(lock) { entries.values.sumOf { it.allocationByteCount.toLong() } }

    /** Number of live entries. Used by tests and by the S0-06 probe. */
    val size: Int
        get() = synchronized(lock) { entries.size }

    fun get(key: String): Bitmap? = synchronized(lock) { entries[key] }

    fun put(key: String, bitmap: Bitmap) {
        synchronized(lock) {
            entries[key] = bitmap
            trimLocked()
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private fun trimLocked() {
        var bytes = entries.values.sumOf { it.allocationByteCount.toLong() }
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val oldest = iterator.next()
            bytes -= oldest.value.allocationByteCount.toLong()
            iterator.remove()
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}

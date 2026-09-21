package dev.veneranative.data.history

/**
 * Decides when a throttled progress update is allowed to hit the database.
 *
 * Kept apart from [ReadingProgressTracker] so the window rule can be tested without coroutines, a
 * fake clock or anything else that belongs to the scheduling side.
 */
internal class ProgressThrottlePolicy(private val throttleMillis: Long) {

    init {
        require(throttleMillis >= 0) { "throttleMillis must not be negative" }
    }

    /**
     * True when the throttle window has elapsed, or when nothing was ever written.
     *
     * `>=` rather than `>` so a window of zero degrades to "write every time", which is the honest
     * behaviour of "no throttling" instead of an off-by-one that silently drops updates.
     */
    fun shouldWrite(nowEpochMillis: Long, lastWriteAtEpochMillis: Long?): Boolean =
        lastWriteAtEpochMillis == null || nowEpochMillis - lastWriteAtEpochMillis >= throttleMillis
}

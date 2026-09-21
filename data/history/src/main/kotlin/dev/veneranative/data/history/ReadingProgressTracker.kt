package dev.veneranative.data.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the database from being written on every page turn.
 *
 * Reading a comic means dozens of page events per minute, and none of them are worth a transaction
 * on their own: what matters is the last one, and even that can wait for the next window. So
 * [onPageChanged] only remembers the newest value and [flush] is what guarantees it survives — the
 * app calls it when the reader is left or the process goes to the background, and nothing can be
 * lost in between because the pending value stays in memory until something writes it.
 *
 * The clock and the scope are injected so the throttle is testable on the JVM.
 */
class ReadingProgressTracker(
    private val repository: HistoryRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val throttleMillis: Long = DEFAULT_THROTTLE_MILLIS,
) {

    private val policy = ProgressThrottlePolicy(throttleMillis)
    private val pending = AtomicReference<ReadingHistoryEntry?>()
    private val lastWriteAt = AtomicReference<Long?>()
    private val lock = Any()

    fun onPageChanged(entry: ReadingHistoryEntry) {
        val writeNow = synchronized(lock) {
            pending.set(entry)
            val now = clock()
            if (policy.shouldWrite(now, lastWriteAt.get())) {
                lastWriteAt.set(now)
                true
            } else {
                false
            }
        }
        if (writeNow) scheduleWrite()
    }

    /** Writes whatever is pending immediately. Used when the reader is being left. */
    suspend fun flush() {
        val snapshot = synchronized(lock) { pending.getAndSet(null) }
        if (snapshot != null) {
            lastWriteAt.set(clock())
            repository.record(snapshot)
        }
    }

    private fun scheduleWrite(): Job = scope.launch {
        val snapshot = synchronized(lock) { pending.getAndSet(null) }
        if (snapshot != null) repository.record(snapshot)
    }

    companion object {
        const val DEFAULT_THROTTLE_MILLIS: Long = 2000L
    }
}

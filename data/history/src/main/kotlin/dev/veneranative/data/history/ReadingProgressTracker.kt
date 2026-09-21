package dev.veneranative.data.history

import dev.veneranative.core.model.ComicKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coalesces each comic independently; a failed write remains pending for the next flush. */
class ReadingProgressTracker(
    private val repository: HistoryRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val throttleMillis: Long = DEFAULT_THROTTLE_MILLIS,
) {
    private val lock = Any()
    private val writer = Mutex()
    private val pending = linkedMapOf<ComicKey, ReadingHistoryEntry>()
    private var lastWriteAt: Long? = null
    private var scheduled: Job? = null
    private val _writeFailed = MutableStateFlow(false)
    val writeFailed: StateFlow<Boolean> = _writeFailed

    fun onPageChanged(entry: ReadingHistoryEntry) = synchronized(lock) {
        pending[entry.comicKey] = entry
        if (scheduled?.isActive != true) {
            scheduled = scope.launch {
                try {
                    while (true) {
                        val wait = synchronized(lock) {
                            if (pending.isEmpty()) { scheduled = null; return@launch }
                            lastWriteAt?.let { (throttleMillis - (clock() - it)).coerceAtLeast(0) } ?: 0
                        }
                        delay(wait)
                        flush()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    synchronized(lock) { scheduled = null }
                    _writeFailed.value = true
                }
            }
        }
    }

    /** Serialized with scheduled writes; values are removed only after a successful transaction. */
    suspend fun flush() = writer.withLock {
        val snapshot = synchronized(lock) { pending.values.toList() }
        for (entry in snapshot) {
            try { repository.record(entry) }
            catch (failure: Exception) {
                _writeFailed.value = true
                throw failure
            }
            synchronized(lock) {
                if (pending[entry.comicKey] === entry) pending.remove(entry.comicKey)
                lastWriteAt = clock()
            }
        }
        _writeFailed.value = false
    }

    companion object { const val DEFAULT_THROTTLE_MILLIS: Long = 2000L }
}

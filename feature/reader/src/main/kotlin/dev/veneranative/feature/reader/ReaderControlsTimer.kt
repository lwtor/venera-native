package dev.veneranative.feature.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-only timer; observing touches never consumes the reader's gestures. */
internal class ReaderControlsTimer(
    private val scope: CoroutineScope,
    private val idleMillis: Long = 5_000L,
) {
    private val _visible = MutableStateFlow(false)
    val visible = _visible.asStateFlow()
    private var timer: Job? = null
    private var pressed = false

    fun toggle() {
        if (_visible.value) hide() else {
            _visible.value = true
            restart()
        }
    }

    fun hide() {
        timer?.cancel()
        timer = null
        _visible.value = false
    }

    fun onPointerActivity(anyPressed: Boolean) {
        pressed = anyPressed
        restart()
    }

    fun onInteraction() = restart()

    private fun restart() {
        timer?.cancel()
        timer = null
        if (_visible.value && !pressed) {
            timer = scope.launch {
                delay(idleMillis)
                _visible.value = false
            }
        }
    }
}

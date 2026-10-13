package dev.veneranative.feature.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderControlsTimerTest {
    @Test fun `controls hide after five idle seconds and can reopen`() = runTest {
        val timer = ReaderControlsTimer(backgroundScope)
        timer.toggle()
        runCurrent()
        advanceTimeBy(4_999)
        assertTrue(timer.visible.value)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(timer.visible.value)
        timer.toggle()
        assertTrue(timer.visible.value)
    }

    @Test fun `interaction restarts deadline and a held drag cannot time out`() = runTest {
        val timer = ReaderControlsTimer(backgroundScope)
        timer.toggle()
        runCurrent()
        advanceTimeBy(4_000)
        timer.onPointerActivity(true)
        advanceTimeBy(10_000)
        assertTrue(timer.visible.value)
        timer.onPointerActivity(false)
        runCurrent()
        advanceTimeBy(4_999)
        assertTrue(timer.visible.value)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(timer.visible.value)
    }

    @Test fun `closing cancels old timeout before another open`() = runTest {
        val timer = ReaderControlsTimer(backgroundScope)
        timer.toggle()
        runCurrent()
        advanceTimeBy(4_000)
        timer.toggle()
        assertFalse(timer.visible.value)
        timer.toggle()
        runCurrent()
        advanceTimeBy(1_001)
        assertTrue(timer.visible.value)
    }
}

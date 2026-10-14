package dev.veneranative.data.comic

import dev.veneranative.source.api.SourceOutcome
import dev.veneranative.source.api.SourceRuntimeError
import dev.veneranative.core.model.SourceCapability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadSourceRetryTest {
    @Test fun `script wrapped transient failure recovers on third read`() = runTest {
        var calls = 0
        val waits = mutableListOf<Int>()
        val result = retrySourceRead(wait = { waits += it }) {
            if (++calls < 3) SourceOutcome.Failure(SourceRuntimeError.ScriptExecution("fixture"))
            else SourceOutcome.Success("ready")
        }
        assertEquals(SourceOutcome.Success("ready"), result)
        assertEquals(listOf(1, 2), waits)
    }

    @Test fun `persistent failure stops after three and unsupported or cancelled never retries`() = runTest {
        for ((failure, expected) in listOf(
            SourceRuntimeError.Timeout(10) to 3,
            SourceRuntimeError.UnsupportedCapability(SourceCapability.DETAIL) to 1,
            SourceRuntimeError.Cancelled() to 1,
        )) {
            var calls = 0
            val result = retrySourceRead(wait = {}) { calls++; SourceOutcome.Failure(failure) }
            assertEquals(SourceOutcome.Failure(failure), result)
            assertEquals(expected, calls)
        }
    }
}

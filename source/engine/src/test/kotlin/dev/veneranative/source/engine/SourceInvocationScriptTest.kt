package dev.veneranative.source.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceInvocationScriptTest {
    @Test
    fun acceptsOnlySingleGlobalIdentifiers() {
        assertTrue(SourceInvocationScript.validateFunctionName("search"))
        assertTrue(SourceInvocationScript.validateFunctionName("_private2"))
        assertTrue(SourceInvocationScript.validateFunctionName("\$host"))
        assertFalse(SourceInvocationScript.validateFunctionName("source.search"))
        assertFalse(SourceInvocationScript.validateFunctionName("search();evil"))
        assertFalse(SourceInvocationScript.validateFunctionName(""))
    }

    @Test
    fun invocationUsesJsonParsingAndJsonEnvelope() {
        val invocation =
            SourceInvocationScript.build(
                functionName = "echo",
                argumentsJson = """["a\"b",{"page":2}]""",
                invocationId = "test-call",
            )

        assertTrue(invocation.contains("""globalThis["echo"]"""))
        assertTrue(invocation.contains("JSON.parse("))
        assertTrue(invocation.contains("Promise.resolve("))
        assertTrue(invocation.contains("JSON.stringify({ value:"))
        assertTrue(invocation.contains("__veneraInvocationId"))
        assertFalse(invocation.contains("""globalThis.echo("""))
    }
}

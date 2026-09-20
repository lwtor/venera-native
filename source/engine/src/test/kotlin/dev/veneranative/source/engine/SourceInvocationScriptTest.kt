package dev.veneranative.source.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceInvocationScriptTest {

    @Test
    fun acceptsMemberPathsButNotCodeFragments() {
        assertTrue(SourceInvocationScript.validateMember("search"))
        assertTrue(SourceInvocationScript.validateMember("search.load"))
        assertTrue(SourceInvocationScript.validateMember("comic.loadEp"))
        assertTrue(SourceInvocationScript.validateMember("_private2"))
        assertTrue(SourceInvocationScript.validateMember("\$host"))
        assertTrue(SourceInvocationScript.validateMember("explore.0.load"))
        assertTrue(SourceInvocationScript.validateMember("explore.12.loadNext"))

        assertFalse(SourceInvocationScript.validateMember(""))
        // A path ends in the name of the function being called, never in an index.
        assertFalse(SourceInvocationScript.validateMember("explore.0"))
        assertFalse(SourceInvocationScript.validateMember("search..load"))
        assertFalse(SourceInvocationScript.validateMember(".search"))
        assertFalse(SourceInvocationScript.validateMember("search."))
        assertFalse(SourceInvocationScript.validateMember("search();evil"))
        assertFalse(SourceInvocationScript.validateMember("""search["load"]"""))
    }

    @Test
    fun invocationResolvesThePathAndKeepsTheDeclarationAsThis() {
        val invocation =
            SourceInvocationScript.build(
                member = "search.load",
                argumentsJson = """["a\"b",{"page":2}]""",
                invocationId = "test-call",
            )

        assertTrue(invocation.contains("""const path = ["search", "load"]"""))
        assertTrue(
            SourceInvocationScript.build(
                member = "explore.0.load",
                argumentsJson = "[]",
                invocationId = "call-2",
            ).contains("""const path = ["explore", "0", "load"]"""),
        )
        assertTrue(invocation.contains("let target = globalThis"))
        assertTrue(invocation.contains("fn.apply(target, args)"))
        assertTrue(invocation.contains("JSON.parse("))
        assertTrue(invocation.contains("Promise.resolve("))
        assertTrue(invocation.contains("JSON.stringify({ value:"))
        assertTrue(invocation.contains("__veneraInvocationId"))
        assertFalse(invocation.contains("""globalThis["search.load"]"""))
    }

    @Test
    fun theAwaitableFormAwaitsAndCanTargetTheRegistryEntry() {
        val invocation =
            SourceInvocationScript.buildAwaitable(
                member = "loadInfo",
                argumentsJson = "[]",
                invocationId = "call-1",
                root = SourceClassConvention.INSTANCE,
            )

        assertTrue(invocation.startsWith("await "))
        assertTrue(invocation.contains("let target = globalThis.__veneraSource"))
    }
}

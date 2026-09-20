package dev.veneranative.source.engine

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spike evidence for ADR-0008: the owned engine can do what the WebView engine cannot.
 *
 * The reference device has no MessagePort, so the JavaScriptEngine binding has no JS → Kotlin
 * channel at all — the project's async Host API is impossible there. This test pins the facts the
 * engine switch depends on, and it runs on the JVM because the binding ships a desktop artifact:
 *
 * 1. a source script can `await` a host call and read the value back;
 * 2. the host side suspends while the script waits;
 * 3. a host failure reaches the script as a rejected `await` instead of killing the engine;
 * 4. several host calls can be in flight at once.
 *
 * **Invocation constraint (measured, not documented)**: the code must be evaluated as a *script*
 * with top-level `await`. An async IIFE returns a `Promise` object that is handed back unsolved, and
 * module mode has no completion value (returns null). The engine adapter must therefore evaluate a
 * wrapper whose last statement awaits the source's async method, and must not wrap it in an async
 * function of its own.
 *
 * If this test cannot pass, ADR-0008's engine switch has no foundation and must be revisited.
 */
class QuickJsBridgeSpikeTest {

    @Test
    fun `a script awaits a suspending host call and reads its result`() = runTest {
        val hostCalls = mutableListOf<String>()

        val body = quickJs {
            asyncFunction("fetchJson") { args ->
                val url = args.first() as String
                hostCalls += url
                // Stands in for the Host API: the host does asynchronous work while the script is
                // parked on `await`.
                """{"status":200,"body":"payload-for:$url"}"""
            }
            evaluate<String>(
                """
                const response = await fetchJson("https://example.com/chapter/1");
                JSON.parse(response).body;
                """.trimIndent(),
            )
        }

        assertEquals("payload-for:https://example.com/chapter/1", body)
        assertEquals(listOf("https://example.com/chapter/1"), hostCalls)
    }

    @Test
    fun `a host failure reaches the script as a rejected await`() = runTest {
        val message = quickJs {
            asyncFunction("fetchJson") { _: Array<Any?> ->
                error("host is down")
            }
            evaluate<String>(
                """
                try {
                  await fetchJson("https://example.com/a");
                  "no error";
                } catch (e) {
                  String(e);
                }
                """.trimIndent(),
            )
        }

        assertTrue("expected the host failure, got: $message", message.contains("host is down"))
    }

    @Test
    fun `concurrent host calls are awaited together`() = runTest {
        val hostCalls = mutableListOf<String>()

        val count = quickJs {
            asyncFunction("fetchJson") { args ->
                hostCalls += args.first() as String
                args.first() as String
            }
            evaluate<Int>(
                """
                const results = await Promise.all([
                  fetchJson("a"),
                  fetchJson("b"),
                  fetchJson("c"),
                ]);
                results.length;
                """.trimIndent(),
            )
        }

        assertEquals(3, count)
        assertEquals(listOf("a", "b", "c"), hostCalls)
    }
}

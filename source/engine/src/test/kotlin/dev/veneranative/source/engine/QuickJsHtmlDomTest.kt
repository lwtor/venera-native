package dev.veneranative.source.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QuickJsHtmlDomTest {

    @Test
    fun `HTML byte budget is per invocation and dispose releases it`() {
        val dom = QuickJsHtmlDom(maxHtmlBytes = 15, maxNodesPerInvocation = 10)
        val invocationId = "fixture-call"

        dom.invoke(request(invocationId, "create", html = "<p>one</p>"))
        assertThrows(IllegalArgumentException::class.java) {
            dom.invoke(request(invocationId, "create", html = "<p>two</p>"))
        }

        dom.invoke(request(invocationId, "dispose"))
        val nextCall = JSONObject(dom.invoke(request("next-call", "create", html = "<p>two</p>")))
        assertEquals("d1", nextCall.getString("documentId"))
    }

    private fun request(invocationId: String, action: String, html: String = ""): String =
        JSONObject()
            .put("invocationId", invocationId)
            .put("action", action)
            .put("html", html)
            .toString()
}

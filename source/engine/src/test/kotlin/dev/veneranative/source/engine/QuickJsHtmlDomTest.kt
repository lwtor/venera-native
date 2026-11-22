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

    @Test
    fun `chapter JSON in script data is returned without normalizing whitespace`() {
        val dom = QuickJsHtmlDom()
        val json = """[{"cid":1,"title":"Chapter  1"},{"cid":2,"title":"Chapter 2"}]"""
        val created = JSONObject(dom.invoke(request("chapters", "create", "<script id='chapters' type='text/plain'>$json</script><p>A   B</p>")))
        val selected = JSONObject(dom.invoke(JSONObject().put("invocationId", "chapters")
            .put("action", "getElementById").put("documentId", created.getString("documentId"))
            .put("id", "chapters").toString()))
        val text = JSONObject(dom.invoke(JSONObject().put("invocationId", "chapters")
            .put("action", "text").put("elementId", selected.getString("elementId")).toString()))
        assertEquals(json, text.getString("value"))
    }

    @Test
    fun `large catalogs fit the bounded HTML budget while oversized documents are rejected`() {
        val dom = QuickJsHtmlDom()
        // Synthetic whitespace models a long catalog without copying site content.
        dom.invoke(request("large", "create", "<p>" + " ".repeat(2_500_000) + "</p>"))
        assertThrows(IllegalArgumentException::class.java) {
            dom.invoke(request("oversized", "create", "<p>" + " ".repeat(4 * 1_048_576) + "</p>"))
        }
        // JSON escaping can double the bridge envelope without increasing UTF-8 HTML size.
        dom.invoke(request("escaped", "create", "<script>" + "\"".repeat(2_500_000) + "</script>"))
    }

    private fun request(invocationId: String, action: String, html: String = ""): String =
        JSONObject()
            .put("invocationId", invocationId)
            .put("action", action)
            .put("html", html)
            .toString()
}

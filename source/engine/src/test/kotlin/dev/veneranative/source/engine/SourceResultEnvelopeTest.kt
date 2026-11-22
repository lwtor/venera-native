package dev.veneranative.source.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceResultEnvelopeTest {
    @Test fun `decoding retains nested chapter and group insertion order`() {
        val envelope = """{"value":{"chapters":{"main":{"ep_17":"First","ep_4":"Second","ep_30":"Third"},"extras":{"ep_5":"Extra"}}},"sourceData":{"z":1,"a":2}}"""
        val decoded = SourceResultEnvelope.decode(envelope)
        val chapters = Json.parseToJsonElement(decoded.valueJson).jsonObject.getValue("chapters").jsonObject
        assertEquals(listOf("main", "extras"), chapters.keys.toList())
        assertEquals(listOf("ep_17", "ep_4", "ep_30"), chapters.getValue("main").jsonObject.keys.toList())
        assertEquals(listOf("z", "a"), Json.parseToJsonElement(decoded.sourceDataJson!!).jsonObject.keys.toList())
    }

    @Test fun `decoding preserves primitive and null values and rejects invalid envelopes`() {
        for (value in listOf("null", "true", "42", "\"a\\nb\"", "[1,2]")) {
            assertEquals(value, SourceResultEnvelope.extract("{\"value\":$value}"))
        }
        for (envelope in listOf("[]", "{}", "broken", "{\"value\":1,\"sourceData\":[]}")) {
            assertThrows(JSONException::class.java) { SourceResultEnvelope.decode(envelope) }
        }
    }
}

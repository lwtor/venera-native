package dev.veneranative.source.engine

import org.json.JSONException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The `{ value }` envelope [SourceInvocationScript] produces, decoded back into result JSON.
 *
 * Both engines return the envelope as a string, so the decoding lives here instead of in each
 * runtime: a difference between the two would be a difference in what a source can return.
 */
internal object SourceResultEnvelope {
    private const val VALUE_KEY = "value"
    private const val SOURCE_DATA_KEY = "sourceData"

    data class Decoded(val valueJson: String, val sourceDataJson: String?)

    /**
     * @throws JSONException when the envelope is malformed or holds a value that cannot be JSON.
     */
    fun extract(envelope: String): String {
        return decode(envelope).valueJson
    }

    fun decode(envelope: String): Decoded {
        // org.json object iteration is implementation dependent (HashMap on the JVM).
        // Chapter object order is source-defined reading order, so keep the JSON parser's order.
        val result = try {
            Json.parseToJsonElement(envelope) as? JsonObject
                ?: throw JSONException("Result envelope must be a JSON object.")
        } catch (failure: IllegalArgumentException) {
            throw JSONException("Invalid result envelope.").apply { initCause(failure) }
        }
        val valueJson = result[VALUE_KEY]?.toString() ?: throw JSONException("Missing result value.")
        val sourceDataJson = when (val sourceData = result[SOURCE_DATA_KEY]) {
            null, JsonNull -> null
            is JsonObject -> sourceData.toString()
            else -> throw JSONException("Source data must be a JSON object.")
        }
        return Decoded(valueJson, sourceDataJson)
    }
}

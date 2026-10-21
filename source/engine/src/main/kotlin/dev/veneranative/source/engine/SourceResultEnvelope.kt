package dev.veneranative.source.engine

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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
        val result = JSONObject(envelope)
        if (!result.has(VALUE_KEY)) {
            throw JSONException("Missing result value.")
        }
        val valueJson = when (val value = result.get(VALUE_KEY)) {
            JSONObject.NULL -> "null"
            is String -> JSONObject.quote(value)
            is Number, is Boolean, is JSONObject, is JSONArray -> value.toString()
            else -> throw JSONException("Unsupported result value.")
        }
        val sourceData = result.opt(SOURCE_DATA_KEY)
        val sourceDataJson = when (sourceData) {
            null, JSONObject.NULL -> null
            is JSONObject -> sourceData.toString()
            else -> throw JSONException("Source data must be a JSON object.")
        }
        return Decoded(valueJson, sourceDataJson)
    }
}

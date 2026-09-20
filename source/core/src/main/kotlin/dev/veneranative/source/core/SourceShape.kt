package dev.veneranative.source.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * What the engine's structure probe reported about one value on a source object.
 *
 * Deliberately shape-only: the host learns *that* `search.load` is callable and *that* `explore` is
 * an array, never what the code does. The engine caps depth, item and key counts, so descriptions of
 * large declared data (translations, tag tables) stay small.
 *
 * Kotlin `null` means "nothing there": the value was absent, was JSON null, or could not be decoded.
 */
internal sealed interface SourceShape {
    data class Text(val value: String) : SourceShape

    data class Number(val value: Double) : SourceShape

    data class Flag(val value: Boolean) : SourceShape

    data object Callable : SourceShape

    data class ListOf(val items: List<SourceShape?>) : SourceShape

    data class Record(val entries: Map<String, SourceShape?>) : SourceShape

    companion object {
        /** The engine reports a function as this marker string. */
        private const val CALLABLE_MARKER = "function"

        private val json = Json

        /**
         * Decodes one probe answer.
         *
         * The answer arrives as a JSON **string** (it is a script return value), so it is decoded
         * twice: once to unwrap the string, once to read the description inside it.
         */
        fun decode(encoded: String): SourceShape? {
            val description =
                runCatching {
                    (json.parseToJsonElement(encoded) as? JsonPrimitive)
                        ?.takeIf(JsonPrimitive::isString)
                        ?.content
                }.getOrNull() ?: return null
            return runCatching { parse(json.parseToJsonElement(description)) }.getOrNull()
        }

        fun parse(element: JsonElement): SourceShape? = when (element) {
            is JsonNull -> null

            is JsonPrimitive -> when {
                element.isString ->
                    if (element.content == CALLABLE_MARKER) Callable else Text(element.content)

                else ->
                    element.booleanOrNull?.let(::Flag)
                        ?: element.doubleOrNull?.let(::Number)
            }

            is JsonArray -> ListOf(element.map(::parse))

            is JsonObject -> when ((element["type"] as? JsonPrimitive)?.content) {
                "array" -> ListOf((element["items"] as? JsonArray).orEmpty().map(::parse))
                "object" -> Record(
                    (element["entries"] as? JsonObject).orEmpty().mapValues { parse(it.value) },
                )

                else -> Record(element.mapValues { parse(it.value) })
            }
        }
    }
}

/** One declared member of an object, or null when it is not declared. */
internal fun SourceShape?.entry(name: String): SourceShape? =
    (this as? SourceShape.Record)?.entries?.get(name)

internal fun SourceShape?.items(): List<SourceShape?> =
    (this as? SourceShape.ListOf)?.items.orEmpty()

internal fun SourceShape?.text(): String? = (this as? SourceShape.Text)?.value

internal fun SourceShape?.isCallable(): Boolean = this is SourceShape.Callable

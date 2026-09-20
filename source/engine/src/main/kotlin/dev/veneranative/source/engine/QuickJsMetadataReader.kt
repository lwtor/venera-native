package dev.veneranative.source.engine

import com.dokar.quickjs.QuickJs
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourceMetadata
import dev.veneranative.source.api.SourceMetadataReader
import dev.veneranative.source.api.SourceMetadataResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads a source's declared metadata by running it in a throwaway engine.
 *
 * `key`, `name` and `version` only exist once the script has executed, so this cannot be a text
 * scan (ADR-0007 §2.1). The engine is created for this one read and closed immediately: an install
 * that fails validation must not leave a loaded source behind.
 *
 * The script under inspection may not be safe to run twice, which is why it gets its own engine
 * rather than sharing an installed source's.
 */
class QuickJsMetadataReader(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : SourceMetadataReader {

    override suspend fun read(script: String): SourceMetadataResult {
        if (script.isBlank()) {
            return SourceMetadataResult.Invalid("Source script must not be blank.")
        }

        val engine =
            try {
                QuickJs.create(dispatcher)
            } catch (failure: Throwable) {
                return SourceMetadataResult.EngineUnavailable(
                    failure.message?.take(MAX_REASON_LENGTH)
                        ?: "JavaScript engine is not available on this device.",
                )
            }

        return engine.use {
            val encoded =
                try {
                    withTimeout(timeoutMillis) {
                        engine.evaluate<String>(script + "\n" + METADATA_PROBE)
                    }
                } catch (_: TimeoutCancellationException) {
                    return@use SourceMetadataResult.Invalid(
                        "Source script did not declare its metadata within ${timeoutMillis}ms.",
                    )
                } catch (failure: Throwable) {
                    return@use SourceMetadataResult.Invalid(
                        failure.message?.take(MAX_REASON_LENGTH)
                            ?: "Source script could not be read.",
                    )
                }
            parse(encoded)
        }
    }

    private fun parse(encoded: String): SourceMetadataResult {
        val declared =
            try {
                JSONObject(encoded)
            } catch (failure: JSONException) {
                return SourceMetadataResult.Invalid("Source metadata could not be decoded.")
            }

        val key = declared.stringOrNull(KEY_FIELD)
            ?: return SourceMetadataResult.Invalid("Source must declare a 'key'.")
        val name = declared.stringOrNull(NAME_FIELD)
            ?: return SourceMetadataResult.Invalid("Source must declare a 'name'.")
        val version = declared.stringOrNull(VERSION_FIELD)
            ?: return SourceMetadataResult.Invalid("Source must declare a 'version'.")

        return try {
            SourceMetadataResult.Success(
                SourceMetadata(
                    sourceId = SourceId(key),
                    name = name,
                    version = version,
                    minAppVersion = declared.stringOrNull(MIN_APP_VERSION_FIELD),
                ),
            )
        } catch (_: IllegalArgumentException) {
            SourceMetadataResult.Invalid("Source 'key' is not usable as an identity.")
        }
    }

    private fun JSONObject.stringOrNull(field: String): String? =
        if (isNull(field)) null else getString(field).trim().takeIf(String::isNotEmpty)

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val MAX_REASON_LENGTH = 512
        const val KEY_FIELD = "key"
        const val NAME_FIELD = "name"
        const val VERSION_FIELD = "version"
        const val MIN_APP_VERSION_FIELD = "minAppVersion"

        /**
         * Runs after the script, in the same engine, so it sees whatever the script declared.
         * `typeof` is used deliberately: an undeclared name must be reported as missing rather than
         * throwing a ReferenceError that would look like a broken script.
         */
        val METADATA_PROBE =
            """
            ;JSON.stringify({
              key: typeof key === "string" ? key : null,
              name: typeof name === "string" ? name : null,
              version: typeof version === "string" ? version : null,
              minAppVersion: typeof minAppVersion === "string" ? minAppVersion : null
            });
            """.trimIndent()
    }
}

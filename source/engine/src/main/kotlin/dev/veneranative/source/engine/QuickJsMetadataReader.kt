package dev.veneranative.source.engine

import com.dokar.quickjs.QuickJs
import dev.veneranative.core.model.SourceId
import dev.veneranative.core.model.SourceMetadata
import dev.veneranative.core.model.SourceSettingDefinition
import dev.veneranative.core.model.SourceSettingOption
import dev.veneranative.source.api.SourceMetadataReader
import dev.veneranative.source.api.SourceMetadataResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads a source's declared metadata by running it in a throwaway engine.
 *
 * `name`, `key` and `version` are instance fields, not literals, so the script has to run: the
 * documented convention is a class extending `ComicSource`, instantiated after evaluation
 * ([SourceClassConvention]). Reading them by text search would accept scripts that only look right.
 *
 * The engine is created for this one read and closed immediately: an install that fails validation
 * must not leave a loaded source behind, and the script under inspection is not trusted to be
 * re-runnable.
 */
class QuickJsMetadataReader(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : SourceMetadataReader {

    override suspend fun read(script: String): SourceMetadataResult {
        if (script.isBlank()) {
            return SourceMetadataResult.Invalid("Source script must not be blank.")
        }

        val className =
            SourceClassConvention.classNameOf(script)
                ?: return SourceMetadataResult.Invalid(
                    "Source script must declare 'class <Name> extends ComicSource' at the start " +
                        "of a line.",
                )

        val engine =
            try {
                QuickJs.create(dispatcher)
            } catch (failure: Throwable) {
                return SourceMetadataResult.EngineUnavailable(
                    failure.message?.take(MAX_REASON_LENGTH)
                        ?: "JavaScript engine is not available on this device.",
                )
            }

        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val evaluation = scope.async {
            engine.use {
                        // The base class has to exist before the script's class can extend it.
                        engine.evaluate<Any?>(SourceBaseScript.script)
                        engine.evaluate<String>(
                            SourceClassConvention.instantiationScript(script, className) +
                                "\n" +
                                METADATA_PROBE,
                        )
            }
        }
        return try {
            parse(withTimeout(timeoutMillis) { evaluation.await() })
        } catch (_: TimeoutCancellationException) {
            Thread { runCatching { engine.close() } }.apply {
                isDaemon = true
                name = "quickjs-metadata-close"
            }.start()
            SourceMetadataResult.Invalid(
                        "Source script did not declare its metadata within ${timeoutMillis}ms.",
            )
        } catch (failure: Throwable) {
            SourceMetadataResult.Invalid(
                        failure.message?.take(MAX_REASON_LENGTH)
                            ?: "Source script could not be read.",
            )
        } finally {
            scope.cancel()
        }
    }

    private fun parse(encoded: String): SourceMetadataResult {
        val declared =
            try {
                JSONObject(encoded)
            } catch (_: JSONException) {
                return SourceMetadataResult.Invalid("Source metadata could not be decoded.")
            }

        val key = declared.stringOrNull(KEY_FIELD)
            ?: return SourceMetadataResult.Invalid("Source must declare a 'key'.")
        val name = declared.stringOrNull(NAME_FIELD)
            ?: return SourceMetadataResult.Invalid("Source must declare a 'name'.")
        val version = declared.stringOrNull(VERSION_FIELD)
            ?: return SourceMetadataResult.Invalid("Source must declare a 'version'.")
        if (!SourceClassConvention.isUsableKey(key)) {
            return SourceMetadataResult.Invalid(
                "Source 'key' must contain only letters, digits and underscores.",
            )
        }

        return try {
            SourceMetadataResult.Success(
                SourceMetadata(
                    sourceId = SourceId(key),
                    name = name,
                    version = version,
                    minAppVersion = declared.stringOrNull(MIN_APP_VERSION_FIELD),
                    settings = declared.optJSONArray(SETTINGS_FIELD).let { array ->
                        (0 until (array?.length() ?: 0)).mapNotNull { index ->
                            val item = array?.optJSONObject(index) ?: return@mapNotNull null
                            val key = item.optString("key").takeIf { it.matches(SETTING_KEY_PATTERN) }
                                ?: return@mapNotNull null
                            val type = when (item.optString("type")) {
                                "input" -> SourceSettingDefinition.Type.INPUT
                                "select" -> SourceSettingDefinition.Type.SELECT
                                "switch" -> SourceSettingDefinition.Type.SWITCH
                                "callback" -> SourceSettingDefinition.Type.CALLBACK
                                else -> return@mapNotNull null
                            }
                            val options = item.optJSONArray("options")?.let { values ->
                                (0 until values.length()).mapNotNull { optionIndex ->
                                    val option = values.optJSONObject(optionIndex) ?: return@mapNotNull null
                                    val value = option.optString("value").takeIf(String::isNotEmpty)
                                        ?: return@mapNotNull null
                                    SourceSettingOption(value, option.optString("text").ifBlank { value })
                                }.take(MAX_SETTING_OPTIONS)
                            }.orEmpty()
                            SourceSettingDefinition(
                                key = key,
                                title = item.optString("title").ifBlank { key },
                                type = type,
                                defaultJson = item.optString("defaultJson").takeIf { it != "null" && it.isNotBlank() },
                                options = options,
                                actionLabel = item.optString("buttonText").takeIf(String::isNotBlank),
                            )
                        }.take(MAX_SETTINGS)
                    },
                    supportsPasswordLogin = declared.optBoolean("passwordLogin", false),
                    supportsLogout = declared.optBoolean("logout", false),
                    registerWebsite = declared.stringOrNull("registerWebsite"),
                ),
            )
        } catch (_: IllegalArgumentException) {
            SourceMetadataResult.Invalid("Source 'key' is not usable as an identity.")
        }
    }

    private fun JSONObject.stringOrNull(field: String): String? {
        if (isNull(field)) return null
        return getString(field).trim().takeIf(String::isNotEmpty)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val MAX_REASON_LENGTH = 512
        const val KEY_FIELD = "key"
        const val NAME_FIELD = "name"
        const val VERSION_FIELD = "version"
        const val MIN_APP_VERSION_FIELD = "minAppVersion"
        const val SETTINGS_FIELD = "settings"
        const val MAX_SETTINGS = 32
        const val MAX_SETTING_OPTIONS = 32
        val SETTING_KEY_PATTERN = Regex("^[A-Za-z0-9_.-]{1,80}$")

        /**
         * Runs after the instance exists, in the same engine. `typeof` keeps an undeclared or
         * non-string field from turning into a ReferenceError that would look like a broken script.
         */
        val METADATA_PROBE: String =
            """
            ;JSON.stringify({
              key: typeof ${SourceClassConvention.INSTANCE}.key === "string" ? ${SourceClassConvention.INSTANCE}.key : null,
              name: typeof ${SourceClassConvention.INSTANCE}.name === "string" ? ${SourceClassConvention.INSTANCE}.name : null,
              version: typeof ${SourceClassConvention.INSTANCE}.version === "string" ? ${SourceClassConvention.INSTANCE}.version : null,
              minAppVersion: typeof ${SourceClassConvention.INSTANCE}.minAppVersion === "string" ? ${SourceClassConvention.INSTANCE}.minAppVersion : null,
              settings: (function() {
                var settings = ${SourceClassConvention.INSTANCE}.settings;
                if (!settings || typeof settings !== "object") return [];
                return Object.keys(settings).slice(0, 32).map(function(key) {
                  var setting = settings[key] || {};
                  return {
                    key: key,
                    title: typeof setting.title === "string" ? setting.title : key,
                    type: typeof setting.type === "string" ? setting.type : "",
                    defaultJson: setting.default === undefined ? null : JSON.stringify(setting.default),
                    buttonText: typeof setting.buttonText === "string" ? setting.buttonText : null,
                    options: Array.isArray(setting.options) ? setting.options.slice(0, 32).map(function(option) {
                      return {value: String(option && option.value != null ? option.value : ""), text: String(option && option.text != null ? option.text : "")};
                    }) : []
                  };
                });
              })(),
              passwordLogin: !!(${SourceClassConvention.INSTANCE}.account && typeof ${SourceClassConvention.INSTANCE}.account.login === "function"),
              logout: !!(${SourceClassConvention.INSTANCE}.account && typeof ${SourceClassConvention.INSTANCE}.account.logout === "function"),
              registerWebsite: typeof ${SourceClassConvention.INSTANCE}.account?.registerWebsite === "string" ? ${SourceClassConvention.INSTANCE}.account.registerWebsite : null
            });
            """.trimIndent()
    }
}

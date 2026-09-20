package dev.veneranative.source.engine

/**
 * How a source script exposes its object to the host.
 *
 * This is not our invention: it is copied from the upstream host
 * (`lib/foundation/comic_source/parser.dart`, read 2026-09-20) so that existing sources work
 * unchanged (ADR-0007). Upstream:
 *
 * 1. finds the **first line** whose trimmed text starts with `class ` — the raw line must start with
 *    `class ` too, so an indented class declaration is rejected — and that contains
 *    `extends ComicSource`;
 * 2. wraps the whole script in an immediately-invoked function and instantiates the class;
 * 3. reads `name` / `key` / `version` / `minAppVersion` / `url` off that instance;
 * 4. registers the instance as `ComicSource.sources[key]` and calls members through that registry,
 *    for example `ComicSource.sources[key].search.load(...)`.
 *
 * Members are therefore **paths**, not identifiers, which is why [SourceInvocationScript] resolves
 * dotted names rather than looking up a global function.
 */
internal object SourceClassConvention {

    const val BASE_CLASS = "ComicSource"
    const val REGISTRY = "globalThis.$BASE_CLASS.sources"

    /** Where one loaded engine keeps its instantiated source. */
    const val INSTANCE = "globalThis.__veneraSource"

    const val INSTANTIATED_SENTINEL = "__VENERA_SOURCE_INSTANTIATED__"

    /** Upstream accepts letters, digits and underscores only, because the key is used as an id. */
    private val keyPattern = Regex("^[A-Za-z0-9_]+$")

    /**
     * @return the class name, or null when the script does not follow the convention.
     */
    fun classNameOf(script: String): String? {
        val line =
            script.replace("\r\n", "\n")
                .lineSequence()
                .firstOrNull { it.trim().startsWith("class ") }
                ?: return null
        if (!line.startsWith("class ") || !line.contains("extends $BASE_CLASS")) {
            return null
        }
        return line.substringAfter("class ")
            .substringBefore("extends $BASE_CLASS")
            .trim()
            .takeIf(String::isNotEmpty)
    }

    fun isUsableKey(key: String): Boolean = keyPattern.matches(key)

    /**
     * Evaluates the script and leaves the instance in [INSTANCE].
     *
     * The script is embedded as-is (not evaluated separately) because it declares the class in its
     * own lexical scope; the completion value is [INSTANTIATED_SENTINEL] so the caller can tell a
     * script that ran from one that quietly did nothing.
     */
    fun instantiationScript(script: String, className: String): String = buildString {
        append("(() => {\n")
        append(script)
        append("\n")
        append("  $INSTANCE = new $className();\n")
        append("  return ${jsQuote(INSTANTIATED_SENTINEL)};\n")
        append("})()\n")
    }

    /** Puts the instance into the registry upstream members are reached through. */
    fun registrationScript(key: String): String =
        "$REGISTRY[${jsQuote(key)}] = $INSTANCE;\n" +
            "${jsQuote(REGISTERED_SENTINEL)};\n"

    /** Reads one string field off the instance, `null` when it is absent or not a string. */
    fun fieldScript(field: String): String =
        "JSON.stringify(typeof $INSTANCE.$field === \"string\" ? $INSTANCE.$field : null)"

    /** Calls `init()` when the source declares one; sources without it must not fail. */
    val INIT_SCRIPT: String =
        """
        if (typeof $INSTANCE.init === "function") {
          await $INSTANCE.init();
        }
        """.trimIndent()

    const val REGISTERED_SENTINEL = "__VENERA_SOURCE_REGISTERED__"
}

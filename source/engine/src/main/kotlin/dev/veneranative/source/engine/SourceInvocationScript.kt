package dev.veneranative.source.engine

/**
 * Builds the JavaScript that invokes one member of a source.
 *
 * A member is a **path** (`search.load`, `comic.loadEp`), not a global function name, because that
 * is how upstream reaches them; the call goes through the loaded source's registry entry so `this`
 * inside the member is the object that declares it, exactly as upstream calls it
 * (see [SourceClassConvention]).
 *
 * The legacy WebView runtime keeps [DEFAULT_ROOT]: its fixture declares flat globals, and a
 * single-segment path on the global object is the same thing.
 */
internal object SourceInvocationScript {
    private val namePattern = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

    /** Explore pages are declared as an array, so their position addresses them: `explore.0.load`. */
    private val indexPattern = Regex("^[0-9]{1,6}$")

    /** `root` is a JavaScript expression the member path is resolved against. */
    const val DEFAULT_ROOT = "globalThis"

    fun validateMember(member: String): Boolean {
        if (member.isEmpty()) return false
        val segments = member.split('.')
        // Whatever the path walks through, it ends in the name of the function being called.
        if (!namePattern.matches(segments.last())) return false
        return segments.all { namePattern.matches(it) || indexPattern.matches(it) }
    }

    fun build(
        member: String,
        argumentsJson: String,
        invocationId: String,
        root: String = DEFAULT_ROOT,
    ): String {
        require(validateMember(member)) { "Invalid source member path." }

        val quotedMember = jsQuote(member)
        val quotedArguments = jsQuote(argumentsJson)
        val quotedInvocationId = jsQuote(invocationId)
        val segments = member.split('.').joinToString(separator = ", ") { jsQuote(it) }
        return """
            (function() {
              const path = [$segments];
              let target = $root;
              for (let index = 0; index < path.length - 1 && target != null; index += 1) {
                target = target[path[index]];
              }
              const fn = target == null ? undefined : target[path[path.length - 1]];
              if (typeof fn !== "function") {
                throw new Error("Unknown source member: " + $quotedMember);
              }
              const args = JSON.parse($quotedArguments);
              if (!Array.isArray(args)) {
                throw new Error("Source function arguments must be a JSON array.");
              }
              const previousInvocationId = globalThis.__veneraInvocationId;
              globalThis.__veneraInvocationId = $quotedInvocationId;
              return Promise.resolve(fn.apply(target, args))
                .then(function(value) {
                  return JSON.stringify({ value: value === undefined ? null : value });
                })
                .finally(function() {
                  if (previousInvocationId === undefined) {
                    delete globalThis.__veneraInvocationId;
                  } else {
                    globalThis.__veneraInvocationId = previousInvocationId;
                  }
                });
            })()
        """.trimIndent()
    }

    /**
     * The same wrapper, as an expression an engine with a completion value must await.
     *
     * An engine that hands back the value of the last expression returns the *Promise* [build]
     * created; awaiting it in the same script yields the envelope itself (ADR-0008 §8).
     */
    fun buildAwaitable(
        member: String,
        argumentsJson: String,
        invocationId: String,
        root: String = DEFAULT_ROOT,
    ): String = "await " + build(member, argumentsJson, invocationId, root)
}

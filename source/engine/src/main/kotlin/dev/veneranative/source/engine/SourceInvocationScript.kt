package dev.veneranative.source.engine

internal object SourceInvocationScript {
    private val functionNamePattern = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

    fun validateFunctionName(functionName: String): Boolean =
        functionNamePattern.matches(functionName)

    fun build(functionName: String, argumentsJson: String): String {
        require(validateFunctionName(functionName)) { "Invalid JavaScript function name." }

        val quotedFunctionName = quote(functionName)
        val quotedArguments = quote(argumentsJson)
        return """
            (function() {
              const fn = globalThis[$quotedFunctionName];
              if (typeof fn !== "function") {
                throw new Error("Unknown source function: " + $quotedFunctionName);
              }
              const args = JSON.parse($quotedArguments);
              if (!Array.isArray(args)) {
                throw new Error("Source function arguments must be a JSON array.");
              }
              return Promise.resolve(fn.apply(undefined, args)).then(function(value) {
                return JSON.stringify({ value: value === undefined ? null : value });
              });
            })()
        """.trimIndent()
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }
}

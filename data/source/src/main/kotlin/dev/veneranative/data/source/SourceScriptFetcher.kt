package dev.veneranative.data.source

import java.io.File
import java.net.URI

/**
 * Reads source script text from wherever the user pointed at.
 *
 * Only local locations are implemented so far, which is what the install tests need. Fetching from
 * a repository URL is added together with the source repository client, and it must reuse the
 * app HTTP client rather than opening its own connections.
 */
interface SourceScriptFetcher {
    suspend fun fetch(location: String): FetchedScript
}

sealed interface FetchedScript {
    data class Success(val script: String) : FetchedScript

    data class Failure(val reason: String) : FetchedScript
}

/** Reads a plain file path or a `file://` URI. */
class LocalFileScriptFetcher : SourceScriptFetcher {

    override suspend fun fetch(location: String): FetchedScript {
        val path = when {
            location.startsWith("file:", ignoreCase = true) ->
                runCatching { File(URI(location)) }.getOrNull()
                    ?: return FetchedScript.Failure("Not a usable file location.")

            else -> File(location)
        }
        if (!path.isFile) return FetchedScript.Failure("No source script at this location.")
        return runCatching { path.readText() }
            .map { script ->
                if (script.isBlank()) {
                    FetchedScript.Failure("The source script is empty.")
                } else {
                    FetchedScript.Success(script)
                }
            }
            .getOrElse { FetchedScript.Failure("The source script could not be read.") }
    }
}

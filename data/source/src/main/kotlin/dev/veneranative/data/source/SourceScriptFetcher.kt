package dev.veneranative.data.source

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

/** Reads Storage Access Framework documents and falls back to ordinary local paths. */
class AndroidSourceScriptFetcher(
    context: Context,
    private val local: SourceScriptFetcher = LocalFileScriptFetcher(),
) : SourceScriptFetcher {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun fetch(location: String): FetchedScript {
        if (!location.startsWith("content:", ignoreCase = true)) return local.fetch(location)
        return withContext(Dispatchers.IO) {
            runCatching {
                resolver.openInputStream(Uri.parse(location))?.bufferedReader()?.use { it.readText() }
            }.fold(
                onSuccess = { script ->
                    if (script.isNullOrBlank()) FetchedScript.Failure("The source script is empty.")
                    else FetchedScript.Success(script)
                },
                onFailure = { FetchedScript.Failure("The source script could not be read.") },
            )
        }
    }
}

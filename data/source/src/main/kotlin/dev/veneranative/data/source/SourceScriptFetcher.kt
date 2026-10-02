package dev.veneranative.data.source

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

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
                if (script.isBlank()) FetchedScript.Failure("The source script is empty.")
                else FetchedScript.Success(script)
            }
            .getOrElse { FetchedScript.Failure("The source script could not be read.") }
    }
}

/** Reads SAF documents/local files or downloads bounded HTTPS scripts with the shared client. */
class AndroidSourceScriptFetcher(
    context: Context,
    private val httpClient: OkHttpClient,
    private val local: SourceScriptFetcher = LocalFileScriptFetcher(),
) : SourceScriptFetcher {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun fetch(location: String): FetchedScript {
        if (location.startsWith("https:", ignoreCase = true)) {
            return when (val result = HttpTextRequest(httpClient, MAX_SCRIPT_BYTES).get(location)) {
                is HttpTextResult.Success -> if (result.text.isBlank()) {
                    FetchedScript.Failure("The source script is empty.")
                } else {
                    FetchedScript.Success(result.text)
                }
                HttpTextResult.Failure -> FetchedScript.Failure("The source script could not be downloaded.")
            }
        }
        if (location.startsWith("http:", ignoreCase = true)) {
            return FetchedScript.Failure("Source scripts must use HTTPS.")
        }
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

    private companion object {
        const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
    }
}

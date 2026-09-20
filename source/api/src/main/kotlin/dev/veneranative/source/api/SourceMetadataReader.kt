package dev.veneranative.source.api

import dev.veneranative.core.model.SourceMetadata

/**
 * Reads the metadata a source script declares about itself.
 *
 * It takes raw script text rather than an installed package on purpose: `SourcePackage` already
 * needs an id and a version, and those are exactly what the metadata provides. This is also why the
 * reader must run the script in a throwaway isolate instead of trusting a text scan.
 *
 * The engine-backed implementation arrives with the engine work; until then callers use a test
 * implementation. Nothing here may expose the script or the engine to callers.
 */
interface SourceMetadataReader {

    suspend fun read(script: String): SourceMetadataResult
}

sealed interface SourceMetadataResult {
    data class Success(val metadata: SourceMetadata) : SourceMetadataResult

    /**
     * The script could not be read at all: syntax error, missing required field, or a key that is
     * not usable as an identity.
     */
    data class Invalid(val reason: String) : SourceMetadataResult

    data class EngineUnavailable(val reason: String) : SourceMetadataResult
}

package dev.veneranative.core.model

@JvmInline
value class SourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceId must not be blank" }
    }
}

@JvmInline
value class RemoteComicId(val value: String) {
    init {
        require(value.isNotBlank()) { "RemoteComicId must not be blank" }
    }
}

data class ComicKey(
    val sourceId: SourceId,
    val remoteId: RemoteComicId,
)

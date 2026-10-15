package dev.veneranative.core.model

/** Persistent user-retained artwork, separate from the reclaimable authenticated network cache. */
interface ComicCoverStorage {
    /** Returns actual successful fetch time; failure must retain the previous valid cover. */
    suspend fun retain(key: ComicKey, url: String, force: Boolean = false): Long?
    suspend fun available(key: ComicKey): Boolean
}

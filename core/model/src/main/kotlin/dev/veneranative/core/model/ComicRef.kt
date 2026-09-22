package dev.veneranative.core.model

/**
 * Id of a comic that lives on the device instead of behind a source script.
 *
 * The app mints these itself when it imports a directory or an archive, so the value is opaque
 * here: all a caller may assume is that it stays stable for as long as the comic is imported.
 */
@JvmInline
value class LocalComicId(val value: String) {
    init {
        require(value.isNotBlank()) { "LocalComicId must not be blank" }
    }
}

/**
 * Source column value for comics that have no source at all.
 *
 * Source keys come from scripts the app installs, and source installation rejects keys starting
 * with `@`, so a remote comic can never collide with this namespace. That is what lets a string
 * column carry both kinds of comic without a second schema.
 */
const val LOCAL_REF_NAMESPACE: String = "@local"

/**
 * A comic the app can open, wherever its pages come from.
 *
 * Sealed rather than a tagged string so a caller cannot hand a local id to a source call by
 * accident: the type itself says which half of the app owns it.
 *
 * Favourites (S2-01) are the first thing that needs it, because a shelf has to be able to hold an
 * imported comic next to a remote one. Chapter-level identity arrives with the reader work in
 * S2-04; adding it now would drag the reader, the route encoding and the history tables into this
 * slice for no benefit.
 */
sealed interface ComicRef {

    /** A comic reached through an installed source. */
    data class Remote(val key: ComicKey) : ComicRef

    /** A comic imported from the device. */
    data class Local(val id: LocalComicId) : ComicRef
}

/** The key behind a remote comic, or null when this ref is local and has no source. */
fun ComicRef.comicKeyOrNull(): ComicKey? = (this as? ComicRef.Remote)?.key

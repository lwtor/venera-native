package dev.veneranative.core.navigation

import dev.veneranative.core.model.ChapterKey
import dev.veneranative.core.model.ComicKey
import dev.veneranative.core.model.RemoteChapterId
import dev.veneranative.core.model.RemoteComicId
import dev.veneranative.core.model.SourceId

/**
 * A route as one string, so the assembly layer can keep navigation state across process death.
 *
 * Deliberately plain functions rather than a Compose `Saver`: this module holds the routing contract
 * and stays free of UI dependencies, and the app wraps these in whatever saver it needs.
 *
 * Ids come from source scripts and can contain any character, so `:` and `%` are escaped instead of
 * trusting separators to be unambiguous.
 */
fun AppRoute.encode(): String = when (this) {
    AppRoute.Home -> "home"
    AppRoute.Sources -> "sources"
    AppRoute.Library -> "library"
    is AppRoute.Explore -> "explore:${sourceId.escape()}"
    is AppRoute.Search -> "search:${sourceId.escape()}"
    is AppRoute.ComicDetails ->
        "comic:${comicKey.sourceId.value.escape()}:${comicKey.remoteId.value.escape()}"

    is AppRoute.Reader ->
        "reader:${chapter.comicKey.sourceId.value.escape()}:" +
            "${chapter.comicKey.remoteId.value.escape()}:${chapter.remoteId.value.escape()}"
}

/** The route [encoded] describes, or null when it is not a route this app version understands. */
fun decodeAppRoute(encoded: String): AppRoute? {
    val parts = encoded.split(':')
    return when (parts.firstOrNull()) {
        "home" -> AppRoute.Home
        "sources" -> AppRoute.Sources
        "library" -> AppRoute.Library
        "explore" -> parts.getOrNull(1)?.let { AppRoute.Explore(it.unescapeOrNull()) } ?: AppRoute.Explore(null)
        "search" -> parts.getOrNull(1)?.let { AppRoute.Search(it.unescapeOrNull()) } ?: AppRoute.Search(null)

        "comic" -> {
            val sourceId = parts.getOrNull(1)?.unescapeOrNull()
            val comicId = parts.getOrNull(2)?.unescapeOrNull()
            if (sourceId.isNullOrEmpty() || comicId.isNullOrEmpty()) {
                null
            } else {
                AppRoute.ComicDetails(ComicKey(SourceId(sourceId), RemoteComicId(comicId)))
            }
        }

        "reader" -> {
            val sourceId = parts.getOrNull(1)?.unescapeOrNull()
            val comicId = parts.getOrNull(2)?.unescapeOrNull()
            val chapterId = parts.getOrNull(3)?.unescapeOrNull()
            if (sourceId.isNullOrEmpty() || comicId.isNullOrEmpty() || chapterId.isNullOrEmpty()) {
                null
            } else {
                AppRoute.Reader(
                    ChapterKey(
                        comicKey = ComicKey(SourceId(sourceId), RemoteComicId(comicId)),
                        remoteId = RemoteChapterId(chapterId),
                    ),
                )
            }
        }

        else -> null
    }
}

private fun String?.escape(): String = this.orEmpty()
    .replace("%", "%25")
    .replace(":", "%3A")

/** Null for an escaped segment that is empty, so "no source selected" stays distinguishable. */
private fun String.unescapeOrNull(): String? = takeIf(String::isNotEmpty)
    ?.replace("%3A", ":")
    ?.replace("%25", "%")

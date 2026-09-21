package dev.veneranative.core.image.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import dev.veneranative.core.image.ComicImageRequest

/**
 * The [ImageLoader] comic images are loaded with.
 *
 * It is provided by the assembly layer (`:app`), which is also where the auth provider and the
 * shared OkHttp client live. Features never build an `ImageLoader`, and never import Coil.
 */
val LocalComicImageLoader: ProvidableCompositionLocal<ImageLoader?> = compositionLocalOf { null }

/**
 * Renders one comic image.
 *
 * This is the only place in the app that names Coil's composables: a request that needs headers,
 * cookies, a referer or a POST body cannot be expressed as a plain URL, so the model is our own
 * [ComicImageRequest] and everything Coil-specific stays inside `:core:image`.
 *
 * [placeholder] is what the caller shows instead of the image: while it loads, when it fails and
 * when there is no image at all. A comic source returning a broken URL is normal, so "no image" is a
 * state to render, not an error to surface.
 */
@Composable
fun ComicImage(
    request: ComicImageRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = {},
) {
    val imageLoader = LocalComicImageLoader.current
    if (request == null || imageLoader == null) {
        Box(modifier = modifier) { placeholder() }
        return
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        imageLoader = imageLoader,
        modifier = modifier,
        contentScale = contentScale,
        loading = { placeholder() },
        error = { placeholder() },
    )
}

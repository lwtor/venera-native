package dev.veneranative.core.image.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import dev.veneranative.core.image.ComicImageRequest
import kotlinx.coroutines.delay

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

    var retryAttempt by remember(request) { mutableIntStateOf(0) }
    var failed by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request, retryAttempt, failed) {
        if (failed && retryAttempt < MAX_AUTOMATIC_RETRIES) {
            delay(AUTOMATIC_RETRY_DELAY_MILLIS)
            retryAttempt += 1
            failed = false
        }
    }

    key(request, retryAttempt) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = contentDescription,
            imageLoader = imageLoader,
            modifier = modifier,
            contentScale = contentScale,
            loading = { placeholder() },
            onError = { failed = true },
            error = {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    placeholder()
                    if (retryAttempt >= MAX_AUTOMATIC_RETRIES) {
                        BasicText(
                            text = "点击重试",
                            style = TextStyle(color = Color.White),
                            modifier = Modifier
                                .padding(8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.68f))
                                .clickable {
                                    retryAttempt = 0
                                    failed = false
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            },
        )
    }
}

private const val MAX_AUTOMATIC_RETRIES = 1
private const val AUTOMATIC_RETRY_DELAY_MILLIS = 600L

package dev.veneranative.app

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import dev.veneranative.core.image.ComicImageFile
import dev.veneranative.core.image.ComicImagePipeline
import dev.veneranative.core.image.ComicImageRequest
import dev.veneranative.core.image.decode.CachingPageImageDecoder
import dev.veneranative.core.image.decode.PageImageCache
import dev.veneranative.core.image.decode.PipelinePageImageDecoder
import dev.veneranative.core.image.decode.RegionPageImageDecoder
import dev.veneranative.core.model.ImageSize
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageSizeState
import dev.veneranative.core.model.SourceId
import dev.veneranative.feature.reader.ReaderScreen
import dev.veneranative.feature.reader.ReaderStatus
import dev.veneranative.feature.reader.ReaderUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReaderVerticalRenderingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun verticalReaderDisplaysPixelsDecodedFromARealImageFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageFile = File.createTempFile("reader-vertical-app-", ".png", context.cacheDir)
        val sourceId = SourceId("reader-test-source")
        var imageLeaseClosed = false
        val imagePipeline = object : ComicImagePipeline {
            override suspend fun cachedFileOf(request: ComicImageRequest): ComicImageFile {
                assertEquals("reader-test-source", request.sourceId?.value)
                assertEquals("https://reader.test/page.png", request.url)
                return ComicImageFile(imageFile, "image/png") { imageLeaseClosed = true }
            }

            override suspend fun sizeOf(request: ComicImageRequest): ImageSize? = ImageSize(40, 80)
        }
        try {
            Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.Red.toArgb())
                imageFile.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            composeRule.activity.setContent {
                ReaderScreen(
                    state = ReaderUiState(
                        chapterTitle = "纵向真实解码回归",
                        pages = listOf(
                            ComicPage(
                                index = 0,
                                imageRef = "https://reader.test/page.png",
                                widthPx = 40,
                                heightPx = 80,
                                sourceId = sourceId,
                                sizeState = PageSizeState.Ready,
                            ),
                        ),
                        status = ReaderStatus.Ready,
                    ),
                    onAction = {},
                    onBack = {},
                    decoderFactory = {
                        CachingPageImageDecoder(
                            PipelinePageImageDecoder(imagePipeline, RegionPageImageDecoder()),
                            PageImageCache(1_000_000),
                        )
                    },
                )
            }

            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty()
            }
            val image = composeRule.onNodeWithContentDescription("第 1 页")
            image.assertIsDisplayed()
            val pixels = image.captureToImage().toPixelMap()
            assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
            composeRule.runOnIdle { assertEquals(true, imageLeaseClosed) }
        } finally {
            imageFile.delete()
        }
    }
}

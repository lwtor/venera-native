package dev.veneranative.feature.reader

import android.content.Context
import android.graphics.BitmapFactory
import dev.veneranative.core.model.ChapterContent
import dev.veneranative.core.model.ChapterRef
import dev.veneranative.core.model.ComicPage
import dev.veneranative.core.model.PageProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * Page provider backed by the generated S0-06 fixtures under `assets/fixtures`.
 *
 * It exists so the reader can be exercised with real files on a device without any comic source:
 * assets are copied once into the cache directory because region decoding needs a real file. The
 * fixtures are generated locally (`tools/test-images`) and are never committed.
 */
class AssetFixturePageProvider(
    private val context: Context,
    private val assetDir: String = DEFAULT_ASSET_DIR,
) : PageProvider {

    override suspend fun loadChapter(chapter: ChapterRef): ChapterContent = withContext(Dispatchers.IO) {
        val pages = fixtureNames().mapIndexed { index, name -> page(index, name) }
        ChapterContent(title = "Generated fixtures (${pages.size})", pages = pages)
    }

    private fun fixtureNames(): List<String> = (context.assets.list(assetDir) ?: emptyArray())
        .filter { name -> name.endsWith(".png", ignoreCase = true) || name.endsWith(".jpg", ignoreCase = true) }
        .sorted()

    private fun page(index: Int, name: String): ComicPage {
        val file = File(File(context.cacheDir, assetDir).apply { mkdirs() }, name)
        if (!file.exists()) {
            context.assets.open("$assetDir/$name").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return ComicPage(
            index = index,
            imageRef = file.absolutePath,
            widthPx = bounds.outWidth.coerceAtLeast(1),
            heightPx = bounds.outHeight.coerceAtLeast(1),
        )
    }

    companion object {
        const val DEFAULT_ASSET_DIR = "fixtures"

        /** True when the locally generated fixtures are present, i.e. when real decoding can run. */
        fun hasFixtures(context: Context, assetDir: String = DEFAULT_ASSET_DIR): Boolean =
            (context.assets.list(assetDir) ?: emptyArray()).any { name ->
                name.endsWith(".png", ignoreCase = true) || name.endsWith(".jpg", ignoreCase = true)
            }
    }
}

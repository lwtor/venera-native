package dev.veneranative.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small native vector for the shelf, matching the other 24dp navigation icons. */
internal val BookshelfIcon = ImageVector.Builder(
    name = "Bookshelf", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 4f); lineTo(7f, 4f); lineTo(7f, 20f); lineTo(3f, 20f); close()
        moveTo(9f, 4f); lineTo(13f, 4f); lineTo(13f, 20f); lineTo(9f, 20f); close()
        moveTo(14f, 5f); lineTo(18f, 4f); lineTo(22f, 19f); lineTo(18f, 20f); close()
    }
}.build()

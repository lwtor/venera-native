package dev.veneranative.core.model

/**
 * The region of a source image that one display page renders, in source pixel coordinates.
 *
 * `null` means the display page is the whole source image. When non-null it describes a crop: the
 * display page shows `[leftPx, leftPx + widthPx) x [topPx, topPx + heightPx)` of an image whose full
 * size is [sourceWidthPx] x [sourceHeightPx]. The full size is kept so decoders still know the
 * source geometry (for example the height needed to reverse horizontal bands).
 *
 * This lives in `:core:model` as plain `Int` fields because `:core:image`'s `PageRegion` depends on
 * `:core:model`, not the other way around.
 */
data class PageCrop(
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
)

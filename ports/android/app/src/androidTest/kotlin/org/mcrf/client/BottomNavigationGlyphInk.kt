package org.mcrf.client

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/** Test-only glyph detector, independent of an opaque/translucent/gradient base. */
internal object BottomNavigationGlyphInk {
    private fun distance(first: Int, second: Int) = maxOf(
        abs(Color.red(first) - Color.red(second)), abs(Color.green(first) - Color.green(second)),
        abs(Color.blue(first) - Color.blue(second)))

    fun requireGlyph(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int,
        density: Float, muted: Int, case: String) {
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val radius = (12 * density).roundToInt()
        val backgroundOffset = (18 * density).roundToInt()
        require(left >= 0 && right <= bitmap.width && top >= 0 && bottom <= bitmap.height)
        require(cx - radius >= left && cx + radius < right && cy - backgroundOffset >= top &&
            cy + backgroundOffset < bottom) { "Invalid glyph slot: $case" }
        var ink = 0
        for (y in cy - radius..cy + radius) for (x in cx - radius..cx + radius) {
            val pixel = bitmap.getPixel(x, y)
            // These samples are outside the 22dp glyph bounds, at the same X.
            // Require separation from BOTH, so the tinted base cannot count as
            // ink even if one side of its gradient approaches the glyph color.
            val baseAbove = bitmap.getPixel(x, cy - backgroundOffset)
            val baseBelow = bitmap.getPixel(x, cy + backgroundOffset)
            if (Color.alpha(pixel) == 255 && distance(pixel, muted) <= 18 &&
                distance(pixel, baseAbove) >= 30 && distance(pixel, baseBelow) >= 30) ink++
        }
        val minimum = (8 * density * density).roundToInt().coerceAtLeast(8)
        check(ink >= minimum) { "Missing muted glyph: $case, opaque ink=$ink minimum=$minimum" }
    }
}

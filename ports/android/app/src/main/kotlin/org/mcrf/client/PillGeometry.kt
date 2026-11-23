package org.mcrf.client

/** Positions the settings list in visible screen coordinates. */
internal object PillGeometry {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Placement(val left: Int, val top: Int, val height: Int)

    fun place(
        viewport: Bounds,
        anchor: Bounds,
        width: Int,
        preferredHeight: Int,
        minimumHeight: Int,
        margin: Int,
        opensDown: Boolean?,
    ): Placement? {
        val left = viewport.left + margin
        val top = viewport.top + margin
        val right = viewport.right - margin
        val bottom = viewport.bottom - margin
        if (right - left < width || bottom - top < minimumHeight) return null

        val above = anchor.top - top
        val below = bottom - anchor.bottom
        val down = when (opensDown) {
            true -> below >= preferredHeight || below >= above
            false -> above < preferredHeight && below > above
            null -> below >= above
        }
        val centeredLeft = ((anchor.left + anchor.right - width) / 2).coerceIn(left, right - width)
        val verticalHeight = minOf(if (down) below else above, preferredHeight)
        if (verticalHeight < preferredHeight) {
            val sideLeft = when {
                anchor.left - left >= width -> anchor.left - width
                right - anchor.right >= width -> anchor.right
                else -> null
            }
            if (sideLeft != null) {
                val height = minOf(preferredHeight, bottom - top)
                val sideTop = ((anchor.top + anchor.bottom - height) / 2).coerceIn(top, bottom - height)
                return Placement(sideLeft, sideTop, height)
            }
        }
        if (verticalHeight < minimumHeight) return null
        return Placement(centeredLeft, if (down) anchor.bottom else anchor.top - verticalHeight, verticalHeight)
    }
}

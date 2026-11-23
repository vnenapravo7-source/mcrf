package org.mcrf.client

import kotlin.math.roundToInt

/** Pure color policy shared by cards, connection panels and navigation. */
object SurfacePalette {
    data class Stops(val start: Int, val middle: Int, val end: Int)

    fun stops(surface: Int, accent: Int, glow: Int, dark: Boolean, connection: Boolean = false): Stops {
        val firstAlpha = if (connection) 179 else if (dark) 214 else 230
        val middleAlpha = if (connection) 179 else if (dark) 204 else 219
        val lastAlpha = if (connection) 179 else if (dark) 224 else 235
        // Dark surfaces need chroma without the luminance of a pastel accent.
        val tint=if(dark)mix(0xff000000.toInt(),chromatic(accent),.50f)else accent
        val highlight=if(dark)mix(0xff000000.toInt(),chromatic(glow),.50f)else glow
        return Stops(
            withAlpha(mix(surface, tint, if (dark) .44f else .22f), firstAlpha),
            withAlpha(mix(surface, tint, if (dark) .30f else .16f), middleAlpha),
            withAlpha(mix(surface, highlight, if (dark) .36f else .20f), lastAlpha)
        )
    }

    /** Give pastel accents a recognizable hue instead of adding more grey to dark cards. */
    private fun chromatic(color:Int):Int {
        val channels=intArrayOf((color ushr 16)and 255,(color ushr 8)and 255,color and 255)
        val high=channels.max();val low=channels.min();val chroma=high-low
        if(chroma==0||chroma>=high*.55f)return color
        val scale=high*.55f/chroma
        fun value(index:Int)=(high-(high-channels[index])*scale).roundToInt().coerceIn(0,255)
        return (color and 0xff000000.toInt())or(value(0)shl 16)or(value(1)shl 8)or value(2)
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)

    /** Same channel rounding as Loft.mix, without Android dependencies. */
    private fun mix(first: Int, second: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val a = (first ushr shift) and 255
            val b = (second ushr shift) and 255
            return (a + (b - a) * amount).roundToInt() shl shift
        }
        return channel(24) or channel(16) or channel(8) or channel(0)
    }

    /** One entry per owner; unchanged frames allocate neither keys nor stop arrays. */
    class Cache<T : Any>(private val create: (Float, Float, Float, Float, Stops) -> T) {
        private var cached: T? = null
        private var left = 0f
        private var top = 0f
        private var right = 0f
        private var bottom = 0f
        private var surface = 0
        private var accent = 0
        private var glow = 0
        private var dark = false
        private var connection = false

        fun value(left: Float, top: Float, right: Float, bottom: Float,
                  surface: Int, accent: Int, glow: Int, dark: Boolean,
                  connection: Boolean = false): T? {
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
                right <= left || bottom <= top) return null
            if (cached != null && this.left == left && this.top == top &&
                this.right == right && this.bottom == bottom && this.surface == surface &&
                this.accent == accent && this.glow == glow && this.dark == dark &&
                this.connection == connection) return cached
            val next = create(left, top, right, bottom, stops(surface, accent, glow, dark, connection))
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
            this.surface = surface
            this.accent = accent
            this.glow = glow
            this.dark = dark
            this.connection = connection
            cached = next
            return next
        }
    }
}

package org.mcrf.client

import kotlin.math.sin
import kotlin.math.roundToInt

/** Presentation preference only; page IDs and routing stay with the existing host. */
object BottomNavigationStyle {
    const val KEY = "bottomNavigationStyle"
    const val POSITION="sectionNavigationPosition"
    const val TOP="top"
    const val BOTTOM="bottom"
    val positions=linkedMapOf(BOTTOM to "Снизу",TOP to "Сверху")
    fun position(raw:String?)=raw?.takeIf {it in positions}?:TOP
    const val SLIDING = "sliding-pill"
    // Legacy saved key only. resolve() falls back without rewriting preferences.
    const val LIQUID = "liquid-bulge"
    const val FLOATING = "floating-notch"
    const val EXPANDING = "expanding-label"
    val choices = linkedMapOf(
        SLIDING to "Sliding Pill",
        FLOATING to "Floating Notch", EXPANDING to "Expanding Label")
    fun resolve(raw: String?) = raw?.takeIf { it in choices } ?: EXPANDING
    private val topChoices = choices.filterKeys { it != FLOATING }
    fun choicesFor(placement: String?) = if (position(placement) == TOP) topChoices else choices
    fun resolve(raw: String?, placement: String?) = raw?.takeIf { it in choicesFor(placement) } ?: EXPANDING
}

/** Geometry in dp. MainActivity alone consumes system/keyboard insets. */
object BottomNavigationGeometry {
    data class Slot(val left: Float, val width: Float) { val center get() = left + width / 2 }
    const val MIN_TOUCH = 48
    data class CompactHighlight(val half: Float, val height: Float, val lean: Float)
    fun compactHighlight(style: String, stretch: Float, direction: Float): CompactHighlight {
        val wave = stretch.coerceIn(0f, 1f)
        return if (style == BottomNavigationStyle.SLIDING) CompactHighlight(20 + 8 * wave, 40 - 4 * wave, 0f)
        else CompactHighlight(24 + 4 * wave, 36 - 4 * wave, 4 * wave * direction.coerceIn(-1f, 1f))
    }
    /** Paint-only constraint; frozen gesture coordinates and hit edges stay intact. */
    fun paintCenter(center: Float, half: Float, width: Float): Float {
        val radius = half.coerceIn(0f, width.coerceAtLeast(0f) / 2)
        return center.coerceIn(radius, width.coerceAtLeast(0f) - radius)
    }
    fun top(style: String, hasSelection: Boolean = true) = if (!hasSelection) 0 else when (style) {
        BottomNavigationStyle.FLOATING -> 0 // The raised circle overlays page content.
        BottomNavigationStyle.LIQUID -> 0 // Local rounded fill, no protrusion or reserved strip.
        else -> 0
    }
    fun widths(width: Float, count: Int, style: String, selected: Int, labelWidth: Float): List<Float> {
        require(count > 0)
        val available = (width - 2 * FloatingNotchMotion.SIDE_MARGIN).coerceAtLeast(0f)
        if (style != BottomNavigationStyle.EXPANDING || selected !in 0 until count)
            return List(count) { available / count }
        val expanded = maxOf((labelWidth + 64).coerceIn(96f, 180f), available / count * 1.4f)
            .coerceAtMost((available - MIN_TOUCH * (count - 1)).coerceAtLeast(available / count))
        val other = if (count == 1) 0f else (available - expanded) / (count - 1)
        return List(count) { if (count == 1) available else if (it == selected) expanded else other }
    }
    fun slots(widths: List<Float>, rtl: Boolean = false): List<Slot> {
        var offset = FloatingNotchMotion.SIDE_MARGIN.toFloat()
        val physical = (if (rtl) widths.reversed() else widths).map { size ->
            Slot(offset, size).also { offset += size }
        }
        return if (rtl) physical.reversed() else physical
    }
    fun interpolate(from: List<Float>, to: List<Float>, progress: Float) =
        to.indices.map { FloatingNotchMotion.x(from[it], to[it], progress) }
    fun weightsAt(slots: List<Slot>, x: Float): List<Float> {
        if (slots.isEmpty()) return emptyList()
        val order = slots.indices.sortedBy { slots[it].center }
        val position = x.coerceIn(slots[order.first()].center, slots[order.last()].center)
        val right = order.first { slots[it].center >= position }
        val left = order.last { slots[it].center <= position }
        val fraction = if (left == right) 0f else (position - slots[left].center) / (slots[right].center - slots[left].center)
        return slots.indices.map { if (it == left) 1 - fraction else if (it == right) fraction else 0f }
    }
}

/** Keep both gradient endpoints readable even with arbitrary user-selected colors. */
object BottomNavigationColors {
    data class Gradient(val start: Int, val end: Int, val foreground: Int)
    fun soft(surface: Int, accent: Int, secondary: Int) =
        liquid(blend(surface, accent, .20f), blend(surface, secondary, .12f))
    private fun blend(first: Int, second: Int, amount: Float): Int {
        fun channel(shift: Int) = (((first ushr shift) and 255) * (1 - amount) +
            ((second ushr shift) and 255) * amount).roundToInt()
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    fun liquid(accent: Int, secondary: Int): Gradient {
        for (step in 10 downTo 0) {
            val end = blend(accent, secondary, step * .1f)
            val white = 0xffffffff.toInt(); val black = 0xff000000.toInt()
            fun contrast(color: Int) = minOf(UserThemeColors.contrast(color, accent), UserThemeColors.contrast(color, end))
            val foreground = if (contrast(white) >= contrast(black)) white else black
            if (contrast(foreground) >= 4.5) return Gradient(accent, end, foreground)
        }
        error("Opaque sRGB always has a readable black or white foreground")
    }
}

object FloatingNotchMotion {
    const val DURATION = 480L
    const val BAR_HEIGHT = 56
    const val SIDE_MARGIN = 0
    const val RADIUS = 28
    const val CIRCLE = 32
    const val TOP = 12 // Circle protrusion, never reserved layout height.
    fun center(width: Float, index: Int, count: Int, margin: Float): Float {
        require(count > 0 && index in 0 until count)
        return margin + (width - margin * 2) * (index + .5f) / count
    }
    fun x(from: Float, to: Float, progress: Float) = when {
        progress <= 0f -> from
        progress >= 1f -> to
        else -> from + (to - from) * progress
    }
    fun lift(progress: Float): Float = (14 * sin(Math.PI * progress.coerceIn(0f, 1f))).toFloat()
    // The hop paints above the bar during motion, without reserving empty space.
    // Interrupted taps start at the actual in-flight height.
    fun y(from: Float, rest: Float, progress: Float): Float =
        (x(from, rest, progress) - lift(progress)).coerceAtLeast(rest - 14f)
}

/** Timings sampled from the supplied 30fps clip: travel ~9–11 frames, settle ~3–4.
 * Raw time is deliberately linear; each visual property owns its phase/easing. */
object BottomNavigationMotion {
    data class LabelContent(val index: Int, val opacity: Float)
    /** At most one title. Fade to zero at the midpoint before changing identity. */
    fun singleLabel(weights: List<Float>): LabelContent {
        val index = weights.indices.maxByOrNull { weights[it] } ?: return LabelContent(-1, 0f)
        val winner = weights[index]
        val runner = weights.indices.filter { it != index }.maxOfOrNull { weights[it] } ?: 0f
        return LabelContent(index, (winner - runner).coerceIn(0f, 1f))
    }
    // Some devices contain the literal "null" after a settings restore and
    // report areAnimatorsEnabled=false. Only an explicit zero/removal is opt-out.
    fun animationsEnabled(rawScale: String?, removeAnimations: Boolean): Boolean {
        if (removeAnimations) return false
        val scale = rawScale?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: 1f
        return scale > 0f
    }
    fun duration(style: String) = when (style) {
        BottomNavigationStyle.SLIDING -> 420L
        BottomNavigationStyle.LIQUID -> 460L
        BottomNavigationStyle.EXPANDING -> 440L
        else -> FloatingNotchMotion.DURATION
    }
    fun phase(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
    fun ease(t: Float): Float { val p = t.coerceIn(0f, 1f); return p * p * (3 - 2 * p) }
    fun travel(style: String, t: Float) = ease(phase(t, .02f, if (style == BottomNavigationStyle.FLOATING) .78f else .9f))
    fun stretch(t: Float): Float {
        val p = phase(t, 0f, .9f)
        return if (p <= 0f || p >= 1f) 0f else sin(Math.PI * p).toFloat()
    }
    fun collapse(t: Float) = ease(phase(t, 0f, .4f))
    fun expand(t: Float) = ease(phase(t, .4f, 1f))
    fun oldLabel(t: Float) = 1 - ease(phase(t, 0f, .26f))
    fun newLabel(t: Float) = ease(phase(t, .64f, 1f))
    fun iconSwap(t: Float) = ease(phase(t, .3f, .6f))
    data class Bulge(val left: Float, val right: Float, val height: Float, val lean: Float)
    fun bulge(t: Float, direction: Float) = bulgeForStretch(stretch(t), direction)
    fun bulgeForStretch(wave: Float, direction: Float): Bulge {
        // Trailing shoulder stretches and flattens, leading shoulder rises;
        // their roles reverse with travel direction, then both settle symmetrically.
        return Bulge(26 + wave * (6 + 9 * direction),
            26 + wave * (6 - 9 * direction), 10 + 5 * wave, direction * 6 * wave)
    }
}

/** Time advances only while actually painting. A blocked render, detached host or
 * repeated submit cannot consume the transition before its first visible frame. */
class BottomNavigationPlayback {
    var progress = 1f; private set
    private var duration = 1L
    private var previousFrame: Long? = null
    fun start(durationMs: Long) { duration = durationMs; progress = 0f; previousFrame = null }
    fun pause() { previousFrame = null }
    fun finish() { progress = 1f; previousFrame = null }
    fun frame(nowMs: Long): Float {
        if (progress < 1f) {
            previousFrame?.let { progress = (progress + (nowMs - it).coerceIn(0, 32).toFloat() / duration).coerceAtMost(1f) }
            previousFrame = nowMs
        }
        return progress
    }
}

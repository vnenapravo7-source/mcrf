package org.mcrf.client

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.View
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Renderer regression only; the real Appearance/window acceptance is separate. */
internal object BottomNavigationUnselectedChecks {
    fun run(instrumentation: Instrumentation): Int {
        var cases = 0
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            val originalDark = Loft.dark
            try {
                for (dark in listOf(false, true)) for (motion in listOf(false, true))
                    for (count in listOf(3, 4)) for (initial in BottomNavigationStyle.choices.keys) {
                        Loft.apply(dark)
                        val context = instrumentation.targetContext
                        val nav = BottomNavigationView(context, { name, color ->
                            Loft.Icon(name, color, context.resources.displayMetrics.density)
                        }) { motion }
                        val withoutGlyphs = BottomNavigationView(context, { _, _ -> ColorDrawable(Color.TRANSPARENT) }) { motion }
                        val expectedBrush = Loft.SurfaceBrush()
                        val width = (400 * context.resources.displayMetrics.density).toInt()
                        val tabs = List(count) { NavigationTab("tab-$it", "Tab $it", "home", "Tab $it", false) }
                        fun submit(style: String) = nav.submit(tabs, style) { error("Unexpected navigation") }
                        fun layout() {
                            nav.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            nav.layout(0, 0, nav.measuredWidth, nav.measuredHeight)
                        }
                        fun paint() {
                            check(nav.childCount == count && (0 until count).none { nav.getChildAt(it).isSelected })
                            val center = BottomNavigationView::class.java.getDeclaredField("centerX").apply { isAccessible = true }
                            check(center.getFloat(nav).isFinite()) { "Off-tab geometry is not finite" }
                            val weights = BottomNavigationView::class.java.getDeclaredField("selectionWeights").apply { isAccessible = true }
                            val selection = weights.get(nav) as List<*>
                            check(selection.size == count && selection.all { it == 0f }) { "Off-tab selection weights are active" }
                            val target = BottomNavigationView::class.java.getDeclaredField("targetId").apply { isAccessible = true }
                            check(target.get(nav) == null) { "Off-tab target is selected" }
                            val bitmap = Bitmap.createBitmap(width, nav.height, Bitmap.Config.ARGB_8888)
                            val blank = Bitmap.createBitmap(width, nav.height, Bitmap.Config.ARGB_8888)
                            val base = Bitmap.createBitmap(width, nav.height, Bitmap.Config.ARGB_8888)
                            try {
                                // Identical gradient/translucency, but no glyphs.
                                // A tinted blank must fail the SAME ink assertion.
                                val style = BottomNavigationView::class.java.getDeclaredField("style").apply { isAccessible = true }.get(nav) as String
                                withoutGlyphs.submit(tabs, style) {}
                                withoutGlyphs.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(nav.height, View.MeasureSpec.EXACTLY))
                                withoutGlyphs.layout(0, 0, width, nav.height)
                                nav.draw(Canvas(bitmap))
                                withoutGlyphs.draw(Canvas(blank))
                                // Independent surface-only reference: no renderer
                                // indicator/label is allowed even in the negative control.
                                val bounds = RectF(0f, 0f, width.toFloat(), FloatingNotchMotion.BAR_HEIGHT * context.resources.displayMetrics.density)
                                Canvas(base).drawRect(bounds, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = expectedBrush.shader(bounds) })
                                for (i in 0 until count) {
                                    val cell = nav.getChildAt(i)
                                    fun requireInk(image: Bitmap) = BottomNavigationGlyphInk.requireGlyph(image,
                                        cell.left, 0, cell.right, nav.height, context.resources.displayMetrics.density,
                                        Loft.MUTED, "$style/$motion/$dark/slot-$i")
                                    requireInk(bitmap)
                                    var rejected = false
                                    try { requireInk(blank) } catch (_: IllegalStateException) { rejected = true }
                                    check(rejected) { "Glyph-free gradient falsely accepted as ink in slot $i" }
                                }
                                // Off-tab paint may differ from its glyph-free
                                // base ONLY inside the normal 22dp glyph bounds.
                                val halfGlyph = (11 * context.resources.displayMetrics.density).toInt() + 1
                                val centers = (0 until count).map { nav.getChildAt(it).let { cell -> (cell.left + cell.right) / 2 } }
                                val sampleStep = context.resources.displayMetrics.density.roundToInt().coerceAtLeast(1)
                                for (y in 0 until nav.height step sampleStep) for (x in 0 until width step sampleStep) {
                                    check(blank.getPixel(x, y) == base.getPixel(x, y)) {
                                        "Unexpected off-tab indicator/label in glyph-free bar: $style/$motion/$dark"
                                    }
                                    val insideGlyph = centers.any { centerX ->
                                        kotlin.math.abs(x - centerX) <= halfGlyph &&
                                            kotlin.math.abs(y - nav.height / 2) <= halfGlyph
                                    }
                                    if (!insideGlyph) check(bitmap.getPixel(x, y) == blank.getPixel(x, y)) {
                                        "Unexpected off-tab content outside glyphs: $style/$motion/$dark"
                                    }
                                }
                            } finally { bitmap.recycle(); blank.recycle(); base.recycle() }
                            cases++
                        }
                        submit(initial); layout(); paint()
                        // Every ordered transition, including same-size and same-style
                        // submits. Paint once BEFORE the replacement layout as well.
                        for (next in BottomNavigationStyle.choices.keys) {
                            submit(initial); layout()
                            submit(next); paint(); layout(); paint()
                            Loft.apply(!dark); submit(next); paint()
                            Loft.apply(dark)
                        }
                        // Interrupt expanding selection travel, then leave all tabs
                        // unchosen while the expanding motion snapshot is still live.
                        nav.submit(tabs.mapIndexed { i, tab -> tab.copy(chosen = i == 0) }, BottomNavigationStyle.EXPANDING) {}
                        nav.submit(tabs.mapIndexed { i, tab -> tab.copy(chosen = i == 1) }, BottomNavigationStyle.EXPANDING) {}
                        submit(BottomNavigationStyle.EXPANDING); paint()
                        val moving = BottomNavigationView::class.java.getDeclaredField("settlingDrag").apply { isAccessible = true }
                        moving.setBoolean(nav, true)
                        paint() // Explicit off-tab paint must also tolerate stale movingContent.
                    }
            } catch (error: Throwable) { failure.set(error) }
            finally { Loft.apply(originalDark) }
        }
        failure.get()?.let { throw it }
        return cases
    }
}

package org.mcrf.client

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Native chart using the geometry of Lorenzo Cabra's MIT-licensed Bencho Stacked Bars. */
internal object ServerUsageChart {
    private const val TX = 0xff5b8def.toInt()
    private const val PLOT_HEIGHT = 127
    private const val BASELINE = 104
    private const val TIP_WIDTH = 132
    private const val NO_POINTER = -1
    private const val GROWTH_DURATION = 600f
    private const val COLUMN_DELAY = 14f

    fun receivedColor(serverId: String): Int {
        val slot = Math.floorMod(serverId.hashCode(), 290)
        // Leave blue hues to the fixed upload series.
        val hue = (if (slot < 188) slot else slot + 70).toFloat()
        val candidate = Color.HSVToColor(floatArrayOf(hue, if (Loft.dark) .42f else .72f, if (Loft.dark) .94f else .57f))
        return if (UserThemeColors.contrast(candidate, Loft.SURFACE) >= 3.0) candidate
            else Loft.mix(candidate, Loft.INK, .4f)
    }

    fun card(activity: MainActivity, receivedColor: Int, data: (Boolean) -> List<ServerUsage.Period>): View = with(activity) {
        val day = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        val total = text("", 26, Loft.INK)
        val subtitle = text("", 12, Loft.MUTED)
        val first = text("", 11, Loft.MUTED)
        val last = text("", 11, Loft.MUTED).apply { gravity = Gravity.END }
        val tip = text("", 12, Loft.INK).apply {
            background = shape(activity, Loft.SURFACE, 10, Loft.LINE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
        }
        val plot = Plot(activity, receivedColor) { period, index, plotWidth ->
            if (period == null) tip.visibility = View.INVISIBLE
            else {
                val left = index * plotWidth / ServerUsage.PERIOD_COUNT
                val anchor = left + plotWidth / ServerUsage.PERIOD_COUNT + dp(8)
                val tipWidth = dp(TIP_WIDTH)
                val x = if (anchor + tipWidth <= plotWidth) anchor else left - dp(8) - tipWidth
                tip.translationX = x.coerceIn(0, (plotWidth - tipWidth).coerceAtLeast(0)).toFloat()
                val range = if (period.start == period.end) day.format(period.start)
                    else "${day.format(period.start)} – ${day.format(period.end)}"
                val values = period.totals
                tip.text = "$range\n↓ ${TrafficState.bytes(values.received)}\n↑ ${TrafficState.bytes(values.sent)}\nСеансы · ${values.sessions}"
                tip.visibility = View.VISIBLE
            }
        }
        val frame = FrameLayout(this).apply {
            clipChildren = false
            addView(plot, FrameLayout.LayoutParams(-1, dp(PLOT_HEIGHT)))
            addView(tip, FrameLayout.LayoutParams(dp(TIP_WIDTH), -2))
        }
        fun choose(weekly: Boolean) {
            val periods = data(weekly)
            val combined = periods.fold(ServerUsage.Totals()) { running, period -> running + period.totals }
            subtitle.text = if (weekly) "Трафик за 20 недель" else "Трафик за 20 дней"
            total.text = TrafficState.bytes(combined.bytes)
            first.text = day.format(periods.first().start)
            last.text = day.format(periods.last().end)
            plot.show(periods)
        }
        LinearLayout(this).apply {
            tag = "server-usage-stacked-bars"
            orientation = LinearLayout.VERTICAL
            background = shape(activity, Loft.SURFACE, 14)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            addView(ModeSwitch(activity, true, { daily -> choose(!daily) },
                listOf("По дням", "По неделям"), listOf("", ""), compact = true), space(0, 10))
            addView(subtitle, space(0, 2))
            addView(total, space(0, 8))
            addView(frame, space(0, 8))
            addView(LinearLayout(activity).apply {
                addView(first, LinearLayout.LayoutParams(0, -2, 1f))
                addView(last, LinearLayout.LayoutParams(0, -2, 1f))
            })
            choose(false)
        }
    }

    private fun shape(activity: MainActivity, color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = activity.dp(radius).toFloat()
        setColor(color)
        if (stroke != 0) setStroke(activity.dp(1), stroke)
    }

    private class Plot(
        private val activity: MainActivity,
        private val receivedColor: Int,
        private val onSelection: (ServerUsage.Period?, Int, Int) -> Unit
    ) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val area = RectF()
        private val density = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var periods: List<ServerUsage.Period> = emptyList()
        private var maxBytes = 1.0
        private var maxSessions = 1.0
        private var selected = -1
        private var pointer = NO_POINTER
        private var touchX = 0f
        private var touchY = 0f
        private var scrubbing = false
        private val growthCurve = android.view.animation.PathInterpolator(.22f, 1f, .36f, 1f)
        private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        private val growthEnd = GROWTH_DURATION + COLUMN_DELAY * (ServerUsage.PERIOD_COUNT - 1)
        private var growth = growthEnd
        private var growthAnimator: ValueAnimator? = null

        init {
            isFocusable = true
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        fun show(values: List<ServerUsage.Period>) {
            periods = values
            maxBytes = values.maxOf { it.totals.bytes }.toDouble().coerceAtLeast(1.0)
            maxSessions = values.maxOf { it.totals.sessions }.toDouble().coerceAtLeast(1.0)
            select(-1)
            growthAnimator?.cancel()
            growth = growthEnd
            if (isAttachedToWindow) animateGrowth() else invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (periods.isNotEmpty()) animateGrowth()
        }

        private fun animateGrowth() {
            if (!ValueAnimator.areAnimatorsEnabled()) { invalidate(); return }
            growthAnimator = ValueAnimator.ofFloat(0f, growthEnd).apply {
                duration = growthEnd.toLong()
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    growth = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            growthAnimator?.cancel()
            growthAnimator = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || periods.isEmpty()) return
            val baseline = BASELINE * density
            paint.color = Loft.LINE
            paint.strokeWidth = density
            canvas.drawLine(0f, baseline, width.toFloat(), baseline, paint)
            val slot = width.toFloat() / periods.size
            val gap = (slot - 3 * density).coerceIn(0f, 6 * density)
            val bar = slot - gap
            val corner = minOf(2.5f * density, bar / 2f)
            if (bar <= 0f) return
            periods.forEachIndexed { index, period ->
                val expanded = growthCurve.getInterpolation(((growth - index * COLUMN_DELAY) / GROWTH_DURATION).coerceIn(0f, 1f))
                val x = index * slot + gap / 2f
                val y = baseline - 2f * density
                val values = period.totals
                val segmentGap = if (values.received > 0 && values.sent > 0) 2f * density else 0f
                val upSpace = baseline - 6f * density - segmentGap
                fun height(value: Long, max: Double, space: Float) =
                    if (value <= 0L) 0f else ((value / max * space).toFloat().coerceAtLeast(2 * density)) * expanded
                val rx = height(values.received, maxBytes, upSpace)
                val tx = height(values.sent, maxBytes, upSpace)
                val sessions = height(values.sessions, maxSessions, 18f * density)
                val gapY = segmentGap * expanded
                segment(canvas, x, y - rx, bar, rx, corner, receivedColor, index)
                segment(canvas, x, y - rx - gapY - tx, bar, tx, corner, TX, index)
                segment(canvas, x, baseline + 3 * density, bar, sessions, corner, Loft.MUTED, index)
                if (selected == index) {
                    val edge = 3 * density
                    val top = (y - rx - gapY - tx - edge).coerceAtLeast(density)
                    area.set(x - edge, top, x + bar + edge, baseline + sessions + 6 * density)
                    paint.color = Loft.MUTED
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.5f * density
                    canvas.drawRoundRect(area, corner + edge, corner + edge, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }

        private fun segment(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int, index: Int) {
            if (height <= 0f) return
            paint.color = if (selected == -1 || selected == index) color else Loft.mix(Loft.SURFACE, color, .38f)
            area.set(x, y, x + width, y + height)
            canvas.drawRoundRect(area, radius, radius, paint)
        }

        private fun pick(x: Float) {
            if (width <= 0 || periods.isEmpty()) return
            val index = (x / width * periods.size).toInt().coerceIn(0, periods.lastIndex)
            if (selected != index) select(index)
        }

        private fun select(index: Int) {
            selected = index
            contentDescription = periods.getOrNull(index)?.let {
                "${shortDate.format(it.start)} — ${shortDate.format(it.end)}. Получено ${TrafficState.bytes(it.totals.received)}, " +
                    "отправлено ${TrafficState.bytes(it.totals.sent)}, сеансы ${it.totals.sessions}."
            } ?: "Диаграмма трафика. Используйте действия прокрутки для выбора периода."
            onSelection(periods.getOrNull(index), index, width)
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointer = event.getPointerId(0)
                    touchX = event.x
                    touchY = event.y
                    scrubbing = false
                    pick(event.x)
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointer)
                    if (index == -1) return false
                    val x = event.getX(index)
                    val dx = abs(x - touchX)
                    if (!scrubbing && dx > touchSlop && dx > abs(event.getY(index) - touchY)) {
                        scrubbing = true
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (scrubbing) pick(x)
                }
                MotionEvent.ACTION_UP -> {
                    if (scrubbing) select(-1) else performClick()
                    endTouch()
                }
                MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) {
                    select(-1)
                    endTouch()
                }
                MotionEvent.ACTION_CANCEL -> { select(-1); endTouch() }
            }
            return true
        }

        private fun endTouch() {
            pointer = NO_POINTER
            scrubbing = false
            parent.requestDisallowInterceptTouchEvent(false)
        }

        override fun performClick(): Boolean {
            super.performClick()
            if (selected < 0 && periods.isNotEmpty()) select(periods.lastIndex)
            return true
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> advance(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> advance(1)
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }

        private fun advance(direction: Int) {
            if (periods.isNotEmpty()) select(if (selected < 0) periods.lastIndex else (selected + direction).coerceIn(0, periods.lastIndex))
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.isScrollable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }

        override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> advance(1)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> advance(-1)
                else -> return super.performAccessibilityAction(action, arguments)
            }
            announceForAccessibility(contentDescription)
            return true
        }
    }
}

package org.mcrf.client

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/** Native adaptation of Bencho's MIT-licensed Particles block. */
internal class ParticlePlayfield(private val activity: MainActivity) : AppearanceMotionView(activity) {
    private data class Point(val x: Float, val y: Float)
    private class Dot(home: Point) {
        var home = home
        var x = home.x
        var y = home.y
        var vx = 0f
        var vy = 0f
    }

    private var dots = emptyList<Dot>()
    private val paint = Paint()
    private val tiles = Path()
    private var pointer: Point? = null
    private var previousFrame = 0L
    private val fieldScale get() = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fieldWidth = w / fieldScale
        val fieldHeight = h / fieldScale
        val columns = (fieldWidth / CELL).toInt().coerceAtLeast(1)
        val rows = (fieldHeight / CELL).toInt().coerceAtLeast(1)
        val columnWidth = fieldWidth / columns
        val rowHeight = fieldHeight / rows
        dots = List(rows * columns) { index ->
            Dot(Point((index % columns + .5f) * columnWidth, (index / columns + .5f) * rowHeight))
        }
        pointer = null
        previousFrame = 0L
    }

    init {
        tag = "appearance-particle-playfield"
        contentDescription = "Поле частиц. Коснитесь или проведите пальцем"
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = true
        if (!Loft.dark) setBackgroundColor(0xff20192f.toInt())
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    override fun performClick(): Boolean {
        super.performClick()
        pushParticles(pointer ?: Point(width / fieldScale * .5f, height / fieldScale * .5f))
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pointer = fieldPoint(event)
                performClick()
            }
            MotionEvent.ACTION_MOVE -> {
                pointer = fieldPoint(event)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                pointer = null
                invalidate()
            }
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> pointer = fieldPoint(event)
            MotionEvent.ACTION_HOVER_EXIT -> pointer = null
        }
        invalidate()
        return super.onHoverEvent(event)
    }

    override fun onDetachedFromWindow() {
        pointer = null
        previousFrame = 0L
        super.onDetachedFromWindow()
    }

    private fun fieldPoint(event: MotionEvent) =
        Point(event.x / fieldScale, event.y / fieldScale)

    private fun pushParticles(origin: Point) {
        if (!motionVisible()) return
        for (dot in dots) {
            val dx = dot.x - origin.x
            val dy = dot.y - origin.y
            val distance = hypot(dx, dy)
            if (distance > .01f && distance < TAP_RADIUS) {
                val impulse = (1f - distance / TAP_RADIUS) * 24f / distance
                dot.vx += dx * impulse
                dot.vy += dy * impulse
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val moving = motionVisible()
        val active = if (moving) simulate() else {
            snapHome()
            false
        }
        canvas.save()
        canvas.scale(fieldScale, fieldScale)
        paint.color = Color.WHITE
        tiles.rewind()
        for (dot in dots) {
            tiles.addRect(dot.x - RADIUS, dot.y - RADIUS, dot.x + RADIUS, dot.y + RADIUS, Path.Direction.CW)
        }
        canvas.drawPath(tiles, paint)
        canvas.restore()
        if (active) postInvalidateOnAnimation() else previousFrame = 0L
    }

    private fun snapHome() {
        for (dot in dots) {
            dot.x = dot.home.x; dot.y = dot.home.y
            dot.vx = 0f; dot.vy = 0f
        }
    }

    private fun simulate(): Boolean {
        val time = SystemClock.uptimeMillis()
        val step = if (previousFrame == 0L) 1f else ((time - previousFrame) / 16.67f).coerceIn(.25f, 2.5f)
        previousFrame = time
        val damping = .84f.pow(step)
        val cursor = pointer
        var active = false
        for (dot in dots) {
            if (cursor != null) {
                val dx = dot.x - cursor.x
                val dy = dot.y - cursor.y
                val distance = hypot(dx, dy)
                if (distance > .01f && distance < DRAG_RADIUS) {
                    val falloff = 1f - distance / DRAG_RADIUS
                    val push = falloff * falloff * 6.6f * step / distance
                    dot.vx += dx * push
                    dot.vy += dy * push
                }
            }
            val home = dot.home
            dot.vx = (dot.vx + (home.x - dot.x) * .05f * step) * damping
            dot.vy = (dot.vy + (home.y - dot.y) * .05f * step) * damping
            dot.x += dot.vx * step
            dot.y += dot.vy * step
            if (abs(home.x - dot.x) < .05f && abs(home.y - dot.y) < .05f &&
                abs(dot.vx) < .02f && abs(dot.vy) < .02f) {
                dot.x = home.x; dot.y = home.y
                dot.vx = 0f; dot.vy = 0f
            } else active = true
        }
        return active
    }

    companion object {
        private const val CELL = 10.5f
        private const val RADIUS = 1.5f
        private const val TAP_RADIUS = 60f
        private const val DRAG_RADIUS = 38f

        fun show(a: MainActivity) {
            val field = ParticlePlayfield(a)
            lateinit var dialog: android.app.AlertDialog
            val header = LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPaddingRelative(a.dp(16), a.dp(6), a.dp(8), a.dp(4))
                addView(a.text("Антистресс", 20, Loft.INK).apply { setAccessibilityHeading(true) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(a.iconButton("close", "Закрыть антистресс", 20).apply {
                    tag = "particle-close"
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(a.dp(48), a.dp(48)))
            }
            val inset = a.dp(12)
            val body = android.widget.FrameLayout(a).apply {
                setPadding(inset, inset, inset, inset)
                addView(field, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
            dialog = ThemedDialogBuilder(a,maxHeightFraction=.68f,widthFraction=.8f)
                .setCustomTitle(header).setView(body).create()
            dialog.show()
        }
    }
}

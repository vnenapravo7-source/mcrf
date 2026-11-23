package org.mcrf.client

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Native adaptation of Bencho's MIT-licensed Eye tracker block, at launcher scale. */
internal class EyeParticleButton(private val activity: MainActivity) : AppearanceMotionView(activity) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Path()
    private val location = IntArray(2)
    private var gazeX = 0f
    private var gazeY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastFrame = 0L
    private var blinkStarted = 0L
    private val blink = Runnable {
        if (motionVisible() && hasWindowFocus()) {
            blinkStarted = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    init {
        tag = "appearance-particle-eye"
        contentDescription = "Открыть антистресс с частицами"
        isFocusable = true
        minimumWidth = activity.dp(48)
        minimumHeight = activity.dp(48)
        setOnClickListener { ParticlePlayfield.show(activity) }
        body.moveTo(16f, 4f)
        for ((x, y) in listOf(84f to 4f, 84f to 10f, 90f to 10f, 90f to 16f, 96f to 16f,
            96f to 84f, 90f to 84f, 90f to 90f, 84f to 90f, 84f to 96f, 16f to 96f,
            16f to 90f, 10f to 90f, 10f to 84f, 4f to 84f, 4f to 16f, 10f to 16f,
            10f to 10f, 16f to 10f)) body.lineTo(x, y)
        body.close()
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    fun followPointer(screenX: Float, screenY: Float, reset: Boolean) {
        if (!motionVisible()) return
        getLocationOnScreen(location)
        val dx = screenX - location[0] - width / 2f
        val dy = screenY - location[1] - height / 2f
        val distance = hypot(dx, dy).coerceAtLeast(1f)
        val reach = if (reset) 0f else (.9f * tanh(distance / activity.dp(60))).coerceAtMost(.85f)
        targetX = dx / distance * reach
        targetY = dy / distance * reach
        invalidate()
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        followPointer(event.rawX, event.rawY, event.actionMasked == MotionEvent.ACTION_HOVER_EXIT)
        return super.onHoverEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (motionStyleAnimationsEnabled(context)) postDelayed(blink, 2200)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        blinkStarted = 0L
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        removeCallbacks(blink)
        if (hasWindowFocus && motionStyleAnimationsEnabled(context)) postDelayed(blink, 2200)
    }

    override fun onDraw(canvas: Canvas) {
        val animate = motionVisible()
        if (animate) moveEyes() else {
            gazeX = 0f; gazeY = 0f
            velocityX = 0f; velocityY = 0f
            lastFrame = 0L
        }
        val size = minOf(width, height).toFloat()
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 100f, size / 100f)
        if (isPressed && animate) canvas.scale(.96f, .96f, 50f, 50f)
        paint.color = Loft.SURFACE
        canvas.drawPath(body, paint)
        run {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (hasFocus()) 3f else 1.8f
            paint.color = if (hasFocus()) Loft.ACCENT else Loft.LINE
            canvas.drawPath(body, paint)
            paint.style = Paint.Style.FILL
        }
        canvas.clipPath(body)
        val yaw = asin(gazeX.coerceIn(-.92f, .92f))
        val pitch = -asin(gazeY.coerceIn(-.92f, .92f))
        paint.color = Loft.ACCENT
        val sinceBlink = SystemClock.uptimeMillis() - blinkStarted
        val blinkScale = if (animate && blinkStarted != 0L && sinceBlink < 160L) {
            postInvalidateOnAnimation()
            1f - .9f * sin(sinceBlink / 160f * kotlin.math.PI.toFloat())
        } else {
            if (blinkStarted != 0L) {
                blinkStarted = 0L
                if (animate && hasWindowFocus()) postDelayed(blink, kotlin.random.Random.nextLong(2600, 5801))
            }
            1f
        }
        drawEye(canvas, -.19f, yaw, pitch, blinkScale)
        drawEye(canvas, .19f, yaw, pitch, blinkScale)
        canvas.restore()
    }

    private fun moveEyes() {
        val time = SystemClock.uptimeMillis()
        val step = if (lastFrame == 0L) 1f else ((time - lastFrame) / 16.67f).coerceIn(.25f, 2.5f)
        velocityX += ((targetX - gazeX) * .06f - velocityX * .274f) * step
        velocityY += ((targetY - gazeY) * .06f - velocityY * .274f) * step
        gazeX += velocityX * step
        gazeY += velocityY * step
        val active = hypot(targetX - gazeX, targetY - gazeY) > .001f || hypot(velocityX, velocityY) > .001f
        lastFrame = if (active) time else 0L
        if (active) postInvalidateOnAnimation()
    }

    private fun drawEye(canvas: Canvas, eye: Float, yaw: Float, pitch: Float, blinkScale: Float) {
        val surfaceZ = sqrt(1f - eye * eye)
        val x = eye * cos(yaw) + surfaceZ * sin(yaw)
        val rotatedZ = surfaceZ * cos(yaw) - eye * sin(yaw)
        val y = -rotatedZ * sin(pitch)
        val depth = (rotatedZ * cos(pitch)).coerceIn(0f, 1f)
        if (depth < .05f) return
        val foreshorten = .45f + .55f * depth
        val halfWidth = 4.5f * foreshorten * (.7f + .3f * depth)
        val halfHeight = (7.5f * foreshorten * blinkScale).coerceAtLeast(.3f)
        canvas.save()
        canvas.translate(50f + x * 42f, 50f + y * 42f)
        canvas.drawRect(-halfWidth, -halfHeight, halfWidth, halfHeight, paint)
        canvas.restore()
    }
}

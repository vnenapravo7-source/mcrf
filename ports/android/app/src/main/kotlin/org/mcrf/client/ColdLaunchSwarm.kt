package org.mcrf.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Process lifetime, independent of Activity recreation and saved preferences. */
internal object ColdLaunchSwarm {
    const val KEY = "coldLaunchSwarm"
    private const val MAX_LAYERS = 24
    private const val MAX_VISITS = 160
    private const val DEADLINE_MS = 2550L
    private const val FRAME_MARGIN_MS = 32L
    private const val DIAMOND_HOLD_MS = 160L
    private const val DIAMOND_FADE_MS = 80L
    private val gate = ColdLaunchGate()
    private var active: RebuildView? = null
    private var dismissGesture = false

    /** The first gesture reveals the page without activating a still-covered control. */
    fun consumeDismissGesture(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){
            dismissGesture=active!=null
            if(dismissGesture)active?.dismiss()
        }
        val consumed=dismissGesture
        if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL) dismissGesture=false
        return consumed
    }

    fun begin(activity: MainActivity, shell: ViewGroup) {
        val accessibility = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (!gate.take(activity.prefs.getBoolean(KEY, true),
                accessibility != null && !accessibility.isTouchExplorationEnabled && motionStyleAnimationsEnabled(activity))) return
        if (accessibility == null) return
        val host = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val effect = RebuildView(activity, host, shell, accessibility)
        active = effect
        host.addView(effect, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun pageReady(activity: MainActivity) {
        active?.takeIf { it.owner === activity }?.pageReady()
    }

    fun afterReveal(activity: MainActivity, action: () -> Unit) {
        val effect = active?.takeIf { it.owner === activity }
        if (effect == null) action() else effect.afterReveal = action
    }

    private data class Candidate(val view: View, val bounds: RectF, val priority: Int)
    private data class Layer(val bounds: RectF, val label: String, val content: RenderNode)

    /** Capture bounded, non-nested parts of the current layout. The live UI stays in place. */
    private fun collectLayers(host: ViewGroup, shell: ViewGroup): List<Layer> {
        val density = host.resources.displayMetrics.density
        val visible = Rect()
        val origin = Point()
        if (!host.getGlobalVisibleRect(visible, origin)) return emptyList()
        val candidates = ArrayList<Candidate>()
        var visited = 0
        fun collect(view: View, depth: Int) {
            if (++visited > MAX_VISITS || depth > 12 || view.visibility != View.VISIBLE ||
                !view.getGlobalVisibleRect(visible)) return
            val wide = visible.width() >= 16f * density
            val tall = visible.height() >= 12f * density
            val panel = view is ViewGroup && view.background != null &&
                visible.width() >= 96f * density && visible.height() >= 48f * density &&
                visible.height() < host.height * .76f
            val control = view is TextView || view.isClickable || view is BottomNavigationView
            val viewTag = view.tag as? String
            val server = view is ServerRow || viewTag?.startsWith("server-orbit-marker-") == true
            val serverCard = viewTag == "vpn-server-card" || viewTag == "bs-server-card"
            if (view !== shell && wide && tall && (panel || control || server)) {
                val bounds = RectF(visible).apply { offset(-origin.x.toFloat(), -origin.y.toFloat()) }
                val priority = when {
                    server -> 3
                    serverCard -> 0
                    panel -> 2
                    else -> 1
                }
                candidates.add(Candidate(view, bounds, priority))
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (visited >= MAX_VISITS) break
                    collect(view.getChildAt(index), depth + 1)
                }
            }
        }
        // The cover is a sibling, so only the real page can contribute layers.
        collect(shell, 0)
        val selected = ArrayList<Candidate>()
        for (candidate in candidates.sortedByDescending { it.priority }) {
            if (selected.size == MAX_LAYERS) break
            if (selected.none { RectF.intersects(it.bounds, candidate.bounds) }) selected += candidate
        }
        return selected.sortedBy { it.bounds.top }.map { candidate ->
            val (view, bounds) = candidate
            val clipOrigin = Point()
            val clipRect = Rect()
            view.getGlobalVisibleRect(clipRect, clipOrigin)
            val pixelWidth = bounds.width().roundToInt()
            val pixelHeight = bounds.height().roundToInt()
            val node = RenderNode("swarm-layer").apply {
                setPosition(0, 0, pixelWidth, pixelHeight)
                setClipToBounds(true)
            }
            val canvas = node.beginRecording(pixelWidth, pixelHeight)
            try {
                canvas.translate((clipOrigin.x - clipRect.left).toFloat(),
                    (clipOrigin.y - clipRect.top).toFloat())
                view.draw(canvas)
            } finally {
                node.endRecording()
            }
            val w = (pixelWidth / density).roundToInt()
            val h = (pixelHeight / density).roundToInt()
            Layer(bounds, "$w × $h", node)
        }
    }

    private class RebuildView(
        val owner: MainActivity,
        private val host: ViewGroup,
        private val shell: ViewGroup,
        private val accessibility: AccessibilityManager,
    ) : View(owner) {
        private val density = resources.displayMetrics.density
        private val backdrop: Drawable = Loft.rootBackground(context)
        private val diamond = context.getDrawable(R.drawable.qs_rkn_diamond)?.mutate()?.apply {
            setTint(Loft.ACCENT)
        }
        private var backdropNode: RenderNode? = null
        private var fadingBackdropNode: RenderNode? = null
        private val scannerSurface = Loft.SURFACE
        private val scannerRing = Loft.ACCENT
        private val construction = 0xff0d99ff.toInt()
        private val badgeFill = construction
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            textSize = dp(11f)
        }
        private val box = RectF()
        private val cursor = Path().apply {
            moveTo(0f, 0f)
            lineTo(17f, 9f)
            lineTo(9.7f, 10.6f)
            lineTo(6.4f, 17.2f)
            close()
        }
        private val cursorColors = intArrayOf(
            0xfff24e1e.toInt(),0xffa259ff.toInt(),0xff1abcfe.toInt(),0xff0acf83.toInt(),
            0xffff7262.toInt(),0xffffc700.toInt(),0xff9747ff.toInt(),0xffe8467c.toInt(),
            0xff14ae5c.toInt(),0xff0d99ff.toInt(),0xffff8a00.toInt(),0xff00b5ce.toInt(),
        )
        private var scenes = emptyList<BuildLayer>()
        private var time = 0f
        private var placementStarted = 0L
        private var placementDuration = 1L
        private val scannerStarted = SystemClock.uptimeMillis()
        private var ready = false
        private var captured = false
        private var finished = false
        var afterReveal: (() -> Unit)? = null
        private var focusedOnce = host.hasWindowFocus()
        private val timeout = Runnable { dismiss() }
        private val exploration = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            if (enabled) dismiss()
        }
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (ready && !captured && width > 0 && height > 0) capturePage()
            true
        }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = false
            isFocusable = false
            setWillNotDraw(false)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            host.viewTreeObserver.addOnPreDrawListener(preDraw)
            accessibility.addTouchExplorationStateChangeListener(exploration)
            postDelayed(timeout, DEADLINE_MS)
        }

        fun pageReady() {
            if (ready || finished) return
            ready = true
            host.postInvalidateOnAnimation()
        }

        private fun capturePage() {
            val layers = if (host.isHardwareAccelerated) collectLayers(host, shell) else emptyList()
            if (layers.isEmpty()) {
                post { dismiss() }
                return
            }
            captured = true
            scenes = layers.mapIndexed { index, layer ->
                val grab = .045f + index * .22f / layers.size + (index % 3) * .012f
                BuildLayer(
                    layer,
                    (index * 2.399963f) % (2f * PI.toFloat()),
                    if (index % 2 == 0) -1f else 1f,
                    grab,
                    grab + .52f + (index % 4) * .019f,
                    cursorColors[index % cursorColors.size],
                    badge.measureText(layer.label) / 2f + dp(4f),
                )
            }
            placementStarted = SystemClock.uptimeMillis()
            placementDuration = (scannerStarted + DEADLINE_MS - FRAME_MARGIN_MS - placementStarted).coerceAtLeast(1L)
            invalidate()
        }

        override fun onDetachedFromWindow() {
            if(active===this)active=null
            finished = true
            stop()
            for (scene in scenes) scene.layer.content.discardDisplayList()
            fadingBackdropNode?.discardDisplayList()
            backdropNode?.discardDisplayList()
            super.onDetachedFromWindow()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != VISIBLE && isAttachedToWindow) dismiss()
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) focusedOnce = true else if (focusedOnce) dismiss()
        }

        private fun stop() {
            accessibility.removeTouchExplorationStateChangeListener(exploration)
            if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnPreDrawListener(preDraw)
            removeCallbacks(timeout)
        }

        fun dismiss() {
            if (finished) return
            finished = true
            if(active===this)active=null
            stop()
            val action = afterReveal
            afterReveal = null
            host.removeView(this)
            if (action != null) host.post {
                if (!owner.isFinishing && !owner.isDestroyed) action()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (!canvas.isHardwareAccelerated) {
                post { dismiss() }
                return
            }
            if (placementStarted != 0L) {
                time = ((SystemClock.uptimeMillis() - placementStarted).toFloat() / placementDuration).coerceIn(0f, 1f)
            }
            drawBackdrop(canvas)
            drawScanner(canvas)
            for (scene in scenes) drawLayer(canvas, scene)
            if (time >= 1f) post { dismiss() } else postInvalidateOnAnimation()
        }

        private fun drawBackdrop(canvas: Canvas) {
            val viewportWidth = width
            val viewportHeight = height
            val node = backdropNode?.takeIf { it.width == viewportWidth && it.height == viewportHeight }
                ?: RenderNode("swarm-backdrop").apply {
                    fadingBackdropNode?.discardDisplayList()
                    fadingBackdropNode = null
                    backdropNode?.discardDisplayList()
                    backdropNode = this
                    setPosition(0, 0, viewportWidth, viewportHeight)
                    val recording = beginRecording(viewportWidth, viewportHeight)
                    try {
                        recording.drawColor(Loft.BACKGROUND or Color.BLACK)
                        val background = shell.background ?: backdrop.apply { setBounds(0, 0, viewportWidth, viewportHeight) }
                        background.draw(recording)
                    } finally {
                        endRecording()
                    }
                }
            val opacity = 1f - smooth(phase(.08f, .23f))
            if (opacity >= 1f) {
                canvas.drawRenderNode(node)
                return
            }
            if (opacity > 0f) {
                val state = canvas.save()
                for (scene in scenes) {
                    if (time < scene.end) canvas.clipOutRect(scene.layer.bounds)
                }
                val fade = fadingBackdropNode ?: RenderNode("swarm-backdrop-fade").apply {
                    fadingBackdropNode = this
                    setPosition(0, 0, viewportWidth, viewportHeight)
                    val recording = beginRecording(viewportWidth, viewportHeight)
                    try {
                        recording.drawRenderNode(node)
                    } finally {
                        endRecording()
                    }
                }
                fade.setAlpha(opacity)
                canvas.drawRenderNode(fade)
                canvas.restoreToCount(state)
            }
            for (scene in scenes) {
                if (time >= scene.end) continue
                val areaClip = canvas.save()
                canvas.clipRect(scene.layer.bounds)
                canvas.drawRenderNode(node)
                canvas.restoreToCount(areaClip)
            }
        }

        private fun drawScanner(canvas: Canvas) {
            val age = SystemClock.uptimeMillis() - scannerStarted
            val introOpacity = 1f - smooth(((age - DIAMOND_HOLD_MS).toFloat() / DIAMOND_FADE_MS).coerceIn(0f, 1f))
            val opacity = minOf(introOpacity, 1f - smooth(phase(0f, .035f)))
            if (opacity <= 0f) return
            val x = width * .5f
            val y = height * .46f
            val radius = dp(30f)
            box.set(x - radius, y - radius, x + radius, y + radius)
            color(scannerSurface or 0xff000000.toInt(), opacity)
            canvas.drawRoundRect(box, dp(16f), dp(16f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.4f)
            color(scannerRing, opacity * .6f)
            box.inset(dp(-5f), dp(-5f))
            val sweep = ((SystemClock.uptimeMillis() - scannerStarted) % 1600L) * .225f
            canvas.drawArc(box, sweep - 90f, 72f, false, paint)
            canvas.drawArc(box, sweep + 90f, 72f, false, paint)
            paint.style = Paint.Style.FILL
            val half = dp(16f)
            diamond?.apply {
                setBounds((x - half).roundToInt(), (y - half).roundToInt(),
                    (x + half).roundToInt(), (y + half).roundToInt())
                alpha = (255f * opacity).roundToInt()
                draw(canvas)
            }
        }

        private fun drawLayer(canvas: Canvas, scene: BuildLayer) {
            val approachStart = (scene.grab - .07f).coerceAtLeast(0f)
            if (time < approachStart || time >= scene.end + .14f) return
            val area = scene.layer.bounds
            val travel = smooth(phase(scene.grab + .024f, scene.end))
            val scale = .60f + .40f * smooth(phase(scene.end - .075f, scene.end))
            val bend = sin(travel * PI.toFloat()) * dp(18f) * scene.direction
            val originX = (area.centerX() - area.width() * .30f + cos(scene.angle) * dp(56f))
                .coerceIn(0f, maxOf(0f, width - area.width() * .60f))
            val originY = (height * .46f + sin(scene.angle) * dp(85f))
                .coerceIn(0f, maxOf(0f, height - area.height() * .60f))
            val gripX = minOf(dp(11f), area.width() * .20f)
            val gripY = minOf(dp(9f), area.height() * .22f)
            val anchorX = gripX * scale
            val anchorY = gripY * scale
            val tipX = (mix(originX + .60f * gripX, area.left + gripX, travel) + bend)
                .coerceIn(anchorX, anchorX + maxOf(0f, width - area.width() * scale))
            val tipY = (mix(originY + .60f * gripY, area.top + gripY, travel) - bend * .56f)
                .coerceIn(anchorY, anchorY + maxOf(0f, height - area.height() * scale))
            val dragX = tipX - anchorX
            val dragY = tipY - anchorY
            val release = smooth(phase(scene.end + .021f, scene.end + .14f))
            if (time < scene.end) {
                scene.layer.content.setAlpha(smooth(phase(approachStart, scene.grab)))
                val state = canvas.save()
                canvas.translate(dragX, dragY)
                canvas.scale(scale, scale)
                canvas.drawRenderNode(scene.layer.content)
                canvas.restoreToCount(state)
            }
            if (time >= scene.grab) {
                val selectionOpacity = smooth(phase(scene.grab, scene.grab + .022f)) * (1f - release)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1f)
                color(scene.color, selectionOpacity * .9f)
                canvas.drawRect(dragX, dragY, dragX + area.width() * scale, dragY + area.height() * scale, paint)
                paint.style = Paint.Style.FILL
            }
            if (travel >= .9f) {
                drawDimensions(canvas, scene, area.centerX(), area.bottom,
                    smooth((travel - .9f) / .1f) * (1f - release))
            }
            val approach = smooth(phase(approachStart, scene.grab))
            val falloff = 1f - approach
            val approachX = (cos(scene.angle) * dp(43f) - dp(13f)) * falloff
            val approachY = sin(scene.angle) * dp(38f) * falloff
            val cursorX = tipX + approachX + release * scene.direction * dp(38f)
            val cursorY = tipY + approachY - release * dp(29f)
            val pressed = smooth(phase(scene.grab - .015f, scene.grab + .01f)) *
                (1f - smooth(phase(scene.end, scene.end + .024f)))
            val cursorOpacity = smooth(phase(approachStart, approachStart + .033f)) * (1f - release)
            drawCursor(canvas, cursorX, cursorY, .94f - .12f * pressed, scene.color, cursorOpacity)
        }

        private fun drawDimensions(canvas: Canvas, scene: BuildLayer, x: Float, bottom: Float, opacity: Float) {
            val half = scene.badgeHalf
            val center = x.coerceIn(half, maxOf(half, width - half))
            val top = (bottom + dp(3f)).coerceAtMost(height - dp(18f))
            color(badgeFill, opacity)
            canvas.drawRoundRect(center - half, top, center + half, top + dp(18f), dp(2f), dp(2f), paint)
            badge.color = Color.WHITE
            badge.alpha = (255f * opacity).roundToInt()
            canvas.drawText(scene.layer.label, center, top + dp(13f), badge)
        }

        private fun drawCursor(canvas: Canvas, x: Float, y: Float, size: Float, fill: Int, opacity: Float) {
            val saved = canvas.save()
            canvas.translate(x, y)
            canvas.scale(density * size, density * size)
            canvas.translate(1.3f, 1.7f)
            color(Color.BLACK, opacity * .22f)
            canvas.drawPath(cursor, paint)
            canvas.translate(-1.3f, -1.7f)
            color(fill, opacity)
            canvas.drawPath(cursor, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = 1.35f
            color(Color.WHITE, opacity)
            canvas.drawPath(cursor, paint)
            paint.style = Paint.Style.FILL
            canvas.restoreToCount(saved)
        }

        private fun dp(value: Float) = value * density
        private fun phase(start: Float, end: Float) = ((time - start) / (end - start)).coerceIn(0f, 1f)
        private fun smooth(value: Float) = value * value * (3f - 2f * value)
        private fun mix(start: Float, end: Float, progress: Float) = start + (end - start) * progress

        private fun color(value: Int, opacity: Float) {
            paint.color = value
            paint.alpha = (Color.alpha(value) * opacity).roundToInt().coerceIn(0, 255)
        }

        private data class BuildLayer(
            val layer: Layer,
            val angle: Float,
            val direction: Float,
            val grab: Float,
            val end: Float,
            val color: Int,
            val badgeHalf: Float,
        )
    }
}

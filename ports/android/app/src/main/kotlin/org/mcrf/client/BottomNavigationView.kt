package org.mcrf.client

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.roundToInt

data class NavigationTab(val id: String, val title: String, val icon: String, val description: String, val chosen: Boolean)

/** Retained across page renders: interrupted animation starts from its actual position. */
class BottomNavigationView(
    context: Context,
    private val glyph: (String, Int) -> Drawable,
    private val animationsEnabled: () -> Boolean = {
        BottomNavigationMotion.animationsEnabled(
            Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE),
            Settings.Global.getInt(context.contentResolver, "remove_animations", 0) == 1)
    }
) : FrameLayout(context) {
    private var tabs = emptyList<NavigationTab>()
    private var style = BottomNavigationStyle.EXPANDING
    private var navigate: (String) -> Unit = {}
    private val playback = BottomNavigationPlayback()
    private val drag = BottomNavigationDrag(ViewConfiguration.get(context).scaledTouchSlop / resources.displayMetrics.density)
    private var pointerId = -1
    private var dragOrigin: String? = null
    private var settlingDrag = false
    private var targetId: String? = null
    private var centerX = Float.NaN
    private var centerY = 0f
    private var slotWidths = emptyList<Float>()
    private var selectionWeights = emptyList<Float>()
    private var motionProgress = 1f
    private var stretch = 0f
    private var fromStretch = 0f
    private var stretchDistance = 0f
    private var fromStretchDistance = 0f
    private var direction = 0f
    private var fromDirection = 0f
    private var labelWeights = emptyList<Float>()
    private var fromLabelWeights = emptyList<Float>()
    private var fromX = 0f
    private var fromY = 0f
    private var fromWidths = emptyList<Float>()
    private var fromWeights = emptyList<Float>()
    private var toWidths = emptyList<Float>()
    private var toWeights = emptyList<Float>()
    private var toX = 0f
    private var lastWidth = 0
    private var lastRtl = false
    private val cells = ArrayList<FrameLayout>()
    private val icons = HashMap<Pair<String, Int>, Drawable>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var amoledNavigationGradient: Shader? = null
    private val amoledGradientBounds = RectF()
    private val navigationBrush=SurfacePalette.Cache<Shader> {left,top,right,bottom,stops->
        LinearGradient(left,top,right,bottom,intArrayOf(stops.start,stops.middle,stops.end),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val path = Path()
    private val cutout = Path()
    private val contentClip = Path()
    private val localFill = Path()
    private val movingContent get() = settlingDrag || drag.state == BottomNavigationDrag.State.DRAGGING ||
        (style == BottomNavigationStyle.EXPANDING && playback.progress < 1f)
    private var liquidColors = BottomNavigationColors.soft(Loft.SURFACE, Loft.ACCENT, HomeUiTheme.palette(Loft.dark).glow)
    private var regionColors = BottomNavigationColors.liquid(Loft.mix(Loft.SURFACE, Loft.ACCENT, .45f),
        Loft.mix(Loft.SURFACE, HomeUiTheme.palette(Loft.dark).glow, .32f))
    private val density get() = resources.displayMetrics.density
    private fun px(value: Float) = value * density
    private fun dp(value: Int) = px(value.toFloat()).roundToInt()
    private val rtl get() = layoutDirection == View.LAYOUT_DIRECTION_RTL
    private val selected get() = tabs.indexOfFirst { it.chosen }
    private val barTop get() = BottomNavigationGeometry.top(style, selected >= 0).toFloat()
    private val restY get() = barTop + FloatingNotchMotion.CIRCLE / 2f - FloatingNotchMotion.TOP

    init {
        tag = "main-navigation"
        setWillNotDraw(false)
        clipChildren = false; clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var placement = BottomNavigationStyle.BOTTOM

    fun submit(items: List<NavigationTab>, requestedStyle: String, placement: String = BottomNavigationStyle.BOTTOM, onNavigate: (String) -> Unit) {
        this.placement = BottomNavigationStyle.position(placement)
        val nextStyle = BottomNavigationStyle.resolve(requestedStyle, placement)
        val structureChanged = tabs.map { it.id } != items.map { it.id }
        val styleChanged = style != nextStyle
        if (structureChanged || styleChanged || tabs.firstOrNull { it.chosen }?.id != items.firstOrNull { it.chosen }?.id) {
            drag.reset(); pointerId = -1
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        tabs = items; navigate = onNavigate; style = nextStyle
        label.typeface=AppTypography.active?:Typeface.create("sans-serif-medium",Typeface.NORMAL)
        label.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        val lineHeight = label.descent() - label.ascent()
        // The floating label occupies the 16dp below the circle. Keep enlarged
        // system fonts inside that slot; TalkBack retains the complete title.
        val maxLabelHeight = if (style == BottomNavigationStyle.FLOATING) 14f else 32f
        if (lineHeight > px(maxLabelHeight)) label.textSize *= px(maxLabelHeight) / lineHeight
        icons.clear() // Re-read colors when the retained host changes palette.
        liquidColors = BottomNavigationColors.soft(Loft.SURFACE, Loft.ACCENT, HomeUiTheme.palette(Loft.dark).glow)
        regionColors = if(amoled)BottomNavigationColors.liquid(0xff1a1724.toInt(),0xff09080d.toInt())
            else BottomNavigationColors.liquid(Loft.mix(Loft.SURFACE, Loft.ACCENT, .45f),
                Loft.mix(Loft.SURFACE, HomeUiTheme.palette(Loft.dark).glow, .32f))
        if (structureChanged || styleChanged) {
            settlingDrag = false
            playback.finish(); targetId = null; centerX = Float.NaN
            slotWidths = emptyList(); selectionWeights = emptyList(); toWidths = emptyList()
        }
        if (structureChanged) rebuildCells()
        cells.forEachIndexed { index, cell ->
            val tab = tabs[index]
            cell.contentDescription = tab.description; cell.isSelected = tab.chosen
        }
        requestLayout()
        if (width > 0) moveSelection()
        invalidate()
    }

    private var quickSettingsTracking = false

    internal fun setQuickSettingsTracking(active: Boolean) {
        quickSettingsTracking = active
        if (active) {
            drag.reset()
            pointerId = -1
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (quickSettingsTracking) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pointerId = event.getPointerId(0)
            dragOrigin = tabs.getOrNull(selected)?.id
            drag.begin(event.x / density, (if(atTop)height-event.y else event.y) / density, width / density, height / density,
                if (isEnabled && selected >= 0) BottomNavigationGeometry.slots(slotWidths, rtl) else emptyList())
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
            updateDrag(event)
            // Sample in parent coordinates before child cancellation or relayout.
            // Every MOVE paints even if Android sends only CANCEL to the child.
            if (event.actionMasked == MotionEvent.ACTION_MOVE && drag.state == BottomNavigationDrag.State.DRAGGING)
                previewDrag()
            else if (drag.state == BottomNavigationDrag.State.REJECTED) settleDrag()
        } else if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            drag.reject(); settleDrag()
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            drag.reject()
        }
        val owned = drag.state == BottomNavigationDrag.State.DRAGGING || drag.state == BottomNavigationDrag.State.REJECTED
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (owned) finishDrag() else { drag.reset(); pointerId = -1 }
        }
        return handled || owned
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        drag.state == BottomNavigationDrag.State.DRAGGING || drag.state == BottomNavigationDrag.State.REJECTED

    private fun updateDrag(event: MotionEvent) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0 || event.pointerCount != 1 || !isEnabled) drag.reject()
        else drag.move(event.getX(index) / density, (if(atTop)height-event.getY(index) else event.getY(index)) / density)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return drag.state != BottomNavigationDrag.State.IDLE || super.onTouchEvent(event)
    }

    private fun finishDrag() {
        if (drag.destination() != null) previewDrag()
        val destination = drag.finish()?.let { tabs.getOrNull(it)?.id }
        pointerId = -1
        parent?.requestDisallowInterceptTouchEvent(false)
        settleDrag()
        if (destination != null && destination != dragOrigin) navigate(destination)
    }

    private fun previewDrag() {
        playback.finish(); settlingDrag = true
        parent?.requestDisallowInterceptTouchEvent(true)
        val oldX = centerX
        centerX = drag.x
        selectionWeights = drag.weights(); labelWeights = selectionWeights
        val nearest = selectionWeights.maxOrNull() ?: 1f
        stretch = (1 - nearest) * 2
        if (centerX != oldX) direction = kotlin.math.sign(centerX - oldX)
        stretchDistance = 16 * stretch
        centerY = restY - if (animationsEnabled()) 14 * stretch else 0f
        val candidates = tabs.indices.map { i -> BottomNavigationGeometry.widths(width / density, tabs.size,
            style, i, label.measureText(tabs[i].title) / density) }
        slotWidths = tabs.indices.map { column -> candidates.indices.sumOf { row ->
            (candidates[row][column] * selectionWeights[row]).toDouble() }.toFloat() }
        layoutCells(); invalidate()
    }

    private fun settleDrag() {
        if (!settlingDrag || playback.progress < 1f) return
        // Force a return even when the committed ID has not changed (CANCEL).
        targetId = null; toWidths = emptyList()
        moveSelection()
    }

    private fun dragPillHalfWidth(): Float = tabs.indices.sumOf { i ->
        val expanded = BottomNavigationGeometry.widths(width / density, tabs.size, style,
            i, label.measureText(tabs[i].title) / density)[i]
        ((expanded - 8) / 2 * selectionWeights.getOrElse(i) { 0f }).toDouble()
    }.toFloat().coerceAtLeast(20f)

    private fun movingPaintCenter(): Float = BottomNavigationGeometry.paintCenter(
        centerX, dragPillHalfWidth(), width / density)

    private fun expandingGradient(left: Float, right: Float): Shader = LinearGradient(
        px(left), px(barTop+8), px(right), px(barTop+48),
        regionColors.start, regionColors.end, Shader.TileMode.CLAMP)

    private fun rebuildCells() {
        removeAllViews(); cells.clear()
        tabs.forEach { tab ->
            val cell = object : FrameLayout(context) {
                override fun drawableStateChanged() {
                    super.drawableStateChanged()
                    // The parent owns the single compact selection visual.
                    this@BottomNavigationView.invalidate()
                }
            }.apply {
                tag = "nav-${tab.id}"; isClickable = true; isFocusable = true
                minimumWidth = dp(48); minimumHeight = dp(48)
                defaultFocusHighlightEnabled = false
                accessibilityDelegate = object : AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = Button::class.java.name; info.isSelected = host.isSelected
                    }
                }
                setOnClickListener {
                    navigate(tab.id)
                }
            }
            cells.add(cell); addView(cell)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Shell consumes both system bars and IME once, also on pre-Android 11.
        val wanted = dp(barTop.toInt() + FloatingNotchMotion.BAR_HEIGHT)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(resolveSize(wanted, heightMeasureSpec), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (lastWidth != width || lastRtl != rtl) {
            drag.reset(); pointerId = -1; settlingDrag = false
            parent?.requestDisallowInterceptTouchEvent(false)
            playback.finish(); centerX = Float.NaN
            slotWidths = emptyList(); selectionWeights = emptyList(); toWidths = emptyList(); targetId = null
            lastWidth = width; lastRtl = rtl
        }
        moveSelection(); layoutCells()
        // A retained renderer can be laid out after its style reset without a
        // size change. Schedule paint explicitly; do not depend on the next tap.
        postInvalidateOnAnimation()
    }

    private fun targetWidths(): List<Float> = BottomNavigationGeometry.widths(width / density, tabs.size, style,
        selected, tabs.getOrNull(selected)?.let { label.measureText(it.title) / density } ?: 0f)

    private fun layoutCells() {
        if (slotWidths.size != cells.size) return
        BottomNavigationGeometry.slots(slotWidths, rtl).forEachIndexed { index, slot ->
            val start = px(slot.left).roundToInt(); val end = px(slot.left + slot.width).roundToInt()
            val cell = cells[index]
            cell.measure(MeasureSpec.makeMeasureSpec(end - start, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            cell.layout(start, 0, end, height)
            // Parent owns the single selection/press visual. Native ripples
            // outlive UP/CANCEL and otherwise leave a second ghost region.
            cell.background = null
        }
    }

    private fun moveSelection() {
        if (drag.state == BottomNavigationDrag.State.DRAGGING) return
        if (width == 0 || tabs.isEmpty()) return
        val widths = targetWidths()
        val weights = tabs.indices.map { if (it == selected) 1f else 0f }
        val id = tabs.getOrNull(selected)?.id
        if (id == targetId && toWidths == widths && centerX.isFinite() && animationsEnabled()) return
        val target = BottomNavigationGeometry.slots(widths, rtl).getOrNull(selected)?.center ?: 0f
        val first = !centerX.isFinite() || slotWidths.size != widths.size
        targetId = id; toWidths = widths; toWeights = weights; toX = target
        if (first || id == null || !animationsEnabled()) {
            // Settings/Appearance deliberately select no bottom tab. A style
            // reset must still publish finite geometry, before layout or paint.
            centerX = target
            centerY = restY; slotWidths = widths; selectionWeights = weights; motionProgress = 1f
            labelWeights = weights; stretch = 0f; stretchDistance = 0f
            playback.finish(); fromWeights = weights
            settlingDrag = false
            layoutCells(); invalidate(); return
        }
        fromX = centerX; fromY = centerY; fromWidths = slotWidths.toList(); fromWeights = selectionWeights.toList()
        fromLabelWeights = labelWeights.toList(); fromStretch = stretch
        fromStretchDistance = stretchDistance; fromDirection = direction
        motionProgress = 0f
        playback.start(BottomNavigationMotion.duration(style))
        invalidate()
    }

    private fun paintFrame() {
        if (playback.progress >= 1f) return
        if (!animationsEnabled()) { moveSelection(); return }
        motionProgress = playback.frame(SystemClock.uptimeMillis())
        val travel = BottomNavigationMotion.travel(style, motionProgress)
        if (settlingDrag) {
            centerX = FloatingNotchMotion.x(fromX, toX, travel)
            centerY = FloatingNotchMotion.x(fromY, restY, travel)
            slotWidths = BottomNavigationGeometry.interpolate(fromWidths, toWidths, travel)
            selectionWeights = BottomNavigationGeometry.interpolate(fromWeights, toWeights, travel)
            labelWeights = BottomNavigationGeometry.interpolate(fromLabelWeights, toWeights, travel)
            stretch = fromStretch * (1 - travel); stretchDistance = fromStretchDistance * (1 - travel)
            layoutCells()
            if (playback.progress < 1f) postInvalidateOnAnimation() else settlingDrag = false
            return
        }
        // Preserve the current silhouette too when a second tap reverses travel.
        val release = 1 - BottomNavigationMotion.ease(BottomNavigationMotion.phase(motionProgress, 0f, .22f))
        stretch = fromStretch * release + BottomNavigationMotion.stretch(motionProgress) * (1 - release)
        stretchDistance = fromStretchDistance * release + minOf(40f, kotlin.math.abs(toX - fromX) * .4f) * stretch * (1 - release)
        direction = FloatingNotchMotion.x(fromDirection, kotlin.math.sign(toX - fromX), 1 - release)
        centerX = FloatingNotchMotion.x(fromX, toX, travel)
        centerY = FloatingNotchMotion.y(fromY, restY, motionProgress)
        if (style == BottomNavigationStyle.EXPANDING) {
            slotWidths = BottomNavigationGeometry.interpolate(fromWidths, toWidths, travel)
        } else {
            slotWidths = toWidths
        }
        // Tap and drag use the same spatial weights. A separate timed icon/label
        // swap used to blink while the painted indicator was elsewhere.
        selectionWeights = BottomNavigationGeometry.weightsAt(BottomNavigationGeometry.slots(slotWidths, rtl), centerX)
        labelWeights = selectionWeights
        if (style == BottomNavigationStyle.SLIDING || style == BottomNavigationStyle.LIQUID) {
            val spatialStretch = (1 - (selectionWeights.maxOrNull() ?: 1f)) * 2
            stretch = fromStretch * release + spatialStretch * (1-release)
            stretchDistance = 16 * stretch
        }
        layoutCells()
        if (playback.progress < 1f) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val saved=canvas.save()
        if(atTop){canvas.translate(0f,height.toFloat());canvas.scale(1f,-1f)}
        drawNavigation(canvas)
        canvas.restoreToCount(saved)
    }
    var atTop=false
        set(value){if(field!=value){field=value;invalidate()}}
    private val amoled get()=HomeUiTheme.amoled
    private fun drawNavigation(canvas:Canvas) {
        super.onDraw(canvas)
        // submit() may reset the retained view before its replacement layout is
        // delivered. Rebuild a complete snapshot on the first paint as well.
        if (width > 0 && tabs.isNotEmpty() && (slotWidths.size != tabs.size || !centerX.isFinite())) moveSelection()
        paintFrame()
        if (tabs.isEmpty() || slotWidths.size != tabs.size) return
        val index = selected
        val paintCenter = if (style == BottomNavigationStyle.EXPANDING && movingContent) movingPaintCenter() else centerX
        val slots = BottomNavigationGeometry.slots(slotWidths, rtl)
        val bar = RectF(0f, px(barTop), width.toFloat(), px(barTop + FloatingNotchMotion.BAR_HEIGHT))
        paint.shader = null; paint.alpha = 255; paint.style = Paint.Style.FILL; paint.color = Loft.SURFACE
        // The surrounding bottom area is one full-width rectangular fill.
        path.reset(); path.addRect(bar, Path.Direction.CW)
        when (style) {
            BottomNavigationStyle.FLOATING -> if (index >= 0) {
                // A 2dp gap under the resting ball; the cradle becomes shallower
                // as the ball lifts, keeping the cutout attached to the motion.
                val x = px(centerX); val y = px(barTop)
                val depth = px(22 - 6 * stretch)
                cutout.reset(); cutout.moveTo(x - px(24f), y - px(1f))
                cutout.lineTo(x + px(24f), y - px(1f)); cutout.lineTo(x + px(24f), y)
                cutout.cubicTo(x + px(16f), y, x + px(19f), y + depth, x, y + depth)
                cutout.cubicTo(x - px(19f), y + depth, x - px(16f), y, x - px(24f), y)
                cutout.close()
                path.op(cutout, Path.Op.DIFFERENCE)
            }
        }
        // Share the card surface recipe, retaining the exact base silhouette.
        if(amoled) {
            paint.color=android.graphics.Color.BLACK
            if(HomeUiTheme.amoledGradient&&placement==BottomNavigationStyle.BOTTOM) {
                if(amoledNavigationGradient==null||amoledGradientBounds!=bar) {
                    amoledGradientBounds.set(bar)
                    amoledNavigationGradient=LinearGradient(bar.left,bar.top,bar.right,bar.bottom,HomeUiTheme.gradientColors(),null,Shader.TileMode.CLAMP)
                }
                paint.shader=amoledNavigationGradient
            }
        }else paint.shader = navigationBrush.value(bar.left,bar.top,bar.right,bar.bottom,Loft.mix(Loft.SURFACE,Loft.ACCENT,.18f),Loft.ACCENT,HomeUiTheme.palette(Loft.dark).glow,Loft.dark)
        canvas.drawPath(path, paint); paint.shader = null
        if (index >= 0) {
            // One local selection over the rectangular base; clip to the same
            // silhouette so the floating notch stays open.
            val saved = canvas.save(); canvas.clipPath(path)
            if (style != BottomNavigationStyle.EXPANDING && style != BottomNavigationStyle.FLOATING)
                drawRegion(canvas, centerX, slots.first().width / 2, bar.top, bar.bottom)
            canvas.restoreToCount(saved); paint.shader = null
        }
        if (index >= 0) {
            if(amoled)neonOutline()
            else paint.shader=LinearGradient(px(paintCenter - 28),px(barTop),px(paintCenter + 28),px(barTop + 56),
                regionColors.start,regionColors.end,Shader.TileMode.CLAMP)
        }
        if (index >= 0) when (style) {
            BottomNavigationStyle.FLOATING -> {
                canvas.drawCircle(px(centerX), px(centerY), px(FloatingNotchMotion.CIRCLE / 2f), paint)
            }
            BottomNavigationStyle.EXPANDING -> {
                if (movingContent) {
                    val half = dragPillHalfWidth()
                    if(!amoled)paint.shader=expandingGradient(paintCenter-half,paintCenter+half)
                    canvas.drawRoundRect(RectF(px(paintCenter - half), px(barTop + 8),
                        px(paintCenter + half), px(barTop + 48)), px(20f), px(20f), paint)
                } else slots.forEachIndexed { i, slot ->
                    val weight = selectionWeights.getOrElse(i) { 0f }
                    if (weight > 0f) {
                        paint.alpha = (255 * weight).roundToInt()
                        val half = 20 + ((slot.width - 8) / 2 - 20) * weight
                        if(!amoled)paint.shader=expandingGradient(slot.center-half,slot.center+half)
                        canvas.drawRoundRect(RectF(px(slot.center - half), px(barTop + 8),
                            px(slot.center + half), px(barTop + 48)), px(20f), px(20f), paint)
                    }
                }
            }
        }
        paint.shader = null; paint.alpha = 255;paint.style=Paint.Style.FILL
        tabs.forEachIndexed { i, tab ->
            val slot = slots[i]; val active = i == index
            // Off-tab pages have no moving/selected content. Paint every icon
            // at its own slot; never hide one behind a nonexistent indicator.
            if (index < 0) {
                drawIcon(canvas, tab.icon, Loft.MUTED, slot.center, barTop + 28)
                return@forEachIndexed
            }
            val foreground = if (style == BottomNavigationStyle.LIQUID || style == BottomNavigationStyle.SLIDING) {
                Loft.mix(Loft.MUTED, regionColors.foreground, selectionWeights.getOrElse(i) { 0f })
            }
                else if (active) liquidColors.foreground else Loft.MUTED
            when {
                style == BottomNavigationStyle.SLIDING || style == BottomNavigationStyle.LIQUID -> {
                    val shape = BottomNavigationGeometry.compactHighlight(style, stretch, direction)
                    if (kotlin.math.abs(slot.center - centerX) > shape.half + 11)
                        drawIcon(canvas, tab.icon, Loft.MUTED, slot.center, barTop + 28)
                    val content = BottomNavigationMotion.singleLabel(selectionWeights)
                    if (i == content.index)
                        drawIcon(canvas, tab.icon, regionColors.foreground, centerX, barTop + 28)
                }
                style == BottomNavigationStyle.FLOATING -> {
                    val content = BottomNavigationMotion.singleLabel(selectionWeights)
                    if (kotlin.math.abs(slot.center-centerX) > 32)
                        drawIcon(canvas, tab.icon, Loft.MUTED, slot.center, barTop + 28)
                    if (i == content.index) drawIcon(canvas, tab.icon, regionColors.foreground, centerX, centerY)
                    val halfLabelHeight = (label.descent() - label.ascent()) / density / 2
                    val labelY = minOf(barTop + 47, barTop + FloatingNotchMotion.BAR_HEIGHT - halfLabelHeight - 2)
                    if (i == content.index && content.opacity > 0f)
                        drawLabel(canvas, tab.title, Loft.INK, slot.center, labelY, slot.width - 12, content.opacity)
                }
                style == BottomNavigationStyle.EXPANDING -> {
                    val weight = selectionWeights.getOrElse(i) { 0f }
                    // Collapse finishes before the incoming pill/label expands.
                    val expandedWidth = BottomNavigationGeometry.widths(width / density, tabs.size, style,
                        i, label.measureText(tab.title) / density)[i]
                    val availableLabel = px((expandedWidth - 50).coerceAtLeast(0f))
                    val originalScale = label.textScaleX
                    val actualLabel = label.measureText(tab.title)
                    if(!movingContent && actualLabel > availableLabel && actualLabel > 0f)
                        label.textScaleX = originalScale * (availableLabel / actualLabel).coerceAtLeast(.80f)
                    val text = TextUtils.ellipsize(tab.title, label, availableLabel, TextUtils.TruncateAt.END).toString()
                    val textWidth = label.measureText(text) / density
                    val direction = if (rtl) -1 else 1
                    if (movingContent) {
                        // The active content and pill share the pointer's coordinate
                        // space. Dynamic slot widths only position the inactive icons.
                        val half = dragPillHalfWidth()
                        if (kotlin.math.abs(slot.center - paintCenter) > half + 12)
                            drawIcon(canvas, tab.icon, Loft.MUTED, slot.center, barTop + 28, 1 - weight)
                        val content = BottomNavigationMotion.singleLabel(selectionWeights)
                        if (i == content.index) {
                            val saved = canvas.save()
                            contentClip.reset()
                            contentClip.addRoundRect(RectF(px(paintCenter - half + 4), px(barTop + 8),
                                px(paintCenter + half - 4), px(barTop + 48)), px(16f), px(16f), Path.Direction.CW)
                            canvas.clipPath(contentClip)
                            val fitted = TextUtils.ellipsize(tab.title, label, px((half * 2 - 56).coerceAtLeast(0f)), TextUtils.TruncateAt.END).toString()
                            val fittedWidth = label.measureText(fitted) / density
                            val movingIconX = paintCenter - direction * (8 + fittedWidth) / 2
                            drawIcon(canvas, tab.icon, regionColors.foreground, movingIconX, barTop + 28)
                            drawLabel(canvas, fitted, regionColors.foreground, paintCenter + direction * 15,
                                barTop + 28, fittedWidth + 1, content.opacity)
                            canvas.restoreToCount(saved)
                        }
                        return@forEachIndexed
                    }
                    val iconX = slot.center - direction * (8 + textWidth) / 2 * weight
                    val labelX = iconX + direction * (19 + textWidth / 2 + 10 * (1 - weight))
                    val color = Loft.mix(Loft.MUTED, regionColors.foreground, weight)
                    drawIcon(canvas, tab.icon, color, iconX, barTop + 28)
                    if (weight > 0f) {
                        val saved = canvas.save()
                        canvas.clipRect(px(slot.left + 4), px(barTop + 8), px(slot.left + slot.width - 4), px(barTop + 48))
                        drawLabel(canvas, text, color, labelX, barTop + 28, textWidth + 1, weight)
                        canvas.restoreToCount(saved)
                    }
                    label.textScaleX = originalScale
                }
                else -> drawIcon(canvas, tab.icon, foreground, slot.center, barTop + 28)
            }
        }
    }

    private fun drawRegion(canvas: Canvas, center: Float, half: Float, top: Float, bottom: Float) {
        if(amoled){
            neonOutline()
            val x=px(center)
            val radius=px(minOf(22f,half))
            canvas.drawRoundRect(RectF(x-radius,top+px(6f),x+radius,bottom-px(6f)),radius,radius,paint)
            paint.style=Paint.Style.FILL
            return
        }
        if (style == BottomNavigationStyle.SLIDING || style == BottomNavigationStyle.LIQUID) {
            val shape = BottomNavigationGeometry.compactHighlight(style, stretch, direction)
            val x = px(center); val y = top + px(28f)
            val radiusX = px(minOf(shape.half, half)); val radiusY = px(shape.height / 2)
            paint.style = Paint.Style.FILL; paint.alpha = 255
            if (style == BottomNavigationStyle.SLIDING) {
                paint.shader = null
                paint.color = regionColors.start
                canvas.drawRoundRect(RectF(x-radiusX, y-radiusY, x+radiusX, y+radiusY), radiusY, radiusY, paint)
            } else {
                val lean = px(shape.lean)
                paint.shader = LinearGradient(x-radiusX, y-radiusY, x+radiusX, y+radiusY,
                    regionColors.start, regionColors.end, Shader.TileMode.CLAMP)
                // Four curved shoulders; deformation stays entirely inside the bar.
                localFill.reset(); localFill.moveTo(x+lean, y-radiusY)
                localFill.cubicTo(x+radiusX*.65f, y-radiusY, x+radiusX, y-radiusY*.55f, x+radiusX, y+lean*.3f)
                localFill.cubicTo(x+radiusX, y+radiusY*.65f, x+radiusX*.45f, y+radiusY, x-lean, y+radiusY)
                localFill.cubicTo(x-radiusX*.65f, y+radiusY, x-radiusX, y+radiusY*.55f, x-radiusX, y-lean*.3f)
                localFill.cubicTo(x-radiusX, y-radiusY*.65f, x-radiusX*.45f, y-radiusY, x+lean, y-radiusY)
                localFill.close(); canvas.drawPath(localFill, paint)
            }
            paint.shader = null
            return
        }
        val left = px(center - half); val right = px(center + half)
        paint.shader = LinearGradient(left, 0f, right, 0f,
            intArrayOf(regionColors.start and 0x00ffffff, regionColors.start,
                regionColors.end, regionColors.end and 0x00ffffff),
            floatArrayOf(0f, .12f, .88f, 1f), Shader.TileMode.CLAMP)
        // Every local selection/press is rounded and filled. The surrounding
        // full-width surface is still the separate rectangular base path.
        paint.style = Paint.Style.FILL
        val radius = minOf(px(24f), (right - left) / 2)
        canvas.drawRoundRect(RectF(left, top + px(2f), right, bottom - px(2f)), radius, radius, paint)
        paint.shader = null
    }

    private fun neonOutline(){
        paint.shader=null;paint.color=Loft.mix(0xff25222e.toInt(),Loft.ACCENT,.48f)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=px(1f)
    }

    private fun drawIcon(canvas: Canvas, name: String, color: Int, x: Float, y: Float, opacity: Float = 1f) {
        if (opacity <= 0f) return
        val drawable = icons.getOrPut(name to color) { glyph(name, color) }
        drawable.alpha = (255 * opacity.coerceIn(0f, 1f)).roundToInt()
        drawable.setBounds(dp(-11), dp(-11), dp(11), dp(11))
        val saved = canvas.save(); canvas.translate(px(x), px(y)); if(atTop)canvas.scale(1f,-1f);drawable.draw(canvas); canvas.restoreToCount(saved)
        drawable.alpha = 255
    }

    private fun drawLabel(canvas: Canvas, title: String, color: Int, x: Float, y: Float, maxWidth: Float, opacity: Float = 1f) {
        label.color = color; label.alpha = (255 * opacity).roundToInt()
        val text = TextUtils.ellipsize(title, label, px(maxWidth.coerceAtLeast(0f)), TextUtils.TruncateAt.END).toString()
        val saved=canvas.save()
        if(atTop){canvas.translate(0f,2*px(y));canvas.scale(1f,-1f)}
        canvas.drawText(text, px(x), px(y) - (label.ascent() + label.descent()) / 2, label)
        canvas.restoreToCount(saved)
    }

    override fun onDetachedFromWindow() {
        if (drag.state != BottomNavigationDrag.State.IDLE) {
            drag.reset(); pointerId = -1
            parent?.requestDisallowInterceptTouchEvent(false)
            settleDrag()
        }
        // Reparenting pauses the painted-frame clock, never discards the snapshot.
        playback.pause()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animationsEnabled()) moveSelection()
        else { playback.pause(); moveSelection(); invalidate() }
    }
}

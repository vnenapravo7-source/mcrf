package org.mcrf.client

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.Switch

/** Uses the same connection action and lifecycle as the circle; no second VPN state. */
class ToggleConnectionButton(
    activity: MainActivity,
    action: () -> Unit,
    style: String = ConnectionAppearance.TOGGLE,
    private val native: Boolean = false,
    gradientTrack: Boolean = false,
): Switch(activity) {
    private var connected=false
    private val artwork = MotionStyleToggle(style, gradientTrack)
    private val artworkDuration = when (style) {
        ConnectionAppearance.DOT_MATRIX -> 840L
        ConnectionAppearance.ARC_REACTOR -> 760L
        ConnectionAppearance.ECLIPSE -> 720L
        else -> 260L
    }
    private val visibleBounds = Rect()
    private var position = 0f
    private var animator: ValueAnimator? = null
    private var rendered = false
    private var failed = false

    init {
        tag="connection-primary-toggle";text="";showText=false
        activity.tune(this)
        if (TvUi.enabled(activity)) background = null
        minimumHeight=activity.dp(HEIGHT_DP)
        setPadding(0, 0, 0, 0)
        if (!native) {
            thumbDrawable = null
            trackDrawable = null
        }
        setOnClickListener { action() }
    }

    // CompoundButton performs a toggle before dispatching a click. The connection
    // owner publishes the checked state once it accepts or rejects the request.
    override fun toggle() = Unit
    fun state(connected:Boolean,pending:Boolean,enabled:Boolean,running:Boolean,failed:Boolean,actionLabel:String?=null) {
        val changed = this.connected != connected
        this.connected=connected;isChecked=connected;isEnabled=enabled
        this.failed = failed
        if (!native && (changed || !rendered)) updatePosition()
        rendered = true
        invalidate()
        contentDescription=actionLabel ?: if(running)"Отключить"else"Подключить"
        if(android.os.Build.VERSION.SDK_INT>=30)stateDescription=when {
            pending->"Подключение";connected->"Подключено";failed->"Ошибка подключения";else->"Отключено"
        }
    }

    private fun updatePosition() {
        animator?.cancel()
        animator = null
        val target = if (connected) 1f else 0f
        if (rendered && position != target && isAttachedToWindow && windowVisibility == View.VISIBLE && isShown &&
            getGlobalVisibleRect(visibleBounds) && motionStyleAnimationsEnabled(context)
        ) {
            val animation = ValueAnimator.ofFloat(position, target).apply {
                duration = artworkDuration
                interpolator = PathInterpolator(.36f, 0f, .28f, 1f)
                addUpdateListener {
                    if (getGlobalVisibleRect(visibleBounds)) {
                        position = it.animatedValue as Float
                        invalidate()
                    } else {
                        settlePosition()
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (animator === animation) animator = null
                    }
                })
            }
            animator = animation
            animation.start()
        } else {
            position = target
        }
    }

    private fun settlePosition() {
        animator?.cancel()
        animator = null
        position = if (connected) 1f else 0f
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE && animator != null) settlePosition()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE && animator != null) settlePosition()
    }

    override fun onDetachedFromWindow() {
        if (animator != null) settlePosition()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (native) return
        val saved = canvas.save()
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            canvas.translate(width.toFloat(), 0f)
            canvas.scale(-1f, 1f)
        }
        artwork.draw(canvas, width, height, position, failed, isEnabled, isFocused, isPressed)
        canvas.restoreToCount(saved)
    }

    companion object {
        const val WIDTH_DP = 96
        const val HEIGHT_DP = 48
        const val COMPACT_WIDTH_DP = 50
        const val COMPACT_HEIGHT_DP = 48

        internal fun selectedStyle(activity: MainActivity, sidebar: Boolean = false) =
            if (TvUi.enabled(activity)) ConnectionAppearance.TOGGLE
            else ConnectionAppearance.sidebarToggleStyle(
                ConnectionAppearanceUi.style(activity),
                sidebar && activity.prefs.getBoolean(ConnectionAppearance.TOGGLE_MAIN_ONLY,false),
            )
    }
}

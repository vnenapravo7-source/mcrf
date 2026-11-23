package org.mcrf.client

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout

/** Same field/accent pill and 48dp targets as VPN/Proxy, usable outside MainActivity. */
class TrafficCaptureSegments(context: Context, labels: List<String>, selected: Int = 0) : LinearLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttons = mutableListOf<Button>()
    private var fraction = selected.coerceIn(0, 1).toFloat()
    private var animator: ValueAnimator? = null
    var changed: (Int) -> Unit = {}
    var selectedItemPosition = selected.coerceIn(0, 1)
        private set
    init {
        require(labels.size == 2)
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false); setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = LayoutParams(-1, -2); minimumHeight = dp(56)
        labels.forEachIndexed { index, title ->
            val button = object : Button(context) {
                override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(info)
                    info.className = "android.widget.RadioButton"; info.isCheckable = true; info.isChecked = isSelected
                }
            }.apply {
                text = title; textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0
                minHeight = dp(48); minimumHeight = dp(48); setPadding(dp(4), 0, dp(4), 0)
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; includeFontPadding = false
                background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                stateListAnimator = null; setSingleLine(false); maxLines = 2
                setOnClickListener { setSelection(index, true) }
            }
            // Two lines must grow with the system font scale; 48dp is a minimum,
            // not a fixed viewport that clips the longer recording/scope labels.
            buttons.add(button); addView(button, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        updateLabels()
    }
    fun setSelection(index: Int, animate: Boolean = false) {
        require(index in 0..1)
        val previous = selectedItemPosition
        selectedItemPosition = index; updateLabels(); animator?.cancel()
        if (animate && isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(fraction, index.toFloat()).apply {
                duration = 220; addUpdateListener { fraction = it.animatedValue as Float; tintLabels(); invalidate() }; start()
            }
        } else { fraction = index.toFloat(); tintLabels(); invalidate() }
        if (previous != index) changed(index)
    }
    private fun updateLabels() {
        buttons.forEachIndexed { index, button ->
            button.isSelected = index == selectedItemPosition
            if (android.os.Build.VERSION.SDK_INT >= 30) button.stateDescription = if (button.isSelected) "Выбран" else "Не выбран"
        }
        tintLabels()
    }
    private fun tintLabels() {
        buttons.forEachIndexed { index, button ->
            button.setTextColor(Loft.mix(Loft.MUTED, Loft.onAccent(), if (index == 0) 1f - fraction else fraction))
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Loft.FIELD
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        val inset = dp(4).toFloat(); val half = (width - 2 * inset) / 2
        val x = inset + half * fraction
        paint.color = Loft.ACCENT
        canvas.drawRoundRect(x, inset, x + half, height - inset, height / 2f - inset, height / 2f - inset, paint)
    }
    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

package org.mcrf.client

import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView

/** Keeps the original pointer on the settings icon until release. */
internal object AppearanceMotionPill {
    fun bind(
        a: MainActivity,
        anchor: View,
        actions: List<AppearanceMotion.Action>,
        opensDown: Boolean?,
        onTrackingChanged: (Boolean) -> Unit,
    ) {
        var tracking = false
        var menu: Menu? = null
        anchor.setOnLongClickListener {
            if (!anchor.isAttachedToWindow || a.isFinishing || a.isDestroyed) return@setOnLongClickListener false
            menu?.dismiss()
            val touch = tracking
            menu = show(a, anchor, actions, opensDown, touch) {
                menu = null
                if (touch) {
                    onTrackingChanged(false)
                    anchor.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            if (menu != null && touch) {
                onTrackingChanged(true)
                anchor.parent?.requestDisallowInterceptTouchEvent(true)
            }
            menu != null
        }
        anchor.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking || menu == null) false else {
                        menu?.highlight(event.rawX, event.rawY)
                        true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    val active = menu?.takeIf { tracking }
                    tracking = false
                    if (active == null) false else {
                        view.isPressed = false
                        if (event.actionMasked == MotionEvent.ACTION_UP) active.select(event.rawX, event.rawY)
                        else active.dismiss()
                        true
                    }
                }
                else -> false
            }
        }
    }

    private fun show(
        a: MainActivity,
        anchor: View,
        actions: List<AppearanceMotion.Action>,
        opensDown: Boolean?,
        tracking: Boolean,
        onDismiss: () -> Unit,
    ): Menu? {
        val viewport = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val origin = IntArray(2).also(anchor::getLocationOnScreen)
        val bounds = PillGeometry.Bounds(viewport.left, viewport.top, viewport.right, viewport.bottom)
        val button = PillGeometry.Bounds(origin[0], origin[1], origin[0] + anchor.width, origin[1] + anchor.height)
        val margin = a.dp(6)
        val width = minOf(a.dp(248), viewport.width() - 2 * margin)
        if (width < a.dp(120)) return null
        val options = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4))
        }
        val rows = actions.map { action ->
            a.text(action.label, 14, Loft.INK).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = a.dp(48)
                setPaddingRelative(a.dp(12), a.dp(4), a.dp(12), a.dp(4))
                compoundDrawablePadding = a.dp(10)
                setCompoundDrawablesRelative(a.icon(action.icon, Loft.ACCENT).apply {
                    setBounds(0, 0, a.dp(18), a.dp(18))
                }, null, null, null)
                background = Loft.rowBackground(a, false)
                isFocusable = !tracking
                options.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        AppTypography.applyTree(options)
        val panel = ScrollView(a).apply {
            tag = "quick-settings-pill-panel"
            isVerticalScrollBarEnabled = !tracking
            addView(options)
        }
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val placement = PillGeometry.place(bounds, button, width, panel.measuredHeight, a.dp(48),
            margin, opensDown) ?: return null
        val window = PopupWindow(panel, width, placement.height, !tracking).apply {
            setIsLaidOutInScreen(true)
            isTouchable = !tracking
            isOutsideTouchable = !tracking
            setBackgroundDrawable(Loft.popupBackground(a))
            elevation = a.dp(8).toFloat()
            animationStyle = 0
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = window.dismiss()
        }
        window.setOnDismissListener {
            anchor.removeOnAttachStateChangeListener(detach)
            onDismiss()
        }
        rows.forEachIndexed { index, row ->
            row.setOnClickListener { window.dismiss(); actions[index].run() }
        }
        anchor.addOnAttachStateChangeListener(detach)
        window.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, placement.left, placement.top)
        return Menu(window, panel, rows, actions)
    }

    private class Menu(
        private val window: PopupWindow,
        private val panel: ScrollView,
        private val rows: List<View>,
        private val actions: List<AppearanceMotion.Action>,
    ) {
        private val location = IntArray(2)
        private val defaultBackgrounds = rows.map { it.background }
        private val activeBackgrounds = rows.map { Loft.rowBackground(it.context, true) }
        private var highlighted = -1
        private var pointerX = 0f
        private var pointerY = 0f
        private val edge = (14 * panel.resources.displayMetrics.density).toInt()
        private val scrollStep = (4 * panel.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        private val scrollFrame = Runnable { scrollAtEdge() }

        init {
            panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    panel.removeCallbacks(scrollFrame)
                }
            })
        }

        private fun scrollAtEdge() {
            if (!window.isShowing || !panel.isLaidOut) return
            panel.getLocationOnScreen(location)
            val y = pointerY - location[1]
            if (pointerX < location[0] || pointerX >= location[0] + panel.width) return
            val direction = when {
                y >= 0 && y < edge -> -1
                y < panel.height && y >= panel.height - edge -> 1
                else -> return
            }
            if (!panel.canScrollVertically(direction)) return
            panel.scrollBy(0, direction * scrollStep)
            updateHighlight(pointerX, pointerY)
            panel.postOnAnimation(scrollFrame)
        }

        private fun hit(x: Float, y: Float): Int {
            if (!window.isShowing || !panel.isLaidOut) return -1
            panel.getLocationOnScreen(location)
            if (x < location[0] || x >= location[0] + panel.width ||
                y < location[1] || y >= location[1] + panel.height) return -1
            return rows.indexOfFirst { row ->
                row.getLocationOnScreen(location)
                x >= location[0] && x < location[0] + row.width &&
                    y >= location[1] && y < location[1] + row.height
            }
        }

        fun highlight(x: Float, y: Float) {
            pointerX = x
            pointerY = y
            updateHighlight(x, y)
            panel.removeCallbacks(scrollFrame)
            panel.postOnAnimation(scrollFrame)
        }

        private fun updateHighlight(x: Float, y: Float) {
            val target = hit(x, y)
            if (target == highlighted) return
            rows.getOrNull(highlighted)?.background = defaultBackgrounds.getOrNull(highlighted)
            highlighted = target
            rows.getOrNull(target)?.background = activeBackgrounds.getOrNull(target)
        }

        fun select(x: Float, y: Float) {
            val target = hit(x, y)
            dismiss()
            actions.getOrNull(target)?.let { it.run() }
        }

        fun dismiss() {
            panel.removeCallbacks(scrollFrame)
            window.dismiss()
        }
    }
}

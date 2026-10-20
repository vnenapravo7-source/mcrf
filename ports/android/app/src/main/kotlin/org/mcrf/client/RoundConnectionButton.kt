package org.mcrf.client

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.Button

/** Same action/state for the large control and its scroll-time miniature. No animation loop. */
class RoundConnectionButton(private val activity: MainActivity, miniature: Boolean, action: () -> Unit) : Button(activity) {
    private val surface = Loft.PowerSurface(resources.displayMetrics.density)
    private var connected = false
    private var failed = false
    private val size = activity.dp(if (miniature) 24 else 34)
    init {
        tag = if (miniature) "connection-mini" else "connection-primary"
        text = ""; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
        minWidth = 0; minimumWidth = 0; minHeight = activity.dp(48); minimumHeight = activity.dp(48)
        stateListAnimator = null
        background = RippleDrawable(ColorStateList.valueOf(Loft.ripple()), surface,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(android.graphics.Color.WHITE) })
        setOnClickListener { action() }
    }
    fun state(connected: Boolean, pending: Boolean, enabled: Boolean, running: Boolean, failed:Boolean=false) {
        this.connected = connected; this.failed=failed; surface.state(connected, pending,failed); isEnabled = enabled
        isSelected = connected
        contentDescription = if (pending) "Идёт подключение" else if (running) "Отключить" else "Подключить"
        if(android.os.Build.VERSION.SDK_INT>=30)stateDescription = if (pending) "Подключение" else if (connected) "Подключено" else if(failed)"Ошибка подключения" else "Отключено"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val glyph = activity.icon("power", if(failed)Loft.ERROR else if (connected) 0xff32b875.toInt() else Loft.INK)
        val left = (width - size) / 2; val top = (height - size) / 2
        glyph.setBounds(left, top, left + size, top + size); glyph.alpha = if (isEnabled) 255 else 100; glyph.draw(canvas)
    }
}

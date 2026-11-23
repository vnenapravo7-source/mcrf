package org.mcrf.client

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/** Same mini-only HUD in the app and in the permission-gated system window. */
internal class ResourceMonitorHud(
    context: Context,
    private val open: () -> Unit,
    close: (() -> Unit)? = null,
    private val openDescription: String = "Открыть монитор нагрузки",
    private val move: (Float, Float) -> Unit,
) : LinearLayout(context) {
    private fun line(sp: Float) = TextView(context).apply {
        textSize = sp; setTextColor(Loft.INK); gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(18)
        isClickable = true; isFocusable = true
        setOnClickListener { open() }
    }
    private val cpu = line(ResourceMonitorSize.MINIATURE.textSp)
    private val memory = line(ResourceMonitorSize.MINIATURE.textSp)

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = Loft.connectionSurface(context)
        elevation = dp(6).toFloat()
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(cpu, LayoutParams(0, -2, 1f))
            close?.let { stop ->
                addView(ResourceMonitorUi.icon(context, false, "Выключить системный монитор", stop),
                    LayoutParams(dp(48), dp(48)))
            }
        }, LayoutParams(-1, -2))
        addView(memory, LayoutParams(-1, -2))
        for (handle in listOf(cpu, memory)) dragHandle(handle)
    }

    private fun dragHandle(handle: TextView) {
        var lastX = 0f; var lastY = 0f
        var downX = 0f; var downY = 0f; var dragging = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; lastX = downX; downY = event.rawY; lastY = downY
                    dragging = false; view.isPressed = true; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) dragging = true
                    if (dragging) {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        move(event.rawX - lastX, event.rawY - lastY)
                        lastX = event.rawX; lastY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false; view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !dragging) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    fun render(value: ResourceMonitorSnapshot) {
        val lines = ResourceMonitorText.hud(value).lines()
        cpu.text = lines[0]; memory.text = lines[1]
        cpu.contentDescription = "${cpu.text}. $openDescription"
        memory.contentDescription = "${memory.text}. $openDescription"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal object ResourceMonitorText {
    fun percent(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "—"
    fun bytes(value: Long?) = value?.let { String.format(Locale.getDefault(), "%.1f МиБ", it / 1048576.0) } ?: "—"
    fun rate(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.1f КиБ/с", it / 1024.0) } ?: "—"

    fun hud(value: ResourceMonitorSnapshot): String {
        val mark = if (value.recording.active) " ●" else ""
        return "CPU ${percent(value.processCpuPercent)}$mark\nPSS ${bytes(value.processPssBytes)}"
    }
}

package org.mcrf.client

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.animation.ValueAnimator
import android.view.View
import android.widget.Button
import kotlin.math.roundToInt

/** A real Button retains focus, click and accessibility behavior while its fill tracks APK bytes. */
internal class AppUpdateProgressAction(private val button: Button) {
    private val original = button.foreground
    private val fill = Fill(button.resources.displayMetrics.density)
    private val decorated = android.graphics.drawable.LayerDrawable(arrayOf(original?.constantState?.newDrawable() ?: ColorDrawable(android.graphics.Color.TRANSPARENT), fill))
    private val useFill = !TvUi.enabled(button.context)

    fun download(percent: Int) {
        val value = percent.coerceIn(0, 100)
        if (useFill) {
            if (button.foreground !== decorated) button.foreground = decorated
            fill.level = value * 100
        }
        button.isEnabled = false
        button.text = "Скачивание · $value%"
    }

    fun busy(label: String) {
        restore()
        button.isEnabled = false
        button.text = label
    }

    fun ready(label: String) {
        restore()
        button.text = label
        button.isEnabled = true
    }

    fun restore() {
        if (button.foreground === decorated) button.foreground = original
    }

    companion object {
        fun stopButton(activity: MainActivity, description: String) = activity.button("×", false).apply {
            contentDescription = description
            textSize = 22f
            minimumWidth = activity.dp(48)
            minWidth = activity.dp(48)
            minimumHeight = activity.dp(48)
            setPadding(0, 0, 0, 0)
            background = activity.flat(Loft.FIELD, 28, Loft.LINE)
            visibility = View.GONE
        }

        fun showStop(button: Button, show: Boolean) {
            if (button.isSelected == show) return
            button.isSelected = show
            button.animate().cancel()
            if (!ValueAnimator.areAnimatorsEnabled()) {
                button.visibility = if (show) View.VISIBLE else View.GONE
                button.translationX = 0f
                button.alpha = 1f
                return
            }
            val offset = -18f * button.resources.displayMetrics.density
            if (show) {
                if (button.visibility != View.VISIBLE) {
                    button.alpha = 0f
                    button.translationX = offset
                }
                button.visibility = View.VISIBLE
            }
            button.animate().translationX(if (show) 0f else offset).alpha(if (show) 1f else 0f)
                .setDuration(180)
                .setInterpolator(android.view.animation.PathInterpolator(.22f, 1f, .36f, 1f))
                .withEndAction { if (!button.isSelected) button.visibility = View.GONE }
                .start()
        }
    }

    private class Fill(private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val area = RectF()
        private val shape = Path()
        override fun onLevelChange(level: Int): Boolean {
            invalidateSelf()
            return true
        }
        override fun draw(canvas: Canvas) {
            if (level == 0) return
            val margin = density.roundToInt()
            area.set(bounds)
            area.inset(margin.toFloat(), margin.toFloat())
            if (area.isEmpty) return
            val radius = area.height() / 2
            shape.rewind()
            shape.addRoundRect(area, radius, radius, Path.Direction.CW)
            val saved = canvas.save()
            canvas.clipPath(shape)
            paint.color = (Loft.INK and 0x00ffffff) or 0x42000000
            val right = area.left + area.width() * level / 10_000f
            canvas.drawRect(area.left, area.top, right, area.bottom, paint)
            canvas.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

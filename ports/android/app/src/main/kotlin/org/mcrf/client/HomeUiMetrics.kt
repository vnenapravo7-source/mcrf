package org.mcrf.client

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Independent home node: caller continues updating the same live TextViews. */
object HomeUiMetrics {
    fun build(activity: MainActivity, speed: TextView, traffic: TextView): LinearLayout = with(activity) {
        card().apply {
            tag = "home-metrics"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(88)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            fun column(title: String, value: TextView) = LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(text(title, 9, Loft.MUTED).apply { gravity = Gravity.CENTER; letterSpacing = .08f }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                addView(value.apply {
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    setHorizontallyScrolling(false)
                }, LinearLayout.LayoutParams(-1, -2))
            }
            addView(column("СКОРОСТЬ", speed), LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(this@with).apply { setBackgroundColor(Loft.LINE) }, LinearLayout.LayoutParams(dp(1), dp(36)).apply { leftMargin = dp(12); rightMargin = dp(12) })
            addView(column("ЗА СЕАНС", traffic), LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
}

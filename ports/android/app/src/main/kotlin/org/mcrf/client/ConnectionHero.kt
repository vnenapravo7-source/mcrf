package org.mcrf.client

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Short connection summary; leaves the beginning of the server list above the fold. */
class ConnectionHero(private val activity: MainActivity, private val summary: LinearLayout, private val control: FrameLayout, telegram:TelegramControls) : LinearLayout(activity) {
    private val topRow=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;addView(summary);addView(control)}
    init { tag="connection-block";orientation=VERTICAL;background=Loft.Glass(resources.displayMetrics.density,22f);addView(topRow,LayoutParams(-1,-2));activity.divider(this,6,2);addView(telegram,LayoutParams(-1,-2)) }
    fun fit(compact: Boolean, roomy: Boolean) {
        gravity = Gravity.TOP
        val inset = activity.dp(if (compact) 6 else 12)
        setPadding(inset, inset, inset, inset)
        summary.getChildAt(0).visibility=if(compact)android.view.View.GONE else android.view.View.VISIBLE
        summary.getChildAt(summary.childCount-1).visibility=if(compact)android.view.View.GONE else android.view.View.VISIBLE
        summary.layoutParams = LayoutParams(0, -2, 1f)
        val diameter = activity.dp(if (compact) 64 else 72)
        control.layoutParams = LayoutParams(diameter, diameter).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            leftMargin = activity.dp(10)
        }
    }
}

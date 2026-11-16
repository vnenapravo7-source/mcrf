package org.mcrf.client

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Adapts to usable viewport, not physical diagonal. All controls remain in the layout. */
class HomeLayout(
    private val activity: MainActivity, private val hero: ConnectionHero, private val metrics: View, private val mode: View, private val hint: View
) : LinearLayout(activity) {
    private val first = LinearLayout(activity).apply { orientation = VERTICAL }
    private val second = LinearLayout(activity).apply { orientation = VERTICAL }
    private var previous = ""
    init {
        orientation = VERTICAL; gravity = Gravity.TOP
        first.addView(hero)
        second.addView(mode);second.addView(metrics);second.addView(hint)
        addView(first); addView(second)
    }
    fun fit(width: Int, height: Int) {
        val landscape = width >= activity.dp(480) && height < activity.dp(400)
        val compact = height < activity.dp(440)
        val signature = "$landscape/$compact/$height"
        if (signature == previous) return
        previous = signature
        orientation = if (landscape) HORIZONTAL else VERTICAL
        // Extra breathing room in portrait; retain the verified short-landscape fit.
        val gap = activity.dp(if (landscape) 6 else if (compact) 10 else 12)
        first.layoutParams = if (landscape) LayoutParams(0, -2, 1f).apply { rightMargin = gap } else LayoutParams(-1, -2)
        second.layoutParams = if (landscape) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2).apply { topMargin = gap }
        for (group in listOf(first, second)) for (i in 0 until group.childCount) {
            (group.getChildAt(i).layoutParams as LayoutParams).apply { topMargin = if (i == 0) 0 else gap }
        }
        hero.fit(compact, height >= activity.dp(600) && !landscape)
        mode.setPadding(activity.dp(4),activity.dp(if(landscape)0 else 4),activity.dp(4),activity.dp(if(landscape)0 else 4))
        mode.minimumHeight=activity.dp(if(landscape)48 else 56)
        val metricsInset=activity.dp(if(landscape)6 else 12)
        metrics.setPadding(activity.dp(12),metricsInset,activity.dp(12),metricsInset)
        metrics.minimumHeight=activity.dp(if(landscape)60 else 80)
        // On a short landscape display put both directional values on one line.
        // Transformation keeps live updates intact and restores two lines in portrait.
        (metrics as? LinearLayout)?.let { row ->for(i in 0 until row.childCount) {
            val column=row.getChildAt(i) as? LinearLayout ?: continue
            val value=column.getChildAt(1) as? android.widget.TextView ?: continue
            column.gravity=Gravity.CENTER
            value.gravity=Gravity.CENTER
            value.maxLines=if(landscape)1 else 2
            value.transformationMethod=if(landscape)android.text.method.SingleLineTransformationMethod.getInstance() else null
            value.textSize=if(landscape)11f else 13f
            value.ellipsize=android.text.TextUtils.TruncateAt.END
        }}
        // This is explanatory text, not an action; the mode pill retains both labels.
        hint.visibility=View.GONE
        minimumHeight = 0
    }
}

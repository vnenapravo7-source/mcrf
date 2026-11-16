package org.mcrf.client

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.roundToInt

/** Independent scrolling chrome and controls; the WebView is never nested in a ScrollView.
 * Long explanations/font scale/IME cannot consume all of an available page viewport. */
internal class OpenFluxVerificationLayout(context: Context) : LinearLayout(context) {
    val details = LinearLayout(context).apply { orientation = VERTICAL; isSaveEnabled = false }
    val actions = LinearLayout(context).apply { orientation = VERTICAL; isSaveEnabled = false }
    val detailScroll = ScrollView(context).apply { isSaveEnabled = false; addView(details) }
    val actionScroll = ScrollView(context).apply { isSaveEnabled = false; addView(actions) }
    private var page: View? = null
    init {
        orientation = VERTICAL; isSaveEnabled = false
        addView(detailScroll, LayoutParams(-1, 0, 1f))
        addView(actionScroll, LayoutParams(-1, -2))
    }
    fun setPage(view: View) {
        check(page == null)
        page = view
        addView(view, 1, LayoutParams(-1, 0, 1f))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val height = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        val detailParams = detailScroll.layoutParams as LayoutParams
        val actionParams = actionScroll.layoutParams as LayoutParams
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            // Natural controls height, then cap only the scrolling viewport. Controls
            // retain their wrap-content heights and remain reachable by scrolling.
            actions.measure(MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            actionParams.height = actions.measuredHeight.coerceAtMost(height / 2)
            val remaining = (height - actionParams.height).coerceAtLeast(0)
            if (page?.visibility == View.VISIBLE) {
                detailParams.height = (128 * resources.displayMetrics.density).roundToInt().coerceAtMost(remaining / 3)
                detailParams.weight = 0f
            } else {
                detailParams.height = 0; detailParams.weight = 1f
            }
        } else {
            actionParams.height = LayoutParams.WRAP_CONTENT
            detailParams.height = LayoutParams.WRAP_CONTENT; detailParams.weight = 0f
        }
        // Existing LayoutParams are updated before super measures: no requestLayout
        // loop, screen polling, stored dimensions or background timer.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

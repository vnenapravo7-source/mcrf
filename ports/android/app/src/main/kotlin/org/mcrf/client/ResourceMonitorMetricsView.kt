package org.mcrf.client

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

/** Inline sections in the ONE main monitor window. Values update in place; actions stay outside its scroll. */
internal class ResourceMonitorMetricsView(
    context: Context, start: () -> Unit, stop: () -> Unit, reset: () -> Unit,
) : LinearLayout(context) {
    private val values = linkedMapOf<String, TextView>()
    private var recording=false
    private val recordButton=action("play", "Продолжить запись", "resource-monitor-record-toggle") {if(recording)stop()else start()}
    private val resetButton=action("refresh", "Сбросить запись", "resource-monitor-record-reset", reset)
    private val metricRows=linkedMapOf<String,View>()
    private val sectionRows=mutableListOf<Pair<View,List<String>>>()
    val actions=LinearLayout(context).apply {
        tag="resource-monitor-record-actions";gravity=Gravity.END
        setPadding(dp(12),dp(4),dp(12),dp(4))
        addView(recordButton,LayoutParams(dp(48),dp(48)))
        addView(resetButton,LayoutParams(dp(48),dp(48)).apply {marginStart=dp(8)})
    }

    init {
        orientation = VERTICAL
        tag = "resource-monitor-inline-metrics"
        for (section in ResourceMonitorPresentation.sections(ResourceMonitorSnapshot(), false)) {
            val heading=label(section.title, Loft.INK).apply {
                setTypeface(typeface, Typeface.BOLD); setAccessibilityHeading(true)
                setPadding(0, dp(16), 0, dp(6))
            }
            addView(heading,LayoutParams(-1,-2))
            sectionRows.add(heading to section.rows.map {it.id})
            for (metric in section.rows) {
                val name = label(metric.label, Loft.MUTED)
                val value = label(metric.value, Loft.INK).apply { tag = "resource-monitor-metric-${metric.id}" }
                values[metric.id] = value
                val metricRow=object : LinearLayout(context) {
                    init {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, dp(4))
                        addView(name, LayoutParams(0, -2, 1f))
                        addView(value, LayoutParams(-2, -2))
                    }
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        val available = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                        val needed = ceil(name.paint.measureText(name.text.toString()) +
                            value.paint.measureText(value.text.toString())).toInt() + dp(16)
                        val row = available >= needed
                        orientation = if (row) HORIZONTAL else VERTICAL
                        val nameParams = name.layoutParams as LayoutParams
                        val nameWidth = if (row) 0 else -1
                        val nameWeight = if (row) 1f else 0f
                        val gap = if (row) dp(12) else 0
                        if (nameParams.width != nameWidth || nameParams.weight != nameWeight || nameParams.marginEnd != gap) {
                            nameParams.width = nameWidth; nameParams.weight = nameWeight; nameParams.marginEnd = gap
                            name.layoutParams = nameParams
                        }
                        val valueParams = value.layoutParams as LayoutParams
                        val valueWidth = if (row) -2 else -1
                        if (valueParams.width != valueWidth) { valueParams.width = valueWidth; value.layoutParams = valueParams }
                        value.gravity = if (row) Gravity.END else Gravity.START
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    }
                }
                metricRows[metric.id]=metricRow
                addView(metricRow,LayoutParams(-1,-2))
            }
        }
        render(ResourceMonitorSnapshot(), false)
    }

    fun render(snapshot: ResourceMonitorSnapshot, live: Boolean) {
        ResourceMonitorPresentation.sections(snapshot, live).flatMap { it.rows }.forEach { metric ->
            values.getValue(metric.id).let { view -> if (view.text.toString() != metric.value) view.text = metric.value }
            metricRows.getValue(metric.id).visibility=if(metric.value=="—"||metric.value=="Недоступно"||metric.value.startsWith("— "))View.GONE else View.VISIBLE
        }
        val enabled = ResourceMonitorPresentation.actions(snapshot.recording, live)
        sectionRows.forEach {(heading,ids)->heading.visibility=if(ids.any {metricRows[it]?.visibility==View.VISIBLE})View.VISIBLE else View.GONE}
        recording=snapshot.recording.active
        recordButton.isEnabled=if(recording)enabled.stop else enabled.start
        recordButton.setImageDrawable(Loft.Icon(if(recording)"pause"else"play",Loft.INK,resources.displayMetrics.density))
        recordButton.contentDescription=if(recording)"Приостановить запись"else"Продолжить запись"
        resetButton.isEnabled=enabled.reset
        recordButton.alpha=if(recordButton.isEnabled)1f else .35f
        resetButton.alpha=if(resetButton.isEnabled)1f else .35f
    }

    private fun action(icon:String,title:String,id:String,clicked:()->Unit)=ImageButton(context).apply {
        tag=id;contentDescription=title;setImageDrawable(Loft.Icon(icon,Loft.INK,resources.displayMetrics.density))
        scaleType=ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12),dp(12),dp(12),dp(12));background=Loft.buttonBackground(context)
        setOnClickListener {clicked()}
    }

    private fun label(value: String, color: Int) = TextView(context).apply {
        text = value; textSize = 13f; setTextColor(color)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** Native image control, with a borderless theme ripple and no styled/outlined text-button surface. */
internal object ResourceMonitorUi {
    fun icon(context: Context, info: Boolean, description: String, clicked: () -> Unit): ImageButton = ImageButton(context).apply {
        if (info) setImageDrawable(Loft.Icon("info", Loft.MUTED, resources.displayMetrics.density))
        else setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        imageTintList = ColorStateList.valueOf(Loft.MUTED)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val inset = (14 * resources.displayMetrics.density).toInt()
        setPadding(inset, inset, inset, inset)
        val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        try { background = attributes.getDrawable(0) } finally { attributes.recycle() }
        contentDescription = description
        setOnClickListener { clicked() }
    }
}

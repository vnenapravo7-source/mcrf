package org.mcrf.client

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Indexed legal values: dragging and accessibility cannot select an invalid count. */
internal class StreamCountSlider(context:Context,values:List<Int>,selected:Int,private val committed:(Int)->Unit):LinearLayout(context) {
    private val choices=values.distinct().sorted().also {require(it.isNotEmpty())}
    private val caption=TextView(context).apply {textSize=16f;setTextColor(Loft.ACCENT);gravity=Gravity.END;includeFontPadding=false}
    private var dragging=false
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    val value:Int get()=choices[bar.progress]
    fun setValue(number:Int){bar.progress=choices.indices.minBy {kotlin.math.abs(choices[it]-number)};refresh()}
    override fun setEnabled(enabled:Boolean){
        super.setEnabled(enabled)
        repeat(childCount){getChildAt(it).isEnabled=enabled&&(getChildAt(it) !is SeekBar||choices.size>1)}
        alpha=if(enabled)1f else 0.55f
    }
    private val bar=SeekBar(context).apply {
        tag="stream-count-slider";max=choices.lastIndex;progress=choices.indices.minBy {kotlin.math.abs(choices[it]-selected)}
        minimumHeight=dp(48);setPadding(dp(12),0,dp(12),0);splitTrack=false
        progressTintList=ColorStateList.valueOf(Loft.ACCENT);progressBackgroundTintList=ColorStateList.valueOf(Loft.LINE)
        thumbTintList=ColorStateList.valueOf(Loft.ACCENT)
        tickMark=GradientDrawable().apply {shape=GradientDrawable.OVAL;setColor(Loft.MUTED);setSize(dp(3),dp(3))}
        isEnabled=choices.size>1
        setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view:SeekBar,position:Int,fromUser:Boolean){refresh();if(fromUser&&!dragging)committed(value)}
            override fun onStartTrackingTouch(view:SeekBar){dragging=true;parent?.requestDisallowInterceptTouchEvent(true)}
            override fun onStopTrackingTouch(view:SeekBar){dragging=false;parent?.requestDisallowInterceptTouchEvent(false);committed(value)}
        })
    }
    private fun refresh(){caption.text=value.toString();bar.contentDescription="Число потоков: $value";if(android.os.Build.VERSION.SDK_INT>=30)bar.stateDescription=value.toString()}
    init {
        orientation=VERTICAL;addView(caption,LayoutParams(-1,-2));addView(bar,LayoutParams(-1,dp(48)))
        addView(LinearLayout(context).apply {
            val labels=listOf(choices.first().toString(),choices.last().toString())
            labels.forEachIndexed {index,label->addView(TextView(context).apply {text=label;textSize=11f;setTextColor(Loft.MUTED);gravity=if(index==0)Gravity.START else Gravity.END},LayoutParams(0,-2,1f))}
        },LayoutParams(-1,-2));refresh()
    }
}

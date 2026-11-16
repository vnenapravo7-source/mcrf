package org.mcrf.client

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class ConnectionIndicator(val key:String,val label:String) {
    COMET("comet","Комета"), SPIRAL("spiral","Спираль"), GYRO("gyro","Гироскоп"),
    RING("ring","Кольцо"), PULSE("pulse","Пульс"), BEACON("beacon","Маяк"), NONE("none","Без индикатора");

    companion object {
        const val KEY="appearanceConnectionIndicator"

        fun selected(activity:MainActivity)=entries.firstOrNull {it.key==activity.prefs.getString(KEY,null)}?:COMET

        fun choose(activity:MainActivity) = with(activity) {
            val content=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(dp(16),dp(8),dp(16),dp(12))
            }
            lateinit var dialog:android.app.AlertDialog
            entries.forEach {style->
                content.addView(LinearLayout(this).apply {
                    tag="indicator-option-${style.key}"
                    gravity=Gravity.CENTER_VERTICAL
                    minimumHeight=dp(48)
                    setPadding(dp(8),0,dp(8),0)
                    if(style==selected(this@with))background=android.graphics.drawable.GradientDrawable().apply {setColor(Loft.selection());cornerRadius=dp(12).toFloat()}
                    val preview=if(style==NONE)View(this@with)else ConnectionIndicatorView(this@with,style)
                    addView(preview,LinearLayout.LayoutParams(dp(28),dp(28)).apply {marginEnd=dp(12)})
                    addView(text(style.label,14,Loft.INK),LinearLayout.LayoutParams(0,-2,1f))
                    isFocusable=true
                    isSelected=style==selected(this@with)
                    setOnClickListener {
                        prefs.edit().putString(KEY,style.key).apply()
                        dialog.dismiss()
                        render()
                    }
                },space(0,4))
            }
            AppTypography.applyTree(content)
            val scroll=CompactUi.scroll(this,content)
            val changed=android.view.ViewTreeObserver.OnScrollChangedListener {
                for(i in 0 until content.childCount) {
                    (content.getChildAt(i) as? LinearLayout)?.getChildAt(0)?.invalidate()
                }
            }
            scroll.viewTreeObserver.addOnScrollChangedListener(changed)
            dialog=ThemedDialogBuilder(this).setTitle("Индикатор подключения")
                .setView(scroll).setNegativeButton("Закрыть",null).create()
            dialog.setOnDismissListener {scroll.viewTreeObserver.removeOnScrollChangedListener(changed)}
            dialog.show()
        }
    }
}

/** One drawing implementation for the preview and the live status. */
internal class ConnectionIndicatorView(context:Context,private val style:ConnectionIndicator):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val visibleBounds=Rect()

    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onVisibilityAggregated(isVisible:Boolean) {
        super.onVisibilityAggregated(isVisible)
        if(isVisible)invalidate()
    }

    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val animate=ValueAnimator.areAnimatorsEnabled()
        val t=if(animate)(SystemClock.uptimeMillis()%2400L).toDouble()/2400*(2*PI) else 0.0
        val radius=minOf(width,height)*.38f
        canvas.save()
        canvas.translate(width*.5f,height*.5f)
        paint.color=Loft.ACCENT
        fun dot(x:Double,y:Double,size:Float,opacity:Int) {
            paint.alpha=opacity
            canvas.drawCircle((x*radius).toFloat(),(y*radius).toFloat(),radius*size,paint)
        }
        when(style) {
            ConnectionIndicator.SPIRAL -> for(index in 0..16) {
                val reach=.12+index*.048
                val angle=t+index*.52
                dot(cos(angle)*reach,sin(angle)*reach,.10f,90+index*9)
            }
            ConnectionIndicator.GYRO -> for(ring in 0..1) for(index in 0..11) {
                val angle=index*PI/6+if(ring==0)t else -t
                val x=cos(angle)*.9
                val y=sin(angle)*.36
                val tilt=(if(ring==0)t else -t)+ring*PI/2
                dot(x*cos(tilt)-y*sin(tilt),x*sin(tilt)+y*cos(tilt),.095f,(150+95*sin(angle)).toInt())
            }
            ConnectionIndicator.RING -> for(i in 0..17){
                val angle=i*PI/9
                val wave=(1+cos(angle-t))/2
                dot(cos(angle)*.84,sin(angle)*.84,(.07+wave*.055).toFloat(),(65+wave*190).toInt())
            }
            ConnectionIndicator.PULSE -> for(i in 0..11){
                val angle=i*PI/6
                val reach=.62+.21*sin(t)
                dot(cos(angle)*reach,sin(angle)*reach,(.10+.035*cos(t)).toFloat(),215)
            }
            ConnectionIndicator.BEACON -> for(i in 0..23){
                val angle=i*PI/12
                val beam=(1+cos(angle-t))/2
                dot(cos(angle)*.82,sin(angle)*.82,.09f,(35+220*beam*beam*beam).toInt())
            }
            ConnectionIndicator.COMET -> for(i in 0..14){
                val angle=t-i*.15
                val reach=.76-i*.017
                dot(cos(angle)*reach,sin(angle)*reach,(.16-i*.006).toFloat(),255-i*13)
            }
            ConnectionIndicator.NONE -> Unit
        }
        canvas.restore()
        if(animate&&isShown&&windowVisibility==VISIBLE&&getLocalVisibleRect(visibleBounds))postInvalidateDelayed(32)
    }
}


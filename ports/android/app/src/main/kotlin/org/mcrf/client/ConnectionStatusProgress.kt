package org.mcrf.client

import android.animation.ValueAnimator
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/** Status display; only a failed state opens logs. Fill follows service milestones once; elapsed time never invents progress. */
internal class ConnectionStatusProgress(private val host:MainActivity,captionInBar:Boolean=true,private val open:(ConnectionProgressState.Phase)->Unit):LinearLayout(host) {
    private var phase=ConnectionProgressState.Phase.IDLE
    private var rendered=false
    private val caption=ConnectionCaptionView(host).apply {
        textSize=12f;setTextColor(Loft.INK);maxLines=2;gravity=Gravity.CENTER
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;includeFontPadding=false;tag="connection-progress-caption"
    }
    internal val captionView:TextView get()=caption
    private val rail=Rail()
    init {
        tag="connection-status-progress";orientation=VERTICAL;gravity=Gravity.CENTER
        minimumHeight=host.dp(52);setPadding(host.dp(4),host.dp(5),host.dp(4),host.dp(5))
        background=null
        addView(rail,LayoutParams(-1,host.dp(14)))
        if(captionInBar)addView(caption,LayoutParams(-1,-2).apply {topMargin=host.dp(5)})
        accessibilityLiveRegion=ACCESSIBILITY_LIVE_REGION_POLITE
        setOnClickListener {if(phase==ConnectionProgressState.Phase.FAILED)open(phase)};state(phase)
    }
    fun state(value:ConnectionProgressState.Phase) {
        if(rendered&&phase==value)return
        rendered=true
        phase=value;caption.label(value.label)
        caption.setTextColor(if(value==ConnectionProgressState.Phase.FAILED)Loft.ERROR else Loft.INK)
        val failed=value==ConnectionProgressState.Phase.FAILED
        isClickable=failed;isFocusable=failed
        contentDescription=if(failed)"${value.label}. Открыть журнал"else value.label
        if(android.os.Build.VERSION.SDK_INT>=30)stateDescription=value.label
        rail.state(value)
    }
    fun connected(label:String,name:String?,address:String?) {
        caption.connected(label,name,address)
        contentDescription=caption.contentDescription
    }
    private inner class Rail:View(host) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect=RectF()
        private var current=ConnectionProgressState.Phase.IDLE
        private var animator:ValueAnimator?=null
        private var travel=0f
        private var fill:Shader?=null
        private fun tint()=when(current) {
            ConnectionProgressState.Phase.FAILED->Loft.ERROR
            ConnectionProgressState.Phase.PAUSED,ConnectionProgressState.Phase.CHALLENGE->if(Loft.dark)0xffe5be6c.toInt()else 0xff84580a.toInt()
            ConnectionProgressState.Phase.CONNECTED->if(Loft.dark)0xff56d5a2.toInt()else 0xff08785b.toInt()
            else->Loft.ACCENT
        }
        fun state(value:ConnectionProgressState.Phase) {
            val reset=value==ConnectionProgressState.Phase.IDLE||value==ConnectionProgressState.Phase.STOPPING||
                value.moving&&!current.moving&&current!=ConnectionProgressState.Phase.CHALLENGE
            if(reset){animator?.cancel();animator=null;travel=0f}
            current=value;shader()
            val target=when(value){
                ConnectionProgressState.Phase.IDLE,ConnectionProgressState.Phase.STOPPING->0f
                ConnectionProgressState.Phase.FAILED,ConnectionProgressState.Phase.PAUSED->travel
                else->maxOf(travel,value.step/4f)
            }
            animator?.cancel();animator=null
            if(target>travel&&isAttachedToWindow&&isShown&&ValueAnimator.areAnimatorsEnabled()) {
                animator=ValueAnimator.ofFloat(travel,target).apply {
                    duration=480;interpolator=LinearInterpolator()
                    addUpdateListener {travel=it.animatedValue as Float;invalidate()};start()
                }
            }else travel=target
            invalidate()
        }
        private fun shader(){fill=if(width>0)LinearGradient(0f,0f,width.toFloat(),0f,Loft.mix(tint(),Loft.INK,.12f),tint(),Shader.TileMode.CLAMP)else null}
        override fun onAttachedToWindow(){super.onAttachedToWindow();shader()}
        override fun onDetachedFromWindow(){animator?.cancel();animator=null;super.onDetachedFromWindow()}
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);shader()}
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            val radius=height*.3f
            rect.set(0f,height*.28f,width.toFloat(),height*.72f)
            paint.shader=null;paint.color=Loft.mix(Loft.FIELD,tint(),.10f)
            canvas.drawRoundRect(rect,radius,radius,paint)
            if(travel>0f){
                val saved=canvas.save();canvas.clipRect(0f,0f,width*travel,height.toFloat())
                paint.shader=fill;canvas.drawRoundRect(rect,radius,radius,paint);paint.shader=null
                canvas.restoreToCount(saved)
            }
        }
    }
}

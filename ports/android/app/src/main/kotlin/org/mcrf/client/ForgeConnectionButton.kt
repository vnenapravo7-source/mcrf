package org.mcrf.client

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout

/** Android owns the single action and actual state; the isolated child only draws approved R10. */
class ForgeConnectionButton(private val activity:MainActivity,private val motion:ConnectionMotion,private val style:String,private val exit:String,action:()->Unit):FrameLayout(activity) {
    internal val scene=ConnectionScene(activity)
    private val visibleBounds=Rect()
    private var speed=0.0
    private var resumed=true
    private val scroll=ViewTreeObserver.OnScrollChangedListener {refresh()}
    init {
        tag="connection-primary";setPadding(0,0,0,0);setBackgroundColor(Color.TRANSPARENT)
        minimumHeight=activity.dp(164);stateListAnimator=null;isClickable=true;isFocusable=true
        addView(scene,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        setOnClickListener {action()}
    }
    fun state(connected:Boolean,pending:Boolean,enabled:Boolean,running:Boolean,failed:Boolean,stopping:Boolean,snapshot:TrafficState.Snapshot) {
        val signal=when {stopping->ConnectionMotion.Signal.STOPPING;pending->ConnectionMotion.Signal.CONNECTING;failed->ConnectionMotion.Signal.FAILED;connected->ConnectionMotion.Signal.CONNECTED;else->ConnectionMotion.Signal.IDLE}
        motion.observe(signal,SystemClock.elapsedRealtime())
        speed=if(connected&&snapshot.available&&snapshot.countersSupported)
            (snapshot.download.coerceAtLeast(0).toDouble()+snapshot.upload.coerceAtLeast(0).toDouble())/1048576.0 else 0.0
        isEnabled=enabled;isSelected=connected
        val state=when {stopping->"Отключение";pending->"Подключение";failed->"Ошибка подключения";connected->"Подключено";else->"Отключено"}
        contentDescription=(if(running)"Отключить"else"Подключить")+" · $state"+if(connected&&style==ConnectionAppearance.BLUEPRINT){
            if(!snapshot.countersSupported)" · Счётчики трафика недоступны" else if(!snapshot.available)" · Ожидаем счётчики трафика" else " · ${TrafficState.speed(snapshot)}"
        }else ""
        if(android.os.Build.VERSION.SDK_INT>=30)stateDescription=state
        refresh()
    }
    fun resumeAnimation(visible:Boolean){resumed=visible;refresh()}
    private fun canDraw()=resumed&&isAttachedToWindow&&windowVisibility==View.VISIBLE&&isShown&&getGlobalVisibleRect(visibleBounds)
    private fun refresh(){scene.present(style,exit,motion.currentSignal(),speed,ValueAnimator.areAnimatorsEnabled(),canDraw())}
    override fun onInterceptTouchEvent(event:MotionEvent)=true
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.Button"}
    override fun onAttachedToWindow(){super.onAttachedToWindow();viewTreeObserver.addOnScrollChangedListener(scroll);refresh()}
    override fun onDetachedFromWindow(){
        resumed=false;refresh()
        if(viewTreeObserver.isAlive)viewTreeObserver.removeOnScrollChangedListener(scroll)
        scene.dispose();super.onDetachedFromWindow()
    }
    override fun onVisibilityAggregated(isVisible:Boolean){super.onVisibilityAggregated(isVisible);refresh()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);refresh()}
}

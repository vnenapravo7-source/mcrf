package org.mcrf.client

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch

/** In-app edge tab: never a system overlay and never a second service/session owner. */
class EdgeConnectionControls(private val activity:MainActivity, private val telegram:TelegramBridge, connection:(Boolean,Boolean)->Unit):LinearLayout(activity) {
    private var updating=false
    private var downX=0f
    private var downY=0f
    private var startY=0f
    private var dragging=false
    private var gestureLeft=false
    private var gestureCollapsed=false
    private val collapsed:Boolean
        get()=activity.edgeConnectionState.collapsed
    private var left=activity.prefs.getBoolean("edgeLeft",false)
    private val content=LinearLayout(activity).apply {orientation=VERTICAL;gravity=Gravity.CENTER_HORIZONTAL}
    private fun handle(label:String)=Button(activity).apply {
        text=label;textSize=20f;isAllCaps=false;setTextColor(Loft.ACCENT)
        minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(0,0,0,0)
        background=activity.flat(android.graphics.Color.TRANSPARENT,12,0)
    }
    private val hide=handle("›").apply {
        tag="edge-hide";contentDescription="Скрыть панель подключения"
        setOnClickListener {setCollapsed(true)}
    }
    private val restore=handle("‹").apply {
        tag="edge-restore";contentDescription="Показать панель подключения"
        setOnClickListener {setCollapsed(false)}
    }
    private fun surface(){
        (background as? GradientDrawable)?.cornerRadii=(if(left)floatArrayOf(0f,0f,16f,16f,16f,16f,0f,0f) else floatArrayOf(16f,16f,0f,0f,0f,0f,16f,16f)).map{activity.dp(it.toInt()).toFloat()}.toFloatArray()
        hide.text=if(left)"‹"else"›";restore.text=if(left)"›"else"‹"
    }
    private fun place(rawX:Float, top:Float,save:Boolean){
        val host=parent as? android.view.View?:return
        val location=IntArray(2);host.getLocationOnScreen(location)
        left=rawX-location[0]<host.width/2f
        val available=(host.height-height).coerceAtLeast(0)
        val y=top.coerceIn(0f,available.toFloat())
        layoutParams=(layoutParams as FrameLayout.LayoutParams).apply {gravity=Gravity.TOP or if(left)Gravity.LEFT else Gravity.RIGHT;topMargin=y.toInt()}
        surface()
        if(save)activity.prefs.edit().putBoolean("edgeLeft",left).putFloat("edgeY",if(available==0)0.5f else y/available).apply()
    }
    private fun rememberedPosition(){
        val host=parent as? android.view.View?:return
        val location=IntArray(2);host.getLocationOnScreen(location)
        place(location[0]+if(left)0f else host.width.toFloat(),(host.height-height).coerceAtLeast(0)*activity.prefs.getFloat("edgeY",0.5f).coerceIn(0f,1f),false)
    }
    private fun setCollapsed(value:Boolean){
        activity.edgeConnectionState.collapsed=value
        renderCollapsed();post {rememberedPosition()}
    }
    private fun renderCollapsed(){
        content.visibility=if(collapsed)View.GONE else View.VISIBLE
        restore.visibility=if(collapsed)View.VISIBLE else View.GONE
        setPadding(activity.dp(if(collapsed)0 else 7),activity.dp(if(collapsed)0 else 6),activity.dp(if(collapsed)0 else 7),activity.dp(if(collapsed)0 else 8))
        (layoutParams as? FrameLayout.LayoutParams)?.let {it.width=activity.dp(if(collapsed)28 else 64);layoutParams=it}
        contentDescription=if(collapsed)"Панель подключения скрыта. Нажмите стрелку или смахните от края, чтобы открыть."else"VPN, белые списки и TGWS Proxy. Смахните к краю, чтобы скрыть; перетащите вверх или вниз, чтобы переместить."
        surface()
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();renderCollapsed();post {rememberedPosition()}}
    private fun begin(event:MotionEvent){
        downX=event.rawX;downY=event.rawY;startY=top.toFloat();dragging=false
        gestureLeft=left;gestureCollapsed=collapsed
    }
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN)begin(event)
        if(event.actionMasked==MotionEvent.ACTION_MOVE&&kotlin.math.hypot(event.rawX-downX,event.rawY-downY)>ViewConfiguration.get(context).scaledTouchSlop){dragging=true;parent.requestDisallowInterceptTouchEvent(true);return true}
        return super.onInterceptTouchEvent(event)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){begin(event);return true}
        if(event.actionMasked==MotionEvent.ACTION_MOVE&&!dragging&&kotlin.math.hypot(event.rawX-downX,event.rawY-downY)>ViewConfiguration.get(context).scaledTouchSlop){dragging=true;parent.requestDisallowInterceptTouchEvent(true)}
        if(!dragging)return super.onTouchEvent(event)
        when(event.actionMasked){MotionEvent.ACTION_MOVE->place(event.rawX,startY+event.rawY-downY,false)
            MotionEvent.ACTION_UP->{
                when(EdgePanelGesture.finish(gestureLeft,gestureCollapsed,event.rawX-downX,event.rawY-downY,activity.dp(24).toFloat())){
                    EdgePanelGesture.Action.HIDE->{left=gestureLeft;setCollapsed(true)}
                    EdgePanelGesture.Action.SHOW->{left=gestureLeft;setCollapsed(false)}
                    EdgePanelGesture.Action.MOVE->place(event.rawX,startY+event.rawY-downY,true)
                }
                dragging=false;parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL->{left=gestureLeft;rememberedPosition();dragging=false;parent.requestDisallowInterceptTouchEvent(false)}}
        return true
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){
        super.onInitializeAccessibilityNodeInfo(info)
        if(!collapsed)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
    }
    override fun performAccessibilityAction(action:Int,args:android.os.Bundle?):Boolean {
        if(action==AccessibilityNodeInfo.ACTION_DISMISS&&!collapsed){setCollapsed(true);return true}
        return super.performAccessibilityAction(action,args)
    }
    private val vpnLabel=activity.text("VPN",10,Loft.MUTED).apply {gravity=Gravity.CENTER;setSingleLine(true)}
    private val whitelistLabel=activity.text("БЕЛЫЕ",10,Loft.MUTED).apply {gravity=Gravity.CENTER;setSingleLine(true);contentDescription="Белые списки"}
    private val whitelistDivider=View(activity).apply {setBackgroundColor(Loft.LINE)}
    private val tgLabel=activity.text("TG WS",10,Loft.MUTED).apply {gravity=Gravity.CENTER}
    private val tgDivider=View(activity).apply {setBackgroundColor(Loft.LINE)}
    val vpn=Switch(activity).apply {tag="edge-vpn";showText=false;contentDescription="VPN · боковой тумблер";setOnCheckedChangeListener {_,on->if(!updating)connection(false,on)}}
    val whitelist=Switch(activity).apply {tag="edge-whitelists";showText=false;contentDescription="Белые списки · боковой тумблер";setOnCheckedChangeListener {_,on->if(!updating)connection(true,on)}}
    val tg=Switch(activity).apply {tag="edge-telegram";showText=false;contentDescription="TG WS · боковой тумблер";setOnCheckedChangeListener {_,on->if(!updating)try{telegram.setEnabled(on)}catch(_:Exception){updateTelegram();activity.alert("TG WS","Не удалось изменить состояние прокси.")}}}
    init {
        tag="connection-edge";orientation=VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;elevation=activity.dp(4).toFloat();setPadding(activity.dp(7),activity.dp(8),activity.dp(7),activity.dp(8))
        background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Loft.SURFACE,Loft.FIELD)).apply {
            cornerRadii=floatArrayOf(activity.dp(16).toFloat(),activity.dp(16).toFloat(),0f,0f,0f,0f,activity.dp(16).toFloat(),activity.dp(16).toFloat())
            setStroke(activity.dp(1),Loft.LINE)
        }
        addView(content,LayoutParams(-1,-2));addView(restore,LayoutParams(-1,activity.dp(72)))
        content.addView(hide,LayoutParams(-1,activity.dp(24)))
        content.addView(vpnLabel,LayoutParams(-1,-2));content.addView(vpn,LayoutParams(activity.dp(50),activity.dp(48)))
        content.addView(whitelistDivider,LayoutParams(-1,activity.dp(1)).apply {topMargin=activity.dp(3);bottomMargin=activity.dp(7)})
        content.addView(whitelistLabel,LayoutParams(-1,-2));content.addView(whitelist,LayoutParams(activity.dp(50),activity.dp(48)))
        content.addView(tgDivider,LayoutParams(-1,activity.dp(1)).apply {topMargin=activity.dp(3);bottomMargin=activity.dp(7)})
        content.addView(tgLabel,LayoutParams(-1,-2));content.addView(tg,LayoutParams(activity.dp(50),activity.dp(48)))
        listOf(vpn,whitelist,tg).forEach {toggle->toggle.minimumWidth=activity.dp(48);toggle.minimumHeight=activity.dp(48);toggle.setPadding(0,0,0,0)
            toggle.thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Loft.onAccent(),if(Loft.dark)Loft.MUTED else Loft.SURFACE))
            toggle.trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Loft.ACCENT,if(Loft.dark)Loft.LINE else Loft.MUTED))
        }
        renderCollapsed();updateTelegram()
    }
    fun updateConnection(running:Boolean,pending:Boolean,enabled:Boolean,vpnMode:Boolean,runningWhitelists:Boolean) {
        val vpnChecked=EdgeConnectionState.checked(false,running,runningWhitelists)
        val whitelistChecked=EdgeConnectionState.checked(true,running,runningWhitelists)
        updating=true
        vpn.isChecked=vpnChecked;vpn.isEnabled=enabled
        whitelist.isChecked=whitelistChecked;whitelist.isEnabled=enabled
        updating=false
        vpnLabel.text=if(pending&&vpnChecked)"…" else if(vpnMode)"VPN" else "ПРОКСИ"
        whitelistLabel.text=if(pending&&whitelistChecked)"…"else"БЕЛЫЕ"
        vpn.contentDescription="${if(vpnMode)"VPN" else "Прокси"} · ${if(vpnChecked)"отключить" else "подключить"}"
        whitelist.contentDescription="Белые списки · ${if(whitelistChecked)"отключить" else "подключить"}"
        val visible=UsageFeatures.Feature.WHITELIST in UsageFeaturesUi.selected(activity)||whitelistChecked
        listOf(whitelist,whitelistLabel,whitelistDivider).forEach {it.visibility=if(visible)View.VISIBLE else View.GONE}
    }
    fun updateTelegram(){
        updating=true;tg.isChecked=telegram.state.wanted;updating=false
        val features=UsageFeaturesUi.selected(activity)
        val visible=UsageFeatures.telegram(features,telegram.state.wanted,telegram.state.running)
        listOf(tg,tgLabel,tgDivider).forEach {it.visibility=if(visible)View.VISIBLE else View.GONE}
        tg.contentDescription="TG WS · ${if(telegram.state.wanted)"выключить" else "включить"}"
    }
}

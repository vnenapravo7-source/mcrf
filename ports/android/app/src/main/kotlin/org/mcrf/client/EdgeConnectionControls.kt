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
import android.widget.ScrollView
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
    private val pinned=EdgePanelAppearance.pinned(activity.prefs)
    private val orbit=activity.orbitEnabled()
    private val gradientBackground=orbit&&HomeUiTheme.amoledGradient
    private val dividerColor=if(orbit)0x40363547 else Loft.LINE
    internal val particleEye=AppearanceMotion.eye(activity)
    private val lockedOpen get()=pinned
    private val collapsed:Boolean
        get()=!lockedOpen&&if(activity.orbitEnabled())activity.prefs.getBoolean(EdgePanelAppearance.ORBIT_COLLAPSED,false)
            else activity.edgeConnectionState.collapsed
    private var left=EdgePanelAppearance.left(activity.prefs)
    private val tools=activity.iconButton("servers","Управление подключением").apply {
        tag="edge-open-controls"
        setOnClickListener(activity::openConnectionControls)
    }
    private val content=LinearLayout(activity).apply {orientation=VERTICAL;gravity=Gravity.CENTER_HORIZONTAL}
    internal fun editContent()=content
    private fun scrollView()=findViewWithTag<ScrollView>(if(pinned)"edge-pinned-scroll"else"edge-overlay-scroll")
    private var pendingScroll:Int?=null
    internal fun scrollOffset()=pendingScroll?:scrollView()?.scrollY?:0
    internal fun restoreScroll(y:Int){
        if(y<=0)return
        val scroll=scrollView()?:return
        pendingScroll=y
        scroll.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean{
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                scroll.scrollTo(0,y);pendingScroll=null
                return true
            }
        })
    }
    internal fun overlayInset():Pair<Int,Int>{
        if(pinned||visibility!=View.VISIBLE||!hasVisibleItems())return 0 to 0
        val width=activity.dp(if(collapsed)28 else 64)
        return if(left)width to 0 else 0 to width
    }
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
        if(orbit)(background as? GradientDrawable)?.cornerRadii=(if(left)floatArrayOf(0f,0f,16f,16f,16f,16f,0f,0f) else floatArrayOf(16f,16f,0f,0f,0f,0f,16f,16f)).map{activity.dp(it.toInt()).toFloat()}.toFloatArray()
        hide.text=if(left)"‹"else"›";restore.text=if(left)"›"else"‹"
    }
    private fun place(rawX:Float, top:Float,save:Boolean){
        val host=parent as? android.view.View?:return
        val location=IntArray(2);host.getLocationOnScreen(location)
        left=if(pinned)EdgePanelAppearance.left(activity.prefs)else rawX-location[0]<host.width/2f
        val available=(host.height-height).coerceAtLeast(0)
        val y=top.coerceIn(0f,available.toFloat())
        layoutParams=(layoutParams as FrameLayout.LayoutParams).apply {gravity=Gravity.TOP or if(left)Gravity.LEFT else Gravity.RIGHT;topMargin=y.toInt()}
        surface()
        activity.refreshOrbitInsets()
        if(save)activity.prefs.edit().putBoolean("edgeLeft",left).putFloat("edgeY",if(available==0)0.5f else y/available).apply()
    }
    private fun rememberedPosition(){
        // A pinned panel is vertically centered by the host and only changes side.
        if(pinned)return
        val host=parent as? android.view.View?:return
        val location=IntArray(2);host.getLocationOnScreen(location)
        place(location[0]+if(left)0f else host.width.toFloat(),(host.height-height).coerceAtLeast(0)*activity.prefs.getFloat("edgeY",0.5f).coerceIn(0f,1f),false)
    }
    private fun setCollapsed(value:Boolean){
        if(lockedOpen)return
        if(activity.orbitEnabled())activity.prefs.edit().putBoolean(EdgePanelAppearance.ORBIT_COLLAPSED,value).apply()
        else activity.edgeConnectionState.collapsed=value
        renderCollapsed();post {rememberedPosition()}
    }
    private fun renderCollapsed(){
        content.visibility=if(collapsed)View.GONE else View.VISIBLE
        findViewWithTag<ScrollView>("edge-overlay-scroll")?.visibility=if(collapsed)View.GONE else View.VISIBLE
        restore.visibility=if(collapsed)View.VISIBLE else View.GONE
        hide.visibility=if(lockedOpen)View.GONE else View.VISIBLE
        setPadding(activity.dp(if(collapsed)0 else 7),activity.dp(if(collapsed)0 else 8),activity.dp(if(collapsed)0 else 7),activity.dp(if(collapsed)0 else 8))
        (layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width=activity.dp(if(collapsed)28 else 64)
            layoutParams=it
        }
        contentDescription=when {
            collapsed->"Панель подключения скрыта. Нажмите стрелку или смахните от края, чтобы открыть."
            lockedOpen->"Панель подключения. Смахните в сторону другого края, чтобы переместить."
            else->"Панель подключения. Смахните к краю, чтобы скрыть."
        }
        surface()
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();renderCollapsed();post {rememberedPosition()}}
    private fun begin(event:MotionEvent){
        downX=event.rawX;downY=event.rawY;startY=top.toFloat();dragging=false
        gestureLeft=left;gestureCollapsed=collapsed
    }
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN)begin(event)
        if(event.actionMasked==MotionEvent.ACTION_MOVE&&dragStarted(event)){
            dragging=true;parent.requestDisallowInterceptTouchEvent(true);return true
        }
        return super.onInterceptTouchEvent(event)
    }
    private fun dragStarted(event:MotionEvent):Boolean {
        val dx=kotlin.math.abs(event.rawX-downX)
        val dy=kotlin.math.abs(event.rawY-downY)
        val slop=ViewConfiguration.get(context).scaledTouchSlop
        return if(pinned||activity.orbitEnabled()&&!gestureCollapsed)dx>slop&&dx>dy*1.25f else kotlin.math.hypot(dx,dy)>slop
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){begin(event);return true}
        if(event.actionMasked==MotionEvent.ACTION_MOVE&&!dragging&&dragStarted(event)){dragging=true;parent.requestDisallowInterceptTouchEvent(true)}
        if(!dragging)return super.onTouchEvent(event)
        if(pinned){
            val dx=event.rawX-downX
            val towardsOther=if(gestureLeft)dx>0 else dx<0
            when(event.actionMasked){
                MotionEvent.ACTION_MOVE->translationX=if(towardsOther)dx else 0f
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                    translationX=0f;dragging=false;parent.requestDisallowInterceptTouchEvent(false)
                    if(event.actionMasked==MotionEvent.ACTION_UP&&towardsOther&&kotlin.math.abs(dx)>=activity.dp(48)){
                        activity.prefs.edit().putBoolean("edgeLeft",!gestureLeft).apply()
                        activity.render()
                    }
                }
            }
            return true
        }
        when(event.actionMasked){MotionEvent.ACTION_MOVE->place(event.rawX,startY+event.rawY-downY,false)
            MotionEvent.ACTION_UP->{
                when(if(lockedOpen)EdgePanelGesture.Action.MOVE else EdgePanelGesture.finish(gestureLeft,gestureCollapsed,event.rawX-downX,event.rawY-downY,activity.dp(24).toFloat())){
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
        if(!collapsed&&!lockedOpen)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
    }
    override fun performAccessibilityAction(action:Int,args:android.os.Bundle?):Boolean {
        if(action==AccessibilityNodeInfo.ACTION_DISMISS&&!collapsed&&!lockedOpen){setCollapsed(true);return true}
        return super.performAccessibilityAction(action,args)
    }
    private val vpnLabel=activity.text("VPN",10,if(activity.orbitEnabled())android.graphics.Color.WHITE else Loft.MUTED).apply {gravity=Gravity.CENTER;setSingleLine(true)}
    private val whitelistLabel=activity.text("БС",10,Loft.MUTED).apply {gravity=Gravity.CENTER;setSingleLine(true);contentDescription="Белые списки"}
    private val whitelistDivider=View(activity).apply {setBackgroundColor(dividerColor)}
    private val tgLabel=activity.text("TG WS",10,Loft.MUTED).apply {gravity=Gravity.CENTER}
    private val tgDivider=View(activity).apply {setBackgroundColor(dividerColor)}
    private val connectionStyle = ToggleConnectionButton.selectedStyle(activity, sidebar = true)
    val vpn: ToggleConnectionButton = ToggleConnectionButton(activity, { connection(false, wantsConnection(false)) }, connectionStyle, gradientTrack=gradientBackground)
        .apply { tag = "edge-vpn" }
    val whitelist: ToggleConnectionButton = ToggleConnectionButton(activity, { connection(true, wantsConnection(true)) }, connectionStyle, gradientTrack=gradientBackground)
        .apply { tag = "edge-whitelists" }
    val tg = ToggleConnectionButton(activity, {
        try {
            telegram.setEnabled(!telegram.state.wanted)
        } catch (_: Exception) {
            updateTelegram()
            activity.alert("TG WS", "Не удалось изменить состояние прокси.")
        }
    }, connectionStyle, gradientTrack=gradientBackground).apply { tag = "edge-telegram" }
    private fun wantsConnection(whitelists: Boolean) = !(if (whitelists) whitelist else vpn).isChecked
    private val autoLabel=activity.text("Авто-БС",10,Loft.MUTED).apply {gravity=Gravity.CENTER;setSingleLine(true)}
    private val autoDivider=View(activity).apply {setBackgroundColor(dividerColor)}
    val autoDetection=Switch(activity).apply {tag="edge-auto-bs";showText=false;contentDescription="Автоопределение БС · боковой тумблер"
        setOnCheckedChangeListener {_,on->if(!updating){activity.setBsDetection(on);updateAutoDetection()}}}
    private val detectionInterval=AutoControlsUi.interval(activity,AutoCheckIntervals.Kind.DETECTION).apply {
        tag="edge-auto-bs-interval"
    }
    private val autoSelectionLabel=activity.text("Автовыбор",9,Loft.MUTED).apply {gravity=Gravity.CENTER;setSingleLine(true)}
    private val autoSelectionDivider=View(activity).apply {setBackgroundColor(dividerColor)}
    private val autoSelectionEnd=View(activity).apply {setBackgroundColor(dividerColor)}
    private val autoSelection=Switch(activity).apply {
        tag="edge-auto-selection";showText=false;contentDescription="Автовыбор сервера"
        setOnCheckedChangeListener {_,enabled->if(!updating)AutoControlsUi.toggle(activity,enabled)}
    }
    private val autoSelectionSettings=activity.iconButton("auto-selection","Настройки автовыбора",18).apply {
        tag="edge-auto-selection-settings";minWidth=0;minimumWidth=0;setPadding(0,0,0,0)
        setOnClickListener {AutoControlsUi.showSettings(activity,it)}
    }
    private val routes=activity.iconButton("routes","Группа маршрутов",20).apply {
        tag="edge-routing-group";minWidth=0;minimumWidth=0;setPadding(0,0,0,0)
        setOnClickListener {RoutingGroupsUi.choose(activity,it)}
    }
    private val modeSwitch=if(pinned&&UsageFeatures.showModeSwitch(UsageFeaturesUi.selected(activity)))ModeSwitch(activity,activity.prefs.getBoolean("tun",true),
        java.util.function.Consumer(activity::changeMode),listOf("VPN","Прокси"),listOf("shield","proxy"),compact=true,vertical=true)
        .apply {tag="edge-vpn-proxy-mode"} else null

    private fun addPinnedActions(){
        fun action(icon:String,label:String,click:()->Unit)=activity.iconButton(icon,label,20).apply {
            tag="edge-action-$icon"
            setOnClickListener {click()}
            content.addView(this,LayoutParams(activity.dp(48),activity.dp(48)))
        }
        val paste=action("clipboard","Вставить из буфера",activity::importFromClipboard)
        if(!TvUi.enabled(activity)){
            paste.contentDescription="Вставить из буфера · удерживайте для сканирования QR"
            paste.setOnLongClickListener {activity.scanServerQr();true}
        }
        action("ping","Проверить задержку серверов",activity::measureEdgeServers)
        action("refresh","Обновить подписки"){activity.refreshSubscriptions()}.setOnLongClickListener {anchor->
            SubscriptionGroupingRefreshUi.chooseInterval(activity,anchor){}
        }
        addPanelDivider()
        action("vk","Настройки VK Turn"){activity.navigate(ClientSettingsNavigation.VK)}
        if(activity.showWarpTools())action("warp","WARP · добавить сервер"){activity.navigate("WARP")}
    }

    private fun addPanelDivider(){
        content.addView(View(activity).apply {setBackgroundColor(dividerColor)},LayoutParams(-1,activity.dp(1)).apply {
            topMargin=activity.dp(5);bottomMargin=activity.dp(5)
        })
    }
    init {
        tag="connection-edge"
        orientation=VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;elevation=activity.dp(if(orbit)0 else 4).toFloat()
        setPadding(activity.dp(7),activity.dp(8),activity.dp(7),activity.dp(8))
        val theme=HomeUiTheme.palette(Loft.dark)
        val standardColors=if(Loft.dark)intArrayOf(
            Loft.mix(theme.surface,theme.end,.42f),
            Loft.mix(theme.surface,theme.end,.75f),
            theme.end
        )else intArrayOf(Loft.SURFACE,Loft.FIELD)
        background=(if(gradientBackground)HomeUiTheme.gradient() else
            GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if(orbit)intArrayOf(0xff0b0a10.toInt(),0xff030305.toInt())else standardColors)).apply {
            setStroke(activity.dp(1),if(orbit)0x50363547 else Loft.LINE)
            if(!orbit)cornerRadius=activity.dp(16).toFloat()
        }
        val viewport=ScrollView(activity).apply {
            tag=if(pinned)"edge-pinned-scroll" else "edge-overlay-scroll"
            isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER
            addView(content)
        }
        addView(viewport,LayoutParams(-1,-2))
        addView(restore,LayoutParams(-1,activity.dp(72)))
        content.addView(hide,LayoutParams(-1,activity.dp(24)))
        content.addView(tools,LayoutParams(activity.dp(48),activity.dp(48)))
        fun connectionSlot() = LayoutParams(activity.dp(ToggleConnectionButton.COMPACT_WIDTH_DP), activity.dp(ToggleConnectionButton.COMPACT_HEIGHT_DP))
        content.addView(vpnLabel,LayoutParams(-1,-2));content.addView(vpn,connectionSlot())
        content.addView(whitelistDivider,LayoutParams(-1,activity.dp(1)).apply {topMargin=activity.dp(3);bottomMargin=activity.dp(7)})
        content.addView(whitelistLabel,LayoutParams(-1,-2));content.addView(whitelist,connectionSlot())
        if(pinned){
            modeSwitch?.let {
                addPanelDivider()
                content.addView(it,LayoutParams(-1,-2).apply {topMargin=activity.dp(6);bottomMargin=activity.dp(6)})
            }
            addPanelDivider()
            addPinnedActions()
        }
        content.addView(tgDivider,LayoutParams(-1,activity.dp(1)).apply {topMargin=activity.dp(3);bottomMargin=activity.dp(7)})
        content.addView(tgLabel,LayoutParams(-1,-2));content.addView(tg,connectionSlot())
        particleEye?.let {content.addView(it,LayoutParams(activity.dp(48),activity.dp(48)))}
        content.addView(autoDivider,LayoutParams(-1,activity.dp(1)).apply {topMargin=activity.dp(3);bottomMargin=activity.dp(7)})
        content.addView(autoLabel,LayoutParams(-1,-2));content.addView(autoDetection,LayoutParams(activity.dp(50),activity.dp(48)))
        content.addView(detectionInterval,LayoutParams(activity.dp(48),activity.dp(44)))
        content.addView(autoSelectionDivider,LayoutParams(-1,activity.dp(1)).apply {topMargin=activity.dp(3);bottomMargin=activity.dp(7)})
        content.addView(autoSelectionLabel,LayoutParams(-1,-2))
        content.addView(autoSelection,LayoutParams(activity.dp(50),activity.dp(48)))
        content.addView(autoSelectionSettings,LayoutParams(activity.dp(48),activity.dp(44)))
        content.addView(autoSelectionEnd,LayoutParams(-1,activity.dp(1)).apply {topMargin=activity.dp(3);bottomMargin=activity.dp(7)})
        content.addView(routes,LayoutParams(activity.dp(48),activity.dp(48)).apply {topMargin=activity.dp(4)})
        listOf(vpn,whitelist,tg).forEach { it.minimumHeight = activity.dp(ToggleConnectionButton.COMPACT_HEIGHT_DP) }
        listOf(autoDetection,autoSelection).forEach {toggle->toggle.minimumWidth=activity.dp(48);toggle.minimumHeight=activity.dp(48);toggle.setPadding(0,0,0,0)
            toggle.thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(if(Loft.dark)Loft.INK else Loft.onAccent(),if(Loft.dark)Loft.MUTED else Loft.SURFACE))
            toggle.trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Loft.ACCENT,if(Loft.dark)Loft.LINE else Loft.MUTED))
        }
        renderCollapsed();updateTelegram();updateAutoDetection();updateAutoSelection()
    }
    fun updateConnection(running:Boolean,pending:Boolean,enabled:Boolean,vpnMode:Boolean,runningWhitelists:Boolean,pendingWhitelists:Boolean?=null) {
        val vpnChecked=EdgeConnectionState.indicator(false,running,pending,runningWhitelists,pendingWhitelists)
        val whitelistChecked=EdgeConnectionState.indicator(true,running,pending,runningWhitelists,pendingWhitelists)
        val unified=activity.mergedConnections()
        val vpnOn = if (unified) vpnChecked || whitelistChecked else vpnChecked
        vpn.state(vpnOn, pending && vpnOn, enabled, vpnOn, false,
            "${if(vpnMode)"VPN" else "Прокси"} · ${if(vpnOn)"отключить" else "подключить"}")
        whitelist.state(whitelistChecked, pending && whitelistChecked, enabled, whitelistChecked, false,
            "Белые списки · ${if(whitelistChecked)"отключить" else "подключить"}")
        vpnLabel.text=if(pending&&vpn.isChecked)"…" else if(unified||vpnMode)"VPN" else "ПРОКСИ"
        whitelistLabel.text=if(pending&&whitelistChecked)"…"else"БС"
        modeSwitch?.setMode(vpnMode,true)
        modeSwitch?.setAvailable(enabled)
        val visible=UsageFeatures.Feature.WHITELIST in UsageFeaturesUi.selected(activity)||whitelistChecked
        listOf(whitelist,whitelistLabel,whitelistDivider).forEach {it.visibility=if(visible)View.VISIBLE else View.GONE}
        autoDetection.isEnabled=enabled;applyAppearance()
    }
    fun updateTelegram(){
        val wanted = telegram.state.wanted
        tg.state(wanted, false, true, wanted, false, "TG WS · ${if(wanted)"выключить" else "включить"}")
        val features=UsageFeaturesUi.selected(activity)
        val visible=UsageFeatures.telegram(features,telegram.state.wanted,telegram.state.running)
        listOf(tg,tgLabel,tgDivider).forEach {it.visibility=if(visible)View.VISIBLE else View.GONE}
        applyAppearance()
    }
    fun updateAutoDetection(){
        updating=true;autoDetection.isChecked=activity.prefs.getBoolean(BsDetectionMonitor.KEY,false);updating=false
        autoDetection.isEnabled=!activity.working();applyAppearance()
    }
    fun updateAutoSelection(){
        updating=true
        autoSelection.isChecked=AutoControlsUi.mode(activity)!=BsAutoSelectionPolicy.OFF
        updating=false
        AutoControlsUi.refreshInterval(activity,detectionInterval,AutoCheckIntervals.Kind.DETECTION)
    }
    fun hasVisibleItems()=(lockedOpen||activity.orbitEnabled()||!EdgePanelAppearance.hidden(activity.prefs))&&listOf(vpn,whitelist,tg,autoDetection,autoSelection,routes,tools).any {it.visibility==View.VISIBLE}
    fun applyAppearance(){
        val observed=listOf(vpn,vpnLabel,whitelist,whitelistLabel,whitelistDivider,tg,tgLabel,tgDivider,autoDetection,autoLabel,autoDivider,routes)
        val before=observed.map {it.visibility}
        tools.visibility=if(!pinned&&(activity.orbitEnabled()||activity.prefs.getBoolean(ConnectionControlsDock.HIDDEN,false)))View.VISIBLE else View.GONE
        val features=UsageFeaturesUi.selected(activity)
        fun show(key:String,available:Boolean,views:List<View>){val visible=EdgePanelAppearance.shown(activity.prefs,key)&&available;views.forEach {it.visibility=if(visible)View.VISIBLE else View.GONE}}
        show("edgeShowVpn",true,listOf(vpn,vpnLabel))
        if(lockedOpen||activity.mergedConnections()){vpn.visibility=View.VISIBLE;vpnLabel.visibility=View.VISIBLE}
        show("edgeShowBs",!activity.mergedConnections()&&(UsageFeatures.Feature.WHITELIST in features||whitelist.isChecked),listOf(whitelist,whitelistLabel,whitelistDivider))
        show(EdgePanelAppearance.TELEGRAM,UsageFeatures.telegram(features,telegram.state.wanted,telegram.state.running),listOf(tg,tgLabel,tgDivider))
        show("edgeShowAutoBs",UsageFeatures.Feature.WHITELIST in features||autoDetection.isChecked,listOf(autoDetection,autoLabel,autoDivider,detectionInterval))
        show("edgeShowRoutes",ExperienceModeStore(activity).load()==ExperienceMode.EXPERT,listOf(routes))
        visibility=if(hasVisibleItems())View.VISIBLE else View.GONE
        activity.enforceConstructorVisibility()
        if(before!=observed.map {it.visibility})post {rememberedPosition()}
    }
}

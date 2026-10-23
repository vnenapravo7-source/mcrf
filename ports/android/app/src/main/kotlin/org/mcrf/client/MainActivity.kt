package org.mcrf.client

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.*
import android.net.VpnService
import android.os.*
import android.text.InputType
import android.text.TextUtils
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : Activity() {
    // Activity.getPreferences derives its name from the launcher alias on Android.
    // Keep the historical name shared with workers/backups for every icon/deep link.
    val prefs get() = getSharedPreferences("MainActivity",MODE_PRIVATE)
    lateinit var catalogue: Connections; private set
    lateinit var routing: RoutingStore; private set
    var selected = ""; private set
    var automatic = false; private set
    private val handler = Handler(Looper.getMainLooper())
    private val serverSwitch=ServerSwitchTransition()
    private val serverSwitchPoll=object:Runnable {
        override fun run() {
            val target=serverSwitch.target?:return
            when(serverSwitch.poll(SystemClock.elapsedRealtime(),active(),McrfVpnService.cleanupPending(),selected,hasWindowFocus())) {
                ServerSwitchTransition.Next.WAIT->handler.postDelayed(this,100)
                ServerSwitchTransition.Next.READY->prepareConnection(false,requestedWhitelists=target.whitelists)
                ServerSwitchTransition.Next.TIMED_OUT->alert("Переключение сервера","Движок ещё освобождает ресурсы. Повтори подключение после его остановки.")
                else->Unit
            }
            updateHomeControl()
        }
    }
    private val recentsDeparture=RecentsDeparture()
    private val recentsPrivacyCheck=Runnable {finishHiddenUiIfLeaving()}
    private val worker = Executors.newSingleThreadExecutor()
    private val presentationWorker=Executors.newSingleThreadExecutor()
    private val probeWorker = Executors.newSingleThreadExecutor()
    @Volatile private var probeBusy=false
    private lateinit var shell: LinearLayout
    private lateinit var page: LinearLayout
    private var current = "Главная"
    @Volatile private var savedConfig = ""
    private var loading = true; private var busy = false
    private val deferredNavigation=DeferredNavigation()
    private var source: EditText? = null; private var port: EditText? = null; private var tun: Switch? = null
    private var status: TextView? = null; private var connectionHint: TextView? = null
    private var connectionTarget:TextView?=null
    private var selectedServer: TextView? = null
    private var startupAccessStarted=false
    private var startupAccessPending:Pair<Long,DirectAccessCheck.Result>?=null
    private var navigationRevision=0L
    private var observedBsSwitch=McrfVpnService.autoBsSwitchSerial
    private var observedVpnSwitch=McrfVpnService.autoVpnSwitchSerial
    private val startupAccessWorker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var connect: View? = null
    private val connectionMotion=ConnectionMotion()
    private var animationResumed=false
    private var lastConnectionPage="Главная"
    private var miniature: EdgeConnectionControls? = null
    internal val edgeConnectionState=EdgeConnectionState()
    private var miniatureShown = false
    private lateinit var viewport: FrameLayout
    private var serversPanel: LinearLayout? = null
    private var settingsOrigin = "Главная"
    private var advancedOrigin=ClientSettingsNavigation.ROOT
    private var settingsDirectEntry=false
    private var returnToDeployVk=false
    var selectedDeployRecipe: String?=null
    private var onboardingVisible = false
    private var homeLayout: HomeLayout? = null; private var modeSwitch: ModeSwitch? = null; private var modeHint: TextView? = null
    private lateinit var telegram:TelegramBridge
    private var telegramControls:TelegramControls?=null
    var pageRefresh: (() -> Unit)? = null
    var autoControlsRefresh: (() -> Unit)? = null
    private var captchaControlsRefresh: (() -> Unit)? = null
    private var catalogueRefreshInFlight=false
    private var lastCatalogueRefresh=0L
    private val probeRefresh: () -> Unit = {ui {pageRefresh?.invoke()}}
    private var activeScroll: ScrollView? = null
    private var renderedPage: String? = null
    private var renderedLoading=true
    private var pendingBackupPath: String? = null
    private lateinit var engineImport: EngineImportUi
    private var pendingConfigLink: String? = null
    private var pendingSettingsSave: Runnable? = null
    private var resumeAttempted=false
    private var preparedProxyPort: Int?=null
    private var pendingConnectionWhitelists:Boolean?=null
    private var deployUi: AndroidDeployUi?=null
    internal val deployNavigation=AndroidDeployBrowserUi.Navigation()
    private var warpController: WarpController?=null
    private var primaryChooser:AlertDialog?=null
    private var usageChooser:AlertDialog?=null
    private var carrierChallengeDialog:AlertDialog?=null
    private var carrierChallengePromptId:String?=null
    internal val routingScreenCache=RoutingScreenCache<LinearLayout>()
    private var pendingPrimaryConnection:Boolean?=null
    private val primaryHost:EngineUpdatePrimaryUi.Host=object:EngineUpdatePrimaryUi.Host {
        override fun selected()=ClientPrimarySelection.selected(this@MainActivity)
        override fun confirmationRequired()=!prefs.getBoolean(ClientPrimarySelection.DUAL_CONFIRMED,false)
        override fun validateFullSnapshot(choiceId:String):List<String> {
            // The actual full snapshot and installed-native gate run asynchronously below.
            return emptyList()
        }
        override fun selectValidated(choiceId:String) {
            if(working())return
            busy=true
            worker.execute {
                try {
                    require(!active()&&ProbeCoordinator.measurements==null){"Дождись остановки соединения и замера перед сменой движка."}
                    val validationChoice=ClientPrimarySelection.effectiveWireGuardChoice(this@MainActivity,choiceId,
                        findSelected()?.protocol?.let(WgAddonPolicy::isWireGuard)==true)
                    val packetChoice=validationChoice=="steer"&&findSelected()?.protocol?.let(WgAddonPolicy::isWireGuard)==true
                    val dispatcherChoice=validationChoice=="steer"&&!packetChoice&&RouteServerPlan.activeIds(routing.snapshot()).isNotEmpty()
                    var steerSettings:String?=null
                    if(packetChoice) {
                        if(RouteServerPlan.activeIds(routing.snapshot()).isNotEmpty())throw ClientOptionalCoreRequired("sing-box")
                        require(prefs.getBoolean("tun",true)) {"Компактный WG/AWG работает только в режиме VPN."}
                        val entry=requireNotNull(findSelected())
                        ClientPacketSelection.validateConfiguration(this@MainActivity,entry.original,ClientPacketSelection.validationDial(entry.host))
                    }else if(dispatcherChoice){
                        require(prefs.getBoolean("tun",true)){"Steer с отдельными серверами правил требует режим VPN."}
                        val entry=findSelected()?:throw IllegalArgumentException("Выбери основной сервер Steer.")
                        NativeRoutePreparation.capture(this@MainActivity,entry,routing.snapshot(),catalogue.entries()).validate(this@MainActivity,true,
                            prefs.getInt("port",10808),java.util.function.BooleanSupplier {isDestroyed||isFinishing})
                    }else if(validationChoice=="steer") {
                        val result=ClientPrimarySelection.steerSnapshot(this@MainActivity,routing.snapshot(),prefs.getBoolean("tun",true),true)
                        require(result.supported) {ClientPrimarySelection.steerFailure(result)}
                        steerSettings=result.settingsJson
                    }
                    if(savedConfig.isNotBlank()&&!packetChoice&&!dispatcherChoice) {
                        val savedChoice=ClientPrimarySelection.selected(this@MainActivity)
                        val session=ClientPrimaryWorker.connect(this@MainActivity,validationChoice,System.nanoTime().coerceAtLeast(1),null)
                        try {
                            val vpn=prefs.getBoolean("tun",true)
                            val config=if(validationChoice=="singbox")ClientPrimarySelection.prepared(this@MainActivity,savedConfig,vpn,prefs.getInt("port",10808))else null
                            val profile=if(validationChoice=="steer")findSelected()?.original?:throw IllegalArgumentException("Выбери сервер с совместимой ссылкой для Steer.")else""
                            val settings=if(validationChoice=="singbox")ClientPrimarySelection.envelope(config!!,vpn,ClientBehaviorStore.load(this@MainActivity).metered)
                                else requireNotNull(steerSettings){"Снимок настроек Steer не подготовлен."}
                            val validation=session.validate(profile,settings,if(vpn)org.mcrf.engine.spi.v1.Engine.Mode.VPN else org.mcrf.engine.spi.v1.Engine.Mode.PROXY)
                            require(validation.supported) {"Профиль не поддерживается выбранным движком (${validation.code})."}
                            check(ClientPrimarySelection.selected(this@MainActivity)==savedChoice) {"Выбор изменился во время проверки. Повтори действие."}
                        }finally{session.close()}
                    }
                    require(!active()&&ProbeCoordinator.measurements==null){"Соединение ещё останавливается. Выбранный движок не изменён."}
                    ClientPrimarySelection.select(this@MainActivity,choiceId)
                    ui {
                        busy=false
                        val resume=pendingPrimaryConnection;pendingPrimaryConnection=null
                        primaryChooser?.dismiss();primaryChooser=null;render()
                        toast("Основной движок: ${if(choiceId=="singbox")"sing-box"else"Steer"}")
                        if(resume!=null)prepareConnection(resume)
                        else continueStartupChoices()
                    }
                }catch(e:ClientPrimarySelection.DnsModelConsentRequired){ui {
                    busy=false
                    if(!isFinishing&&!isDestroyed)ThemedDialogBuilder(this@MainActivity)
                        .setTitle("Модель DNS Steer")
                        .setMessage("Steer будет выдавать внутренние адреса для DNS-ответов и применять к ним правила. IPv6 обрабатывается только если включён и поддержан установленным модулем. Зашифрованный DNS самих приложений не перехватывается. Это отдельное согласие, прежний выбор IPv4 его не заменяет. Исходные настройки не изменяются.")
                        .setNegativeButton("Отмена",null)
                        .setPositiveButton("Подтвердить") {_,_->
                            if(!working()) {
                                busy=true
                                worker.execute {
                                    try {
                                        ClientPrimarySelection.acceptSteerDnsModel(this@MainActivity)
                                        ui {busy=false;if(!isFinishing&&!isDestroyed)primaryHost.selectValidated(choiceId)}
                                    }catch(failure:Exception){ui {busy=false;alert("Основной движок",failure.message?:"Не удалось сохранить согласие.")}}
                                }
                            }
                        }.show()
                }}catch(e:Exception){ui {busy=false;alert("Основной движок",e.message?:"Не удалось проверить движок.")}}
            }
        }
    }
    private fun offerPrimaryChoice(force:Boolean=false) {
        if(loading||isFinishing||isDestroyed||primaryChooser?.isShowing==true||(!force&&ClientPrimarySelection.selected(this)!=null))return
        primaryChooser=ThemedDialogBuilder(this).setTitle("Через что отправлять основной трафик?")
            .setView(ScrollView(this).apply {
                tag="primary-choice-scroll"
                setPadding(dp(24),dp(8),dp(24),dp(8))
                isVerticalScrollBarEnabled=false
                addView(EngineUpdatePrimaryUi.view(this@MainActivity,primaryHost,firstLaunch=true))
            })
            .setNegativeButton("Позже",null).show().also {dialog->dialog.setOnDismissListener {primaryChooser=null;pendingPrimaryConnection=null}}
    }
    private fun startupChoices() {
        val fresh=savedConfig.isBlank()&&catalogue.entries().isEmpty()&&ClientPrimarySelection.selected(this)==null
        val testing=BuildConfig.DEBUG&&intent.getBooleanExtra("test_no_prompts",false)
        if(!testing&&UsageFeatures.needsFirstChoice(prefs.getString(UsageFeatures.KEY,null),prefs.getBoolean(UsageFeatures.ASKED,false),fresh)) {
            onboardingVisible=true
            usageChooser=UsageFeaturesUi.firstLaunch(this){usageChooser=null;onboardingVisible=false;continueStartupChoices()}
        }else continueStartupChoices()
    }
    private fun continueStartupChoices() {
        if(isFinishing||isDestroyed)return
        if(ClientPrimarySelection.selected(this)==null)offerPrimaryChoice()
        else {batteryPrompt();AppUpdates.onStart(this);resumeWantedOnLaunch();checkStartupAccess()}
    }
    private fun checkStartupAccess(){
        if(startupAccessStarted||BuildConfig.DEBUG&&intent.getBooleanExtra("test_no_prompts",false))return
        startupAccessStarted=true
        val revision=navigationRevision
        startupAccessWorker.execute {
            val result=try{DirectAccessCheck.check(applicationContext)}catch(_:Exception){null}
            runOnUiThread {if(!isFinishing&&!isDestroyed&&result!=null)startupAccessPending=revision to result}
        }
    }
    private fun offerStartupAccess(){
        val pending=startupAccessPending?:return
        if(onboardingVisible||working())return
        startupAccessPending=null
        if(pending.first!=navigationRevision||current !in setOf("Главная",ClientSettingsNavigation.WHITELISTS))return
        if(pending.second.google==DirectAccessCheck.Outcome.CANCELLED||pending.second.gitlab==DirectAccessCheck.Outcome.CANCELLED)return
        if(!pending.second.anyInternetResponse&&(pending.second.google==DirectAccessCheck.Outcome.RESTRICTED||pending.second.gitlab==DirectAccessCheck.Outcome.RESTRICTED)){
            alert("Проверка доступа","Android не разрешил проверку напрямую. Это не подтверждает белые списки; настройки VPN и системные ограничения не изменены.")
            return
        }
        if(pending.second.anyInternetResponse){
            if(UsageFeatures.visible("Главная",UsageFeaturesUi.selected(this)))navigate("Главная")
        }else {
            if(UsageFeatures.whitelists(UsageFeaturesUi.selected(this)))navigate(ClientSettingsNavigation.WHITELISTS)
            alert("Возможны белые списки","Google и GitLab не ответили напрямую. Возможно, сейчас работают белые списки; также проверь наличие сети. Текущее подключение не изменено.")
        }
    }
    private val scrollPositions = mutableMapOf<String, Int>()
    private data class ScreenState(val page: String, val positions: Map<String, Int>, val pendingImport: EngineImportUi.State?, val pendingLink: String?, val settingsOrigin: String,val settingsDirectEntry:Boolean,val connectionPage:String,val advancedOrigin:String)
    private companion object {var hiddenScreenState:ScreenState?=null}
    private val refresh = object : Runnable {
        override fun run() {
            offerStartupAccess()
            if(observedBsSwitch!=McrfVpnService.autoBsSwitchSerial){
                observedBsSwitch=McrfVpnService.autoBsSwitchSerial
                if(current in setOf("Главная",ClientSettingsNavigation.WHITELISTS)&&UsageFeatures.whitelists(UsageFeaturesUi.selected(this@MainActivity)))navigate(ClientSettingsNavigation.WHITELISTS)
                toast("Похоже, работают белые списки. Подключение переключено.")
            }
            if(observedVpnSwitch!=McrfVpnService.autoVpnSwitchSerial){
                observedVpnSwitch=McrfVpnService.autoVpnSwitchSerial
                if(current in setOf("Главная",ClientSettingsNavigation.WHITELISTS)&&UsageFeatures.visible("Главная",UsageFeaturesUi.selected(this@MainActivity)))navigate("Главная")
                toast("Доступ восстановлен. Подключено сохранённое VPN-подключение.")
            }
            status?.apply { text = McrfVpnService.status; setTextColor(if (McrfVpnService.status.startsWith("Ошибка")) Loft.ERROR else Loft.INK) }
            updateHomeControl()
            pageRefresh?.invoke()
            drainRulesTile()
            if(current in setOf("Главная",ClientSettingsNavigation.WHITELISTS)&&!working()&&!catalogueRefreshInFlight&&SystemClock.elapsedRealtime()-lastCatalogueRefresh>=3000) {
                catalogueRefreshInFlight=true;lastCatalogueRefresh=SystemClock.elapsedRealtime()
                worker.execute {
                    val changed=try {catalogue.refreshPresentationFromStorage()}catch(_:Exception){false}
                    ui {catalogueRefreshInFlight=false;if(changed&&current in setOf("Главная",ClientSettingsNavigation.WHITELISTS)&&!working())render()}
                }
            }
            openPendingLink()
            if(engineImport.state==null&&!onboardingVisible){AppUpdates.offerPending(this@MainActivity);EngineStartupUpdates.offerPending(this@MainActivity)}
            // Native automatic deadlines can expire while this prompt is visible.
            // Retire that window so it cannot starve later live challenges.
            carrierChallengePromptId?.let {id->if(CarrierChallenge.get(id)==null)carrierChallengeDialog?.dismiss()}
            if(carrierChallengeDialog?.isShowing!=true&&!onboardingVisible)CarrierChallenge.unopened()?.let { request ->
                request.opened=true
                carrierChallengePromptId=request.id
                carrierChallengeDialog=ThemedDialogBuilder(this@MainActivity).setTitle("Подтверждение VK").setMessage("Для подключения VK просит капчу. Открыть страницу проверки?")
                    .setNegativeButton("Отмена") { _, _ -> if(!CarrierChallenge.respondAsync(request.id,"error:cancelled"))request.opened=false }
                    .setPositiveButton("Открыть") { _, _ -> startActivity(Intent(this@MainActivity,CarrierCaptchaActivity::class.java).putExtra("request",request.id)) }
                    .setOnCancelListener { if(!CarrierChallenge.respondAsync(request.id,"error:cancelled"))request.opened=false }.show().also {dialog->
                        dialog.setOnDismissListener {if(carrierChallengePromptId==request.id){carrierChallengeDialog=null;carrierChallengePromptId=null}}
                    }
            }
            handler.postDelayed(this, 750)
        }
    }
    override fun onCreate(state: Bundle?) {
        ClientSettingsRegistry.register()
        val restoreProblem = try { AndroidBackup.applyRestoredPreferences(this); false } catch (_: Exception) { true }
        UserThemeColorsUi.configure(this)
        val theme = prefs.getString("theme", "system")
        val dark = theme == "dark" || theme == "system" && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        Loft.apply(dark); setTheme(if (dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(state); catalogue = Connections(this); routing = RoutingStore(this)
        engineImport = EngineImportUi(this)
        telegram=TelegramBridge(this){telegramControls?.update();miniature?.updateTelegram()}
        receiveConfigLink(intent)
        pendingBackupPath = state?.getString("backupPath")
        selected = prefs.getString("selected", "").orEmpty(); automatic = prefs.getBoolean("automatic", false)
        // The old standalone toggle no longer exists. Preserve a currently live
        // legacy session, but never carry its hidden flag into a new connection.
        if(automatic&&!active()){automatic=false;prefs.edit().putBoolean("automatic",false).apply()}
        if (state != null) { current = state.getString("page", current); scrollPositions[current] = state.getInt("scroll", 0); settingsOrigin = state.getString("settingsOrigin", "Главная");returnToDeployVk=state.getBoolean("returnToDeployVk");selectedDeployRecipe=state.getString("selectedDeployRecipe") }
        val retained = (lastNonConfigurationInstance as? ScreenState)?:hiddenScreenState
        hiddenScreenState=null
        lastConnectionPage=retained?.connectionPage?:state?.getString("connectionPage")?:"Главная"
        settingsDirectEntry=state?.getBoolean("settingsDirectEntry")?:false
        retained?.let {settingsDirectEntry=it.settingsDirectEntry}
        advancedOrigin=retained?.advancedOrigin?:state?.getString("advancedOrigin")?:ClientSettingsNavigation.ROOT
        retained?.let { current = it.page; scrollPositions.putAll(it.positions); pendingConfigLink = it.pendingLink ?: pendingConfigLink; settingsOrigin = it.settingsOrigin }
        current = when (current) { "Серверы" -> "Главная"; "Журнал" -> "Логи";else->ClientSettingsNavigation.canonical(current) }
        shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Loft.Backdrop(); isFocusableInTouchMode = true; clipToPadding = false }
        SystemBarsUi.apply(this)
        shell.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) { val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); val keyboard = insets.getInsets(WindowInsets.Type.ime()); view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, keyboard.bottom)) }
            else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(shell); render(); shell.requestApplyInsets();ProbeCoordinator.observe(probeRefresh)
        worker.execute {
            try {
                if (restoreProblem) throw java.io.IOException("Pending restore preferences could not be applied")
                catalogue.load();recoverWarpSelection(); routing.load();RemoteRoutingSchedule.sync(this,routing.snapshot()); var saved = ProfileStore(this).read()
                LocalProxySettings.value(this)
                SubscriptionUpdates.initialize(this, prefs)
                if(!(BuildConfig.DEBUG&&intent.getBooleanExtra("test_direct",false)))SubscriptionOpenRefresh.onOpen(this,catalogue.subscriptions().isNotEmpty())
                EngineUpdateSchedule.schedule(this);EngineUpdates.recover(this)
                val direct = BuildConfig.DEBUG && saved.isEmpty() && intent.getBooleanExtra("test_direct", false)
                if (direct) saved = assets.open("direct.json").use { String(ImportIO.read(it), Charsets.UTF_8) }
                savedConfig = saved
                ui { loading = false; val requested=deferredNavigation.take(); if(requested!=null&&!direct)navigate(requested)else render(); retained?.pendingImport?.let(engineImport::attach); if (direct) { openDiagnosticPage(); tun?.isChecked = true; prepareConnection(true) } else if(!handleQuickTile()){startupChoices();EngineStartupUpdates.onOpen(this)} }
            } catch (_: Exception) { ui { loading = false; deferredNavigation.take(); render(); alert("Подключения", "Не удалось открыть сохранённые подключения. Исходные файлы не изменены.") } }
        }
    }
    fun render() {
        SystemBarsUi.apply(this)
        val shownFeatures=UsageFeaturesUi.selected(this)
        val mergedConnections=prefs.getBoolean(ConnectionAppearance.MERGED,false)
        if(!UsageFeatures.visible(current,shownFeatures))current=when {
            UsageFeatures.visible("Главная",shownFeatures)->"Главная"
            UsageFeatures.whitelists(shownFeatures)->ClientSettingsNavigation.WHITELISTS
            UsageFeatures.Feature.WARP in shownFeatures->"WARP"
            else->"Настройки"
        }
        deployUi?.detach();deployUi=null
        if(ConnectionAppearance.isConnection(current))lastConnectionPage=current
        pendingSettingsSave?.let { handler.removeCallbacks(it); it.run() }; pendingSettingsSave = null
        shell.background = Loft.rootBackground(this)
        val animatePage = renderedPage != null && renderedPage != current
        renderedPage?.let { name -> activeScroll?.let { scrollPositions[name] = it.scrollY } }
        renderedPage = current
        renderedLoading=loading
        shell.removeAllViews(); source = null; port = null; tun = null; connect = null; status = null; connectionHint = null;connectionTarget=null
        miniature?.animate()?.cancel(); miniature = null; miniatureShown = false; serversPanel = null
        selectedServer = null; homeLayout = null; modeSwitch = null; modeHint = null
        telegramControls=null
        pageRefresh=null;autoControlsRefresh=null;captchaControlsRefresh=null
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        brand.addView(ImageView(this).apply { setImageResource(R.drawable.mcrf_logo) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        val brandTitle=if(mergedConnections)ConnectionAppearanceUi.header(this,current){navigate(it)} else text("MCRF",18,Loft.INK).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);letterSpacing=.06f;setPadding(dp(9),0,0,0)}
        brand.addView(brandTitle,LinearLayout.LayoutParams(0,-2,1f))
        brand.addView(iconButton(if(Loft.dark)"sun"else"moon",if(Loft.dark)"Светлая тема"else"Тёмная тема").apply {tag="theme-action";setOnClickListener {if(!working())switchAppearance()}},LinearLayout.LayoutParams(dp(48),dp(48)))
        brand.addView(iconButton("gear", "Настройки").apply { tag="settings-action"; isSelected=ClientSettingsNavigation.isSettings(current); setOnClickListener { navigate("Настройки") } }, LinearLayout.LayoutParams(dp(48),dp(48)))
        shell.addView(brand)
        val scroll = object : ScrollView(this) { override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = min(MeasureSpec.getSize(widthSpec), dp(720))
            homeLayout?.fit(width-page.paddingLeft-page.paddingRight, MeasureSpec.getSize(heightSpec)-page.paddingTop-page.paddingBottom)
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec)
        } }
        activeScroll = scroll; scroll.isFillViewport = true; scroll.clipToPadding = false; scroll.isVerticalScrollBarEnabled = false; scroll.overScrollMode = View.OVER_SCROLL_NEVER
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag="screen-$current"; val connectionPage=current in setOf("Главная",ClientSettingsNavigation.WHITELISTS);setPadding(dp(if(connectionPage)16 else 24),dp(if(current=="Главная")6 else 14),dp(if(connectionPage)16 else 24),dp(if(current=="Главная")6 else 24));isFocusableInTouchMode=true };scroll.addView(page)
        viewport = FrameLayout(this).apply { clipToPadding=false; addView(scroll, FrameLayout.LayoutParams(-1,-1).apply {gravity=Gravity.CENTER_HORIZONTAL}) }
        shell.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        if(current=="Логи") {scroll.removeView(page);viewport.removeView(scroll);viewport.addView(page,FrameLayout.LayoutParams(-1,-1));activeScroll=null}
        val heading = when (current) { "Диагностика" -> "Конфигурация"; "Главная" -> "Подключение"; "Маршрутизация" -> "Маршруты"; else -> ClientSettingsNavigation.tabTitle(current) }
        if (!ConnectionAppearance.isConnection(current)) {
            if (ClientSettingsNavigation.isSettings(current)&&current!="Автодеплой") page.addView(button("← ${if(current==ClientSettingsNavigation.ROOT) "Назад" else ClientSettingsNavigation.tabTitle(ClientSettingsNavigation.back(current,settingsOrigin,settingsDirectEntry,advancedOrigin))}",false).apply {
                tag="settings-back";contentDescription="Вернуться: ${ClientSettingsNavigation.tabTitle(ClientSettingsNavigation.back(current,settingsOrigin,settingsDirectEntry,advancedOrigin))}"
                setOnClickListener {onBackPressed()}
            },space(0,12))
            val title=text(heading,26,Loft.INK).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setAccessibilityHeading(true)}
            if(current=="Автодеплой")page.addView(LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL
                addView(iconButton("back","Назад").apply {tag="androidDeploy.back";setOnClickListener {onBackPressed()}},LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginEnd=dp(8)})
                addView(title,LinearLayout.LayoutParams(0,-2,1f))
            },space(0,20))else if(current=="Логи")page.addView(LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL
                addView(title,LinearLayout.LayoutParams(0,-2,1f))
                addView(LogRetentionUi.button(this@MainActivity),LinearLayout.LayoutParams(-2,dp(48)).apply {rightMargin=dp(6)})
                addView(button(if(prefs.getBoolean("logsTerminal",false))"Таблица"else"Терминал",false).apply {
                    tag="logs-view-toggle";contentDescription="Переключить оформление логов"
                    setOnClickListener {prefs.edit().putBoolean("logsTerminal",!prefs.getBoolean("logsTerminal",false)).apply();render()}
                },LinearLayout.LayoutParams(-2,dp(48)))
            },space(0,20))else page.addView(title,space(0,20))
        }
        // Until the asynchronous catalogue read completes, an empty in-memory
        // list is not an empty saved catalogue (nor a subscription read error).
        if (loading) {
            page.addView(ProgressBar(this).apply { tag="catalogue-loading-progress" },space(16,12))
            page.addView(text("Загружаем сохранённые подключения…",14,Loft.MUTED).apply {
                tag="catalogue-loading-message";gravity=Gravity.CENTER
                accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            },space(0,16))
        } else when (current) { "Маршрутизация" -> RoutingPage(this, routing){navigate("WARP")}.build(page); "WARP" -> page.addView(warp().build(this),space(0,16)); ClientSettingsNavigation.WHITELISTS -> whiteLists(); "Настройки" -> settings(); "Логи" -> journal(); "Диагностика" -> diagnostics(); in ClientSettingsNavigation.children -> settingsPage(current); else -> home() }
        if(!loading)miniature=EdgeConnectionControls(this,telegram,::toggleEdgeConnection).apply {
            viewport.addView(this,FrameLayout.LayoutParams(dp(64),-2,Gravity.CENTER_VERTICAL or Gravity.END))
        }
        updateHomeControl()
        shell.addView(View(this).apply { setBackgroundColor(Loft.LINE) }, LinearLayout.LayoutParams(-1, dp(1)))
        val compactNavigation=resources.configuration.screenWidthDp<=320
        val nav = LinearLayout(this).apply { tag="main-navigation";isBaselineAligned=false;gravity=Gravity.CENTER_VERTICAL;background = android.graphics.drawable.ColorDrawable(Loft.SURFACE); setPadding(dp(if(compactNavigation)4 else 12), dp(6), dp(if(compactNavigation)4 else 12), dp(6)) }
        val homeVisible=UsageFeatures.visible("Главная",shownFeatures)
        val bsVisible=UsageFeatures.whitelists(shownFeatures)
        for (tab in ConnectionAppearance.tabs(mergedConnections,homeVisible,bsVisible)) {
            val joined=tab==ConnectionAppearance.TAB
            val chosen = if(joined)ConnectionAppearance.isConnection(current) else current==tab
            val item = button(if(joined)ConnectionAppearance.bottomLabel(lastConnectionPage,homeVisible,bsVisible) else ClientSettingsNavigation.tabTitle(tab), false).apply {
                tag="nav-$tab";contentDescription = ClientSettingsNavigation.tabTitle(tab); isSelected = chosen; setTextColor(if (chosen) Loft.ACCENT else Loft.MUTED)
                background = RippleDrawable(ColorStateList.valueOf(Loft.ripple()), flat(if (chosen) Loft.selection() else Color.TRANSPARENT, 18, 0), flat(Color.WHITE, 18, 0))
                setPadding(dp(if(compactNavigation)1 else 2), dp(6), dp(if(compactNavigation)1 else 2), dp(6)); textSize = 11f; compoundDrawablePadding = dp(4);gravity=Gravity.CENTER
                setSingleLine(true);setHorizontallyScrolling(false);ellipsize=TextUtils.TruncateAt.END;includeFontPadding=false;minHeight=0;minimumHeight=0
                setAutoSizeTextTypeUniformWithConfiguration(8,11,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                setCompoundDrawablesWithIntrinsicBounds(null, icon(when (tab) {ConnectionAppearance.TAB->"rkn"; "Главная" -> "home"; "Маршрутизация" -> "routes"; ClientSettingsNavigation.WHITELISTS -> "shield"; else -> "logs" }, if (chosen) Loft.ACCENT else Loft.MUTED), null, null)
                if(joined)contentDescription="Подключения · $text"+if(homeVisible&&bsVisible&&chosen)". Нажми для переключения VPN / БС"else""
                setOnClickListener {if(joined)ConnectionAppearance.bottomTarget(current,lastConnectionPage,homeVisible,bsVisible)?.let {navigate(it)}else navigate(tab)}
            }
            nav.addView(item, LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(if(compactNavigation)1 else 2), 0, dp(if(compactNavigation)1 else 2), 0) })
        }
        shell.addView(nav); page.requestFocus(); val pageName = current
        if (animatePage && android.animation.ValueAnimator.areAnimatorsEnabled()) { page.alpha = 0f; page.animate().alpha(1f).setDuration(160).start() }
        val anchor=pendingSettingsAnchor.takeIf {pageName==ClientSettingsNavigation.ADVANCED||pageName==ClientSettingsNavigation.VPN}
        val searchQuery=pendingSettingsQuery.takeIf {anchor!=null}
        if(anchor!=null)pendingSettingsAnchor=null
        if(anchor!=null)pendingSettingsQuery=null
        // Restore position before the new hierarchy's FIRST visible frame.
        // A posted scroll briefly painted the top after Apply and other saves.
        scroll.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                if(activeScroll!==scroll)return true
            val anchored=anchor?.let {page.findViewWithTag<View>(it)}
            val target=if(anchored!=null&&!searchQuery.isNullOrBlank())findSettingsSearchTarget(anchored,searchQuery)?:anchored else anchored
            if(target!=null) {
                val rect=android.graphics.Rect();target.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(target,rect)
                scroll.scrollTo(0,(rect.top-dp(12)).coerceAtLeast(0))
                target.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
            } else {scroll.scrollTo(0,scrollPositions[pageName]?:0);if(anchor!=null)toast("Этот параметр скрыт выбранным режимом приложения")}
            updateMiniature(false)
                return true
            }
        })
    }
    private fun switchAppearance() {
        val dark = !Loft.dark
        activeScroll?.let { scrollPositions[current] = it.scrollY }
        prefs.edit().putString("theme", if (dark) "dark" else "light").apply()
        // Keep the loaded catalogue, current page and pending UI state. Recreating
        // the Activity exposed an empty catalogue before its asynchronous reload.
        Loft.apply(dark)
        setTheme(if (dark) R.style.AppThemeDark else R.style.AppTheme)
        window.setBackgroundDrawable(Loft.rootBackground(this))
        SystemBarsUi.apply(this)
        render()
        // Restore before drawing, rather than showing one frame at scroll zero.
        activeScroll?.let { scroll ->
            val position = scrollPositions[current] ?: 0
            scroll.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    scroll.viewTreeObserver.removeOnPreDrawListener(this)
                    scroll.scrollTo(0, position)
                    return true
                }
            })
        }
    }
    fun applyUserThemeColors() {
        UserThemeColorsUi.configure(this)
        Loft.apply(Loft.dark)
        window.setBackgroundDrawable(Loft.rootBackground(this))
        routingScreenCache.clear()
        render()
    }
    private var pendingSettingsAnchor:String?=null
    private var pendingSettingsQuery:String?=null
    fun openAdvanced(anchor:String,query:String?=null) {pendingSettingsAnchor=anchor;pendingSettingsQuery=query;navigate(ClientSettingsNavigation.ADVANCED)}
    private fun findSettingsSearchTarget(root:View,query:String):View? {
        val candidates=mutableListOf<TextView>()
        fun visit(view:View){
            if(view.visibility!=View.VISIBLE)return
            if(view is TextView&&view !is EditText&&SettingsSearchIndex.hitCount(query,view.text.toString())>0)candidates.add(view)
            if(view is ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i))
        }
        visit(root)
        return candidates.sortedWith(compareByDescending<TextView>{SettingsSearchIndex.hitCount(query,it.text.toString())}.thenBy {it.text.length}).firstOrNull()
    }
    fun navigate(name: String) {
        // Re-selecting an already visible tab is not a refresh. Keep the same
        // controls, focus and scroll position (also after alias normalization).
        if(name=="VPN"){navigate("Главная");return}
        if(name in setOf(ClientSettingsNavigation.CAPTCHA,ClientSettingsNavigation.CONNECTIONS)){navigate(ConnectionAppearance.section(settingsOrigin,UsageFeatures.visible("Главная",UsageFeaturesUi.selected(this)),UsageFeatures.whitelists(UsageFeaturesUi.selected(this)))?:"Главная");return}
        if(name==ClientSettingsNavigation.TLS){openAdvanced("advanced-tls");return}
        if(name==ClientSettingsNavigation.XRAY){openAdvanced("advanced-xray");return}
        if(name==ClientSettingsNavigation.SHARING){pendingSettingsAnchor="lan-sharing-page";navigate(ClientSettingsNavigation.VPN);return}
        val canonical=ClientSettingsNavigation.canonical(name)
        if(canonical!=name){navigate(canonical);return}
        val target=if(name=="Журнал")"Логи"else name
        if(!loading&&!renderedLoading&&target==current&&renderedPage==current&&pendingSettingsAnchor==null)return
        navigationRevision++
        if (loading) {deferredNavigation.capture(name);return}
        if(!UsageFeatures.visible(name,UsageFeaturesUi.selected(this))){current="Главная";render();return}
        activeScroll?.let { scrollPositions[current] = it.scrollY }
        if(name==ClientSettingsNavigation.ADVANCED&&current!=ClientSettingsNavigation.ADVANCED)advancedOrigin=current
        if(ClientSettingsNavigation.isSettings(name)&&!ClientSettingsNavigation.isSettings(current)){
            settingsOrigin=current;settingsDirectEntry=name!=ClientSettingsNavigation.ROOT
        }
        current=when(name){"Серверы"->"Главная";"Журнал"->"Логи";else->name};render()
        if(name=="Серверы")activeScroll?.post { serversPanel?.let { activeScroll?.smoothScrollTo(0,it.top) } }
    }
    fun openDeployVkSettings() {
        returnToDeployVk=true
        settingsOrigin="Автодеплой"
        navigate(ClientSettingsNavigation.VK)
    }
    @Deprecated("Legacy back dispatch for the Activity-based UI")
    override fun onBackPressed() {
          if(current=="Автодеплой"&&deployUi?.back()==true)return
          else if(current==ClientSettingsNavigation.VK&&returnToDeployVk){returnToDeployVk=false;navigate("Автодеплой")}
          else if(ClientSettingsNavigation.isSettings(current))navigate(ClientSettingsNavigation.back(current,settingsOrigin,settingsDirectEntry,advancedOrigin))
        else if(current=="Диагностика")navigate(settingsOrigin)
        else if(current!="Главная")navigate("Главная")
        else if((activeScroll?.scrollY?:0)>0)activeScroll?.smoothScrollTo(0,0)
        else super.onBackPressed()
    }
    private fun connectionButton(action:()->Unit):View {
        val style=ConnectionAppearanceUi.style(this)
        return if(style==ConnectionAppearance.CLASSIC)RoundConnectionButton(this,false,action)
        else ForgeConnectionButton(this,connectionMotion,style,ConnectionAppearance.exit(prefs.getString(ConnectionAppearance.EXIT,null)),action).apply {resumeAnimation(animationResumed)}
    }
    private fun home() {
        val control=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;tag="home-connection-control";background=Loft.connectionSurface(this@MainActivity);setPadding(dp(12),dp(12),dp(12),dp(8))}
        connect=connectionButton {toggleConnection()}
        val custom=connect is ForgeConnectionButton
        val controls=LinearLayout(this).apply {gravity=Gravity.CENTER;orientation=LinearLayout.VERTICAL}
        controls.addView(connect,LinearLayout.LayoutParams(if(custom)-1 else dp(104),dp(if(custom)224 else 104)))
        controls.addView(autoBsChoice(),LinearLayout.LayoutParams(-1,-2))
        control.addView(controls,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(10)})
        status=text("Отключено",15,Loft.INK).apply {gravity=Gravity.CENTER;tag="home-connection-status";setPadding(paddingLeft,paddingTop,paddingRight,paddingBottom+dp(3))}
        connectionHint=text("",12,Loft.MUTED).apply {
            gravity=Gravity.CENTER;maxLines=3;ellipsize=TextUtils.TruncateAt.END;tag="home-connection-hint"
            setPadding(dp(12),dp(8),dp(12),dp(12));minimumHeight=dp(48)
            setOnClickListener {
                if(ConnectionPresentation.failed(McrfVpnService.status))navigate("Логи")
            }
        }
        control.addView(status);control.addView(connectionTargetView());control.addView(connectionHint)
        page.addView(control,space(0,16))
        tun = Switch(this).apply { isChecked = if(active())prefs.getBoolean("tun",true)else UsageFeatures.preferredVpn(UsageFeaturesUi.selected(this@MainActivity),prefs.getBoolean("tun",true)) }
        modeSwitch = ModeSwitch(this, tun!!.isChecked, java.util.function.Consumer { changeMode(it) })
        modeSwitch?.tag="home-mode-switch"
        modeSwitch?.visibility=if(UsageFeatures.showModeSwitch(UsageFeaturesUi.selected(this)))View.VISIBLE else View.GONE
        modeHint = text("", 11, Loft.MUTED).apply { maxLines = 2; gravity = Gravity.CENTER }
        page.addView(modeSwitch,space(0,12))
        serversPanel = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; tag="home-servers"
            addView(LinearLayout(this@MainActivity).apply {gravity=Gravity.CENTER_VERTICAL
                addView(text("Серверы",21,Loft.INK).apply {typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)},LinearLayout.LayoutParams(0,-2,1f))
                if(UsageFeatures.telegram(UsageFeaturesUi.selected(this@MainActivity),telegram.state.wanted,telegram.state.running))addView(iconButton("telegram","Открыть в Telegram",18).apply {tag="telegram-open";setOnClickListener {try{telegram.openTelegram()}catch(_:ActivityNotFoundException){alert("Telegram","На телефоне не найден Telegram.")}}},LinearLayout.LayoutParams(dp(48),dp(48)))
                if(UsageFeatures.Feature.WARP in UsageFeaturesUi.selected(this@MainActivity))addView(iconButton("cloudflare","WARP · добавить сервер",22).apply {tag="warp-open";setOnClickListener {navigate("WARP")}},LinearLayout.LayoutParams(dp(48),dp(48)))
            },space(4,4))
            selectedServer=text(findSelected()?.takeIf {ConnectionSections.belongs(it,false)}?.name?:"VPN-сервер не выбран",11,Loft.MUTED).apply {tag="selected-server";setSingleLine(true);ellipsize=TextUtils.TruncateAt.END}
            addView(selectedServer,space(0,8))
        }.also { page.addView(it,space(0,72)); ServersPage(this).build(it) }
        updateHomeControl()
    }
    private fun captchaChoice(entry:ServerImport.Server?):LinearLayout {
        fun supported():ServerImport.Server? {
            val entries=catalogue.entries()
            val candidates=listOfNotNull(findSelected(),entries.firstOrNull {it.id==BsAutoSelectionPolicy.last(prefs,true)},entries.firstOrNull {it.id==entry?.id})
            return candidates.firstOrNull {it.protocol in SwgProfile.TYPES&&it.carrier!=null}
        }
        lateinit var choice:ModeSwitch
        choice=ModeSwitch(this,CarrierCaptchaMode.automatic(supported()?.carrier?.optString("captcha_mode","auto")?:"auto"),java.util.function.Consumer {automatic->
            val target=supported()
            if(target!=null&&!working())run(Operation {
                catalogue.setCaptchaMode(target.id,automatic)
                "Режим капчи сохранён. Применится при следующем подключении."
            },false,Runnable {captchaControlsRefresh?.invoke()})
        },listOf("Авто","Вручную"),listOf("",""),true)
        choice.tag="captcha-mode-switch";choice.contentDescription="Капча выбранного сервера"
        captchaControlsRefresh={
            choice.setMode(CarrierCaptchaMode.automatic(supported()?.carrier?.optString("captcha_mode","auto")?:"auto"),true)
            choice.setAvailable(supported()!=null&&!working())
        }
        captchaControlsRefresh?.invoke()
        return connectionOptionRow("Капча","home-captcha-settings",choice)
    }
    private fun connectionOptionRow(label:String,rowTag:String,choice:View)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;tag=rowTag;minimumHeight=dp(48)
        addView(text(label,11,Loft.MUTED).apply {
            gravity=Gravity.CENTER_VERTICAL;setSingleLine(true);setHorizontallyScrolling(false);includeFontPadding=false
            setAutoSizeTextTypeUniformWithConfiguration(9,11,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        },LinearLayout.LayoutParams(0,dp(48),1.15f).apply {rightMargin=dp(8)})
        addView(choice,LinearLayout.LayoutParams(0,dp(48),1f))
    }
    private fun autoBsChoice():LinearLayout {
        lateinit var choice:ModeSwitch
        choice=ModeSwitch(this,prefs.getBoolean(BsDetectionMonitor.KEY,false),java.util.function.Consumer {enabled->
            setBsDetection(enabled)
            choice.setMode(prefs.getBoolean(BsDetectionMonitor.KEY,false),true)
        },listOf("Вкл","Выкл"),listOf("",""),true)
        choice.tag="auto-bs-switch";choice.contentDescription="Автоопределение белых списков"
        choice.setAvailable(!working())
        autoControlsRefresh={choice.setMode(prefs.getBoolean(BsDetectionMonitor.KEY,false),false);choice.setAvailable(!working())}
        fun intervalButton(kind:AutoCheckIntervals.Kind)=Button(this).apply {
            tag=if(kind==AutoCheckIntervals.Kind.DETECTION)"auto-bs-interval"else"auto-selection-interval"
            DropdownStyle.value(this@MainActivity,this);textSize=13f
            isAllCaps=false;includeFontPadding=false;gravity=Gravity.CENTER;setSingleLine(true)
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setTextColor(Loft.MUTED)
            setPadding(dp(5),0,dp(5),0);stateListAnimator=null;elevation=0f
            backgroundTintList=null
            fun value()=AutoCheckIntervals.seconds(kind,prefs::getInt)
            fun refresh(){text=AutoCheckIntervals.label(value());contentDescription="${kind.title}: $text"}
            refresh()
            setOnClickListener {anchor->
                val values=BsDetectionInterval.seconds
                DropdownPopup.show(this@MainActivity,anchor,kind.title,values.map {DropdownPopup.Item(AutoCheckIntervals.label(it))},values.indexOf(value()),startAtTop=true){position->
                    val change=AutoCheckIntervals.change(kind,values[position])
                    prefs.edit().putInt(change.first,change.second).apply();refresh()
                }
            }
        }
        val control=LinearLayout(this).apply {
            gravity=Gravity.CENTER;isBaselineAligned=false;tag="auto-selection-control"
            addView(button("i",false).apply {
                tag="auto-bs-selection-help";DropdownStyle.apply(this@MainActivity,this);contentDescription="Справка об автовыборе"
                setOnClickListener {alert("Автовыбор",AutoCheckIntervals.HELP+"\n\n"+BsAutoSelectionPolicy.HELP)}
            },LinearLayout.LayoutParams(dp(40),dp(48)))
            addView(text("Автовыбор",13,Loft.INK).apply {gravity=Gravity.CENTER;includeFontPadding=false;setSingleLine(true);setAutoSizeTextTypeUniformWithConfiguration(9,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);minimumWidth=dp(76)},LinearLayout.LayoutParams(-2,dp(48)).apply {weight=1f})
            addView(intervalButton(AutoCheckIntervals.Kind.SELECTION),LinearLayout.LayoutParams(dp(56),dp(48)))
            addView(Button(this@MainActivity).apply {
                tag="auto-bs-selection-policy";DropdownStyle.value(this@MainActivity,this);textSize=14f
                val modes=listOf(BsAutoSelectionPolicy.OFF,BsAutoSelectionPolicy.FASTEST,BsAutoSelectionPolicy.RECENT)
                fun refresh(){val mode=BsAutoSelectionPolicy.selected(prefs);text="";setCompoundDrawablesRelative(icon(when(mode){BsAutoSelectionPolicy.OFF->"power";BsAutoSelectionPolicy.RECENT->"history";else->"bolt"},Loft.MUTED).apply {setBounds(0,0,dp(18),dp(18))},null,icon("down",Loft.MUTED).apply {setBounds(0,0,dp(10),dp(10))},null);contentDescription="Выбор сервера: "+when(mode){BsAutoSelectionPolicy.OFF->"выключен";BsAutoSelectionPolicy.RECENT->"последний выбранный";else->"самый быстрый"}}
                refresh()
                setOnClickListener {anchor->DropdownPopup.show(this@MainActivity,anchor,"Выбор сервера",listOf(DropdownPopup.Item("Выкл",icon="power"),DropdownPopup.Item("Самый быстрый",icon="bolt"),DropdownPopup.Item("Последний выбранный",icon="history")),modes.indexOf(BsAutoSelectionPolicy.selected(prefs))){index->
                    prefs.edit().putString(BsAutoSelectionPolicy.KEY,modes[index]).apply();refresh()
                }}
            },LinearLayout.LayoutParams(dp(44),dp(48)))
            addView(AutoToleranceUi.button(this@MainActivity),LinearLayout.LayoutParams(dp(88),dp(48)))
        }
        return LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;tag="unified-auto-selection"
            val detector=LinearLayout(this@MainActivity).apply {
                gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
                addView(choice,LinearLayout.LayoutParams(0,dp(48),1f))
                addView(intervalButton(AutoCheckIntervals.Kind.DETECTION),LinearLayout.LayoutParams(dp(56),dp(48)).apply {marginStart=dp(4)})
            }
            addView(connectionOptionRow("Автоопределение БС","auto-bs-control",detector),LinearLayout.LayoutParams(-1,dp(48)))
            addView(control,LinearLayout.LayoutParams(-1,dp(48)))
        }
    }
    private fun whiteLists() {
        val custom=ConnectionAppearanceUi.style(this)!=ConnectionAppearance.CLASSIC
        val controls=LinearLayout(this).apply {gravity=Gravity.CENTER;tag="whitelist-connection-control";orientation=LinearLayout.VERTICAL;background=Loft.connectionSurface(this@MainActivity);setPadding(dp(12),dp(12),dp(12),dp(8))}
        connect=connectionButton {toggleConnection()}
        controls.addView(connect,LinearLayout.LayoutParams(if(custom)-1 else dp(88),dp(if(custom)224 else 88)))
        controls.addView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            addView(autoBsChoice())
        },LinearLayout.LayoutParams(-1,-2))
        page.addView(controls,space(0,12))
        status=text("Отключено",15,Loft.INK).apply {gravity=Gravity.CENTER;tag="home-connection-status";setPadding(paddingLeft,paddingTop,paddingRight,paddingBottom+dp(3))}
        connectionHint=text("",12,Loft.MUTED).apply {
            gravity=Gravity.CENTER;minimumHeight=dp(48);tag="home-connection-hint"
            setOnClickListener {
                val challenge=CarrierChallenge.pendingManual()
                if(challenge!=null)startActivity(Intent(this@MainActivity,CarrierCaptchaActivity::class.java).putExtra("request",challenge.id))
                else if(ConnectionPresentation.failed(McrfVpnService.status))navigate("Логи")
            }
        }
        controls.addView(status);controls.addView(connectionTargetView());controls.addView(connectionHint,space(0,12))
        val actions=LinearLayout(this).apply {tag="whitelist-actions";gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false}
        fun action(label:String,description:String,target:String?,open:()->Unit) {
            actions.addView(button(label,false).apply {
                tag=if(target!=null)"whitelist-settings-$target"else"whitelist-engines"
                contentDescription=description;setSingleLine(true);setHorizontallyScrolling(false);ellipsize=null;includeFontPadding=false
                setPadding(dp(2),dp(6),dp(2),dp(6));setAutoSizeTextTypeUniformWithConfiguration(8,11,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                setOnClickListener {open()}
            },LinearLayout.LayoutParams(0,dp(48),1f).apply {leftMargin=dp(6);rightMargin=dp(6)})
        }
        if(UsageFeatures.Feature.WHITELIST in UsageFeaturesUi.selected(this)){
            action("VK Turn ⚙","VK Turn · настройки транспортов, аккаунт и звонки VK",ClientSettingsNavigation.VK){navigate(ClientSettingsNavigation.VK)}
        }
        if(UsageFeatures.Feature.OPENFLUX in UsageFeaturesUi.selected(this))action("OpenFlux","Настройки OpenFlux",ClientSettingsNavigation.OPENFLUX){navigate(ClientSettingsNavigation.OPENFLUX)}
        if(actions.childCount>0)page.addView(actions,space(0,12))
        ServersPage(this).build(page,includeControls=true,whitelistOnly=true)
        updateHomeControl()
    }
    private fun toggleConnection() {
        if(working())return
        if(active())stopConnection() else prepareConnection(false)
        updateHomeControl()
    }
    private fun toggleEdgeConnection(whitelists:Boolean,wanted:Boolean) {
        val runningWhitelists=catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId}?.carrier!=null
        val available=!working()&&!McrfVpnService.cleanupPending()&&
            (!whitelists||UsageFeatures.whitelists(UsageFeaturesUi.selected(this))||active()&&runningWhitelists)
        when(EdgeConnectionState.action(whitelists,wanted,active(),runningWhitelists,available)) {
            EdgeConnectionState.Action.START->{
                navigate(if(whitelists)ClientSettingsNavigation.WHITELISTS else "Главная")
                // The normal section-scoped validation/selection/consent path owns the start.
                // A sidebar toggle must not reuse a hidden server from the other pool.
                if(whitelists&&ConnectionSections.entries(catalogue.entries(),true).isEmpty())toast("Добавь подключение в «Белых списках»")
                else prepareConnection(false,requestedWhitelists=whitelists)
            }
            EdgeConnectionState.Action.STOP->stopConnection()
            EdgeConnectionState.Action.SWITCH->{
                // An explicit opposite-section ON chooses the fastest target-pool server,
                // not the saved (possibly unrelated) selection or marked automatic pool.
                // Resolve it BEFORE touching the live connection: an empty pool is a no-op.
                val target=SectionConnectSelection.choose(catalogue.entries(),"",whitelists,catalogue.probes())
                if(target==null)toast("В разделе «${if(whitelists)ClientSettingsNavigation.WHITELISTS else "VPN"}» пока нет серверов")
                else select(target.id) // Same exact STOP acknowledgement/retirement/consent path as a row tap.
            }
            EdgeConnectionState.Action.NONE->Unit
        }
        updateHomeControl()
    }
    fun setBsDetection(enabled:Boolean):Boolean {
        if(enabled&&catalogue.entries().none {AutoSelection.eligible(it,AutoSelection.BS_ENGINES)}){
            alert("Автоопределение БС","Сначала добавьте поддерживаемый сервер БС.");return false
        }
        if(enabled&&ClientPrimarySelection.selected(this)=="steer"){
            alert("Автоопределение БС","Для этих транспортов БС выберите sing-box в «Обновлениях и движках». Текущее подключение не изменено.");return false
        }
        prefs.edit().putBoolean(BsDetectionMonitor.KEY,enabled).apply()
        McrfQuickTile.refresh(this)
        miniature?.updateAutoDetection();autoControlsRefresh?.invoke()
        return true
    }
    private var pendingRulesTile=0
    private var rulesTileDeadline=0L
    private fun drainRulesTile() {
        if(pendingRulesTile==0)return
        if(SystemClock.elapsedRealtime()>rulesTileDeadline){pendingRulesTile=0;return}
        if(working()||onboardingVisible||McrfVpnService.cleanupPending()||ConnectionPresentation.pending(McrfVpnService.status))return
        pendingRulesTile--
        selectRoutingGroup(RoutingGroups.next(routing.snapshot()))
    }
    private fun handleQuickTile():Boolean {
        if(loading)return false
        val command=QuickTileCommand.consume(this,intent)?:return false
        if(command=="rules") {
            pendingRulesTile=(pendingRulesTile+1).coerceAtMost(16)
            rulesTileDeadline=SystemClock.elapsedRealtime()+60_000
            drainRulesTile();return true
        }
        if(working()){toast("Дождитесь завершения текущего действия");return true}
        when(command) {
            "vpn","bs"->{val bs=command=="bs"
                val runningBs=catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId}?.carrier!=null
                toggleEdgeConnection(bs,!(active()&&bs==runningBs))}
            "auto-bs"->{setBsDetection(!prefs.getBoolean(BsDetectionMonitor.KEY,false));updateHomeControl()}
        }
        McrfQuickTile.refresh(this)
        return true
    }
    private fun resumeWantedOnLaunch(){
        if(resumeAttempted)return
        resumeAttempted=true
        val behavior=try{ClientBehaviorStore.load(this)}catch(_:Exception){return}
        if(behavior.shouldReconnect(prefs.getBoolean("wantedRunning",false),savedConfig.isNotBlank(),VpnService.prepare(this)==null,active())&&!EngineUpdates.installationPending(this))launchConnection()
    }
    private fun updateMiniature(animate:Boolean) {
        miniature?.apply {visibility=if(hasVisibleItems())View.VISIBLE else View.GONE;alpha=1f}
    }
    fun refreshEdgeMenu(){miniature?.applyAppearance();updateMiniature(false)}
    private fun connectionTargetView()=text("",12,Loft.INK).apply {
        gravity=Gravity.CENTER;tag="home-connection-target";maxLines=2;ellipsize=TextUtils.TruncateAt.END
        setPadding(dp(8),dp(4),dp(8),dp(4));visibility=View.GONE
        accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;connectionTarget=this
    }
    private fun updateHomeControl() {
        autoControlsRefresh?.invoke()
        val connected=ConnectionPresentation.connected(McrfVpnService.status)
        val wifiPaused=McrfVpnService.wifiPaused()
        val stopping=McrfVpnService.cleanupPending()
        val pending = stopping||busy||serverSwitch.target!=null||ConnectionPresentation.pending(McrfVpnService.status)
        val failed=!pending&&ConnectionPresentation.failed(McrfVpnService.status)
        val challenge=CarrierChallenge.pendingManual()
        val shownVpn=if(active()||pending)prefs.getBoolean("tun",true)else UsageFeatures.preferredVpn(UsageFeaturesUi.selected(this),prefs.getBoolean("tun",true))
        (connect as? RoundConnectionButton)?.state(connected,pending,!working()&&!stopping,active(),failed)
        (connect as? ForgeConnectionButton)?.state(connected,pending,!working()&&!stopping,active(),failed,stopping,TrafficState.current)
        val runningWhitelists=catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId}?.carrier!=null
        val pendingSection=serverSwitch.target?.whitelists
            ?:if(ConnectionPresentation.pending(McrfVpnService.status))McrfVpnService.pendingWhitelists else if(busy)pendingConnectionWhitelists else null
        miniature?.updateConnection(active(),pending,!working()&&!stopping,shownVpn,runningWhitelists,pendingSection)
        if(!pending||connected&&!busy)pendingConnectionWhitelists=null
        miniature?.updateTelegram()
        miniature?.updateAutoDetection()
        status?.text = when {stopping->"Остановка движка…"; pending -> ConnectionPresentation.progressLabel(McrfVpnService.status); connected -> if (prefs.getBoolean("tun", true)) "VPN подключён" else "Прокси подключён"; failed -> "Ошибка подключения"; else -> "Отключено" }
        connectionHint?.text = if(stopping)"Ждём завершения очистки"else if (pending) "Соединение с сервером" else if (connected) { if (McrfVpnService.status.startsWith("VPN")) "Системный VPN" else "HTTP / SOCKS5 · локальный доступ" } else ""
        if(failed)connectionHint?.text=ClientEvents.redact(ConnectionPresentation.failureDetail(McrfVpnService.status)).take(140)+" · Открыть логи"
        if(wifiPaused&&!pending){status?.text="БС на паузе · Wi-Fi";connectionHint?.text="Wi-Fi напрямую · нажмите, чтобы отменить автовозврат БС"}
        if(challenge!=null&&current==ClientSettingsNavigation.WHITELISTS){status?.text="Нужна проверка VK";connectionHint?.text="Открыть капчу · осталось ${challenge.remainingMillis()/1000} с"}
        connectionHint?.setTextColor(if(failed)Loft.ERROR else Loft.MUTED)
        if(challenge!=null)connectionHint?.setTextColor(Loft.ACCENT)
        connectionHint?.visibility=if(connectionHint?.text.isNullOrBlank())View.GONE else View.VISIBLE
        status?.setTextColor(if(failed)Loft.ERROR else if(connected)0xff32b875.toInt() else Loft.INK)
        val snapshot = TrafficState.current
        val runningEntry=catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId}
        connectionTarget?.apply {
            text=ConnectionPresentation.target(connected,pending,runningEntry?.name,runningEntry?.host,runningEntry?.let {it.carrier!=null})
            visibility=if(text.isEmpty())View.GONE else View.VISIBLE
        }
        if (connected && snapshot.server.isNotEmpty()&&runningEntry!=null&&ConnectionSections.belongs(runningEntry,false)) selectedServer?.text = snapshot.server
        modeHint?.text = if (shownVpn) "Системный VPN" else if(active()&&McrfVpnService.localProxyPort>0) "Только прокси · 127.0.0.1:${McrfVpnService.localProxyPort}" else "Только локальный прокси"
    }
    private fun changeMode(vpn: Boolean) {
        if (working() || prefs.getBoolean("tun", true) == vpn) return
        if(active()&&catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId}?.let { !ConnectionSections.belongs(it,connectionSectionWhitelists()) }==true){toast("Сначала отключи соединение в другом разделе");return}
        if(McrfVpnService.cleanupPending()){toast("Дождись остановки движка");return}
        fun change() {
            if (active() && vpn) { VpnService.prepare(this)?.let { startActivityForResult(it, 5); return } }
            prefs.edit().putBoolean("tun", vpn).apply(); tun?.isChecked = vpn; modeSwitch?.setMode(vpn, true); updateHomeControl(); if (active()) prepareConnection(false)
        }
        if (active()) ThemedDialogBuilder(this).setTitle("Сменить режим?").setMessage("Соединение будет переподключено в режиме «${if (vpn) "VPN" else "Только прокси"}».")
            .setNegativeButton("Отмена", null).setPositiveButton("Переключить") { _, _ -> change() }.show() else change()
    }
    private fun settings() {
        val features=UsageFeaturesUi.selected(this)
        val search=editor("Поиск по настройкам").apply {setSingleLine(true);tag="settings-search"}
        val results=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val rows=mutableListOf<Pair<View,String>>()
        page.addView(search,space(0,12));page.addView(results)
        fun link(target:String,detail:String) {
            if(!UsageFeatures.visible(target,features))return
            val row=SettingsUiNodes(this).actionRow(target,detail,"arrow","Открыть: $target"){
                val query=search.text.toString().trim()
                if(target==ClientSettingsNavigation.ADVANCED&&query.isNotEmpty())openAdvanced(SettingsSearchIndex.advancedAnchor(query),query)else navigate(target)
            }.apply {tag="settings-page-$target"}
            results.addView(row,space(0,12));rows.add(row to "$target $detail ${SettingsSearchIndex.keywords(target)}")
        }
        ClientSettingsNavigation.rootLeading.forEach {target->link(target,when(target) {
            ClientSettingsNavigation.APPEARANCE->"Цвета светлой и тёмной темы"
            ClientSettingsNavigation.DNS->"DNS для VPN и прямых маршрутов"
            else->"Установка и управление сервером · SSH и панели"
        })}
        link(ClientSettingsNavigation.BEHAVIOR,"Переподключение, уведомления и батарея")
        link(ClientSettingsNavigation.VPN,"IPv6, локальные сети и прокси на телефоне")
        link(ClientSettingsNavigation.GEO,"Источники и загрузка баз; пресеты — в маршрутах")
        link(ClientSettingsNavigation.VK,"Привязка аккаунта и количество звонков")
        link(ClientSettingsNavigation.UPDATES,"Версия ${BuildConfig.VERSION_NAME} · приложение и движки")
        link(ClientSettingsNavigation.ADVANCED,"TUN, TLS, Multiplex, QUIC, дополнительные DNS и прокси")
        val empty=text("Ничего не найдено. Проверь запрос или выбор функций в «Минимализме».",12,Loft.MUTED).apply {visibility=View.GONE}
        results.addView(empty)
        search.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                var found=0
                rows.forEach {(row,words)->val match=SettingsSearchIndex.matches(s.toString(),words);row.visibility=if(match)View.VISIBLE else View.GONE;if(match)found++}
                empty.visibility=if(found==0)View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        divider(page,0,16)
        val backup = card(); backup.addView(text("Бэкап и восстановление", 18, Loft.INK))
        backup.addView(LinearLayout(this).apply {tag="backup-actions"
            addView(button("Создать",false).apply {contentDescription="Создать зашифрованный бэкап";setOnClickListener {BackupUi(this@MainActivity).export()}},LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=dp(4)})
            addView(button("Восстановить",false).apply {textSize=13f;setOnClickListener {BackupUi(this@MainActivity).chooseRestore()}},LinearLayout.LayoutParams(0,-2,1f).apply {leftMargin=dp(4)})
        },space(12,0))
        page.addView(backup, space(0,16))
        rows.add(backup to "Бэкап резервная копия экспорт импорт восстановление зашифрованный")
        val reset=button("Восстановить настройки по умолчанию",false).apply {setOnClickListener {BackupUi(this@MainActivity).resetDefaults()}}
        page.addView(reset,space(0,16));rows.add(reset to "Восстановить настройки по умолчанию сброс")
    }
    private fun settingsPage(name:String) {
        when(name) {
            ClientSettingsNavigation.DEPLOY -> deployment()
            ClientSettingsNavigation.OPENFLUX -> {
                page.addView(text("Параметры применяются к выбранному подключению OpenFlux. Открытие страницы не запускает движок и не меняет активный VPN.",12,Loft.MUTED),space(0,12))
                val entries=catalogue.entries().filter {it.protocol=="openflux"&&it.carrier!=null}
                if(entries.isEmpty())page.addView(text("Добавь подключение OpenFlux в «Белых списках».",14,Loft.MUTED),space(0,12))
                entries.forEach {entry->page.addView(SettingsUiNodes(this).actionRow(entry.name,"Настройки подключения OpenFlux","arrow","Настройки OpenFlux ${entry.name}"){ConnectionEditor(this,entry).show(Runnable {render()})},space(0,12))}
            }
            ClientSettingsNavigation.MINIMALISM -> page.addView(UsageFeaturesUi.settings(this),space(0,16))
            ClientSettingsNavigation.APPEARANCE -> page.addView(UserThemeColorsUi.view(this),space(0,16))
            ClientSettingsNavigation.ADVANCED -> {
                val features=UsageFeaturesUi.selected(this)
                fun family(title:String){page.addView(text(title,18,Loft.INK).apply {setAccessibilityHeading(true)},space(0,12))}
                if(UsageFeatures.visible(ClientSettingsNavigation.XRAY,features)) {
                    family("Основное ядро · безопасность и транспорт")
                    page.addView(CoreTuningUi(this).advancedView().apply {tag="advanced-core"},space(0,16))
                    family("Отдельные модули · Xray и TLS")
                    page.addView(OptionalCoreUi(this).advancedXrayView().apply {tag="advanced-xray"},space(0,16))
                }
                if(UsageFeatures.visible(ClientSettingsNavigation.TLS,features))page.addView(OptionalCoreUi(this).advancedTlsView().apply {tag="advanced-tls"},space(0,16))
                family("VPN и прокси · интерфейсы и локальная сеть")
                page.addView(ClientTunUi(this).advancedView().apply {tag="advanced-tun"},space(0,16))
                family("DNS · ответы и разрешение доменов")
                page.addView(AdvancedDnsEditor(this,routing).advancedView().apply {tag="advanced-dns"},space(0,16))
                family("Геобазы · собственные источники")
                page.addView(GeoDataEditor(this,routing).advancedView().apply {tag="advanced-geo"},space(0,16))
            }
            ClientSettingsNavigation.DNS -> {settingsDns();page.addView(AdvancedDnsEditor(this,routing).build(),space(0,16))}
            ClientSettingsNavigation.BEHAVIOR -> page.addView(ClientBehaviorUi.build(this),space(0,16))
            ClientSettingsNavigation.VPN -> page.addView(ClientTunUi(this).view(),space(0,16))
            ClientSettingsNavigation.GEO -> page.addView(GeoDataEditor(this,routing).build(),space(0,16))
            ClientSettingsNavigation.VK -> {
                page.addView(captchaChoice(findSelected()?.takeIf {ConnectionSections.belongs(it,true)}),space(0,12))
                deployUi=AndroidDeployUi(this,AndroidDeployStore(this),AndroidDeployUi.backend(this)){importDialog(it)}
                page.addView(deployUi!!.vkSettings(),space(0,16))
                page.addView(VkTurnUi.serverSettings(this),space(0,16))
                page.addView(BsWifiUi.view(this),space(0,16))
                page.addView(WdttMasqueUi.view(this),space(0,16))
                if(returnToDeployVk) page.addView(button("Вернуться к настройке SWG",false).apply {tag="androidDeploy.returnFromVk";setOnClickListener {returnToDeployVk=false;navigate("Автодеплой")}},space(0,12))
            }
            ClientSettingsNavigation.UPDATES -> {
                page.addView(SettingsUiNodes(this).actionRow("Обновление приложения","Версия ${BuildConfig.VERSION_NAME}","refresh","Проверить обновления"){AppUpdates.check(this)}.apply {tag="update-row"},space(0,16))
                page.addView(ProtocolsSheet.engineView(this,primaryHost),space(0,16))
                page.addView(button("Расширенные параметры основного ядра",false).apply {setOnClickListener {openAdvanced("advanced-core")}},space(0,12))
                if(UsageFeatures.Feature.OPENFLUX in UsageFeaturesUi.selected(this))page.addView(EngineUpdateUi.view(this),space(0,16))
            }
        }
    }
    fun proxyLinks():LinearLayout {
        var proxyAuth=LocalProxySettings.value(this)
        return LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            tag="local-proxy-links"
            addView(LinearLayout(this@MainActivity).apply {
                listOf("socks5" to "SOCKS5","http" to "HTTP").forEachIndexed {index,(scheme,label)->
                    addView(button(label,false).apply {
                        tag="copy-proxy-$scheme";textSize=12f;contentDescription="Скопировать $label прокси"
                        setCompoundDrawablesWithIntrinsicBounds(icon("copy",Loft.MUTED),null,null,null);compoundDrawablePadding=dp(6)
                        setOnClickListener {
                            val setting=ClientTunStore(this@MainActivity).load()
                            if(!setting.mixedEnabled){alert("Прокси","Включите локальный прокси на этой странице.");return@setOnClickListener}
                            if(setting.randomPort&&!active()){alert("Прокси","При случайном порте сначала включите подключение, затем скопируйте фактический адрес.");return@setOnClickListener}
                            val number=if(active()&&McrfVpnService.localProxyPort>0)McrfVpnService.localProxyPort else setting.mixedPort
                            val clip=AndroidDeployClipboard.sensitive(LocalProxyConfig.link(scheme,number,LocalProxySettings.value(this@MainActivity)),"MCRF · $label")
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip);toast("Ссылка $label скопирована")
                        }
                    },LinearLayout.LayoutParams(0,-2,1f).apply {if(index==0)rightMargin=dp(6)else leftMargin=dp(6)})
                }
            },space(0,12))
            val auth=Switch(this@MainActivity).apply {tag="proxy-auth";text="Авторизация локального прокси";tune(this);isChecked=proxyAuth.getBoolean("enabled")}
            var restoring=false
            auth.setOnCheckedChangeListener {_,enabled->if(!restoring){
                if(active()){restoring=true;auth.isChecked=proxyAuth.getBoolean("enabled");restoring=false;alert("Прокси","Сначала отключите соединение.")}
                else try{val next=JSONObject(proxyAuth.toString()).put("enabled",enabled);ClientTunStore(this@MainActivity).saveAuth(next);proxyAuth=next}
                catch(_:Exception){restoring=true;auth.isChecked=proxyAuth.getBoolean("enabled");restoring=false;alert("Прокси","Авторизация несовместима с текущими параметрами системного HTTP-прокси или раздачи.")}
            }}
            addView(auth,space(0,6))
            addView(LinearLayout(this@MainActivity).apply {tag="proxy-auth-actions";gravity=Gravity.CENTER_VERTICAL
                for((index,pair)in listOf("username" to "Логин","password" to "Пароль").withIndex()){val(key,label)=pair;addView(button(label,false).apply {
                    tag="proxy-copy-$key";contentDescription="Скопировать $label";setSingleLine(true);textSize=12f
                    setCompoundDrawablesWithIntrinsicBounds(icon("copy",Loft.ACCENT),null,null,null);compoundDrawablePadding=dp(4)
                    setOnClickListener {try{getSystemService(ClipboardManager::class.java).setPrimaryClip(AndroidDeployClipboard.sensitive(ClientTunStore(this@MainActivity).auth().getString(key),"MCRF · $label"));toast("Скопировано")}catch(_:Exception){alert("Прокси","Не удалось скопировать данные")}}
                },LinearLayout.LayoutParams(0,dp(48),1f).apply {if(index==0)rightMargin=dp(6)})}
                addView(iconButton("refresh","Изменить логин и пароль").apply {tag="proxy-edit-auth";setOnClickListener {LocalProxyAuthUi.edit(this@MainActivity)}},LinearLayout.LayoutParams(dp(48),dp(48)).apply {leftMargin=dp(6)})
            },space(0,8))
            addView(text("Логин и пароль общие для обоих прокси. Скопированные ссылки HTTP/SOCKS5 могут содержать их — не публикуйте ссылки.",12,Loft.MUTED),space(0,8))
        }
    }
    private fun settingsDns() {
        val dns = try { DnsEditor(this, routing.snapshot().getJSONObject("globalDns")) } catch (_: Exception) { page.addView(text("Не удалось прочитать настройки DNS. Бэкап и остальные настройки доступны ниже.", 14, Loft.ERROR)); null }
        if(dns!=null){
        val dnsStatus = text("Сохраняется автоматически · применяется при подключении.", 11, Loft.MUTED)
        val card = card().apply {tag="dns-settings";setPadding(dp(14),dp(12),dp(14),dp(12))}
        card.addView(text("DNS трафика VPN", 16, Loft.INK)); card.addView(dns.view, space(6,6)); card.addView(dnsStatus)
        card.addView(text("Для JSON-профиля используется его DNS.", 11, Loft.MUTED), space(6,0)); page.addView(card,space(0,12))
        var revision = 0L
        val dnsDraft = ClientSettingsDnsDraft(dns.value())
        val saveDns = Runnable {
            val setting = try { dns.value() } catch (error: Exception) { dnsStatus.text = error.message ?: "Проверь адрес DNS"; dnsStatus.setTextColor(Loft.ERROR); return@Runnable }
            if (!dnsDraft.changed(setting)) return@Runnable
            val requested = revision
            worker.execute {
                try { routing.updateDns(setting); dnsDraft.saved(setting)
                    ui { if (revision == requested) { dnsStatus.text = "DNS сохранён · применится при следующем подключении"; dnsStatus.setTextColor(Loft.MUTED) } }
                } catch (_: Exception) { ui { dnsStatus.text = "Не удалось сохранить DNS. Предыдущие настройки сохранены."; dnsStatus.setTextColor(Loft.ERROR) } }
            }
        }
        dns.onChange { revision++; handler.removeCallbacks(saveDns); handler.postDelayed(saveDns, 350) }
        pendingSettingsSave = saveDns
        }
    }
    private fun batteryPrompt() {
        if (BuildConfig.DEBUG && intent.getBooleanExtra("test_no_prompts", false)) return
        val power = getSystemService(PowerManager::class.java)
        val needsRuntimeNotificationPermission=Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
        val notifications=getSystemService(android.app.NotificationManager::class.java)
        val needsNotifications=needsRuntimeNotificationPermission||!notifications.areNotificationsEnabled()||notifications.getNotificationChannel("vpn")?.importance==android.app.NotificationManager.IMPORTANCE_NONE
        val installation=packageManager.getPackageInfo(packageName,0).firstInstallTime
        if(needsRuntimeNotificationPermission&&prefs.getLong("notificationPermissionInstallation",-1)!=installation){
            prefs.edit().putLong("notificationPermissionInstallation",installation).apply()
            requestNotificationAccess()
            return
        }
        if (prefs.getLong("backgroundAdviceInstallation",-1)==installation) return
        val needsBattery=!power.isIgnoringBatteryOptimizations(packageName)
        prefs.edit().putBoolean("connectionSetupShown",true).putLong("backgroundAdviceInstallation",installation).apply()
        if(!needsNotifications&&!needsBattery)return
        onboardingVisible=true
        ThemedDialogBuilder(this).setTitle("Соединение в фоне")
            .setMessage("Уведомление показывает состояние и позволяет остановить подключение. Работа без ограничений батареи помогает Android не прерывать VPN. Настройки необязательны и доступны позже: Настройки → Подключение и работа в фоне. Постоянный wakelock не используется.")
            .setNegativeButton("Позже",null)
            .setPositiveButton("Настроить") {_,_->
                if(needsBattery)batterySettings()
                else if(needsNotifications)requestNotificationAccess()
            }.setOnDismissListener {onboardingVisible=false}.show()
    }
    fun requestNotificationAccess() {
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            onboardingVisible=true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),2)
        }
        else try{startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))}
        catch(_:ActivityNotFoundException){alert("Уведомления","Android → Приложения → MCRF → Уведомления.")}
    }
    fun openBackgroundSettings() = batterySettings()
    override fun onRequestPermissionsResult(request:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(request,permissions,results)
        if(request==BsWifiUi.PERMISSION_REQUEST){McrfVpnService.refreshBsEnvironment(this);if(!isFinishing&&!isDestroyed)render();return}
        if(request==2)onboardingVisible=false
        if(request==2&&prefs.getBoolean("backgroundAfterNotifications",false)) {
            prefs.edit().remove("backgroundAfterNotifications").apply()
            if(!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))batterySettings()
        }else if(request==2&&!isFinishing&&!isDestroyed)batteryPrompt()
    }
    override fun onPostResume() {
        super.onPostResume()
        if(current==ClientSettingsNavigation.VK)McrfVpnService.refreshBsEnvironment(this)
    }
    private fun batterySettings() {
        when(ClientBatterySettings.open(this)){
            ClientBatterySettings.Destination.APP_DETAILS->toast("На этом Android открой «Батарея» в карточке MCRF → «Без ограничений».")
            ClientBatterySettings.Destination.UNAVAILABLE->alert("Работа в фоне","Настройки Android → Приложения → MCRF → Батарея → Без ограничений.")
            else->Unit
        }
    }
    private fun journal() {
        LogsPage(this).build(page)
    }
    private fun deployment() {
        deployUi=AndroidDeployUi(this,AndroidDeployStore(this),AndroidDeployUi.backend(this)){importDialog(it)}
        page.addView(deployUi!!.buildPage(),space(0,16))
    }
    private fun recoverWarpSelection() {
        val raw=prefs.getString("warpSelectionJournal",null)?:return
        val next=ClientWarpSelectionJournal.recover(raw,selected,ProfileStore(this,"connections.enc").revision().substringBefore(':'),catalogue.entries().mapTo(hashSetOf()){it.id}) {catalogue.warpReceipt(it)}
        if(next!=null){check(prefs.edit().putString("selected",next).remove("warpSelectionJournal").commit());selected=next;return}
        check(prefs.edit().remove("warpSelectionJournal").commit())
    }
    private fun warp(): WarpController = warpController?:WarpController(this,catalogue,WarpTraceProbe(applicationContext),
        commit={verified,previousId->
            val next=WarpProfile.prepare(verified.server.original,verified.account).id
            val replaceSelection=selected.isEmpty()||selected==previousId
            val operationId=java.util.UUID.randomUUID().toString()
            if(replaceSelection)check(prefs.edit().putString("warpSelectionJournal",ClientWarpSelectionJournal.create(selected,previousId,next,ProfileStore(this,"connections.enc").revision().substringBefore(':'),operationId)).commit())
            val committed=try{catalogue.saveWarp(verified.account,verified.server.original,previousId,operationId)}catch(error:Exception){prefs.edit().remove("warpSelectionJournal").commit();throw error}
            check(committed==next)
            if(replaceSelection){check(prefs.edit().putString("selected",committed).remove("warpSelectionJournal").commit());selected=committed}
            committed
        },remove={id->prefs.edit().remove("warpSelectionJournal").commit();catalogue.remove(id);reconcileSelection(false)},onChanged={if(!isFinishing&&!isDestroyed&&!loading&&current in setOf("WARP","Главная","Маршрутизация"))render()}
    ).also {warpController=it}
    fun openDiagnosticPage() { if (busy) return; current = "Диагностика"; render() }
    private fun diagnostics() {
        page.addView(text("Отдельный профиль sing-box. Использует собственные выходы и маршруты.", 13, Loft.MUTED), space(0, 12))
        tun = Switch(this).apply { text = "Системный VPN"; tune(this); isChecked = prefs.getBoolean("tun", true) }; page.addView(tun, space(0, 12))
        port = number(prefs.getInt("port", 10808)); page.addView(field("Порт прокси", port!!))
        source = editor("JSON-конфигурация").apply { minLines = 10; maxLines = 18; typeface = Typeface.MONOSPACE; textSize = 12f; setText(savedConfig) }; page.addView(source, space(12, 12))
        connect = button("Подключить", true).apply { isEnabled = !working(); setOnClickListener { prepareConnection(true) } }; page.addView(connect, space(0, 10))
        page.addView(button("Остановить", false).apply { setOnClickListener { stopConnection() } }); status = text(McrfVpnService.status, 14, Loft.ACCENT); page.addView(status, space(12, 0))
    }
    fun findSelected() = catalogue.entries().firstOrNull { it.id == selected }
    fun connectionSectionWhitelists()=current==ClientSettingsNavigation.WHITELISTS||ClientSettingsNavigation.isSettings(current)&&settingsOrigin==ClientSettingsNavigation.WHITELISTS
    fun connectionSectionPolicy()=ConnectionSections.policy(connectionSectionWhitelists())
    fun active() = McrfVpnService.wifiPaused()||McrfVpnService.cleanupPending()||McrfVpnService.status.startsWith("VPN подключён") || McrfVpnService.status.startsWith("Прокси ") || McrfVpnService.status == "Подключение…" || McrfVpnService.status=="Восстановление соединения…" || McrfVpnService.status=="Возвращаемся в VPN…" || McrfVpnService.status.startsWith("Переключаем движок")
    private fun stopConnection() {serverSwitch.cancel();handler.removeCallbacks(serverSwitchPoll);startService(Intent(this, McrfVpnService::class.java).setAction(McrfVpnService.STOP))}
    private fun prepareConnection(diagnostic: Boolean,trafficChoiceChecked:Boolean=false,requestedWhitelists:Boolean?=null) {
        if (working()) return
        val whitelists=requestedWhitelists?:connectionSectionWhitelists()
        val sectionPolicy=ConnectionSections.policy(whitelists)
        val sectionEntries=ConnectionSections.entries(catalogue.entries(),whitelists)
        if(McrfVpnService.cleanupPending()){toast("Дождись остановки движка");return}
        if(!diagnostic&&catalogue.entries().isNotEmpty()) {
            if(sectionEntries.isEmpty()) {
                toast("В разделе «${if(whitelists)ClientSettingsNavigation.WHITELISTS else "VPN"}» пока нет серверов");return
            }
            run {
                // The detector's policy is the only user-facing auto choice.
                // Do not resurrect the removed marked-pool switch from old prefs.
                val recent=BsAutoSelectionPolicy.selected(prefs)==BsAutoSelectionPolicy.RECENT
                val manual=BsAutoSelectionPolicy.last(prefs,whitelists)
                if(BsAutoSelectionPolicy.selected(prefs)==BsAutoSelectionPolicy.OFF&&sectionEntries.none {it.id==manual}){
                    alert("Автовыбор выключен","Сначала выберите сервер ${if(whitelists)"БС"else"VPN"} вручную.");return
                }
                val allowed=sectionEntries.filter {ClientPrimarySelection.selected(this)!="steer"||SteerProbePlan.supports(it)}
                val preferred=if(prefs.getBoolean(BsDetectionMonitor.KEY,false)&&BsAutoSelectionPolicy.selected(prefs)!=BsAutoSelectionPolicy.OFF)BsAutoSelectionPolicy.startup(
                    allowed,catalogue.probes(),System.currentTimeMillis(),whitelists,recent,manual)?.id?:selected else if(BsAutoSelectionPolicy.selected(prefs)==BsAutoSelectionPolicy.OFF)manual else selected
                val start=SectionConnectSelection.start(catalogue.entries(),preferred,whitelists,false,false,catalogue.probes())!!
                val entry=start.server
                if(entry.id!=selected||start.automatic!=automatic) {
                    if(!prefs.edit().remove("warpSelectionJournal").putString("selected",entry.id).putBoolean("automatic",start.automatic).commit()) {
                        alert("Подключение","Не удалось сохранить выбранный сервер. Повторите подключение.");return
                    }
                    selected=entry.id;automatic=start.automatic
                    refreshSelectedServer()
                }
            }
        }
        if(ClientPrimarySelection.selected(this)==null){offerPrimaryChoice();return}
        if(!trafficChoiceChecked&&!prefs.getBoolean(ClientPrimarySelection.DUAL_CONFIRMED,false)) {
            busy=true
            worker.execute {
                val ask=ClientPrimarySelection.needsTrafficChoice(this)
                ui {
                    busy=false
                    if(ask){pendingPrimaryConnection=diagnostic;offerPrimaryChoice(force=true)}
                    else prepareConnection(diagnostic,trafficChoiceChecked=true,requestedWhitelists=whitelists)
                }
            }
            return
        }
        if(EngineUpdates.installationPending(this)||EngineUpdateScripts.mutationPending()){alert("Обновление движка","Завершите обновление движка перед подключением.");return}
        if (!diagnostic && !automatic) findSelected()?.takeIf { it.carrier != null }?.let { entry ->
            if(CarrierPrimaryChoice.required(ClientPrimarySelection.selected(this),entry.protocol)) {
                ThemedDialogBuilder(this).setTitle("Нужен совместимый основной движок")
                    .setMessage(CarrierPrimaryChoice.explanation(entry.protocol))
                    .setNegativeButton("Отмена",null).setPositiveButton("Выбрать движок") {_,_->
                        pendingPrimaryConnection=diagnostic;offerPrimaryChoice(force=true)
                    }.show()
                return
            }
            if (EnginePackages.missing(this, setOf(EngineModules.carrierKind(requireNotNull(entry.carrier)))).isNotEmpty()) { importDialog(catalogue.share(entry)); return }
        }
        val vpn = if(diagnostic)tun?.isChecked ?: prefs.getBoolean("tun",true)else UsageFeatures.preferredVpn(UsageFeaturesUi.selected(this),prefs.getBoolean("tun",true))
        val diagnosticPort = try { if (diagnostic) port!!.text.toString().toInt() else null } catch (_: Exception) { alert("Порт", "Некорректный порт прокси."); return }
        val raw = if (diagnostic) source!!.text.toString() else savedConfig
        pendingConnectionWhitelists=whitelists
        busy = true; connect?.isEnabled = false
        connectionStartedNs=ConnectTiming.now()
        val queuedAt=connectionStartedNs
        worker.execute {
            ConnectTiming.mark(this,"ui_queue",queuedAt)
            val prepareAt=ConnectTiming.now()
            try {
                val wireGuardProfile=if(diagnostic)WgAddonPolicy.usesWireGuard(JSONObject(raw))
                    else !automatic&&findSelected()?.protocol?.let(WgAddonPolicy::isWireGuard)==true
                val effectivePrimary=ClientPrimarySelection.effectiveWireGuardChoice(this,ClientPrimarySelection.requireSelected(this),wireGuardProfile)
                val requiredPrimary=EngineUpdatePrimary.kindForChoice(effectivePrimary)
                if(EnginePackages.missing(this,setOf(requiredPrimary)).isNotEmpty()) {
                    ui {busy=false;connect?.isEnabled=true;installEngine(requiredPrimary)}
                    return@execute
                }
                if(effectivePrimary=="steer") {
                    require(!diagnostic&&vpn) {"Steer: нужен режим VPN и сервер из каталога, не отдельный JSON-профиль."}
                    val autoPool=if(automatic)SteerAutomaticPool(ConnectionSections.entries(catalogue.entries(),false),catalogue.autoIds(),System.nanoTime().coerceAtLeast(1))else null
                    var entry=if(autoPool!=null)autoPool.candidates.firstOrNull()
                        ?:throw IllegalArgumentException("Отметь совместимые серверы Steer для автовыбора; другой движок автоматически не выбирается.")
                        else findSelected()?:throw IllegalArgumentException("Выбери сервер для Steer.")
                    require(entry.carrier==null&&entry.configuration==null) {"Steer пока не поддерживает это подключение."}
                    if(wireGuardProfile) {
                        if(RouteServerPlan.activeIds(routing.snapshot()).isNotEmpty())throw ClientOptionalCoreRequired("sing-box")
                        require(vpn) {"Компактный WG/AWG работает только в режиме VPN."}
                        if(EnginePackages.missing(this,setOf(WgAddonPolicy.KIND)).isNotEmpty()) {
                            ui {busy=false;connect?.isEnabled=true;installEngine(WgAddonPolicy.KIND)}
                            return@execute
                        }
                        // Actual service owns the fresh module/route admission before TUN.
                        if(!ConnectionStartPolicy.servicePrepares(diagnostic,RouteServerPlan.activeIds(routing.snapshot()).isNotEmpty()))
                            ClientPacketSelection.prepare(this,entry.original,ClientPacketSelection.validationDial(entry.host))
                        val generated=ServerImport.build(listOf(entry),entry.id,false,60,50,"Cloudflare")
                        DnsConfig.applyGenerated(generated,routing.snapshot().getJSONObject("globalDns"))
                        val content=generated.toString();ProfileStore(this).write(content);savedConfig=content
                        check(prefs.edit().putBoolean("tun",true).putBoolean("applyRoutes",true).putBoolean("catalogueProfile",true).commit())
                        ui {busy=false;connect?.isEnabled=true;val consent=VpnService.prepare(this);if(consent!=null)startActivityForResult(consent,1)else launchConnection()}
                        return@execute
                    }
                    if(RouteServerPlan.activeIds(routing.snapshot()).isNotEmpty()){
                        val preparation=NativeRoutePreparation.capture(this,entry,routing.snapshot(),catalogue.entries())
                        val number=ClientTunStore(this).load().choosePort()
                        preparation.validate(this,true,number,java.util.function.BooleanSupplier {isDestroyed||isFinishing})
                        val content=preparation.source().toString()
                        ProfileStore(this).write(content);savedConfig=content
                        check(prefs.edit().putBoolean("tun",true).putInt("port",number).putBoolean("applyRoutes",true).putBoolean("catalogueProfile",true).commit())
                        ui {preparedProxyPort=number;busy=false;connect?.isEnabled=true;val consent=VpnService.prepare(this);if(consent!=null)startActivityForResult(consent,1)else launchConnection()}
                        return@execute
                    }
                    val result=ClientPrimarySelection.steerSnapshot(this,routing.snapshot(),vpn)
                    require(result.supported) {ClientPrimarySelection.steerFailure(result)}
                    if(!ConnectionStartPolicy.skipTemporarySteerWorker(automatic,RouteServerPlan.activeIds(routing.snapshot()).isNotEmpty())) {
                    val session=ClientPrimaryWorker.connect(this,"steer",System.nanoTime().coerceAtLeast(1),null)
                    try {
                        entry=(autoPool?.candidates?:listOf(entry)).firstOrNull {candidate->
                            session.validate(SteerProbePlan.profile(candidate),result.settingsJson!!,org.mcrf.engine.spi.v1.Engine.Mode.VPN).supported
                        }?:throw IllegalArgumentException("Steer не поддерживает параметры отмеченных серверов. Отметки и выбор движка не изменены.")
                    }finally{session.close()}
                    }
                    val content=ServerImport.build(listOf(entry),entry.id,false,60,50,"Cloudflare").toString()
                    ProfileStore(this).write(content);savedConfig=content
                    check(prefs.edit().putBoolean("tun",true).putBoolean("applyRoutes",true).putBoolean("catalogueProfile",true).commit())
                    ui {busy=false;connect?.isEnabled=true;val consent=VpnService.prepare(this);if(consent!=null)startActivityForResult(consent,1)else launchConnection()}
                    return@execute
                }
                val clientTun=ClientTunStore(this);val tunSettings=clientTun.load();val proxyAuth=clientTun.auth()
                val number=diagnosticPort?:tunSettings.choosePort()
                var content = raw
                if (!diagnostic && catalogue.entries().isEmpty() && prefs.getBoolean("catalogueProfile", false)) throw IllegalArgumentException("Добавь или выбери подключение в разделе «Серверы».")
                val routes = if (diagnostic) null else routing.snapshot()
                if (!diagnostic && catalogue.entries().isNotEmpty()) {
                    val chosen = findSelected()
                    if (!automatic && chosen?.carrier != null) {
                        NativeCarrier.executable(this, EngineModules.carrierKind(chosen.carrier))
                        NativeCarrier.requireOptions(this,chosen.carrier)
                    }
                    val policy=sectionPolicy
                    val pool = ConnectionSections.entries(if (automatic) catalogue.startupEntries(policy,refresh=true) else catalogue.entries(),whitelists)
                    if (automatic && pool.isEmpty()) throw IllegalArgumentException("В этом разделе нет отмеченных совместимых серверов для автовыбора. Отметь хотя бы один сервер нужного раздела.")
            val generated = if(automatic&&policy==AutoSelection.BS_ENGINES) BsPlan.build(pool,prefs.getInt("interval",60),AutoSelectionDeviation.millis(prefs),"Cloudflare")
                else ServerImport.build(pool, selected, automatic, prefs.getInt("interval", 60), AutoSelectionDeviation.millis(prefs), "Cloudflare")
                    if (automatic || chosen == null || chosen.configuration == null) DnsConfig.applyGenerated(generated, routes!!.getJSONObject("globalDns"))
                    content = generated.toString()
                }
                val routeServers=if(diagnostic)emptyList()else catalogue.entries()
                if(!diagnostic) {
                    val ids=RouteServerPlan.activeIds(routes!!).toSet()
                    val extra=routeServers.filter {it.id in ids}.mapNotNull {it.carrier}
                    val template=if(content.trimStart().startsWith("{"))JSONObject(content)else JSONObject()
                    ConnectTiming.measure(this,"masque_prepare"){WdttMasqueRegistration.prepare(this,template,extra,java.util.function.BooleanSupplier{isDestroyed||isFinishing})}
                }
                // P049_DEFER_BEGIN: raw profile only; actual worker validates the ONE service-built config.
                if(ConnectionStartPolicy.servicePrepares(diagnostic,routes?.let {RouteServerPlan.activeIds(it).isNotEmpty()}?:false)) {
                    val availability=OptionalCorePlan(ProfileConfig.build(content,vpn,number),OptionalCoreStore(this).load(),CoreTuningStore(this).load())
                    for(item in availability.entries())if(EnginePackages.missing(this,setOf(item.request.kind)).isNotEmpty())throw ClientOptionalCoreRequired(item.request.kind)
                    ProfileStore(this).write(content);savedConfig=content
                    check(prefs.edit().putBoolean("tun",vpn).putInt("port",number).putBoolean("applyRoutes",true).putString("autoPolicy",sectionPolicy)
                        .putBoolean("catalogueProfile",catalogue.entries().isNotEmpty()).commit())
                    ui {preparedProxyPort=number;busy=false;connect?.isEnabled=true;val consent=if(vpn)VpnService.prepare(this)else null;if(consent!=null)startActivityForResult(consent,1)else launchConnection()}
                    return@execute
                }
                // P049_DEFER_END
                var prepared = LocalProxyConfig.apply(ProfileConfig.build(content, vpn, number),proxyAuth); if (!diagnostic) prepared=RouteRules.apply(prepared, routes!!, vpn, PresetAssets.resolve(this,routes),PresetAssets.ipRuleSetTags(this,routes),DirectDnsNetwork.ipv4Only(this),routeServers)
                val extensionSettings=OptionalCoreStore(this).load()
                val coreSettings=CoreTuningStore(this).load()
                val engineLease=EngineUpdateGate.acquireUsage(this)?:throw IllegalArgumentException("Завершите обновление движка перед проверкой подключения.")
                try {
                if(!diagnostic)RouteServerSession.check(this,RouteServerPlan(routes!!,routeServers),java.util.function.BooleanSupplier{isDestroyed||isFinishing})
                val plan=OptionalCorePlan(prepared,extensionSettings,coreSettings)
                val bridges=linkedMapOf<String,JSONObject>()
                plan.preflight(java.util.function.BooleanSupplier{isDestroyed||isFinishing}) {entry->
                    val request=entry.request
                    if(EnginePackages.missing(this,setOf(request.kind)).isNotEmpty())throw ClientOptionalCoreRequired(request.kind)
                    OptionalCoreProcess.check(this,request,java.util.function.BooleanSupplier{isDestroyed||isFinishing})
                    bridges[entry.tag]=OptionalCoreConfig.bridgeOutbound(entry.tag,10900+bridges.size,java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),request.kind)
                }
                prepared=CoreTuningConfig.apply(plan.replace(bridges),coreSettings)
                require(vpn||tunSettings.mixedEnabled||tunSettings.customListeners().isNotEmpty()){ "В режиме прокси включите локальный listener." }
                prepared=ClientTunConfig.apply(prepared,tunSettings,proxyAuth,number).config
                ClientPrimarySelection.validateNative(this,prepared.toString(),vpn)
                } finally {engineLease.close()}
                ProfileStore(this).write(content); savedConfig = content
                check(prefs.edit().putBoolean("tun", vpn).putInt("port", number).putBoolean("applyRoutes", !diagnostic)
                    .apply {if(!diagnostic)putString("autoPolicy",sectionPolicy)}
                    .putBoolean("catalogueProfile", !diagnostic && catalogue.entries().isNotEmpty()).commit())
                ui { preparedProxyPort=number;busy = false; connect?.isEnabled = true; val consent = if (vpn) VpnService.prepare(this) else null; if (consent != null) startActivityForResult(consent, 1) else launchConnection() }
            } catch (error: Exception) {
                val primaryFailure=ClientPrimaryPreparationFailure.code(error)
                if(primaryFailure!=null)ClientEvents.record(this,"Движки","Подготовка основного движка: $primaryFailure")
                ui { busy = false; connect?.isEnabled = true
                    if(error is ClientOptionalCoreRequired)installEngine(error.kind)
                    else if(error is WdttMasqueConsentRequired)WdttMasqueUi.consent(this)
                    else alert("Подключение",when {
                        primaryFailure!=null->ClientPrimaryPreparationFailure.message(primaryFailure)
                        error is IllegalArgumentException->error.message.orEmpty()
                        else->"Не удалось сохранить или проверить профиль подключения."
                    })
                }
            } finally {ConnectTiming.mark(this,"ui_prepare",prepareAt)}
        }
    }
    private var connectionStartedNs=0L
    private fun launchConnection() {
        if(EngineUpdates.installationPending(this)||EngineUpdateScripts.mutationPending()){toast("Завершите обновление движка перед подключением");return}
        val launch=Intent(this, McrfVpnService::class.java).setAction(McrfVpnService.START).putExtra("tun", prefs.getBoolean("tun", true)).putExtra("port", prefs.getInt("port", 10808)).putExtra("applyRoutes", prefs.getBoolean("applyRoutes", true))
        launch.putExtra(ConnectTiming.EXTRA,connectionStartedNs);connectionStartedNs=0
        launch.putExtra("pendingWhitelists",pendingConnectionWhitelists?:findSelected()?.let {it.carrier!=null}?:false)
        preparedProxyPort?.let {launch.putExtra("preparedProxyPort",it)};preparedProxyPort=null
        startForegroundService(launch)
    }
    private fun refreshSelectedServer() {
        selectedServer?.text=findSelected()?.takeIf {ConnectionSections.belongs(it,false)}?.name?:"VPN-сервер не выбран"
        autoControlsRefresh?.invoke();captchaControlsRefresh?.invoke();pageRefresh?.invoke();updateHomeControl()
    }
    fun select(id: String) {
        if(working())return
        val entry=catalogue.entries().firstOrNull {it.id==id}?:return
        val action=ServerTapPolicy.action(selected,automatic,id,active(),McrfVpnService.selectedId)
        if(action.save) {
            if(!prefs.edit().remove("warpSelectionJournal").putString("selected",id).putBoolean("automatic",false).commit()) {
                alert("Подключение","Не удалось сохранить выбранный сервер.");return
            }
            selected=id;automatic=false
        }
        BsAutoSelectionPolicy.remember(prefs,entry)
        refreshSelectedServer()
        if(action.reconnect) {
            val request=serverSwitch.request(id,entry.carrier!=null,SystemClock.elapsedRealtime())
            startService(Intent(this,McrfVpnService::class.java).setAction(McrfVpnService.STOP).putExtra(ServerSwitchTransition.EXTRA,request.token))
            handler.post(serverSwitchPoll)
            updateHomeControl()
        }
    }
    fun selectRoutingGroup(id:String) {
        if(working()||McrfVpnService.cleanupPending()||ConnectionPresentation.pending(McrfVpnService.status))return
        if(RoutingGroups.active(routing.snapshot())==id)return
        val reconnect=active()
        var saved=false
        var missingEngine:String?=null
        var masqueConsent=false
        run(Operation {
            val state=routing.snapshot();RoutingGroups.select(state,id)
            if(reconnect) {
                val plan=RouteServerPlan(state,catalogue.entries())
                try {
                    RouteServerSession.check(this,plan,java.util.function.BooleanSupplier{isDestroyed||isFinishing})
                    if(plan.entries.any {it.carrier?.optBoolean("rt_masque")==true}) {
                        if(!WdttMasqueStore.accepted(this))throw WdttMasqueConsentRequired()
                        require(WdttMasqueStore.load(this)!=null) {"Для первой подготовки MASQUE отключите MCRF и подключитесь заново. Активная группа правил не изменена."}
                    }
                }catch(error:ClientOptionalCoreRequired){missingEngine=error.kind;return@Operation ""}
                catch(_:WdttMasqueConsentRequired){masqueConsent=true;return@Operation ""}
            }
            routing.save(state)
            saved=true
            "Группа правил переключена"
        },true,Runnable {
            missingEngine?.let {pendingRulesTile=0;installEngine(it);return@Runnable}
            if(masqueConsent){pendingRulesTile=0;WdttMasqueUi.consent(this);return@Runnable}
            if(saved)McrfQuickTile.refresh(this)
            if(saved&&reconnect&&active()) {
                startService(Intent(this,McrfVpnService::class.java).setAction(McrfVpnService.RELOAD_ROUTES))
            }
        })
    }
    fun setAutomatic(value: Boolean):Boolean {
        if(active()&&catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId}?.let { !ConnectionSections.belongs(it,connectionSectionWhitelists()) }==true){toast("Сначала отключи соединение в другом разделе");return false}
        automatic = value; prefs.edit().remove("warpSelectionJournal").putBoolean("automatic", value).apply()
        if (!value) {
            ProbeCoordinator.cancel(this)
            McrfVpnService.selectedId.takeIf { id -> active() && catalogue.entries().any { it.id == id } }?.let {
                selected = it; prefs.edit().putString("selected", it).apply()
            }
            if (active()) startService(Intent(this, McrfVpnService::class.java).setAction(McrfVpnService.AUTO_OFF))
        }
        return true
    }
    fun reconcileSelection(firstIfEmpty: Boolean) { val previous = selected.isNotEmpty(); if (findSelected() == null) selected = ""; if (firstIfEmpty && !previous && selected.isEmpty()) selected = catalogue.entries().firstOrNull()?.id.orEmpty(); prefs.edit().remove("warpSelectionJournal").putString("selected", selected).apply() }
    fun importDialog(initial: String = "") {
        if(working())return
        if(CompatibilityImportEncrypted.isEncrypted(initial.trim())) {
            CompatibilityImportUi.importEncrypted(this,initial.trim()){importDialog(it)};return
        }
        engineImport.show(initial)
    }
    fun installEngine(kind:String){if(!working())engineImport.showEngine(kind)}
    fun canOfferEngineUpdates()=!working()&&!isFinishing&&!isDestroyed&&hasWindowFocus()&&engineImport.state==null&&!onboardingVisible&&primaryChooser?.isShowing!=true&&carrierChallengeDialog?.isShowing!=true&&!EngineUpdates.installationPending(this)
    fun updateInstalledEngines(assets:List<EnginePackage>){
        if(working()||engineImport.state!=null)return
        worker.execute {
            try {
                val remaining=EngineStartupPolicy.stillNeeded(assets,EngineStartupUpdates.installed(applicationContext))
                    .filterNot {EnginePackages.installed(applicationContext,it)}
                ui {if(!working()&&engineImport.state==null){if(remaining.isEmpty())toast("Нет обновлений для установленных движков")else engineImport.showUpdates(remaining)}}
            } catch(_:Exception) {
                ui {alert("Обновления движков","Не удалось повторно проверить установленные движки. Пакеты не изменены; открой приложение повторно.")}
            }
        }
    }
    override fun onNewIntent(incoming: Intent) { super.onNewIntent(incoming); setIntent(incoming); if(!handleQuickTile()){receiveConfigLink(incoming); openPendingLink()} }
    private fun receiveConfigLink(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_VIEW) return
        val link = ImportLink.config(incoming.dataString)
        incoming.data = null
        if (link == null) { if (!loading) alert("Импорт", "Не удалось прочитать ссылку подключения."); return }
        pendingConfigLink = link
    }
    private fun openPendingLink() {
        if (working() || engineImport.state != null) return
        val value = pendingConfigLink ?: return
        pendingConfigLink = null; current = "Главная"; render(); engineImport.show(value, true)
    }
    fun refreshSubscriptions() = run(Operation {
        val changes=SubscriptionUpdates.refresh(this,catalogue)
        reconcileSelection(false)
        changes.summary()
    })
    fun protocols(): AlertDialog? = try {
        ProtocolsSheet.show(this,false,primaryHost)
    } catch (_: Exception) { alert("Протоколы", "Не удалось открыть таблицу протоколов."); null }
    fun interface Operation { @Throws(Exception::class) fun run(): String }
    /** Read-only UI preparation must not globally disable navigation/editors. */
    @JvmOverloads fun readPresentation(operation:Operation,completed:Runnable?=null){
        if(isFinishing||isDestroyed){completed?.run();return}
        try{presentationWorker.execute {
            try{val message=operation.run();ui {if(!isFinishing&&!isDestroyed){completed?.run();toast(message)}}}
            catch(error:Exception){ui {if(!isFinishing&&!isDestroyed){completed?.run();if(error !is java.util.concurrent.CancellationException)alert("Приложения","Не удалось загрузить список приложений. Попробуй ещё раз.")}}}
        }}catch(_:java.util.concurrent.RejectedExecutionException){completed?.run()}
    }
    @JvmOverloads fun run(operation: Operation, rebuild: Boolean = true, completed: Runnable? = null) {
        if (working()) { completed?.run(); return }; busy = true
        worker.execute { try { val message = operation.run(); ui { busy = false; if (rebuild) render(); completed?.run(); toast(message) } }
            catch (error: Exception) { ui { busy = false; render(); completed?.run(); if (error is java.util.concurrent.CancellationException) toast(error.message.orEmpty()) else alert("Настройки", if (error is IllegalArgumentException) error.message.orEmpty() else "Не удалось сохранить настройки. Исходные данные не изменены.") } } }
    }
    // Pool persistence must block competing edits, not masquerade as a VPN connection.
    private var poolSaveBusy=false
    fun saveAutoMark(id:String,value:Boolean,completed:Runnable):Boolean {
        if(working())return false
        poolSaveBusy=true
        worker.execute {
            try {catalogue.setAuto(id,value);ui {poolSaveBusy=false;completed.run();updateHomeControl()}}
            catch(error:Exception){ui {poolSaveBusy=false;completed.run();updateHomeControl();alert("Автовыбор",if(error is IllegalArgumentException)error.message.orEmpty()else"Не удалось сохранить отметку. Предыдущая настройка сохранена.")}}
        }
        return true
    }
    fun working() = busy || loading || poolSaveBusy || serverSwitch.target!=null
    fun probing()=probeBusy||ProbeCoordinator.measurements!=null
    fun measureServers(operation:Operation){
        if(loading||probing())return
        probeBusy=true
        probeWorker.execute {try{
            val message=operation.run();ui {probeBusy=false;pageRefresh?.invoke();toast(message)}
        }catch(error:Exception){ui {probeBusy=false;pageRefresh?.invoke();if(error is java.util.concurrent.CancellationException)toast("Замер остановлен")else alert("Замер серверов",if(error is IllegalArgumentException)error.message.orEmpty()else"Не удалось проверить серверы. Предыдущие результаты сохранены.")}}}
    }
    private fun ui(block: () -> Unit) { runOnUiThread { if (!isFinishing && !isDestroyed) block() } }
    fun toast(message: String) { if(message.isNotBlank())Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    fun share(label: String, value: String) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, label).putExtra(Intent.EXTRA_TEXT, value), "Поделиться")) }
    fun saveBackupDocument(file: java.io.File) {
        pendingBackupPath = file.absolutePath
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, "MCRF-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())}.mcrfbackup"), 7)
    }
    fun showQr(label: String, value: String) {
        try {
            val matrix = QrCodec.encode(value, 512)
            val pixels = IntArray(512*512) { i -> if (matrix[i%512, i/512]) Color.BLACK else Color.WHITE }
            val bitmap = Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
            val view = ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true; setPadding(dp(16), dp(16), dp(16), dp(16)); contentDescription = "QR-код $label" }
            ThemedDialogBuilder(this).setTitle(label).setView(view).setNegativeButton("Закрыть", null).setPositiveButton("Поделиться ссылкой") { _, _ -> share(label, value) }.create().apply {
                setOnDismissListener { view.setImageDrawable(null); bitmap.recycle() }; show()
            }
        } catch (_: Exception) { alert("QR-код", "Подключение слишком длинное для QR. Поделись ссылкой или файлом.") }
    }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 9 || request == 10 || EngineUpdateGate.isInstallerRequest(request)) engineImport.returned(request,result)
        if (request == 1 && result == RESULT_OK) launchConnection()
        if (request == 5 && result == RESULT_OK) { prefs.edit().putBoolean("tun", true).apply(); render(); prepareConnection(false) }
        if (request == 6 && result == RESULT_OK) data?.getStringExtra("qr")?.let { importDialog(it) }
        if (request == 7) {
            val file = BackupUi.cached(this, pendingBackupPath); pendingBackupPath = null
            if (file != null && result == RESULT_OK && data?.data != null) { val uri = data.data!!; run(Operation {
                try { val output = contentResolver.openOutputStream(uri, "wt") ?: throw IllegalArgumentException("Не удалось сохранить бэкап.")
                    output.use { destination -> file.inputStream().use { it.copyTo(destination) } }; "Бэкап сохранён" } finally { file.delete() }
            }, false) } else file?.delete()
        }
        if (request == 8 && result == RESULT_OK && data?.data != null) BackupUi(this).restore(data.data!!)
        if (request == 3 && result == RESULT_OK && data?.data != null) { val uri = data.data!!; var imported = ""; run(Operation { val bytes = contentResolver.openInputStream(uri)?.use(ImportIO::read) ?: throw IllegalArgumentException("Не удалось прочитать файл."); imported = String(bytes, Charsets.UTF_8); "Файл прочитан" }, false, Runnable { if (imported.isNotBlank()) importDialog(imported) }) }
    }
    override fun onSaveInstanceState(state: Bundle) { state.putString("page", current);state.putString("connectionPage",lastConnectionPage); state.putString("settingsOrigin",settingsOrigin);state.putString("advancedOrigin",advancedOrigin);state.putBoolean("settingsDirectEntry",settingsDirectEntry);state.putBoolean("returnToDeployVk",returnToDeployVk);state.putString("selectedDeployRecipe",selectedDeployRecipe); state.putInt("scroll", activeScroll?.scrollY ?: 0); state.putString("backupPath", pendingBackupPath); super.onSaveInstanceState(state) }
    private fun screenState():ScreenState {activeScroll?.let {scrollPositions[current]=it.scrollY};return ScreenState(current,scrollPositions.toMap(),engineImport.state,pendingConfigLink,settingsOrigin,settingsDirectEntry,lastConnectionPage,advancedOrigin)}
    override fun onRetainNonConfigurationInstance(): Any = screenState()
    override fun startActivityForResult(intent:Intent,requestCode:Int,options:Bundle?) {
        recentsDeparture.launched()
        try {super.startActivityForResult(intent,requestCode,options)}catch(error:Exception){recentsDeparture.launchFailed();throw error}
    }
    override fun onUserLeaveHint(){recentsDeparture.userLeave();super.onUserLeaveHint()}
    override fun onResume() { super.onResume();TelemetryCadence.resumed(this);handler.removeCallbacks(recentsPrivacyCheck);recentsDeparture.resumed();animationResumed=true;(connect as? ForgeConnectionButton)?.resumeAnimation(true); telegram.bind();handler.post(refresh);EngineUpdates.recover(this);if(!loading)EngineStartupUpdates.onOpen(this);ClientBehaviorUi.applyRecents(this);if(!loading&&ClientSettingsNavigation.isSettings(current))render() }
    override fun onPause() {TelemetryCadence.paused(this);ClientBehaviorUi.applyRecents(this);recentsDeparture.paused();animationResumed=false;(connect as? ForgeConnectionButton)?.resumeAnimation(false); telegram.unbind();pendingSettingsSave?.let { handler.removeCallbacks(it); it.run() }; handler.removeCallbacks(refresh); super.onPause();handler.postDelayed(recentsPrivacyCheck,250) }
    override fun onStop() {
        super.onStop()
        finishHiddenUiIfLeaving()
    }
    private fun finishHiddenUiIfLeaving() {
        val hidden=try{ClientBehaviorStore.load(this).hideRecents}catch(_:Exception){false}
        if(recentsDeparture.removeUi(hidden,isChangingConfigurations,isFinishing,isInMultiWindowMode)) {
            // RecentTasks keeps the top excluded task. Finish ONLY its UI after a
            // background pause as well (Samsung Overview can omit userLeaveHint).
            // The foreground VPN service has stopWithTask=false. Own pickers and
            // configuration changes remain protected; resume cancels delayed work.
            // Keep navigation in process memory, never rewrite profiles/preferences.
            hiddenScreenState=screenState()
            finishAndRemoveTask()
        }
    }
    override fun onDestroy() {
        AppUpdateInstallUi.close(this)
        serverSwitch.cancel();handler.removeCallbacks(serverSwitchPoll)
        presentationWorker.shutdownNow()
        startupAccessPending=null;startupAccessWorker.shutdownNow()
        // An unchosen prompt is not a solved/cancelled challenge. A replacement
        // Activity may offer it again; never retain a window from the old owner.
        carrierChallengePromptId?.let {CarrierChallenge.get(it)?.opened=false}
        carrierChallengeDialog?.dismiss();carrierChallengeDialog=null;carrierChallengePromptId=null
        routingScreenCache.clear();warpController?.detach();warpController=null;deployUi?.detach();deployUi=null;ProbeCoordinator.unobserve(probeRefresh);handler.removeCallbacksAndMessages(null);worker.shutdown();probeWorker.shutdown();super.onDestroy()
    }
    fun alert(title: String, message: String) { if (!isFinishing && !isDestroyed) ThemedDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    fun text(value: String, size: Int, color: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding = false }
    fun flat(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply { setColor(if(color==Loft.SURFACE||color==Loft.FIELD)Loft.translucent(color)else color); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(1), stroke) }
    fun icon(name: String, color: Int): Drawable = Loft.Icon(name, color, resources.displayMetrics.density)
    fun iconButton(name: String, description: String, glyphSize:Int=22): Button {
        val glyph = icon(name, Loft.MUTED)
        return styleButton(object : Button(this) { override fun onDraw(canvas: Canvas) { super.onDraw(canvas); val size = dp(glyphSize); val x = (width-size)/2; val y = (height-size)/2; glyph.setBounds(x, y, x+size, y+size); glyph.alpha = if (isEnabled) 255 else 85; glyph.draw(canvas) } }, "", false).apply {
            contentDescription = description; setPadding(0, 0, 0, 0); background = RippleDrawable(ColorStateList.valueOf(Loft.ripple()), flat(Color.TRANSPARENT, 14, 0), flat(Color.WHITE, 14, 0)) }
    }
    fun button(label: String, accent: Boolean) = if(label=="i")iconButton("info","Справка",20)else styleButton(Button(this), label, accent)
    private fun styleButton(button: Button, label: String, accent: Boolean) = StandardButtons.style(this,button,label,accent)
    fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); background = Loft.Glass(resources.displayMetrics.density, 20f) }
    fun section(parent: LinearLayout, label: String) { parent.addView(text(label, 10, Loft.MUTED).apply { letterSpacing = .1f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, space(0, 12)) }
    fun divider(parent: LinearLayout, top: Int, bottom: Int) { parent.addView(View(this).apply { setBackgroundColor(Loft.LINE) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(top); bottomMargin = dp(bottom) }) }
    fun tune(control: Switch) { control.setTextColor(Loft.INK); control.textSize = 14f; control.minHeight = dp(48); control.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Loft.ACCENT, Loft.MUTED)); control.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Loft.mix(Loft.SURFACE,Loft.ACCENT,.38f), Loft.LINE)); control.switchPadding = dp(12) }
    fun editor(hint: String) = EditText(this).apply { this.hint = hint; setHintTextColor(Loft.MUTED); setTextColor(Loft.INK); textSize = 14f; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; typeface=Typeface.create("sans-serif",Typeface.NORMAL);includeFontPadding=false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS; setPadding(dp(12), dp(12), dp(12), dp(12)); background = flat(Loft.FIELD, 12, Loft.LINE); minimumHeight = dp(48) }
    fun number(value: Int) = editor("").apply { setSingleLine(true); inputType = InputType.TYPE_CLASS_NUMBER; setText(value.toString()) }
    fun field(label: String, control: View) = LinearLayout(this).apply {
        if(control is Spinner){
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false;minimumHeight=dp(48)
            addView(text(label,13,Loft.INK).apply {gravity=Gravity.CENTER_VERTICAL;maxLines=2},LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
            addView(control,LinearLayout.LayoutParams(0,dp(48),1.2f))
        }else {orientation=LinearLayout.VERTICAL;addView(text(label,12,Loft.MUTED));addView(control,space(8,16))}
    }
    fun space(top: Int, bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    fun spinnerAdapter(labels: Array<String>): ArrayAdapter<String> = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels) {
        private fun style(view: View, dropdown: Boolean) = (view as TextView).apply { setTextColor(Loft.INK); textSize = 13f; minHeight = dp(48); gravity = Gravity.CENTER; maxLines = 2; includeFontPadding=false;ellipsize = TextUtils.TruncateAt.END;setPadding(0,0,0,0); if (dropdown) { setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Loft.SURFACE) } }
        override fun getView(position: Int, previous: View?, parent: ViewGroup) = style(super.getView(position, previous, parent), false)
        override fun getDropDownView(position: Int, previous: View?, parent: ViewGroup) = style(super.getDropDownView(position, previous, parent), true)
    }
    fun picker(labels: Array<String>, index: Int):Spinner = object:Spinner(this,Spinner.MODE_DROPDOWN){
        private var choices:android.widget.PopupWindow?=null
        override fun performClick():Boolean {
            if(!isEnabled)return false
            choices?.dismiss()
            val current=adapter?:return false
            choices=DropdownPopup.show(this@MainActivity,this,null,(0 until current.count).map {DropdownPopup.Item(current.getItem(it).toString())},selectedItemPosition){setSelection(it)}
            return true
        }
        override fun onDetachedFromWindow(){choices?.dismiss();choices=null;super.onDetachedFromWindow()}
    }.apply {adapter=spinnerAdapter(labels);setSelection(max(0,index));minimumHeight=dp(48);setPadding(dp(8),0,dp(28),0);background=LayerDrawable(arrayOf(DropdownStyle.decoration(this@MainActivity),Chevron()));setPopupBackgroundDrawable(Loft.popupBackground(this@MainActivity))}
    fun selectedListener(selected: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener { override fun onNothingSelected(parent: AdapterView<*>?) = Unit; override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = selected(position) }
    private inner class Chevron : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) { val x = (bounds.right-dp(18)).toFloat(); val y = bounds.exactCenterY(); paint.color = Loft.MUTED; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; canvas.drawPath(Path().apply { moveTo(x-dp(4), y-dp(2)); lineTo(x, y+dp(2)); lineTo(x+dp(4), y-dp(2)) }, paint) }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }; override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }; override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

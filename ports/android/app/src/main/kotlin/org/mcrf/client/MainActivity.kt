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

internal object CommunityUpdateNotice {
    const val VERSION="communityNoticeVersionCode"
    const val INSTALLATION="communityNoticeFirstInstallTime"
    private const val FIRST_V2_CODE=10065

    fun shouldShow(versionName:String,seen:Int,installation:Long,seenInstallation:Long,updated:Long):Boolean {
        if(installation<=0||updated<installation)return false
        val seenOnThisInstall=installation==seenInstallation
        if(updated==installation)return !seenOnThisInstall
        val isV2=versionName=="2.0"||versionName.startsWith("2.0.")||versionName.startsWith("2.0-")
        return isV2&&(!seenOnThisInstall||seen<FIRST_V2_CODE)
    }
}

class MainActivity : Activity() {
    private val tvUi by lazy { TvUi.enabled(this) }
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
    private var serviceTestsUi: ServiceTestsUi? = null
    private var serviceDiagnosticsUi: ServiceDiagnosticsUi? = null
    internal val resourceMonitor = ResourceMonitorController()
    @Volatile private var probeBusy=false
    private lateinit var shell: LinearLayout
    private var bottomNavigation: BottomNavigationView? = null
    private val layoutEditor by lazy {UiConstructor(this)}
    private var edgePanelScroll=0
    internal fun canEditInterface()=settingsExperience().first==ExperienceMode.EXPERT
    internal fun openInterfaceConstructor(){if(canEditInterface())layoutEditor.start()}
    internal fun bindConstructorDialog(content:LinearLayout,scope:String){
        layoutEditor.bind(content,scope)
    }
    internal fun constructorDialogControl(scope:String):View?=
        if(layoutEditor.editing)layoutEditor.levelControl(scope) else null
    internal fun enforceConstructorVisibility(){layoutEditor.enforceHidden()}
    private lateinit var page: LinearLayout
    private var current = "Главная"
    @Volatile private var savedConfig = ""
    private var loading = true; private var busy = false
    private val deferredNavigation=DeferredNavigation()
    private var source: EditText? = null; private var port: EditText? = null; private var tun: Switch? = null
    private var status: TextView? = null; private var connectionHint: TextView? = null
    private var connectionProgress:ConnectionStatusProgress?=null
    private var connectionIndicator:ConnectionIndicatorView?=null
    private var isolationUpgradeNotice:TextView?=null
    private var connectionPreparationError:String?=null
    private var connectionTarget:TextView?=null
    private var connectionAddress:TextView?=null
    private var connectionStreams:TextView?=null
    private var orbitRoutingLabel:((String)->String)?=null
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
    private var anubisProviderPrompted=false
    private var lastConnectionPage="Главная"
    private var connectionDock:ConnectionControlsDock?=null
    private var connectionToolbar:LinearLayout?=null
    private var miniature: EdgeConnectionControls? = null
    internal val edgeConnectionState=EdgeConnectionState()
    private var miniatureShown = false
    private lateinit var viewport: FrameLayout
    private var serversPanel: LinearLayout? = null
    private var settingsOrigin = "Главная"
    private var lastSettingsPage=ClientSettingsNavigation.ROOT
    private var systemBackCallback:android.window.OnBackInvokedCallback?=null
    private var advancedOrigin=ClientSettingsNavigation.ROOT
    private var settingsDirectEntry=false
    private var returnToDeployVk=false
    var selectedDeployRecipe: String?=null
    private var onboardingVisible = false
    private var communityUpdateDialog:AlertDialog?=null
    private var homeLayout: HomeLayout? = null; private var modeSwitch: ModeSwitch? = null; private var modeHint: TextView? = null
    private lateinit var telegram:TelegramBridge
    private var telegramControls:TelegramControls?=null
    var pageRefresh: (() -> Unit)? = null
    var autoControlsRefresh: (() -> Unit)? = null
    private var selectionPolicyRefresh: (() -> Unit)? = null
    private var captchaControlsRefresh: (() -> Unit)? = null
    private var directTunControlsRefresh: (() -> Unit)? = null
    private var catalogueRefreshInFlight=false
    private var lastCatalogueRefresh=0L
    private var catalogueRefreshRequested=true
    private var catalogueProfileRefreshRequested=false
    private val catalogueRefreshDelivery=CatalogueRefreshDelivery()
    private val probeRefresh: () -> Unit = {ui {pageRefresh?.invoke();updateHomeControl()}}
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
    private var pendingPrimaryInstallKind:String?=null
    private val bulkEngineRemoval=ArrayDeque<String>()
    private var bulkEngineRemovalRequest=0
    private val primaryHost:EngineUpdatePrimaryUi.Host=object:EngineUpdatePrimaryUi.Host {
        override fun selected()=ClientPrimarySelection.selected(this@MainActivity)
        override fun confirmationRequired()=!prefs.getBoolean(ClientPrimarySelection.DUAL_CONFIRMED,false)
        override fun validateFullSnapshot(choiceId:String):List<String> {
            // The actual full snapshot and installed-native gate run asynchronously below.
            return emptyList()
        }
        override fun selectValidated(choiceId:String)=selectValidated(choiceId) {}
        override fun selectValidated(choiceId:String,onSelected:()->Unit) {
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
                        val generation=routing.generation()
                        val result=ClientPrimarySelection.steerSnapshot(this@MainActivity,routing.snapshot(),prefs.getBoolean("tun",true),true,
                            prepareCachedGeo={!isDestroyed&&!isFinishing&&routing.generation()==generation})
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
                        onSelected()
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
                                        ui {busy=false;if(!isFinishing&&!isDestroyed)primaryHost.selectValidated(choiceId,onSelected)}
                                    }catch(failure:Exception){ui {busy=false;alert("Основной движок",failure.message?:"Не удалось сохранить согласие.")}}
                                }
                            }
                        }.show()
                }}catch(e:PresetAssets.DownloadRequired){ui {
                    busy=false
                    offerPresetDownload(e){primaryHost.selectValidated(choiceId,onSelected)}
                }}catch(e:Exception){ui {busy=false;alert("Основной движок",e.message?:"Не удалось проверить движок.")}}
            }
        }
    }
    private fun offerPrimaryChoice(force:Boolean=false,startup:Boolean=false) {
        if(loading||isFinishing||isDestroyed||primaryChooser?.isShowing==true||(!force&&ClientPrimarySelection.selected(this)!=null))return
        var confirmDraft:(()->Unit)?=null
        val content=EngineUpdatePrimaryUi.updatesView(this,primaryHost,firstLaunch=true,onDraft={confirmDraft=it},optionalOnboarding=startup)
        primaryChooser=ThemedDialogBuilder(this,compactActions=startup)
            .setView(CompactUi.scroll(this,content,.72f).apply {
                tag="primary-choice-scroll";setPadding(dp(20),dp(20),dp(20),dp(4));isVerticalScrollBarEnabled=false
            })
            .setPositiveButton("Выбрать",null).apply {
                if(startup)setNegativeButton("Не нужно") {dialog,_->
                    prefs.edit().putBoolean("primaryOnboardingDeclined",true).apply()
                    dialog.dismiss()
                    continueStartupChoices()
                }else setNegativeButton("Позже",null)
            }.create().also {dialog->
                dialog.setOnDismissListener {primaryChooser=null;pendingPrimaryConnection=null}
                dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    tag="primary-choice-confirm";setOnClickListener {if(!working())confirmDraft?.invoke()}
                }}
                dialog.show()
            }
    }
    private fun startupChoices() {
        if(RuAppTunnelNotice.handle(this))return
        if(!tvUi&&offerCommunityUpdateNotice())return
        val fresh=savedConfig.isBlank()&&catalogue.entries().isEmpty()&&ClientPrimarySelection.selected(this)==null
        val testing=BuildConfig.DEBUG&&intent.getBooleanExtra("test_no_prompts",false)
        if(!testing&&UsageFeatures.needsFirstChoice(prefs.getString(UsageFeatures.KEY,null),prefs.getBoolean(UsageFeatures.ASKED,false),fresh)) {
            onboardingVisible=true
            usageChooser=UsageFeaturesUi.firstLaunch(this){usageChooser=null;onboardingVisible=false;continueStartupChoices()}
        }else continueStartupChoices()
    }
    private fun offerCommunityUpdateNotice():Boolean {
        if(isFinishing||isDestroyed||BuildConfig.DEBUG&&intent.getBooleanExtra("test_no_prompts",false))return false
        if(communityUpdateDialog?.isShowing==true)return true
        val info=runCatching {packageManager.getPackageInfo(packageName,0)}.getOrNull()?:return false
        val installation=info.firstInstallTime
        val seenInstallation=prefs.getLong(CommunityUpdateNotice.INSTALLATION,-1)
        val seen=prefs.getInt(CommunityUpdateNotice.VERSION,-1)
        val show=CommunityUpdateNotice.shouldShow(BuildConfig.VERSION_NAME,seen,installation,seenInstallation,info.lastUpdateTime)
        if(!show)return false
        if(!prefs.edit().putLong(CommunityUpdateNotice.INSTALLATION,installation)
                .putInt(CommunityUpdateNotice.VERSION,BuildConfig.VERSION_CODE).commit())return false
        onboardingVisible=true
        communityUpdateDialog=ThemedDialogBuilder(this).setTitle("Сообщество MCRF")
            .setMessage("В Telegram — новости, помощь и обсуждение MCRF.")
            .setPositiveButton("Перейти"){_,_->McrfCommunityLink.open(this)}
            .setNegativeButton("Закрыть",null).show().also {dialog->
                dialog.setOnDismissListener {communityUpdateDialog=null;onboardingVisible=false;startupChoices()}
            }
        return true
    }
    private fun continueStartupChoices() {
        if(isFinishing||isDestroyed)return
        val pending=pendingPrimaryInstallKind
        if(ClientPrimarySelection.selected(this)==null&&(pending!=null||!prefs.getBoolean("primaryOnboardingDeclined",false)&&!selectedConnectionHasOwnEngine())){
            if(pending!=null&&EnginePackages.missing(this,setOf(pending)).isEmpty())engineInstallConfirmed(pending)else offerPrimaryChoice(startup=pending==null)
            return
        }
        batteryPrompt()
        AppUpdates.onStart(this)
        resumeWantedOnLaunch()
        checkStartupAccess()
    }

    private fun selectedConnectionHasOwnEngine():Boolean {
        if(automatic)return false
        val entry=findSelected()?:return false
        val vpn=UsageFeatures.preferredVpn(UsageFeaturesUi.selected(this),prefs.getBoolean("tun",true))
        return entry.carrier?.let {BsNativeContract.connectionHint(this,it,vpn)}
            ?: (WgAddonPolicy.standaloneProfile(entry,vpn,false)&&WgAddonPolicy.standaloneHint(this)||
                StandaloneRouterSelection.hint(this,entry,vpn))
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
            alert("Возможны белые списки","Оба выбранных сайта не ответили напрямую. Возможно, сейчас работают белые списки; также проверь наличие сети. Текущее подключение не изменено.")
        }
    }
    private val scrollPositions = mutableMapOf<String, Int>()
    private var observedNetworkProfile=NetworkProfilesRuntime.revision
    private var observedWidgetSelection=McrfHomeWidget.selectionRevision
    private data class ScreenState(val page: String, val positions: Map<String, Int>, val pendingImport: EngineImportUi.State?, val pendingLink: String?, val settingsOrigin: String,val settingsDirectEntry:Boolean,val connectionPage:String,val advancedOrigin:String)
    private companion object {var hiddenScreenState:ScreenState?=null}
    private val refresh = object : Runnable {
        override fun run() {
            offerStartupAccess()
            if(!loading&&observedNetworkProfile!=NetworkProfilesRuntime.revision) {
                observedNetworkProfile=NetworkProfilesRuntime.revision
                selected=prefs.getString("selected","").orEmpty();automatic=prefs.getBoolean("automatic",false)
                catalogueRefreshRequested=true;catalogueProfileRefreshRequested=true
            }
            if(!working()&&observedWidgetSelection!=McrfHomeWidget.selectionRevision){
                observedWidgetSelection=McrfHomeWidget.selectionRevision
                selected=prefs.getString("selected","").orEmpty()
                automatic=prefs.getBoolean("automatic",false)
                refreshSelectedServer()
            }
            deliverCatalogueRefresh()
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
            if(!loading&&!catalogueRefreshInFlight&&(catalogueProfileRefreshRequested||
                current in setOf("Главная",ClientSettingsNavigation.WHITELISTS)&&!working()&&
                (catalogueRefreshRequested||SystemClock.elapsedRealtime()-lastCatalogueRefresh>=3000))) {
                val profileChanged=catalogueProfileRefreshRequested
                catalogueRefreshRequested=false;catalogueProfileRefreshRequested=false
                catalogueRefreshInFlight=true;lastCatalogueRefresh=SystemClock.elapsedRealtime()
                worker.execute {
                    val changed=try {catalogue.refreshPresentationFromStorage()}catch(_:Exception){null}
                    ui {
                        catalogueRefreshInFlight=false
                        if(changed!=null)catalogueRefreshDelivery.changed(changed,profileChanged)
                        else if(profileChanged)catalogueProfileRefreshRequested=true
                        deliverCatalogueRefresh()
                    }
                }
            }
            openPendingLink()
            if(engineImport.state==null&&!onboardingVisible){AppUpdates.offerPending(this@MainActivity);EngineStartupUpdates.offerPending(this@MainActivity)}
            // Native automatic deadlines can expire while this prompt is visible.
            // Retire that window so it cannot starve later live challenges.
            carrierChallengePromptId?.let {id->
                val live=if(id.startsWith("openflux:"))OpenFluxVerificationRequests.registry.get(id.removePrefix("openflux:"))!=null else CarrierChallenge.get(id)!=null
                if(!live)carrierChallengeDialog?.dismiss()
            }
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
            if(carrierChallengeDialog?.isShowing!=true&&!onboardingVisible)OpenFluxVerificationRequests.registry.unopened(includeInline=true,includeHttp=OpenFluxHttpVerificationPolicy.productionEnabled)?.let {request->
                request.opened.set(true)
                val promptId="openflux:${request.id}"
                carrierChallengePromptId=promptId
                fun cancelFlux(){OpenFluxVerificationRequests.registry.cancel(request.id);OpenFluxVerificationUi.cancel(this@MainActivity,request.id)}
                carrierChallengeDialog=ThemedDialogBuilder(this@MainActivity).setTitle("Проверка OpenFlux")
                    .setMessage(if(request.http) "Скрипт запросил свою страницу проверки.\n\nСтраница открывается вручную в отдельном окне. Запросы передаются только текущему обработчику ядра; прямой доступ страницы к сети отключён. Внешние ресурсы и cookie-сессии не поддерживаются. Ответ передаётся отдельной кнопкой; выход отменяет проверку."
                        else if(request.inline) "Провайдер запросил встроенную проверку.\n\nСтраница открывается вручную в отдельном окне без доступа к сети. Ответ передаётся только кнопкой «Отправить». При выходе из окна незавершённая проверка отменяется; потребуется новый запрос."
                        else "Провайдер запросил ручную проверку.\n\nСайт: ${request.page.origin}\n\nВ отдельном окне сначала можно проверить адрес, затем открыть страницу.")
                    .setNegativeButton("Отмена"){_,_->cancelFlux()}
                    .setPositiveButton("Открыть"){_,_->
                        try{startActivity(OpenFluxVerificationUi.intent(this@MainActivity,request.id,request.inline,request.http))}
                        catch(_:Exception){
                            if(request.http){cancelFlux();alert("OpenFlux","Не удалось открыть окно текущей проверки. Нужен новый запрос; повторное открытие старой страницы запрещено.")}
                            else{OpenFluxVerificationRequests.registry.reopenAfterRecreate(request.id);alert("OpenFlux","Не удалось открыть окно текущей проверки. Запрос сохранён до истечения его времени.")}
                        }
                    }
                    .setOnCancelListener{cancelFlux()}.show().also {dialog->
                        dialog.setOnDismissListener {if(carrierChallengePromptId==promptId){carrierChallengeDialog=null;carrierChallengePromptId=null}}
                    }
            }
            handler.postDelayed(this, 750)
        }
    }
    /** Debug diagnostics only: fixed phase names and monotonic durations, never saved values or exceptions. */
    private fun startupTiming(stage: String, startedNs: Long) {
        if (!BuildConfig.DEBUG) return
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L
        runCatching { android.util.Log.d("MCRF_STARTUP", "$stage=${elapsedMs}ms") }
    }
    private inline fun <T> startupStage(stage: String, action: () -> T): T {
        if (!BuildConfig.DEBUG) return action()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        try { return action() } finally { startupTiming(stage, startedNs) }
    }
    private fun startupFirstDraw(startedNs: Long) {
        if (!BuildConfig.DEBUG) return
        val renderedNs = SystemClock.elapsedRealtimeNanos()
        val tree = shell.viewTreeObserver
        tree.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (tree.isAlive) tree.removeOnPreDrawListener(this)
                startupTiming("ui_first_predraw", renderedNs)
                return true
            }
        })
        tree.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var posted = false
            override fun onDraw() {
                if (posted) return
                posted = true
                // Removal during onDraw is forbidden. Post also waits until this actual frame has drawn.
                shell.post {
                    if (tree.isAlive) tree.removeOnDrawListener(this)
                    if (!isFinishing && !isDestroyed) {
                        startupTiming("ui_first_draw_ready", startedNs)
                        startupTiming("process_first_draw_ready", android.os.Process.getStartElapsedRealtime() * 1_000_000L)
                    }
                }
            }
        })
        shell.invalidate()
    }
    override fun onCreate(state: Bundle?) {
        requestedOrientation=if(tvUi)android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ClientSettingsRegistry.register()
        val restoreProblem = try { AndroidBackup.applyRestoredPreferences(this); false } catch (_: Exception) { true }
        if(!prefs.getBoolean(ConnectionAppearance.MERGED_DEFAULT_APPLIED,false)){
            prefs.edit().putBoolean(ConnectionAppearance.MERGED,true)
                .putBoolean(ConnectionAppearance.MERGED_DEFAULT_APPLIED,true).apply()
        }
        // Decide migration before first-launch defaults create durable settings.
        runCatching {ExperienceModeStore(this).load()}
        if(!tvUi)HomeUiTheme.applyV2Defaults(prefs)
        UserThemeColorsUi.configure(this)
        applyAppearancePalette()
        super.onCreate(state); catalogue = Connections(this); routing = RoutingStore(this)
        layoutEditor.restore(state?.getString("constructorDraft"))
        engineImport = EngineImportUi(this)
        telegram=TelegramBridge(this){telegramControls?.update();miniature?.updateTelegram()}
        receiveConfigLink(intent)
        pendingBackupPath = state?.getString("backupPath")
        pendingPrimaryInstallKind=prefs.getString("pendingPrimaryInstallKind",null)
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
        lastSettingsPage=state?.getString("lastSettingsPage")?.takeIf {ClientSettingsNavigation.isSettings(it)&&it!=ClientSettingsNavigation.INVITE}?:lastSettingsPage
        retained?.let { current = it.page; scrollPositions.putAll(it.positions); pendingConfigLink = it.pendingLink ?: pendingConfigLink; settingsOrigin = it.settingsOrigin }
        val restoredPage=current
        current = when (current) { "Серверы" -> "Главная"; "Журнал" -> "Логи";else->ClientSettingsNavigation.canonical(current) }
        if(tvUi&&!TvUi.settingsVisible(this,current))current=ClientSettingsNavigation.ROOT
        if(current!=restoredPage)scrollPositions.remove(restoredPage)?.let {scrollPositions[current]=it}
        settingsOrigin=ClientSettingsNavigation.canonical(settingsOrigin)
        shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Loft.Backdrop(); isFocusableInTouchMode = !tvUi; clipChildren = false; clipToPadding = false }
        SystemBarsUi.apply(this)
        shell.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) { val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); val keyboard = insets.getInsets(WindowInsets.Type.ime()); view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, keyboard.bottom)) }
            else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(shell)
        ColdLaunchSwarm.begin(this, shell)
        TvUi.outlineFocus(shell)
        render(); shell.requestApplyInsets();ProbeCoordinator.observe(probeRefresh)
        if(BuildConfig.DEBUG&&packageName.endsWith(".ui20261120")&&intent.getBooleanExtra("mcrf_motion_preview",false)) {
            intent.removeExtra("mcrf_motion_preview")
            shell.postDelayed({
                if(!isFinishing&&!isDestroyed&&shell.isAttachedToWindow) {
                    runCatching {
                        Class.forName("org.mcrf.client.DebugMotionPreview",true,classLoader)
                            .getMethod("show",MainActivity::class.java).invoke(null,this)
                    }.onFailure {alert("Предпросмотр","Не удалось открыть тестовый предпросмотр.")}
                }
            },5000)
        }
        // Measure the saved-connections loading window, not ActivityTotal or time before the loading UI.
        val startupQueuedNs = if (BuildConfig.DEBUG) SystemClock.elapsedRealtimeNanos() else 0L
        worker.execute {
            startupTiming("load_queue", startupQueuedNs)
            try {
                if (restoreProblem) throw java.io.IOException("Pending restore preferences could not be applied")
                // Install the preference observer before startup/default writers; snapshots stay off UI.
                startupStage("history_observer") { runCatching {ChangeHistoryStore.local(applicationContext)} }
                startupStage("catalogue_load") { catalogue.load() }
                startupTiming("catalogue_ready", startupQueuedNs)
                startupStage("recover_warp") { recoverWarpSelection() }
                startupStage("routing_load") { routing.load() }
                var saved = startupStage("profile_read") { ProfileStore(this).read() }
                startupStage("local_proxy_settings") { LocalProxySettings.value(this) }
                val direct = BuildConfig.DEBUG && saved.isEmpty() && intent.getBooleanExtra("test_direct", false)
                if (direct) saved = assets.open("direct.json").use { String(ImportIO.read(it), Charsets.UTF_8) }
                savedConfig = saved
                val startupUiQueuedNs = if (BuildConfig.DEBUG) SystemClock.elapsedRealtimeNanos() else 0L
                ui { loading = false;
                    startupTiming("ui_queue_wait", startupUiQueuedNs)
                    startupStage("ui_render") {
                        val requested=deferredNavigation.take(); if(requested!=null&&!direct)navigate(requested)else render()
                    }
                    shell.post { ColdLaunchSwarm.pageReady(this) }
                    startupFirstDraw(startupQueuedNs)
                    retained?.pendingImport?.let(engineImport::attach)
                    if (direct) {
                        openDiagnosticPage(); tun?.isChecked = true; prepareConnection(true)
                    } else if (!handleQuickTile()) {
                        ColdLaunchSwarm.afterReveal(this) {
                            if (BuildConfig.DEBUG) startupTiming(
                                "process_interface_revealed", android.os.Process.getStartElapsedRealtime() * 1_000_000L
                            )
                            startupChoices()
                            EngineStartupUpdates.onOpen(this)
                        }
                    }
                }
            } catch (_: Exception) { ui { loading = false; deferredNavigation.take(); render(); alert("Подключения", "Не удалось открыть сохранённые подключения. Исходные файлы не изменены.") };return@execute }
            // The saved UI does not depend on optional job/package scheduling. Keep
            // these failures separate from a catalogue/route read failure.
            runCatching {RemoteRoutingSchedule.cancelRetired(this)}
            runCatching {SubscriptionUpdates.initialize(this,prefs)}
            if(!(BuildConfig.DEBUG&&intent.getBooleanExtra("test_direct",false)))
                runCatching {SubscriptionOpenRefresh.onOpen(this,catalogue.subscriptions().isNotEmpty())}
            runCatching {EngineUpdateSchedule.schedule(this);EngineUpdates.recover(this)}
        }
    }
    private fun deliverCatalogueRefresh() {
        val update=catalogueRefreshDelivery.take(
            animationResumed&&!loading&&!working()&&!catalogueProfileRefreshRequested&&!catalogueRefreshInFlight,
            ConnectionAppearance.isConnection(current))
        when(update) {
            CatalogueRefreshDelivery.Update.PAGE->render()
            CatalogueRefreshDelivery.Update.SERVERS->{
                refreshConnectionServers(connectionSectionWhitelists())
                captchaControlsRefresh?.invoke();updateHomeControl()
            }
            CatalogueRefreshDelivery.Update.NONE->Unit
        }
    }
    fun render() {
        val previousFocusTag=if(tvUi&&renderedPage==current)shell.findFocus()?.tag as? String else null
        layoutEditor.beginRender()
        updateSystemBack()
        SystemBarsUi.apply(this)
        val shownFeatures=UsageFeaturesUi.selected(this)
        if(!UsageFeatures.visible(current,shownFeatures))current=when {
            UsageFeatures.visible("Главная",shownFeatures)->"Главная"
            UsageFeatures.whitelists(shownFeatures)->ClientSettingsNavigation.WHITELISTS
            UsageFeatures.Feature.WARP in shownFeatures->"WARP"
            else->"Настройки"
        }
        if(current!="Логи"&&!ClientSettingsNavigation.visible(current,settingsExperience().first))current=ClientSettingsNavigation.ROOT
        if(ClientSettingsNavigation.isRoutes(current)) {
            val routePage=ClientSettingsNavigation.routePage(current)
            if(prefs.getString(ClientSettingsNavigation.ROUTES_PAGE_KEY,null)!=routePage)
                prefs.edit().putString(ClientSettingsNavigation.ROUTES_PAGE_KEY,routePage).apply()
        }
        if(current==ClientSettingsNavigation.ROOT)settingsDirectEntry=false
        deployUi?.detach();deployUi=null
        if(ConnectionAppearance.isConnection(current))lastConnectionPage=current
        pendingSettingsSave?.let { handler.removeCallbacks(it); it.run() }; pendingSettingsSave = null
        shell.background = Loft.rootBackground(this)
        val animatePage = renderedPage != null && renderedPage != current && renderedPage!=ClientSettingsNavigation.VK
        renderedPage?.let { name -> activeScroll?.let { scrollPositions[name] = it.scrollY } }
        renderedPage = current
        renderedLoading=loading
        // Keep the navigation attached so page renders cannot cancel its transition.
        for(index in shell.childCount-1 downTo 0)if(shell.getChildAt(index)!==bottomNavigation)shell.removeViewAt(index)
        shell.clipChildren=false
        source = null; port = null; tun = null; connect = null; status = null; connectionHint = null;connectionTarget=null;connectionAddress=null;connectionStreams=null;orbitRoutingLabel=null;connectionProgress=null;connectionIndicator=null;isolationUpgradeNotice=null
        connectionDock?.close();connectionDock=null;connectionToolbar=null
        miniature?.let {edgePanelScroll=it.scrollOffset();it.animate()?.cancel()}; miniature = null; miniatureShown = false; serversPanel = null
        selectedServer = null; homeLayout = null; modeSwitch = null; modeHint = null
        telegramControls=null
        pageRefresh=null;autoControlsRefresh=null;selectionPolicyRefresh=null;captchaControlsRefresh=null;directTunControlsRefresh=null
        val scroll = object : ScrollView(this) { override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val available=MeasureSpec.getSize(widthSpec)
            val width=if(tvUi)available else min(available,dp(720))
            homeLayout?.fit(width-page.paddingLeft-page.paddingRight, MeasureSpec.getSize(heightSpec)-page.paddingTop-page.paddingBottom)
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec)
        } }
        activeScroll = scroll; scroll.isFillViewport = true; scroll.clipToPadding = false; scroll.isVerticalScrollBarEnabled = false; scroll.overScrollMode = View.OVER_SCROLL_NEVER
        val pinned=isEdgePanelPinned()
        val pinnedLeft=pinned&&EdgePanelAppearance.left(prefs)
        fun viewportContent()=FrameLayout.LayoutParams(-1,-1).apply {
            gravity=if(pinned)Gravity.LEFT else Gravity.CENTER_HORIZONTAL
            if(pinnedLeft)leftMargin=dp(64) else if(pinned)rightMargin=dp(64)
        }
        page = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            tag="screen-$current"
            val connectionPage=ConnectionAppearance.isConnection(current)
            val fullOrbit=connectionPage&&orbitEnabled()&&!loading
            val horizontal=if(tvUi)32 else if(fullOrbit)0 else if(pinned)12 else if(connectionPage)16 else 24
            setPadding(dp(horizontal),dp(if(fullOrbit)0 else if(connectionPage)6 else 14),dp(horizontal),dp(if(fullOrbit)0 else if(connectionPage)6 else 24))
            isFocusableInTouchMode=!tvUi
        }
        scroll.addView(page)
        viewport = FrameLayout(this).apply { clipToPadding=false; addView(scroll,viewportContent()) }
        val navigationAbove=tvUi||BottomNavigationStyle.position(prefs.getString(BottomNavigationStyle.POSITION,null))==BottomNavigationStyle.TOP
        shell.addView(viewport,if(navigationAbove&&bottomNavigation?.parent===shell)1 else 0, LinearLayout.LayoutParams(-1, 0, 1f))
        if(current=="Логи" || current==ClientSettingsNavigation.DIAGNOSTICS||!loading&&orbitEnabled()&&ConnectionAppearance.isConnection(current)) {scroll.removeView(page);viewport.removeView(scroll);viewport.addView(page,viewportContent());activeScroll=null}
        val heading = when (current) { "Диагностика" -> "Конфигурация"; "Главная" -> "Подключение"; "Маршрутизация" -> "Маршруты"; else -> ClientSettingsNavigation.tabTitle(current) }
        if(ClientSettingsNavigation.isRoutes(current))page.addView(RoutingPage.sections(this,current,::navigate),space(0,12))
        if (!ConnectionAppearance.isConnection(current) && current!=ClientSettingsNavigation.DIAGNOSTICS) {
            val title=text(heading,26,Loft.INK).apply {
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setAccessibilityHeading(true)
                if(pinned&&current==ClientSettingsNavigation.ROOT){
                    maxLines=1
                    setAutoSizeTextTypeUniformWithConfiguration(15,23,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                }
            }
            if(current=="Логи")page.addView(LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL
                addView(title,LinearLayout.LayoutParams(0,-2,1f))
                addView(LogRetentionUi.button(this@MainActivity),LinearLayout.LayoutParams(-2,dp(48)).apply {rightMargin=dp(6)})

            },space(0,20))else if(current==ClientSettingsNavigation.ROOT)page.addView(LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL;addView(title,LinearLayout.LayoutParams(0,-2,1f))
                addView(button("Инвайт",false).apply {
                    tag="settings-invite";textSize=13f;minimumWidth=0;minWidth=0
                    setCompoundDrawablesRelativeWithIntrinsicBounds(icon("mail",Loft.ACCENT),null,null,null);compoundDrawablePadding=dp(6)
                    setOnClickListener {navigate(ClientSettingsNavigation.INVITE)}
                },LinearLayout.LayoutParams(-2,dp(48)))
            },space(0,20))else if(!ClientSettingsNavigation.isRoutes(current))page.addView(title,space(0,20))
        }
        // Until the asynchronous catalogue read completes, an empty in-memory
        // list is not an empty saved catalogue (nor a subscription read error).
        if (loading) {
            val loadingHost=if(current in setOf("Главная",ClientSettingsNavigation.WHITELISTS))card().apply {
                tag="servers-loading";addView(text(if(current==ClientSettingsNavigation.WHITELISTS)"Серверы БС"else"Серверы VPN",21,Loft.INK))
                page.addView(this,space(0,16))
            }else page
            loadingHost.addView(AppearanceMotion.loading(this),space(16,12))
            loadingHost.addView(text("Загружаем сохранённые подключения…",14,Loft.MUTED).apply {
                tag="catalogue-loading-message";gravity=Gravity.CENTER
                accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            },space(0,16))
        } else when (current) { ClientSettingsNavigation.ANUBIS -> page.addView(AppLaunchGroupsUi.view(this)); "Маршрутизация" -> RoutingPage(this, routing, openWarp = { navigate("WARP") }).build(page); ClientSettingsNavigation.SCHEDULE -> schedule(); "WARP" -> page.addView(warp().build(this),space(0,16)); ClientSettingsNavigation.WHITELISTS -> whiteLists(); "Настройки" -> settings(); "Логи" -> journal(); "Диагностика" -> diagnostics(); ClientSettingsNavigation.DIAGNOSTICS -> ConnectionDiagnosticsUi.build(this,page); in ClientSettingsNavigation.children -> settingsPage(current); else -> home() }
        if(!loading&&!tvUi)miniature=EdgeConnectionControls(this,telegram,::toggleEdgeConnection).apply {
            val edge=if(pinnedLeft)Gravity.LEFT else Gravity.RIGHT
            viewport.addView(this,FrameLayout.LayoutParams(dp(64),-2,Gravity.CENTER_VERTICAL or edge))
            restoreScroll(edgePanelScroll)
            if(!navigationAbove&&!orbitEnabled()&&ConnectionAppearance.isConnection(current)&&connect !is ToggleConnectionButton) {
                val statusRow=page.findViewWithTag<LinearLayout>("connection-status-row")
                val caption=connectionProgress?.captionView?.takeIf {it.parent!=null}?:status
                if(statusRow!=null&&caption!=null)alignConnectionStatus(statusRow,caption,vpn)
            }
        }
        resourceMonitor.attach(this,viewport)
        updateHomeControl()
        val nav=bottomNavigation?:BottomNavigationView(this,::icon).also {bottomNavigation=it}
        nav.atTop=navigationAbove
        updateBottomNavigation()
        if(nav.parent!==shell)shell.addView(nav,if(navigationAbove)0 else shell.childCount,LinearLayout.LayoutParams(-1,-2))
        (nav.layoutParams as LinearLayout.LayoutParams).apply {topMargin=if(navigationAbove)dp(6)else 0;bottomMargin=if(navigationAbove)dp(6)else 0}.also {nav.layoutParams=it}
        nav.tag=if(navigationAbove)"top-section-navigation"else"bottom-section-navigation"
        layoutEditor.bind(page,"page:$current")
        miniature?.editContent()?.let {layoutEditor.bind(it,"edge")}
        nav.visibility=if(layoutEditor.editing)View.GONE else View.VISIBLE
        if(layoutEditor.editing){
            val header=layoutEditor.header("page:$current")
            val tabEditor=layoutEditor.tabEditor(navigationItems(),::openNavigationTab)
            fun panelMargins()=LinearLayout.LayoutParams(-1,-2).apply {
                marginStart=dp(8);marginEnd=dp(8);topMargin=dp(4);bottomMargin=dp(4)
            }
            if(navigationAbove)shell.addView(layoutEditor.panel(header,tabEditor),0,panelMargins())
            else {
                shell.addView(layoutEditor.panel(header),0,panelMargins())
                shell.addView(layoutEditor.panel(tabEditor),panelMargins())
            }
        }
        AppTypography.applyTree(shell)
        MathTextMotion.bindTree(shell)
        if(!tvUi)page.requestFocus()
        val pageName = current
        if (animatePage && current!=ClientSettingsNavigation.VK && android.animation.ValueAnimator.areAnimatorsEnabled()) { page.alpha = 0f; page.animate().alpha(1f).setDuration(160).start() }
        val anchor=pendingSettingsAnchor.takeIf {
            pageName==ClientSettingsNavigation.ADVANCED||pageName==ClientSettingsNavigation.VPN||
                pageName==ClientSettingsNavigation.APPEARANCE&&it=="appearance-edge-toggle"
        }
        val searchQuery=pendingSettingsQuery.takeIf {anchor!=null}
        if(anchor!=null)pendingSettingsAnchor=null
        if(anchor!=null)pendingSettingsQuery=null
        // Restore position before the new hierarchy's FIRST visible frame.
        // A posted scroll briefly painted the top after Apply and other saves.
        scroll.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                if(activeScroll!==scroll)return true
            if(tvUi) {
                val previous=previousFocusTag?.let {shell.findViewWithTag<View>(it)}
                if(previous?.requestFocus()!=true&&shell.findFocus()==null) {
                    TvUi.firstAction(shell)?.requestFocus()
                }
            }
            val anchored=anchor?.let {page.findViewWithTag<View>(it)}
            val target=if(anchored!=null&&!searchQuery.isNullOrBlank())findSettingsSearchTarget(anchored,searchQuery)?:anchored else anchored
            if(target!=null) {
                val rect=android.graphics.Rect();target.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(target,rect)
                scroll.scrollTo(0,(rect.top-dp(12)).coerceAtLeast(0))
                target.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
            } else {scroll.scrollTo(0,scrollPositions[pageName]?:0);if(anchor!=null)toast("Этот параметр скрыт выбранным режимом приложения")}
            updateMiniature(false)
            if(!loading)ColdLaunchSwarm.pageReady(this@MainActivity)
                return true
            }
        })
    }
    fun applyUserThemeColors() {
        UserThemeColorsUi.configure(this)
        applyAppearancePalette()
        window.setBackgroundDrawable(Loft.rootBackground(this))
        routingScreenCache.clear()
        render()
    }
    internal fun orbitEnabled()=!tvUi&&HomeUiTheme.mode(prefs).orbit
    internal fun mergedConnections()=tvUi||prefs.getBoolean(ConnectionAppearance.MERGED,true)
    internal fun isEdgePanelPinned()=!tvUi&&EdgePanelAppearance.pinned(prefs)
    internal fun contentWidthDp()=resources.configuration.screenWidthDp-if(isEdgePanelPinned())64 else 0
    internal fun refreshAutoSelectionUi(){
        selectionPolicyRefresh?.invoke()
        miniature?.updateAutoSelection()
    }
    private fun applyAppearancePalette(){
        AppTypography.configure(this)
        val theme=if(tvUi)HomeUiTheme.Mode.AMOLED_BACKGROUND else HomeUiTheme.mode(prefs)
        HomeUiTheme.amoled=theme.orbit
        HomeUiTheme.amoledGradient=theme==HomeUiTheme.Mode.AMOLED_BACKGROUND
        val dark=theme!=HomeUiTheme.Mode.LIGHT
        Loft.apply(dark)
        setTheme(if(dark)R.style.AppThemeDark else R.style.AppTheme)
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
    /** A catalogue mutation must refresh even when navigation would keep the same tab. */
    fun showImportedConnections(entries:List<ServerImport.Server>) {
        reconcileSelection(true)
        val destination=ConnectionSections.importDestination(entries)
        if(ConnectionSections.needsNavigation(destination,current))navigate(requireNotNull(destination))else render()
    }
    fun navigate(name: String) {
        // Re-selecting an already visible tab is not a refresh. Keep the same
        // controls, focus and scroll position (also after alias normalization).
        if(name==ClientSettingsNavigation.ANUBIS&&!AnubisRouteAccess.allowed(this))return
        if(name=="WARP"&&!showWarpTools())return
        if(name==ClientSettingsNavigation.INVITE){InviteCodeUi(this){kinds->engineImport.showEngines(kinds)}.show();return}
        if(name=="VPN"){navigate("Главная");return}
        if(name in setOf(ClientSettingsNavigation.CAPTCHA,ClientSettingsNavigation.CONNECTIONS)){navigate(ConnectionAppearance.section(settingsOrigin,UsageFeatures.visible("Главная",UsageFeaturesUi.selected(this)),UsageFeatures.whitelists(UsageFeaturesUi.selected(this)))?:"Главная");return}
        if(name==ClientSettingsNavigation.TLS){openAdvanced("advanced-tls");return}
        if(name==ClientSettingsNavigation.XRAY){openAdvanced("advanced-xray");return}
        if(name==ClientSettingsNavigation.GEO){openAdvanced("advanced-geo");return}
        if(name==ClientSettingsNavigation.SHARING){pendingSettingsAnchor="lan-sharing-page";navigate(ClientSettingsNavigation.VPN);return}
        val canonical=ClientSettingsNavigation.canonical(name)
        if(canonical!=name){navigate(canonical);return}
        if(tvUi&&!TvUi.settingsVisible(this,name))return
        val target=if(name=="Журнал")"Логи"else name
        if(!loading&&!renderedLoading&&target==current&&renderedPage==current&&pendingSettingsAnchor==null)return
        navigationRevision++
        if (loading) {deferredNavigation.capture(name);return}
        if(!UsageFeatures.visible(name,UsageFeaturesUi.selected(this))){current="Главная";render();return}
        if(switchConnectionSection(target))return
        activeScroll?.let { scrollPositions[current] = it.scrollY }
        if(name==ClientSettingsNavigation.ADVANCED&&current!=ClientSettingsNavigation.ADVANCED)advancedOrigin=current
        if(ClientSettingsNavigation.isSettings(name)&&!ClientSettingsNavigation.isSettings(current)){
            settingsOrigin=current;settingsDirectEntry=name!=ClientSettingsNavigation.ROOT
        }
        if(ClientSettingsNavigation.isSettings(current))lastSettingsPage=current
        if(name==ClientSettingsNavigation.ROOT)settingsDirectEntry=false
        if(current==ClientSettingsNavigation.ANUBIS)anubisProviderPrompted=false
        current=when(name){"Серверы"->"Главная";"Журнал"->"Логи";else->name};render()
        if(current==ClientSettingsNavigation.ANUBIS)promptForAnubisProvider()
        if(name=="Серверы")activeScroll?.post { serversPanel?.let { activeScroll?.smoothScrollTo(0,it.top) } }
    }
    fun openDeployVkSettings() {
        returnToDeployVk=true
        settingsOrigin="Автодеплой"
        navigate(ClientSettingsNavigation.VK)
    }
    private fun updateSystemBack() {
        if(android.os.Build.VERSION.SDK_INT<33)return
        if(current!="Главная"&&systemBackCallback==null){
            val callback=android.window.OnBackInvokedCallback {onBackPressed()}
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback)
            systemBackCallback=callback
        }else if(current=="Главная"){
            systemBackCallback?.let {onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)};systemBackCallback=null
        }
    }
    @Deprecated("Legacy back dispatch for the Activity-based UI")
    override fun onBackPressed() {
          if(current=="Автодеплой"&&deployUi?.back()==true)return
          else if(current==ClientSettingsNavigation.VK&&returnToDeployVk){returnToDeployVk=false;navigate("Автодеплой")}
          else if(current==ClientSettingsNavigation.ANUBIS)navigate(ClientSettingsNavigation.ROUTES)
          else if(tvUi&&ClientSettingsNavigation.isSettings(current)&&current!=ClientSettingsNavigation.ROOT)navigate(ClientSettingsNavigation.ROOT)
          else if(ClientSettingsNavigation.isSettings(current))navigate(ClientSettingsNavigation.back(current,settingsOrigin,settingsDirectEntry,advancedOrigin))
        else if(current=="Диагностика")navigate(settingsOrigin)
        else if(current!="Главная")navigate("Главная")
        else if((activeScroll?.scrollY?:0)>0)activeScroll?.smoothScrollTo(0,0)
        else super.onBackPressed()
    }
    private fun connectionButton(action:()->Unit):View {
        val style=connectionStyle()
        return if(ConnectionAppearance.isToggle(style))ToggleConnectionButton(this,action,style,native=style==ConnectionAppearance.TOGGLE)
        else ForgeConnectionButton(this,connectionMotion,style,ConnectionAppearance.exit(prefs.getString(ConnectionAppearance.EXIT,null)),action).apply {resumeAnimation(animationResumed)}
    }
    private fun connectionStyle()=if(tvUi)ConnectionAppearance.TOGGLE else ConnectionAppearanceUi.style(this)
    private fun home(whitelists:Boolean=false) {
        val wideTv=tvUi&&resources.configuration.screenWidthDp>=680
        val controlsColumn=if(wideTv)LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
        }else page
        val serverColumn=if(wideTv)LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
        }else page
        if(wideTv)page.addView(LinearLayout(this).apply {
            tag="tv-connection-columns"
            isBaselineAligned=false
            addView(controlsColumn,LinearLayout.LayoutParams(0,-2,2f).apply {marginEnd=dp(20)})
            addView(serverColumn,LinearLayout.LayoutParams(0,-2,3f))
        },space(0,24))
        val connectionCard=card().apply {tag=if(whitelists)"bs-connection-card"else"vpn-connection-card"}
        val control=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;tag=if(whitelists)"whitelist-connection-control"else"home-connection-control";setPadding(0,dp(2),0,dp(2))}
        val style=connectionStyle()
        val cardAction={
            if(!ConnectionAppearance.isToggle(style)&&(connectionPreparationError!=null||ConnectionPresentation.failed(McrfVpnService.status)||LastConnectionFailure.detail()!=null)&&!active()&&!working())navigate("Логи")
            else toggleConnection()
        }
        val orbit=orbitEnabled()
        if(orbit){
            connectionCard.background=null
            connectionCard.setPadding(dp(8),0,dp(8),0)
        }
        val sideOnly=style==ConnectionAppearance.SIDE_ONLY
        val orbitToggle=orbit&&ConnectionAppearance.isToggle(style)
        connect=if(sideOnly||orbit&&!orbitToggle)null else connectionButton {toggleConnection()}
        val custom=connect is ForgeConnectionButton
        if(custom)connectionCard.setOnClickListener {cardAction()}
        val compact=prefs.getBoolean(ConnectionAppearance.COMPACT,true)
        val controls=object:LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                if(custom&&!compact)connect?.layoutParams?.width=((View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight)*.8f).toInt().coerceAtLeast(1)
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            }
        }.apply {gravity=Gravity.CENTER;orientation=LinearLayout.VERTICAL}
        if(orbit) {
            orbitRoutingLabel=RoutingProfileUi.labels(this)
            status=ConnectionCaptionView(this).apply {
                textSize=14f;maxLines=2;gravity=Gravity.START;tag="orbit-connection-status"
                letterSpacing=.05f
                accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
                setOnClickListener {
                    val challenge=CarrierChallenge.pendingManual()
                    if(challenge!=null)startActivity(Intent(this@MainActivity,CarrierCaptchaActivity::class.java).putExtra("request",challenge.id))
                    else if(connectionPreparationError!=null||ConnectionPresentation.failed(McrfVpnService.status)||LastConnectionFailure.detail()!=null)navigate("Логи")
                    else if(showingOrbitRouting||sideOnly)
                        RoutingProfileUi.show(this@MainActivity,McrfVpnService.selectedId.takeIf {active()}?:findSelected()?.id)
                    else toggleConnection()
                }
            }
            val statusRow=connectionStatusRow(requireNotNull(status),true)
            val pixelLoading=AppearanceMotion.pixelLoading(this)
            // Reserve identical space for both caption lines, stream count and the optional pixel indicator.
            controls.minimumHeight=maxOf(dp(ToggleConnectionButton.HEIGHT_DP),
                requireNotNull(status).lineHeight*2+(connectionStreams?.lineHeight?:0)+dp(if(pixelLoading)28 else 8))
            if(orbitToggle){
                controls.addView(LinearLayout(this).apply {
                    gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false;tag="connection-toggle-progress-row"
                    addView(connect,LinearLayout.LayoutParams(dp(ToggleConnectionButton.WIDTH_DP),dp(ToggleConnectionButton.HEIGHT_DP)).apply {marginEnd=dp(12)})
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation=LinearLayout.VERTICAL
                        addView(statusRow,LinearLayout.LayoutParams(-1,-2))
                        if(pixelLoading)connectionProgress=ConnectionStatusProgress(this@MainActivity,captionInBar=false,visualOnly=true,pixelAnchor=status){}.also {
                            addView(it,LinearLayout.LayoutParams(-1,-2))
                        }
                    },LinearLayout.LayoutParams(0,-2,1f))
                },LinearLayout.LayoutParams(-1,-2))
            }else{
                controls.addView(statusRow,LinearLayout.LayoutParams(-1,-2))
                if(pixelLoading)connectionProgress=ConnectionStatusProgress(this,captionInBar=false,visualOnly=true,pixelAnchor=status){}.also {
                    controls.addView(it,LinearLayout.LayoutParams(-1,-2))
                }
            }
        }else if(connect is ToggleConnectionButton) {
            val toggleRow=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;tag="connection-toggle-progress-row";isBaselineAligned=false}
            toggleRow.addView(connect,LinearLayout.LayoutParams(dp(ToggleConnectionButton.WIDTH_DP),dp(ToggleConnectionButton.HEIGHT_DP)).apply {marginEnd=dp(12)})
            connectionProgress=ConnectionStatusProgress(this,captionInBar=false) {phase->
                if(phase==ConnectionProgressState.Phase.FAILED)navigate("Логи")
            }.also {progress->
                toggleRow.addView(LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    addView(connectionStatusRow(progress.captionView,alignStart=true),LinearLayout.LayoutParams(-1,-2))
                    addView(progress,LinearLayout.LayoutParams(-1,-2))
                },LinearLayout.LayoutParams(0,-2,1f))
            }
            controls.addView(toggleRow,LinearLayout.LayoutParams(-1,-2))
        }else if(connect!=null)controls.addView(connect,LinearLayout.LayoutParams(if(compact)dp(ConnectionAppearance.COMPACT_SCENE_DP)else -1,dp(if(compact)ConnectionAppearance.COMPACT_SCENE_DP else ConnectionAppearance.EXPANDED_SCENE_DP)))
        control.addView(controls,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(4)})
        if(!orbit)status=ConnectionCaptionView(this).apply {text="Отключено";textSize=12f;setTextColor(Loft.MUTED);gravity=Gravity.CENTER;tag="home-connection-status";setPadding(dp(8),dp(2),dp(8),dp(2));visibility=if(connect is ToggleConnectionButton)View.GONE else View.VISIBLE}
        if(!orbit)connectionHint=text("",12,Loft.MUTED).apply {
            gravity=Gravity.CENTER;maxLines=3;ellipsize=TextUtils.TruncateAt.END;tag="home-connection-hint"
            setPadding(dp(8),dp(4),dp(8),dp(6));minimumHeight=dp(36)
            setOnClickListener {
                val challenge=CarrierChallenge.pendingManual().takeIf {connectionSectionWhitelists()}
                if(challenge!=null)startActivity(Intent(this@MainActivity,CarrierCaptchaActivity::class.java).putExtra("request",challenge.id))
                else if(custom)cardAction()
                else if(connectionPreparationError!=null||ConnectionPresentation.failed(McrfVpnService.status)||LastConnectionFailure.detail()!=null)navigate("Логи")
            }
        }
        if(!orbit){
        val details=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL
            if(custom||sideOnly)addView(connectionStatusRow(requireNotNull(status),alignStart=compact||sideOnly));addView(connectionHint);addView(connectionTargetView(alignStart=sideOnly))}
        if(sideOnly)listOf(status,connectionHint).forEach {it?.gravity=Gravity.START}
        if(custom&&compact){
            control.removeView(controls)
            val sceneRow=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;tag="connection-scene-status-row"
                addView(controls,LinearLayout.LayoutParams(dp(ConnectionAppearance.COMPACT_SCENE_DP),dp(ConnectionAppearance.COMPACT_SCENE_DP)).apply {marginEnd=dp(8)})
                addView(details,LinearLayout.LayoutParams(0,-2,1f))}
            listOf(status,connectionHint,connectionTarget,connectionAddress).forEach {it?.gravity=Gravity.START}
            control.addView(sceneRow,LinearLayout.LayoutParams(-1,-2))
        }else control.addView(details,LinearLayout.LayoutParams(-1,-2))
        }

        connectionCard.addView(control,space(0,if(orbit)0 else 6))
        isolationUpgradeNotice=text("Изоляция пока не применяется: обновите движок и переподключите VPN. Открыть обновления",13,Loft.INK).apply {
            tag="connection-isolation-upgrade"
            visibility=View.GONE;minHeight=dp(48);gravity=Gravity.CENTER_VERTICAL
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener {navigate(ClientSettingsNavigation.UPDATES)}
        }.also {connectionCard.addView(it,space(0,6))}
        tun = Switch(this).apply { isChecked = if(active())prefs.getBoolean("tun",true)else UsageFeatures.preferredVpn(UsageFeaturesUi.selected(this@MainActivity),prefs.getBoolean("tun",true)) }
        modeSwitch = ModeSwitch(this, tun!!.isChecked, java.util.function.Consumer { changeMode(it) })
        modeSwitch?.tag="home-mode-switch"
        modeSwitch?.visibility=if(UsageFeatures.showModeSwitch(UsageFeaturesUi.selected(this)))View.VISIBLE else View.GONE
        modeHint = text("", 11, Loft.MUTED).apply { maxLines = 2; gravity = Gravity.CENTER }

        controlsColumn.addView(connectionCard)
        val tools=card().apply {
            tag="connection-controls-block"
            if(orbit){background=ServerOrbitPicker.controlSurface(this@MainActivity);setPadding(dp(10),dp(6),dp(10),dp(6))}
            addView(autoBsChoice(),LinearLayout.LayoutParams(-1,-2))
            addView(LinearLayout(this@MainActivity).apply {
                tag="connection-mode-actions";gravity=Gravity.END or Gravity.CENTER_VERTICAL
                addView(modeSwitch,LinearLayout.LayoutParams(0,-2,1f))
                if(!orbit&&!tvUi)addView(iconButton("arrow","Свернуть управление в боковое меню").apply {
                    tag="connection-controls-hide";rotation=if(prefs.getBoolean(ConnectionControlsDock.HIDDEN,false))180f else 0f;contentDescription=if(prefs.getBoolean(ConnectionControlsDock.HIDDEN,false))"Вернуть управление на экран"else"Свернуть управление в боковое меню"
                    setOnClickListener {setConnectionControlsHidden(!prefs.getBoolean(ConnectionControlsDock.HIDDEN,false))}
                },LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginStart=dp(8)})
            },space(6,8))
        }
        val toolbar=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="connection-toolbar-host"}
        connectionToolbar=toolbar;tools.addView(toolbar,LinearLayout.LayoutParams(-1,-2))
        connectionDock=ConnectionControlsDock(this,tools,viewport)
        if(!orbit&&!isEdgePanelPinned())controlsColumn.addView(connectionDock!!.holder,space(12,0))
        val serverCard=serverCard(whitelists)
        serversPage().build(serverCard,whitelistOnly=whitelists,controlsHost=toolbar)
        serversPanel=serverCard
        serverColumn.addView(serverCard,if(orbit)LinearLayout.LayoutParams(-1,0,1f)else space(if(wideTv)0 else 16,72))
        updateHomeControl()
    }
    private fun serversPage()=ServersPage(this,ServerPageActions(
        vkSettings={navigate(ClientSettingsNavigation.VK)},
        telegramProxy={try {telegram.openTelegram()}catch(_:ActivityNotFoundException){alert("Telegram","На телефоне не найден Telegram.")}},
        warp={navigate("WARP")},
        compareTransports={bs->TransportComparisonUi.show(this,bs){refreshConnectionServers(bs)}}
    ))
    internal fun measureEdgeServers(){
        if(probing())ProbeCoordinator.cancel(this)
        else if(!working())serversPage().measureVisible(connectionSectionWhitelists())
    }
    internal fun importFromClipboard(){
        try {
            val clipboard=getSystemService(ClipboardManager::class.java).primaryClip
            val value=if(clipboard!=null&&clipboard.itemCount>0)clipboard.getItemAt(0).coerceToText(this)?.toString().orEmpty()else ""
            if(value.isBlank())toast("В буфере нет ссылки или конфигурации")else importDialog(value)
        }catch(_:Exception){alert("Импорт","Не удалось прочитать буфер обмена.")}
    }
    internal fun scanServerQr() {
        if(!tvUi)startActivityForResult(Intent(this,QrScanActivity::class.java),6)
    }
    private fun refreshConnectionServers(bs:Boolean) {
        if(!ConnectionAppearance.isConnection(current)||connectionSectionWhitelists()!=bs||loading||isFinishing||isDestroyed)return
        val old=serversPanel?:return
        val host=old.parent as? ViewGroup?:return
        val index=host.indexOfChild(old);if(index<0)return
        // Build detached; the connection control, wallpaper, scroller and edge tab survive.
        val replacement=serverCard(bs)
        connectionToolbar?.removeAllViews()
        serversPage().build(replacement,whitelistOnly=bs,controlsHost=connectionToolbar?:replacement)
        host.removeViewAt(index);host.addView(replacement,index,old.layoutParams)
        serversPanel=replacement
    }
    private fun switchConnectionSection(target:String):Boolean {
        if(orbitEnabled()||mergedConnections()||layoutEditor.editing)return false
        if(loading||renderedLoading||renderedPage!=current||target==current||
            !ConnectionAppearance.isConnection(current)||!ConnectionAppearance.isConnection(target)||serversPanel==null)return false
        val scroll=activeScroll?:return false
        scrollPositions[current]=scroll.scrollY
        current=target;lastConnectionPage=target;renderedPage=target;page.tag="screen-$target"
        val bs=connectionSectionWhitelists()
        page.findViewWithTag<View>(if(bs)"vpn-connection-card"else"bs-connection-card")?.tag=if(bs)"bs-connection-card"else"vpn-connection-card"
        page.findViewWithTag<View>(if(bs)"home-connection-control"else"whitelist-connection-control")?.tag=if(bs)"whitelist-connection-control"else"home-connection-control"
        refreshConnectionServers(bs)
        // Only section labels change; do not restart the connection animation or fade the page.
        updateBottomNavigation()
        val position=scrollPositions[target]?:0
        scroll.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                if(activeScroll===scroll&&current==target){scroll.scrollTo(0,position);updateMiniature(false)}
                return true
            }
        })
        refreshAutoSelectionUi()
        updateHomeControl();return true
    }
    private var sectionSwipeStart: Pair<Float,Float>?=null
    private var sectionSwipePage: String?=null

    private fun swipeHitsControl(view:View,x:Int,y:Int):Boolean {
        val bounds=android.graphics.Rect()
        if(!view.isShown||!view.getGlobalVisibleRect(bounds)||!bounds.contains(x,y))return false
        if(view.isClickable||view.isLongClickable||view is ServerSwipeRow||view is AndroidDeployDraftSwipeRow||
            view is android.webkit.WebView||view is android.widget.EditText||view is StreamCountSlider)return true
        if(view is android.view.ViewGroup)for(index in view.childCount-1 downTo 0)
            if(swipeHitsControl(view.getChildAt(index),x,y))return true
        return false
    }

    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {
        if(ColdLaunchSwarm.consumeDismissGesture(event))return true
        miniature?.particleEye?.followPointer(event.rawX,event.rawY,event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL)
        when(event.actionMasked){
            android.view.MotionEvent.ACTION_DOWN->{
                val x=event.rawX;val y=event.rawY
                val bounds=android.graphics.Rect()
                val eligible=prefs.getBoolean("sectionSwipesEnabled",false)&&!loading&&::viewport.isInitialized&&viewport.getGlobalVisibleRect(bounds)&&
                    bounds.contains(x.toInt(),y.toInt())&&!swipeHitsControl(viewport,x.toInt(),y.toInt())
                sectionSwipeStart=if(eligible)x to y else null
                sectionSwipePage=current
            }
            android.view.MotionEvent.ACTION_MOVE->sectionSwipeStart?.let {(x,y)->
                val dx=kotlin.math.abs(event.rawX-x);val dy=kotlin.math.abs(event.rawY-y)
                if(dy>android.view.ViewConfiguration.get(this).scaledTouchSlop&&dy>dx) sectionSwipeStart=null
            }
            android.view.MotionEvent.ACTION_UP->{
                val start=sectionSwipeStart;sectionSwipeStart=null
                if(start!=null&&sectionSwipePage==current&&prefs.getBoolean("sectionSwipesEnabled",false)){
                    val dx=event.rawX-start.first;val dy=event.rawY-start.second
                    if(kotlin.math.abs(dx)>=dp(64)&&kotlin.math.abs(dx)>1.6f*kotlin.math.abs(dy)){
                        val tabs=layoutEditor.tabs(navigationItems()).map(NavigationTab::id)
                        val selected=tabs.indexOfFirst {navigationSelected(it)}
                        val adjacent=if(selected>=0)tabs.getOrNull(selected+if(dx<0)1 else -1)else null
                        if(adjacent!=null){
                            val cancel=android.view.MotionEvent.obtain(event)
                            try{cancel.action=android.view.MotionEvent.ACTION_CANCEL;super.dispatchTouchEvent(cancel)}finally{cancel.recycle()}
                            openNavigationTab(adjacent)
                            return true
                        }
                    }
                }
            }
            android.view.MotionEvent.ACTION_POINTER_DOWN,android.view.MotionEvent.ACTION_CANCEL->sectionSwipeStart=null
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_HOVER_MOVE ->
                miniature?.particleEye?.followPointer(event.rawX, event.rawY, false)
            android.view.MotionEvent.ACTION_HOVER_EXIT ->
                miniature?.particleEye?.followPointer(event.rawX, event.rawY, true)
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun visibleNavigationTabs():List<String> {
        val features=UsageFeaturesUi.selected(this)
        return ConnectionAppearance.tabs(mergedConnections(),
            UsageFeatures.visible("Главная",features),UsageFeatures.whitelists(features))
            .filter {it==ConnectionAppearance.TAB||ClientSettingsNavigation.visible(it,settingsExperience().first)}
    }
    private fun navigationSelected(tab:String)=when(tab){
        ClientSettingsNavigation.ROOT->ClientSettingsNavigation.isSettings(current)
        ConnectionAppearance.TAB->ConnectionAppearance.isConnection(current)
        else->ClientSettingsNavigation.mainTab(current)==tab
    }
    private fun openNavigationTab(tab:String){
        if(tab==UiConstructor.RECOVER){navigate(ClientSettingsNavigation.APPEARANCE);return}
        if(tab==ConnectionAppearance.TAB){
            val features=UsageFeaturesUi.selected(this)
            ConnectionAppearance.bottomTarget(UsageFeatures.visible("Главная",features),
                UsageFeatures.whitelists(features))?.let {navigate(it)}
        }else if(tab==ClientSettingsNavigation.ROOT)navigate(if(tvUi)ClientSettingsNavigation.ROOT else ClientSettingsNavigation.settingsTabTarget(current,lastSettingsPage))
        else navigate(ClientSettingsNavigation.bottomTarget(tab,prefs.getString(ClientSettingsNavigation.ROUTES_PAGE_KEY,null)))
    }
    private fun navigationItems()=visibleNavigationTabs().map {tab->
                val joined=tab==ConnectionAppearance.TAB
                val chosen=navigationSelected(tab)
                val label=if(joined)"VPN" else ClientSettingsNavigation.tabTitle(tab)
                val description=label
                NavigationTab(tab,label,when(tab){ConnectionAppearance.TAB->"rkn";"Главная"->"home";"Маршрутизация"->"routes";ClientSettingsNavigation.WHITELISTS->"shield";"Настройки"->"gear";else->"logs"},description,chosen)
            }
    private fun updateBottomNavigation() {
        val position=BottomNavigationStyle.position(prefs.getString(BottomNavigationStyle.POSITION,null))
        bottomNavigation?.submit(layoutEditor.tabs(navigationItems()),prefs.getString(BottomNavigationStyle.KEY,null).orEmpty(),position,::openNavigationTab)
        bottomNavigation?.let {AppearanceMotion.bindNavigationPill(this,it,position==BottomNavigationStyle.TOP)}
    }
    private fun serverCard(whitelists:Boolean)=card().apply {
        tag=if(whitelists)"bs-server-card"else"vpn-server-card"
        if(orbitEnabled()){background=null;setPadding(0,0,0,0)}
        selectedServer=null
    }
    fun serverActionsMenu(anchor:View) {
        val actions=mutableListOf<Pair<String,()->Unit>>()
        actions.add("Импортировать из файла" to {startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),3)})
        actions.add("Ввести ссылку" to {importDialog("")})
        if(!tvUi&&UsageFeatures.telegram(UsageFeaturesUi.selected(this),telegram.state.wanted,telegram.state.running))actions.add("Добавить в TG Proxy" to {try{telegram.openTelegram()}catch(_:ActivityNotFoundException){alert("Telegram","На телефоне не найден Telegram.")}})
        if(settingsExperience().first==ExperienceMode.EXPERT&&UsageFeatures.Feature.WARP in UsageFeaturesUi.selected(this))actions.add("WARP · добавить сервер" to {navigate("WARP")})
        actions.add("Логи" to {navigate("Логи")})
        ThemedActionMenu(this,anchor).apply {
            actions.forEachIndexed {index,item->menu.add(0,index,index,item.first)}
            setOnMenuItemClickListener {item->actions[item.itemId].second();true};show()
        }
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
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false;tag=rowTag;minimumHeight=dp(48)
        addView(text(label,11,Loft.MUTED).apply {
            gravity=Gravity.CENTER_VERTICAL;setSingleLine(false);maxLines=3;setHorizontallyScrolling(false);includeFontPadding=false
            setAutoSizeTextTypeUniformWithConfiguration(9,11,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        },LinearLayout.LayoutParams(0,dp(48),1.15f).apply {rightMargin=dp(8);gravity=Gravity.CENTER_VERTICAL})
        addView(choice,if(choice.minimumWidth>0)LinearLayout.LayoutParams(choice.minimumWidth,dp(48)).apply {gravity=Gravity.CENTER_VERTICAL}
            else LinearLayout.LayoutParams(0,dp(48),1f).apply {gravity=Gravity.CENTER_VERTICAL})
    }
    private fun directTunChoice():LinearLayout {
        fun saved()=CsqttDirectTunSettings.selected(prefs::getBoolean)
        fun state():CsqttDirectTunSettings.UiState {
            val bs=connectionSectionWhitelists()
            val running=catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId}.takeIf {active()}
            val entry=running?:findSelected()
            val vpn=if(active()||working())prefs.getBoolean("tun",true)
                else UsageFeatures.preferredVpn(UsageFeaturesUi.selected(this),prefs.getBoolean("tun",true))
            val csqtt=entry?.whitelists==true&&entry.carrier?.optString("type")=="csqtt"
            val pending=working()||McrfVpnService.cleanupPending()
            val blocked=when {
                running!=null&&!running.whitelists->"Сначала отключите соединение в разделе VPN."
                !bs||!vpn||!csqtt||pending->null // Do not probe native capabilities on unrelated/hidden controls.
                else->CsqttDirectTunSupport.unavailableReason(this,entry?.carrier,vpn)
            }
            return CsqttDirectTunSettings.state(saved(),bs,vpn,csqtt,pending,blocked)
        }
        lateinit var refresh:()->Unit
        val choice=ModeSwitch(this,saved(),java.util.function.Consumer {enabled->
            val current=state()
            if(enabled!=current.selected) {
                if(!current.canSet(enabled))toast(current.reason?:"Тестовый режим недоступен.")
                else {
                    fun change() {
                        // Recheck after the confirmation: selection/mode/native support may have changed.
                        val latest=state()
                        if(!latest.canSet(enabled))toast(latest.reason?:"Тестовый режим недоступен.")
                        else if(!prefs.edit().putBoolean(CsqttDirectTunSettings.KEY,enabled).commit())
                            alert(CsqttDirectTunSettings.TITLE,"Не удалось сохранить настройку.")
                        else McrfVpnService.directTunChanged(this)
                        refresh()
                    }
                    if(active())ThemedDialogBuilder(this).setTitle("Переподключить CSQTT?")
                        .setMessage(if(enabled)"Эксперимент: соединение будет переподключено напрямую в Rust, без обычных правил, автовыбора и сетевых политик MCRF для этого подключения."
                            else "Соединение будет переподключено с обычными правилами MCRF.")
                        .setNegativeButton("Отмена",null).setPositiveButton("Переключить"){_,_->change()}.show()
                    else change()
                }
            }
            refresh()
        },listOf("Вкл","Выкл"),listOf("",""),true).apply {
            tag="bs-direct-tun-switch";contentDescription=CsqttDirectTunSettings.TITLE
        }
        val info=button("i",false).apply {
            tag="bs-direct-tun-help";DropdownStyle.apply(this@MainActivity,this)
            contentDescription="Справка: ${CsqttDirectTunSettings.TITLE}"
            setOnClickListener {alert(CsqttDirectTunSettings.TITLE,CsqttDirectTunSettings.HELP)}
        }
        val controls=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false
            addView(choice,LinearLayout.LayoutParams(0,dp(48),1f))
            addView(info,LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginStart=dp(4)})
        }
        val reason=text("",11,Loft.MUTED).apply {
            tag="bs-direct-tun-unavailable";maxLines=3;includeFontPadding=false
            setPadding(0,0,0,dp(4));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        return LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;tag="bs-direct-tun-control"
            addView(connectionOptionRow(CsqttDirectTunSettings.TITLE,"bs-direct-tun-row",controls),LinearLayout.LayoutParams(-1,dp(48)))
            addView(reason,LinearLayout.LayoutParams(-1,-2))
            refresh={
                val current=state()
                visibility=if(current.visible)View.VISIBLE else View.GONE
                choice.setMode(current.selected,false);choice.setAvailable(current.available)
                reason.text=current.reason?.let {
                    if(current.selected)"Сохранено «Вкл» · недоступно: $it"+(if(current.canDisable)" Можно выключить."else"")else it
                }.orEmpty()
                reason.visibility=if(current.reason==null)View.GONE else View.VISIBLE
                choice.contentDescription=CsqttDirectTunSettings.TITLE+if(current.reason!=null)". Недоступно: ${current.reason}"else""
            }
            directTunControlsRefresh=refresh;refresh()
        }
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
        val detectionInterval=AutoControlsUi.interval(this,AutoCheckIntervals.Kind.DETECTION)
        val selectionInterval=AutoControlsUi.interval(this,AutoCheckIntervals.Kind.SELECTION)
        val policy=AutoControlsUi.policy(this)
        selectionPolicyRefresh={
            AutoControlsUi.refreshInterval(this,detectionInterval,AutoCheckIntervals.Kind.DETECTION)
            AutoControlsUi.refreshInterval(this,selectionInterval,AutoCheckIntervals.Kind.SELECTION)
            AutoControlsUi.refreshPolicy(this,policy,true)
        }
        val control=LinearLayout(this).apply {
            gravity=Gravity.CENTER;isBaselineAligned=false;tag="auto-selection-control"
            addView(button("i",false).apply {
                tag="auto-bs-selection-help";DropdownStyle.apply(this@MainActivity,this);contentDescription="Справка об автовыборе"
                setOnClickListener {alert("Автовыбор",BsAutoSelectionPolicy.HELP)}
            },LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(text("Автовыбор",13,Loft.INK).apply {tag="auto-selection-title";gravity=Gravity.CENTER;includeFontPadding=false;setSingleLine(true);setAutoSizeTextTypeUniformWithConfiguration(10,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);minimumWidth=0},LinearLayout.LayoutParams(0,dp(48),1f))
            addView(selectionInterval,LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(policy,LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(AutoToleranceUi.button(this@MainActivity),LinearLayout.LayoutParams(dp(72),dp(48)))
        }
        return LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;tag="unified-auto-selection"
            val detector=LinearLayout(this@MainActivity).apply {
                gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false;setPadding(0,0,0,0);minimumWidth=dp(148)
                addView(choice,LinearLayout.LayoutParams(0,dp(48),1f))
                addView(detectionInterval,LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginStart=dp(4)})
            }
            if(!tvUi||TvUi.showAutoBs(this@MainActivity))addView(connectionOptionRow("Автоопределение БС","auto-bs-control",detector).apply {
                if(contentWidthDp()-220 < 110*resources.configuration.fontScale) {
                    (getChildAt(0) as TextView).apply {text="Автоопределение\nБС";maxLines=2}
                }
                (getChildAt(0) as TextView).gravity=Gravity.CENTER
                contentDescription="Автоопределение ограничений сети и переход VPN ↔ БС"
            },LinearLayout.LayoutParams(-1,dp(48)))
            if(contentWidthDp()-64 < 216+76*resources.configuration.fontScale) {
                val details=LinearLayout(this@MainActivity).apply {gravity=Gravity.CENTER;isBaselineAligned=false}
                while(control.childCount>2) {
                    val child=control.getChildAt(2);control.removeViewAt(2)
                    details.addView(child,LinearLayout.LayoutParams(0,dp(48),1f))
                }
                addView(control,LinearLayout.LayoutParams(-1,dp(48)))
                addView(details,LinearLayout.LayoutParams(-1,dp(48)))
            }else addView(control,LinearLayout.LayoutParams(-1,dp(48)))
        }
    }
    private fun whiteLists()=home(whitelists=true)
    internal fun toggleConnection() {
        // A preparing or retained blackhole TUN is still ours: power means STOP, never START.
        if(directStopAvailable()){stopConnection();updateHomeControl();return}
        if(working())return
        if(active())stopConnection() else prepareConnection(false)
        updateHomeControl()
    }
    internal fun toggleOrbitServer(id:String){
        if(working())return
        if(active()&&McrfVpnService.selectedId==id){toggleConnection();return}
        select(id)
        if(!active()&&selected==id)toggleConnection()
    }
    private fun toggleEdgeConnection(whitelists:Boolean,wanted:Boolean) {
        if(mergedConnections()){
            if(!wanted&&directStopAvailable())stopConnection()
            else if(!working()&&!McrfVpnService.cleanupPending()){
                if(!wanted&&active())stopConnection()
                if(wanted&&!active()){
                    val entries=catalogue.entries()
                    val last=listOf(BsAutoSelectionPolicy.last(prefs,connectionSectionWhitelists()),
                        BsAutoSelectionPolicy.last(prefs,!connectionSectionWhitelists()))
                    val target=findSelected()?:last.firstNotNullOfOrNull {id->entries.firstOrNull {it.id==id}}
                    if(target==null)toast("Сначала выберите сервер")
                    else prepareConnection(false,requestedWhitelists=target.whitelists,preferredServerId=target.id)
                }
            }
            updateHomeControl()
            return
        }
        if(whitelists&&!wanted&&directStopAvailable()){stopConnection();updateHomeControl();return}
        val runningWhitelists=catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId}?.whitelists==true
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
        if(tvUi)return false
        if(command=="anubis"){navigate(ClientSettingsNavigation.ANUBIS);return true}
        if(command=="rules") {
            pendingRulesTile=(pendingRulesTile+1).coerceAtMost(16)
            rulesTileDeadline=SystemClock.elapsedRealtime()+60_000
            drainRulesTile();return true
        }
        if(working()){toast("Дождитесь завершения текущего действия");return true}
        when(command) {
            "vpn","bs"->{val bs=command=="bs"
                val target=SectionConnectSelection.choose(catalogue.entries(),BsAutoSelectionPolicy.last(prefs,bs),bs,catalogue.probes())
                if(target==null)toast(if(bs)"В разделе БС пока нет серверов"else "В разделе VPN пока нет серверов")
                else prepareConnection(false,requestedWhitelists=bs,preferredServerId=target.id)}
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
    internal fun refreshOrbitInsets(){
        if(!::page.isInitialized||!orbitEnabled())return
        val (start,end)=miniature?.overlayInset()?: (0 to 0)
        fun margins(tag:String,base:Int){
            val view=page.findViewWithTag<View>(tag)?:return
            val params=view.layoutParams as? ViewGroup.MarginLayoutParams?:return
            val left=dp(base)+start
            val right=dp(base)+end
            if(params.leftMargin==left&&params.rightMargin==right)return
            params.leftMargin=left;params.rightMargin=right
            view.layoutParams=params
        }
        val footer=viewport.findViewWithTag<View>("server-orbit-footer")
        if(footer==null)margins("server-orbit-group-label",14) else positionOrbitFooter(footer)
        margins("server-orbit-details",4)
    }
    internal fun attachOrbitFooter(footer:View){
        val owner=viewport
        owner.addView(footer,FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM).apply {leftMargin=dp(14);rightMargin=dp(14);bottomMargin=dp(4)})
        val observer=owner.viewTreeObserver
        val listener=object:android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                if(viewport!==owner||!footer.isAttachedToWindow){if(observer.isAlive)observer.removeOnPreDrawListener(this);return true}
                positionOrbitFooter(footer);return true
            }
        }
        observer.addOnPreDrawListener(listener)
        footer.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:View)=Unit
            override fun onViewDetachedFromWindow(view:View){if(observer.isAlive)observer.removeOnPreDrawListener(listener);view.removeOnAttachStateChangeListener(this)}
        })
    }
    private fun positionOrbitFooter(footer:View){
        val panel=miniature
        val panelBounds=android.graphics.Rect();val footerBounds=android.graphics.Rect();val hostBounds=android.graphics.Rect()
        viewport.getGlobalVisibleRect(hostBounds);footer.getGlobalVisibleRect(footerBounds)
        val overlaps=panel!=null&&panel.isShown&&panel.getGlobalVisibleRect(panelBounds)&&panelBounds.top<footerBounds.bottom&&panelBounds.bottom>footerBounds.top
        val left=dp(14)+if(overlaps&&panelBounds.centerX()<hostBounds.centerX())(panelBounds.right-hostBounds.left).coerceAtLeast(0)else 0
        val right=dp(14)+if(overlaps&&panelBounds.centerX()>=hostBounds.centerX())(hostBounds.right-panelBounds.left).coerceAtLeast(0)else 0
        val params=footer.layoutParams as FrameLayout.LayoutParams
        if(params.leftMargin!=left||params.rightMargin!=right){params.leftMargin=left;params.rightMargin=right;footer.layoutParams=params}
    }
    private fun updateMiniature(animate:Boolean) {
        miniature?.apply {visibility=if(hasVisibleItems())View.VISIBLE else View.GONE;alpha=1f}
        refreshOrbitInsets()
    }
    fun setConnectionControlsHidden(hidden:Boolean) {
        val edit=prefs.edit().putBoolean(ConnectionControlsDock.HIDDEN,hidden)
        if(hidden)edit.putBoolean(EdgePanelAppearance.HIDDEN,false)
        edit.apply();refreshEdgeMenu()
        connectionDock?.content?.findViewWithTag<View>("connection-controls-hide")?.apply {
            rotation=if(hidden)180f else 0f
            contentDescription=if(hidden)"Вернуть управление на экран"else"Свернуть управление в боковое меню"
        }
        connectionDock?.changed(hidden,miniature)
    }
    fun openConnectionControls(anchor:View) {
        if(ConnectionAppearance.isConnection(current))connectionDock?.open(anchor)
        else {navigate(lastConnectionPage);page.post {miniature?.let {connectionDock?.open(it)}}}
    }
    fun refreshEdgeMenu(){miniature?.applyAppearance();updateMiniature(false)}
    private fun connectionTargetView(alignStart:Boolean=false)=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;visibility=View.GONE
        connectionTarget=text("",12,Loft.INK).apply {
            gravity=if(alignStart)Gravity.START else Gravity.CENTER;tag="home-connection-target";maxLines=2;ellipsize=TextUtils.TruncateAt.END
            setPadding(dp(8),dp(2),dp(8),dp(2));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also {addView(it,LinearLayout.LayoutParams(-1,-2))}
        connectionAddress=text("",12,Loft.MUTED).apply {
            gravity=if(alignStart)Gravity.START else Gravity.CENTER;tag="home-connection-ip";setSingleLine(true);ellipsize=TextUtils.TruncateAt.MIDDLE
            setPadding(dp(8),dp(2),dp(8),dp(2))
        }.also {addView(it,LinearLayout.LayoutParams(-1,-2))}
    }
    private fun alignConnectionStatus(row:View,caption:View,toggle:View) {
        val root=viewport
        val captionLocation=IntArray(2)
        val toggleLocation=IntArray(2)
        val rootLocation=IntArray(2)
        root.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                if(!root.isAttachedToWindow||!row.isAttachedToWindow) {
                    if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
                if(!toggle.isShown||!caption.isShown)return true
                caption.getLocationInWindow(captionLocation)
                toggle.getLocationInWindow(toggleLocation)
                root.getLocationInWindow(rootLocation)
                val naturalTop=captionLocation[1]-row.paddingTop
                val toggleCenter=toggleLocation[1]+toggle.height/2f
                val visible=toggleCenter>=rootLocation[1]&&toggleCenter<rootLocation[1]+root.height
                val offset=if(visible)(toggleCenter-naturalTop-caption.height/2f).toInt().coerceAtLeast(dp(4))else dp(4)
                if(row.paddingTop!=offset)row.setPadding(0,offset,0,0)
                return true
            }
        })
    }
    private fun connectionStatusRow(caption:TextView,alignStart:Boolean=false)=LinearLayout(this).apply {
        tag="connection-status-row"
        if(BottomNavigationStyle.position(prefs.getString(BottomNavigationStyle.POSITION,null))==BottomNavigationStyle.BOTTOM)
            setPadding(0,dp(4),0,0)
        gravity=Gravity.CENTER_VERTICAL or if(alignStart)Gravity.START else Gravity.CENTER_HORIZONTAL
        fun LinearLayout.addCaption() {
            val style=ConnectionIndicator.selected(this@MainActivity)
            if(style!=ConnectionIndicator.NONE){
                connectionIndicator=ConnectionIndicatorView(this@MainActivity,style).apply {
                    tag="connection-status-indicator";visibility=View.GONE
                }.also {addView(it,LinearLayout.LayoutParams(dp(21),dp(21)).apply {marginEnd=dp(5)})}
            }
            addView(caption,LinearLayout.LayoutParams(-2,-2))
        }
        if(orbitEnabled()){
            orientation=LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {gravity=Gravity.CENTER_VERTICAL;addCaption()},LinearLayout.LayoutParams(-1,-2))
        }else addCaption()
        val tint=if(Loft.dark)0xffe3a0b3.toInt()else 0xff801c3b.toInt()
        connectionStreams=text("",12,tint).apply {
            tag="home-turn-streams";visibility=View.GONE;maxLines=1
            compoundDrawablePadding=dp(3)
            setCompoundDrawablesRelative(null,null,icon("bolt",tint).apply {setBounds(0,0,dp(13),dp(13))},null)
        }.also {addView(it,LinearLayout.LayoutParams(-2,-2).apply {
            if(orbitEnabled())topMargin=dp(3) else marginStart=dp(5)
        })}
    }
    private fun updateHomeControl() {
        isolationUpgradeNotice?.visibility=if(McrfVpnService.vpnOwnerIsolationUpgradePending())View.VISIBLE else View.GONE
        autoControlsRefresh?.invoke()
        directTunControlsRefresh?.invoke()
        val connected=ConnectionPresentation.connected(McrfVpnService.status)
        val wifiPaused=McrfVpnService.wifiPaused()
        val stopping=McrfVpnService.cleanupPending()
        val directPending=McrfVpnService.directTunWorking()
        val pending = stopping||busy||serverSwitch.target!=null||directPending||ConnectionPresentation.pending(McrfVpnService.status)
        val directPaused=McrfVpnService.directTunActive()&&!pending&&!connected
        val retainedFailure=LastConnectionFailure.detail()
        val failed=!pending&&!connected&&(directPaused||connectionPreparationError!=null||retainedFailure!=null||ConnectionPresentation.failed(McrfVpnService.status))
        val challenge=CarrierChallenge.pendingManual()
        val shownVpn=if(active()||pending)prefs.getBoolean("tun",true)else UsageFeatures.preferredVpn(UsageFeaturesUi.selected(this),prefs.getBoolean("tun",true))
        val powerEnabled=!stopping&&(!working()||directStopAvailable())
        (connect as? ToggleConnectionButton)?.state(connected,pending,powerEnabled,active(),failed)
        (connect as? RoundConnectionButton)?.state(connected,pending,powerEnabled,active(),failed)
        (connect as? ForgeConnectionButton)?.state(connected,pending,powerEnabled,active(),failed,stopping,TrafficState.current)
        if(directStopAvailable())connect?.contentDescription=if(directPending)"Отключить · подготовка прямого TUN"else"Отключить · прямой TUN"
        modeSwitch?.setAvailable(!working()&&!stopping)
        val runningWhitelists=catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId}?.whitelists==true
        val pendingSection=if(directPending)true else serverSwitch.target?.whitelists
            ?:if(ConnectionPresentation.pending(McrfVpnService.status))McrfVpnService.pendingWhitelists else if(busy)pendingConnectionWhitelists else null
        miniature?.updateConnection(active(),pending,powerEnabled,shownVpn,runningWhitelists||directPaused,pendingSection)
        if(!pending||connected&&!busy)pendingConnectionWhitelists=null
        miniature?.updateTelegram()
        miniature?.updateAutoDetection()
        val failure=ConnectionPresentation.cleanFailure(ClientEvents.redact(connectionPreparationError?:retainedFailure?:ConnectionPresentation.failureDetail(McrfVpnService.status)))
        val orbitFlood=orbitEnabled()&&(failure.contains("VK Flood",ignoreCase=true)||failure.contains(VkCredentialLimit.NETWORK_MESSAGE))
        val orbitPinging=orbitEnabled()&&!stopping&&(probing()||pending&&McrfVpnService.connectionStep=="server_ping")
        val runningEntry=catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId}
        val cycleOrbit=orbitEnabled()&&connected&&!pending&&!orbitPinging&&!wifiPaused&&challenge==null&&runningEntry!=null
        val caption=when {
            stopping->"Остановка движка…"
            orbitPinging->"Проверяем пинг серверов…"
            pending->ConnectionPresentation.progressLabel(McrfVpnService.status,McrfVpnService.connectionStep)
            orbitFlood->"VK Flood"
            directPaused->"VPN приостановлен"
            connected->if(prefs.getBoolean("tun",true))"VPN подключён"else"Прокси подключён"
            failed->"Ошибка подключения"
            else->"Отключено"
        }
        if(!cycleOrbit)(status as? ConnectionCaptionView)?.label(caption)
        connectionHint?.text = if(stopping)"Ждём завершения очистки"else ""
        if(failed)connectionHint?.text=failure+"\nОткрыть логи"
        if(directPaused)connectionHint?.text="Трафик заблокирован · выключите тест или отключите VPN"
        connectionHint?.apply {maxLines=if(failed)Int.MAX_VALUE else 3;ellipsize=if(failed)null else TextUtils.TruncateAt.END;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        if(wifiPaused&&!pending&&!orbitPinging){status?.text="БС на паузе · Wi-Fi";connectionHint?.text="Wi-Fi напрямую · нажмите, чтобы отменить автовозврат БС"}
        if(!orbitPinging&&challenge!=null&&(current==ClientSettingsNavigation.WHITELISTS||orbitEnabled()&&connectionSectionWhitelists())){
            status?.text="Нужна проверка VK";connectionHint?.text="Открыть капчу · осталось ${challenge.remainingMillis()/1000} с"
        }
        val phase=ConnectionProgressState.resolve(McrfVpnService.status,McrfVpnService.connectionStep,pending,connected,failed,stopping,wifiPaused&&!pending,challenge!=null&&current==ClientSettingsNavigation.WHITELISTS)
        connectionProgress?.state(phase)
        (status as? ConnectionCaptionView)?.setLoadingShimmer(AppearanceMotion.pixelLoading(this)&&phase.moving)
        connectionIndicator?.let {it.visibility=if(it.showForConnectionPhase(phase,pending,stopping))View.VISIBLE else View.GONE}
        connectionHint?.setTextColor(if(failed)Loft.ERROR else Loft.MUTED)
        if(challenge!=null)connectionHint?.setTextColor(Loft.ACCENT)
        connectionHint?.visibility=if(connectionHint?.text.isNullOrBlank())View.GONE else View.VISIBLE
        status?.setTextColor(if(!orbitPinging&&(failed||orbitFlood&&!pending))Loft.ERROR else if(connected){if(Loft.dark)0xff56d5a2.toInt()else 0xff075d46.toInt()} else Loft.INK)
        val snapshot = TrafficState.current
        val runningName=runningEntry?.let(ServerDisplay::name)
        connectionTarget?.apply {
            maxLines=1
            text=ConnectionPresentation.target(connected,false,runningName,runningEntry?.host,runningEntry?.whitelists)
            if(connected){
                val label=if(prefs.getBoolean("tun",true))"VPN подключён"else"Прокси подключён"
                if(connect is ToggleConnectionButton){
                    connectionProgress?.connected(label,runningName,runningEntry?.host)
                    text=""
                }else if(!prefs.getBoolean(ConnectionAppearance.COMPACT,true)){
                    (status as? ConnectionCaptionView)?.connected(label,runningName,runningEntry?.host)
                    text=""
                }else text=runningName.orEmpty()
                connectionHint?.visibility=View.GONE
            }else connectionHint?.visibility=if(connectionHint?.text.isNullOrBlank())View.GONE else View.VISIBLE
            visibility=if(text.isEmpty())View.GONE else View.VISIBLE
            (parent as? View)?.visibility=visibility
            if(!orbitEnabled())connectionAddress?.apply {text=if(connected&&connect !is ToggleConnectionButton&&prefs.getBoolean(ConnectionAppearance.COMPACT,true))runningEntry?.host?.filterNot(Char::isISOControl).orEmpty()else "";visibility=if(text.isEmpty())View.GONE else View.VISIBLE}
        }
        val visibleBs=current==ClientSettingsNavigation.WHITELISTS
        selectedServer?.text=if(connected&&runningEntry!=null&&ConnectionSections.belongs(runningEntry,visibleBs))runningEntry.name else findSelected()?.takeIf {ConnectionSections.belongs(it,visibleBs)}?.name?:"Сервер не выбран"
        connectionStreams?.apply {
            val show=connected&&!pending&&snapshot.turn&&snapshot.streams>=0&&snapshot.server==runningEntry?.name
            text=if(show)snapshot.streams.toString()else ""
            contentDescription=if(show)TrafficState.streamLabel(snapshot)else null
            visibility=if(show)View.VISIBLE else View.GONE
        }
        if(cycleOrbit&&runningEntry!=null){
            val route=orbitRoutingLabel?.invoke(runningEntry.id).orEmpty()
            (status as? ConnectionCaptionView)?.orbitCaption(runningEntry.id,caption,
                ServerDisplay.name(runningEntry),route,connectionStreams)
        }
        modeHint?.text = if (shownVpn) "Системный VPN" else if(active()&&McrfVpnService.localProxyPort>0) "Только прокси · 127.0.0.1:${McrfVpnService.localProxyPort}" else "Только локальный прокси"
    }
    internal fun changeMode(vpn: Boolean) {
        if (working() || prefs.getBoolean("tun", true) == vpn) return
        if(McrfVpnService.directTunActive()){toast("Сначала выключите тест прямого TUN или отключите VPN");return}
        if(!mergedConnections()&&active()&&catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId}?.let { !ConnectionSections.belongs(it,connectionSectionWhitelists()) }==true){toast("Сначала отключи соединение в другом разделе");return}
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
        val (mode,_)=settingsExperience()
        val search=editor("Поиск по настройкам").apply {setSingleLine(true);tag="settings-search"}
        val rows=mutableListOf<Pair<View,String>>()
        page.addView(search,space(0,12))
        fun link(target:String,detail:String) {
            if(!UsageFeatures.visible(target,features)||!ClientSettingsNavigation.visible(target,mode)||
                tvUi&&!TvUi.settingsVisible(this,target))return
            val row=SettingsUiNodes(this).actionRow(target,detail,"arrow","Открыть: $target"){
                val query=search.text.toString().trim()
                if(target==ClientSettingsNavigation.ADVANCED&&query.isNotEmpty())openAdvanced(SettingsSearchIndex.advancedAnchor(query),query)else navigate(target)
            }.apply {tag="settings-page-$target"}
            page.addView(row,space(0,12));rows.add(row to "$target $detail ${if(mode==ExperienceMode.EXPERT||target==ClientSettingsNavigation.BETA)SettingsSearchIndex.keywords(target)else""}")
        }
        ClientSettingsNavigation.rootLeading.forEach {target->link(target,when(target) {
            ClientSettingsNavigation.INVITE->"Подписка и 12 серверов по персональному коду"
            ClientSettingsNavigation.BETA->"Экспериментальные функции и запись трафика"
            ClientSettingsNavigation.APPEARANCE->if(tvUi)"AMOLED с фоном и шрифты"else"Цвета светлой и тёмной темы"
            else->"Установка и управление сервером · SSH и панели"
        })}
        link(ClientSettingsNavigation.BEHAVIOR,if(tvUi)"Автозапуск, переподключение и уведомления"else"Переподключение, уведомления и батарея")
        link(ClientSettingsNavigation.VPN,"IPv6, локальные сети и прокси на телефоне")
        link(ClientSettingsNavigation.UPDATES,"Версия ${BuildConfig.VERSION_NAME} · ${if(mode==ExperienceMode.EXPERT)"приложение и движки"else"обновление приложения"}")
        link(ClientSettingsNavigation.ADVANCED,"TUN, TLS, Multiplex, QUIC, дополнительные DNS и прокси")
        val empty=text("Ничего не найдено. Проверь запрос или выбор функций в «Скрыть разделы».",12,Loft.MUTED).apply {visibility=View.GONE}
        page.addView(empty)
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
        if(!tvUi&&mode==ExperienceMode.SIMPLE)page.addView(text(ExperienceModeStore.SAVED_COPY,12,Loft.MUTED),space(0,12))
        if(mode==ExperienceMode.EXPERT){
            val backup = card().apply {tag="backup-settings"}
            backup.addView(text("Бэкап и восстановление",16,Loft.INK))
            fun backupAction(label:String,description:String,action:()->Unit)=button(label,false).apply {
                contentDescription=description;StandardButtons.compact(this@MainActivity,this);textSize=12f
                setOnClickListener {action()}
            }
            fun backupRow(first:View,second:View)=LinearLayout(this).apply {
                addView(first,LinearLayout.LayoutParams(0,dp(48),1f).apply {marginEnd=dp(4)})
                addView(second,LinearLayout.LayoutParams(0,dp(48),1f).apply {marginStart=dp(4)})
            }
            backup.addView(backupRow(
                backupAction("Создать","Создать бэкап"){BackupUi(this).export()},
                backupAction("Восстановить","Восстановить бэкап"){BackupUi(this).chooseRestore()}
            ).apply {tag="backup-actions"},space(10,0))
            backup.addView(backupRow(
                backupAction("Откат","История версий и откат"){startActivity(Intent(this,VersionHistoryActivity::class.java))}.apply {tag="version-history"},
                backupAction("Сброс","Восстановить настройки по умолчанию"){BackupUi(this).resetDefaults()}
            ),space(8,0))
            page.addView(backup,space(0,16))
            rows.add(backup to "Бэкап резервная копия экспорт импорт восстановление откат история версий сброс")
        }
        if(tvUi)page.addView(card().apply {
            addView(text("Дополнительные функции",16,Loft.INK),space(0,6))
            fun featureSwitch(label:String,key:String) {
                addView(android.widget.Switch(this@MainActivity).apply {
                    text=label
                    tag=key
                    tune(this)
                    setTextColor(Loft.INK)
                    minHeight=dp(48)
                    isChecked=prefs.getBoolean(key,false)
                    setOnCheckedChangeListener {_,enabled->
                        prefs.edit().putBoolean(key,enabled).apply()
                        if(key==TvUi.SHOW_AUTO_BS&&!enabled)setBsDetection(false)
                    }
                },space(0,4))
            }
            featureSwitch("Показывать автоопределение БС",TvUi.SHOW_AUTO_BS)
            featureSwitch("Показывать VK Turn",TvUi.SHOW_VK_TURN)
        },space(0,12))
        if(!tvUi)page.addView(text("Сообщество MCRF в Telegram ↗",13,Loft.ACCENT).apply {
            tag="mcrf-community-settings";minimumHeight=dp(48)
            gravity=Gravity.CENTER;setPadding(dp(4),dp(8),dp(4),dp(8))
            background=null;isFocusable=true
            setOnClickListener {McrfCommunityLink.open(this@MainActivity)}
        },space(0,12))
    }
    private fun settingsPage(name:String) {
        val (mode,_)=settingsExperience()
        if(!ClientSettingsNavigation.visible(name,mode)) {
            page.addView(text(ExperienceModeStore.SAVED_COPY,14,Loft.INK),space(0,12))
            if(!tvUi)page.addView(button("Перейти в экспертный режим",false).apply {setOnClickListener {navigate(ClientSettingsNavigation.EXPERIENCE)}},space(0,12))
            return
        }
        when(name) {
            ClientSettingsNavigation.BETA -> betaSettings()
            ClientSettingsNavigation.INVITE -> page.addView(InviteCodeUi(this){kinds->engineImport.showEngines(kinds)}.view(),space(0,16))
            ClientSettingsNavigation.DEPLOY -> deployment()
            ClientSettingsNavigation.MINIMALISM -> page.addView(UsageFeaturesUi.settings(this),space(0,16))
            ClientSettingsNavigation.APPEARANCE -> page.addView(UserThemeColorsUi.view(this),space(0,16))
            ClientSettingsNavigation.ADVANCED -> {
                page.addView(SettingsSectionUi.build(this,"advanced-settings","Расширенные настройки") {
                val page=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
                val features=UsageFeaturesUi.selected(this)
                fun family(title:String){page.addView(text(title,14,Loft.ACCENT).apply {setAccessibilityHeading(true);setPadding(dp(12),dp(12),dp(12),dp(8));background=Loft.fieldBackground(this@MainActivity)},space(4,8))}
                if(UsageFeatures.visible(ClientSettingsNavigation.XRAY,features)) {
                    family("Основное ядро · безопасность и транспорт")
                    page.addView(SettingsSectionUi.build(this,"advanced-core","Основное ядро") {CoreTuningUi(this).advancedView()},space(0,16))
                    family("Отдельные модули · Xray и TLS")
                    page.addView(SettingsSectionUi.build(this,"advanced-xray","Параметры Xray") {OptionalCoreUi(this).advancedXrayView()},space(0,16))
                }
                if(UsageFeatures.visible(ClientSettingsNavigation.TLS,features))page.addView(SettingsSectionUi.build(this,"advanced-tls","Параметры TLS") {OptionalCoreUi(this).advancedTlsView()},space(0,16))
                family("VPN и прокси · интерфейсы и локальная сеть")
                page.addView(SettingsSectionUi.build(this,"advanced-tun","VPN и прокси") {ClientTunUi(this).advancedView()},space(0,16))
                family("DNS · ответы и разрешение доменов")
                page.addView(SettingsSectionUi.build(this,"advanced-dns","Параметры DNS") {AdvancedDnsEditor(this,routing).advancedView()},space(0,16))
                family("Базы GeoIP и GeoSite")
                page.addView(SettingsSectionUi.build(this,"advanced-geo","Базы GeoIP и GeoSite") {
                    LinearLayout(this).apply {
                        orientation=LinearLayout.VERTICAL
                        addView(GeoDataEditor(this@MainActivity,routing).build(),space(0,12))
                        addView(GeoDataEditor(this@MainActivity,routing).advancedView())
                    }
                },space(0,16))
                page
                },space(0,16))
            }
            ClientSettingsNavigation.DNS -> {settingsDns();page.addView(AdvancedDnsEditor(this,routing).build(),space(0,16))}
            ClientSettingsNavigation.BEHAVIOR -> page.addView(ClientBehaviorUi.build(this),space(0,16))
            ClientSettingsNavigation.VPN -> page.addView(ClientTunUi(this).view(),space(0,16))
            ClientSettingsNavigation.GEO -> page.addView(GeoDataEditor(this,routing).build(),space(0,16))
            ClientSettingsNavigation.VK -> {
                val host=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;tag="vk-turn-settings-host"}
                val owner=page
                val controller=AndroidDeployUi(this,AndroidDeployStore(this),AndroidDeployUi.backend(this)){importDialog(it)}
                deployUi=controller
                host.addView(captchaChoice(findSelected()?.takeIf {ConnectionSections.belongs(it,true)}),space(0,12))
                val extras=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
                host.addView(controller.vkSettings {
                    if(current!=ClientSettingsNavigation.VK||page!==owner||deployUi!==controller||!host.isAttachedToWindow)return@vkSettings
                    // Fill the existing host after its first frame, without render()/fade.
                    if(mode==ExperienceMode.EXPERT) {
                        extras.addView(VkTurnUi.serverSettings(this),space(0,16));extras.addView(BsWifiUi.view(this),space(0,16));extras.addView(WdttMasqueUi.view(this),space(0,16))
                    } else if(!tvUi)extras.addView(text("Автопауза БС в Wi-Fi может отключать системный VPN: трафик пойдёт напрямую. Имя Wi-Fi можно подделать. Удержание CPU увеличивает расход батареи. ${ExperienceModeStore.SAVED_COPY}",12,Loft.MUTED),space(0,12))
                },space(0,16))
                host.addView(extras)
                if(!tvUi&&returnToDeployVk)host.addView(button("Вернуться к настройке SWG",false).apply {tag="androidDeploy.returnFromVk";setOnClickListener {returnToDeployVk=false;navigate("Автодеплой")}},space(0,12))
                page.addView(host)
            }
            ClientSettingsNavigation.UPDATES -> {
                page.addView(EngineUpdateAppUi.view(this),space(0,12))
                page.addView(EngineUpdateInventoryUi.view(this,primaryHost,simple=mode==ExperienceMode.SIMPLE),space(0,12))
                if(mode==ExperienceMode.EXPERT&&UsageFeatures.Feature.OPENFLUX in UsageFeaturesUi.selected(this))page.addView(EngineUpdateUi.view(this),space(0,12))
            }
        }
    }
    internal fun showWarpTools()=settingsExperience().first==ExperienceMode.EXPERT&&UsageFeatures.Feature.WARP in UsageFeaturesUi.selected(this)
    internal fun quickSettingsDestinationVisible(page:String)=
        ClientSettingsNavigation.canonical(page)!=ClientSettingsNavigation.canonical(current)&&
            ClientSettingsNavigation.visible(page,settingsExperience().first)&&
            UsageFeatures.visible(page,UsageFeaturesUi.selected(this))
    private fun settingsExperience():Pair<ExperienceMode,ConditionProfile?> {
        if(tvUi)return ExperienceMode.SIMPLE to null
        val base=runCatching {ExperienceModeStore(this).load()}.getOrDefault(ExperienceMode.EXPERT)
        return base to null
    }
    private fun schedule() {
        try {
            val profiles=ConditionProfilesUi.view(this){render()}
            val scroll=activeScroll
            val position=scrollPositions[ClientSettingsNavigation.SCHEDULE]?:0
            // The generic first-frame restoration runs while profiles still show
            // their loading label. Restore again when the asynchronous body arrives.
            if(position>0&&scroll!=null&&profiles is ViewGroup)profiles.setOnHierarchyChangeListener(object:ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewRemoved(parent:View?,child:View?)=Unit
                override fun onChildViewAdded(parent:View?,child:View?) {
                    if(child !is ViewGroup)return
                    profiles.setOnHierarchyChangeListener(null)
                    scroll.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw():Boolean {
                            scroll.viewTreeObserver.removeOnPreDrawListener(this)
                            if(activeScroll===scroll&&current==ClientSettingsNavigation.SCHEDULE)scroll.scrollTo(0,position)
                            return true
                        }
                    })
                }
            })
            page.addView(profiles,space(0,16))
        }
        catch(_:Exception){page.addView(text("Не удалось прочитать профили. Данные не изменены.",14,Loft.ERROR),space(0,12))}
    }
    private fun betaSettings() {
        UsageFeaturesUi.betaSettings(this,page)
        page.addView(SettingsUiNodes(this).actionRow("Статистика серверов","Трафик и число сеансов по подключениям","arrow","Открыть статистику серверов"){
            val content=ServerUsageUi.view(this)
            AppTypography.applyTree(content)
            ThemedDialogBuilder(this,compactActions=true).setCustomTitle(text("Статистика серверов",21,Loft.INK).apply {
                setAccessibilityHeading(true)
                setPaddingRelative(dp(16),dp(16),dp(16),dp(6))
            }).setView(CompactUi.scroll(this,content,.69f)).setNegativeButton("Закрыть",null).show()
        }.apply {tag="server-usage-open"},space(0,12))
        page.addView(SettingsUiNodes(this).actionRow("Диагностика · Beta","Один сервер или несколько · доступность сервисов","arrow","Открыть диагностику") {
            ConnectionDiagnosticsUi.show(this)
        }.apply {tag="connection-diagnostics-open"},space(0,12))
        fun local(title:String,section:String,viewTag:String) {
            page.addView(SettingsUiNodes(this).actionRow(title,"Локальные данные и управление","arrow","Открыть: $title"){
                LogsPage(this).showLocalDiagnostics(section)
            }.apply {tag=viewTag},space(0,12))
        }
        local("Журнал соединений · Beta","journal","traffic-journal-open")
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
        val editing = try { val snapshot=routing.snapshotWithGeneration();DnsEditor(this,snapshot.first.getJSONObject("globalDns")) to snapshot.second } catch (_: Exception) { page.addView(text("Не удалось прочитать настройки DNS. Бэкап и остальные настройки доступны ниже.", 14, Loft.ERROR)); null }
        val dns=editing?.first
        if(dns!=null){
        val generation=requireNotNull(editing).second
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
                try {
                    DnsConfig.build(setting,"chosen","bootstrap")
                    routing.editInGeneration(generation){it.put("globalDns",JSONObject(setting.toString()))};dnsDraft.saved(setting)
                    ui { if (revision == requested) { dnsStatus.text = "DNS сохранён · применится при следующем подключении"; dnsStatus.setTextColor(Loft.MUTED) } }
                } catch (error: Exception) {
                    val detail=EditorSaveFailure.describe(error,EditorSaveFailure.Phase.SAVE)
                    ClientEvents.record(this,"DNS",detail)
                    ui { if(revision==requested){dnsStatus.text=detail;dnsStatus.setTextColor(Loft.ERROR)} }
                }
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
        if(request==RuAppTunnelNotice.PERMISSION_REQUEST){if(!isFinishing&&!isDestroyed)render();return}
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
    fun connectionSectionWhitelists():Boolean {
        if(mergedConnections()&&ConnectionAppearance.isConnection(current)){
            val running=if(active())catalogue.entries().firstOrNull {it.id==McrfVpnService.selectedId} else null
            return (running?:findSelected())?.whitelists?: (current==ClientSettingsNavigation.WHITELISTS)
        }
        return current==ClientSettingsNavigation.WHITELISTS||ClientSettingsNavigation.isSettings(current)&&settingsOrigin==ClientSettingsNavigation.WHITELISTS
    }
    fun connectionSectionPolicy()=ConnectionSections.policy(connectionSectionWhitelists())
    fun active() = McrfVpnService.directTunActive()||McrfVpnService.wifiPaused()||McrfVpnService.cleanupPending()||McrfVpnService.status.startsWith("VPN подключён") || McrfVpnService.status.startsWith("Прокси ") || McrfVpnService.status == "Подключение…" || McrfVpnService.status=="Восстановление соединения…" || McrfVpnService.status=="Возвращаемся в VPN…" || McrfVpnService.status.startsWith("Переключаем движок")
    private fun directStopAvailable()=McrfVpnService.directTunActive()&&!McrfVpnService.cleanupPending()
    private fun stopConnection() {serverSwitch.cancel();handler.removeCallbacks(serverSwitchPoll);startService(Intent(this, McrfVpnService::class.java).setAction(McrfVpnService.STOP))}
    private fun prepareConnection(diagnostic: Boolean,trafficChoiceChecked:Boolean=false,requestedWhitelists:Boolean?=null,
                                  preferredServerId:String?=null) {
        if (working()) return
        if(McrfVpnService.directTunActive()){toast("Сначала выключите тест прямого TUN или отключите VPN");return}
        connectionPreparationError=null
        val whitelists=requestedWhitelists?:connectionSectionWhitelists()
        val sectionPolicy=ConnectionSections.policy(whitelists)
        val sectionEntries=ConnectionSections.entries(catalogue.entries(),whitelists)
        if(McrfVpnService.cleanupPending()){toast("Дождись остановки движка");return}
        if(!diagnostic&&catalogue.entries().isNotEmpty()) {
            if(sectionEntries.isEmpty()) {
                toast("В разделе «${if(whitelists)ClientSettingsNavigation.WHITELISTS else "VPN"}» пока нет серверов");return
            }
            if(preferredServerId!=null){
                val entry=sectionEntries.firstOrNull {it.id==preferredServerId}?:run {toast("Выбранный сервер больше недоступен");return}
                if(selected!=entry.id||automatic){
                    if(!prefs.edit().remove("warpSelectionJournal").putString("selected",entry.id).putBoolean("automatic",false).commit()){
                        alert("Подключение","Не удалось сохранить выбранный сервер.");return
                    }
                    selected=entry.id;automatic=false;refreshSelectedServer()
                }
            }else run {
                // The detector's policy is the only user-facing auto choice.
                // Do not resurrect the removed marked-pool switch from old prefs.
                val recent=BsAutoSelectionPolicy.selected(prefs)==BsAutoSelectionPolicy.RECENT
                val manual=BsAutoSelectionPolicy.last(prefs,whitelists)
                val chain=try {FallbackChainStore(this).load()}catch(_:Exception){alert("Автовыбор","Не удалось прочитать последовательность серверов.");return}
                val sequenceEnabled=chain.enabled&&chain.ids(whitelists).isNotEmpty()
                if(!sequenceEnabled&&BsAutoSelectionPolicy.selected(prefs)==BsAutoSelectionPolicy.OFF&&sectionEntries.none {it.id==manual}){
                    alert("Автовыбор выключен","Сначала выберите сервер ${if(whitelists)"БС"else"VPN"} вручную.");return
                }
                val allowed=(if(sequenceEnabled||BsAutoSelectionPolicy.selected(prefs)!=BsAutoSelectionPolicy.OFF)catalogue.autoCandidates(sectionEntries)else sectionEntries).filter {ClientPrimarySelection.selected(this)!="steer"||SteerAutomaticPool.compatible(it)}
                val sequential=if(sequenceEnabled)AutoSelection.sequential(allowed,chain.ids(whitelists))else emptyList()
                if(sequenceEnabled&&sequential.isEmpty()){alert("Автовыбор","Выберите серверы для последовательного подключения в этом разделе.");return}
                val preferred=if(sequenceEnabled)sequential.firstOrNull {it.id==selected}?.id?:sequential.first().id else if(prefs.getBoolean(BsDetectionMonitor.KEY,false)&&BsAutoSelectionPolicy.selected(prefs)!=BsAutoSelectionPolicy.OFF)BsAutoSelectionPolicy.startup(
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
        val requestedVpn=UsageFeatures.preferredVpn(UsageFeaturesUi.selected(this),prefs.getBoolean("tun",true))
        val standaloneWgHint=!diagnostic&&WgAddonPolicy.standaloneProfile(findSelected(),requestedVpn,automatic)&&
            WgAddonPolicy.standaloneHint(this)
        val nativeBsHint=!diagnostic&&!automatic&&findSelected()?.carrier?.let {profile->
            BsNativeContract.connectionHint(this,profile,requestedVpn)
        }==true
        val optionalRouterEntry=findSelected()?.takeIf {
            !diagnostic&&!automatic&&StandaloneRouterSelection.hint(this,it,requestedVpn)
        }
        if(!nativeBsHint&&!standaloneWgHint&&optionalRouterEntry==null&&ClientPrimarySelection.selected(this)==null){offerPrimaryChoice();return}
        if(!nativeBsHint&&!standaloneWgHint&&optionalRouterEntry==null&&!trafficChoiceChecked&&!prefs.getBoolean(ClientPrimarySelection.DUAL_CONFIRMED,false)) {
            busy=true
            worker.execute {
                val ask=ClientPrimarySelection.needsTrafficChoice(this)
                ui {
                    busy=false
                    if(ask){pendingPrimaryConnection=diagnostic;offerPrimaryChoice(force=true)}
                    else prepareConnection(diagnostic,trafficChoiceChecked=true,requestedWhitelists=whitelists,preferredServerId=preferredServerId)
                }
            }
            return
        }
        if(EngineUpdates.installationPending(this)||EngineUpdateScripts.mutationPending()){alert("Обновление движка","Завершите обновление движка перед подключением.");return}
        if (!diagnostic && !automatic) findSelected()?.takeIf { it.carrier != null }?.let { entry ->
            if(!nativeBsHint&&CarrierPrimaryChoice.required(ClientPrimarySelection.selected(this),entry.protocol)) {
                ThemedDialogBuilder(this).setTitle("Нужен совместимый основной движок")
                    .setMessage(CarrierPrimaryChoice.explanation(entry.protocol))
                    .setNegativeButton("Отмена",null).setPositiveButton("Выбрать движок") {_,_->
                        pendingPrimaryConnection=diagnostic;offerPrimaryChoice(force=true)
                    }.show()
                return
            }
            val missing=EnginePackages.missingForProfile(this,requireNotNull(entry.carrier))
            if (missing.isNotEmpty()) { engineImport.showEngines(missing); return }
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
                if(optionalRouterEntry!=null) {
                    val state=RoutingConnectionBinding.forManualVpn(routing.snapshot())
                    val generated=ServerImport.build(listOf(optionalRouterEntry),optionalRouterEntry.id,false,60,50,"Cloudflare")
                    DnsConfig.applyGenerated(generated,state.getJSONObject("globalDns"))
                    val cancelled=java.util.function.BooleanSupplier {
                        isDestroyed||isFinishing||automatic||findSelected()?.id!=optionalRouterEntry.id||
                            ClientPrimarySelection.selected(this)!=null
                    }
                    if(StandaloneRouterSelection.preflight(this,optionalRouterEntry,generated,state,cancelled)==null){
                        ui {busy=false;connect?.isEnabled=true;offerPrimaryChoice()}
                        return@execute
                    }
                    check(!cancelled.asBoolean){"Профиль изменился. Повторите подключение."}
                    val number=ClientTunStore(this).load().choosePort()
                    val content=generated.toString();ProfileStore(this).write(content);savedConfig=content
                    check(prefs.edit().putBoolean("tun",vpn).putInt("port",number)
                        .putBoolean("applyRoutes",true).putBoolean("catalogueProfile",true).commit())
                    ui {preparedProxyPort=number;busy=false;connect?.isEnabled=true
                        val consent=if(vpn)VpnService.prepare(this)else null
                        if(consent!=null)startActivityForResult(consent,1)else launchConnection()
                    }
                    return@execute
                }
                val wireGuardProfile=if(diagnostic)WgAddonPolicy.usesWireGuard(JSONObject(raw))
                    else !automatic&&findSelected()?.protocol?.let(WgAddonPolicy::isWireGuard)==true
                val packetEntry=if(diagnostic)null else findSelected()?.takeIf {WgAddonPolicy.standaloneProfile(it,vpn,automatic)}
                if(packetEntry!=null&&WgAddonPolicy.prefersStandalone(this)) {
                    if(EnginePackages.missing(this,setOf(WgAddonPolicy.KIND)).isNotEmpty()) {
                        ui {busy=false;connect?.isEnabled=true;installEngine(WgAddonPolicy.KIND)}
                        return@execute
                    }
                    val state=routing.snapshot()
                    ClientPacketSelection.validateConfiguration(this,packetEntry.original,ClientPacketSelection.validationDial(packetEntry.host))
                    val generated=ServerImport.build(listOf(packetEntry),packetEntry.id,false,60,50,"Cloudflare")
                    DnsConfig.applyGenerated(generated,state.getJSONObject("globalDns"))
                    val content=generated.toString();ProfileStore(this).write(content);savedConfig=content
                    check(prefs.edit().putBoolean("tun",true).putBoolean("applyRoutes",true).putBoolean("catalogueProfile",true).commit())
                    ui {busy=false;connect?.isEnabled=true;val consent=VpnService.prepare(this);if(consent!=null)startActivityForResult(consent,1)else launchConnection()}
                    return@execute
                }
                val nativeBs=nativeBsHint&&findSelected()?.carrier?.let {profile->
                    BsNativeContract.connectionAvailable(this,profile,vpn)
                }==true
                check(!nativeBsHint||nativeBs){"Модуль БС изменился; повтори подключение."}
                val effectivePrimary=if(nativeBs)BsNativeContract.CHOICE else ClientPrimarySelection.effectiveWireGuardChoice(this,ClientPrimarySelection.requireSelected(this),wireGuardProfile)
                val requiredPrimary=if(nativeBs)null else EngineUpdatePrimary.kindForChoice(effectivePrimary)
                if(requiredPrimary!=null&&EnginePackages.missing(this,setOf(requiredPrimary)).isNotEmpty()) {
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
                    val routingGeneration=routing.generation()
                    val snapshot=ProfilePolicyRuntime.routing(this) {
                        SubscriptionRouting.projectPool(routing.snapshot(),listOf(entry.id),catalogue.subscriptions())
                    }
                    val boundRoutes=RoutingConnectionBinding.prepare(this,if(automatic)snapshot else RoutingConnectionBinding.forManualVpn(snapshot),catalogue.entries(),entry.whitelists,catalogue.probes())
                    if(RouteServerPlan.activeIds(boundRoutes).isNotEmpty()){
                        PresetAssets.requireAvailable(this,boundRoutes)
                        val preparation=NativeRoutePreparation.capture(this,entry,boundRoutes,catalogue.entries())
                        val number=ClientTunStore(this).load().choosePort()
                        preparation.validate(this,true,number,java.util.function.BooleanSupplier {isDestroyed||isFinishing})
                        val content=preparation.source().toString()
                        ProfileStore(this).write(content);savedConfig=content
                        check(prefs.edit().putBoolean("tun",true).putInt("port",number).putBoolean("applyRoutes",true).putBoolean("catalogueProfile",true).commit())
                        ui {preparedProxyPort=number;busy=false;connect?.isEnabled=true;val consent=VpnService.prepare(this);if(consent!=null)startActivityForResult(consent,1)else launchConnection()}
                        return@execute
                    }
                    val result=ClientPrimarySelection.steerSnapshot(this,boundRoutes,vpn,prepareCachedGeo={
                        !isDestroyed&&!isFinishing&&routing.generation()==routingGeneration
                    })
                    require(result.supported) {ClientPrimarySelection.steerFailure(result)}
                    if(CompactProtocolPolicy.eligible(entry))CompactProtocolSession.preflight(this,entry,routing.snapshot())
                    if(!ConnectionStartPolicy.skipTemporarySteerWorker(automatic,RouteServerPlan.activeIds(routing.snapshot()).isNotEmpty())) {
                    val session=ClientPrimaryWorker.connect(this,"steer",System.nanoTime().coerceAtLeast(1),null)
                    try {
                        entry=(autoPool?.candidates?:listOf(entry)).firstOrNull {candidate->
                            val effective=if(CompactProtocolPolicy.eligible(candidate)){
                                val request=CompactProtocolSession.preflight(this,candidate,routing.snapshot())
                                OptionalCoreProcess.check(this,request,java.util.function.BooleanSupplier{isDestroyed||isFinishing})
                                // Validation only. No listener, protocol login, stored
                                // profile rewrite or guessed connectivity proof.
                                CompactProtocolPolicy.effective(candidate,10900,java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString())
                            }else candidate
                            session.validate(SteerProbePlan.profile(effective),result.settingsJson!!,org.mcrf.engine.spi.v1.Engine.Mode.VPN).supported
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
                val routes = if (diagnostic) null else RoutingConnectionBinding.prepare(this,routing.snapshot().let {
                    if(vpn&&!automatic)RoutingConnectionBinding.forManualVpn(it)else it
                },catalogue.entries(),whitelists,catalogue.probes())
                if (!diagnostic && catalogue.entries().isNotEmpty()) {
                    val boundPrimary=if(RoutingConnectionBinding.enabled(routes!!))(routes.optJSONObject(RoutingConnectionBinding.DEFAULT)?.let{RouteServerPlan.ids(it).firstOrNull()}?:RouteServerPlan.activeIds(routes).firstOrNull())?.let{id->catalogue.entries().firstOrNull{it.id==id}}else null
                    val chosen = boundPrimary?:findSelected()
                    if(nativeBs&&boundPrimary!=null&&boundPrimary.id!=selected) {
                        require(boundPrimary.carrier?.let {BsNativeContract.connectionAvailable(this,it,vpn)}==true) {
                            "БС: привязанный в правилах сервер не поддерживает автономное подключение."
                        }
                    }
                    if (!automatic && chosen?.carrier != null) {
                        NativeCarrier.executable(this, EngineModules.carrierKind(chosen.carrier))
                        NativeCarrier.requireOptions(this,chosen.carrier)
                    }
                    val policy=sectionPolicy
                    val pool = if(boundPrimary!=null)listOf(boundPrimary)else ConnectionSections.entries(if (automatic) catalogue.startupEntries(policy,refresh=true) else catalogue.entries(),whitelists)
                    if (automatic && pool.isEmpty()) throw IllegalArgumentException("В этом разделе нет отмеченных совместимых серверов для автовыбора. Отметь хотя бы один сервер нужного раздела.")
                    val generated = if(boundPrimary==null&&automatic&&policy==AutoSelection.BS_ENGINES) BsPlan.build(pool,prefs.getInt("interval",60),AutoSelectionDeviation.millis(prefs),"Cloudflare")
                        else ServerImport.build(pool, boundPrimary?.id?:selected, boundPrimary==null&&automatic, prefs.getInt("interval", 60), AutoSelectionDeviation.millis(prefs), "Cloudflare")
                    if (automatic || chosen == null || chosen.configuration == null) DnsConfig.applyGenerated(generated, routes!!.getJSONObject("globalDns"))
                    content = generated.toString()
                }
                val routeServers=if(diagnostic)emptyList()else catalogue.entries()
                if(!diagnostic) {
                    val ids=RouteServerPlan.activeIds(routes!!).toSet()
                    val extra=routeServers.filter {it.id in ids}.mapNotNull {it.carrier}
                    val template=if(content.trimStart().startsWith("{"))JSONObject(content)else JSONObject()
                    val effectiveRoutes=ProfilePolicyRuntime.routing(this) {
                        val sourceIds=SubscriptionRouting.sourceIds(template,null,routeServers).ifEmpty {listOfNotNull(findSelected()?.id)}
                        SubscriptionRouting.projectPool(routes,sourceIds,catalogue.subscriptions())
                    }
                    if(nativeBs||ConnectionStartPolicy.servicePrepares(false,ids.isNotEmpty()))PresetAssets.requireAvailable(this,effectiveRoutes)
                    else PresetAssets.requireAvailable(this,routes,effectiveRoutes)
                    ConnectTiming.measure(this,"masque_prepare"){WdttMasqueRegistration.prepare(this,template,extra,java.util.function.BooleanSupplier{isDestroyed||isFinishing})}
                }
                // P049_DEFER_BEGIN: raw profile only; actual worker validates the ONE service-built config.
                if(nativeBs||ConnectionStartPolicy.servicePrepares(diagnostic,routes?.let {RouteServerPlan.activeIds(it).isNotEmpty()}?:false)) {
                    val availability=OptionalCorePlan(ProfileConfig.build(content,vpn,number),OptionalCoreStore(this).load(),CoreTuningStore(this).load())
                    for(item in availability.entries())if(EnginePackages.missing(this,setOf(item.request.kind)).isNotEmpty())throw ClientOptionalCoreRequired(item.request.kind)
                    ProfileStore(this).write(content,skipIdentical=nativeBs);savedConfig=content
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
                    else if(error is PresetAssets.DownloadRequired) {
                        val capturedServer=selected
                        val capturedAutomatic=automatic
                        offerPresetDownload(error) {
                            if(selected==capturedServer&&automatic==capturedAutomatic)
                                prepareConnection(diagnostic,true,whitelists,preferredServerId)
                            else toast("Списки загружены. Повтори подключение для выбранного сервера.")
                        }
                    }
                    else {
                        val message=when {
                            primaryFailure!=null->ClientPrimaryPreparationFailure.message(primaryFailure)
                            error is IllegalArgumentException->error.message.orEmpty()
                            else->"Не удалось сохранить или проверить профиль подключения."
                        }
                        connectionPreparationError=ClientEvents.redact(message).ifBlank {"Не удалось подготовить подключение."}.take(600)
                        ClientEvents.record(this,"Ошибка","Ошибка подключения: $connectionPreparationError")
                        updateHomeControl();alert("Подключение",connectionPreparationError.orEmpty())
                    }
                }
            } finally {ConnectTiming.mark(this,"ui_prepare",prepareAt)}
        }
    }
    private var routingDownloadDialog:AlertDialog?=null
    internal fun offerPresetDownload(request:PresetAssets.DownloadRequired, cancelled:()->Unit={}, resume:()->Unit) {
        if(working()||isFinishing||isDestroyed||routingDownloadDialog?.isShowing==true){cancelled();return}
        val dialog=ThemedDialogBuilder(this).setTitle("Геоданные для маршрутов")
            .setMessage("Для продолжения загрузите:\n${request.names.joinToString("\n")}")
            .setNegativeButton("Отмена",null).setPositiveButton("Загрузить",null).create()
        var completed=false
        routingDownloadDialog=dialog
        dialog.setOnDismissListener {
            if(routingDownloadDialog===dialog)routingDownloadDialog=null
            if(!completed)cancelled()
        }
        dialog.setOnShowListener {
            val download=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            download.setOnClickListener {
                if(working())return@setOnClickListener
                busy=true
                dialog.setCancelable(false)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled=false
                download.isEnabled=false
                dialog.setMessage("Загружаем и проверяем геоданные…")
                fun failed() {
                    busy=false
                    dialog.setMessage("Не удалось загрузить или проверить геоданные. Проверьте подключение к интернету и повторите загрузку.")
                    dialog.setCancelable(true)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled=true
                    download.isEnabled=true
                }
                try {
                    worker.execute {
                        try {
                            PresetAssets.download(applicationContext,request)
                            ui {busy=false;completed=true;dialog.dismiss();resume()}
                        } catch(_:Exception) { ui { failed() } }
                    }
                } catch(_:java.util.concurrent.RejectedExecutionException) { failed() }
            }
        }
        dialog.show()
    }
    private var connectionStartedNs=0L
    private fun launchConnection() {
        if(EngineUpdates.installationPending(this)||EngineUpdateScripts.mutationPending()){toast("Завершите обновление движка перед подключением");return}
        connectionPreparationError=null
        val launch=Intent(this, McrfVpnService::class.java).setAction(McrfVpnService.START).putExtra("tun", prefs.getBoolean("tun", true)).putExtra("port", prefs.getInt("port", 10808)).putExtra("applyRoutes", prefs.getBoolean("applyRoutes", true))
        launch.putExtra(ConnectTiming.EXTRA,connectionStartedNs);connectionStartedNs=0
        launch.putExtra("pendingWhitelists",pendingConnectionWhitelists?:findSelected()?.whitelists?:false)
        launch.putExtra(McrfVpnService.MANUAL_VPN,!automatic)
        preparedProxyPort?.let {launch.putExtra("preparedProxyPort",it)};preparedProxyPort=null
        startForegroundService(launch)
    }
    private fun refreshSelectedServer() {
        selectedServer?.text=findSelected()?.takeIf {ConnectionSections.belongs(it,false)}?.name?:"VPN-сервер не выбран"
        autoControlsRefresh?.invoke();captchaControlsRefresh?.invoke();pageRefresh?.invoke();updateHomeControl()
        refreshAutoSelectionUi()
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
            LastConnectionFailure.clear()
        }
        BsAutoSelectionPolicy.remember(prefs,entry)
        refreshSelectedServer()
        if(action.reconnect) {
            val request=serverSwitch.request(id,entry.whitelists,SystemClock.elapsedRealtime())
            startService(Intent(this,McrfVpnService::class.java).setAction(McrfVpnService.STOP).putExtra(ServerSwitchTransition.EXTRA,request.token))
            handler.post(serverSwitchPoll)
            updateHomeControl()
        }
    }
    fun selectRoutingGroup(id:String) {
        if(working()||McrfVpnService.cleanupPending()||ConnectionPresentation.pending(McrfVpnService.status))return
        if(RoutingGroups.active(routing.snapshot())==id)return
        val reconnect=active()
        val generation=routing.generation()
        var saved=false
        var missingEngine:String?=null
        var masqueConsent=false
        var missingPresets:PresetAssets.DownloadRequired?=null
        run(Operation {
            val state=routing.snapshot();RoutingGroups.select(state,id)
            if(reconnect) {
                val plan=RouteServerPlan(state,catalogue.entries())
                try {
                    PresetAssets.requireActive(this,state)
                    RouteServerSession.check(this,plan,java.util.function.BooleanSupplier{isDestroyed||isFinishing})
                    if(plan.entries.any {it.carrier?.optBoolean("rt_masque")==true}) {
                        if(!WdttMasqueStore.accepted(this))throw WdttMasqueConsentRequired()
                        require(WdttMasqueStore.load(this)!=null) {"Для первой подготовки MASQUE отключите MCRF и подключитесь заново. Активная группа правил не изменена."}
                    }
                }catch(error:PresetAssets.DownloadRequired){missingPresets=error;return@Operation ""}
                catch(error:ClientOptionalCoreRequired){missingEngine=error.kind;return@Operation ""}
                catch(_:WdttMasqueConsentRequired){masqueConsent=true;return@Operation ""}
            }
            routing.save(state)
            saved=true
            "Группа правил переключена"
        },true,Runnable {
            missingPresets?.let {request->
                pendingRulesTile=0
                offerPresetDownload(request) {
                    if(routing.generation()==generation)selectRoutingGroup(id)
                    else toast("Списки загружены. Выбери группу маршрутов повторно.")
                }
                return@Runnable
            }
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
    internal fun importTvProfile(profile: TvTransferredProfile) {
        if (!working()) engineImport.showTvProfile(profile)
    }
    fun importDialog(initial: String = "") {
        if(working())return
        if(CompatibilityImportEncrypted.isEncrypted(initial.trim())) {
            CompatibilityImportUi.importEncrypted(this,initial.trim()){importDialog(it)};return
        }
        engineImport.show(initial)
    }
    fun installEngine(kind:String){if(!working())engineImport.showEngine(kind)}
    fun installPrimaryEngine(kind:String){
        if(working())return
        pendingPrimaryInstallKind=kind
        check(prefs.edit().putString("pendingPrimaryInstallKind",kind).commit())
        engineImport.showEngine(kind)
    }
    fun engineInstallConfirmed(kind:String?){
        if(kind==null||pendingPrimaryInstallKind!=kind)return
        pendingPrimaryInstallKind=null
        check(prefs.edit().remove("pendingPrimaryInstallKind").commit())
        if(ClientPrimarySelection.selected(this)==null)primaryHost.selectValidated(EngineUpdatePrimary.choiceId(kind))
    }
    fun startBulkEngineRemoval(kinds:Collection<String>){
        if(working()||bulkEngineRemovalRequest!=0)return
        bulkEngineRemoval.clear();bulkEngineRemoval.addAll(kinds.distinctBy {EngineStorage.size(this,it)?.packageId})
        continueBulkEngineRemoval()
    }
    fun stopProcessesForEngineRemoval(){
        stopOwnedEngineProcesses()
        val started=SystemClock.elapsedRealtime()
        val check=object:Runnable{override fun run(){
            if(!active()&&ProbeCoordinator.measurements==null&&!EngineUpdateGate.blocksUsage(this@MainActivity)){toast("Процессы движков завершены — можно удалять");render();return}
            if(SystemClock.elapsedRealtime()-started>=15_000){alert("Процессы движков","Не все процессы освободились. Дождись завершения системной установки/удаления или остановки Android и повтори.");return}
            handler.postDelayed(this,250)
        }}
        handler.post(check);toast("Завершаем процессы движков…")
    }
    fun stopOwnedEngineProcesses(){
        serverSwitch.cancel();handler.removeCallbacks(serverSwitchPoll)
        ProbeCoordinator.cancel(this)
        telegram.setEnabled(false)
        startService(Intent(this,McrfVpnService::class.java).setAction(McrfVpnService.STOP))
    }
    private fun continueBulkEngineRemoval(){
        while(bulkEngineRemoval.isNotEmpty()){
            val kind=bulkEngineRemoval.removeFirst()
            val request=EngineStorage.launchRemoval(this,kind)
            if(request!=null){bulkEngineRemovalRequest=request;return}
        }
        bulkEngineRemovalRequest=0
        render();toast("Удаление движков завершено")
    }
    fun canOfferEngineUpdates()=!(BuildConfig.DEBUG&&intent.getBooleanExtra("test_no_prompts",false))&&!working()&&!isFinishing&&!isDestroyed&&hasWindowFocus()&&engineImport.state==null&&!onboardingVisible&&primaryChooser?.isShowing!=true&&carrierChallengeDialog?.isShowing!=true&&!EngineUpdates.installationPending(this)
    fun updateInstalledEngines(assets:List<EnginePackage>,completed:()->Unit={}){
        prepareEngineUpdates(assets,emptyList(),completed)
    }
    internal fun replaceUniversalEngineApks(replacements:List<EngineStartupPolicy.AbiReplacement>,completed:()->Unit) {
        prepareEngineUpdates(replacements.map { it.asset },replacements,completed)
    }
    private fun prepareEngineUpdates(assets:List<EnginePackage>,replacements:List<EngineStartupPolicy.AbiReplacement>,completed:()->Unit){
        if(working()||engineImport.state!=null){completed();return}
        worker.execute {
            try {
                val installed=EngineStartupUpdates.installed(applicationContext,replacements.isNotEmpty())
                val eligible=if(replacements.isEmpty())assets else replacements.filter { installed[it.asset.engine]==it.installed }.map { it.asset }
                val remaining=EngineStartupPolicy.stillNeeded(eligible,installed)
                    .filterNot {EnginePackages.installed(applicationContext,it)}
                runOnUiThread {try {if(!isFinishing&&!isDestroyed&&!working()&&engineImport.state==null){if(remaining.isEmpty())toast("Нет обновлений для установленных движков")else engineImport.showUpdates(remaining,replacements.isNotEmpty())}}finally {completed()}}
            } catch(_:Exception) {
                runOnUiThread {try {if(!isFinishing&&!isDestroyed)alert("Обновления движков","Не удалось повторно проверить установленные движки. Пакеты не изменены; открой приложение повторно.")}finally {completed()}}
            }
        }
    }
    override fun onNewIntent(incoming: Intent) { super.onNewIntent(incoming); setIntent(incoming); if(!RuAppTunnelNotice.handle(this)&&!handleQuickTile()){receiveConfigLink(incoming); openPendingLink()} }
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
        if (isFinishing || isDestroyed) { completed?.run(); return }
        if (working()) { completed?.run(); return }; busy = true
        try { worker.execute { try { val message = operation.run(); ui { busy = false; if (rebuild) render(); completed?.run(); toast(message) } }
            catch (error: Exception) { ui { busy = false; render(); completed?.run(); if (error is java.util.concurrent.CancellationException) toast(error.message.orEmpty()) else alert("Настройки", if (error is IllegalArgumentException) error.message.orEmpty() else "Не удалось завершить операцию. Обновите раздел и проверьте текущие данные перед повтором.") } } } }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            busy = false; completed?.run()
            if (!isFinishing && !isDestroyed) toast("Операция не запущена. Откройте раздел повторно.")
        }
    }
    /** Keep editor drafts on failure or busy rejection; cleanup still runs on every completion. */
    fun runAfterSuccess(operation: Operation, rebuild: Boolean, onSuccess: Runnable, completed: Runnable? = null) {
        val outcome = OperationSuccessGate()
        run(Operation { outcome.execute { operation.run() } }, rebuild, Runnable {
            outcome.complete({ onSuccess.run() }, { completed?.run() })
        })
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
    fun working() = busy || loading || poolSaveBusy || serverSwitch.target!=null || McrfVpnService.directTunWorking()
    fun probing()=probeBusy||ProbeCoordinator.measurements!=null
    fun measureServers(operation:Operation){
        if(loading||probing())return
        probeBusy=true
        updateHomeControl()
        probeWorker.execute {try{
            val message=operation.run();ui {probeBusy=false;pageRefresh?.invoke();updateHomeControl();toast(message)}
        }catch(error:Exception){ui {probeBusy=false;pageRefresh?.invoke();updateHomeControl();if(error is java.util.concurrent.CancellationException)toast("Замер остановлен")else alert("Замер серверов",if(error is IllegalArgumentException)error.message.orEmpty()else"Не удалось проверить серверы. Предыдущие результаты сохранены.")}}}
    }
    private fun ui(block: () -> Unit) { runOnUiThread { if (!isFinishing && !isDestroyed) block() } }
    fun toast(message: String) {
        if(message.isNotBlank())
            Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
    }
    internal fun shareConnection(entry: ServerImport.Server) {
        ServerShareUi.show(this, entry.id)
    }

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
        if(request==AnubisModuleInstall.REQUEST){
            AnubisModuleInstall.returned()
            if(current==ClientSettingsNavigation.ANUBIS)render()
            return
        }
        if(request==ShizukuInstall.REQUEST){
            if(result==RESULT_OK&&current==ClientSettingsNavigation.ANUBIS)ManagedAppFreezeUi.resumeSetup(this)
            return
        }
        val fontScope=AppTypography.Scope.entries.firstOrNull {it.request==request}
        if(fontScope!=null){
            val key=AppTypography.CUSTOM
            if(result==RESULT_OK)data?.data?.let {uri->
                runAfterSuccess(Operation {AppTypography.importFont(this,fontScope,key,uri);""},false,Runnable {
                    prefs.edit().putString(fontScope.key,key).apply()
                    applyUserThemeColors()
                })
            }
            return
        }
        if(EngineUpdateMigrationUi.returned(this,request,result))return
        if(BsEngineMigration.returned(this,request,result))return
        if(request==bulkEngineRemovalRequest&&request!=0){
            worker.execute {runCatching {EngineUpdates.applyInstallerResult(applicationContext,request,result)};ui {bulkEngineRemovalRequest=0;continueBulkEngineRemoval()}}
        }else if (request == 9 || request == 10 || EngineUpdateGate.isInstallerRequest(request)) engineImport.returned(request,result)
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
        if (request == 3 && result == RESULT_OK && data?.data != null) { val uri = data.data!!; var imported = ""; run(Operation { imported = contentResolver.openInputStream(uri)?.use(ImportIO::readText) ?: throw IllegalArgumentException("Не удалось прочитать файл."); "Файл прочитан" }, false, Runnable { if (imported.isNotBlank()) importDialog(imported) }) }
    }
    override fun onSaveInstanceState(state: Bundle) { state.putString("lastSettingsPage",lastSettingsPage);state.putString("page", current);state.putString("connectionPage",lastConnectionPage); state.putString("settingsOrigin",settingsOrigin);state.putString("advancedOrigin",advancedOrigin);state.putBoolean("settingsDirectEntry",settingsDirectEntry);state.putBoolean("returnToDeployVk",returnToDeployVk);state.putString("selectedDeployRecipe",selectedDeployRecipe); state.putInt("scroll", activeScroll?.scrollY ?: 0); state.putString("backupPath", pendingBackupPath);state.putString("constructorDraft",layoutEditor.snapshot());super.onSaveInstanceState(state) }
    private fun screenState():ScreenState {activeScroll?.let {scrollPositions[current]=it.scrollY};return ScreenState(current,scrollPositions.toMap(),engineImport.state,pendingConfigLink,settingsOrigin,settingsDirectEntry,lastConnectionPage,advancedOrigin)}
    override fun onRetainNonConfigurationInstance(): Any = screenState()
    override fun startActivityForResult(intent:Intent,requestCode:Int,options:Bundle?) {
        recentsDeparture.launched()
        try {super.startActivityForResult(intent,requestCode,options)}catch(error:Exception){recentsDeparture.launchFailed();throw error}
    }
    override fun onUserLeaveHint(){recentsDeparture.userLeave();super.onUserLeaveHint()}
    internal fun promptForAnubisProvider(){
        if(anubisProviderPrompted||!AnubisLaunchObserver.setupComplete(this)||ManagedAppFreezeUi.providerChosen(this))return
        anubisProviderPrompted=true
        ManagedAppFreezeUi.show(this)
    }

    override fun onResume() { super.onResume();resourceMonitor.onResume();val appearanceChanged=applyForegroundAppearance();if(::engineImport.isInitialized)engineImport.resumed();TelemetryCadence.resumed(this);handler.removeCallbacks(recentsPrivacyCheck);recentsDeparture.resumed();animationResumed=true;(connect as? ForgeConnectionButton)?.resumeAnimation(true); if(!tvUi)telegram.bind();catalogueRefreshRequested=true;handler.removeCallbacks(refresh);handler.post(refresh);EngineUpdates.recover(this);if(!tvUi)ClientBehaviorUi.applyRecents(this);if(!loading&&current==ClientSettingsNavigation.ANUBIS){if(!appearanceChanged)render();promptForAnubisProvider()};if(!loading&&!appearanceChanged&&current==ClientSettingsNavigation.UPDATES&&!working())render() else if(!loading&&!appearanceChanged&&ClientSettingsNavigation.isSettings(current))pageRefresh?.invoke() }
    override fun onPause() {resourceMonitor.onPause();if(::engineImport.isInitialized)engineImport.paused();TelemetryCadence.paused(this);if(!tvUi)ClientBehaviorUi.applyRecents(this);recentsDeparture.paused();animationResumed=false;(connect as? ForgeConnectionButton)?.resumeAnimation(false); telegram.unbind();pendingSettingsSave?.let { handler.removeCallbacks(it); it.run() }; handler.removeCallbacks(refresh); super.onPause();handler.postDelayed(recentsPrivacyCheck,250) }
    override fun onStop() {
        super.onStop()
        resourceMonitor.onStop()
        serviceTestsUi?.close(); serviceTestsUi=null
        serviceDiagnosticsUi?.close(); serviceDiagnosticsUi=null
        RandomAppearance.foreground.stopped(isChangingConfigurations)
        finishHiddenUiIfLeaving()
    }
    private fun applyForegroundAppearance():Boolean{
        try {
            if(UserThemeColorsUi.randomizeForeground(this)){
                activeScroll?.let {scrollPositions[current]=it.scrollY}
                if(!loading)applyUserThemeColors()else {
                    UserThemeColorsUi.configure(this);Loft.apply(Loft.dark)
                    window.setBackgroundDrawable(Loft.rootBackground(this))
                    // Refresh the retained bar's palette even while the loading
                    // shell is kept (no extra render or catalogue work).
                    if(bottomNavigation!=null)updateBottomNavigation()
                }
                return true
            }
        }catch(error:Exception){toast(error.message?:"Не удалось обновить оформление")}
        return false
    }
    private fun finishHiddenUiIfLeaving() {
        if(tvUi)return
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
        if(android.os.Build.VERSION.SDK_INT>=33)systemBackCallback?.let {onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)}
        systemBackCallback=null
        connectionDock?.close();connectionDock=null
        resourceMonitor.close()
        communityUpdateDialog?.setOnDismissListener(null);communityUpdateDialog?.dismiss();communityUpdateDialog=null
        serviceTestsUi?.close(); serviceTestsUi=null
        serviceDiagnosticsUi?.close(); serviceDiagnosticsUi=null
        AppUpdateInstallUi.close(this)
        serverSwitch.cancel();handler.removeCallbacks(serverSwitchPoll)
        presentationWorker.shutdownNow()
        startupAccessPending=null;startupAccessWorker.shutdownNow()
        // An unchosen prompt is not a solved/cancelled challenge. A replacement
        // Activity may offer it again; never retain a window from the old owner.
        carrierChallengePromptId?.let {id->
            if(id.startsWith("openflux:"))OpenFluxVerificationRequests.registry.reopenAfterRecreate(id.removePrefix("openflux:"))
            else CarrierChallenge.get(id)?.opened=false
        }
        carrierChallengeDialog?.dismiss();carrierChallengeDialog=null;carrierChallengePromptId=null
        routingScreenCache.clear();warpController?.detach();warpController=null;deployUi?.detach();deployUi=null;ProbeCoordinator.unobserve(probeRefresh);handler.removeCallbacksAndMessages(null);worker.shutdown();probeWorker.shutdown();super.onDestroy()
    }
    fun alert(title: String, message: String) { if (!isFinishing && !isDestroyed) ThemedDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    fun text(value: String, size: Int, color: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding = false }
    fun flat(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply { setColor(if(color==Loft.SURFACE||color==Loft.FIELD)Loft.translucent(color)else color); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(1), stroke) }
    fun icon(name: String, color: Int): Drawable = if(name in setOf("chain","globe","apps","direct"))RouteEditorIcon(name,color,resources.displayMetrics.density)else Loft.Icon(name, color, resources.displayMetrics.density)
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
    fun tune(control: Switch) { if(tvUi)control.setPaddingRelative(dp(10),control.paddingTop,dp(10),control.paddingBottom); control.setTextColor(Loft.INK); control.textSize = 14f; control.minHeight = dp(48); control.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Loft.ACCENT, Loft.MUTED)); control.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Loft.mix(Loft.SURFACE,Loft.ACCENT,.38f), Loft.LINE)); control.switchPadding = dp(12) }
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
        override fun getView(position: Int, previous: View?, parent: ViewGroup) =
            style(super.getView(position, previous, parent), false).also(AppTypography::applyTree)
        override fun getDropDownView(position: Int, previous: View?, parent: ViewGroup) =
            style(super.getDropDownView(position, previous, parent), true).also(AppTypography::applyTree)
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

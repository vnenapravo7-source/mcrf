package org.mcrf.client

import android.app.*
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Network
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.*
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.engine.spi.v1.Platform
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import android.app.Notification as AndroidNotification

class McrfVpnService : ClientPrimaryHostPlatform() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var serverUsage: ServerUsage.Session
    private var recordedUsageConnection: Triple<Long, String, Long>? = null
    @Volatile private var core: ClientPrimaryWorker? = null
    private var telemetry: Runnable? = null
    private var notificationTraffic:NotificationTrafficSampler?=null
    private val adaptivePolicy by lazy { AdaptivePolicyRuntime(this) }
    private var policySelector = false
    private var policyProbeAt = Long.MIN_VALUE
    private var policyCounterAt = Long.MIN_VALUE
    private var policyErrorAt = Long.MIN_VALUE
    private var policyCandidateCursor = 0
    private var policyRequestedTarget: String? = null
    private var carrier: NativeCarrier? = null
    @Volatile private var directTunSession:CsqttDirectTunSession?=null
    @Volatile private var directTunEffective:JSONObject?=null
    @Volatile private var directTunOwned=false // Includes a failed, retained blackhole TUN; never an ordinary session.
    @Volatile private var directTunTransition:RuntimeStart?=null
    @Volatile private var directAdmissionPending=false
    @Volatile private var directTunStarting=false
    @Volatile private var directTunBsContext=false
    @Volatile private var directAdmissionSerial=0L
    private var directRetirementSerial=0L // Service worker only: admission failure is not a retired run.
    private var directTunChangeTimer:Runnable?=null
    private fun directTunSelected()=!fixtureMode&&CsqttDirectTunSettings.selected {key,fallback->preferences.getBoolean(key,fallback)}
    private fun directTunRequested(vpn:Boolean=vpnMode,explicitBs:Boolean=directTunBsContext)=
        CsqttDirectTunPolicy.requested(directTunSelected(),vpn,explicitBs)
    private var routeServerSession:RouteServerSession?=null
    private var nativeRouteSession:NativeRouteWorkers?=null
    private var nativeRoutePlatforms:NativeRoutePlatforms?=null
    private var nativeRouteBootstrap:ServerBootstrapResolver?=null
    private var carrierSwitchboard:CarrierSocksSwitch?=null
    @Volatile private var routeBindingSelection:RouteBindingSelection?=null
    private val boundSelectionReload=Runnable {
        if(!destroyed&&currentService===this&&core!=null&&routeBindingSelection!=null&&currentStart?.applyRoutes==true)
            startService(Intent(this,McrfVpnService::class.java).setAction(RELOAD_ROUTES))
    }
    private val boundSelectionListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->
        if(key in RouteBindingSelection.preferenceKeys&&routeBindingSelection!=null){main.removeCallbacks(boundSelectionReload);main.postDelayed(boundSelectionReload,250)}
    }
    private var bsCandidateCursor=0
    private val bsHotMeasurements=linkedMapOf<String,Pair<Long,Long>>()
    private var optionalSession:OptionalCoreSession?=null
    private var compactSession:CompactProtocolSession?=null
    private var standaloneProtocol:OwnedOptionalRouterTransport?=null
    private var mixedRouteSession:MixedNativeRouteSession?=null
    private var engineUsage:EngineUpdateGate.Usage?=null
    @Volatile private var retirement:ClientPrimaryRetirement<ClientPrimaryWorker>?=null
    @Volatile private var destroyed=false
    private data class RuntimeStart(val template:JSONObject,val candidate:JSONObject?,val vpn:Boolean,val port:Int,val applyRoutes:Boolean,val requested:Long)
    @Volatile private var currentStart:RuntimeStart?=null
    private data class StreamConsumer(val profile:JSONObject)
    private data class StreamRuntime(val main:StreamConsumer?,val routed:List<StreamConsumer>,
        val routing:JSONObject?,val entries:List<ServerImport.Server>,val choice:String,val policyOwner:String?,
        val nativePreparation:NativeRoutePreparation?,val nativeEntry:ServerImport.Server?,
        val bindingSelection:RouteBindingSelection?=null,
        val selections:Map<String,String> = emptyMap(),val autonomousMain:Boolean=false)
    private var streamRuntime:StreamRuntime?=null // Serialized service worker only.
    private val streamBoardConsumers=linkedMapOf<String,StreamConsumer>()
    private val streamChanges=CarrierStreamRestartPolicy.Pending()
    private var streamChangeTimer:Runnable?=null // Main looper only.
    @Volatile private var streamChangeApplying=false
    private var captivePortalWatch:AutoCloseable?=null
    @Volatile private var captivePortalRetry:Runnable?=null
    private var captivePortalStatus:String?=null
    private var captivePortalNeedsRecovery=false
    private val fallbackChain=FallbackChainController(this)
    private var bsDetection:BsDetectionMonitor?=null
    @Volatile private var routeBindingDetectionAllowed=true
    @Volatile private var bsWifiPaused:RuntimeStart?=null
    @Volatile private var bsWifiSettings=BsWifiSettings()
    @Volatile private var bsWifiMonitor:BsWifiMonitor?=null
    @Volatile private var bsPowerLease:BsScreenOffLease?=null
    private var bsPowerGeneration=-1L
    private var bsPowerSettings:BsScreenOffSettings?=null
    private val bsWifiCheck=Runnable {
        if(!destroyed&&!worker.isShutdown)try{worker.execute {try{applyBsWifiPolicy()}finally{refreshBsPowerLease()}}}catch(_:java.util.concurrent.RejectedExecutionException){}
    }
    private val autoSectionNotice=AutoSectionNotice()
    private var autoDetectionRetryAfter=0L
    @Volatile private var networkRecovery:RuntimeStart?=null
    @Volatile private var networkRecoveryTask:Runnable?=null
    private val recoveryState=RecoveryState()
    private val recoveryPolicyLock=Any()
    @Volatile private var safeModeActive=false
    private val safeModeListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key==SafeModeStore.KEY)applySafeMode(preferences.getBoolean(SafeModeStore.KEY,false))
    }
    private val runtimeHealth=RuntimeHealthLifecycle(SystemClock::elapsedRealtimeNanos) { server,event ->
        runCatching { ProbeCoordinator.ingestHealthEvent(this,server,event) }
    }
    private val journalWorker=java.util.concurrent.ThreadPoolExecutor(1,1,0,
        java.util.concurrent.TimeUnit.MILLISECONDS,java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
        java.util.concurrent.ThreadFactory { Thread(it,"mcrf-journal").apply {isDaemon=true} },
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
    private data class RecoveryTarget(val choice:String?,val server:String,val outbound:String?,val moduleIdentity:String?)
    private var recoveryTarget:RecoveryTarget?=null
    private var runningPrimaryIdentity:String?=null
    @Volatile private var recoveryTunnel:ParcelFileDescriptor?=null
    @Volatile private var rebuildingForNetwork=false
    private val networkResetQueued=java.util.concurrent.atomic.AtomicBoolean()
    private val names = ConcurrentHashMap<String, String>()
    private val ids = ConcurrentHashMap<String, String>()
    @Volatile private var target = ""
    @Volatile private var generation = 0L
    @Volatile private var epoch = 0L
    @Volatile private var steerProbeEpoch = -1L
    internal fun runtimeSteerProbeToken():Long? = generation.takeIf {
        steerProbeEpoch>=0&&steerProbeEpoch==epoch&&!destroyed&&currentService===this
    }
    private var healthTask: Runnable? = null
    private var steerVpnNetwork:Network?=null
    private var packetVpnNetwork:Network?=null
    private var steerFrozenId=""
    private val steerRunLock=Any()
    @Volatile private var autoDisabled = false
    @Volatile private var profilePolicyOwner:String?=null
    @Volatile private var selectedOutbound = ""
    private var runningConfig: JSONObject? = null
    private var runningDirectDnsIpv4: Boolean? = null
    private var fixtureMode=false
    @Volatile private var fixtureP1OwnerHelperSha:String?=null
    @Volatile private var fixtureStrictOwner:ClientP1StrictOwnerFixture?=null
    @Volatile private var fixtureSteerOwnerPin:String?=null
    @Volatile private var fixtureBsNativePhysical:BsNativePhysicalServiceFixture?=null
    private var sessionPrimaryChoice:String?=null
    private var autonomousDispatcherChoice:String?=null
    @Volatile private var autonomousDispatcherPhysical:android.net.Network?=null
    private var fallbackAttempted=false
    private var fixtureRetirementHold:File?=null
    private var fixtureConnectPending=false
    private var carrierTimeout=60000L
    private var healthUrl="https://www.gstatic.com/generate_204"
    private var behavior=ClientBehaviorSettings()
    private var wakeRegistered=false
    private var vpnPackageReceiverRegistered=false
    private var vpnPackageReload:Runnable?=null
    @Volatile private var ownerIsolationUpgradePending=false
    private val vpnPackageReceiver=object:BroadcastReceiver(){
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action==Intent.ACTION_PACKAGE_REMOVED&&intent.getBooleanExtra(Intent.EXTRA_REPLACING,false))return
            val name=intent.data?.schemeSpecificPart?:return
            val uid=intent.getIntExtra(Intent.EXTRA_UID,-1)
            val captured=installedVpnScope?.takeIf {it.first===tunnel&&it.second.tracksPackage(name,uid)}?:return
            if(fixtureMode||destroyed||currentStart?.vpn!=true)return
            val requested=epoch
            val run=generation
            vpnPackageReload?.let(main::removeCallbacks)
            val refresh=Runnable {
                vpnPackageReload=null
                if(!destroyed&&currentService===this@McrfVpnService&&epoch==requested&&generation==run&&
                    tunnel===captured.first&&installedVpnScope===captured&&currentStart?.vpn==true){
                    startService(Intent(this@McrfVpnService,McrfVpnService::class.java).setAction(VPN_OWNER_ISOLATION_CHANGED))
                }
            }
            vpnPackageReload=refresh
            main.postDelayed(refresh,350)
        }
    }
    private val wakeCheck=WakeTunnelCheck()
    private var wakeCheckTask:Runnable?=null
    private val wakeReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
        val power=getSystemService(PowerManager::class.java)
        if(intent.action in setOf(Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_PRESENT)||
            intent.action==PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED&&!power.isDeviceIdleMode&&power.isInteractive)
            checkTunnelAfterWake()
    }}
    private fun checkTunnelAfterWake() {
        if(fixtureMode||!wakeCheck.admit(SystemClock.elapsedRealtime(),behavior.resetOnWake,
                !destroyed&&currentService===this&&currentStart!=null&&core!=null&&networkRecovery==null&&bsWifiPaused==null))return
        val requested=epoch;val captured=generation
        val task=Runnable {
            wakeCheckTask=null
            if(worker.isShutdown){wakeCheck.complete(SystemClock.elapsedRealtime(),false);return@Runnable}
            try {worker.execute {
                var performed=false
                try {
                fun current()=!destroyed&&currentService===this&&epoch==requested&&generation==captured&&
                    currentStart!=null&&core!=null&&networkRecovery==null&&bsWifiPaused==null&&getSystemService(PowerManager::class.java).isInteractive
                if(!current()||!getSystemService(PowerManager::class.java).isInteractive)return@execute
                val running=core?:return@execute
                val outcome=WakeTunnelCheck.verify(listOf(healthUrl,"https://www.cloudflare.com/cdn-cgi/trace"),::current) {url->
                    if(running.choiceId=="singbox") {
                        // Explicit outbound: carrier children exclude our UID from TUN.
                        running.health(target,url,3500)>=0
                    }else {
                        // Packet/Steer: bind strictly to our own VPN, never physical fallback.
                        val connectivity=getSystemService(android.net.ConnectivityManager::class.java)
                        val network=connectivity.allNetworks.firstOrNull {network->
                            connectivity.getNetworkCapabilities(network)?.let {caps->
                                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)&&
                                    (if(android.os.Build.VERSION.SDK_INT>=30)caps.ownerUid==android.os.Process.myUid()
                                     else network==(steerVpnNetwork?:packetVpnNetwork))
                            }==true
                        }
                        if(network==null)false else {
                            val test=network.openConnection(java.net.URL(url),java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                            try {test.useCaches=false;test.connectTimeout=3500;test.readTimeout=3500;test.instanceFollowRedirects=false;test.responseCode in 200..399}finally{test.disconnect()}
                        }
                    }
                }
                if(outcome==WakeTunnelCheck.Result.CANCELLED||!current())return@execute
                performed=true
                val healthy=outcome==WakeTunnelCheck.Result.HEALTHY
                ClientEvents.record(this,"Клиент",if(healthy)"После пробуждения связь через туннель подтверждена"else"После пробуждения туннель не отвечает; восстанавливаем соединение")
                if(!healthy)resetConnections("нет связи после пробуждения",forceRestart=true)
                }finally{wakeCheck.complete(SystemClock.elapsedRealtime(),performed)}
            }}catch(_:java.util.concurrent.RejectedExecutionException){wakeCheck.complete(SystemClock.elapsedRealtime(),false)}
        }
        wakeCheckTask=task;main.postDelayed(task,2000)
    }
    override fun onCreate(){
        super.onCreate()
        if(RuAppTunnelNotice.enabled(this))RuAppTunnelNotice.prepareCountryIndex(applicationContext)
        serverUsage = ServerUsage.Session(applicationContext)
        currentService=this
        captivePortalWatch=CaptivePortalMonitor.watch(this)
        safeModeActive=SafeModeStore(this).enabled()
        preferences.registerOnSharedPreferenceChangeListener(safeModeListener)
        preferences.registerOnSharedPreferenceChangeListener(boundSelectionListener)
        behavior=try{ClientBehaviorStore.load(this)}catch(_:Exception){ClientBehaviorSettings()}
        val wakeFilter=IntentFilter(Intent.ACTION_SCREEN_ON).apply {addAction(Intent.ACTION_USER_PRESENT);addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(wakeReceiver,wakeFilter,Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(wakeReceiver,wakeFilter)
        wakeRegistered=true
        val packageFilter=IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if(Build.VERSION.SDK_INT>=33)registerReceiver(vpnPackageReceiver,packageFilter,Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(vpnPackageReceiver,packageFilter)
        vpnPackageReceiverRegistered=true
        reloadBsWifiMonitor()
        bsDetection=BsDetectionMonitor(this,{
            val start=currentStart
            if(destroyed||core==null||networkRecovery!=null||start==null||CaptivePortalMonitor.blocksAutomatic(this)||
                !(status.startsWith("VPN подключён")||status.startsWith("Прокси ")))null
            else if(fixtureMode||!routeBindingDetectionAllowed)null // No route-family switching outside the bound Auto group.
            else BsDetectionMonitor.Stamp(epoch,generation,!start.template.has("mcrf_bs")&&!start.template.has("mcrf_carrier"),start.vpn)
        },{stamp->if(stamp.vpnSection)switchAutomaticallyToBs(stamp)else switchAutomaticallyToVpn(stamp)},{stamp,site->
            // Our UID is deliberately excluded from the TUN when carrier children run.
            // Test the actual selected primary outbound, not a VPN Network inaccessible to that UID.
            val running=core
            if(destroyed||epoch!=stamp.epoch||generation!=stamp.generation||networkRecovery!=null||running==null)
                DirectAccessCheck.Outcome.CANCELLED
            else try{
                if(running.health(target,BsDetectionSites.url(site),2500)>=0)DirectAccessCheck.Outcome.RESPONSE
                else DirectAccessCheck.Outcome.NO_RESPONSE
            }catch(_:Exception){DirectAccessCheck.Outcome.NO_RESPONSE}
        },::retryThirdSiteThroughVpn)
    }
    private fun reloadBsWifiMonitor() {
        bsWifiMonitor?.close();bsWifiMonitor=null
        try {
            bsWifiSettings=BsWifiStore.load(this)
            if(bsWifiSettings.pauseOnWifi||bsWifiSettings.trustedEnabled) {
                val monitor=BsWifiMonitor(this,bsWifiSettings){scheduleBsWifiCheck()}
                bsWifiMonitor=monitor
                try{monitor.start()}catch(error:Exception){monitor.close();bsWifiMonitor=null;throw error}
            }
        }catch(_:Exception){ClientEvents.record(this,"БС","Наблюдение Wi-Fi недоступно. Неизвестные сети не считаются доверенными.")}
        scheduleBsWifiCheck()
    }
    private fun energySessionAlive()=core!=null||directTunOwned&&directTunSession?.isAlive()==true
    private fun refreshBsPowerLease() {
        val session=currentStart
        val settings=try{BsScreenOffSettings.load(this)}catch(_:Exception){BsScreenOffSettings()}
        val eligible=!destroyed&&!fixtureMode&&currentService===this&&energySessionAlive()&&session!=null&&session.requested==epoch&&
            ConnectionPresentation.connected(status)&&settings.needsLease
        if(!eligible){releaseBsPowerLease();return}
        if(bsPowerLease!=null&&bsPowerGeneration==generation&&bsPowerSettings==settings)return
        releaseBsPowerLease()
        val captured=generation;val requested=epoch
        try {
            val lease=BsScreenOffLease(this,settings){!destroyed&&currentService===this&&generation==captured&&epoch==requested&&energySessionAlive()&&ConnectionPresentation.connected(status)}
            try{lease.start()}catch(error:Exception){lease.close();throw error}
            synchronized(this){
                if(destroyed||epoch!=requested||generation!=captured||!energySessionAlive())lease.close()
                else {bsPowerLease=lease;bsPowerGeneration=captured;bsPowerSettings=settings}
            }
        }catch(_:Exception){ClientEvents.record(this,"БС","Удержание CPU недоступно. Соединение продолжает обычную фоновую работу.")}
    }
    private fun releaseBsPowerLease()=synchronized(this) {
        val previous=bsPowerLease;bsPowerLease=null;bsPowerGeneration=-1;bsPowerSettings=null
        previous?.close()
        Unit
    }
    private fun scheduleBsWifiCheck() {
        main.removeCallbacks(bsWifiCheck)
        if(!destroyed)main.postDelayed(bsWifiCheck,300)
    }
    /** Only this service's saved VK Turn session may be paused/resumed. No section reselection. */
    private fun applyBsWifiPolicy() {
        if(directTunOwned)return // Explicit direct test never releases VPN via an automatic Wi-Fi policy.
        if(destroyed||currentService!==this||fixtureMode)return
        val paused=bsWifiPaused
        if(paused!=null&&paused.requested!=epoch){bsWifiPaused=null;return}
        val snapshot=bsWifiMonitor?.snapshot?:BsWifiMonitor.Snapshot(false,emptySet())
        val pause=bsWifiSettings.shouldPause(snapshot.wifi,snapshot.ssids)
        if(paused==null) {
            val session=currentStart?:return
            if(!pause||!session.vpn||session.requested!=epoch||!ConnectionPresentation.connected(status)||core==null)return
            val transport=session.candidate?.optJSONObject("profile")?:session.template.optJSONObject("mcrf_carrier")
            if(transport?.optString("type")?.let {it in SwgProfile.TYPES}!=true)return
            bsWifiPaused=session
            clearNetworkRecovery()
            closeCore() // Explicit opt-in releases the TUN as well as native children.
            if(destroyed||session.requested!=epoch||bsWifiPaused!==session)return
            status="БС на паузе · Wi-Fi";foreground(status)
            ClientEvents.record(this,"БС","Туннель отключён по настройке Wi-Fi; до выхода из этой сети трафик идёт напрямую.")
            scheduleBsWifiCheck() // Network/setting may have changed while exact owners retired.
            return
        }
        if(pause)return
        if(!preferences.getBoolean("wantedRunning",false)){bsWifiPaused=null;return}
        if(paused.vpn&&VpnService.prepare(this)!=null){
            bsWifiPaused=null;preferences.edit().putBoolean("wantedRunning",false).apply()
            status="Отключено";stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            return // Another VPN may have acquired permission while our TUN was released.
        }
        bsWifiPaused=null
        rebuildingForNetwork=true
        try {
            status="Восстановление соединения…";foreground(status)
            val frozen=if(autoDisabled)selectedOutbound.takeIf {it.isNotEmpty()}else null
            startSource(paused.template,paused.candidate,paused.vpn,paused.port,paused.applyRoutes,paused.requested,false,frozen)
            if(paused.requested!=epoch||destroyed)return
            val bs=paused.template.optJSONObject("mcrf_bs")
            if(autoAllowed()&&bs!=null&&paused.candidate!=null)
                scheduleHealth(paused.template,BsPlan.candidates(bs),paused.candidate,bs.getInt("interval"),paused.vpn,paused.port,paused.applyRoutes,paused.requested)
            ClientEvents.record(this,"БС","Wi-Fi: восстановлено прежнее подключение БС без изменения выбранного сервера и группы правил.")
        }catch(error:Exception){
            if(paused.requested==epoch&&!destroyed){
                val detail=NetworkRecoveryFailure.classify(error).detail
                closeCore(keepTun=paused.vpn)
                status="Ошибка подключения: БС не восстановлено после Wi-Fi · $detail";foreground(status)
                ClientEvents.record(this,"БС","Автовосстановление после Wi-Fi не удалось: $detail. Повторите подключение вручную; повторные запросы VK автоматически не запускаются.")
            }
        }finally{rebuildingForNetwork=false}
    }
    private fun switchAutomaticallyToBs(stamp:BsDetectionMonitor.Stamp){
        if(worker.isShutdown)return
        try{worker.execute {
            fun live()=!destroyed&&currentService===this&&epoch==stamp.epoch&&generation==stamp.generation&&preferences.getBoolean(BsDetectionMonitor.KEY,false)
            val previous=currentStart?:return@execute
            if(holdForCaptivePortal(previous,false))return@execute
            if(!live()||SystemClock.elapsedRealtime()<autoDetectionRetryAfter||previous.template.has("mcrf_bs")||previous.template.has("mcrf_carrier"))return@execute
            var switching=false
            try {
                require(ClientPrimarySelection.selected(this)!="steer") {"Автоопределение БС: выбранное ядро пока не поддерживает транспорты БС."}
                val template=prepareAutoBsTemplate()
                val candidates=BsPlan.candidates(template.getJSONObject("mcrf_bs"))
                if(!live())return@execute
                require(rememberVpnSession(previous)) {"Не удалось сохранить цель возврата в VPN"}
                clearNetworkRecovery();switching=true
                pendingWhitelists=true
                status="Переключаем движок БС…";foreground("Сайты не ответили напрямую; переключаемся на белые списки")
                val chosen=chooseEngine(template,candidates,previous.vpn,previous.port,previous.applyRoutes,stamp.epoch)
                check(!destroyed&&currentService===this&&epoch==stamp.epoch&&preferences.getBoolean(BsDetectionMonitor.KEY,false)) {"Автопереключение отменено"}
                if(autoAllowed())scheduleHealth(template,candidates,chosen,preferences.getInt("interval",60),previous.vpn,previous.port,previous.applyRoutes,stamp.epoch)
                autoBsSwitchSerial++
                ClientEvents.record(this,"Клиент","Автоопределение БС: подключение белых списков подтверждено. Возврат к сохранённому VPN — после восстановления доступа.")
                if(autoSectionNotice.accept(true,autoBsSwitchSerial,stamp.epoch,epoch,!destroyed&&currentService===this))AutoSectionNotifications.post(this,true)
            }catch(error:Exception){
                if(!destroyed&&currentService===this&&epoch==stamp.epoch){
                    val retryMillis=BsDetectionInterval.millis(preferences.getInt(BsDetectionInterval.KEY,BsDetectionInterval.DEFAULT))
                    autoDetectionRetryAfter=SystemClock.elapsedRealtime()+retryMillis
                    ClientEvents.record(this,"Предупреждение","Автоопределение БС: переключение не выполнено: ${ClientEvents.redact(error.message)}. Повтор через ${retryMillis/1000} с.")
                    if(switching)try {
                        startSource(previous.template,previous.candidate,previous.vpn,previous.port,previous.applyRoutes,stamp.epoch,true)
                    }catch(_:Exception){
                        networkRecovery=previous;status="Восстановление соединения…";foreground(status);retryNetworkRecovery(previous)
                    }
                }
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException){}
    }
    private fun rememberVpnSession(session:RuntimeStart):Boolean {
        if(session.requested==epoch&&core?.state=="RUNNING")recordHealthyConnection()
        if(networkRecovery==null&&session.requested==epoch&&core?.state=="RUNNING"){
            releaseRecoveryTun()
            recoveryState.connected(session.requested,SystemClock.elapsedRealtime());publishRecovery()
        }
        if(fixtureMode||!preferences.getBoolean(BsDetectionMonitor.KEY,false)||session.template.has("mcrf_bs")||session.template.has("mcrf_carrier"))return true
        if(core?.choiceId==WgAddonPolicy.KIND&&ClientPrimarySelection.selected(this)==null)return true
        return try{
            if(preferences.getString(BsAutoSelectionPolicy.LAST_VPN,"").isNullOrEmpty()&&selectedId.matches(Regex("[0-9a-f]{64}")))preferences.edit().putString(BsAutoSelectionPolicy.LAST_VPN,selectedId).apply()
            AutoVpnReturn.save(this,session.template,session.vpn,session.port,session.applyRoutes,requireNotNull(ClientPrimarySelection.selected(this)));true
        }
        catch(_:Exception){ClientEvents.record(this,"Предупреждение","Не удалось сохранить цель автоматического возврата в VPN. Текущее подключение не изменено.");false}
    }
    /** The third-site failure first verifies another VPN path; it is not BS evidence by itself. */
    private fun retryThirdSiteThroughVpn(stamp:BsDetectionMonitor.Stamp,site:String){
        if(worker.isShutdown)return
        try{worker.execute {
            fun live()=!destroyed&&currentService===this&&epoch==stamp.epoch&&generation==stamp.generation&&preferences.getBoolean(BsDetectionMonitor.KEY,false)
            val original=currentStart?:return@execute
            if(holdForCaptivePortal(original,false))return@execute
            if(!live()||original.template.has("mcrf_bs")||original.template.has("mcrf_carrier"))return@execute
            val alternate=autoVpnTargets(null,selectedId).firstOrNull()
            if(alternate==null){
                val confirmed=try{core?.health(target,BsDetectionSites.url(site),2500)?.let {it>=0}==true}catch(_:Exception){false}
                if(!confirmed&&live())switchAutomaticallyToBs(stamp)
                return@execute
            }
            try{
                status="Проверяем другой VPN-сервер…";foreground(status)
                startSource(alternate,null,original.vpn,original.port,original.applyRoutes,stamp.epoch,true)
                require(core?.health(target,BsDetectionSites.url(site),2500)?.let {it>=0}==true){"Другой VPN-сервер также не подтвердил контрольный сайт"}
                ClientEvents.record(this,"Клиент","Автоопределение БС: контрольный сайт подтверждён через другой VPN-сервер; переход на БС не требуется.")
            }catch(error:Exception){
                ClientEvents.record(this,"Предупреждение","Автоопределение БС: повторная проверка через другой VPN не прошла: ${ClientEvents.redact(error.message)}")
                try{
                    startSource(original.template,original.candidate,original.vpn,original.port,original.applyRoutes,stamp.epoch,true)
                    if(!destroyed&&epoch==stamp.epoch)switchAutomaticallyToBs(BsDetectionMonitor.Stamp(stamp.epoch,generation,true,original.vpn))
                }catch(_:Exception){networkRecovery=original;status="Восстановление соединения…";foreground(status);retryNetworkRecovery(original)}
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException){}
    }
    private fun switchAutomaticallyToVpn(stamp:BsDetectionMonitor.Stamp){
        if(worker.isShutdown)return
        try{worker.execute {
            fun live()=!destroyed&&currentService===this&&epoch==stamp.epoch&&generation==stamp.generation&&preferences.getBoolean(BsDetectionMonitor.KEY,false)
            fun switchingLive()=!destroyed&&currentService===this&&epoch==stamp.epoch&&preferences.getBoolean(BsDetectionMonitor.KEY,false)
            val previous=currentStart?:return@execute
            if(holdForCaptivePortal(previous,false))return@execute
            if(!live()||SystemClock.elapsedRealtime()<autoDetectionRetryAfter||
                (!previous.template.has("mcrf_carrier")&&!previous.template.has("mcrf_bs")))return@execute
            var switching=false
            try{
                val saved=runCatching {AutoVpnReturn.load(this)}.getOrNull()
                val targets=autoVpnTargets(saved?.template)
                require(targets.isNotEmpty()) {"Нет совместимого VPN-сервера для возврата"}
                if(!live())return@execute
                clearNetworkRecovery();switching=true
                pendingWhitelists=false
                status="Возвращаемся в VPN…";foreground("Прямой доступ восстановлен; проверяем сохранённое VPN-подключение")
                // Same reverse policy: retain the currently chosen mode/listeners/routing, not stale cached controls.
                var connected=false;var failure:Exception?=null
                val deadline=SystemClock.elapsedRealtime()+60_000
                var attempts=0
                for(template in targets){
                    if(!switchingLive())throw IOException("Переключение отменено")
                    // Give one rotated fallback its chance even after a slow
                    // preferred target; otherwise every interval retries only it.
                    if(attempts>=2&&SystemClock.elapsedRealtime()>=deadline)break
                    attempts++
                    try {
                        startSource(template,null,previous.vpn,previous.port,previous.applyRoutes,stamp.epoch,true)
                        val control=BsDetectionSites.load {key,fallback->preferences.getString(key,fallback)?:fallback}[2]
                        require(core?.health(target,BsDetectionSites.url(control),2500)?.let {it>=0}==true) {"VPN пока не подтвердил контрольный сайт"}
                        connected=true;break
                    }catch(error:Exception){
                        failure=error
                        ClientEvents.record(this,"Предупреждение","Автовозврат VPN · попытка $attempts: ${ClientEvents.redact(error.message)}")
                    }
                }
                if(!connected)throw failure?:IOException("Нет доступного VPN-сервера")
                require(switchingLive())
                autoVpnSwitchSerial++
                ClientEvents.record(this,"Клиент","Автоопределение БС: подключение к VPN восстановлено.")
                if(autoSectionNotice.accept(false,autoVpnSwitchSerial,stamp.epoch,epoch,!destroyed&&currentService===this))AutoSectionNotifications.post(this,false)
            }catch(_:Exception){
                val retryMillis=BsDetectionInterval.millis(preferences.getInt(BsDetectionInterval.KEY,BsDetectionInterval.DEFAULT))
                autoDetectionRetryAfter=SystemClock.elapsedRealtime()+retryMillis
                if(!destroyed&&currentService===this&&epoch==stamp.epoch){
                    ClientEvents.record(this,"Предупреждение","Автовозврат в VPN не выполнен: нет доступного совместимого VPN-сервера с ответом YouTube. БС сохраняются; повтор через ${retryMillis/1000} с.")
                    if(switching)try{
                        startSource(previous.template,previous.candidate,previous.vpn,previous.port,previous.applyRoutes,stamp.epoch,true)
                        previous.template.optJSONObject("mcrf_bs")?.takeIf {autoAllowed()}?.let {plan->
                            previous.candidate?.let {selected->scheduleHealth(previous.template,BsPlan.candidates(plan),selected,plan.getInt("interval"),previous.vpn,previous.port,previous.applyRoutes,stamp.epoch)}
                        }
                    }catch(_:Exception){
                        networkRecovery=previous;status="Восстановление соединения…";foreground(status);retryNetworkRecovery(previous)
                    }
                }
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException){}
    }
    override fun onUnderlyingNetworkChanged(network:Network?){
        if(directTunOwned)return // No network-policy re-selection/reconnect in this test session.
        if(bsWifiPaused!=null)return
        refreshRecoveryBehavior()
        val pinnedHandle=autonomousDispatcherPhysical?.networkHandle?:standaloneProtocol?.physical?.networkHandle?:mixedRouteSession?.physicalNetworkHandle?:optionalSession?.physicalNetworkHandle
        if(!rebuildingForNetwork&&pinnedHandle!=null&&network?.networkHandle!=pinnedHandle){
            resetConnections("смена сети автономного транспорта")
            return
        }
        if(!behavior.resetOnNetworkChange||rebuildingForNetwork)return
        val dnsChanged=network!=null&&DirectDnsFamily.requiresRestart(runningDirectDnsIpv4,
            DirectDnsNetwork.ipv4Only(this,network))
        if(networkRecovery==null&&currentStart!=null)resetConnections(if(network==null)"потеря сети"else"смена сети",dnsChanged)
    }
    private fun recordHealthyConnection() {
        if(SafeModeStore(this).enabled()||!ProbeCoordinator.healthStore(this).settings().enabled) {
            runtimeHealth.stopped(HealthDisconnect.UNKNOWN);return
        }
        val caps=getSystemService(android.net.ConnectivityManager::class.java).getNetworkCapabilities(underlying)
        val network=when {
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true->HealthNetwork.WIFI
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)==true->HealthNetwork.CELLULAR
            caps==null->HealthNetwork.UNKNOWN
            else->HealthNetwork.OTHER
        }
        val engine=when(core?.choiceId?:sessionPrimaryChoice) {
            "steer"->HealthEngine.STEER
            "sing-box","singbox"->HealthEngine.SINGBOX
            else->HealthEngine.OTHER
        }
        runtimeHealth.connected(generation,selectedId,network,engine)
    }
    override fun onConnectionObserved(generation:Long,query:Platform.ConnectionQuery,owner:Platform.ConnectionOwner?) {
        if(destroyed||generation!=this.generation||fixtureBsNativePhysical!=null||fixtureStrictOwner!=null||fixtureSteerOwnerPin!=null)return
        if(!safeModeActive&&currentService===this){
            TrafficCaptureService.serverConnection(query,owner,generation)
            if(!fixtureMode&&owner!=null&&owner.packageNames.isNotEmpty()&&query.destinationPort !in setOf(53,853)&&
                ownsSystemTunnel()&&ConnectionPresentation.connected(status)&&RuAppTunnelNotice.enabled(this)){
                val packages=owner.packageNames.toSet()
                val host=RuAppTunnelNotice.observedHost(generation,owner.uid,query.destination)
                val russianApp=packages.all(RoutingDefaultsRussianApps.byPackage::containsKey)
                if((russianApp||RuAppTunnelNotice.russianDomain(host)||RuAppTunnelNotice.russianAddress(query.destination))&&RuAppTunnelNotice.unambiguousProxy(
                        runningConfig,RuAppTunnelNotice.Flow(packages,host,query.destination,query.destinationPort,query.protocol))){
                    RuAppTunnelNotice.report(applicationContext)
                }
            }
        }
    }
    override fun onDnsObserved(generation:Long,uid:Int,domain:String,ip:String,ttlMs:Long) {
        if(destroyed||safeModeActive||generation!=this.generation||fixtureBsNativePhysical!=null||fixtureStrictOwner!=null||fixtureSteerOwnerPin!=null)return
        if(currentService===this){
            TrafficCaptureService.serverDns(uid,domain,ip,ttlMs,generation)
            RuAppTunnelNotice.observeDns(generation,uid,domain,ip,ttlMs)
        }
    }
    override fun onRouteObserved(generation:Long,query:Platform.ConnectionQuery,owner:Platform.ConnectionOwner,domain:String?,tunneled:Boolean){
        if(destroyed||safeModeActive||generation!=this.generation||currentService!==this||fixtureMode||!ownsSystemTunnel()||!tunneled||!RuAppTunnelNotice.enabled(this))return
        if(query.destinationPort in setOf(53,853)||owner.packageNames.isEmpty())return
        val host=domain?:RuAppTunnelNotice.observedHost(generation,owner.uid,query.destination)
        val russian=owner.packageNames.all(RoutingDefaultsRussianApps.byPackage::containsKey)||RuAppTunnelNotice.russianDomain(host)||RuAppTunnelNotice.russianAddress(query.destination)
        if(BuildConfig.DEBUG&&russian)android.util.Log.i("MCRF.RuApp","native proxy route verified; notifications=${getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()}")
        if(russian)RuAppTunnelNotice.report(applicationContext)
    }
    override fun onDnsExchangeObserved(generation:Long,query:ByteArray,reply:ByteArray) {
        if(!destroyed&&!safeModeActive&&generation==this.generation&&currentService===this&&
            fixtureBsNativePhysical==null&&fixtureStrictOwner==null&&fixtureSteerOwnerPin==null)
            TrafficCaptureService.serverDnsExchange(query,reply,generation)
    }
    private fun clearNetworkRecovery(){
        networkRecovery=null
        networkRecoveryTask?.let(main::removeCallbacks);networkRecoveryTask=null
        recoveryTarget=null
    }
    private fun recoveryLive(session:RuntimeStart)=!safeModeActive&&!destroyed&&currentService===this&&session.requested==epoch&&
        recoveryState.snapshot().userRequested&&(fixtureMode||preferences.getBoolean("wantedRunning",false))
    private fun applySafeMode(enabled:Boolean) {
        val cancelledEpoch=synchronized(recoveryPolicyLock) {
            if(destroyed||currentService!==this||safeModeActive==enabled)return
            safeModeActive=enabled
            if(!enabled)return // Removing the gate never starts/resumes a service or timer.
            var cancelled:Long?=null
            SafeModeRuntimePolicy.apply(true,recoveryState.snapshot(),networkRecovery!=null,recoveryTunnel!=null,{
                val requested=++epoch // Invalidate an in-flight recovery START as well as queued timers.
                clearNetworkRecovery()
                recoveryState.cancel(requested,RecoveryReason.CANCELLED)
                if(!fixtureMode)preferences.edit().putBoolean("wantedRunning",false).putBoolean(RECOVERY_USER_STOPPED,true).apply()
                cancelled=requested
            },{releaseRecoveryTun()}) // Close only this service's blocking descriptor, under the same ownership lock.
            cancelled
        }
        journalWorker.execute {runCatching {TrafficJournalStore.local(this).clearDnsCorrelations()}}
        if(cancelledEpoch==null)return
        publishRecovery()
        if(!worker.isShutdown)try {worker.execute {
            if(destroyed||currentService!==this||epoch!=cancelledEpoch)return@execute
            closeCore() // Also releases an original TUN retained after failed replacement.
            status="Восстановление отменено · безопасный режим"
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        }}catch(_:java.util.concurrent.RejectedExecutionException){}
    }
    private fun publishRecovery(){
        if(destroyed||currentService!==this)return
        val next=recoveryState.snapshot()
        if(recovery==next)return
        recovery=next
        sendBroadcast(Intent(RECOVERY_CHANGED).setPackage(packageName)
            .putExtra("phase",next.phase.name).putExtra("reason",next.reason?.name)
            .putExtra("attempt",next.attempt).putExtra("retryInMs",next.retryInMs?:-1L)
            .putExtra("trafficBlocked",next.trafficBlocked))
        ClientEvents.record(this,"Восстановление","${next.phase} · ${next.reason?:"READY"} · попытка ${next.attempt}"+
            if(next.trafficBlocked)" · Kill switch Beta: трафик заблокирован"else"")
    }
    private fun pinRecovery(session:RuntimeStart){
        if(recoveryTarget!=null)return
        val outbound=selectedOutbound.takeIf {it.isNotBlank()}?:runCatching {core?.selectedOutbound(target)}.getOrNull()?.takeIf {it.isNotBlank()}
        recoveryTarget=RecoveryTarget(sessionPrimaryChoice?:core?.choiceId,selectedId,outbound,runningPrimaryIdentity)
    }
    /** Full IPv4/IPv6 capture, no app exclusions, no reader or socket forwarding.
     * Establish before retiring the active TUN; close only this service's descriptors. */
    private fun holdRecoveryTun(session:RuntimeStart,reason:RecoveryReason):Boolean { return synchronized(recoveryPolicyLock) {
        if(!KillSwitchPolicy.hold(behavior.killSwitchBeta&&!SafeModeStore(this).enabled(),recoveryLive(session),session.vpn,reason))return false
        val next=try {
            Builder().setSession("MCRF · Kill switch Beta").setMtu(1500).setBlocking(false).setMetered(behavior.metered)
                .addAddress("10.89.255.2",32).addAddress("fd89:ffff::2",128)
                .addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("10.89.255.1")
                .establish()?:throw IOException("RECOVERY_TUN_DENIED")
        }catch(_:Exception){
            // Preserve the original capture if Android refuses a replacement.
            // Do not claim full Beta protection when that replacement failed.
            return false
        }
        if(!recoveryLive(session)){next.close();return false}
        val previous=recoveryTunnel;recoveryTunnel=next;previous?.close()
        recoveryState.blocked(session.requested,true)
        return true
    } }
    private fun releaseRecoveryTun()=synchronized(recoveryPolicyLock){
        val previous=recoveryTunnel;recoveryTunnel=null
        try{previous?.close()}catch(_:IOException){}
    }
    private fun finishRecovery(session:RuntimeStart){
        if(!recoveryLive(session))return
        networkRecoveryTask?.let(main::removeCallbacks);networkRecoveryTask=null
        // A held TUN requires a visible STOP action, but has no retry/health timer.
        // Without a TUN, terminate the foreground service rather than spinning it.
        val held=recoveryTunnel!=null
        recoveryState.finish(session.requested,held);networkRecovery=null;recoveryTarget=null
        if(!fixtureMode)preferences.edit().putBoolean("wantedRunning",false).apply()
        status=if(held)"Восстановление остановлено · трафик заблокирован · отключите VPN или подключитесь заново"
            else"Восстановление остановлено · ${recoveryState.snapshot().reason}"
        publishRecovery()
        if(held)foreground(status)else {releaseRecoveryTun();closeCore();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    }
    /** Pause without reporting a server failure or spending the recovery budget. No VPN teardown. */
    private fun holdForCaptivePortal(session:RuntimeStart,reconnect:Boolean=true):Boolean {
        if(fixtureMode||!recoveryLive(session)||!CaptivePortalMonitor.blocksAutomatic(this))return false
        if(captivePortalStatus==null)captivePortalStatus=status
        captivePortalNeedsRecovery=captivePortalNeedsRecovery||reconnect
        fallbackChain.clearEvidence()
        status=CaptivePortalPolicy.label(CaptivePortalMonitor.state(this));foreground(status)
        if(captivePortalRetry==null) {
            val task=Runnable {
                captivePortalRetry=null
                if(recoveryLive(session)&&!worker.isShutdown)try{worker.execute {
                    if(!recoveryLive(session))return@execute
                    if(holdForCaptivePortal(session,false))return@execute
                    val needsRecovery=captivePortalNeedsRecovery
                    val previous=captivePortalStatus
                    captivePortalStatus=null;captivePortalNeedsRecovery=false
                    if(needsRecovery||core==null) {
                        val bs=session.template.optJSONObject("mcrf_bs")
                        if(bs!=null&&session.candidate==null) {
                            val candidates=BsPlan.candidates(bs)
                            try {
                                val chosen=chooseEngine(session.template,candidates,session.vpn,session.port,session.applyRoutes,session.requested)
                                scheduleHealth(session.template,candidates,chosen,bs.getInt("interval"),session.vpn,session.port,session.applyRoutes,session.requested)
                            }catch(error:Exception) {
                                beginRecovery(session.copy(candidate=candidates.firstOrNull()),RecoveryPolicy.classify(error,DirectAccessCheck.physical(this)!=null))
                            }
                        }else beginRecovery(session,RecoveryReason.NETWORK_LOSS)
                    }
                    else {
                        status=previous?:status;foreground(status)
                        val bs=session.template.optJSONObject("mcrf_bs")?.takeIf {autoAllowed()}
                        if(bs!=null&&session.candidate!=null)scheduleHealth(session.template,BsPlan.candidates(bs),session.candidate,bs.getInt("interval"),session.vpn,session.port,session.applyRoutes,session.requested)
                        else if(autoAllowed()&&sessionPrimaryChoice=="steer")scheduleSteerHealth(session)
                    }
                }}catch(_:java.util.concurrent.RejectedExecutionException){}
            }
            captivePortalRetry=task;main.postDelayed(task,5000)
        }
        return true
    }
    /** Only explicit manual chains; route-bound groups and existing automatic policies keep ownership. */
    private fun tryFallbackChain(session:RuntimeStart):Boolean {
        if(fixtureMode||!recoveryLive(session))return false
        val plan=fallbackChain.next(session.template,session.applyRoutes,selectedId)?:return false
        val template=plan.template
        val nextSession=RuntimeStart(template,null,session.vpn,session.port,session.applyRoutes,session.requested)
        if(!recoveryLive(session)||!fallbackChain.valid(plan))return false
        clearNetworkRecovery()
        steerFrozenId=plan.serverId
        selectedId=plan.serverId;selectedOutbound=""
        status="Резервная цепочка · подключение…";foreground(status)
        try {
            startSource(template,null,session.vpn,session.port,session.applyRoutes,session.requested,true)
            if(recoveryLive(session)) {
                releaseRecoveryTun();recoveryState.connected(session.requested,SystemClock.elapsedRealtime());publishRecovery()
                ClientEvents.record(this,"Клиент","Резервная цепочка: выполнен переход к следующему подключению")
            }
        }catch(error:Exception) {
            if(recoveryLive(session)) {
                // A preparation failure can precede publication of the replacement by startSource.
                // Recovery must pin this attempted entry, never the retired server's outbound tag.
                recoveryTarget=RecoveryTarget(sessionPrimaryChoice,plan.serverId,null,runningPrimaryIdentity)
                beginRecovery(nextSession,RecoveryPolicy.classify(error,DirectAccessCheck.physical(this)!=null),
                    FallbackChainPolicy.confirmedConnectionFailure(error))
            }
        }
        return true
    }
    private fun checkFallbackChain(session:RuntimeStart,current:ClientPrimaryWorker):Boolean {
        if(fixtureMode||!recoveryLive(session))return false
        val captured=generation
        if(!fallbackChain.confirmedFailure(session.applyRoutes,selectedId,{url->
            if(current.choiceId=="steer"&&nativeRouteSession==null)steerHealth(url)else current.health(target,url,2500)},
            {!recoveryLive(session)||captured!=generation}))return false
        return tryFallbackChain(session)
    }
    private fun refreshRecoveryBehavior(){
        try { behavior=ClientBehaviorStore.load(this) }
        catch(error:Exception) {
            ClientEvents.record(this,"Восстановление","Не удалось перечитать настройки; используются последние загруженные · ${error.javaClass.simpleName}")
        }
    }
    private fun beginRecovery(session:RuntimeStart,reason:RecoveryReason,confirmedServerFailure:Boolean=false){
        if(fixtureBsNativePhysical!=null||fixtureStrictOwner!=null||fixtureSteerOwnerPin!=null)return
        if(directTunOwned){pauseDirectTun(session.requested,generation);return}
        if(reason in setOf(RecoveryReason.NETWORK_LOSS,RecoveryReason.ENGINE_CRASH,RecoveryReason.SERVER_FAILURE)&&holdForCaptivePortal(session))return
        if(reason==RecoveryReason.SERVER_FAILURE&&confirmedServerFailure&&runCatching{tryFallbackChain(session)}.getOrDefault(false))return
        synchronized(recoveryPolicyLock) {
        if(!recoveryLive(session))return
        // The settings screen writes the store without restarting this service.
        // Never retire a live transport using an earlier copy of recovery policy.
        refreshRecoveryBehavior()
        if(session.vpn&&VpnService.prepare(this)!=null){onRevoke();return}
        if(networkRecoveryTask!=null)return // Physical flapping cannot advance or reset a queued retry.
        pinRecovery(session);networkRecovery=session
        if(!recoveryState.fail(session.requested,reason,SystemClock.elapsedRealtime()))return
        }
        runtimeHealth.failed(reason)
        val killEnabled=behavior.killSwitchBeta&&!SafeModeStore(this).enabled()
        val protected=holdRecoveryTun(session,reason)
        closeCore(keepTun=protected||killEnabled&&session.vpn&&reason!=RecoveryReason.CANCELLED)
        if(!recoveryLive(session))return
        recoveryState.blocked(session.requested,protected)
        if(!behavior.recoveryEnabled(reason)||!RecoveryPolicy().retryable(reason)){
            ClientEvents.record(this,"Восстановление","Повтор не запланирован · причина=$reason · приЗапуске=${behavior.reconnectOnLaunch} · автовосстановление=${behavior.autoRecoveryEnabled} · сменаСети=${behavior.resetOnNetworkChange} · повторяемая=${RecoveryPolicy().retryable(reason)}")
            finishRecovery(session);return
        }
        status="Восстановление соединения…";foreground(status)
        retryNetworkRecovery(session)
    }
    private fun retryNetworkRecovery(session:RuntimeStart){
        if(holdForCaptivePortal(session))return
        val scheduled=synchronized(recoveryPolicyLock) {
        if(!recoveryLive(session)||networkRecovery!==session||networkRecoveryTask!=null)return
        pinRecovery(session)
        if(recoveryState.snapshot().phase!=RecoveryState.Phase.RECONNECTING)
            recoveryState.fail(session.requested,RecoveryReason.SERVER_FAILURE,SystemClock.elapsedRealtime())
        val reason=recoveryState.snapshot().reason?:RecoveryReason.SERVER_FAILURE
        val delay=if(behavior.recoveryEnabled(reason))
            recoveryState.schedule(session.requested,SystemClock.elapsedRealtime(),kotlin.random.Random.nextDouble()) else null
        if(delay==null){
            val snapshot=recoveryState.snapshot()
            ClientEvents.record(this,"Восстановление","Повтор не запланирован · фаза=${snapshot.phase} · причина=$reason · попытка=${snapshot.attempt} · политикаРазрешает=${behavior.recoveryEnabled(reason)} · лимитПопыток=${RecoveryPolicy().maxAttempts} · лимитВремениМс=${RecoveryPolicy().maxElapsedMs}")
            return@synchronized false
        }
        val task=Runnable {
            if(recoveryLive(session)&&networkRecovery===session){
                networkRecoveryTask=null
                if(holdForCaptivePortal(session))return@Runnable
                if(recoveryState.attempt(session.requested,SystemClock.elapsedRealtime()))runRecovery(session)
                else finishRecovery(session)
            }
        }
        networkRecoveryTask=task
        publishRecovery();main.postDelayed(task,delay)
        true
        }
        if(!scheduled)finishRecovery(session) // Retirement must never wait while holding the policy lock.
    }
    private fun resetConnections(reason:String,forceRestart:Boolean=false){
        if(worker.isShutdown||!networkResetQueued.compareAndSet(false,true))return
        val requested=epoch
        try{worker.execute {try{
            val session=currentStart?:networkRecovery?:return@execute
            if(requested!=epoch||!recoveryLive(session)||networkRecoveryTask!=null)return@execute
            ClientEvents.record(this,"Восстановление","Сетевое событие · $reason · пересоздание=$forceRestart")
            beginRecovery(session,if(forceRestart)RecoveryReason.SERVER_FAILURE else RecoveryReason.NETWORK_LOSS)
        }finally{networkResetQueued.set(false)}}}catch(_:java.util.concurrent.RejectedExecutionException){networkResetQueued.set(false)}
    }
    private fun requestEngineRecovery(captured:Long,requested:Long){
        if(worker.isShutdown)return
        try{worker.execute {
            if(captured!=generation||requested!=epoch||destroyed||currentService!==this)return@execute
            val session=currentStart?:return@execute // Startup caller owns failures before readiness.
            beginRecovery(session,RecoveryReason.ENGINE_CRASH)
        }}catch(_:java.util.concurrent.RejectedExecutionException){}
    }
    private fun runRecovery(session:RuntimeStart){
        if(worker.isShutdown)return
        try{worker.execute {
            if(!recoveryLive(session)||networkRecovery!==session)return@execute
            if(holdForCaptivePortal(session))return@execute
            val requested=session.requested
            rebuildingForNetwork=true
            try{
            if(session.vpn&&VpnService.prepare(this)!=null){onRevoke();return@execute}
            if(DirectAccessCheck.physical(this)==null){
                recoveryState.fail(requested,RecoveryReason.NETWORK_LOSS,SystemClock.elapsedRealtime())
                retryNetworkRecovery(session);return@execute
            }
            val pinned=requireNotNull(recoveryTarget)
            if(EngineUpdates.installationPending(this)||EngineUpdateScripts.mutationPending())throw IllegalArgumentException("RECOVERY_ENGINE_UPDATE_PENDING")
            pinned.choice?.let {choice->
                if(pinned.moduleIdentity!=null){
                    val installed=when(choice) {
                        BsNativeContract.CHOICE->BsNativeContract.workerPin(this,pinned.moduleIdentity)
                        WgAddonPolicy.KIND->EngineUpdatePacket.artifactPin(this)
                        else->primaryIdentity(choice)
                    }
                    require(installed==pinned.moduleIdentity){"RECOVERY_ENGINE_IDENTITY_CHANGED"}
                }
                sessionPrimaryChoice=choice
            }
            publishRecovery()
            // Reuse exactly the captured server/template/engine; never rerank the pool or fallback a version.
            startSource(session.template,session.candidate,session.vpn,session.port,session.applyRoutes,requested,true,pinned.outbound)
            if(!recoveryLive(session))return@execute
            check(core!=null&&core?.state=="RUNNING"){"RECOVERY_ENGINE_NOT_RUNNING"}
            clearNetworkRecovery()
            releaseRecoveryTun()
            recoveryState.connected(requested,SystemClock.elapsedRealtime());publishRecovery()
            recordHealthyConnection()
            val bs=session.template.optJSONObject("mcrf_bs")?.takeIf {autoAllowed()}
            if(bs!=null&&session.candidate!=null)scheduleHealth(session.template,BsPlan.candidates(bs),session.candidate,bs.getInt("interval"),session.vpn,session.port,session.applyRoutes,requested)
            if(bs==null&&sessionPrimaryChoice=="steer"&&autoAllowed())
                currentStart?.takeIf {it.requested==requested}?.let {scheduleSteerHealth(it)}
        }catch(error:Exception){
            if(recoveryLive(session))beginRecovery(session,RecoveryPolicy.classify(error,DirectAccessCheck.physical(this)!=null),FallbackChainPolicy.confirmedConnectionFailure(error))
        }finally{
            rebuildingForNetwork=false
        }}}catch(_:java.util.concurrent.RejectedExecutionException){networkResetQueued.set(false)}
    }
    private val effectivePreferences by lazy {ProfilePolicyRuntime.preferences(this)}
    private val preferences get() = effectivePreferences
    private var subscriptionRoutingIds=emptyList<String>()
    private var manualVpnRoutes=false
    private fun runtimeRoutes(ordinary:()->JSONObject):JSONObject =
        runtimeRoutes(if(fixtureMode)null else ProfilePolicyRuntime.active(this),ordinary)
    private fun runtimeRoutes(policy:ProfilePolicyAuthority.Policy?,ordinary:()->JSONObject):JSONObject =
        if(fixtureMode)ordinary()else {
            val routes=policy?.routing(ordinary)?:ordinary()
            if(policy?.configuration!=null)routes else {
                val catalogue=Connections(this).apply {load()}
                SubscriptionRouting.projectPool(routes,subscriptionRoutingIds.ifEmpty {listOf(selectedId).filter(String::isNotEmpty)},catalogue.subscriptions())
            }
        }.let {if(manualVpnRoutes)RoutingConnectionBinding.forManualVpn(it)else it}
    private fun profilePolicyCurrent()=fixtureMode||ProfilePolicyRuntime.active(this)?.takeIf {it.configuration!=null}?.id==profilePolicyOwner
    private fun streamChangeOwned(requested:Long,running:Boolean):Boolean =
        CarrierStreamRestartPolicy.owned(requested,epoch,!destroyed&&currentService===this&&!fixtureMode&&!safeModeActive,
            running,preferences.getBoolean("wantedRunning",false)&&!preferences.getBoolean(RECOVERY_USER_STOPPED,false),
            vpnMode,tunnel!=null&&VpnService.prepare(this)==null,profilePolicyCurrent())&&networkRecovery==null&&bsWifiPaused==null

    /** Called on main after persistence, never through START or network-profile selection. */
    private fun queueStreamChange(changes:Map<String,Int>,completed:(String)->Unit) {
        val requested=epoch
        if(!streamChangeOwned(requested,streamChangeApplying||(core?.state=="RUNNING"||directTunOwned&&directTunSession?.isAlive()==true)&&currentStart?.requested==requested)) {
            completed("Сохранено. Подходящего работающего подключения нет; значение применится при подключении.");return
        }
        val ticket=streamChanges.offer(requested,changes)
        streamChangeTimer?.let(main::removeCallbacks)
        val task=Runnable {
            if(worker.isShutdown){completed("Сохранено; служба останавливается, применение отменено.");return@Runnable}
            try {worker.execute {
                val latest=streamChanges.snapshot(ticket,epoch)
                if(latest==null){main.post {completed("Сохранено; применение отменено или объединено с новым изменением.")};return@execute}
                var message="Сохранено; применение отменено, подключение изменилось."
                try {message=applyStreamChange(ticket,latest)}
                catch(error:Exception){
                    ClientEvents.record(this,"Потоки","Применение сохранённого значения к активному сеансу не подтверждено. ${NetworkRecoveryFailure.classify(error).detail}")
                    message=CarrierStreamRestartPolicy.blockedExplanation(error)?.let {
                        "Сохранено. $it Текущее подключение сохранено."
                    }?:"Сохранено, но применение к работающему подключению не подтверждено; проверьте журнал."
                }
                finally {
                    streamChanges.complete(ticket)
                    main.post {completed(message)}
                }
            }}catch(_:java.util.concurrent.RejectedExecutionException){completed("Сохранено; служба останавливается, применение отменено.")}
        }
        streamChangeTimer=task;main.postDelayed(task,600)
    }

    private fun applyDirectStreamChange(ticket:CarrierStreamRestartPolicy.Pending.Ticket,changes:Map<String,Int>):String {
        val requested=ticket.epoch
        val session=currentStart?:return "Сохранено; прямой сеанс остановлен."
        val started=directTunEffective?:return "Сохранено; параметры прямого сеанса не подтверждены."
        if(!streamChangeOwned(requested,directTunOwned&&directTunSession?.isAlive()==true&&session.requested==requested)||!directTunSelected())
            return "Сохранено; применение отменено, прямой сеанс изменился."
        val settings=CarrierStreamDefaults.load(this)
        if(changes.any {(kind,count)->settings.optInt(kind,CarrierStreamDefaults.default(kind))!=count})return "Сохранено; более новое значение ожидает применения."
        if(started.has(CarrierStreamDefaults.OVERRIDE))return "Сохранено. В прямом сеансе действует отдельная настройка сервера."
        val selection=directSelection(session.template,session.candidate,selectedId)
        val effective=CsqttDirectTunSession.effectiveProfile(this,selection.profile)
        val engine=EngineModules.carrierKind(effective)
        val kind=if(engine in CsqttGenerations.legacyEngines)"csqtt"else engine
        if(!CarrierStreamRestartPolicy.affected(changes.keys,kind,false,started.getInt("workers"),effective.getInt("workers")))
            return "Сохранено. Действующее число потоков прямого сеанса не изменилось."
        BsDirectTunContract.resolve(this,effective)
        VkCredentialBackoffStore(this).requireAllowed()
        if(streamChanges.snapshot(ticket,epoch)==null)return "Сохранено; применение объединено с новым изменением."
        streamChangeApplying=true
        try {
            status="Применяем число потоков · прямой TUN…";foreground(status)
            startDirectTun(session.template,session.candidate,session.vpn,session.port,session.applyRoutes,requested)
            if(!streamChangeOwned(requested,directTunSession?.isAlive()==true))return "Сохранено; прямой сеанс остановлен."
            val actual=directTunEffective?.getInt("workers")?:return "Сохранено; число потоков не подтверждено."
            ClientEvents.record(this,"Потоки","Прямой Rust TUN переподключён с параметром $actual потоков. Фактические активные потоки показывает транспорт.")
            return "Сохранено и применено к прямому TUN: запрошено $actual потоков. Ограничения сервера и VK действуют."
        }catch(error:Exception) {
            closeCore(keepTun=true)
            if(requested==epoch&&!destroyed&&currentService===this) {
                directTunOwned=true;currentStart=session
                status="VPN приостановлен · число потоков не применено";foreground(status)
            }
            throw error
        }finally{streamChangeApplying=false}
    }
    private fun applyStreamChange(ticket:CarrierStreamRestartPolicy.Pending.Ticket,changes:Map<String,Int>):String {
        if(directTunOwned)return applyDirectStreamChange(ticket,changes)
        val requested=ticket.epoch
        val session=currentStart?:return "Сохранено. Подключение остановлено; автоматический запуск не выполняется."
        val capture=streamRuntime?:return "Сохранено. Работающее подключение не использует эти дополнительные транспорты."
        if(!streamChangeOwned(requested,core?.state=="RUNNING"&&session.requested==requested))
            return "Сохранено; применение отменено, подключение изменилось."
        val settings=CarrierStreamDefaults.load(this)
        if(changes.any {(kind,count)->settings.optInt(kind,CarrierStreamDefaults.default(kind))!=count})
            return "Сохранено; более новое значение ожидает применения."
        val boardConsumers=streamBoardConsumers.filterKeys {carrierSwitchboard?.carrierFor(it)!=null}.values
        val consumers=listOfNotNull(capture.main)+capture.routed+boardConsumers
        val affected=consumers.any {consumer->
            val profile=consumer.profile
            if(profile.optString("type") !in SwgProfile.TYPES||profile.has(CarrierStreamDefaults.OVERRIDE))false
            else {
                val unresolved=CsqttAutoDetection.negotiates(profile)
                val engine=if(unresolved)"unknown-csqtt"else EngineModules.carrierKind(profile)
                val kind=if(engine in CsqttGenerations.legacyEngines)"csqtt"else engine
                if(!unresolved&&kind !in changes||unresolved&&changes.keys.none {it=="csqtt"||it=="csqtt25"})false
                else {
                    check(!profile.optBoolean(CarrierStreamRestartPolicy.UNVERIFIED)&&!unresolved){
                        "Параметры активного транспорта не подтверждены"
                    }
                    CarrierStreamRestartPolicy.affected(changes.keys,kind,false,
                        profile.getInt("workers"),
                        CarrierStreamDefaults.apply(profile,settings).getInt("workers"))
                }
            }
        }
        if(!affected)return "Сохранено. Действующее число потоков не изменилось: другой транспорт, лимит звонков или настройка сервера."
        // A known provider cooldown must not tear down a healthy existing session.
        VkCredentialBackoffStore(this).requireAllowed()
        val selections=linkedMapOf<String,String>()
        runningConfig?.optJSONArray("outbounds")?.let {outputs->repeat(outputs.length()){index->
            val node=outputs.getJSONObject(index)
            if(node.optString("type")=="selector") {
                val group=node.getString("tag")
                core?.selectedOutbound(group)?.takeIf {it.isNotBlank()}?.let {selections[group]=it}
            }
        }}
        val retained=capture.copy(selections=selections)
        val previousSelectedId=selectedId
        val previousSelectedOutbound=selectedOutbound
        val frozen=if(autoDisabled)selectedOutbound.takeIf {it.isNotEmpty()}else null
        if(streamChanges.snapshot(ticket,epoch)==null||!streamChangeOwned(requested,core?.state=="RUNNING"))
            return "Сохранено; применение отменено или объединено с новым изменением."
        streamChangeApplying=true
        try {
            status="Применяем число потоков · короткое переподключение…";foreground(status)
            ClientEvents.record(this,"Потоки","Настройка сохранена; переподключаем тот же сеанс. Возможен короткий перерыв трафика; TUN, серверы и правила сохраняются.")
            // Exact same captured dispatcher, native route workers, template and candidate.
            // closeCore's existing retirement barrier finishes ALL old children before a new start.
            if(retained.choice==BsNativeContract.CHOICE)startSource(session.template,session.candidate,session.vpn,session.port,session.applyRoutes,requested,true,frozen)
            else startSourceWithCore(session.template,session.candidate,session.vpn,session.port,session.applyRoutes,
                requested,true,frozen,retained.choice,retained.nativePreparation,retained.nativeEntry,null,retained)
            if(!streamChangeOwned(requested,core?.state=="RUNNING"))return "Сохранено; применение отменено, подключение остановлено."
            sessionPrimaryChoice=retained.choice
            selectedId=previousSelectedId;selectedOutbound=previousSelectedOutbound
            val bs=session.template.optJSONObject("mcrf_bs")
            if(autoAllowed()&&bs!=null&&session.candidate!=null)
                scheduleHealth(session.template,BsPlan.candidates(bs),session.candidate,bs.getInt("interval"),session.vpn,session.port,session.applyRoutes,requested)
            else if(autoAllowed()&&sessionPrimaryChoice=="steer")currentStart?.let {scheduleSteerHealth(it)}
            val latestDefaults=runCatching {CarrierStreamDefaults.load(this)}.getOrNull()
                ?:return "Сеанс переподключён; проверить актуальность сохранённого значения не удалось."
            if(streamChanges.snapshot(ticket,epoch)==null||changes.any {(kind,count)->
                latestDefaults.optInt(kind,CarrierStreamDefaults.default(kind))!=count})
                return "Сеанс переподключён; более новое сохранённое значение ожидает применения."
            ClientEvents.record(this,"Потоки","Число потоков применено переподключением текущего сеанса; серверные ограничения сохранены.")
            return "Сохранено и применено: текущий сеанс переподключён. Ограничения сервера действуют."
        }catch(error:Exception){
            // No retry, alternate server, new selection or Direct fallback for a settings failure.
            closeCore(keepTun=session.vpn)
            if(requested!=epoch||destroyed||currentService!==this)return "Сохранено; применение отменено остановкой или сменой подключения."
            status=if(session.vpn)"VPN приостановлен · число потоков не применено"else"Прокси остановлен · число потоков не применено"
            foreground(status)
            ClientEvents.record(this,"Потоки","Переподключение для нового числа потоков не удалось. Трафик приостановлен; автоматический повтор не запущен. ${NetworkRecoveryFailure.classify(error).detail}")
            return CarrierStreamRestartPolicy.blockedExplanation(error)?.let {
                "Сохранено. $it Трафик приостановлен."
            }?:"Сохранено, но переподключение не удалось. Трафик приостановлен; подключитесь вручную."
        }finally{streamChangeApplying=false}
    }
    override fun onStartCommand(incoming: Intent?, flags: Int, startId: Int): Int {
        if(incoming==null||incoming.action==START||incoming.action==STOP||incoming.action==VpnService.SERVICE_INTERFACE) {
            directAdmissionSerial++;directAdmissionPending=false
        }
        return handleStartCommand(incoming,flags,startId)
    }
    private fun handleStartCommand(incoming:Intent?,flags:Int,startId:Int,admitted:DirectAdmission?=null):Int {
        if(destroyed)return START_NOT_STICKY
        if(incoming?.action==DIRECT_TUN_CHANGED){queueDirectTunChange();return START_STICKY}
        if(incoming?.action==VPN_OWNER_ISOLATION_CHANGED||
            incoming?.action==RELOAD_ROUTES&&directTunOwned&&currentStart?.applyRoutes==true) {
            if(!vpnOwnerIsolationRequired())ownerIsolationUpgradePending=false
            val requested=epoch
            if(!fixtureMode&&!worker.isShutdown)worker.execute {
                val session=currentStart?:return@execute
                if(destroyed||currentService!==this||requested!=epoch||!session.vpn)return@execute
                val updateRequested=incoming?.action==VPN_OWNER_ISOLATION_CHANGED
                val activeCore=core
                if(updateRequested&&vpnOwnerIsolationRequired()&&!directTunOwned&&activeCore!=null&&
                    activeCore.choiceId!=BsNativeContract.CHOICE&&
                    !(if(activeCore.choiceId==WgAddonPolicy.KIND)activeCore.packetOwnerIsolationSupported
                        else activeCore.primaryOwnerIsolationSupported)){
                    keepSessionWhileWaitingForOwnerIsolationUpdate()
                    return@execute
                }
                try {
                    if(directTunOwned)startDirectTun(session.template,session.candidate,true,session.port,session.applyRoutes,requested)
                    else {
                        val frozen=selectedOutbound.takeIf {autoDisabled&&it.isNotEmpty()}
                        startSource(session.template,session.candidate,true,session.port,session.applyRoutes,requested,true,frozen)
                        val bs=session.template.optJSONObject("mcrf_bs")
                        if(bs!=null&&session.candidate!=null&&autoAllowed())
                            scheduleHealth(session.template,BsPlan.candidates(bs),session.candidate,bs.getInt("interval"),true,session.port,session.applyRoutes,requested)
                        else if(autoAllowed()&&sessionPrimaryChoice=="steer")currentStart?.let {scheduleSteerHealth(it)}
                    }
                }catch(error:Exception) {
                    if(requested==epoch&&!destroyed&&currentService===this) {
                        if(updateRequested&&error is BsDirectTunContract.OwnerIsolationUnavailable&&hasActiveSession()){
                            keepSessionWhileWaitingForOwnerIsolationUpdate()
                            return@execute
                        }
                        val detail=NetworkRecoveryFailure.classify(error).detail
                        ClientEvents.record(this,"Маршруты","Не удалось обновить список приложений VPN · $detail.")
                        if(!hasActiveSession()){status="Ошибка подключения · $detail";foreground(status)}
                    }
                }
            }
            return START_STICKY
        }
        if((directTunOwned||directTunStarting||directTunTransition!=null)&&(incoming?.action==RELOAD_ROUTES||incoming?.action==NETWORK_PROFILE_CHANGED||incoming?.action==AUTO_OFF))
            return START_STICKY // Saved policy stays intact; this explicit test bypasses it only while owned.
        if(incoming?.action!=STOP&&TrafficCaptureService.blocksServerStart()) {
            if(!hasActiveSession()){status="Сначала остановите режим Только запись";stopSelf(startId)}
            return START_NOT_STICKY
        }
        val systemAlwaysOn=incoming?.action==VpnService.SERVICE_INTERFACE&&isAlwaysOn
        val resume=incoming==null||systemAlwaysOn||incoming?.getBooleanExtra(RESUME_REQUEST,false)==true
        if(resume){
            behavior=try{ClientBehaviorStore.load(this)}catch(_:Exception){ClientBehaviorSettings()}
            val denied=safeModeActive||preferences.getBoolean(RECOVERY_USER_STOPPED,false)||
                !systemAlwaysOn&&!behavior.shouldReconnect(preferences.getBoolean("wantedRunning",false),true,
                    VpnService.prepare(this)==null,hasActiveSession())
            if(denied||hasActiveSession()){
            if(!hasActiveSession()){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)}
                return START_NOT_STICKY
            }
        }
        if(incoming?.action==REFRESH_POWER_POLICY){
            if(!destroyed&&!worker.isShutdown)worker.execute {refreshBsPowerLease()}
            return START_STICKY
        }
        if(incoming?.action==REFRESH_BS_ENVIRONMENT){reloadBsWifiMonitor();return START_STICKY}
        if(incoming?.action==RELOAD_ROUTES||incoming?.action==NETWORK_PROFILE_CHANGED){
            val networkProfile=incoming.action==NETWORK_PROFILE_CHANGED
            if(networkProfile)bsDetection?.refresh()
            if(currentStart==null&&bsWifiPaused==null&&core==null&&!cleanupPending()) {stopSelf(startId);return START_NOT_STICKY}
            val requested=epoch
            if(!fixtureMode&&!worker.isShutdown)worker.execute {
                if(destroyed||currentService!==this||requested!=epoch)return@execute
                val session=currentStart?:bsWifiPaused?:return@execute
                if(!session.applyRoutes&&!networkProfile)return@execute
                try {
                    if(networkProfile) {
                        val source=NetworkProfilesRuntime.template(this)
                        if(bsWifiPaused!=null){bsWifiPaused=session.copy(template=source,candidate=null,applyRoutes=true);return@execute}
                        clearNetworkRecovery()
                        autoDisabled=BsAutoSelectionPolicy.selected(preferences)==BsAutoSelectionPolicy.OFF
                        steerFrozenId="";selectedOutbound="";routeBindingSelection=null
                        val bs=source.optJSONObject("mcrf_bs")
                        if(bs==null)startVpnWithBsPreflight(source,session.vpn,session.port,true,requested)
                        else {
                            val candidates=BsPlan.candidates(bs)
                            val chosen=chooseEngine(source,candidates,session.vpn,session.port,true,requested)
                            scheduleHealth(source,candidates,chosen,bs.getInt("interval"),session.vpn,session.port,true,requested)
                        }
                        ClientEvents.record(this,"Сетевой профиль","Маршруты и выбор серверов применены к работающему подключению.")
                        return@execute
                    }
                    // Reuse the current runtime template, selected BS candidate,
                    // engine choice, AUTO OFF state and listeners. Do not run a
                    // fresh section-selection/preflight or replace user marks.
                    val frozen=if(autoDisabled)selectedOutbound.takeIf {it.isNotEmpty()}else null
                    startSource(session.template,session.candidate,session.vpn,session.port,true,requested,true,frozen)
                    if(requested!=epoch||destroyed)return@execute
                    val bs=session.template.optJSONObject("mcrf_bs")
                    if(autoAllowed()&&bs!=null&&session.candidate!=null)
                        scheduleHealth(session.template,BsPlan.candidates(bs),session.candidate,bs.getInt("interval"),session.vpn,session.port,true,requested)
                    else if(autoAllowed()&&sessionPrimaryChoice=="steer")currentStart?.let {scheduleSteerHealth(it)}
                    ClientEvents.record(this,"Маршруты","Активная группа правил применена; параметры выбора серверов сохранены.")
                }catch(error:Exception){
                    if(requested==epoch&&!destroyed&&currentService===this){
                        val detail=if(ClientPrimaryPreparationFailure.code(error)=="PRIMARY_BS_TUN_ADOPTION_TIMEOUT")
                            "БС не завершил запуск системного VPN вовремя"
                        else NetworkRecoveryFailure.classify(error).detail
                        closeCore(keepTun=session.vpn)
                        status="Ошибка подключения: ${if(networkProfile)"сетевой профиль"else"группа правил"} не применён · $detail";foreground(status)
                        val retained=if(session.vpn)"Трафик VPN приостановлен, обход через прямой выход не включён."else"Прокси остановлен."
                        ClientEvents.record(this,"Маршруты","Не удалось применить группу · $detail. $retained")
                    }
                }
            }
            return START_STICKY
        }
        if(incoming?.action!=STOP&&(EngineUpdates.installationPending(this)||EngineUpdateScripts.mutationPending())){
            status="Обновление движка · завершите установку перед подключением";stopSelf(startId);return START_NOT_STICKY
        }
        if (incoming?.action == AUTO_OFF) {
            if(!fixtureMode&&ProfilePolicyRuntime.active(this)?.configuration!=null&&preferences.getBoolean("automatic",false))return START_STICKY
            synchronized(steerRunLock){
                autoDisabled = true
                if((core?.choiceId=="steer"||nativeRouteSession!=null)&&selectedId.isNotEmpty())steerFrozenId=selectedId
            }
            ProbeCoordinator.cancelRuntime(this)
            healthTask?.let(main::removeCallbacks); healthTask = null
            worker.execute { freezeSelection() }
            return START_STICKY
        }
        val intent = if(systemAlwaysOn||incoming==null&&preferences.getBoolean("wantedRunning", false)) Intent(this, McrfVpnService::class.java).setAction(START)
            .putExtra("tun", preferences.getBoolean("tun", true)).putExtra("port", preferences.getInt("port", 10808))
            .putExtra("applyRoutes", preferences.getBoolean("applyRoutes", true)) else incoming
        // Preflight must precede epoch publication AND every old-run side effect. A rejection
        // cannot rebind old HostRun/watchers to a new epoch, and epoch is never rolled back.
        if(admitted==null&&intent?.action==START&&intent.getBooleanExtra("tun",true)&&
            CsqttDirectTunSettings.selected {key,fallback->preferences.getBoolean(key,fallback)}&&
            (!intent.hasExtra("pendingWhitelists")||intent.getBooleanExtra("pendingWhitelists",false))&&
            !(BuildConfig.DEBUG&&intent.hasExtra("test_fixture"))) {
            queueDirectStartAdmission(Intent(intent),flags,startId)
            return START_STICKY
        }
        val requested = ++epoch
        captivePortalRetry?.let(main::removeCallbacks);captivePortalRetry=null
        captivePortalStatus=null;captivePortalNeedsRecovery=false
        releaseBsPowerLease()
        bsWifiPaused=null
        main.removeCallbacks(bsWifiCheck)
        ProbeCoordinator.cancelRuntime(this)
        clearNetworkRecovery()
        if (intent == null || intent.action == STOP) {
            if(!fixtureMode)AnubisLaunchObserver.onVpnStopped()
            recoveryState.cancel(requested,RecoveryReason.MANUAL_STOP);publishRecovery()
            ServerSwitchTransition.observeCommand(intent?.getStringExtra(ServerSwitchTransition.EXTRA))
            val stopRequestedAt=android.os.SystemClock.elapsedRealtime()
            if(!fixtureMode) preferences.edit().putBoolean("wantedRunning", false).putBoolean(RECOVERY_USER_STOPPED,true).apply()
            status="Остановка движка…"
            try{foreground(status)}catch(_:Exception){}
            core?.requestStop();directTunSession?.requestStop()
            worker.execute {
                // Log I/O stays off the main/UI thread, including log rotation.
                ClientEvents.record(this,"Клиент","Остановка: очередь службы пройдена за ${android.os.SystemClock.elapsedRealtime()-stopRequestedAt} мс; ожидаем освобождение движков/VPN. Подтверждение будет отдельной записью.")
                runtimeHealth.stopped(HealthDisconnect.USER)
                closeCore()
                releaseRecoveryTun()
                if(!destroyed&&currentService===this&&requested==epoch){status = "Отключено"; McrfQuickTile.connectionChanged(applicationContext); ClientEvents.record(this, "Клиент", "Соединение остановлено; очередь и освобождение ресурсов заняли ${android.os.SystemClock.elapsedRealtime()-stopRequestedAt} мс"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)}
            }
            return START_NOT_STICKY
        }
        if (intent.action != START) { stopSelf(startId); return START_NOT_STICKY }
        LastConnectionFailure.clear()
        fallbackChain.reset()
        recoveryState.start(requested);publishRecovery()
        ServerSwitchTransition.observeCommand(null)
        autoDisabled = false
        steerFrozenId=""
        sessionPrimaryChoice=null;autonomousDispatcherChoice=null;autonomousDispatcherPhysical=null;fallbackAttempted=false;runningPrimaryIdentity=null
        selectedOutbound=""
        val testInput=if(BuildConfig.DEBUG)intent.getStringExtra("test_fixture") else null
        fixtureMode=testInput!=null
        lastForegroundContent=null // A new START must always promote the service, even after a failed prior start.
        pendingWhitelists=if(intent.hasExtra("pendingWhitelists"))intent.getBooleanExtra("pendingWhitelists",false)else null
        directTunBsContext=admitted?.direct?:pendingWhitelists?:false
        directTunStarting=admitted?.direct==true
        connectionStep="prepare";status="Подключение…"
        foreground("Подключение…")
        val vpn = if(systemAlwaysOn)true else intent.getBooleanExtra("tun", true)
        manualVpnRoutes=vpn&&!fixtureMode&&intent.getBooleanExtra(
            MANUAL_VPN,!preferences.getBoolean("automatic",false))
        val preflightPort=intent.getIntExtra("preparedProxyPort",0).takeIf {it in 1024..65535}
        val port = if(fixtureMode)intent.getIntExtra("port",10808)else preflightPort?:try{ClientTunStore(this).load().choosePort()}catch(_:Exception){intent.getIntExtra("port",10808)}
        val applyRoutes = intent.getBooleanExtra("applyRoutes", false)
        val connectStartedNs=intent.getLongExtra(ConnectTiming.EXTRA,0)
        val serviceQueuedNs=ConnectTiming.now()
        if(!fixtureMode) preferences.edit().putBoolean("wantedRunning", true).putBoolean(RECOVERY_USER_STOPPED,false).apply()
        admitted?.enqueued=true
        try {worker.execute {
            ConnectTiming.mark(this,"service_queue",serviceQueuedNs)
            val serviceStartedNs=ConnectTiming.now()
            var startupSession:RuntimeStart?=null
            var directAttempt=false
            try {
                runtimeHealth.stopped(HealthDisconnect.USER)
                if (requested != epoch) return@execute
                val deferForAdmission=admitted?.direct==true
                val tileHandover=intent.getBooleanExtra(EXPLICIT_TILE_START,false)&&vpn&&tunnel!=null
                if(!deferForAdmission&&!tileHandover)ConnectTiming.measure(this,"previous_core_close"){closeCore()}
                if(requested!=epoch||destroyed||currentService!==this)return@execute
                fixtureP1OwnerHelperSha=null
                fixtureStrictOwner=null
                fixtureSteerOwnerPin=null
                fixtureBsNativePhysical=null
                vpnMode = vpn; nativeVpnExclusion = false
                if(!fixtureMode)behavior=ClientBehaviorStore.load(this)
                if (vpn && VpnService.prepare(this) != null) throw IOException("Сначала разреши VPN в приложении.")
                connectionStep="prepare";status = "Подключение…"
                val source=admitted?.source?:if(testInput==null){carrierTimeout=60000;healthUrl="https://www.gstatic.com/generate_204";ConnectTiming.measure(this,"profile_read"){
                    val profiles=JSONObject(ProfileStore(this,"preferences.enc").read().ifBlank {"{}"}).optJSONObject(ConditionProfiles.KEY)
                    if(!intent.getBooleanExtra(EXPLICIT_TILE_START,false)&&!(directTunSelected()&&vpn&&pendingWhitelists==true)&&applyRoutes&&profiles?.has("activeId")==true)NetworkProfilesRuntime.template(this)else JSONObject(ProfileStore(this).read())
                }}
                    else readFixture(testInput)
                startupSession=RuntimeStart(JSONObject(source.toString()),null,vpn,port,applyRoutes,requested)
                val bs=source.optJSONObject("mcrf_bs")
                if(manualVpnRoutes&&!resume)source.optJSONObject("mcrf_carrier")
                    ?.takeIf {it.optString("type") in SwgProfile.TYPES}
                    ?.let {NativeCarrier.confirmConnectionPing(this,it,java.util.function.BooleanSupplier {
                        requested!=epoch||destroyed||currentService!==this
                    }){connectionStep="server_ping"}}
                if(connectionStep=="server_ping")connectionStep="prepare"
                pendingWhitelists=sourceWhitelists(source)
                if(admitted==null&&!intent.hasExtra("pendingWhitelists"))directTunBsContext=pendingWhitelists==true
                directAttempt=directTunRequested(vpn)
                if(deferForAdmission&&!directAttempt)ConnectTiming.measure(this,"previous_core_close"){closeCore()}
                if(!directAttempt&&resume&&holdForCaptivePortal(requireNotNull(startupSession)))return@execute
                if(directAttempt)startDirectTun(source,null,vpn,port,applyRoutes,requested,admitted)
                else if(bs==null&&(fixtureBsNativePhysical!=null||intent.getBooleanExtra(EXPLICIT_TILE_START,false)))startSource(source,null,vpn,port,applyRoutes,requested,tileHandover)
                else if(bs==null) startVpnWithBsPreflight(source,vpn,port,applyRoutes,requested)
                else {
                    val candidates=BsPlan.candidates(bs)
                    TrafficState.reset("Выбираем движок БС…")
                    val chosen=chooseEngine(source,candidates,vpn,port,applyRoutes,requested)
                    scheduleHealth(source,candidates,chosen,bs.getInt("interval"),vpn,port,applyRoutes,requested)
                }
                if(requested==epoch){ConnectTiming.mark(this,"service_to_ready",serviceStartedNs);ConnectTiming.mark(this,"tap_to_ready",connectStartedNs)}
            } catch (error: Exception) {
                if (requested != epoch) { closeCore(); return@execute }
                if(directAttempt||directTunOwned) {
                    val diagnostic=CsqttDirectTunDiagnostic.failure(error,CsqttDirectTunDiagnostic.Phase.START)
                    LastConnectionFailure.remember(diagnostic.detail)
                    ClientEvents.record(this,"Direct TUN",diagnostic.code)
                    val retained=currentStart?:startupSession
                    closeCore(keepTun=vpn)
                    if(requested==epoch&&!destroyed&&currentService===this) {
                        directTunOwned=true;currentStart=retained
                        status=if(tunnel!=null)"VPN приостановлен · прямой TUN"else"Прямой TUN не запущен"
                        ClientEvents.record(this,"Direct TUN","Запуск отклонён или не подтверждён. Автоматического fallback нет; сохранённые правила не изменены.")
                        foreground(status)
                    }
                    return@execute
                }
                val portalSession=currentStart?:startupSession
                if(portalSession!=null&&RecoveryPolicy().retryable(RecoveryPolicy.classify(error))&&holdForCaptivePortal(portalSession))return@execute
                val failureDetail=ClientPrimaryPreparationFailure.code(error)?.let(ClientPrimaryPreparationFailure::message)
                    ?:ClientEvents.redact(error.message)
                LastConnectionFailure.remember(failureDetail)
                ClientEvents.record(this,"Ошибка","Ошибка подключения: ${LastConnectionFailure.detail()}")
                val failedSession=currentStart?:startupSession
                if(!fixtureMode&&failedSession!=null){
                    beginRecovery(failedSession,RecoveryPolicy.classify(error,DirectAccessCheck.physical(this)!=null),FallbackChainPolicy.confirmedConnectionFailure(error))
                    return@execute
                }
                val explanation=LastConnectionFailure.detail()!!
                // Record the redacted cause before potentially slow native retirement.
                closeCore()
                releaseRecoveryTun()
                if(requested!=epoch||destroyed||currentService!==this)return@execute
                status = "Ошибка подключения: $explanation"
                if(!fixtureMode)preferences.edit().putBoolean("wantedRunning", false).apply(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
            }finally {
                if(requested==epoch)directTunStarting=false
                admitted?.let {OptionalCoreRetirement.drain(it)}
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException) {
            admitted?.enqueued=false
            admitted?.let {releaseDirectAdmission(it)}
            directTunStarting=false
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    private fun keepSessionWhileWaitingForOwnerIsolationUpdate() {
        ownerIsolationUpgradePending=true
        status="VPN подключён · для изоляции приложений обновите движок"
        ClientEvents.record(this,"Маршруты","Текущее подключение сохранено. Обязательная проверка приложений заработает после обновления движка и переподключения.")
        foreground(status)
    }
    // P046_BEGIN: startup BS preflight; extracted alone into the P0-only snapshot.
    private fun prepareAutoBsTemplate():JSONObject {
        val catalogue=Connections(this).apply {load()}
        val marked=catalogue.bsStartupEntries()
        val mode=BsAutoSelectionPolicy.selected(preferences)
        val binding=runtimeRoutes {RoutingStore(this).apply{load()}.snapshot()}
        if(RoutingConnectionBinding.enabled(binding)){
            val entry=RoutingConnectionBinding.bsDetectorEntries(this,binding,catalogue.entries(),catalogue.probes()).first()
            val template=ServerImport.build(listOf(entry),entry.id,false,preferences.getInt("interval",60),AutoSelectionDeviation.millis(preferences),"Cloudflare")
            DnsConfig.applyGenerated(template,binding.getJSONObject("globalDns"))
            return template.put("mcrf_bs",JSONObject().put("interval",preferences.getInt("interval",60).coerceIn(5,86400)).put("connectionBound",true)
                .put("candidates",org.json.JSONArray().put(BsPlan.candidate(entry,preferences.getInt("interval",60)))))
        }
        val allowed=RoutingConnectionBinding.admitted(binding,catalogue.entries(),true).map{it.id}.toSet()
        val pool=(if(ProfilePolicyRuntime.active(this)?.configuration!=null||marked.isNotEmpty()&&mode!=BsAutoSelectionPolicy.OFF)marked else catalogue.autoCandidates(catalogue.entries()).filter {AutoSelection.eligible(it,AutoSelection.BS_ENGINES)}
            .sortedBy {AutoSelection.engineTier(it)}).filter {entry->
            entry.id in allowed&&(entry.carrier==null||runCatching {NativeCarrier.executable(this,EngineModules.carrierKind(entry.carrier));true}.getOrDefault(false))
        }
        require(pool.isNotEmpty()) {"Автоопределение БС: нет серверов с установленным совместимым движком. Проверь серверы БС и движки."}
        val ranked=AutoSelection.startup(pool,pool.map {it.id}.toSet(),catalogue.probes(),System.currentTimeMillis(),AutoSelection.BS_ENGINES)
        val ordered=BsAutoSelectionPolicy.targets(ranked,BsAutoSelectionPolicy.last(preferences,true),mode){it.id}
        require(ordered.isNotEmpty()) {"Автовыбор выключен: сначала выберите сервер БС вручную."}
        val template=BsPlan.build(ordered,preferences.getInt("interval",60),AutoSelectionDeviation.millis(preferences),"Cloudflare")
        val routes=RoutingStore(this).apply {load()}
        DnsConfig.applyGenerated(template,runtimeRoutes {routes.snapshot()}.getJSONObject("globalDns"))
        return template
    }
    private var autoVpnCandidateCursor=0
    private fun autoVpnTargets(fallback:JSONObject?,excludeId:String?=null):List<JSONObject> {
        val catalogue=Connections(this).apply {load()}
        val steer=ClientPrimarySelection.selected(this)=="steer"
        val binding=runtimeRoutes {RoutingStore(this).apply{load()}.snapshot()}
        val pool=catalogue.autoCandidates(RoutingConnectionBinding.admitted(binding,catalogue.entries(),false)).filter {it.id!=excludeId&&(ProfilePolicyRuntime.active(this)?.configuration==null||it.id in catalogue.autoIds())&&AutoSelection.eligible(it,AutoSelection.LATENCY)&&(!steer||SteerProbePlan.supports(it))}
        val fastest=AutoSelection.startup(pool,pool.map {it.id}.toSet(),catalogue.probes(),System.currentTimeMillis())
        val mode=BsAutoSelectionPolicy.selected(preferences)
        val ordered=BsAutoSelectionPolicy.targets(fastest,BsAutoSelectionPolicy.last(preferences,false),mode){it.id}
        val dns=runtimeRoutes {RoutingStore(this).apply {load()}.snapshot()}.getJSONObject("globalDns")
        val attempts=BsAutoSelectionPolicy.attempts(ordered,autoVpnCandidateCursor)
        autoVpnCandidateCursor=if(ordered.size>1)(autoVpnCandidateCursor+7)%(ordered.size-1) else 0
        return attempts.mapNotNull {entry->runCatching {
            ServerImport.build(listOf(entry),entry.id,false,preferences.getInt("interval",60),AutoSelectionDeviation.millis(preferences),"Cloudflare").also {DnsConfig.applyGenerated(it,dns)}
        }.getOrNull()}.ifEmpty {if(mode==BsAutoSelectionPolicy.OFF||RoutingConnectionBinding.enabled(binding))emptyList()else listOfNotNull(fallback)}
    }
    private fun startVpnWithBsPreflight(source:JSONObject,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long) {
        if(preferences.getBoolean(BsDetectionMonitor.KEY,false)&&holdForCaptivePortal(RuntimeStart(source,null,vpn,port,applyRoutes,requested)))return
        routeBindingDetectionAllowed=if(applyRoutes){val state=runtimeRoutes {RoutingStore(this).apply{load()}.snapshot()};!RoutingConnectionBinding.enabled(state)||RoutingConnectionBinding.networkDriven(state,true)}else true
        fun cancelled()=requested!=epoch||destroyed||currentService!==this
        val primary=ClientPrimarySelection.selected(this)
        val standalonePacket=routeBindingDetectionAllowed&&preferences.getBoolean(BsDetectionMonitor.KEY,false)&&
            standalonePacketEntry(source,vpn,applyRoutes)!=null&&WgAddonPolicy.prefersStandalone(this)
        val decision=BsStartupPolicy.start(
            enabled={routeBindingDetectionAllowed&&preferences.getBoolean(BsDetectionMonitor.KEY,false)&&(!standalonePacket||primary!=null)},fixture=fixtureMode,
            alreadyBs=sourceWhitelists(source),cancelled={cancelled()},
            probe={
                require(primary!="steer"||standalonePacket) {"Автоопределение БС: выбранное ядро пока не поддерживает транспорты БС."}
                status="Проверяем доступность сайтов…";foreground("Проверка сети перед подключением VPN")
                ConnectTiming.measure(this,"bs_preflight"){DirectAccessCheck.check(this,cancelled={cancelled()})}
            },
            startVpn={startSource(source,null,vpn,port,applyRoutes,requested,false)},
            startBs={
                val template=prepareAutoBsTemplate()
                val candidates=BsPlan.candidates(template.getJSONObject("mcrf_bs"))
                check(!cancelled()&&preferences.getBoolean(BsDetectionMonitor.KEY,false)) {"Подключение отменено"}
                val original=RuntimeStart(JSONObject(source.toString()),null,vpn,port,applyRoutes,requested)
                require(rememberVpnSession(original)) {"Не удалось сохранить цель возврата в VPN"}
                check(!cancelled()) {"Подключение отменено"}
                status="Подключаем БС…";foreground("Ограничения обнаружены до запуска VPN; подключаем белые списки")
                val chosen=chooseEngine(template,candidates,vpn,port,applyRoutes,requested)
                check(!cancelled()) {"Подключение отменено"}
                if(autoAllowed())scheduleHealth(template,candidates,chosen,preferences.getInt("interval",60),vpn,port,applyRoutes,requested)
                autoBsSwitchSerial++
                ClientEvents.record(this,"Клиент","Автоопределение БС: ограничения обнаружены до запуска VPN; сразу подключены белые списки. Периодические проверки и возврат к сохранённому VPN остаются включены.")
                if(autoSectionNotice.accept(true,autoBsSwitchSerial,requested,epoch,!cancelled()))AutoSectionNotifications.post(this,true)
            })
        check(decision!=BsStartupPolicy.Decision.CANCELLED) {"Подключение отменено или сеть изменилась во время проверки"}
    }
    // P046_END
    private fun sourceWhitelists(template:JSONObject):Boolean {
        if(template.has("mcrf_carrier")||template.has("mcrf_bs"))return true
        if(fixtureMode)return false
        val entries=Connections(this).apply {load()}.entries()
        val target=template.optJSONObject("route")?.optString("final")
        val entry=entries.firstOrNull {ServerImport.tag(it)==target}
            ?:entries.firstOrNull {it.id==preferences.getString("selected","")&&it.configuration!=null&&preferences.getBoolean("catalogueProfile",false)}
        return entry?.whitelists==true
    }
    private data class DirectSelection(val profile:JSONObject,val id:String,val name:String)
    /** A verified, usage-locked snapshot. Never publishes an epoch or mutates a live run. */
    private class DirectAdmission(val source:JSONObject,val selection:DirectSelection?=null,
        val effective:JSONObject?=null,val installation:BsDirectTunContract.Installation?=null,
        private val usage:EngineUpdateGate.Usage?=null):AutoCloseable {
        val direct get()=selection!=null
        @Volatile var enqueued=false
        private var taken=false
        fun takeUsage():EngineUpdateGate.Usage {check(!taken);return checkNotNull(usage).also {taken=true}}
        override fun close(){if(!taken)usage?.close()}
    }
    private fun queueDirectStartAdmission(intent:Intent,flags:Int,startId:Int) {
        val serial=directAdmissionSerial
        val liveEpoch=epoch
        fun current()=!destroyed&&currentService===this&&serial==directAdmissionSerial&&liveEpoch==epoch
        directAdmissionPending=true
        if(!hasActiveSession())status="Подключение…"
        foreground(status)
        try {worker.execute {
            var prepared:DirectAdmission?=null
            var handedOff=false
            try {
                if(!current())return@execute
                val explicitBs=intent.takeIf {it.hasExtra("pendingWhitelists")}?.getBooleanExtra("pendingWhitelists",false)
                val applyRoutes=intent.getBooleanExtra("applyRoutes",false)
                val profiles=JSONObject(ProfileStore(this,"preferences.enc").read().ifBlank {"{}"}).optJSONObject(ConditionProfiles.KEY)
                val source=if(explicitBs!=true&&applyRoutes&&profiles?.has("activeId")==true)NetworkProfilesRuntime.template(this)
                    else JSONObject(ProfileStore(this).read())
                // Explicit ordinary-VPN context wins even if a network policy contains BS routes.
                val bs=explicitBs?:sourceWhitelists(source)
                if(bs) {
                    check(current()&&!safeModeActive&&directTunSelected()&&VpnService.prepare(this)==null) {"DIRECT_TUN_ADMISSION_CANCELLED"}
                    val selection=directSelection(source,null,preferences.getString("selected","").orEmpty())
                    CsqttDirectTunSupport.profileReason(selection.profile,true)?.let {throw IOException(it)}
                    val effective=CsqttDirectTunSession.effectiveProfile(this,selection.profile)
                    if(intent.getBooleanExtra(MANUAL_VPN,!preferences.getBoolean("automatic",false))&&
                        !intent.getBooleanExtra(RESUME_REQUEST,false)) {
                        NativeCarrier.confirmConnectionPing(this,effective,java.util.function.BooleanSupplier {!current()}) {
                            connectionStep="server_ping"
                        }
                    }
                    val usage=EngineUpdateGate.acquireUsage(this)?:throw IOException("BS Engine обновляется; прямой TUN не запущен.")
                    try {
                        val installation=BsDirectTunContract.resolve(this,effective)
                        if(vpnOwnerIsolationRequired())BsDirectTunContract.requireOwnerIsolation(installation)
                        prepared=DirectAdmission(source,selection,effective,installation,usage)
                    }catch(error:Exception){OptionalCoreRetirement.drain(usage);throw error}
                }else prepared=DirectAdmission(source)
                if(!current())return@execute
                val ready=checkNotNull(prepared)
                handedOff=main.post {
                    try {
                        if(current()&&(!ready.direct||directTunSelected()&&!safeModeActive&&VpnService.prepare(this)==null)) {
                            directAdmissionPending=false
                            handleStartCommand(intent,flags,startId,ready)
                        }else if(current()) {
                            directAdmissionPending=false
                            LastConnectionFailure.remember("Прямой TUN: запуск отменён до остановки работающего сеанса.")
                            if(!hasActiveSession()){status="Прямой TUN не запущен";stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)}
                        }
                    }finally {
                        if(!ready.enqueued)releaseDirectAdmission(ready)
                    }
                }
            }catch(_:Exception) {
                if(current()) {
                    LastConnectionFailure.remember("Прямой TUN не допущен: проверьте выбранный BS-сервер, ручные хеши, разрешение VPN и capability BS Engine. Предыдущее соединение не изменялось.")
                    ClientEvents.record(this,"Direct TUN","Допуск отклонён до изменения epoch и остановки работающего сеанса. Fallback не запускался.")
                    main.post {
                        if(current()) {
                            directAdmissionPending=false
                            if(!hasActiveSession()){status="Прямой TUN не запущен";stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)}
                        }
                    }
                }
            }finally {if(!handedOff)prepared?.let {OptionalCoreRetirement.drain(it)}}
        }}catch(_:java.util.concurrent.RejectedExecutionException){directAdmissionPending=false}
    }
    private fun releaseDirectAdmission(admission:DirectAdmission) {
        try {worker.execute {OptionalCoreRetirement.drain(admission)}}
        catch(_:java.util.concurrent.RejectedExecutionException) {
            Thread({OptionalCoreRetirement.drain(admission)},"mcrf-direct-admission-retire").apply {isDaemon=true;start()}
        }
    }
    /** Service worker only: never pick an auto candidate or use an unrelated selected-tab preference. */
    private fun directSelection(template:JSONObject,candidate:JSONObject?,startingId:String?=null):DirectSelection {
        candidate?.optJSONObject("profile")?.let {return DirectSelection(JSONObject(it.toString()),candidate.optString("id"),candidate.optString("name","CSQTT"))}
        template.optJSONObject("mcrf_carrier")?.let {return DirectSelection(JSONObject(it.toString()),
            startingId?:selectedId.ifEmpty {preferences.getString("selected","").orEmpty()},it.optString("name","CSQTT"))}
        val id=startingId?:if(currentStart!=null)selectedId else preferences.getString("selected","").orEmpty()
        val entry=Connections(this).apply {load()}.entries().firstOrNull {it.id==id&&it.whitelists&&it.carrier!=null}
            ?:throw IOException("Прямой TUN: выберите один поддержанный сервер в разделе БС.")
        return DirectSelection(JSONObject(checkNotNull(entry.carrier).toString()),entry.id,entry.name)
    }
    private fun directLive(requested:Long)=requested==epoch&&!destroyed&&currentService===this&&
        preferences.getBoolean("wantedRunning",false)&&!preferences.getBoolean(RECOVERY_USER_STOPPED,false)

    /** Captures the projected application scope. IPv6 is BLOCKED by Android's default family
     * policy: no IPv6 address/DNS/route, no allowFamily(AF_INET6), no allowBypass. Never an escape. */
    private fun openDirectTun(config:CsqttDirectTunSession.Config?,requested:Long,scope:VpnOwnerIsolation.Scope):ParcelFileDescriptor {
        check(directLive(requested)&&!safeModeActive&&VpnService.prepare(this)==null) {"DIRECT_TUN_PERMISSION_OR_EPOCH"}
        val builder=Builder().setSession("MCRF · Direct Rust TUN · тест").setMtu(config?.mtu?:1500)
            .setBlocking(false).setMetered(behavior.metered)
            .addAddress(config?.address?:"172.19.0.1",32).addRoute("0.0.0.0",0)
        scope.apply(builder)
        config?.dns?.forEach(builder::addDnsServer)
        underlying?.let {builder.setUnderlyingNetworks(arrayOf(it))}
        val descriptor=builder.establish()?:throw IOException("Android не разрешил открыть прямой TUN.")
        if(!directLive(requested)||VpnService.prepare(this)!=null){descriptor.close();throw IOException("Прямой TUN: разрешение или запуск изменились.")}
        installedVpnScope=descriptor to scope
        val previous=tunnel;tunnel=descriptor
        previous?.close() // The new capture already exists; never publish a momentary direct fallback.
        return descriptor
    }
    private fun startDirectTun(template:JSONObject,candidate:JSONObject?,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long,admission:DirectAdmission?=null) {
        check(directLive(requested)) {"DIRECT_TUN_CANCELLED"}
        if(!vpn)throw IOException("Прямой TUN доступен только в режиме VPN.")
        if(safeModeActive||fixtureMode)throw IOException("Прямой TUN недоступен в безопасном режиме или другом fixture.")
        val selection=admission?.selection?:directSelection(template,candidate)
        CsqttDirectTunSupport.profileReason(selection.profile,vpn)?.let {throw IOException(it)}
        val effective=admission?.effective?:CsqttDirectTunSession.effectiveProfile(this,selection.profile)
        VkCredentialBackoffStore(this).requireConnectionAllowed(effective)
        // Authoritative full signed-module/ABI/capability check OFF UI and BEFORE retirement.
        val routing=if(applyRoutes)runtimeRoutes {RoutingStore(this).apply {load()}.snapshot()}else null
        val appScope=routing?.let(RoutingAppPolicies::systemScope)
        val included=if(routing?.optString("appMode")=="include")checkNotNull(appScope).forced else emptyList()
        // The native child shares our UID and owns its physical underlay.
        val directScope=captureVpnScope(included,appScope?.direct.orEmpty(),bypassOwnUid=true)
        val usage=admission?.takeUsage()?:EngineUpdateGate.acquireUsage(this)?:throw IOException("BS Engine обновляется; прямой TUN не запущен.")
        var adoptedLease=false
        try {
            val installation=admission?.installation?:BsDirectTunContract.resolve(this,effective)
            checkNotNull(installation)
            if(directScope.strict)BsDirectTunContract.requireOwnerIsolation(installation)
            check(directLive(requested)&&!safeModeActive&&directTunSelected()&&VpnService.prepare(this)==null) {"DIRECT_TUN_CANCELLED"}
            directTunTransition=RuntimeStart(JSONObject(template.toString()),candidate?.let {JSONObject(it.toString())},true,port,applyRoutes,requested)
            directRetirementSerial++
            closeCore(keepTun=true)
            check(directLive(requested)&&!safeModeActive&&VpnService.prepare(this)==null) {"DIRECT_TUN_CANCELLED"}
            engineUsage=usage;adoptedLease=true;vpnMode=true;directTunOwned=true;nativeVpnExclusion=true
            pendingWhitelists=true;localProxyPort=0;profilePolicyOwner=null
            routeBindingDetectionAllowed=false
            openDirectTun(null,requested,directScope) // Fail-closed capture BEFORE native DNS/credentials/connect.
            currentStart=RuntimeStart(JSONObject(template.toString()),candidate?.let {JSONObject(it.toString())},true,port,applyRoutes,requested)
            selectedId=selection.id
            val ownedGeneration=++generation
            connectionStep="connect";status="Подключение… · прямой TUN";foreground(status)
            ClientEvents.record(this,"Direct TUN","Пользовательский тест: применён системный список приложений VPN. IPv6 заблокирован; UID MCRF использует физическую сеть.")
            val native=CsqttDirectTunSession.start(this,effective,installation,ownedGeneration,
                {!directLive(requested)||safeModeActive||generation!=ownedGeneration||!directTunSelected()},
                {config->openDirectTun(config,requested,directScope)},
                {if(!worker.isShutdown)try {worker.execute {pauseDirectTun(requested,ownedGeneration)}}catch(_:java.util.concurrent.RejectedExecutionException){}},
                directOwnerScope=directScope.takeIf {it.strict})
            directTunSession=native
            directTunEffective=CarrierStreamRestartPolicy.startedProfile(effective,selection.profile)
            if(!directLive(requested)||!native.isAlive())throw IOException("Прямой TUN: готовность отозвана.")
            TrafficState.reset(selection.name)
            TrafficState.connection(selection.name,true,selection.profile.optString("server"),-1)
            status="VPN подключён · прямой TUN";foreground(status)
            refreshBsPowerLease()
            val sample=object:Runnable {
                override fun run() {
                    if(!directLive(requested)||generation!=ownedGeneration||directTunSession!==native)return
                    if(!native.isAlive()) {
                        if(!worker.isShutdown)try{worker.execute {pauseDirectTun(requested,ownedGeneration)}}catch(_:java.util.concurrent.RejectedExecutionException){}
                        return
                    }
                    val counters=native.counters()
                    TrafficState.update(counters[0],counters[1],SystemClock.elapsedRealtime())
                    accountServerUsage(ownedGeneration, selection.id, counters[0], counters[1])
                    TrafficState.connection(selection.name,true,selection.profile.optString("server"),counters[2].toInt().coerceIn(-1,TurnStreams.MAX))
                    foreground(status);main.postDelayed(this,2000)
                }
            }
            telemetry=sample;main.post(sample)
        }finally{directTunTransition=null;if(!adoptedLease)OptionalCoreRetirement.drain(usage)}
    }
    /** Fail closed without fallback, auto-selection or implicit retry. Master TUN remains open. */
    private fun pauseDirectTun(requested:Long,ownedGeneration:Long) {
        if(!directLive(requested)||generation!=ownedGeneration||!directTunOwned)return
        val diagnostic=directTunSession?.failureDiagnostic()
            ?:CsqttDirectTunDiagnostic.Failure(CsqttDirectTunDiagnostic.Phase.ACTIVE,CsqttDirectTunDiagnostic.Cause.PROCESS_EXIT)
        LastConnectionFailure.remember(diagnostic.detail)
        ClientEvents.record(this,"Direct TUN",diagnostic.code)
        val retained=currentStart
        closeCore(keepTun=true)
        if(!directLive(requested))return
        directTunOwned=true;currentStart=retained
        status="VPN приостановлен · прямой TUN"
        ClientEvents.record(this,"Direct TUN","Native-путь потерян. TUN удерживается, прямого fallback и автоматического перезапуска нет.")
        foreground(status)
    }
    /** Main only schedules; every authoritative check and retirement runs on the service worker. */
    private fun queueDirectTunChange() {
        val session=currentStart?:directTunTransition?:return
        if(!directLive(session.requested)||!session.vpn)return
        if(!CsqttDirectTunPolicy.reconfigure(directTunSelected(),directTunOwned||directTunTransition!=null,
                directTunBsContext))return
        directTunChangeTimer?.let(main::removeCallbacks)
        val requested=epoch
        val task=Runnable {
            directTunChangeTimer=null
            if(worker.isShutdown)return@Runnable
            try{worker.execute {
                if(!directLive(requested)||safeModeActive)return@execute
                val liveSession=currentStart?.takeIf {it.requested==requested}?:return@execute
                val enabled=directTunSelected()
                if(enabled==directTunOwned&&(!enabled||directTunSession?.isAlive()==true))return@execute
                // startDirectTun verifies the exact effective profile under a usage lease before
                // crossing retirementSerial. No duplicate module-hash probe; OFF needs no capability.
                val retirementBefore=directRetirementSerial
                val previousStatus=status
                try {
                    if(enabled)startDirectTun(liveSession.template,liveSession.candidate,true,liveSession.port,liveSession.applyRoutes,requested)
                    else {
                        nativeVpnExclusion=false
                        startSource(liveSession.template,liveSession.candidate,true,liveSession.port,liveSession.applyRoutes,requested,true,
                            if(autoDisabled)selectedOutbound.takeIf {it.isNotEmpty()}else null)
                        val bs=liveSession.template.optJSONObject("mcrf_bs")
                        if(autoAllowed()&&bs!=null&&liveSession.candidate!=null)
                            scheduleHealth(liveSession.template,BsPlan.candidates(bs),liveSession.candidate,bs.getInt("interval"),true,liveSession.port,liveSession.applyRoutes,requested)
                    }
                }catch(error:Exception){
                    val diagnostic=CsqttDirectTunDiagnostic.failure(error,CsqttDirectTunDiagnostic.Phase.SWITCH)
                    ClientEvents.record(this,"Direct TUN",diagnostic.code)
                    if(enabled&&directRetirementSerial==retirementBefore) {
                        status=previousStatus;foreground(status)
                        LastConnectionFailure.remember("${diagnostic.detail} Текущее соединение не изменено.")
                        return@execute
                    }
                    val retained=currentStart?:liveSession
                    closeCore(keepTun=true)
                    if(directLive(requested)) {
                        directTunOwned=enabled;currentStart=retained
                        status="VPN приостановлен · прямой TUN";foreground(status)
                        LastConnectionFailure.remember("${diagnostic.detail} Трафик приостановлен.")
                    }
                }
            }}catch(_:java.util.concurrent.RejectedExecutionException){}
        }
        directTunChangeTimer=task;main.postDelayed(task,350)
    }
    private fun startSource(template:JSONObject,candidate:JSONObject?,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long,keepTraffic:Boolean,frozenOutbound:String?=null) {
        fixtureBsNativePhysical?.let { fixture ->
            check(BuildConfig.DEBUG&&fixtureMode&&candidate==null&&vpn&&!applyRoutes&&!keepTraffic&&frozenOutbound==null&&
                template===fixture.source&&core==null&&tunnel==null&&BsNativeContract.advertised(this,fixture.profile)) {
                "BS_PHYSICAL_FIXTURE_START_SCOPE"
            }
            startNativeBs(template,null,port,false,requested,false,fixture.profile)
            return
        }
        if(directTunRequested(vpn)){startDirectTun(template,candidate,vpn,port,applyRoutes,requested);return}
        val bsProfile=candidate?.optJSONObject("profile")?:template.optJSONObject("mcrf_carrier")
        if(autonomousDispatcherChoice!=null&&bsProfile!=null)require(BsNativeContract.routerTransport(this,bsProfile,true)){
            "Для восстановления смешанного подключения нужен автономный транспорт."
        }
        if(!fixtureMode&&bsProfile!=null&&BsNativeContract.connectionAvailable(this,bsProfile,vpn)){
            val routing=if(applyRoutes)Connections(this).apply {load()}.let {catalogue->
                val state=RoutingConnectionBinding.prepare(this,runtimeRoutes {RoutingStore(this).apply {load()}.snapshot()},
                    catalogue.entries(),true,catalogue.probes())
                state to RouteServerPlan(state,catalogue.entries())
            }else null
            val mixed=routing?.second?.requiresProtocolDispatcher==true
            if(mixed&&autonomousDispatcherChoice==null&&ClientPrimarySelection.selected(this)==null){
                require(vpn&&candidate==null&&!preferences.getBoolean("automatic",false)&&
                    !BsNativeContract.allowsVersionDetection(bsProfile)){"NATIVE_ROUTER_MIXED_MANUAL_VPN_REQUIRED"}
                require(BsNativeContract.routerTransport(this,bsProfile,true)){"NATIVE_ROUTER_MIXED_TRANSPORT_UNAVAILABLE"}
                startNativeCarrierRouter(template,null,port,applyRoutes,requested,keepTraffic,bsProfile,vpn,mixedRoutes=true)
                return
            }
            if(mixed||autonomousDispatcherChoice!=null){
                require(BsNativeContract.routerTransport(this,bsProfile,true)){
                    "Для смешанных правил нужен автономный транспорт."
                }
                val preferred=autonomousDispatcherChoice?:ClientPrimarySelection.requireSelected(this)
                val steerRules=if(preferred=="steer"&&mixed){
                    ConnectionStartPolicy.requireMixedSteerVpn(vpn,candidate!=null)
                    val (state,plan)=requireNotNull(routing)
                    NativeRoutePreparation.captureSteerRules(this,state,plan.entries,template)
                }else null
                EngineUpdatePrimary.installation(this,"sing-box")
                pendingWhitelists=candidate!=null||sourceWhitelists(template)
                startSourceWithCore(template,candidate,vpn,port,applyRoutes,requested,keepTraffic,frozenOutbound,"singbox",
                    nativePreparation=steerRules,requireAutonomousMain=true)
                autonomousDispatcherChoice=preferred
                sessionPrimaryChoice="singbox"
                return
            }
            val effective=if(BsNativeContract.allowsVersionDetection(bsProfile)){
                val target=ConnectTiming.measure(this,"bs_version_detect") {
                    CsqttAutoRuntime.resolveForRouter(this,bsProfile,java.util.function.BooleanSupplier {
                        requested!=epoch||destroyed||currentService!==this
                    })
                }
                CsqttAutoDetection.profile(bsProfile,target)
            }else bsProfile
            if(vpn&&BsNativeContract.advertised(this,effective))
                startNativeBs(template,candidate,port,applyRoutes,requested,keepTraffic,effective)
            else startNativeCarrierRouter(template,candidate,port,applyRoutes,requested,keepTraffic,effective,vpn)
            return
        }
        pendingWhitelists=candidate!=null||sourceWhitelists(template)
        if(candidate==null&&bsProfile==null){
            if(startStandaloneOptional(template,vpn,port,applyRoutes,requested,keepTraffic))return
            val entry=standalonePacketEntry(template,vpn,applyRoutes)
            val packet=if(rebuildingForNetwork)sessionPrimaryChoice==WgAddonPolicy.KIND
                else entry!=null&&WgAddonPolicy.prefersStandalone(this)
            if(entry!=null&&packet){
                if(requested!=epoch||destroyed||currentService!==this)throw IOException("Подключение отменено")
                closeCore(keepTraffic)
                vpnMode=true
                connectionStep="prepare"
                profilePolicyOwner=ProfilePolicyRuntime.active(this)?.takeIf {it.configuration!=null}?.id
                subscriptionRoutingIds=SubscriptionRouting.sourceIds(template,null,listOf(entry))
                ConnectTiming.measure(this,"wg_prepare_start"){
                    startCompactWireGuard(template,entry,port,requested)
                }
                return
            }
        }
        require(!rebuildingForNetwork||sessionPrimaryChoice!=WgAddonPolicy.KIND){"RECOVERY_PACKET_PROFILE_CHANGED"}
        val initial=if(fixtureMode)"singbox"else sessionPrimaryChoice?.takeIf {it=="singbox"||it=="steer"}?:ClientPrimarySelection.effectiveWireGuardChoice(this,
            ClientPrimarySelection.requireSelected(this),WgAddonPolicy.usesWireGuard(template))
        ClientEvents.record(this,"Движки","Запуск · MCRF ${BuildConfig.VERSION_NAME}; API ${android.os.Build.VERSION.SDK_INT}; основной=$initial; режим=${if(vpn)"VPN"else"прокси"}; маршруты=$applyRoutes; выбор=${if(candidate!=null)"авто"else"один профиль"}")
        try {
            startSourceWithCore(template,candidate,vpn,port,applyRoutes,requested,keepTraffic,frozenOutbound,initial)
            sessionPrimaryChoice=initial
        }catch(error:Exception) {
            // Steer resolves the selected catalogue entry; never substitute it for a raw/diagnostic profile.
            val catalogueFallback=networkRecovery==null&&PrimaryFallbackPolicy.canRetryFailure(error)&&applyRoutes&&candidate==null&&frozenOutbound==null&&
                preferences.getBoolean("catalogueProfile",false)&&!preferences.getBoolean("automatic",false)&&
                !template.has("mcrf_carrier")&&!template.has("mcrf_bs")
            val alternate=PrimaryFallbackPolicy.alternate(initial,preferences.getBoolean(PrimaryFallbackPolicy.KEY,false)&&catalogueFallback,
                WgAddonPolicy.usesWireGuard(template),fixtureMode,requested!=epoch||destroyed||currentService!==this,fallbackAttempted)
                ?:throw error
            // Verify presence and identity; never download, change preference, or weaken the profile.
            try {EngineUpdatePrimary.installation(this,EngineUpdatePrimary.kindForChoice(alternate))}
            catch(_:Exception){throw error}
            fallbackAttempted=true
            val label=if(alternate=="singbox")"sing-box"else"Steer"
            PrimaryFallbackPolicy.handover(
                retire={closeCore(keepTun=vpn)},
                cancelled={requested!=epoch||destroyed||currentService!==this},
                start={
                    ClientEvents.record(this,"Движки","Основной движок не подключился; пробуем запасной: $label")
                    status="Пробуем запасной · $label";foreground(status)
                    startSourceWithCore(template,candidate,vpn,port,applyRoutes,requested,vpn,frozenOutbound,alternate)
                })
            sessionPrimaryChoice=alternate
            status+=" · запасной $label";foreground(status)
        }
    }
    private class BsRouterWorker(val transport:NativeCarrier,val egress:BsNativeRoutingSnapshot.Egress,
                                 val physical:android.net.Network,val pin:String)
    private fun startNativeCarrierRouter(template:JSONObject,candidate:JSONObject?,port:Int,applyRoutes:Boolean,requested:Long,
                                         keepTraffic:Boolean,raw:JSONObject,vpn:Boolean=true,mixedRoutes:Boolean=false){
        fun cancelled()=requested!=epoch||destroyed||currentService!==this
        val physical=checkNotNull(DirectAccessCheck.physical(this)){"Нет физической сети для БС."}
        val source=candidate?.optJSONObject("source")?:template
        val tag=source.getJSONObject("mcrf_carrier").getString("outbound_tag")
        val id=candidate?.optString("id")?.takeIf(String::isNotBlank)?:Connections(this).apply {load()}.entries()
            .singleOrNull {ServerImport.tag(it)==tag}?.id?:throw IOException("БС: выбранный профиль отсутствует.")
        val lease=EngineUpdateGate.acquireUsage(this)?:throw IOException("Движок обновляется. Повтори подключение после установки.")
        lease.use {
            val pin=if(mixedRoutes)BsNativeContract.requireBundledRouter(this)else BsNativeContract.routerPin(this)
            BsNativeContract.routerInstallation(this,pin)
            ProbeCoordinator.awaitQuiescent(this)
            check(!cancelled()){"Подключение отменено"}
            val transport=ConnectTiming.measure(this,"bs_named_carrier_start"){
                NativeCarrier.startForBsRouter(this,raw,physical,java.util.function.BooleanSupplier(::cancelled))
            }
            try {
                val egress=BsNativeRoutingSnapshot.Egress(id,transport.port,transport.username,transport.password)
                startNativeBs(template,candidate,port,applyRoutes,requested,keepTraffic,raw,BsRouterWorker(transport,egress,physical,pin),vpn,mixedRoutes=mixedRoutes)
            }finally{
                if(carrier!==transport)OptionalCoreRetirement.drain(transport)
            }
        }
    }
    private class OwnedOptionalRouterTransport(val entry:ServerImport.Server,val worker:OptionalCoreProcess,
                                                val physical:android.net.Network,val routerPin:String,
                                                val routing:JSONObject,val policyOwner:String?,val storageGeneration:String) {
        val egress=BsNativeRoutingSnapshot.Egress(entry.id,worker.port,worker.username,worker.password)
    }

    private fun startStandaloneOptional(template:JSONObject,vpn:Boolean,port:Int,applyRoutes:Boolean,
                                        requested:Long,keepTraffic:Boolean):Boolean {
        val recovering=rebuildingForNetwork&&sessionPrimaryChoice==BsNativeContract.CHOICE
        fun unavailable():Boolean {
            check(!recovering){"NATIVE_ROUTER_STANDALONE_RECOVERY_CHANGED"}
            return false
        }
        if(fixtureMode||!preferences.getBoolean("catalogueProfile",false)||preferences.getBoolean("automatic",false))return unavailable()
        if(!recovering&&ClientPrimarySelection.selected(this)!=null)return false
        val tag=template.optJSONObject("route")?.optString("final").orEmpty()
        val entry=Connections(this).apply {load()}.entries().singleOrNull {ServerImport.tag(it)==tag}?:return unavailable()
        if(!StandaloneRouterSelection.candidate(entry))return unavailable()
        val policy=ProfilePolicyRuntime.active(this)?.takeIf {it.configuration!=null}
        val storage=ProfileStore.generation(this)
        val state=runtimeRoutes(policy) {RoutingStore(this).apply {load()}.snapshot()}
        val request=StandaloneRouterSelection.request(this,entry,template,state)?:return unavailable()
        val strictIngress=vpn&&vpnOwnerIsolationRequired()
        require(vpn||BsNativeContract.proxyHint()){"NATIVE_ROUTER_STANDALONE_PROXY_UNAVAILABLE"}
        val pin=BsNativeContract.requireBundledRouter(this)
        val physical=checkNotNull(DirectAccessCheck.physical(this)){"NATIVE_ROUTER_STANDALONE_PHYSICAL"}
        fun cancelled()=requested!=epoch||destroyed||currentService!==this||DirectAccessCheck.physical(this)!=physical
        val lease=EngineUpdateGate.acquireUsage(this)?:throw IOException("NATIVE_ROUTER_STANDALONE_UPDATE_BUSY")
        lease.use {
            check(!cancelled()){"NATIVE_ROUTER_STANDALONE_CANCELLED"}
            if(EnginePackages.missing(this,setOf(request.kind)).isNotEmpty())throw ClientOptionalCoreRequired(request.kind)
            if(strictIngress)OptionalCoreProcess.requireStrictIngress(this,request.kind)
            if(request.kind!=CompactProtocolPolicy.KIND)StandaloneOptionalRouter.requirePhysical(this,request)
            ProbeCoordinator.awaitQuiescent(this)
            val worker=OptionalCoreProcess.start(this,request.copy(physicalNetworkHandle=physical.networkHandle),
                java.util.function.BooleanSupplier(::cancelled),vpn,false,strictIngress=strictIngress)
            val transport=OwnedOptionalRouterTransport(entry,worker,physical,pin,state,policy?.id,storage)
            try {
                startNativeBs(template,null,port,applyRoutes,requested,keepTraffic,null,vpn=vpn,ordinary=transport,
                    mixedRoutes=applyRoutes&&RouteServerPlan.activeIds(state).isNotEmpty())
            }finally {
                if(standaloneProtocol!==transport)OptionalCoreRetirement.drain(worker)
            }
        }
        return true
    }

    /** Prepare and admit in the separate validation worker before retiring the
     * current session. The normal primary worker remains the lifecycle owner. */
    private fun startNativeBs(template:JSONObject,candidate:JSONObject?,port:Int,applyRoutes:Boolean,requested:Long,keepTraffic:Boolean,raw:JSONObject?,
                              namedCarrier:BsRouterWorker?=null,vpn:Boolean=true,ordinary:OwnedOptionalRouterTransport?=null,
                              mixedRoutes:Boolean=false){
        fun cancelled()=requested!=epoch||destroyed||currentService!==this
        require(ordinary==null||namedCarrier==null&&raw==null&&candidate==null)
        require(vpn||(namedCarrier!=null||ordinary!=null)&&BsNativeContract.proxyHint())
        check(!cancelled()){"Подключение отменено"}
        val physical=checkNotNull(DirectAccessCheck.physical(this)){"Нет физической сети для БС."}
        require(namedCarrier==null||namedCarrier.physical==physical){"Физическая сеть изменилась во время запуска БС."}
        require(ordinary==null||ordinary.physical==physical&&ordinary.worker.isAlive()){"NATIVE_ROUTER_STANDALONE_RETIRED"}
        val manager=getSystemService(ConnectivityManager::class.java)
        val caps=checkNotNull(manager.getNetworkCapabilities(physical))
        val source=candidate?.optJSONObject("source")?:template
        val tag=ordinary?.entry?.let(ServerImport::tag)?:source.optJSONObject("mcrf_carrier")?.optString("outbound_tag").orEmpty()
        require(tag.isNotBlank()){"БС: не указан выход выбранного подключения."}
        val ownedFixture=fixtureBsNativePhysical
        val profile=ownedFixture?.profile?:if(ordinary!=null)null else if(namedCarrier==null){
            val effective=CarrierStreamDefaults.apply(VkRelayRuntime.withManualSupplements(requireNotNull(raw),VkRelayAccount(AndroidDeployStore(this))),CarrierStreamDefaults.load(this))
            checkNotNull(Csqtt25Profile.fromJson("bs-native",effective).carrier)
        }else namedCarrier.transport.streamProfile()?:JSONObject(requireNotNull(raw).toString())
        val hasTurnStreams=profile?.optString("type")?.let(SwgProfile.TYPES::contains)==true
        if(hasTurnStreams&&ownedFixture==null)VkCredentialBackoffStore(this).requireConnectionAllowed(requireNotNull(profile))
        profile?.let {NativeCarrier.requireOptions(this,it)}
        val pin=ownedFixture?.moduleSha256?:ordinary?.routerPin?:namedCarrier?.pin?:BsNativeContract.pin(this)
        val mainEgress=ordinary?.egress?:namedCarrier?.egress
        val routing=if(applyRoutes)ordinary?.routing?:runtimeRoutes {RoutingStore(this).apply {load()}.snapshot()}else null
        val routingEntry=Connections(this).apply {load()}.entries().firstOrNull {it.id==candidate?.optString("id")||ServerImport.tag(it)==tag}
        val serverRoutingMode=if(ownedFixture!=null)"mcrf" else routingEntry?.routingRules?:
            if(mainEgress!=null)"mcrf" else if((routing==null||CsqttRoutingPrecedence.usesServer(this,routing))&&!CsqttRoutingPrecedence.hasSourceRules(source))"server"else"mcrf_first"
        fun requireOptionalCurrent() {
            if(ordinary!=null)check(ProfileStore.generation(this)==ordinary.storageGeneration&&
                ProfilePolicyRuntime.active(this)?.takeIf {it.configuration!=null}?.id==ordinary.policyOwner&&
                ordinary.worker.isAlive()){"NATIVE_ROUTER_STANDALONE_POLICY_CHANGED"}
        }
        requireOptionalCurrent()
        var routeWorkers:RouteServerSession?=null
        var mixedWorkers:MixedNativeRouteSession?=null
        var routeLease:AutoCloseable?=null
        var routesAdopted=false
        val admitMixed:((MixedNativeRoutePlan,JSONObject,Set<String>)->List<BsNativeRoutingSnapshot.Egress>)?=
            if(!mixedRoutes)null else {plan,compiled,bound->
                routeLease=EngineUpdateGate.acquireUsage(this)?:throw IOException("NATIVE_ROUTER_MIXED_UPDATE_BUSY")
                BsNativeContract.routerInstallation(this,pin)
                val cancelledSupplier=java.util.function.BooleanSupplier(::cancelled)
                plan.prepare(this,compiled,physical,bound,cancelledSupplier,strictIngress=vpn&&vpnOwnerIsolationRequired()).start(this,cancelledSupplier)
                    .also {mixedWorkers=it}.bindings()
            }
        try {
            val preparation=ConnectTiming.measure(this,"bs_profile_prepare"){
                BsNativeProfilePreparation.capture(this,template,candidate,tag,port,applyRoutes,physical,routing,profile,
                    listOfNotNull(mainEgress),mainEgress?.serverId,admitRouteServers={entries->
                        routeLease=EngineUpdateGate.acquireUsage(this)?:throw IOException("Движок обновляется. Повтори подключение после установки.")
                        BsNativeContract.routerInstallation(this,pin)
                        RouteServerSession.startForBsRouter(this,entries,physical,java.util.function.BooleanSupplier(::cancelled))
                            .also {routeWorkers=it}.bsBindings()
                    },vpn=vpn,standaloneMain=ordinary?.entry,admitMixedRoutes=admitMixed,ownedFixture=ownedFixture,serverRoutingMode=serverRoutingMode,cancelled=::cancelled)
            }
            val kind=when {caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)->"wifi";caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)->"cellular";caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)->"ethernet";else->"other"}
            val wifi=caps.transportInfo as? android.net.wifi.WifiInfo
            val ssid=wifi?.ssid?.takeUnless {it==android.net.wifi.WifiManager.UNKNOWN_SSID}?.removeSurrounding("\"").orEmpty()
            val underlay=JSONObject().put("kind",kind).put("expensive",!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                .put("constrained",false).put("ssid",ssid).put("bssid",wifi?.bssid.orEmpty())
            val behavior=if(ownedFixture==null)ClientBehaviorStore.load(this)else this.behavior
            val payload=JSONObject().put("schema",1).put("bundle",preparation.captured.bundle()).put("profile",if(mainEgress==null)requireNotNull(profile) else JSONObject.NULL)
                .put("physical",physical.networkHandle).put("underlay",underlay).put("metered",behavior.metered).put("tunnelTag",tag)
                .put("serverRoutingMode",serverRoutingMode)
            val replacingSession=core!=null||tunnel!=null
            if(replacingSession)ConnectTiming.measure(this,"bs_admission"){
                ClientPrimaryWorker.connectBs(this,generation.coerceAtLeast(1),null,pin,true).use {it.validateBs(payload)}
            }
            preparation.requireCurrent(this)
            requireOptionalCurrent()
            mixedWorkers?.requireCurrent(this)
            // connectBs also verifies the expected digest. On handover, check it
            // here as well so a module change cannot retire the existing VPN.
            check(!cancelled()&&DirectAccessCheck.physical(this)==physical&&(!replacingSession||BsNativeContract.workerPin(this,pin)==pin)){
                "Сеть или модуль БС изменились во время подготовки."
            }
            closeCore(keepTraffic&&vpn);vpnMode=vpn
            if(namedCarrier!=null)carrier=namedCarrier.transport
            if(ordinary!=null)standaloneProtocol=ordinary
            routeServerSession=routeWorkers;mixedRouteSession=mixedWorkers;routesAdopted=true
            check(!cancelled()){"Подключение отменено"}
            engineUsage=EngineUpdateGate.acquireUsage(this)?:throw IOException("Движок обновляется. Повтори подключение после установки.")
            pendingWhitelists=candidate!=null||sourceWhitelists(template)
            profilePolicyOwner=if(ownedFixture!=null)null else if(ordinary!=null)ordinary.policyOwner else ProfilePolicyRuntime.active(this)?.takeIf {it.configuration!=null}?.id
            val catalogue=if(ownedFixture==null)Connections(this).apply {load()}.entries()else emptyList()
            subscriptionRoutingIds=SubscriptionRouting.sourceIds(template,candidate,catalogue)
            routeBindingDetectionAllowed=routing==null||!RoutingConnectionBinding.enabled(routing)||RoutingConnectionBinding.networkDriven(routing,true)
            runningConfig=preparation.compiled();runningDirectDnsIpv4=null;localProxyPort=preparation.localPort
            nativeVpnExclusion=false;target=tag;selectedOutbound=tag;policySelector=false
            names.clear();ids.clear()
            catalogue.forEach {entry->names[ServerImport.tag(entry)]=entry.name;ids[ServerImport.tag(entry)]=entry.id}
            selectedId=candidate?.optString("id")?:ids[tag].orEmpty()
            val runGeneration=generation.coerceAtLeast(1)
            val awaitProbeRetirement=ProbeCoordinator.requestQuiescence(this)
            val platform=forRun(runGeneration,{cancelled()||generation!=runGeneration},{plan->
                requireOptionalCurrent()
                mixedWorkers?.requireCurrent(this)
                check(vpn&&!cancelled()&&generation==runGeneration){"Подключение отменено"};openPrimaryTun(plan,false)
            }){tx,rx->if(!cancelled()&&generation==runGeneration){TrafficState.update(tx,rx,SystemClock.elapsedRealtime());accountPolicyCounters(runGeneration,tx,rx)}}
            val server=try {ConnectTiming.measure(this,"bs_worker_bind"){ClientPrimaryWorker.connectBs(this,runGeneration,platform,pin,onDeath={
                if(!cancelled()&&runGeneration==generation){(platform as? AutoCloseable)?.close();requestEngineRecovery(runGeneration,requested)}
            }).also {core=it;runningPrimaryIdentity=pin}}}catch(pending:ClientPrimaryWorker.CleanupPending){core=pending.worker;throw pending}
            ConnectTiming.measure(this,"bs_probe_quiesce_wait",awaitProbeRetirement)
            preparation.requireCurrent(this);requireOptionalCurrent();ConnectTiming.measure(this,"bs_worker_validate"){server.validateBs(payload)}
            if(!cancelled())connectionStep="connect"
            try{ConnectTiming.measure(this,"bs_carrier_ready"){server.startBs()}}catch(error:Exception){
                if(hasTurnStreams&&ownedFixture==null)VkCredentialBackoffStore(this).requireAllowed()
                throw error
            }
            if(ordinary!=null){
                val delay=server.bsStartupHealth(tag,listOf(healthUrl),5000)
                if(delay<0||!ordinary.worker.isAlive()||DirectAccessCheck.physical(this)!=ordinary.physical)
                    throw ordinary.worker.transferFailure("проверка передачи через протокольный выход")
            }
            // A selected server is running once the owned transport has authenticated
            // and adopted TUN. URL reachability is evidence for automatic selection.
            if(candidate!=null){
                val deadline=SystemClock.elapsedRealtime()+20_000
                var measuredDelay=-1L
                var cloudflareControl="https://1.1.1.1/cdn-cgi/trace"
                while(!cancelled()&&runGeneration==generation&&SystemClock.elapsedRealtime()<deadline){
                    val remaining=(deadline-SystemClock.elapsedRealtime()).coerceAtMost(5000).toInt();if(remaining<=0)break
                    measuredDelay=ConnectTiming.measure(this,"bs_control_check"){
                        AdaptivePolicyRuntime.measured(this,BatteryCostReport.Action.PROBE){
                            server.bsStartupHealth(tag,listOf(cloudflareControl,healthUrl).distinct(),remaining)
                        }
                    }
                    if(measuredDelay>=0)break
                    cloudflareControl="https://www.cloudflare.com/cdn-cgi/trace"
                    Thread.sleep(200)
                }
                check(measuredDelay>=0){"БС не подтвердил передачу данных через выбранный сервер."}
                record(candidate.getString("id"),measuredDelay)
            }
            check(!cancelled()&&runGeneration==generation){"Подключение отменено"}
            sessionPrimaryChoice=BsNativeContract.CHOICE
            currentStart=RuntimeStart(JSONObject(template.toString()),candidate?.let {JSONObject(it.toString())},vpn,port,applyRoutes,requested)
            streamRuntime=StreamRuntime(if(hasTurnStreams)StreamConsumer(requireNotNull(profile))else null,(routeWorkers?.streamProfiles().orEmpty()+mixedWorkers?.streamProfiles().orEmpty()).values.map {StreamConsumer(it)},
                preparation.routing(),catalogue,BsNativeContract.CHOICE,profilePolicyOwner,null,null,null)
            status=if(vpn)"VPN подключён"+(if(localProxyPort>0)" · прокси 127.0.0.1:$localProxyPort"else"")
                else if(localProxyPort>0)"Прокси 127.0.0.1:$localProxyPort" else "Прокси подключён · пользовательские входы"
            if(ownedFixture==null){
                ClientBehaviorUi.applyRecents(this);startTelemetry(catalogue);foreground(status);rememberVpnSession(requireNotNull(currentStart))
            }else foreground(status)
            ClientEvents.record(this,"Движки",if(namedCarrier==null)"БС подключён самостоятельно: маршруты, DNS и локальный прокси обрабатываются в том же движке."
                else "Транспорт подключён через маршрутизацию БС.")
        }finally{
            try {if(!routesAdopted)listOfNotNull(mixedWorkers,routeWorkers).takeIf {it.isNotEmpty()}?.let {
                OptionalCoreRetirement.drain(OptionalCoreRetirement(it))
            }}
            finally {routeLease?.close()}
        }
    }
    private fun startSourceWithCore(template:JSONObject,candidate:JSONObject?,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long,keepTraffic:Boolean,frozenOutbound:String?,primaryChoice:String,
                                    nativePreparation:NativeRoutePreparation?=null,nativeEntry:ServerImport.Server?=null,nativeProof:SteerProbeDelays?=null,
                                    retainedStreams:StreamRuntime?=null,requireAutonomousMain:Boolean=false) {
        fun cancelled()=requested!=epoch||retainedStreams!=null&&!streamChangeOwned(requested,true)
        if(!fixtureMode)profilePolicyOwner=if(retainedStreams!=null)retainedStreams.policyOwner else ProfilePolicyRuntime.active(this)?.takeIf {it.configuration!=null}?.id
        val routeOnlySteer=nativePreparation!=null&&nativeEntry==null
        val autonomousMain=requireAutonomousMain||retainedStreams?.autonomousMain==true
        require(nativeEntry==null||nativePreparation!=null){"NATIVE_ROUTE_START_CAPTURE"}
        if(nativePreparation!=null)require(!fixtureMode&&vpn&&applyRoutes&&candidate==null&&
            if(routeOnlySteer)primaryChoice=="singbox"&&autonomousMain
            else primaryChoice=="steer"&&nativePreparation.plan.bindings.any {it.id==nativeEntry!!.id}){"NATIVE_ROUTE_START_SCOPE"}
        if(fixtureP1OwnerHelperSha!=null){
            val scopedSteer=template.keys().asSequence().toSet()==setOf("mcrf_steer_fixture")
            if(scopedSteer)ClientSteerFixture.requireP1ProxySnapshot(
                template.getJSONObject("mcrf_steer_fixture").getJSONObject("snapshot"),healthUrl.substringAfterLast('/'))
            check(BuildConfig.DEBUG&&fixtureMode&&vpn&&candidate==null&&!applyRoutes&&!keepTraffic&&frozenOutbound==null&&primaryChoice=="singbox"&&
                (scopedSteer||template.keys().asSequence().toSet()==setOf("outbounds","dns","route"))) { "P1_OWNER_SCOPE_START_CONFLICT" }
        }
        if(fixtureStrictOwner!=null){
            check(BuildConfig.DEBUG&&fixtureMode&&vpn&&candidate==null&&!applyRoutes&&!keepTraffic&&
                frozenOutbound==null&&primaryChoice=="singbox"&&nativePreparation==null&&
                template.keys().asSequence().toSet()==setOf("outbounds","dns","route")) { "P1_STRICT_OWNER_START_CONFLICT" }
        }
        if(cancelled()) throw IOException("Подключение отменено")
        val strictIngress=vpn&&vpnOwnerIsolationRequired()
        val coreSettings=if(fixtureMode)CoreTuningSettings()else CoreTuningStore(this).load()
        val optionalSettings=if(fixtureMode)null else OptionalCoreStore(this).load()
        if(strictIngress&&optionalSettings!=null&&(primaryChoice!="steer"||nativePreparation!=null)){
            val previewSource=JSONObject((candidate?.optJSONObject("source")?:template).toString()).apply {
                if(candidate?.has("source")==true)template.optJSONObject("dns")?.let {put("dns",JSONObject(it.toString()))}
                remove("mcrf_carrier");remove("mcrf_bs")
            }
            var preview=ProfileConfig.build(previewSource.toString(),vpn,port)
            if(applyRoutes){
                val routes=retainedStreams?.routing?.let {JSONObject(it.toString())}?:nativePreparation?.state()
                    ?:runtimeRoutes {RoutingStore(this).apply {load()}.snapshot()}
                val entries=retainedStreams?.entries?:nativePreparation?.entries()?:Connections(this).apply {load()}.entries()
                preview=RouteServerPlan(routes,entries).append(preview)
            }
            OptionalCoreSession.requireStrictIngress(this,OptionalCorePlan(preview,optionalSettings,coreSettings))
        }
        closeCore(keepTraffic&&vpn);vpnMode=vpn
        if(requested==epoch)connectionStep="prepare"
        subscriptionRoutingIds=if(fixtureMode)emptyList()else Connections(this).apply {load()}.let {SubscriptionRouting.sourceIds(template,candidate,it.entries())}
        if(requested!=epoch||destroyed||currentService!==this)throw IOException("Подключение отменено")
        if(retainedStreams!=null)check(streamChangeOwned(requested,true)){"Применение потоков отменено"}
        var capturedRouting:JSONObject?=null
        var capturedEntries=emptyList<ServerImport.Server>()
        var capturedMain:JSONObject?=null
        if(primaryChoice=="steer")steerProbeEpoch=requested
        if(BuildConfig.DEBUG&&fixtureMode&&template.has("mcrf_packet_fixture")) {
            require(vpn&&candidate==null&&!keepTraffic&&!applyRoutes)
            startPacketFixture(template,ClientPacketFixture.parse(template,healthUrl),port,requested)
            return
        }
        if(BuildConfig.DEBUG&&fixtureMode&&template.has("mcrf_steer_fixture")) {
            require(vpn&&candidate==null&&!keepTraffic) {"Steer fixture requires an isolated fresh VPN run"}
            val fixture=ClientSteerFixture.parse(template,healthUrl)
            startSteerPrepared(template,SteerPrepared(fixture.original,fixture.dialIpv4,"Owned Steer fixture","owned-steer-fixture",fixture.settingsJson,fixture.dialIpv4),
                port,applyRoutes,requested,false)
            return
        }
        // Only the existing debug-only, private-file fixture explicitly selects sing-box.
        // Production runs always require the user's stored choice; this is not a fallback.
        if(!fixtureMode&&primaryChoice=="steer"&&nativePreparation==null) {
            require(candidate==null&&!template.has("mcrf_carrier")&&!template.has("mcrf_bs")) {"Steer пока не поддерживает дополнительные транспорты и автовыбор БС."}
            require(!WgAddonPolicy.usesWireGuard(template)) {"Компактный WG: выбери один WG/AWG-профиль в режиме VPN, без автовыбора или JSON-профиля."}
            ConnectTiming.measure(this,"steer_prepare_start"){startSteer(template,vpn,port,applyRoutes,requested,keepTraffic)}
            return
        }
        engineUsage=EngineUpdateGate.acquireUsage(this)?:throw IOException("Движок обновляется. Завершите установку перед подключением.")
        if(nativeEntry!=null)prepareCarrierHandoverTun(vpn,requested)
        val source=JSONObject((candidate?.optJSONObject("source")?:template).toString()).apply {
            // Keep the current DNS policy while choosing the candidate's exact outbound.
            if(candidate?.has("source")==true)template.optJSONObject("dns")?.let {put("dns",JSONObject(it.toString()))}
        }
        val boundRouting=if(applyRoutes&&(nativePreparation==null||routeOnlySteer))(retainedStreams?.routing?.let {JSONObject(it.toString())}
            ?:nativePreparation?.state()?:runtimeRoutes {RoutingStore(this).apply{load()}.snapshot()}).takeIf{RoutingConnectionBinding.enabled(it)}else null
        routeBindingDetectionAllowed=boundRouting==null||RoutingConnectionBinding.networkDriven(boundRouting,true)
        val profile=retainedStreams?.main?.profile?.let {JSONObject(it.toString())}
            ?:if(candidate!=null)candidate.optJSONObject("profile")else source.optJSONObject("mcrf_carrier")
        val bridgeTag=source.optJSONObject("mcrf_carrier")?.optString("outbound_tag").orEmpty()
        source.remove("mcrf_carrier");source.remove("mcrf_bs")
        if(autonomousMain&&boundRouting==null)require(profile!=null&&bridgeTag.isNotEmpty()){
            "В смешанном подключении потерян основной автономный транспорт."
        }
        var mainCarrierPhysical:android.net.Network?=null
        if(frozenOutbound!=null)source.optJSONArray("outbounds")?.let {list->repeat(list.length()){i->val node=list.getJSONObject(i);if(node.optString("type") in setOf("urltest","selector")&&node.optJSONArray("outbounds")?.let {members->(0 until members.length()).any{members.getString(it)==frozenOutbound}}==true){node.put("type","selector").put("default",frozenOutbound);listOf("url","interval","tolerance").forEach(node::remove)}}}
        profile?.takeUnless{boundRouting!=null}?.let {
            if(autonomousMain){
                check(!fixtureMode&&primaryChoice=="singbox"&&BsNativeContract.routerTransport(this,it,true)){
                    "Автономный транспорт изменился во время подготовки."
                }
                mainCarrierPhysical=requireNotNull(DirectAccessCheck.physical(this)){"Для автономного транспорта нужна физическая сеть."}
            }else prepareCarrierHandoverTun(vpn,requested)
            if(requested==epoch)connectionStep="connect"
            val transport=ConnectTiming.measure(this,"carrier_start"){
                val physical=mainCarrierPhysical
                if(physical!=null)NativeCarrier.startForBsRouter(this,it,physical,java.util.function.BooleanSupplier {cancelled()},carrierTimeout)
                else if(primaryChoice=="singbox"&&!template.has("mcrf_bs")&&!fixtureMode)
                    NativeCarrier.startRouted(this,it,java.util.function.BooleanSupplier {cancelled()},generation.coerceAtLeast(1),carrierTimeout)
                else NativeCarrier.start(this,it,java.util.function.BooleanSupplier {cancelled()},carrierTimeout)
            };carrier=transport
            capturedMain=transport.streamProfile()
                ?:RouteServerSession.streamProfile(this,it).put(CarrierStreamRestartPolicy.UNVERIFIED,true)
            val outputs=source.getJSONArray("outbounds")
            val node=(0 until outputs.length()).map { outputs.getJSONObject(it) }.firstOrNull { it.optString("tag")==bridgeTag }
                ?: throw IOException("Missing carrier outbound")
            if(candidate!=null&&template.has("mcrf_bs")&&!fixtureMode&&mainCarrierPhysical==null){
                val board=CarrierSocksSwitch(candidate.getString("id"),transport);carrierSwitchboard=board
                capturedMain?.let {raw->streamBoardConsumers[candidate.getString("id")]=StreamConsumer(raw)}
                node.put("server_port",board.port).put("username",board.username).put("password",board.password)
            }else node.put("server_port",transport.port).put("username",transport.username).put("password",transport.password)
        }
        if(requested!=epoch) throw IOException("Подключение отменено")
        var prepared=ProfileConfig.build(source.toString(),vpn,port)
        if(!fixtureMode)LocalProxyConfig.apply(prepared,LocalProxySettings.value(this))
        target=prepared.getJSONObject("route").optString("final","")
        runningDirectDnsIpv4=null
        var routePlan:RouteServerPlan?=null
        if(applyRoutes){
            val routes=RoutingStore(this);ConnectTiming.measure(this,"routes_load"){routes.load()};val stored=retainedStreams?.routing?.let {JSONObject(it.toString())}?:nativePreparation?.state()?:boundRouting?:runtimeRoutes {routes.snapshot()}
            val catalogue=Connections(this);catalogue.load();val routeEntries=retainedStreams?.entries?:nativePreparation?.entries()?:catalogue.entries()
            val settings=if(nativePreparation!=null||retainedStreams!=null)stored else RoutingConnectionBinding.prepare(this,stored,routeEntries,profile!=null||template.has("mcrf_bs"),catalogue.probes())
            capturedRouting=JSONObject(settings.toString());capturedEntries=routeEntries.toList()
            routeBindingSelection=if(retainedStreams!=null)retainedStreams.bindingSelection
                else if(settings.has(RoutingConnectionBinding.RUNTIME))RouteBindingSelection(settings,routeEntries)else null
            val plan=RouteServerPlan(settings,routeEntries)
            routePlan=plan
            RouteServerSession.check(this,plan,java.util.function.BooleanSupplier{cancelled()})
            val directIpv4=DirectDnsNetwork.ipv4Only(this,underlying)
            prepared=ConnectTiming.measure(this,"routes_compile"){RouteRules.apply(prepared,settings,vpn,PresetAssets.resolve(this,settings),PresetAssets.ipRuleSetTags(this,settings),directIpv4,routeEntries)}
            if(plan.entries.any {it.carrier!=null}) {
                val session=RouteServerSession.start(this,prepared,plan,java.util.function.BooleanSupplier{cancelled()},
                    if(!fixtureMode&&(nativePreparation!=null||primaryChoice=="singbox"))generation.coerceAtLeast(1)else 0L,
                    allowAutonomousUnderlay=!fixtureMode&&(nativePreparation!=null||primaryChoice=="singbox"),
                    prepareLegacyUnderlay={prepareCarrierHandoverTun(vpn,requested)})
                routeServerSession=session;prepared=session.config
                val started=session.streamProfiles()
                capturedEntries=routeEntries.map {entry->
                    val actual=started[entry.id]
                    if(actual==null)entry else ServerImport.Server(entry.original,entry.name,entry.protocol,entry.host,entry.port,
                        entry.outbound,entry.endpoint,entry.configuration,actual,entry.profileDns.toList(),entry.whitelists)
                }
            }
            if(RoutingConnectionBinding.enabled(settings))target=RouteServerPlan.outbound(settings.getJSONObject(RoutingConnectionBinding.DEFAULT),prepared.getJSONObject("route").getString("final"))
            if(DnsConfig.appOwned(source)){runningDirectDnsIpv4=directIpv4;if(directIpv4)ClientEvents.record(this,"DNS","У физической сети нет IPv6-маршрута в интернет. Для однозначных прямых TCP-маршрутов включён быстрый отказ недоступного IPv6, позволяющий приложению попробовать IPv4. AAAA, IPv6 через VPN и защита TUN не отключены.")}
        }
        if(nativePreparation!=null)SteerRouteTuning.requireSupported(coreSettings)
        if(optionalSettings!=null){
            val plan=OptionalCorePlan(prepared,optionalSettings,coreSettings)
            plan.notes.forEach {ClientEvents.record(this,"Движки",it)}
            if(plan.requiresWorkers){
                val boundWorkers=vpn&&(autonomousMain||plan.entries().any {OptionalCoreProcess.advertisesPhysicalNetwork(this,it.request.kind)})
                require(!boundWorkers||nativeEntry==null&&(carrier==null||mainCarrierPhysical!=null)&&routeServerSession?.requiresUidBypass!=true){
                    "Для привязанных дополнительных ядер нужны транспорты с собственной физической сетью."
                }
                val legacyWorkers=vpn&&!boundWorkers
                val physical=if(boundWorkers)checkNotNull(underlying?:DirectAccessCheck.physical(this)){"Нет физической сети для дополнительного ядра."}else null
                if(legacyWorkers)prepareCarrierHandoverTun(vpn,requested)
                val native=ConnectTiming.measure(this,"optional_core_start"){
                    OptionalCoreSession.start(this,plan,java.util.function.BooleanSupplier{
                        requested!=epoch||physical!=null&&(underlying?:DirectAccessCheck.physical(this))!=physical
                    },vpn,legacyWorkers,physical?.networkHandle,strictIngress)
                }
                optionalSession=native;prepared=native.config
            }
        }
        if(!fixtureMode){
            prepared=CoreTuningConfig.apply(prepared,coreSettings)
            val tunStore=ClientTunStore(this);val tunSettings=tunStore.load()
            require(vpn||tunSettings.mixedEnabled||tunSettings.customListeners().isNotEmpty()){ "В режиме прокси включите локальный listener." }
            val configured=ClientTunConfig.apply(prepared,tunSettings,tunStore.auth(),port)
            prepared=configured.config;localProxyPort=configured.localPort?:0
            configured.notes.forEach {ClientEvents.record(this,"Маршруты",it)}
            if(!vpn&&tunSettings.httpProxy)ClientEvents.record(this,"Клиент","Системный HTTP-прокси Android применяется только в режиме VPN. Локальные входы работают независимо.")
        }else {
            fixtureStrictOwner?.lan?.let {prepared=it.configure(prepared,port)}
            localProxyPort=port
        }
        val poolProfiles=template.optJSONObject("mcrf_bs")?.optJSONArray("candidates")?.let {list->
            (0 until list.length()).mapNotNull {list.getJSONObject(it).optJSONObject("profile")}
        }?:emptyList()
        val mainFamily=CarrierAddressFamily.forPool(poolProfiles,profile?.let(EngineModules::carrierKind))
        val hasRouteServers=routePlan?.entries?.isNotEmpty()==true
        val selectedCompatibilityId=if(hasRouteServers){candidate?.optString("id")?:try {
            val catalogue=Connections(this);catalogue.load();catalogue.entries().firstOrNull {ServerImport.tag(it)==target}?.id
        }catch(_:Exception){null}}else null
        val mainCompatibility=selectedCompatibilityId?.let {preferences.getBoolean(ConnectionAddressFamily.key(it),false)}==true
        val familyPrepared=if(!hasRouteServers)CarrierAddressFamily.apply(prepared,mainFamily)else {
            val restricted=requireNotNull(routePlan).entries.filter {entry->
                CarrierAddressFamily.needsIpv4(entry.carrier?.let(EngineModules::carrierKind))||preferences.getBoolean(ConnectionAddressFamily.key(entry.id),false)
            }.mapTo(linkedSetOf()) {it.id}
            RouteServerAddressFamily.apply(prepared,target,CarrierAddressFamily.needsIpv4(mainFamily)||mainCompatibility,restricted)
        }
        if(familyPrepared!==prepared)ClientEvents.record(this,"DNS",if(hasRouteServers)
            "IPv4-совместимость применяется только к DNS-условиям соответствующего сервера. Общие ответы DNS, остальные серверы и IPv6 TUN автоматически не ограничиваются."
            else if(poolProfiles.isEmpty())CarrierAddressFamily.NOTE else CarrierAddressFamily.POOL_NOTE)
        prepared=familyPrepared
        retainedStreams?.selections?.forEach { (group,selected) ->
            val outputs=prepared.optJSONArray("outbounds")
            if(outputs!=null)repeat(outputs.length()) {index->val node=outputs.getJSONObject(index)
                if(node.optString("tag")==group&&node.optString("type")=="selector"&&
                    node.optJSONArray("outbounds")?.let {members->(0 until members.length()).any {members.getString(it)==selected}}==true)
                    node.put("default",selected)
            }
        }
        names.clear(); ids.clear()
        try{val catalogue=Connections(this);catalogue.load();catalogue.entries().forEach {
            for(tag in listOf(ServerImport.tag(it),RouteServerPlan.tag(it.id))){names[tag]=it.name;ids[tag]=it.id}
        }}catch(_:Exception){}
        if(nativeEntry==null)selectedId = candidate?.getString("id") ?: ids[target].orEmpty()
        policySelector=false
        if(!fixtureMode&&autoAllowed()&&profile==null&&!hasRouteServers&&routeBindingSelection==null&&
            adaptivePolicy.managed()) {
            val outputs=prepared.optJSONArray("outbounds")
            val group=outputs?.let {nodes->(0 until nodes.length()).map {nodes.getJSONObject(it)}.firstOrNull {it.optString("tag")==target&&it.optString("type")=="urltest"}}
            group?.optJSONArray("outbounds")?.let {members->
                val tags=(0 until members.length()).map {members.getString(it)}
                if(tags.isNotEmpty()&&tags.all(ids::containsKey)) {
                    val chosen=tags.first()
                    group.put("type","selector").put("default",chosen).put("interrupt_exist_connections",false)
                    listOf("url","interval","tolerance","idle_timeout").forEach(group::remove)
                    selectedId=ids[chosen].orEmpty();policySelector=true
                }
            }
        }
        if (!hasRouteServers && profile == null && selectedId.isNotEmpty() && preferences.getBoolean(ConnectionAddressFamily.key(selectedId), false)) {
            prepared = ConnectionAddressFamily.apply(prepared, true)
            ClientEvents.record(this, "DNS", ConnectionAddressFamily.NOTE)
        }
        val label=nativeEntry?.name ?: candidate?.getString("name") ?: names[target] ?: if(target=="proxy") "Определяем сервер…" else "JSON-профиль"
        if(keepTraffic) TrafficState.handover(label) else TrafficState.reset(label)
        ClientEvents.record(this,"Маршруты",ConnectionDiagnostics.summary(prepared))
        if(TrafficCaptureService.wantsServerMetadata() || vpn && !fixtureMode && RuAppTunnelNotice.enabled(this)) {
            prepared = TrafficCaptureConfig.observeNewServer(prepared)
        }
        runningConfig = prepared; selectedOutbound = ""
        val choice=if(nativePreparation!=null)"singbox"else primaryChoice
        require(choice=="singbox") {"Для Steer сначала нужна совместимая конфигурация: текущие параметры не будут отброшены."}
        val runGeneration=generation.coerceAtLeast(1)
        val otherUidBypass=nativeEntry!=null||carrier!=null&&mainCarrierPhysical==null||routeServerSession?.requiresUidBypass==true
        val nativeUidBypass=otherUidBypass||optionalSession?.requiresUidBypass==true
        nativeVpnExclusion=nativeUidBypass
        if(vpn&&!nativeUidBypass)ProbeCoordinator.awaitQuiescent(this)
        val platform=forRun(runGeneration,{cancelled()||runGeneration!=generation},{plan->
            require(requested==epoch&&runGeneration==generation) {"Подключение отменено"}
            openPrimaryTun(plan,nativeUidBypass,reuse=retainedStreams!=null)
        }) {tx,rx->
            if(requested==epoch&&runGeneration==generation) {
                TrafficState.update(tx,rx,SystemClock.elapsedRealtime())
                accountPolicyCounters(runGeneration,tx,rx)
            }
        }
        var admittedNativeProof=nativeProof
        if(nativePreparation!=null){
            nativeProof?.let {proof->
                require(proof.moduleSha==nativePreparation.steerSha&&proof.networkHandle==underlying?.networkHandle&&
                    proof.completedAt[nativeEntry!!.id]?.let {SystemClock.elapsedRealtime()-it in 0..300000}==true){"NATIVE_ROUTE_AUTOMATIC_PROOF_RETIRED"}
            }
            val children=NativeRoutePlatforms(platform,runGeneration){cancelled()||runGeneration!=generation||destroyed}.also {nativeRoutePlatforms=it}
            val bootstrap=ServerBootstrapResolver(this,ServerBootstrapPolicy.capture(nativePreparation.state()),platform,runGeneration,{underlying?.networkHandle}){requested!=epoch||runGeneration!=generation||destroyed}
                .also {nativeRouteBootstrap=it}
            val session=NativeRouteWorkers.start(this,prepared,nativePreparation.plan,runGeneration,nativePreparation.steerSha,
                java.util.function.BooleanSupplier{cancelled()||runGeneration!=generation||destroyed},children::child,bootstrap::ipv4,
                nativePreparation.state(),underlying?.networkHandle,nativeEntry?.id?.takeIf {nativeProof!=null},healthUrl)
            if(session.admittedProof!=null)admittedNativeProof=session.admittedProof
            nativeRouteSession=session;prepared=session.config;runningConfig=prepared
            bootstrap.close();if(nativeRouteBootstrap===bootstrap)nativeRouteBootstrap=null
            session.requireListening()
            ClientEvents.record(this,"Движки","Правила/DNS: внешний диспетчер sing-box; серверы: собственные выходы Steer и необходимые протокольные помощники. Основной выбор и серверы правил не изменены.")
        }
        val dispatcherDeath=java.util.concurrent.atomic.AtomicBoolean()
        val server=connectPrimary(choice,runGeneration,platform,onDeath={
            if(runGeneration==generation&&requested==epoch){
                if(nativePreparation!=null)synchronized(steerRunLock){dispatcherDeath.set(true)}
                (platform as? AutoCloseable)?.close()
                if(nativePreparation!=null)nativeDispatcherDied(runGeneration,requested)
                else requestEngineRecovery(runGeneration,requested)
            }
        })
        val mode=if(vpn)Engine.Mode.VPN else Engine.Mode.PROXY
        require(!vpn||!vpnOwnerIsolationRequired()||server.primaryOwnerIsolationSupported) {"VPN_OWNER_ISOLATION_MODULE_REQUIRED"}
        val settings=ClientPrimarySelection.envelope(prepared,vpn,behavior.metered)
        val validation=ConnectTiming.measure(this,"worker_validate"){server.validate("",settings,mode)}
        require(validation.supported) {"Движок не поддерживает профиль (${validation.code})."}
        val originalCancelled=java.util.function.BooleanSupplier {cancelled()||runGeneration!=generation}
        routeServerSession?.requireCurrentPhysical(this)
        if(mainCarrierPhysical==null)carrier?.attachOriginalPackets(server,settings,bridgeTag,originalCancelled)
        else check(DirectAccessCheck.physical(this)==mainCarrierPhysical){"Физическая сеть автономного транспорта изменилась."}
        routeServerSession?.attachOriginalPackets(server,settings,originalCancelled)
        if(requested==epoch)connectionStep="verify"
        ConnectTiming.measure(this,"worker_start"){server.start("",settings,mode)}
        routeBindingSelection?.tick(SystemClock.elapsedRealtime(),{tag->server.health(tag,healthUrl,2500)},{group,tag->server.selectOutbound(group,tag)},{requested!=epoch||destroyed||core!==server||!profilePolicyCurrent()})
        if(nativePreparation!=null)nativeRouteSession?.requireListening()
        if(nativeEntry!=null)require(health()>=0){"Основной сервер Steer не подтвердил передачу через диспетчер."}
        if(candidate!=null){val delay=health();record(candidate.getString("id"),delay);if(delay<0) throw IOException("Движок не прошёл проверку передачи данных")}
        else if(optionalSession!=null&&(optionalSession?.isAlive()!=true||health()<0))throw IOException("Дополнительное ядро не подтвердило передачу данных. ${optionalSession?.failureDetails().orEmpty()}")
        else if(mainCarrierPhysical!=null&&health()<0)throw IOException("Автономный транспорт не подтвердил передачу данных через sing-box.")
        else if(carrier?.originalPackets==true) {
            // FD adoption/relay READY proves ownership, not remote connectivity.
            // The Rust workers may still be authenticating after GETCONF.
            val deadline=SystemClock.elapsedRealtime()+20_000
            var verified=false
            while(!cancelled()&&runGeneration==generation&&SystemClock.elapsedRealtime()<deadline) {
                carrier?.checkFailure()
                val remaining=(deadline-SystemClock.elapsedRealtime()).coerceAtMost(5_000).toInt()
                if(remaining<=0)break
                if(AdaptivePolicyRuntime.measured(this,BatteryCostReport.Action.PROBE){server.health(target,healthUrl,remaining)}>=0){verified=true;break}
                Thread.sleep(200)
            }
            if(!verified)throw IOException("CSQTT не подтвердил передачу данных после запуска транспорта.")
        }
        carrier?.checkFailure()
        if(requested!=epoch||retainedStreams!=null&&!streamChangeOwned(requested,true)) throw IOException("Подключение отменено")
        val ready=nativeEntry?.let {captureSteerReady(it.id,nativeRouteSession?.assistedProfile(it.id)?:SteerProbePlan.profile(it),admittedNativeProof)}
        synchronized(steerRunLock){
            if(nativeEntry!=null){
                require(!dispatcherDeath.get()&&requested==epoch&&runGeneration==generation&&core===server&&server.state=="RUNNING"){"NATIVE_ROUTE_START_RETIRED"}
                requireNotNull(nativeRouteSession).requireListening()
                require(ready==null||ready.ready(autoAllowed(),underlying?.networkHandle,SystemClock.elapsedRealtime())){"STEER_READY_PROOF_RETIRED"}
                selectedId=nativeEntry.id
                currentStart=RuntimeStart(JSONObject(template.toString()),null,vpn,port,applyRoutes,requested)
            }
            status=if(vpn) "VPN подключён"+(if(localProxyPort>0)" · прокси 127.0.0.1:$localProxyPort"else"") else if(localProxyPort>0) "Прокси 127.0.0.1:$localProxyPort" else "Прокси подключён · пользовательские входы"
        }
        ClientBehaviorUi.applyRecents(this)
        try{startTelemetry()}catch(_:Exception){ClientEvents.record(this,"Предупреждение","Счётчики ядра пока недоступны")}
        ClientEvents.record(this,"Клиент",if(candidate!=null) "Автовыбор БС: ${candidate.getString("name")}" else if(vpn) "Системный VPN подключён" else "Локальный прокси запущен")
        foreground(status)
        if(nativeEntry==null)currentStart=RuntimeStart(JSONObject(template.toString()),candidate?.let{JSONObject(it.toString())},vpn,port,applyRoutes,requested)
        autonomousDispatcherPhysical=mainCarrierPhysical?:routeServerSession?.boundPhysical
        streamRuntime=StreamRuntime(capturedMain?.let {StreamConsumer(it)},
            routeServerSession?.streamProfiles().orEmpty().values.map {StreamConsumer(it)},
            capturedRouting,capturedEntries,primaryChoice,profilePolicyOwner,nativePreparation,nativeEntry,routeBindingSelection,
            autonomousMain=autonomousMain)
        rememberVpnSession(requireNotNull(currentStart))
    }
    private fun prepareCarrierHandoverTun(vpn:Boolean,requested:Long) {
        if(!vpn||nativeVpnExclusion)return
        val rebound=prepareRetainedTunForNativeWorker(
            {requested==epoch&&!destroyed&&currentService===this},
            {plan->openPrimaryTun(plan,true)})
        if(rebound){
            nativeVpnExclusion=true
            ClientEvents.record(this,"Движки","Туннель сохранён: политика транспорта подготовлена до запуска движка БС.")
        }
    }
    private fun startSteer(template:JSONObject,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long,keepTraffic:Boolean) {
        require(vpn&&!fixtureMode) {"Steer сейчас работает только как системный VPN; локальный прокси не поддерживается."}
        val routes=RoutingStore(this);routes.load()
        val connections=Connections(this);connections.load()
        val state=RoutingConnectionBinding.prepare(this,runtimeRoutes {routes.snapshot()},connections.entries(),template.has("mcrf_carrier")||template.has("mcrf_bs"),connections.probes())
        val dispatcher=RouteServerPlan.activeIds(state).isNotEmpty()
        val snapshot=if(dispatcher)null else ClientPrimarySelection.steerSnapshot(this,state,true)
        if(snapshot!=null)require(snapshot.supported) {ClientPrimarySelection.steerFailure(snapshot)}
        fun begin(entry:ServerImport.Server,proof:SteerProbeDelays?=null){
            if(dispatcher){
                val preparation=NativeRoutePreparation.capture(this,entry,state,connections.entries())
                startSourceWithCore(preparation.source(),null,true,port,applyRoutes,requested,keepTraffic||tunnel!=null,null,"steer",preparation,entry,proof)
            }else {
                startSteerPrepared(template,prepareSteerSelected(entry,state,requireNotNull(requireNotNull(snapshot).settingsJson),requested,proof),
                    port,applyRoutes,requested,keepTraffic)
            }
        }
        val pinned=recoveryTarget?.takeIf {networkRecovery!=null}
        if(pinned!=null){
            val entry=connections.entries().singleOrNull {it.id==pinned.server}
                ?:throw IllegalArgumentException("RECOVERY_SERVER_REMOVED")
            begin(entry);return
        }
        if(autoAllowed()) {
            fun paused(){
                currentStart=RuntimeStart(JSONObject(template.toString()),null,true,port,applyRoutes,requested)
                status=if(autoAllowed())"VPN · Steer: отмеченные серверы недоступны, трафик приостановлен"else"VPN · Steer: автовыбор выключен, трафик приостановлен"
                foreground(status);if(autoAllowed())scheduleSteerHealth(requireNotNull(currentStart))
            }
            val pool=SteerAutomaticPool(ConnectionSections.entries(connections.autoCandidates(connections.entries()),false),connections.autoIds(),System.nanoTime().coerceAtLeast(1))
            require(pool.candidates.isNotEmpty()) {"Нет отмеченных серверов, совместимых с собственным движком Steer. Отметки не изменены; другой движок автоматически не выбирается."}
            val native=ProbeCoordinator.measureSteerCandidatesRuntime(this,pool.measurements,state,healthUrl) {
                requested!=epoch||destroyed||currentService!==this||!autoAllowed()
            }
            val ranked=pool.rank(native,SystemClock.elapsedRealtime())
            for(entry in ranked) {
                if(!autoAllowed()&&requested==epoch&&!destroyed&&currentService===this&&tunnel!=null){paused();return}
                if(requested!=epoch||destroyed||currentService!==this||!autoAllowed())throw IOException("Подключение отменено")
                val fresh=Connections(this);fresh.load()
                if(pool.stillMarked(listOf(entry),fresh.entries(),fresh.autoIds()).isEmpty()||
                    SystemClock.elapsedRealtime()-native.completedAt.getValue(entry.id) !in 0..300000)continue
                try {
                    begin(entry,native)
                    scheduleSteerHealth(requireNotNull(currentStart));return
                }catch(error:Exception){
                    closeCore(keepTun=true)
                    if(!PrimaryFallbackPolicy.canRetryFailure(error))throw error
                    if(!autoAllowed()&&requested==epoch&&!destroyed&&currentService===this&&tunnel!=null){paused();return}
                    if(requested!=epoch||destroyed||currentService!==this||!autoAllowed())throw IOException("Подключение отменено")
                }
            }
            if(tunnel!=null) {
                paused();return
            }
            throw IOException("Отмеченные серверы Steer не подтвердили HTTPS. Это не отсутствие выбранных серверов; отметки сохранены.")
        }
        val entry=connections.entries().singleOrNull {it.id==steerFrozenId.ifEmpty {preferences.getString("selected","").orEmpty()}}
            ?:throw IOException("Выбери один сервер для Steer.")
        begin(entry)
    }
    /** Immutable capture shared by ordinary selection and the strictly local DEBUG fixture.
     * No user choice/settings are written by the native run itself. */
    private fun prepareSteerSelected(entry:ServerImport.Server,state:JSONObject,settings:String,requested:Long,proof:SteerProbeDelays?):SteerPrepared {
        check(requested==epoch&&!destroyed&&currentService===this){"STEER_SELECTED_OWNER_RETIRED"}
        steerProbeEpoch=requested
        val bootstrap=ServerBootstrapPolicy.capture(state)
        if(!CompactProtocolPolicy.eligible(entry))return SteerPrepared(SteerProbePlan.profile(entry),entry.host,entry.name,entry.id,settings,proof=proof,serverDns=bootstrap)
        val capturedState=JSONObject(state.toString())
        val deferred=SteerHelperPreparation {handle->
            var admitted=proof
            require(compactSession==null){"STEER_HELPER_ALREADY_OWNED"}
            if(engineUsage==null)engineUsage=EngineUpdateGate.acquireUsage(this)?:throw IOException("Движок обновляется.")
            fun retired()=requested!=epoch||destroyed||currentService!==this||underlying?.networkHandle!=handle
            val helper=CompactProtocolSession.start(this,entry,capturedState,handle,java.util.function.BooleanSupplier(::retired))
            compactSession=helper // The service now owns cleanup even if its fresh probe fails.
            if(proof!=null){
                val fresh=ProbeCoordinator.measureSteerRuntime(this,listOf(helper.effective),healthUrl) {retired()||!autoAllowed()} as SteerProbeDelays
                helper.requireAlive(underlying?.networkHandle);helper.requireHealthy(fresh.getValue(entry.id)>=0)
                admitted=SteerProbeDelays(fresh.toMap(),fresh.completedAt,fresh.moduleSha,fresh.networkHandle,mapOf(entry.id to helper.binding))
            }
            SteerPrepared(SteerProbePlan.profile(helper.effective),helper.effective.host,entry.name,entry.id,settings,proof=admitted,serverDns=bootstrap)
        }
        // Creating the immutable preparation must not start a process or read
        // underlying: startSteerPrepared initializes its one owning HostRun first.
        return SteerPrepared("",entry.host,entry.name,entry.id,settings,proof=proof,serverDns=bootstrap,deferred=deferred)
    }
    private class SteerPrepared(val original:String,val host:String,val name:String,val id:String,val settings:String,val fixtureDial:String?=null,val proof:SteerProbeDelays?=null,val serverDns:ServerBootstrapPolicy?=null,val deferred:SteerHelperPreparation<SteerPrepared>?=null)
    private fun standalonePacketEntry(template:JSONObject,vpn:Boolean,applyRoutes:Boolean):ServerImport.Server? {
        if(fixtureMode||!vpn||!applyRoutes||!preferences.getBoolean("catalogueProfile",false)||
            preferences.getBoolean("automatic",false)||template.has("mcrf_bs")||template.has("mcrf_carrier")||
            !WgAddonPolicy.usesWireGuard(template))return null
        val tag=template.optJSONObject("route")?.optString("final")
        return Connections(this).apply {load()}.entries().singleOrNull {ServerImport.tag(it)==tag}
            ?.takeIf {WgAddonPolicy.standaloneProfile(it,vpn,false)}
    }
    private fun startCompactWireGuard(template:JSONObject,entry:ServerImport.Server,port:Int,requested:Long) {
        // This prestart owner is retired before the packet worker's forRun.
        // It cannot establish a TUN or replace the chosen server-address DNS.
        val policy=ProfilePolicyRuntime.active(this)?.takeIf {it.configuration!=null}
        check(policy?.id==profilePolicyOwner) {"PACKET_PROFILE_POLICY_CHANGED"}
        val settings=ClientPacketSelection.settings(this){ordinary->runtimeRoutes(policy){ordinary}}
        val routing=settings.routing
        val runGeneration=generation.coerceAtLeast(1)
        val platform=forRun(runGeneration,{requested!=epoch||runGeneration!=generation||destroyed},
            {throw IOException("WG_BOOTSTRAP_TUN_FORBIDDEN")}){_,_->}
        val resolver=ServerBootstrapResolver(this,ServerBootstrapPolicy.capture(routing),platform,runGeneration,{underlying?.networkHandle}){requested!=epoch||runGeneration!=generation||destroyed}
            .also {nativeRouteBootstrap=it}
        val dial=resolver.ipv4(entry.host)
        resolver.close();if(nativeRouteBootstrap===resolver)nativeRouteBootstrap=null
        closePlatform(keepTun=true)
        val prepared=ClientPacketSelection.prepare(this,entry.original,dial,settings)
        check(profilePolicyCurrent()) {"PACKET_PROFILE_POLICY_CHANGED"}
        val pin=prepared.module.apkSha256
        if(rebuildingForNetwork)recoveryTarget?.takeIf {it.choice==WgAddonPolicy.KIND}?.let {
            require(it.moduleIdentity==pin&&it.server==entry.id){"RECOVERY_PACKET_IDENTITY_CHANGED"}
        }
        val excludedApps=if(prepared.includedApps.isEmpty())prepared.routing.directApps else emptyList()
        val run=startPacketPrepared(entry.original,dial,pin,prepared.plan,prepared.routing.routes,prepared.routing.dns,
            excludedApps,prepared.includedApps,"MCRF · WG/AWG",requested,prepared.routing.captured.settings,false,prepared.storageGeneration)
        val test=run.second.openConnection(java.net.URL(healthUrl),java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
        val verified=try {test.useCaches=false;test.connectTimeout=10000;test.readTimeout=10000;test.instanceFollowRedirects=false;test.responseCode==204}finally{test.disconnect()}
        check(verified&&requested==epoch&&!destroyed&&core===run.first) {"WG/AWG запущен, но передача данных не подтверждена."}
        TrafficState.reset(entry.name,countersSupported=false) // Distinct from waiting for a supported core's first sample.
        selectedId=entry.id;target=entry.id;runningConfig=null
        sessionPrimaryChoice=WgAddonPolicy.KIND;runningPrimaryIdentity=pin
        status="VPN подключён · WG/AWG · счётчики недоступны";ClientBehaviorUi.applyRecents(this)
        currentStart=RuntimeStart(JSONObject(template.toString()),null,true,port,true,requested)
        rememberVpnSession(requireNotNull(currentStart))
        foreground(status);ClientEvents.record(this,"Клиент","Компактный WG/AWG: передача данных подтверждена")
    }
    private fun startPacketFixture(template:JSONObject,fixture:ClientPacketFixture.Result,port:Int,requested:Long) {
        check(BuildConfig.DEBUG&&fixtureMode&&requested==epoch&&!destroyed)
        val ownerPackages=fixture.ownerPairSha256?.let { ClientP1OwnerFixtureScope.packages(this,it) }
        val run=startPacketPrepared(fixture.original,fixture.dial,fixture.apkHash,fixture.plan,
            fixture.tunRoutes,fixture.dns,emptyList(),listOf(packageName),"MCRF · WG/AWG fixture",requested,
            fixture.captured.settings,fixture.captureBlocked,strictOwnerFixture=ownerPackages!=null)
        val server=run.first;val network=run.second
        val ownerManager=ownerPackages?.let { getSystemService(android.net.ConnectivityManager::class.java) }
        if(ownerPackages!=null) {
            val scope=requireNotNull(installedVpnScope?.takeIf {it.first===tunnel}?.second)
            val helperUid=packageManager.getApplicationInfo(ownerPackages[1],0).uid
            check(scope.strict&&scope.includeOnly&&scope.admits(android.os.Process.myUid(),listOf(packageName))&&
                !scope.admits(helperUid,ownerPackages.drop(1))&&!scope.admits(0,listOf(packageName))&&
                !scope.admits(android.os.Process.myUid(),emptyList())&&!scope.admits(-1,emptyList())) {"PACKET_STRICT_ACTIVE_SCOPE"}
        }
        for(ipv6 in listOf(false,true)) {
            if(fixture.captureBlocked){
                val blocked=java.net.InetAddress.getByName(if(ipv6)"fd77::3"else"10.77.0.3")
                val source=java.net.InetAddress.getByName(if(ipv6)"fd77::2"else"10.77.0.2")
                val until=SystemClock.elapsedRealtime()+8000
                var bound:java.net.DatagramSocket?=null
                while(bound==null){
                    check(requested==epoch&&!destroyed&&core===server)
                    val socket=java.net.DatagramSocket(null)
                    try{network.bindSocket(socket);bound=socket}
                    catch(error:java.net.SocketException){socket.close();if(SystemClock.elapsedRealtime()>=until)throw error;Thread.sleep(100)}
                }
                requireNotNull(bound).use {socket->
                    socket.bind(java.net.InetSocketAddress(source,0));socket.connect(blocked,9090)
                    val bytes=fixture.body.toByteArray(Charsets.US_ASCII)
                    repeat(3){check(requested==epoch&&!destroyed&&core===server);socket.send(java.net.DatagramPacket(bytes,bytes.size));Thread.sleep(50)}
                }
            }
            val target=java.net.InetAddress.getByName(if(ipv6)"fd77::1" else "10.77.0.1")
            val local=java.net.InetAddress.getByName(if(ipv6)"fd77::2" else "10.77.0.2")
            val admitUntil=SystemClock.elapsedRealtime()+8000
            var admitted:java.net.Socket?=null
            while(admitted==null) {
                check(requested==epoch&&!destroyed&&core===server)
                val socket=java.net.Socket()
                try {network.bindSocket(socket);admitted=socket}
                catch(error:java.net.SocketException) {socket.close();if(SystemClock.elapsedRealtime()>=admitUntil)throw error;Thread.sleep(100)}
            }
            requireNotNull(admitted).use {socket->
                socket.soTimeout=5000;socket.bind(java.net.InetSocketAddress(local,0));socket.connect(java.net.InetSocketAddress(target,8080),5000)
                if(ownerManager!=null)check(ownerManager.getConnectionOwnerUid(6,
                    java.net.InetSocketAddress(local,socket.localPort),java.net.InetSocketAddress(target,8080))==android.os.Process.myUid()) {
                    "PACKET_STRICT_TCP_OWNER"
                }
                socket.getOutputStream().apply {write(("GET ${fixture.path} HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII));flush()}
                PacketFixtureHttp.verify(socket.getInputStream(),fixture.body)
            }
            if(fixture.managedDns==null)java.net.DatagramSocket(null).use {socket->
                network.bindSocket(socket);socket.soTimeout=5000
                socket.bind(java.net.InetSocketAddress(local,0));socket.connect(target,53)
                val query=PacketFixtureDns.query(fixture.body.removePrefix("mcrf-fixture-"),ipv6,java.security.SecureRandom().nextInt(65536))
                socket.send(java.net.DatagramPacket(query,query.size))
                if(ownerManager!=null)check(ownerManager.getConnectionOwnerUid(17,
                    java.net.InetSocketAddress(local,socket.localPort),java.net.InetSocketAddress(target,53))==android.os.Process.myUid()) {
                    "PACKET_STRICT_UDP_OWNER"
                }
                val packet=java.net.DatagramPacket(ByteArray(513),513);socket.receive(packet)
                PacketFixtureDns.verify(query,packet.data.copyOfRange(packet.offset,packet.offset+packet.length),ipv6)
            }
        }
        if(fixture.managedDns!=null)PacketManagedDnsFixture.verify(network,fixture){check(requested==epoch&&!destroyed&&core===server)}
        else if(ownerPackages==null) for(ipv6 in listOf(false,true)) {
            val cancellation=CancellationSignal()
            val answer=java.util.concurrent.CompletableFuture<List<java.net.InetAddress>>()
            try {
                // DnsResolver cache flags are documented combinable bit flags.
                @android.annotation.SuppressLint("WrongConstant")
                val noCacheFlags=android.net.DnsResolver.FLAG_NO_CACHE_LOOKUP or android.net.DnsResolver.FLAG_NO_CACHE_STORE
                android.net.DnsResolver.getInstance().query(network,fixture.body.removePrefix("mcrf-fixture-")+".invalid",
                    if(ipv6)android.net.DnsResolver.TYPE_AAAA else android.net.DnsResolver.TYPE_A,
                    noCacheFlags,
                    java.util.concurrent.Executor {it.run()},cancellation,object:android.net.DnsResolver.Callback<List<java.net.InetAddress>> {
                        override fun onAnswer(addresses:List<java.net.InetAddress>,rcode:Int) {
                            if(rcode==0)answer.complete(addresses)else answer.completeExceptionally(IOException("PACKET_PROFILE_DNS_RCODE"))
                        }
                        override fun onError(error:android.net.DnsResolver.DnsException) {answer.completeExceptionally(IOException("PACKET_PROFILE_DNS_FAILED"))}
                    })
                val addresses=answer.get(5,java.util.concurrent.TimeUnit.SECONDS)
                val expected=java.net.InetAddress.getByName(if(ipv6)"fd77::1"else"10.77.0.1")
                check(addresses.size==1&&addresses.single()==expected) {"PACKET_PROFILE_DNS_ANSWER"}
            }finally{cancellation.cancel()}
        }
        check(requested==epoch&&!destroyed&&core===server)
        status=if(ownerPackages==null)"VPN подключён · WG/AWG fixture" else "VPN подключён · WG/AWG strict owner fixture"
        foreground(status)
        currentStart=RuntimeStart(JSONObject(template.toString()),null,true,port,false,requested)
        rememberVpnSession(requireNotNull(currentStart))
    }
    /** Shared packet ingress construction; admission belongs to the caller, not the native backend. */
    private fun startPacketPrepared(original:String,dial:String,apkHash:String,plan:CompactWireGuardPlan.Plan,
        routes:List<String>,dns:List<String>,excludedApps:List<String>,includedApps:List<String>,label:String,requested:Long,routeSettings:String="{}",ownedBlockFixture:Boolean=false,storageGeneration:String?=null,strictOwnerFixture:Boolean=false):Pair<ClientPrimaryWorker,Network> {
        check(vpnMode&&requested==epoch&&!destroyed)
        require(!strictOwnerFixture||BuildConfig.DEBUG&&fixtureMode&&!ownedBlockFixture&&
            includedApps==listOf(packageName)&&excludedApps.isEmpty()) {"PACKET_STRICT_FIXTURE_SCOPE"}
        require(!ownedBlockFixture||BuildConfig.DEBUG&&fixtureMode&&includedApps==listOf(packageName)&&excludedApps.isEmpty()) {"PACKET_BLOCK_FIXTURE_SCOPE"}
        require(excludedApps.isEmpty()||includedApps.isEmpty()) {"PACKET_APP_SCOPE_CONFLICT"}
        engineUsage=EngineUpdateGate.acquireUsage(this)?:throw IOException("PACKET_MODULE_UPDATE_BUSY")
        val module=EngineUpdatePacket.capture(this)
        require(module.apkSha256==apkHash){"PACKET_MODULE_PIN_CHANGED"}
        if(routeSettings!="{}") {
            val captured=PacketRouteEnvelope.decode(routeSettings,plan.routes,plan.addresses)
            require(routes==captured.projection.tun) {"PACKET_TUN_PROJECTION_MISMATCH"}
            require(if(ownedBlockFixture)captured.dns==null else captured.dns==dns) {"PACKET_DNS_SNAPSHOT_MISMATCH"}
            if(ownedBlockFixture)PacketRouteEnvelope.ownedBlockFixturePlan(plan,routeSettings)
            else PacketRouteEnvelope.workerPlan(plan,routeSettings,module.admission,module.managedDns) // Before worker/TUN side effects.
        } else require(fixtureMode) {"PACKET_PRODUCTION_ROUTE_SNAPSHOT_REQUIRED"}
        val runGeneration=generation.coerceAtLeast(1)
        val platform=forRun(runGeneration,{requested!=epoch||runGeneration!=generation},{throw IOException("PACKET_TUN_ALREADY_OWNED")}) {_,_->}
        val server=ClientPrimaryWorker.connectPacket(this,runGeneration,platform,apkHash,onDeath={
            if(runGeneration==generation&&requested==epoch){(platform as? AutoCloseable)?.close();requestEngineRecovery(runGeneration,requested)}
        })
        core=server
        val physical=underlying?:throw IOException("PACKET_PHYSICAL_NETWORK_REQUIRED")
        val builder=Builder().setSession(label).setMtu(plan.mtu).setBlocking(false).setMetered(behavior.metered)
            .setUnderlyingNetworks(arrayOf(physical))
        plan.addresses.forEach {cidr->val parts=cidr.split('/');builder.addAddress(parts[0],parts[1].toInt())}
        require(routes.isNotEmpty()) {"PACKET_TUN_NO_ROUTES"}
        routes.forEach {cidr->val checked=ClientTunCidr.parse(cidr);val parts=checked.toString().split('/');builder.addRoute(parts[0],parts[1].toInt())}
        dns.forEach {builder.addDnsServer(it)}
        excludedApps.forEach {name->RouteRules.validatePackage(name);require(name!=packageName) {"PACKET_HOST_MUST_BE_INSIDE_VPN"}}
        if(includedApps.isNotEmpty()) {
            require(packageName in includedApps) {"PACKET_HOST_MUST_BE_INSIDE_VPN"}
            includedApps.forEach(RouteRules::validatePackage)
        }
        val appScope=captureVpnScope(includedApps,excludedApps,forceStrict=strictOwnerFixture)
        require(!appScope.includeOnly||packageName in appScope.included) {"PACKET_HOST_MUST_BE_INSIDE_VPN"}
        require(!appScope.strict||server.packetOwnerIsolationSupported) {"VPN_OWNER_ISOLATION_MODULE_REQUIRED"}
        appScope.apply(builder)
        ProbeCoordinator.awaitQuiescent(this)
        if(storageGeneration!=null)require(ProfileStore.generation(this)==storageGeneration&&profilePolicyCurrent()){"PACKET_SETTINGS_GENERATION_CHANGED"}
        check(requested==epoch&&!destroyed&&currentService===this&&core===server) {"PACKET_START_RETIRED"}
        val descriptor=ConnectTiming.measure(this,"tun_establish"){builder.establish()}?:throw IOException("PACKET_TUN_NOT_GRANTED")
        installedVpnScope=descriptor to appScope
        val previous=tunnel;tunnel=descriptor;previous?.close();nativeVpnExclusion=false;localProxyPort=0
        val cm=getSystemService(android.net.ConnectivityManager::class.java)
        val deadline=SystemClock.elapsedRealtime()+8000
        var selected:Network?=null
        while(requested==epoch&&!destroyed&&core===server&&SystemClock.elapsedRealtime()<deadline&&selected==null) {
            selected=cm.allNetworks.firstOrNull {network->
                val caps=cm.getNetworkCapabilities(network)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true&&
                    (android.os.Build.VERSION.SDK_INT<30||caps.ownerUid==android.os.Process.myUid())&&
                    cm.getLinkProperties(network)?.linkAddresses?.let {actual->actual.size==plan.addresses.size&&plan.addresses.all {cidr->
                        val parts=cidr.split('/');actual.any {it.address==java.net.InetAddress.getByName(parts[0])&&it.prefixLength==parts[1].toInt()}
                    }}==true
            }
            if(selected==null)Thread.sleep(50)
        }
        val network=selected?:throw IOException("PACKET_VPN_NETWORK_REQUIRED")
        if(storageGeneration!=null)require(ProfileStore.generation(this)==storageGeneration&&profilePolicyCurrent()){"PACKET_SETTINGS_GENERATION_CHANGED"}
        check(requested==epoch&&!destroyed&&currentService===this&&core===server) {"PACKET_START_RETIRED"}
        val managed=PacketRouteEnvelope.decode(routeSettings,plan.routes,plan.addresses).managed!=null
        server.startPacket(original,descriptor,dial,routeSettings,ownedBlockFixture,if(managed)network.networkHandle else 0,appScope.strict)
        packetVpnNetwork=network
        return server to network
    }

    private fun startSteerPrepared(template:JSONObject,prepared:SteerPrepared,port:Int,applyRoutes:Boolean,requested:Long,keepTraffic:Boolean) {
        var entry=prepared
        require(vpnMode&&(!fixtureMode||BuildConfig.DEBUG&&entry.fixtureDial!=null)) {"Steer требует системный VPN."}
        if(requested!=epoch||destroyed||currentService!==this)throw IOException("Подключение отменено")
        steerProbeEpoch=requested
        if(engineUsage==null)engineUsage=EngineUpdateGate.acquireUsage(this)?:throw IOException("Заверши установку движка.")
        val runGeneration=generation.coerceAtLeast(1)
        var proofPinned=false
        val unexpectedDeath=java.util.concurrent.atomic.AtomicBoolean()
        val platform=forRun(runGeneration,{requested!=epoch||runGeneration!=generation||
            proofPinned&&entry.proof?.networkHandle?.let {underlying?.networkHandle!=it}==true},{throw IOException("Steer использует заранее созданный TUN")}) {tx,rx->
            if(requested==epoch&&runGeneration==generation) {
                TrafficState.update(tx,rx,SystemClock.elapsedRealtime())
                accountPolicyCounters(runGeneration,tx,rx)
            }
        }
        entry=entry.deferred?.materialize(underlying?.networkHandle)?:entry
        entry.proof?.let {proof->
            val installed=EngineUpdatePrimary.installation(this,"steer")
            require(ClientPrimaryWorkerWire.hashFile(File(installed.sourceDir))==proof.moduleSha&&
                underlying?.networkHandle==proof.networkHandle&&
                proof.completedAt[entry.id]?.let {SystemClock.elapsedRealtime()-it in 0..300000}==true) {"STEER_AUTOMATIC_PROOF_RETIRED"}
        }
        proofPinned=true
        val server=connectPrimary("steer",runGeneration,platform,onDeath={
            synchronized(steerRunLock) {
                if(runGeneration==generation&&requested==epoch){
                    unexpectedDeath.set(true)
                }
            }
            if(runGeneration==generation&&requested==epoch) {
                (platform as? AutoCloseable)?.close() // Exact old callback owner, never a newer run/TUN.
                requestEngineRecovery(runGeneration,requested)
            }
        })
        val settings=entry.settings
        fixtureSteerOwnerPin?.let { pin ->
            check(BuildConfig.DEBUG&&fixtureMode&&entry.fixtureDial!=null&&!applyRoutes&&!keepTraffic&&
                ClientPrimaryWorkerWire.hashFile(File(EngineUpdatePrimary.installation(this,"steer").sourceDir))==pin) {
                "STEER_STRICT_FIXTURE_MODULE_CHANGED"
            }
        }
        require(!vpnOwnerIsolationRequired()||server.primaryOwnerIsolationSupported) {
            "Для изоляции приложений требуется обновить модуль Steer."
        }
        val validation=server.validate(entry.original,settings,Engine.Mode.VPN)
        require(validation.supported) {"Steer не поддерживает параметры выбранного сервера (${validation.code} · ${validation.field}). Профиль не изменён."}
        val plan=JSONObject(server.tunPlan(entry.original,settings,Engine.Mode.VPN))
        val admittedPlan=SteerTunPlanPolicy.parse(plan,JSONObject(settings),packageName)
        val physical=underlying?:throw IOException("Нет физической сети для Steer.")
        val resolver=entry.serverDns?.let {policy->ServerBootstrapResolver(this,policy,platform,runGeneration,{underlying?.networkHandle}){requested!=epoch||runGeneration!=generation||destroyed}
            .also {nativeRouteBootstrap=it}}
        val dial=entry.fixtureDial?.let {literal->
            require(BuildConfig.DEBUG&&fixtureMode&&literal==entry.host)
            // The strict fixture parser already pinned a literal RFC1918 address.
            // Materialize it without a resolver, never a physical/default fallback.
            java.net.InetAddress.getByAddress(literal.split('.').map {it.toInt().toByte()}.toByteArray()).hostAddress
        }?:requireNotNull(resolver){"STEER_SERVER_DNS_CAPTURE_REQUIRED"}.ipv4(entry.host)
        val bootstrap=JSONObject(settings).getJSONObject("globalDns").let {dns->
            if(dns.optString("provider")=="Свой"&&dns.optString("protocol") in setOf("DoH","DoT")) {
                val address=dns.getString("custom");val host=java.net.URI(if("://" in address)address else "tls://$address").host?:throw IOException("Неверный DNS Steer.")
                arrayOf(requireNotNull(resolver){"STEER_SERVER_DNS_CAPTURE_REQUIRED"}.ipv4(host))
            }else emptyArray()
        }
        resolver?.close();if(nativeRouteBootstrap===resolver)nativeRouteBootstrap=null
        if(requested!=epoch)throw IOException("Подключение отменено")
        ProbeCoordinator.awaitQuiescent(this)
        val builder=Builder().setSession("MCRF · Steer").setMtu(1500).setBlocking(false).setMetered(behavior.metered)
            .addAddress("10.89.0.2",32).addRoute("0.0.0.0",0).addDnsServer("10.89.0.1").setUnderlyingNetworks(arrayOf(physical))
        if(admittedPlan.ipv6)builder.addAddress(SteerTunPlanPolicy.IPV6_ADDRESS,126).addRoute("::",0)
        admittedPlan.excludedApps.forEach {name->require(name!=packageName)}
        val fixtureScope=if(entry.fixtureDial!=null&&JSONObject(settings).optInt("steerVersion",1)==2){
            // Exact ClientSteerFixture.p1Snapshot ONLY, admitted before this
            // entry exists. Narrow the synthetic VPN to our UID, not user apps.
            // Does not rewrite ordinary model2 app scope or any saved setting.
            check(BuildConfig.DEBUG&&fixtureMode&&admittedPlan.includedApps.isEmpty()&&admittedPlan.excludedApps.isEmpty()) {"STEER_P1_OWNED_UID_SCOPE"}
            fixtureP1OwnerHelperSha?.let {ClientP1OwnerFixtureScope.packages(this,it)}?:listOf(packageName)
        }else null
        val appScope=captureVpnScope(fixtureScope?:admittedPlan.includedApps,admittedPlan.excludedApps)
        require(!appScope.includeOnly||packageName in appScope.included) {"STEER_HOST_MUST_BE_INSIDE_VPN"}
        appScope.apply(builder)
        val descriptor=ConnectTiming.measure(this,"tun_establish"){builder.establish()}?:throw IOException("Android не разрешил открыть VPN.")
        installedVpnScope=descriptor to appScope
        val previous=tunnel;tunnel=descriptor;previous?.close();nativeVpnExclusion=false;localProxyPort=0
        val connectivity=getSystemService(android.net.ConnectivityManager::class.java)
        val deadline=SystemClock.elapsedRealtime()+8000
        var vpnNetwork:Network?=null
        while(requested==epoch&&SystemClock.elapsedRealtime()<deadline&&vpnNetwork==null){
            vpnNetwork=connectivity.allNetworks.firstOrNull {network->
                val caps=connectivity.getNetworkCapabilities(network)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true&&
                    (android.os.Build.VERSION.SDK_INT<30||caps.ownerUid==android.os.Process.myUid())&&
                    connectivity.getLinkProperties(network)?.linkAddresses?.any {it.address.hostAddress=="10.89.0.2"}==true
            }
            if(vpnNetwork==null)Thread.sleep(50)
        }
        val selectedNetwork=vpnNetwork?:throw IOException("Не удалось получить сеть VPN Steer.")
        steerVpnNetwork=selectedNetwork
        if(keepTraffic)TrafficState.handover(entry.name)else TrafficState.reset(entry.name)
        if(requested==epoch)connectionStep="verify"
        server.start(entry.original,settings,Engine.Mode.VPN,descriptor,dial,bootstrap,selectedNetwork.networkHandle)
        val verifyTransfer={if(entry.fixtureDial!=null) verifySteerFixtureHealth(selectedNetwork,requested) else {
            val test=selectedNetwork.openConnection(java.net.URL(healthUrl),java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            try {test.useCaches=false;test.connectTimeout=10000;test.readTimeout=10000;test.instanceFollowRedirects=false;test.responseCode==204}finally{test.disconnect()}
        }}
        val verified=compactSession?.verifyHealth(verifyTransfer)?:verifyTransfer()
        compactSession?.requireAlive(underlying?.networkHandle)
        compactSession?.requireHealthy(verified)
        require(verified&&requested==epoch) {"Steer запущен, но передача данных не подтверждена. ${compactSession?.diagnostic().orEmpty()}"}
        val ready=captureSteerReady(entry.id,entry.original,entry.proof)
        synchronized(steerRunLock) {
            require(!unexpectedDeath.get()&&runGeneration==generation&&core===server&&server.state in setOf("STARTING","RUNNING")) {"STEER_START_OWNER_DIED"}
            require(ready==null||ready.ready(autoAllowed(),underlying?.networkHandle,SystemClock.elapsedRealtime())){"STEER_READY_PROOF_RETIRED"}
            selectedId=entry.id;target=entry.id;runningConfig=null
            currentStart=RuntimeStart(JSONObject(template.toString()),null,true,port,applyRoutes,requested)
            localProxyPort=server.steerProxyPort
            status="VPN подключён · Steer"+(if(localProxyPort>0)" · прокси 127.0.0.1:$localProxyPort"else"")
        }
        if(fixtureSteerOwnerPin==null){
            ClientBehaviorUi.applyRecents(this)
            rememberVpnSession(requireNotNull(currentStart))
            startTelemetry()
        }
        foreground(status);ClientEvents.record(this,"Клиент","Steer: передача данных подтверждена")
    }
    /** Owned DEBUG-only plaintext protocol fixture; ordinary health remains HTTPS.
     * Keep the application's cleartext policy unchanged. No physical/default retry. */
    private fun verifySteerFixtureHealth(network:Network,requested:Long):Boolean {
        check(BuildConfig.DEBUG&&fixtureMode)
        val uri=java.net.URI(healthUrl)
        require(uri.scheme=="http"&&uri.host=="198.18.0.2"&&uri.port in listOf(-1,80)&&uri.rawQuery==null&&uri.fragment==null&&
            uri.userInfo==null&&uri.rawPath.matches(Regex("/owned/[0-9a-f]{32}")))
        val deadline=SystemClock.elapsedRealtime()+8000
        fun admitted():java.net.Socket {while(true){
            check(requested==epoch&&!destroyed&&currentService===this&&core?.choiceId=="steer") {"STEER_FIXTURE_HEALTH_RETIRED"}
            val next=java.net.Socket()
            try {network.bindSocket(next);return next}
            catch(error:java.net.SocketException){
                next.close()
                if(SystemClock.elapsedRealtime()>=deadline||error.message.orEmpty().let {"EPERM" !in it&&"ENONET" !in it})throw error
                Thread.sleep(100)
            }catch(error:Exception){next.close();throw error}
        }}
        val socket=admitted()
        return socket.use {
            it.bind(java.net.InetSocketAddress(java.net.InetAddress.getByAddress(byteArrayOf(10,89,0,2)),0))
            it.connect(java.net.InetSocketAddress(java.net.InetAddress.getByAddress(byteArrayOf(198.toByte(),18,0,2)),80),5000)
            check(it.localAddress.hostAddress=="10.89.0.2")
            it.getOutputStream().apply {write(("GET ${uri.rawPath} HTTP/1.1\r\nHost: 198.18.0.2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII));flush()}
            val expected="HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
            fun read():Int {val left=deadline-SystemClock.elapsedRealtime();check(left>0);it.soTimeout=left.coerceAtMost(5000).toInt();return it.getInputStream().read()}
            expected.all {byte->read()==byte.toInt()}&&read()==-1&&requested==epoch&&!destroyed
        }
    }
    private fun steerHealth(url:String=healthUrl):Long {
        val selectedNetwork=steerVpnNetwork?:return -1
        if(core?.choiceId!="steer")return -1
        val captured=generation;val requested=epoch;val started=SystemClock.elapsedRealtime()
        return try {
            require(java.net.URI(url).scheme=="https") {"STEER_HEALTH_HTTPS_REQUIRED"}
            val test=selectedNetwork.openConnection(java.net.URL(url),java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            val success=try {test.useCaches=false;test.connectTimeout=5000;test.readTimeout=5000;test.instanceFollowRedirects=false;test.responseCode==204}finally{test.disconnect()}
            if(success&&captured==generation&&requested==epoch)SystemClock.elapsedRealtime()-started else -1
        }catch(_:Exception){-1}
    }
    private fun captureSteerReady(id:String,profile:String,proof:SteerProbeDelays?):SteerReadyAdmission? {
        if(proof==null)return null
        val latest=Connections(this);latest.load()
        val entry=latest.entries().singleOrNull {it.id==id}
        val matches=entry!=null&&try{
            if(CompactProtocolPolicy.eligible(entry)){
                val helper=compactSession
                if(helper!=null)proof.assisted[id]===helper.binding&&helper.binding.matches(entry,profile)
                else nativeRouteSession?.matchesAssisted(id,entry,profile,proof)==true
            }else SteerProbePlan.profile(entry)==profile
        }catch(_:Exception){false}
        return SteerReadyAdmission(id in latest.autoIds(),matches,proof.completedAt[id]?:Long.MIN_VALUE,proof.networkHandle)
    }
    private fun nativeDispatcherDied(captured:Long,requested:Long){
        requestEngineRecovery(captured,requested)
    }
    private fun scheduleSteerHealth(source:RuntimeStart,immediate:Boolean=false) {
        if(!autoAllowed()||source.requested!=epoch||destroyed||networkRecovery!=null)return
        // An all-failed round may have retired its core while retaining the
        // captured VPN and this explicit Steer retry plan. Re-arm only this
        // live plan, not the saved primary preference or an old probe owner.
        steerProbeEpoch=source.requested
        healthTask?.let(main::removeCallbacks)
        val captured=generation
        val task=Runnable {
            if(autoAllowed()&&source.requested==epoch&&captured==generation&&!destroyed&&networkRecovery==null)worker.execute {
                if(holdForCaptivePortal(source,false))return@execute
                if(!autoAllowed()||source.requested!=epoch||captured!=generation||destroyed||networkRecovery!=null)return@execute
                if(ProbeCoordinator.measurements!=null){scheduleSteerHealth(source);return@execute}
                val healthy=(if(nativeRouteSession!=null)health()else AdaptivePolicyRuntime.measured(this,BatteryCostReport.Action.PROBE){steerHealth()})>=0;val previous=selectedId
                val policyTarget=policyRequestedTarget
                if(!fixtureMode&&policyTarget==null&&!adaptivePolicy.observe(previous,healthy)){scheduleSteerHealth(source);return@execute}
                if(holdForCaptivePortal(source,false))return@execute
                if(!fixtureMode&&policyTarget==null&&AutoSelectionDeviation.keepHealthy(healthy,AutoSelectionDeviation.failureOnly(preferences))){scheduleSteerHealth(source);return@execute}
                var replaced=false
                try {
                    val catalogue=Connections(this);catalogue.load()
                    val routes=RoutingStore(this);routes.load()
                    val state=runtimeRoutes {routes.snapshot()}
                    val pool=SteerAutomaticPool(ConnectionSections.entries(catalogue.autoCandidates(catalogue.entries()),false),catalogue.autoIds(),System.nanoTime().coerceAtLeast(1))
                    require(pool.candidates.isNotEmpty()) {"STEER_AUTOMATIC_NO_COMPATIBLE_MARKS"}
                    val native=AdaptivePolicyRuntime.measured(this,BatteryCostReport.Action.PROBE) { ProbeCoordinator.measureSteerCandidatesRuntime(this,pool.measurements,state,healthUrl) {
                        source.requested!=epoch||destroyed||currentService!==this||!autoAllowed()||captured!=generation
                    } }
                    if(source.requested!=epoch||captured!=generation||!autoAllowed())return@execute
                    if(holdForCaptivePortal(source,false))return@execute
                    val latest=Connections(this);latest.load()
                    val ranked=pool.stillMarked(pool.rank(native,SystemClock.elapsedRealtime(),if(healthy)null else previous),latest.entries(),latest.autoIds())
                    val next=ranked.firstOrNull {(policyTarget==null||it.id==policyTarget)&&(fixtureMode||adaptivePolicy.permitsTarget(it.id))}?:throw IOException("STEER_AUTOMATIC_NO_FRESH_SUCCESS")
                    if(policyTarget==null&&healthy&&(next.id==previous||previous in latest.autoIds()&&!SteerAutomaticPool.better(native.getValue(next.id),native[previous]?:-1,AutoSelectionDeviation.millis(preferences)))) {
                        scheduleSteerHealth(source);return@execute
                    }
                    val useDispatcher=RouteServerPlan.activeIds(state).isNotEmpty()
                    val prepared=if(useDispatcher)null else ClientPrimarySelection.steerSnapshot(this,state,true)
                    if(prepared!=null)require(prepared.supported) {"STEER_AUTOMATIC_CONFIG_CHANGED"}
                    val dispatch=if(useDispatcher)NativeRoutePreparation.capture(this,next,state,latest.entries())else null
                    require(SystemClock.elapsedRealtime()-native.completedAt.getValue(next.id) in 0..300000) {"STEER_AUTOMATIC_PROOF_EXPIRED"}
                    if(policyTarget!=null&&UsageQuotaStore.local(this).hits().none {it.rule.action!=UsageQuotaPolicy.Action.WARN}) {
                        policyRequestedTarget=null;scheduleSteerHealth(source);return@execute
                    }
                    if(!fixtureMode&&(adaptivePolicy.managed()||policyTarget!=null)&&!adaptivePolicy.admit(previous,next.id,!autoAllowed())) {
                        policyRequestedTarget=null;scheduleSteerHealth(source);return@execute
                    }
                    policyRequestedTarget=null
                    replaced=true
                    closeCore(keepTun=true)
                    if(source.requested!=epoch||!autoAllowed()||destroyed)return@execute
                    if(dispatch!=null)startSourceWithCore(dispatch.source(),null,true,source.port,source.applyRoutes,source.requested,true,null,"steer",dispatch,next,native)
                    else startSteerPrepared(source.template,prepareSteerSelected(next,state,requireNotNull(requireNotNull(prepared).settingsJson),source.requested,native),
                        source.port,source.applyRoutes,source.requested,true)
                    ClientEvents.record(this,"Автовыбор","Steer: выполнено переключение на проверенный отмеченный сервер")
                    scheduleSteerHealth(requireNotNull(currentStart))
                }catch(_:Exception){
                    if(policyTarget!=null&&!replaced) {
                        policyRequestedTarget=null
                        adaptivePolicy.warn("Лимит достигнут; альтернатива не подтверждена. Текущее подключение сохранено.")
                        if(source.requested==epoch&&autoAllowed()&&!destroyed)scheduleSteerHealth(source)
                        return@execute
                    }
                    if(holdForCaptivePortal(source,false))return@execute
                    if(source.requested==epoch&&autoAllowed()&&!destroyed&&currentService===this) {
                        if(replaced||!healthy||core==null){
                            closeCore(keepTun=true);currentStart=source
                            status="VPN · Steer: отмеченные серверы недоступны, трафик приостановлен";foreground(status)
                        }
                        scheduleSteerHealth(source)
                    }
                }
            }
        }
        healthTask=task;main.postDelayed(task,if(immediate)0L else adaptivePolicy.probeInterval(preferences.getInt("interval",60).coerceIn(5,86400)*1000L))
    }
    private fun health():Long {
        return AdaptivePolicyRuntime.measured(this,BatteryCostReport.Action.PROBE) {try{core?.health(target,healthUrl,10000)?:-1L}catch(_:Exception){-1L}}
    }
    private fun record(id:String,delay:Long){
        if(fixtureMode||CaptivePortalMonitor.blocksAutomatic(this))return
        bsHotMeasurements.remove(id);bsHotMeasurements[id]=SystemClock.elapsedRealtime() to delay
        if(bsHotMeasurements.size>1024)bsHotMeasurements.remove(bsHotMeasurements.keys.first())
        try{val catalogue=Connections(this);catalogue.load();catalogue.recordProbes(mapOf(id to delay))}catch(_:Exception){}
    }
    private fun readFixture(path:String):JSONObject {
        check(BuildConfig.DEBUG)
        fixtureStrictOwner=null
        fixtureSteerOwnerPin=null
        fixtureBsNativePhysical=null
        val file=File(path);val parent=file.parentFile ?: throw IOException("Missing fixture")
        require(parent.name.matches(Regex("mcrf-test-[0-9a-f-]{36}"))&&parent.canonicalFile.parentFile==cacheDir.canonicalFile&&file.name=="service-fixture.json"&&file.canonicalFile.parentFile==parent.canonicalFile&&
            !java.nio.file.Files.isSymbolicLink(parent.toPath())&&!java.nio.file.Files.isSymbolicLink(file.toPath()))
        val data=file.inputStream().use { JSONObject(String(ImportIO.read(it),Charsets.UTF_8)) }
        BsNativePhysicalServiceFixture.read(this,file,data)?.let { physical ->
            fixtureP1OwnerHelperSha=null
            fixtureRetirementHold=null
            fixtureConnectPending=false
            carrierTimeout=3000
            healthUrl="http://127.0.0.1:1/${physical.nonce}"
            fixtureBsNativePhysical=physical
            return physical.source
        }
        ClientP1StrictOwnerFixture.read(this,file,data)?.let { strict ->
            fixtureP1OwnerHelperSha=null
            fixtureRetirementHold=null
            fixtureConnectPending=false
            carrierTimeout=3000
            healthUrl=strict.healthUrl
            fixtureStrictOwner=strict
            return strict.source
        }
        fixtureP1OwnerHelperSha=if(data.has("p1OwnerHelperSha256"))data.getString("p1OwnerHelperSha256").also {
            require(!data.optBoolean("retirementFault",false)&&!data.optBoolean("connectPendingFault",false))
            ClientP1OwnerFixtureScope.packages(this,it)
        }else null
        // Private DEBUG fixtures may withhold a parent cleanup acknowledgement;
        // they never assert a fake worker death or alter ordinary user settings.
        fixtureRetirementHold=if(data.optBoolean("retirementFault",false))File(parent,"retirement-hold")else null
        fixtureConnectPending=data.optBoolean("connectPendingFault",false)
        carrierTimeout=data.optLong("timeout",60000).also { require(it in 1000..180000) }
        healthUrl=data.getString("url").also { val uri=java.net.URI(it);require(uri.scheme=="http"&&uri.host!=null&&RouteRules.literal(uri.host)!=null&&uri.userInfo==null) }
        val source=data.getJSONObject("source")
        if(data.has("p1SteerStrictOwner")){
            require(data.keys().asSequence().toSet()==setOf("source","url","timeout","p1OwnerHelperSha256","p1SteerStrictOwner")&&
                fixtureP1OwnerHelperSha!=null&&carrierTimeout==8000L)
            val pin=data.getJSONObject("p1SteerStrictOwner")
            require(pin.keys().asSequence().toSet()==setOf("directory","createdElapsed","moduleSha256")&&
                pin.getString("directory")==parent.name)
            val now=SystemClock.elapsedRealtime()
            require(pin.getLong("createdElapsed") in (now-60_000)..now)
            ClientSteerFixture.requireP1ProxySnapshot(source.getJSONObject("mcrf_steer_fixture").getJSONObject("snapshot"),
                healthUrl.substringAfterLast('/'))
            val expected=pin.getString("moduleSha256")
            val module=EngineUpdatePrimary.installation(this,"steer")
            require(expected.matches(Regex("[0-9a-f]{64}"))&&ClientPrimaryWorkerWire.hashFile(File(module.sourceDir))==expected) {
                "STEER_STRICT_FIXTURE_MODULE_PIN"
            }
            fixtureSteerOwnerPin=expected
        }
        return source
    }
    private fun chooseEngine(source:JSONObject,candidates:List<JSONObject>,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long):JSONObject {
        val ordered=if(!fixtureMode&&preferences.getBoolean(BsDetectionMonitor.KEY,false)&&source.optJSONObject("mcrf_bs")?.optBoolean("connectionBound",false)!=true)
            BsAutoSelectionPolicy.targets(candidates,BsAutoSelectionPolicy.last(preferences,true),BsAutoSelectionPolicy.selected(preferences)){it.getString("id")}else candidates
        for(candidate in ordered){
            if(requested!=epoch) throw IOException("Подключение отменено")
            if(CaptivePortalMonitor.blocksAutomatic(this))throw IOException("NETWORK_VALIDATION_PENDING")
            try{startSource(source,candidate,vpn,port,applyRoutes,requested,true);return candidate}
            catch(error:ConnectionStartPolicy.MixedSteerRequiresManualVpn){throw error}
            catch(_:Exception){record(candidate.getString("id"),-1L);closeCore(vpn)}
        }
        throw IOException("Отмеченные движки БС не подтвердили передачу данных.")
    }
    private fun refreshBsPool(source:JSONObject,requested:Long):Pair<JSONObject,List<JSONObject>> {
        val fresh=if(fixtureMode)source else {
            val catalogue=Connections(this).apply {load()}
            if(source.optJSONObject("mcrf_bs")?.optBoolean("connectionBound",false)==true)prepareAutoBsTemplate()
            else BsPlan.refresh(source,catalogue.bsStartupEntries(),preferences.getInt("interval",60).coerceIn(5,86400))
        }
        if(requested!=epoch||destroyed||!autoAllowed())throw IOException("Автовыбор отменён")
        val candidates=BsPlan.candidates(fresh.getJSONObject("mcrf_bs"))
        currentStart=currentStart?.takeIf {it.requested==requested}?.copy(template=fresh)?:currentStart
        return fresh to candidates
    }
    private fun scheduleHealth(source:JSONObject,candidates:List<JSONObject>,selected:JSONObject,interval:Int,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long){
        if (!autoAllowed() || networkRecovery!=null) return
        val session=generation
        val task=Runnable {
            if(autoAllowed()&&networkRecovery==null&&requested==epoch&&session==generation&&core!=null) worker.execute {
                if(currentStart?.let {holdForCaptivePortal(it,false)}==true)return@execute
                if(!autoAllowed()||networkRecovery!=null||requested!=epoch||session!=generation) return@execute
                val refreshed=try{refreshBsPool(source,requested)}catch(_:Exception){
                    // Empty marks/storage failure must not resurrect the stale launch pool or stop healthy flows.
                    if(autoAllowed()&&requested==epoch&&session==generation)
                        scheduleHealth(source,candidates,selected,interval,vpn,port,applyRoutes,requested)
                    return@execute
                }
                val freshSource=refreshed.first;val freshCandidates=refreshed.second
                val freshInterval=freshSource.getJSONObject("mcrf_bs").getInt("interval")
                val delay=health()
                if(currentStart?.let {holdForCaptivePortal(it,false)}==true)return@execute
                record(selected.getString("id"),delay)
                if(!fixtureMode&&!adaptivePolicy.observe(selected.getString("id"),delay>=0)) {
                    scheduleHealth(freshSource,freshCandidates,selected,freshInterval,vpn,port,applyRoutes,requested);return@execute
                }
                if (!autoAllowed() || networkRecovery!=null || requested!=epoch || session!=generation) return@execute
                val reconsidered=reconsiderCarrier(freshCandidates,selected,delay,requested,session)
                if(reconsidered!=null) scheduleHealth(freshSource,freshCandidates,reconsidered,freshInterval,vpn,port,applyRoutes,requested)
                else if(delay>=0) scheduleHealth(freshSource,freshCandidates,selected,freshInterval,vpn,port,applyRoutes,requested)
                else try{
                    val permitted=freshCandidates.filter { it.getString("id")!=selected.getString("id")&&(fixtureMode||adaptivePolicy.permitsTarget(it.getString("id"))) }
                    val alternatives=if(!fixtureMode&&adaptivePolicy.settings().let {it.adaptivePolicyEnabled&&it.prioritizeRealtime})
                        permitted.sortedBy {candidate->bsHotMeasurements[candidate.getString("id")]?.takeIf {SystemClock.elapsedRealtime()-it.first in 0..300_000&&it.second>=0}?.second?:Long.MAX_VALUE}
                        else permitted
                    val policyActive=!fixtureMode&&adaptivePolicy.managed()
                    if(policyActive&&(alternatives.isEmpty()||!adaptivePolicy.admit(selected.getString("id"),alternatives.first().getString("id"),!autoAllowed()))) {
                        scheduleHealth(freshSource,freshCandidates,selected,freshInterval,vpn,port,applyRoutes,requested);return@execute
                    }
                    status="Переключаем движок БС…";foreground(status)
                    val next=chooseEngine(freshSource,if(policyActive)alternatives.take(1)else alternatives,vpn,port,applyRoutes,requested)
                    scheduleHealth(freshSource,freshCandidates,next,freshInterval,vpn,port,applyRoutes,requested)
                }catch(_:Exception){if(requested==epoch){closeCore(vpn);if(requested==epoch&&!destroyed&&currentService===this){status=if(vpn) "VPN подключён · движки БС недоступны, трафик приостановлен" else "Прокси 127.0.0.1:$port · движки БС недоступны";foreground(status);scheduleRetry(freshSource,freshCandidates,freshInterval,vpn,port,applyRoutes,requested)}}}
            }
        }
        healthTask=task;main.postDelayed(task,if(fixtureMode)interval*1000L else adaptivePolicy.probeInterval(preferences.getInt("interval",60).coerceIn(5,86400)*1000L))
    }
    /** Make-before-break for new flows; old flows drain on their exact process owner. */
    private fun reconsiderCarrier(candidates:List<JSONObject>,selected:JSONObject,currentDelay:Long,requested:Long,session:Long):JSONObject? {
        val board=carrierSwitchboard?:return null
        fun live()=autoAllowed()&&requested==epoch&&session==generation&&networkRecovery==null&&!destroyed
        if(!live()||ProbeCoordinator.measurements!=null)return null
        if(!fixtureMode&&AutoSelectionDeviation.keepHealthy(currentDelay>=0,AutoSelectionDeviation.failureOnly(preferences)))return null
        if(!fixtureMode&&preferences.getBoolean(BsDetectionMonitor.KEY,false)&&BsAutoSelectionPolicy.selected(preferences)==BsAutoSelectionPolicy.OFF)return null
        board.retireDrained()
        val currentKind=selected.getJSONObject("profile").getString("type")
        val currentTier=AutoSelection.ENGINE_PRIORITY.indexOf(currentKind)
        val currentMarked=candidates.any {it.getString("id")==selected.getString("id")}
        if(preferences.getBoolean(BsDetectionMonitor.KEY,false)&&BsAutoSelectionPolicy.selected(preferences)==BsAutoSelectionPolicy.RECENT&&currentDelay>=0&&currentMarked)return null
        val eligible=candidates.filter {candidate->
            candidate.has("profile")&&
            candidate.getString("id")!=selected.getString("id")&&
                (fixtureMode||adaptivePolicy.permitsTarget(candidate.getString("id")))&&
                (!currentMarked||currentDelay<0||AutoSelection.ENGINE_PRIORITY.indexOf(candidate.getJSONObject("profile").getString("type"))<=currentTier)
        }
        val higher=eligible.filter {!currentMarked||currentDelay<0||AutoSelection.ENGINE_PRIORITY.indexOf(it.getJSONObject("profile").getString("type"))<currentTier}
        val now=SystemClock.elapsedRealtime()
        val retryAge=maxOf(60000L,preferences.getInt("interval",60).toLong()*2000)
        fun fresh(id:String)=bsHotMeasurements[id]?.let {now-it.first in 0 until retryAge}==true
        val knownHigher=higher.filter {fresh(it.getString("id"))&&bsHotMeasurements[it.getString("id")]!!.second>=0}
        val dueHigher=higher.filter {!fresh(it.getString("id"))}
        val priorityChecks=(knownHigher+dueHigher).distinctBy {it.getString("id")}
        // Do not optimize latency within a lower tier while a known-live or untested higher tier is waiting.
        val pending=priorityChecks.ifEmpty {
            if(!currentMarked||currentDelay<0)emptyList() else
            eligible.filter {it.getJSONObject("profile").getString("type")==currentKind}
        }
        val firstTier=pending.minOfOrNull {AutoSelection.ENGINE_PRIORITY.indexOf(it.getJSONObject("profile").getString("type"))}
        val choices=pending.filter {AutoSelection.ENGINE_PRIORITY.indexOf(it.getJSONObject("profile").getString("type"))==firstTier}
        if(choices.isEmpty())return null
        // Bound background starts; rotate so permanently failed early entries cannot starve the rest.
        val start=Math.floorMod(bsCandidateCursor,choices.size);bsCandidateCursor=(start+2)%choices.size
        for(offset in 0 until minOf(2,choices.size)){
            if(!live())return null
            val candidate=choices[(start+offset)%choices.size];val id=candidate.getString("id")
            var created:NativeCarrier?=null
            var adopted=false
            try{
                val existing=board.carrierFor(id)
                if(existing==null&&!board.canAdmit())continue // Never evict an old stream to free a slot.
                val transport=existing?:NativeCarrier.startBackground(this,JSONObject(candidate.getJSONObject("profile").toString()),
                    java.util.function.BooleanSupplier {!live()},minOf(carrierTimeout,5000)).also {created=it}
                val delay=AdaptivePolicyRuntime.measured(this,BatteryCostReport.Action.PROBE){CarrierSocksSwitch.probe(transport,healthUrl,3000)};record(id,delay)
                if(!live()||!AutoSelection.prefer(candidate.getJSONObject("profile").getString("type"),delay,currentKind,if(currentMarked)currentDelay else -1L,AutoSelectionDeviation.millis(preferences)))continue
                if(!fixtureMode&&adaptivePolicy.managed()&&!adaptivePolicy.admit(selected.getString("id"),id,!autoAllowed()))return null
                if(existing!=null)board.selectExisting(id)else board.select(id,transport)
                adopted=true;carrier=transport;selectedId=id
                val consumer=if(existing!=null)streamBoardConsumers[id]else StreamConsumer(
                    transport.streamProfile()?:RouteServerSession.streamProfile(this,candidate.getJSONObject("profile"))
                        .put(CarrierStreamRestartPolicy.UNVERIFIED,true))
                if(consumer!=null){streamBoardConsumers[id]=consumer;streamRuntime=streamRuntime?.copy(main=consumer)}
                currentStart=currentStart?.copy(candidate=JSONObject(candidate.toString()))
                TrafficState.handover(candidate.getString("name"))
                ClientEvents.record(this,"Клиент","Автовыбор БС: новые соединения переключены по приоритету/порогу; уже открытые соединения сохраняют прежний транспорт.")
                return candidate
            }catch(_:Exception){if(live())record(id,-1L)}
            finally{if(!adopted)created?.close()}
        }
        return null
    }
    private fun scheduleRetry(source:JSONObject,candidates:List<JSONObject>,interval:Int,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long){
        if (!autoAllowed() || networkRecovery!=null) return
        val session=generation
        val task=Runnable { if(autoAllowed()&&networkRecovery==null&&requested==epoch&&session==generation) worker.execute {
            if(!autoAllowed()||networkRecovery!=null||requested!=epoch||session!=generation)return@execute
            if(holdForCaptivePortal(RuntimeStart(source,null,vpn,port,applyRoutes,requested)))return@execute
            try{
                val (freshSource,freshCandidates)=refreshBsPool(source,requested)
                val next=chooseEngine(freshSource,freshCandidates,vpn,port,applyRoutes,requested)
                scheduleHealth(freshSource,freshCandidates,next,freshSource.getJSONObject("mcrf_bs").getInt("interval"),vpn,port,applyRoutes,requested)
            }
            catch(_:Exception){if(requested==epoch){closeCore(vpn);if(requested==epoch&&!destroyed&&currentService===this)scheduleRetry(source,candidates,interval,vpn,port,applyRoutes,requested)}}
        } }
        healthTask=task;main.postDelayed(task,if(fixtureMode)interval*1000L else adaptivePolicy.probeInterval(preferences.getInt("interval",60).coerceIn(5,86400)*1000L))
    }
    private fun autoAllowed() = !directTunOwned&&!directTunStarting&&directTunTransition==null&&profilePolicyCurrent()&&(!autoDisabled||!fixtureMode&&ProfilePolicyRuntime.active(this)?.configuration!=null) && (fixtureMode || preferences.getBoolean("automatic", false)&&BsAutoSelectionPolicy.selected(preferences)!=BsAutoSelectionPolicy.OFF)
    private fun freezeSelection() {
        val server = core ?: return
        if(server.choiceId=="steer"||sessionPrimaryChoice=="steer"&&nativeRouteSession!=null) {
            steerFrozenId=selectedId
            ClientEvents.record(this,"Автовыбор","Steer: выключен, текущий сервер сохранён без перезапуска")
            return
        }
        val requested=epoch
        try {
            val frozen = JSONObject((runningConfig?:return).toString())
            val outputs = frozen.getJSONArray("outbounds")
            var changed = false
            repeat(outputs.length()) { i -> val node = outputs.getJSONObject(i)
                if (node.optString("tag") == target && node.optString("type") == "urltest") {
                    val members = node.getJSONArray("outbounds")
                    val chosen = server.selectedOutbound(target).takeIf { value -> (0 until members.length()).any { members.getString(it) == value } }
                        ?: throw IOException("Не удалось определить выбранный сервер")
                    selectedOutbound=chosen
                    node.put("type", "selector").put("default", chosen).put("interrupt_exist_connections", false)
                    listOf("url", "interval", "tolerance", "idle_timeout").forEach(node::remove); changed = true
                }
            }
            if (changed) {
                val session=currentStart?:throw IOException("Нет активного подключения")
                startSource(session.template,session.candidate,session.vpn,session.port,session.applyRoutes,session.requested,true,selectedOutbound)
            }
            ClientEvents.record(this, "Автовыбор", "Выключен · текущий сервер сохранён")
        } catch (_: Exception) {
            // Never silently continue an automatic URL-test after it was switched off.
            closeCore()
            if(requested!=epoch||destroyed||currentService!==this)return
            status = "Отключено"; if (!fixtureMode) preferences.edit().putBoolean("wantedRunning", false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            ClientEvents.record(this, "Автовыбор", "Выключен · выбери сервер для ручного подключения")
        }
    }
    private var lastForegroundContent:Triple<String,String,String>?=null
    private var foregroundChannelCreated=false
    @Synchronized private fun foreground(message: String) {
        McrfQuickTile.connectionChanged(applicationContext)
        if(!fixtureMode)ManagedAppFreezeUi.onTunnelChanged(this)
        if (!fixtureMode && !destroyed) {
            val connected = vpnMode && energySessionAlive() && status.startsWith("VPN подключён") &&
                !status.contains("приостановлен")
            if(connected&&!safeModeActive&&selectedId.isNotBlank()&&
                preferences.getString(AppLaunchShortcuts.LAST_VPN_SERVER,null)!=selectedId){
                preferences.edit().putString(AppLaunchShortcuts.LAST_VPN_SERVER,selectedId).apply()
            }
            val token = if (connected && !safeModeActive) ServerUsage.token(this) else null
            val id = if (token != null) usageServer(aggregate = false) else null
            if (token != null && id != null) {
                val owner = generation
                val connection = Triple(owner, id, token)
                if (connection != recordedUsageConnection) {
                    journalWorker.execute {
                        if (!destroyed && currentService === this && generation == owner && !safeModeActive) {
                            serverUsage.connected(owner, id, token)
                        }
                    }
                    recordedUsageConnection = connection
                }
            } else recordedUsageConnection = null
        }
        val state = TrafficState.current
        val counters=preferences.getBoolean(NotificationTrafficSettings.ENABLED,true)
        val policyNotice=adaptivePolicy.notice
        val presentation=ConnectionNotification.content(state,if(policyNotice.isBlank())message else "$message · $policyNotice",!vpnMode,counters)
        val content=Triple(presentation.title,presentation.text,presentation.expanded)
        if(lastForegroundContent==content)return
        if(!foregroundChannelCreated){
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("vpn", "Подключение MCRF", NotificationManager.IMPORTANCE_LOW))
            foregroundChannelCreated=true
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, McrfVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = AndroidNotification.Builder(this, "vpn").setContentTitle(presentation.title)
            .setContentText(presentation.text)
            .setStyle(AndroidNotification.BigTextStyle().bigText(presentation.expanded))
            .setSmallIcon(R.drawable.status_icon).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(AndroidNotification.Action.Builder(null, "Остановить", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, notification)
        lastForegroundContent=content
    }
    private fun connectPrimary(choice:String,runGeneration:Long,platform:Platform,onDeath:()->Unit):ClientPrimaryWorker = try {
        val connected=ConnectTiming.measure(this,"core_init"){ClientPrimaryWorker.connect(this,choice,runGeneration,platform,onDeath=onDeath)}
        runningPrimaryIdentity=primaryIdentity(choice)
        if(BuildConfig.DEBUG&&fixtureMode&&fixtureConnectPending){
            fixtureConnectPending=false
            throw ClientPrimaryWorker.CleanupPending(connected)
        }
        connected.also {core=it}
    }catch(pending:ClientPrimaryWorker.CleanupPending){
        // connect can fail after authentication while its worker is still alive.
        // Adopt its exact owner before the outer start catch enters retirement.
        core=pending.worker
        throw pending
    }
    private fun closeCore(keepTun:Boolean=false) {
        ownerIsolationUpgradePending=false
        streamRuntime=null;streamBoardConsumers.clear()
        policyRequestedTarget=null
        policySelector=false
        runCatching {UsageQuotaStore.local(this).flush(true)}
        journalWorker.execute {runCatching {TrafficJournalStore.local(this).clearDnsCorrelations()}}
        runtimeHealth.stopped(HealthDisconnect.UNKNOWN)
        routeBindingSelection=null
        steerProbeEpoch=-1L
        releaseBsPowerLease()
        currentStart=null
        steerVpnNetwork=null
        packetVpnNetwork=null
        healthTask?.let(main::removeCallbacks);healthTask=null
        generation++; TrafficState.stop()
        if(!fixtureMode)ServerUsage.flush(this)
        val client = telemetry; telemetry = null; if(client!=null)main.removeCallbacks(client)
        notificationTraffic?.close();notificationTraffic=null
        runningConfig=null;runningDirectDnsIpv4=null
        val batch=retirement?:run {
            val previous=core
            val direct=directTunSession
            val transport=carrier
            val board=carrierSwitchboard
            val extra=optionalSession
            val compact=compactSession
            val standalone=standaloneProtocol
            val mixedRoutes=mixedRouteSession
            val routeExtras=routeServerSession
            val nativeRoutes=nativeRouteSession
            val nativePlatforms=nativeRoutePlatforms
            val bootstrap=nativeRouteBootstrap
            val usage=engineUsage
            val hold=fixtureRetirementHold.takeIf {BuildConfig.DEBUG&&fixtureMode}
            ClientPrimaryRetirement(previous,
                requestStop={previous?.requestStop();direct?.requestStop()},
                retireCallbacks={closePlatform(keepTun=true)},
                closeOwner={
                    if(hold!=null){
                        require(!java.nio.file.Files.isSymbolicLink(hold.toPath()))
                        check(!hold.exists()) {"DEBUG_PARENT_RETIREMENT_ACK_HELD"}
                    }
                    it.close()
                },
                dependents=listOf({direct?.close();Unit},{closePlatform(keepTun)},{board?.close();transport?.close();Unit},{bootstrap?.close();Unit},{nativeRoutes?.close();Unit},{nativePlatforms?.close();Unit},{routeExtras?.close();Unit},{extra?.close();Unit},{compact?.close();Unit},{standalone?.worker?.close();Unit},{mixedRoutes?.close();Unit},{usage?.close();Unit}),
                completed={
                    if(core===previous)core=null
                    if(directTunSession===direct){directTunSession=null;directTunEffective=null}
                    directTunOwned=false
                    directTunStarting=false
                    if(carrier===transport)carrier=null
                    if(carrierSwitchboard===board)carrierSwitchboard=null
                    if(optionalSession===extra)optionalSession=null
                    if(compactSession===compact)compactSession=null
                    if(standaloneProtocol===standalone)standaloneProtocol=null
                    if(mixedRouteSession===mixedRoutes)mixedRouteSession=null
                    if(routeServerSession===routeExtras)routeServerSession=null
                    if(nativeRouteSession===nativeRoutes)nativeRouteSession=null
                    if(nativeRoutePlatforms===nativePlatforms)nativeRoutePlatforms=null
                    if(nativeRouteBootstrap===bootstrap)nativeRouteBootstrap=null
                    if(engineUsage===usage)engineUsage=null
                }).also {retirement=it}
        }
        var interrupted=Thread.interrupted()
        fun pause(delay:Long){try{Thread.sleep(delay)}catch(_:InterruptedException){interrupted=true}}
        fun pendingStatus(){
            if(!destroyed&&currentService===this&&status!="Остановка движка…"){
                status="Остановка движка…"
                try{foreground(status)}catch(_:Exception){}
            }
        }
        try {
            // This executor is shut down (not interrupted) onDestroy. Its current
            // task and retained bundle survive until real worker death/cleanup.
            // New STARTs stay queued; another Service instance sees the same gate.
            while(!batch.attempt()){pendingStatus();pause(batch.retryDelayMillis())}
            if(retirement===batch)retirement=null
            while(!destroyed&&(ClientPrimaryRetirement.hasPending()||retiringServices.any {it!==this})){
                pendingStatus();pause(100)
            }
        }finally{
            if(interrupted)Thread.currentThread().interrupt()
        }
    }
    private fun primaryIdentity(choice:String):String=if(choice==BsNativeContract.CHOICE)BsNativeContract.pin(this)else ClientPrimaryWorkerWire.hashFile(
        File(EngineUpdatePrimary.installation(this,EngineUpdatePrimary.kindForChoice(choice)).sourceDir))
    private fun retireFailedMainTransport(detail:String,requested:Long) {
        if(requested!=epoch||destroyed||currentService!==this)return
        val session=currentStart?:return
        beginRecovery(session,RecoveryPolicy.classify(IOException(detail),DirectAccessCheck.physical(this)!=null),true)
    }
    private fun usageServer(aggregate: Boolean): String? {
        val id = selectedId.takeIf(String::isNotBlank) ?: return null
        if (directTunOwned || sessionPrimaryChoice == WgAddonPolicy.KIND) return id
        val config = runningConfig ?: return if (core?.choiceId == "steer") id else null
        val final = UsageServerAttribution.connectedOutput(config, selectedOutbound) ?: return null
        if (ids[final] != id && (final != target || currentStart?.candidate?.optString("id") != id)) return null
        if (aggregate && (carrierSwitchboard != null || policySelector || !UsageServerAttribution.permits(config, final))) return null
        return id
    }

    private fun accountServerUsage(owner: Long, id: String?, tx: Long, rx: Long) {
        if (fixtureMode || safeModeActive || !vpnMode) return
        val token = ServerUsage.token(this) ?: return
        journalWorker.execute {
            if (!destroyed && generation == owner && currentService === this && !safeModeActive) {
                serverUsage.sample(owner, id, tx, rx, token)
            }
        }
    }

    private fun accountPolicyCounters(owner:Long,tx:Long,rx:Long) {
        if(fixtureMode||tx<0||rx<0||safeModeActive)return
        // Aggregate engine counters may include Direct/rule outbounds. Do not attribute them
        // to the selected server unless this session has a single un-routed main output.
        val id=if(currentStart?.applyRoutes==false)selectedId else UsageQuotaPolicy.UNKNOWN
        val token = if (vpnMode) ServerUsage.token(this) else null
        val usageId = if (token != null) usageServer(aggregate = true) else null
        try {journalWorker.execute {
            if(!destroyed&&generation==owner&&currentService===this) {
                runCatching {adaptivePolicy.counters(owner.toString(),tx,rx,id)}
                if (token != null && !safeModeActive) serverUsage.sample(owner, usageId, tx, rx, token)
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException){}
    }
    /** Runs on the existing service worker. Resource policy can delay optional work, never stop VPN. */
    private fun policyTick(current:ClientPrimaryWorker,owner:Long,requested:Long) {
        if(fixtureMode||safeModeActive||networkRecovery!=null)return
        fun live()=owner==generation&&requested==epoch&&core===current&&!destroyed&&currentService===this&&profilePolicyCurrent()
        val now=SystemClock.elapsedRealtime()
        val ledger=UsageQuotaStore.local(this)
        val accounting=adaptivePolicy.accountingEnabled()
        if(!accounting){adaptivePolicy.clearNotice();policyRequestedTarget=null}
        if(accounting&&(policyCounterAt==Long.MIN_VALUE||now-policyCounterAt>=15_000)) {
            policyCounterAt=now
            val stats=AdaptivePolicyRuntime.measured(this,BatteryCostReport.Action.COUNTER_SAMPLE){current.stats()}
            val tx=stats.txPayload.value;val rx=stats.rxPayload.value
            if(live()&&tx!=null&&rx!=null)accountPolicyCounters(owner,tx,rx)
        }
        if(!live())return
        for(hit in ledger.hits()) {
            if(!ledger.claim(hit))continue
            if(hit.rule.action==UsageQuotaPolicy.Action.WARN) {
                adaptivePolicy.warn("Локальный лимит трафика достигнут; VPN продолжает работать.");continue
            }
            if(ProfilePolicyRuntime.active(this)?.configuration!=null||!autoAllowed()) {adaptivePolicy.warn("Лимит достигнут. Приоритет текущего выбора сохранён; VPN продолжает работать.");continue}
            val catalogue=Connections(this).apply {load()}
            val pool=catalogue.autoCandidates(catalogue.entries()).filter {it.id in catalogue.autoIds()}
            val candidates=adaptivePolicy.quotaCandidates(pool,catalogue.probes(),selectedId,hit,catalogue.subscriptions())
            val board=carrierSwitchboard
            val group=runningConfig?.optJSONArray("outbounds")?.let {nodes->(0 until nodes.length()).map {nodes.getJSONObject(it)}.firstOrNull {it.optString("tag")==target&&it.optString("type")=="selector"}}
            val members=group?.optJSONArray("outbounds")?.let {nodes->(0 until nodes.length()).map {nodes.getString(it)}.toSet()}.orEmpty()
            val next=candidates.firstOrNull {entry->
                when {board!=null->board.carrierFor(entry.id)!=null
                    current.choiceId=="steer"&&nativeRouteSession==null->SteerAutomaticPool.compatible(entry)
                    policySelector->ServerImport.tag(entry) in members
                    else->false}
            }
            if(next==null) {adaptivePolicy.warn("Лимит достигнут; нет проверенной совместимой альтернативы. VPN продолжает работать.");continue}
            catalogue.load()
            if(!live()||!autoAllowed()||!ledger.enabled()||next.id !in catalogue.autoIds()) {
                adaptivePolicy.warn("Лимит достигнут; выбор изменён вручную. Текущее подключение сохранено.");continue
            }
            if(current.choiceId=="steer"&&nativeRouteSession==null) {
                policyRequestedTarget=next.id
                currentStart?.let {scheduleSteerHealth(it,immediate=true)}
                break
            }
            if(!live()||!adaptivePolicy.admit(selectedId,next.id,!autoAllowed())) {
                adaptivePolicy.warn("Лимит достигнут; переключение отложено защитой от повторов. VPN продолжает работать.");continue
            }
            AdaptivePolicyRuntime.measured(this,BatteryCostReport.Action.SWITCH) {
                if(board!=null) {board.selectExisting(next.id);carrier=board.carrierFor(next.id)
                    val candidate=currentStart?.template?.optJSONObject("mcrf_bs")?.let(BsPlan::candidates)?.firstOrNull {it.getString("id")==next.id}
                    if(candidate!=null)currentStart=currentStart?.copy(candidate=candidate)
                } else current.selectOutbound(target,ServerImport.tag(next))
            }
            if(live()) {selectedId=next.id;TrafficState.server(next.name);adaptivePolicy.warn("Лимит достигнут; выбран другой отмеченный сервер. Открытые соединения сохраняются.")}
            break
        }
        if(!live()||!policySelector||!autoAllowed()||policyRequestedTarget!=null)return
        val interval=adaptivePolicy.probeInterval(preferences.getInt("interval",60).coerceIn(5,86400)*1000L)
        if(policyProbeAt!=Long.MIN_VALUE&&now-policyProbeAt<interval)return
        policyProbeAt=now
        val delay=health()
        if(!live()||!adaptivePolicy.observe(selectedId,delay>=0))return
        record(selectedId,delay)
        if(delay>=0&&AutoSelectionDeviation.failureOnly(preferences))return
        val catalogue=Connections(this).apply {load()}
        val group=runningConfig?.getJSONArray("outbounds")?.let {nodes->(0 until nodes.length()).map {nodes.getJSONObject(it)}.firstOrNull {it.optString("tag")==target}}
        val tags=group?.optJSONArray("outbounds")?.let {nodes->(0 until nodes.length()).map {nodes.getString(it)}.toSet()}.orEmpty()
        val entries=catalogue.entries().filter {it.id in catalogue.autoIds()&&it.id!=selectedId&&ServerImport.tag(it) in tags&&AutoSelection.eligible(it)&&adaptivePolicy.permitsTarget(it.id)}
        // Two probes per round at most; conservative budgets reduce the round frequency.
        if(entries.isEmpty())return
        val start=Math.floorMod(policyCandidateCursor,entries.size);policyCandidateCursor=(start+2)%entries.size
        val round=(0 until minOf(2,entries.size)).map {entries[(start+it)%entries.size]}
        val measured=round.associate {entry->entry.id to AdaptivePolicyRuntime.measured(this,BatteryCostReport.Action.PROBE){current.health(ServerImport.tag(entry),healthUrl,3000)}}
        if(!live()||!autoAllowed())return
        catalogue.recordProbes(measured)
        catalogue.load()
        val next=catalogue.autoCandidates(entries).filter {it.id in catalogue.autoIds()&&(measured[it.id]?:-1)>=0}.minByOrNull {measured.getValue(it.id)}?:return
        if(delay>=0&&measured.getValue(next.id)+AutoSelectionDeviation.millis(preferences)>=delay)return
        if(!adaptivePolicy.admit(selectedId,next.id,!autoAllowed()))return
        AdaptivePolicyRuntime.measured(this,BatteryCostReport.Action.SWITCH){current.selectOutbound(target,ServerImport.tag(next))}
        if(live()){selectedId=next.id;TrafficState.server(next.name)}
    }
    private fun startTelemetry(catalogue:List<ServerImport.Server>?=null) {
        scheduleBsWifiCheck()
        val session = generation
        val requested = epoch
        val entries=runCatching {(catalogue?:Connections(this).apply{load()}.entries()).associateBy {it.id}}.getOrDefault(emptyMap())
        var nativeStreams=-1
        fun publishConnection(){
            if(session!=generation||requested!=epoch)return
            val entry=entries[selectedId]?:return
            val turn=entry.protocol in SwgProfile.TYPES
            val streams=if(!turn)-1 else when {
                core?.choiceId==BsNativeContract.CHOICE->if(nativeStreams>=0)nativeStreams else carrier?.actualStreams()?:-1
                carrierSwitchboard!=null->carrierSwitchboard?.carrierFor(entry.id)?.actualStreams()?:-1
                routeServerSession!=null->routeServerSession?.actualStreams(entry.id)?:-1
                else->carrier?.actualStreams()?:-1
            }
            TrafficState.connection(entry.name,turn,if(turn)TurnStreams.serverIp(entry.host)else "",streams)
        }
        publishConnection()
        notificationTraffic?.close()
        val fixedOutbound=OutboundObservation.fixed(runningConfig,target)
        // Route defaults may send otherwise-unmatched traffic directly instead of
        // through the profile's original target. Only the actual compiled final
        // outbound may turn an optional-worker failure into main-session failure.
        val fixedFinalOutbound=OutboundObservation.fixedFinal(runningConfig)
        fun live()=session==generation&&requested==epoch&&core!=null&&!destroyed&&currentService===this@McrfVpnService
        notificationTraffic=try{NotificationTrafficSampler(this,main,worker,{live()&&TrafficState.current.countersSupported},{
            val current=core
            if(live()&&current!=null){
                val stats=current.stats {streams->
                    if(live()&&core===current){nativeStreams=streams;publishConnection()}
                }
                val tx=stats.txPayload.value;val rx=stats.rxPayload.value
                if(live()&&core===current&&tx!=null&&rx!=null) {
                    TrafficState.update(tx,rx,SystemClock.elapsedRealtime());accountPolicyCounters(session,tx,rx)
                }
            }
        },{if(live())foreground(status)})}catch(error:Exception){
            ClientEvents.record(this,"Статистика","Опрос счётчиков не запущен; соединение продолжает работать. ${ClientEvents.redact(error.message)}")
            null
        }
        val task=object:Runnable {override fun run(){
            if(session!=generation||requested!=epoch||core==null||worker.isShutdown||destroyed||currentService!==this@McrfVpnService)return
            worker.execute {try {
                if(session!=generation||requested!=epoch||destroyed||networkRecovery!=null||currentService!==this@McrfVpnService)return@execute
                val current=core?:return@execute
                runCatching {policyTick(current,session,requested)}.onFailure {
                    val now=SystemClock.elapsedRealtime()
                    if(policyErrorAt==Long.MIN_VALUE||now-policyErrorAt>=60_000) {
                        policyErrorAt=now
                        runCatching {adaptivePolicy.warn("Политика временно недоступна; текущее подключение сохранено.")}
                    }
                }
                if(currentStart?.let {checkFallbackChain(it,current)}==true)return@execute
                recordHealthyConnection()
                routeBindingSelection?.tick(SystemClock.elapsedRealtime(),{tag->current.health(tag,healthUrl,2500)},{group,tag->current.selectOutbound(group,tag)},{!live()||core!==current||!profilePolicyCurrent()})
                // A live primary SOCKS/TUN does not mean the external carrier is
                // alive. Manual sessions previously kept a green status after
                // the owned transport died. BS sessions retain their existing
                // measured failover policy, rather than being stopped here.
                val automaticCarrier=currentStart?.candidate!=null&&autoAllowed()
                routeServerSession?.reportFailures {
                    ClientEvents.record(this@McrfVpnService,"Маршруты","Сервер отдельного правила остановился. Его трафик не переводится на другой сервер; остальные маршруты продолжают работать.")
                }
                nativeRouteSession?.reportFailures {tag->
                    ClientEvents.record(this@McrfVpnService,"Маршруты","Сервер Steer отдельного выхода остановился. Его трафик не переводится на другой сервер.")
                    if(tag==target){
                        status="VPN · Steer: основной сервер недоступен, трафик приостановлен";foreground(status)
                        currentStart?.takeIf {autoAllowed()}?.let {scheduleSteerHealth(it,immediate=true)}
                    }
                }
                mixedRouteSession?.reportFailures {detail->
                    ClientEvents.record(this@McrfVpnService,"Routes",detail)
                }
                optionalSession?.reportFailures {tag->
                    ClientEvents.record(this@McrfVpnService,"Маршруты",if(tag.startsWith("mcrf-route-"))
                        "Модуль сервера отдельного правила остановился. Трафик этого правила не переводится на основной сервер."
                        else "Дополнительный модуль остановился. Его выходы не заменяются другим сервером автоматически.")
                    val detail=optionalSession?.failureDetail(tag)
                    detail?.let {ClientEvents.record(this@McrfVpnService,"Ошибка движка",it)}
                }
                // Notification delivery is one-shot; main-session health must remain
                // observable on subsequent ticks even if a prior reporting step failed.
                val extras=optionalSession
                val dead=extras?.deadTags().orEmpty()
                val primaryOptionalFailure=fixedFinalOutbound?.takeIf {it in dead}?.let {tag->
                    "Дополнительное ядро основного выхода остановилось. ${extras?.failureDetail(tag).orEmpty()}"
                }
                val standaloneFailure=standaloneProtocol?.worker?.takeUnless(OptionalCoreProcess::isAlive)?.let {
                    it.diagnostic("standalone protocol stopped") ?: "Основной протокольный выход остановился."
                }
                val transportFailure=standaloneFailure?:primaryOptionalFailure ?: if(automaticCarrier)null else carrier?.runtimeFailure()
                if(transportFailure!=null){
                    retireFailedMainTransport(transportFailure,requested)
                    return@execute
                }
                val outbound=if(current.choiceId=="singbox")fixedOutbound?:current.selectedOutbound(target)else""
                if(session==generation&&outbound.isNotEmpty()){
                    selectedOutbound=outbound
                    val hot=currentStart?.candidate?.takeIf {carrierSwitchboard!=null}
                    if(hot!=null){selectedId=hot.getString("id");TrafficState.server(hot.getString("name"))}
                    else {ids[outbound]?.let {selectedId=it};names[outbound]?.let(TrafficState::server)}
                }
                if(current.choiceId=="singbox"&&extras!=null){
                    val failed=OutboundObservation.failedManualFinal(runningConfig,autoAllowed(),dead){group->
                        if(group==target)outbound else current.selectedOutbound(group)
                    }
                    if(failed!=null){
                        retireFailedMainTransport("Дополнительное ядро выбранного основного выхода остановилось. ${extras.failureDetail(failed).orEmpty()}",requested)
                        return@execute
                    }
                }
                publishConnection()
                carrierSwitchboard?.retireDrained()
            }catch(_:Exception){}finally{main.post {if(session==generation&&requested==epoch&&core!=null&&!destroyed&&currentService===this@McrfVpnService){foreground(status);main.postDelayed(this,2000)}}}}
        }}
        telemetry=task;main.post(task)
    }
    private data class PrimaryTunPolicy(val scope:VpnOwnerIsolation.Scope,val metered:Boolean,val network:Long?)
    private var primaryTunPolicy:Pair<ParcelFileDescriptor,PrimaryTunPolicy>?=null
    @Volatile private var installedVpnScope:Pair<ParcelFileDescriptor,VpnOwnerIsolation.Scope>?=null
    override fun vpnOwnerIsolationRequired():Boolean = fixtureStrictOwner!=null||fixtureSteerOwnerPin!=null||fixtureBsNativePhysical!=null||!fixtureMode&&
        preferences.getBoolean(VpnOwnerIsolation.KEY,VpnOwnerIsolation.DEFAULT_ENABLED)
    override fun vpnOwnerIsolationFor(descriptor:ParcelFileDescriptor):Boolean =
        installedVpnScope?.takeIf {it.first===descriptor}?.second?.strict?:vpnOwnerIsolationRequired()
    override fun admitsConnectionOwner(owner:Platform.ConnectionOwner):Boolean {
        val descriptor=tunnel?:return !vpnMode||!vpnOwnerIsolationRequired()
        val scope=installedVpnScope?.takeIf {it.first===descriptor}?.second?:return !vpnOwnerIsolationRequired()
        return scope.admits(owner.uid,owner.packageNames)
    }
    private fun openPrimaryTun(options: Platform.TunPlan,nativeUidBypass:Boolean,reuse:Boolean=false):ParcelFileDescriptor {
        if (!vpnMode) throw IOException("TUN выключен.")
        val ownerScope=fixtureP1OwnerHelperSha?.let { sha ->
            check(BuildConfig.DEBUG&&fixtureMode&&!nativeUidBypass&&options.includePackage.isEmpty()&&options.excludePackage.isEmpty()) { "P1_OWNER_SCOPE_CONFLICT" }
            ClientP1OwnerFixtureScope.packages(this,sha)
        }
        val strictFixture=fixtureStrictOwner
        if(strictFixture!=null){
            check(BuildConfig.DEBUG&&fixtureMode&&!nativeUidBypass&&ownerScope==null&&
                options.includePackage.isEmpty()&&options.excludePackage.isEmpty()) { "P1_STRICT_OWNER_SCOPE_CONFLICT" }
        }
        val appScope=captureVpnScope(strictFixture?.includedPackages?:ownerScope?:options.includePackage,
            strictFixture?.excludedPackages?:options.excludePackage,nativeUidBypass)
        val policy=PrimaryTunPolicy(appScope,behavior.metered,underlying?.networkHandle)
        if(reuse)matchingRetainedTun(options)?.let {retained->
            if(primaryTunPolicy?.let {it.first===retained&&it.second==policy}==true)return retained
        }
        val builder = Builder().setSession("MCRF").setMtu(options.mtu).setBlocking(false).setMetered(behavior.metered)
        fun prefixes(values:List<String>,action:(String,Int)->Unit){values.forEach {value->val cidr=ClientTunCidr.parse(value,false);action(value.substringBefore('/'),cidr.prefix)}}
        prefixes(options.routes.inet4Address){host,bits->builder.addAddress(host,bits)}
        prefixes(options.routes.inet6Address){host,bits->builder.addAddress(host,bits)}
        if(options.autoRoute){
            if(options.dnsEnabled)options.dnsServers.forEach {address->require(RouteRules.literal(address)!=null);builder.addDnsServer(address)}
            if(Build.VERSION.SDK_INT>=33){
                prefixes(options.routes.inet4Routes.ifEmpty {if(options.routes.inet4Address.isEmpty())emptyList()else listOf("0.0.0.0/0")}){host,bits->builder.addRoute(host,bits)}
                prefixes(options.routes.inet6Routes.ifEmpty {if(options.routes.inet6Address.isEmpty())emptyList()else listOf("::/0")}){host,bits->builder.addRoute(host,bits)}
                prefixes(options.routes.inet4Exclude+options.routes.inet6Exclude){host,bits->val address=java.net.InetAddress.getByName(host);if(!address.isLoopbackAddress)builder.excludeRoute(android.net.IpPrefix(address,bits))}
            }else prefixes(options.routes.inet4Range+options.routes.inet6Range){host,bits->if(!java.net.InetAddress.getByName(host).isLoopbackAddress)builder.addRoute(host,bits)}
        }
        if(options.httpProxy.enabled)builder.setHttpProxy(android.net.ProxyInfo.buildDirectProxy(options.httpProxy.server,options.httpProxy.port,options.httpProxy.bypass))
        appScope.apply(builder)
        underlying?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        val next = ConnectTiming.measure(this,"tun_establish"){builder.establish()} ?: throw IOException("Android не разрешил открыть VPN.")
        primaryTunPolicy=next to policy
        installedVpnScope=next to appScope
        return next // HostRun alone publishes/closes this descriptor after its final generation check.
    }
    private fun captureVpnScope(includes:Collection<String>,excludes:Collection<String>,bypassOwnUid:Boolean=false,forceStrict:Boolean=false):VpnOwnerIsolation.Scope {
        val denied=if(bypassOwnUid)excludes+packageName else excludes
        return VpnOwnerIsolation.capture(forceStrict||vpnOwnerIsolationRequired(),
            includes,denied,{name->
                try {packageManager.getApplicationInfo(name,0).uid}
                catch(_:android.content.pm.PackageManager.NameNotFoundException){null}
            },{uid->packageManager.getPackagesForUid(uid)?.toList().orEmpty()},android.os.Process.myUid().takeIf {bypassOwnUid})
    }
    override fun onRevoke() {
        ServerSwitchTransition.observeCommand(null);val requested=++epoch
        recoveryState.cancel(requested,RecoveryReason.ANDROID_REVOKE);publishRecovery()
        bsWifiPaused=null;releaseBsPowerLease()
        ProbeCoordinator.cancelRuntime(this);clearNetworkRecovery();core?.requestStop();directTunSession?.requestStop()
        if(!fixtureMode)preferences.edit().putBoolean("wantedRunning",false).putBoolean(RECOVERY_USER_STOPPED,true).apply()
        if(!destroyed)worker.execute {runtimeHealth.stopped(HealthDisconnect.USER);closeCore();releaseRecoveryTun();if(!destroyed&&currentService===this&&requested==epoch){
            status="VPN-разрешение отозвано";ClientEvents.record(this,"Клиент",status);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        }}
    }
    override fun onDestroy() {
        captivePortalWatch?.close();captivePortalWatch=null
        captivePortalRetry?.let(main::removeCallbacks);captivePortalRetry=null
        preferences.unregisterOnSharedPreferenceChangeListener(safeModeListener)
        preferences.unregisterOnSharedPreferenceChangeListener(boundSelectionListener);main.removeCallbacks(boundSelectionReload)
        ProbeCoordinator.cancelRuntime(this)
        bsDetection?.close();bsDetection=null
        bsWifiPaused=null;main.removeCallbacks(bsWifiCheck);bsWifiMonitor?.close();bsWifiMonitor=null
        // Register synchronously before Android can create the replacement
        // Service: even a queued cleanup task must block its new native START.
        retiringServices.add(this)
        destroyed=true;epoch++;clearNetworkRecovery();core?.requestStop();directTunSession?.requestStop()
        if(!fixtureMode)ManagedAppFreezeUi.onTunnelChanged(this)
        journalWorker.shutdownNow()
        if(recoveryState.snapshot().phase!=RecoveryState.Phase.STOPPED)recoveryState.cancel(epoch,RecoveryReason.CANCELLED)
        wakeCheckTask?.let(main::removeCallbacks);wakeCheckTask=null
        vpnPackageReload?.let(main::removeCallbacks);vpnPackageReload=null
        if(vpnPackageReceiverRegistered){unregisterReceiver(vpnPackageReceiver);vpnPackageReceiverRegistered=false}
        releaseBsPowerLease()
        if(wakeRegistered){try{unregisterReceiver(wakeReceiver)}catch(_:IllegalArgumentException){};wakeRegistered=false}
        if(!fixtureMode)ClientBehaviorUi.applyRecents(this)
        localProxyPort=0
        worker.execute {
            closeCore()
            releaseRecoveryTun()
            if(currentService===this){
                if(status.startsWith("VPN подключён")||status.startsWith("Прокси ")||status=="Подключение…"||status=="Восстановление соединения…"||status=="Возвращаемся в VPN…"||status=="Остановка движка…")status="Отключено"
                currentService=null
            }
            retiringServices.remove(this)
            McrfQuickTile.connectionChanged(applicationContext)
        }
        worker.shutdown()
        super.onDestroy()
    }
    companion object {
        internal fun safeModeChanged(enabled:Boolean) { currentService?.applySafeMode(enabled) }
        @Volatile private var currentService:McrfVpnService?=null
        private val retiringServices=ConcurrentHashMap.newKeySet<McrfVpnService>()
        @JvmStatic fun cleanupPending()=status=="Остановка движка…"||ClientPrimaryRetirement.hasPending()||retiringServices.isNotEmpty()
        @JvmStatic fun ownsCurrentTun()=currentService?.let {it.tunnel!=null&&!it.destroyed}==true
        @JvmStatic fun vpnOwnerIsolationUpgradePending()=currentService?.let {
            !it.destroyed&&it.tunnel!=null&&it.ownerIsolationUpgradePending&&it.vpnOwnerIsolationRequired()
        }==true
        /** Includes a retained fail-closed TUN: power must offer STOP, not a second START. */
        @JvmStatic fun directTunActive()=currentService?.let {!it.destroyed&&CsqttDirectTunPolicy.active(it.directTunOwned,
            it.directAdmissionPending||it.directTunStarting||it.directTunTransition!=null)}==true
        /** Paused ownership is active but not busy, so the user may disable the test or STOP. */
        @JvmStatic fun directTunWorking()=currentService?.let {!it.destroyed&&
            (it.directAdmissionPending||it.directTunStarting||it.directTunTransition!=null)}==true
        internal fun captureGenerationLive(value:Long)=currentService?.let {
            !it.destroyed&&!it.safeModeActive&&it.generation==value&&it.tunnel!=null
        }==true
        internal fun ownsSystemTunnel()=currentService?.let {!it.destroyed&&
            (it.tunnel!=null||it.recoveryTunnel!=null)}==true
        @JvmStatic fun hasActiveSession()=currentService?.let {!it.destroyed&&
            (it.core!=null||it.currentStart!=null||it.networkRecovery!=null||it.recoveryTunnel!=null||it.recoveryState.snapshot().userRequested)}==true
        internal fun reloadActiveRoutes(context:Context){
            if(hasActiveSession())context.startService(Intent(context,McrfVpnService::class.java).setAction(RELOAD_ROUTES))
        }
        @JvmStatic fun wifiPaused()=currentService?.let {!it.destroyed&&it.bsWifiPaused?.requested==it.epoch}==true
        internal fun refreshPowerPolicy(context:Context) {
            if(currentService?.destroyed==false)context.startService(Intent(context,McrfVpnService::class.java).setAction(REFRESH_POWER_POLICY))
        }
        private const val REFRESH_POWER_POLICY="org.mcrf.client.REFRESH_POWER_POLICY"
        internal fun refreshBsEnvironment(context:Context) {
            if(currentService?.destroyed==false)context.startService(Intent(context,McrfVpnService::class.java).setAction(REFRESH_BS_ENVIRONMENT))
        }
        const val START = "org.mcrf.client.START"
        const val STOP = "org.mcrf.client.STOP"
        const val AUTO_OFF = "org.mcrf.client.AUTO_OFF"
        const val RESUME_REQUEST="org.mcrf.client.RESUME_REQUEST"
        internal const val MANUAL_VPN="manualVpn"
        internal const val EXPLICIT_TILE_START="explicitTileStart"
        const val RECOVERY_USER_STOPPED="recoveryUserStopped"
        const val RECOVERY_CHANGED="org.mcrf.client.RECOVERY_CHANGED"
        @JvmField @Volatile var recovery=RecoveryState.Snapshot()
        const val RELOAD_ROUTES = "org.mcrf.client.RELOAD_ROUTES"
        const val DIRECT_TUN_CHANGED="org.mcrf.client.DIRECT_TUN_CHANGED"
        const val VPN_OWNER_ISOLATION_CHANGED="org.mcrf.client.VPN_OWNER_ISOLATION_CHANGED"
        @JvmStatic fun directTunChanged(context:Context) {
            val owner=currentService?:return // A saved flag must never start a disconnected/foreign VPN.
            owner.main.post {
                if(owner.destroyed||currentService!==owner||!owner.preferences.getBoolean("wantedRunning",false)||owner.currentStart==null&&owner.directTunTransition==null)return@post
                if(!owner.directTunOwned&&owner.directTunTransition==null&&!owner.directTunSelected())return@post // OFF on an ordinary VPN is a strict NO-OP.
                owner.queueDirectTunChange()
            }
        }
        private const val NETWORK_PROFILE_CHANGED="org.mcrf.client.NETWORK_PROFILE_CHANGED"
        internal fun carrierStreamDefaultsChanged(changes:Map<String,Int>,completed:(String)->Unit) {
            val owner=currentService
            val handler=owner?.main?:Handler(Looper.getMainLooper())
            handler.post {
                if(owner==null||currentService!==owner||owner.destroyed)
                    completed("Сохранено. Активной службы нет; значение применится при подключении.")
                else owner.queueStreamChange(changes,completed)
            }
        }
        internal fun networkProfileChanged(context:Context) {
            val owner=currentService?:return
            // Only the live owner may schedule a reconfiguration; a stopped session stays stopped.
            owner.main.post {
                if(!owner.destroyed&&currentService===owner&&owner.preferences.getBoolean("wantedRunning",false)&&
                    (owner.currentStart!=null||owner.bsWifiPaused!=null))
                    context.startService(Intent(context,McrfVpnService::class.java).setAction(NETWORK_PROFILE_CHANGED))
            }
        }
        private const val REFRESH_BS_ENVIRONMENT="org.mcrf.client.REFRESH_BS_ENVIRONMENT"
        @JvmField @Volatile var status = "Отключено"
        @JvmField @Volatile var connectionStep = "prepare"
        @JvmField @Volatile var selectedId = ""
        @JvmField @Volatile var pendingWhitelists:Boolean?=null
        @JvmField @Volatile var autoBsSwitchSerial=0L
        @JvmField @Volatile var autoVpnSwitchSerial=0L
        @JvmField @Volatile var nativeVpnExclusion = false
        @JvmField @Volatile var localProxyPort=0
    }
}

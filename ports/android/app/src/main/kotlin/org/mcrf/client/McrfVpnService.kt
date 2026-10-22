package org.mcrf.client

import android.app.*
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Network
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
    @Volatile private var core: ClientPrimaryWorker? = null
    private var telemetry: Runnable? = null
    private var notificationTraffic:NotificationTrafficSampler?=null
    private var carrier: NativeCarrier? = null
    private var routeServerSession:RouteServerSession?=null
    private var nativeRouteSession:NativeRouteWorkers?=null
    private var nativeRoutePlatforms:NativeRoutePlatforms?=null
    private var nativeRouteBootstrap:ServerBootstrapResolver?=null
    private var carrierSwitchboard:CarrierSocksSwitch?=null
    private var bsCandidateCursor=0
    private val bsHotMeasurements=linkedMapOf<String,Pair<Long,Long>>()
    private var optionalSession:OptionalCoreSession?=null
    private var engineUsage:EngineUpdateGate.Usage?=null
    @Volatile private var retirement:ClientPrimaryRetirement<ClientPrimaryWorker>?=null
    @Volatile private var destroyed=false
    private data class RuntimeStart(val template:JSONObject,val candidate:JSONObject?,val vpn:Boolean,val port:Int,val applyRoutes:Boolean,val requested:Long)
    @Volatile private var currentStart:RuntimeStart?=null
    private var bsDetection:BsDetectionMonitor?=null
    @Volatile private var bsWifiPaused:RuntimeStart?=null
    @Volatile private var bsWifiSettings=BsWifiSettings()
    @Volatile private var bsWifiMonitor:BsWifiMonitor?=null
    @Volatile private var bsPowerLease:BsScreenOffLease?=null
    private var bsPowerGeneration=-1L
    private var bsPowerMode=""
    private val bsWifiCheck=Runnable {
        if(!destroyed&&!worker.isShutdown)try{worker.execute {try{applyBsWifiPolicy()}finally{refreshBsPowerLease()}}}catch(_:java.util.concurrent.RejectedExecutionException){}
    }
    private val autoSectionNotice=AutoSectionNotice()
    private var autoDetectionRetryAfter=0L
    @Volatile private var networkRecovery:RuntimeStart?=null
    private var networkRecoveryTask:Runnable?=null
    private var networkRecoveryDelay=2000L
    @Volatile private var rebuildingForNetwork=false
    private val networkResetQueued=java.util.concurrent.atomic.AtomicBoolean()
    private val names = ConcurrentHashMap<String, String>()
    private val ids = ConcurrentHashMap<String, String>()
    @Volatile private var target = ""
    @Volatile private var generation = 0L
    @Volatile private var epoch = 0L
    private var healthTask: Runnable? = null
    private var steerVpnNetwork:Network?=null
    private var steerFrozenId=""
    private val steerRunLock=Any()
    @Volatile private var autoDisabled = false
    @Volatile private var selectedOutbound = ""
    private var runningConfig: JSONObject? = null
    private var runningDirectDnsIpv4: Boolean? = null
    private var fixtureMode=false
    @Volatile private var fixtureP1OwnerHelperSha:String?=null
    private var sessionPrimaryChoice:String?=null
    private var fallbackAttempted=false
    private var fixtureRetirementHold:File?=null
    private var fixtureConnectPending=false
    private var carrierTimeout=60000L
    private var healthUrl="https://www.gstatic.com/generate_204"
    private var behavior=ClientBehaviorSettings()
    private var wakeRegistered=false
    private val wakeReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==Intent.ACTION_SCREEN_ON&&behavior.resetOnWake)resetConnections("пробуждение")}}
    override fun onCreate(){
        super.onCreate()
        currentService=this
        behavior=try{ClientBehaviorStore.load(this)}catch(_:Exception){ClientBehaviorSettings()}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(wakeReceiver,IntentFilter(Intent.ACTION_SCREEN_ON),Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(wakeReceiver,IntentFilter(Intent.ACTION_SCREEN_ON))
        wakeRegistered=true
        reloadBsWifiMonitor()
        bsDetection=BsDetectionMonitor(this,{
            val start=currentStart
            if(destroyed||core==null||networkRecovery!=null||start==null||
                !(status.startsWith("VPN подключён")||status.startsWith("Прокси ")))null
            else if(fixtureMode)null // Owned P1 fixtures must never poll public sites or change pools.
            else BsDetectionMonitor.Stamp(epoch,generation,!start.template.has("mcrf_bs")&&!start.template.has("mcrf_carrier"),start.vpn)
        },{stamp->if(stamp.vpnSection)switchAutomaticallyToBs(stamp)else switchAutomaticallyToVpn(stamp)},{stamp->
            // Our UID is deliberately excluded from the TUN when carrier children run.
            // Test the actual selected primary outbound, not a VPN Network inaccessible to that UID.
            val running=core
            if(destroyed||epoch!=stamp.epoch||generation!=stamp.generation||networkRecovery!=null||running==null)
                DirectAccessCheck.Outcome.CANCELLED
            else try{
                if(running.health(target,"https://youtube.com/",2500)>=0)DirectAccessCheck.Outcome.RESPONSE
                else DirectAccessCheck.Outcome.NO_RESPONSE
            }catch(_:Exception){DirectAccessCheck.Outcome.NO_RESPONSE}
        })
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
    private fun refreshBsPowerLease() {
        val session=currentStart
        val profile=session?.candidate?.optJSONObject("profile")?:session?.template?.optJSONObject("mcrf_carrier")
        val settings=try{BsScreenOffSettings.load(this)}catch(_:Exception){BsScreenOffSettings()}
        val eligible=!destroyed&&!fixtureMode&&currentService===this&&core!=null&&session!=null&&session.requested==epoch&&
            ConnectionPresentation.connected(status)&&profile?.optString("type")?.let {it in SwgProfile.TYPES}==true&&settings.mode=="stable"
        if(!eligible){releaseBsPowerLease();return}
        if(bsPowerLease!=null&&bsPowerGeneration==generation&&bsPowerMode==settings.mode)return
        releaseBsPowerLease()
        val captured=generation;val requested=epoch
        try {
            val lease=BsScreenOffLease(this,settings){!destroyed&&currentService===this&&generation==captured&&epoch==requested&&core!=null&&ConnectionPresentation.connected(status)}
            try{lease.start()}catch(error:Exception){lease.close();throw error}
            synchronized(this){
                if(destroyed||epoch!=requested||generation!=captured||core==null)lease.close()
                else {bsPowerLease=lease;bsPowerGeneration=captured;bsPowerMode=settings.mode}
            }
        }catch(_:Exception){ClientEvents.record(this,"БС","Удержание CPU недоступно. Соединение продолжает обычную фоновую работу.")}
    }
    private fun releaseBsPowerLease()=synchronized(this) {
        val previous=bsPowerLease;bsPowerLease=null;bsPowerGeneration=-1;bsPowerMode=""
        previous?.close()
        Unit
    }
    private fun scheduleBsWifiCheck() {
        main.removeCallbacks(bsWifiCheck)
        if(!destroyed)main.postDelayed(bsWifiCheck,300)
    }
    /** Only this service's saved VK Turn session may be paused/resumed. No section reselection. */
    private fun applyBsWifiPolicy() {
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
        }catch(_:Exception){
            if(paused.requested==epoch&&!destroyed){
                closeCore(keepTun=paused.vpn)
                status="Ошибка подключения: БС не восстановлено после Wi-Fi";foreground(status)
                ClientEvents.record(this,"БС","Автовосстановление не удалось. Повторите подключение вручную; повторные запросы VK автоматически не запускаются.")
            }
        }finally{rebuildingForNetwork=false}
    }
    private fun switchAutomaticallyToBs(stamp:BsDetectionMonitor.Stamp){
        if(worker.isShutdown)return
        try{worker.execute {
            fun live()=!destroyed&&currentService===this&&epoch==stamp.epoch&&generation==stamp.generation&&preferences.getBoolean(BsDetectionMonitor.KEY,false)
            val previous=currentStart?:return@execute
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
        if(fixtureMode||!preferences.getBoolean(BsDetectionMonitor.KEY,false)||session.template.has("mcrf_bs")||session.template.has("mcrf_carrier"))return true
        return try{
            if(preferences.getString(BsAutoSelectionPolicy.LAST_VPN,"").isNullOrEmpty()&&selectedId.matches(Regex("[0-9a-f]{64}")))preferences.edit().putString(BsAutoSelectionPolicy.LAST_VPN,selectedId).apply()
            AutoVpnReturn.save(this,session.template,session.vpn,session.port,session.applyRoutes,requireNotNull(ClientPrimarySelection.selected(this)));true
        }
        catch(_:Exception){ClientEvents.record(this,"Предупреждение","Не удалось сохранить цель автоматического возврата в VPN. Текущее подключение не изменено.");false}
    }
    private fun switchAutomaticallyToVpn(stamp:BsDetectionMonitor.Stamp){
        if(worker.isShutdown)return
        try{worker.execute {
            fun live()=!destroyed&&currentService===this&&epoch==stamp.epoch&&generation==stamp.generation&&preferences.getBoolean(BsDetectionMonitor.KEY,false)
            fun switchingLive()=!destroyed&&currentService===this&&epoch==stamp.epoch&&preferences.getBoolean(BsDetectionMonitor.KEY,false)
            val previous=currentStart?:return@execute
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
                        require(core?.health(target,"https://youtube.com/",2500)?.let {it>=0}==true) {"VPN пока не подтвердил ответ YouTube"}
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
        if(bsWifiPaused!=null)return
        if(!behavior.resetOnNetworkChange||rebuildingForNetwork)return
        if(network==null){
            currentStart?.let {networkRecovery=it;retryNetworkRecovery(it)}
            healthTask?.let(main::removeCallbacks);healthTask=null
        }else if(currentStart!=null||networkRecovery?.requested==epoch)resetConnections("смена сети")
    }
    private fun clearNetworkRecovery(){
        networkRecovery=null
        networkRecoveryTask?.let(main::removeCallbacks);networkRecoveryTask=null
        networkRecoveryDelay=2000L
    }
    private fun retryNetworkRecovery(session:RuntimeStart){
        if(destroyed||currentService!==this||session.requested!=epoch||networkRecovery!==session)return
        networkRecoveryTask?.let(main::removeCallbacks)
        val task=Runnable {
            if(!destroyed&&currentService===this&&session.requested==epoch&&networkRecovery===session)
                resetConnections("возврат сети")
        }
        networkRecoveryTask=task
        main.postDelayed(task,networkRecoveryDelay)
        networkRecoveryDelay=(networkRecoveryDelay*2).coerceAtMost(30000L)
    }
    private fun resetConnections(reason:String){
        if(worker.isShutdown||!networkResetQueued.compareAndSet(false,true))return
        val requested=epoch
        try{worker.execute {
            if(destroyed||currentService!==this||requested!=epoch||(core==null&&networkRecovery?.requested!=requested)){
                networkResetQueued.set(false);return@execute
            }
            rebuildingForNetwork=true
            var startingPhysical:Network?=null
            try{
            val session=currentStart?:networkRecovery?.takeIf {it.requested==requested}
            if(session==null)return@execute
            networkRecovery=session
            startingPhysical=DirectAccessCheck.physical(this)
            if(startingPhysical==null){status="Восстановление соединения…";foreground("Ожидаем возвращения сети");retryNetworkRecovery(session);return@execute}
            val bs=session.template.optJSONObject("mcrf_bs")?.takeIf {autoAllowed()}
            if(bs!=null){
                val (freshSource,candidates)=refreshBsPool(session.template,requested)
                val selected=chooseEngine(freshSource,candidates,session.vpn,session.port,session.applyRoutes,requested)
                clearNetworkRecovery()
                scheduleHealth(freshSource,candidates,selected,freshSource.getJSONObject("mcrf_bs").getInt("interval"),session.vpn,session.port,session.applyRoutes,requested)
            }else if(optionalSession!=null||routeServerSession!=null||carrier!=null||ClientPrimarySelection.selected(this)=="steer"||
                DirectDnsFamily.requiresRestart(runningDirectDnsIpv4,DirectDnsNetwork.ipv4Only(this,startingPhysical))){
                val frozen=if(autoDisabled)selectedOutbound.takeIf{it.isNotEmpty()}else null
                optionalSession?.networkChanged()
                startSource(session.template,session.candidate,session.vpn,session.port,session.applyRoutes,session.requested,true,frozen)
            }else if(core!=null){core?.resetNetwork();if(status=="Восстановление соединения…"){
                status=if(session.vpn)"VPN подключён"else"Прокси 127.0.0.1:${session.port}";foreground(status)
            }}
            else startSource(session.template,session.candidate,session.vpn,session.port,session.applyRoutes,session.requested,true)
            clearNetworkRecovery()
            // startSteer cannot arm health while the recovery barrier is held.
            // Rearm only after it is released, including a paused all-failed run.
            if(bs==null&&sessionPrimaryChoice=="steer"&&autoAllowed())
                currentStart?.takeIf {it.requested==requested}?.let {scheduleSteerHealth(it)}
            ClientEvents.record(this,"Клиент","Соединения обновлены: $reason")
        }catch(_:Exception){
            val recovery=networkRecovery
            closeCore(keepTun=recovery?.vpn==true)
            if(!destroyed&&currentService===this&&requested==epoch&&recovery?.requested==requested){
                networkRecovery=recovery;status="Восстановление соединения…";foreground("Повторяем подключение после смены сети")
                ClientEvents.record(this,"Предупреждение","Сеть изменилась; соединение пока не восстановлено, повторяем с задержкой")
                retryNetworkRecovery(recovery)
            }
        }finally{
            rebuildingForNetwork=false;networkResetQueued.set(false)
            // A new platform monitor announces its initial interface while rebuilding.
            // Compare actual physical identity instead of replaying that self-callback.
            // Conversely, do not lose a real SIM/Wi-Fi/loss event during a slow start.
            if(!destroyed&&currentService===this&&requested==epoch&&core!=null&&networkRecovery==null&&
                runCatching {DirectAccessCheck.physical(this)}.getOrNull()!=startingPhysical){
                currentStart?.takeIf {it.requested==requested}?.let {
                    networkRecovery=it;status="Восстановление соединения…";foreground(status);retryNetworkRecovery(it)
                }
            }
        }}}catch(_:java.util.concurrent.RejectedExecutionException){networkResetQueued.set(false)}
    }
    private val preferences get() = getSharedPreferences("MainActivity", MODE_PRIVATE)
    override fun onStartCommand(incoming: Intent?, flags: Int, startId: Int): Int {
        if(destroyed)return START_NOT_STICKY
        if(incoming?.action==REFRESH_BS_ENVIRONMENT){reloadBsWifiMonitor();return START_STICKY}
        if(incoming?.action==RELOAD_ROUTES){
            if(currentStart==null&&bsWifiPaused==null&&core==null&&!cleanupPending()) {stopSelf(startId);return START_NOT_STICKY}
            val requested=epoch
            if(!fixtureMode&&!worker.isShutdown)worker.execute {
                if(destroyed||currentService!==this||requested!=epoch)return@execute
                val session=currentStart?:return@execute
                if(!session.applyRoutes)return@execute
                try {
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
                }catch(_:Exception){
                    if(requested==epoch&&!destroyed){
                        closeCore(keepTun=session.vpn)
                        status="Ошибка подключения: группа правил не применена";foreground(status)
                        ClientEvents.record(this,"Маршруты","Не удалось применить группу. Трафик VPN приостановлен, обход через прямой выход не включён.")
                    }
                }
            }
            return START_STICKY
        }
        if(incoming?.action!=STOP&&(EngineUpdates.installationPending(this)||EngineUpdateScripts.mutationPending())){
            status="Обновление движка · завершите установку перед подключением";stopSelf(startId);return START_NOT_STICKY
        }
        if (incoming?.action == AUTO_OFF) {
            synchronized(steerRunLock){
                autoDisabled = true
                if((core?.choiceId=="steer"||nativeRouteSession!=null)&&selectedId.isNotEmpty())steerFrozenId=selectedId
            }
            ProbeCoordinator.cancelRuntime(this)
            healthTask?.let(main::removeCallbacks); healthTask = null
            worker.execute { freezeSelection() }
            return START_STICKY
        }
        val requested = ++epoch
        releaseBsPowerLease()
        bsWifiPaused=null
        main.removeCallbacks(bsWifiCheck)
        ProbeCoordinator.cancelRuntime(this)
        clearNetworkRecovery()
        val systemAlwaysOn=incoming?.action==VpnService.SERVICE_INTERFACE&&isAlwaysOn
        val intent = if(systemAlwaysOn||incoming==null&&preferences.getBoolean("wantedRunning", false)) Intent(this, McrfVpnService::class.java).setAction(START)
            .putExtra("tun", preferences.getBoolean("tun", true)).putExtra("port", preferences.getInt("port", 10808))
            .putExtra("applyRoutes", preferences.getBoolean("applyRoutes", true)) else incoming
        if (intent == null || intent.action == STOP) {
            ServerSwitchTransition.observeCommand(intent?.getStringExtra(ServerSwitchTransition.EXTRA))
            val stopRequestedAt=android.os.SystemClock.elapsedRealtime()
            if(!fixtureMode) preferences.edit().putBoolean("wantedRunning", false).apply()
            status="Остановка движка…"
            try{foreground(status)}catch(_:Exception){}
            core?.requestStop()
            worker.execute {
                // Log I/O stays off the main/UI thread, including log rotation.
                ClientEvents.record(this,"Клиент","Остановка: очередь службы пройдена за ${android.os.SystemClock.elapsedRealtime()-stopRequestedAt} мс; ожидаем освобождение движков/VPN. Подтверждение будет отдельной записью.")
                closeCore()
                if(!destroyed&&currentService===this&&requested==epoch){status = "Отключено"; ClientEvents.record(this, "Клиент", "Соединение остановлено; очередь и освобождение ресурсов заняли ${android.os.SystemClock.elapsedRealtime()-stopRequestedAt} мс"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)}
            }
            return START_NOT_STICKY
        }
        if (intent.action != START) { stopSelf(startId); return START_NOT_STICKY }
        ServerSwitchTransition.observeCommand(null)
        autoDisabled = false
        steerFrozenId=""
        sessionPrimaryChoice=null;fallbackAttempted=false
        val testInput=if(BuildConfig.DEBUG)intent.getStringExtra("test_fixture") else null
        fixtureMode=testInput!=null
        lastForegroundContent=null // A new START must always promote the service, even after a failed prior start.
        pendingWhitelists=if(intent.hasExtra("pendingWhitelists"))intent.getBooleanExtra("pendingWhitelists",false)else null
        status="Подключение…"
        foreground("Подключение…")
        val vpn = if(systemAlwaysOn)true else intent.getBooleanExtra("tun", true)
        val preflightPort=intent.getIntExtra("preparedProxyPort",0).takeIf {it in 1024..65535}
        val port = if(fixtureMode)intent.getIntExtra("port",10808)else preflightPort?:try{ClientTunStore(this).load().choosePort()}catch(_:Exception){intent.getIntExtra("port",10808)}
        val applyRoutes = intent.getBooleanExtra("applyRoutes", false)
        val connectStartedNs=intent.getLongExtra(ConnectTiming.EXTRA,0)
        val serviceQueuedNs=ConnectTiming.now()
        if(!fixtureMode) preferences.edit().putBoolean("wantedRunning", true).apply()
        worker.execute {
            ConnectTiming.mark(this,"service_queue",serviceQueuedNs)
            val serviceStartedNs=ConnectTiming.now()
            try {
                if (requested != epoch) return@execute
                ConnectTiming.measure(this,"previous_core_close"){closeCore()}
                if(requested!=epoch||destroyed||currentService!==this)return@execute
                fixtureP1OwnerHelperSha=null
                vpnMode = vpn; nativeVpnExclusion = false
                if(!fixtureMode)behavior=ClientBehaviorStore.load(this)
                if (vpn && VpnService.prepare(this) != null) throw IOException("Сначала разреши VPN в приложении.")
                status = "Подключение…"
                val source=if(testInput==null){carrierTimeout=60000;healthUrl="https://www.gstatic.com/generate_204";ConnectTiming.measure(this,"profile_read"){JSONObject(ProfileStore(this).read())}}
                    else readFixture(testInput)
                val bs=source.optJSONObject("mcrf_bs")
                pendingWhitelists=bs!=null||source.has("mcrf_carrier")
                if(bs==null) startVpnWithBsPreflight(source,vpn,port,applyRoutes,requested)
                else {
                    val candidates=BsPlan.candidates(bs)
                    TrafficState.reset("Выбираем движок БС…")
                    val chosen=chooseEngine(source,candidates,vpn,port,applyRoutes,requested)
                    scheduleHealth(source,candidates,chosen,bs.getInt("interval"),vpn,port,applyRoutes,requested)
                }
                if(requested==epoch){ConnectTiming.mark(this,"service_to_ready",serviceStartedNs);ConnectTiming.mark(this,"tap_to_ready",connectStartedNs)}
            } catch (error: Exception) {
                if (requested != epoch) { closeCore(); return@execute }
                val primaryFailure=ClientPrimaryPreparationFailure.code(error)
                val explanation=primaryFailure?.let(ClientPrimaryPreparationFailure::message)
                    ?:ClientEvents.redact(error.message)
                // Record the redacted cause before potentially slow native retirement.
                ClientEvents.record(this, "Ошибка", "Ошибка подключения: $explanation")
                closeCore()
                if(requested!=epoch||destroyed||currentService!==this)return@execute
                status = "Ошибка подключения: $explanation"
                if(!fixtureMode)preferences.edit().putBoolean("wantedRunning", false).apply(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
            }
        }
        return START_STICKY
    }
    // P046_BEGIN: startup BS preflight; extracted alone into the P0-only snapshot.
    private fun prepareAutoBsTemplate():JSONObject {
        val catalogue=Connections(this).apply {load()}
        val marked=catalogue.bsStartupEntries()
        val mode=BsAutoSelectionPolicy.selected(preferences)
        val pool=(if(marked.isNotEmpty()&&mode!=BsAutoSelectionPolicy.OFF)marked else catalogue.entries().filter {AutoSelection.eligible(it,AutoSelection.BS_ENGINES)}
            .sortedBy {AutoSelection.ENGINE_PRIORITY.indexOf(it.protocol)}).filter {entry->
            runCatching {NativeCarrier.executable(this,EngineModules.carrierKind(requireNotNull(entry.carrier)));true}.getOrDefault(false)
        }
        require(pool.isNotEmpty()) {"Автоопределение БС: нет серверов с установленным совместимым движком. Проверь серверы БС и движки."}
        val ranked=AutoSelection.startup(pool,pool.map {it.id}.toSet(),catalogue.probes(),System.currentTimeMillis(),AutoSelection.BS_ENGINES)
        val ordered=BsAutoSelectionPolicy.targets(ranked,BsAutoSelectionPolicy.last(preferences,true),mode){it.id}
        require(ordered.isNotEmpty()) {"Автовыбор выключен: сначала выберите сервер БС вручную."}
        val template=BsPlan.build(ordered,preferences.getInt("interval",60),AutoSelectionDeviation.millis(preferences),"Cloudflare")
        val routes=RoutingStore(this).apply {load()}
        DnsConfig.applyGenerated(template,routes.snapshot().getJSONObject("globalDns"))
        return template
    }
    private var autoVpnCandidateCursor=0
    private fun autoVpnTargets(fallback:JSONObject?):List<JSONObject> {
        val catalogue=Connections(this).apply {load()}
        val steer=ClientPrimarySelection.selected(this)=="steer"
        val pool=catalogue.entries().filter {AutoSelection.eligible(it,AutoSelection.LATENCY)&&(!steer||SteerProbePlan.supports(it))}
        val fastest=AutoSelection.startup(pool,pool.map {it.id}.toSet(),catalogue.probes(),System.currentTimeMillis())
        val mode=BsAutoSelectionPolicy.selected(preferences)
        val ordered=BsAutoSelectionPolicy.targets(fastest,BsAutoSelectionPolicy.last(preferences,false),mode){it.id}
        val dns=RoutingStore(this).apply {load()}.snapshot().getJSONObject("globalDns")
        val attempts=BsAutoSelectionPolicy.attempts(ordered,autoVpnCandidateCursor)
        autoVpnCandidateCursor=if(ordered.size>1)(autoVpnCandidateCursor+7)%(ordered.size-1) else 0
        return attempts.mapNotNull {entry->runCatching {
            ServerImport.build(listOf(entry),entry.id,false,preferences.getInt("interval",60),AutoSelectionDeviation.millis(preferences),"Cloudflare").also {DnsConfig.applyGenerated(it,dns)}
        }.getOrNull()}.ifEmpty {if(mode==BsAutoSelectionPolicy.OFF)emptyList()else listOfNotNull(fallback)}
    }
    private fun startVpnWithBsPreflight(source:JSONObject,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long) {
        fun cancelled()=requested!=epoch||destroyed||currentService!==this
        val decision=BsStartupPolicy.start(
            enabled={preferences.getBoolean(BsDetectionMonitor.KEY,false)},fixture=fixtureMode,
            alreadyBs=source.has("mcrf_bs")||source.has("mcrf_carrier"),cancelled={cancelled()},
            probe={
                require(ClientPrimarySelection.selected(this)!="steer") {"Автоопределение БС: выбранное ядро пока не поддерживает транспорты БС."}
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
    private fun startSource(template:JSONObject,candidate:JSONObject?,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long,keepTraffic:Boolean,frozenOutbound:String?=null) {
        pendingWhitelists=candidate!=null||template.has("mcrf_carrier")||template.has("mcrf_bs")
        val initial=if(fixtureMode)"singbox"else sessionPrimaryChoice?:ClientPrimarySelection.effectiveWireGuardChoice(this,
            ClientPrimarySelection.requireSelected(this),WgAddonPolicy.usesWireGuard(template))
        ClientEvents.record(this,"Движки","Запуск · MCRF ${BuildConfig.VERSION_NAME}; API ${android.os.Build.VERSION.SDK_INT}; основной=$initial; режим=${if(vpn)"VPN"else"прокси"}; маршруты=$applyRoutes; выбор=${if(candidate!=null)"авто"else"один профиль"}")
        try {
            startSourceWithCore(template,candidate,vpn,port,applyRoutes,requested,keepTraffic,frozenOutbound,initial)
            sessionPrimaryChoice=initial
        }catch(error:Exception) {
            // Steer resolves the selected catalogue entry; never substitute it for a raw/diagnostic profile.
            val catalogueFallback=applyRoutes&&candidate==null&&frozenOutbound==null&&
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
    private fun startSourceWithCore(template:JSONObject,candidate:JSONObject?,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long,keepTraffic:Boolean,frozenOutbound:String?,primaryChoice:String,
                                    nativePreparation:NativeRoutePreparation?=null,nativeEntry:ServerImport.Server?=null,nativeProof:SteerProbeDelays?=null) {
        require((nativePreparation==null)==(nativeEntry==null)){"NATIVE_ROUTE_START_CAPTURE"}
        if(nativePreparation!=null)require(!fixtureMode&&vpn&&applyRoutes&&candidate==null&&primaryChoice=="steer"&&
            nativePreparation.plan.bindings.any {it.id==nativeEntry!!.id}){"NATIVE_ROUTE_START_SCOPE"}
        if(fixtureP1OwnerHelperSha!=null){
            val scopedSteer=template.keys().asSequence().toSet()==setOf("mcrf_steer_fixture")
            if(scopedSteer)ClientSteerFixture.requireP1ProxySnapshot(
                template.getJSONObject("mcrf_steer_fixture").getJSONObject("snapshot"),healthUrl.substringAfterLast('/'))
            check(BuildConfig.DEBUG&&fixtureMode&&vpn&&candidate==null&&!applyRoutes&&!keepTraffic&&frozenOutbound==null&&primaryChoice=="singbox"&&
                (scopedSteer||template.keys().asSequence().toSet()==setOf("outbounds","dns","route"))) { "P1_OWNER_SCOPE_START_CONFLICT" }
        }
        if(requested!=epoch) throw IOException("Подключение отменено")
        closeCore(keepTraffic&&vpn);vpnMode=vpn
        if(requested!=epoch||destroyed||currentService!==this)throw IOException("Подключение отменено")
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
            if(WgAddonPolicy.usesWireGuard(template)) {
                ConnectTiming.measure(this,"wg_prepare_start"){startCompactWireGuard(template,vpn,port,applyRoutes,requested)}
                return
            }
            ConnectTiming.measure(this,"steer_prepare_start"){startSteer(template,vpn,port,applyRoutes,requested,keepTraffic)}
            return
        }
        engineUsage=EngineUpdateGate.acquireUsage(this)?:throw IOException("Движок обновляется. Завершите установку перед подключением.")
        if(nativePreparation!=null)prepareCarrierHandoverTun(vpn,requested)
        val source=JSONObject(template.toString())
        val profile=candidate?.getJSONObject("profile") ?: source.optJSONObject("mcrf_carrier")
        val bridgeTag=source.optJSONObject("mcrf_carrier")?.optString("outbound_tag").orEmpty()
        source.remove("mcrf_carrier");source.remove("mcrf_bs")
        if(frozenOutbound!=null)source.optJSONArray("outbounds")?.let {list->repeat(list.length()){i->val node=list.getJSONObject(i);if(node.optString("type") in setOf("urltest","selector")&&node.optJSONArray("outbounds")?.let {members->(0 until members.length()).any{members.getString(it)==frozenOutbound}}==true){node.put("type","selector").put("default",frozenOutbound);listOf("url","interval","tolerance").forEach(node::remove)}}}
        profile?.let {
            prepareCarrierHandoverTun(vpn,requested)
            val transport=ConnectTiming.measure(this,"carrier_start"){NativeCarrier.start(this,it,java.util.function.BooleanSupplier { requested!=epoch },carrierTimeout)};carrier=transport
            val outputs=source.getJSONArray("outbounds")
            val node=(0 until outputs.length()).map { outputs.getJSONObject(it) }.firstOrNull { it.optString("tag")==bridgeTag }
                ?: throw IOException("Missing carrier outbound")
            if(candidate!=null&&template.has("mcrf_bs")&&!fixtureMode){
                val board=CarrierSocksSwitch(candidate.getString("id"),transport);carrierSwitchboard=board
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
            val routes=RoutingStore(this);ConnectTiming.measure(this,"routes_load"){routes.load()};val settings=nativePreparation?.state()?:routes.snapshot()
            val catalogue=Connections(this);catalogue.load();val routeEntries=nativePreparation?.entries()?:catalogue.entries()
            val plan=RouteServerPlan(settings,routeEntries)
            routePlan=plan
            RouteServerSession.check(this,plan,java.util.function.BooleanSupplier{requested!=epoch})
            val directIpv4=DirectDnsNetwork.ipv4Only(this,underlying)
            prepared=ConnectTiming.measure(this,"routes_compile"){RouteRules.apply(prepared,settings,vpn,PresetAssets.resolve(this,settings),PresetAssets.ipRuleSetTags(this,settings),directIpv4,routeEntries)}
            if(plan.entries.any {it.carrier!=null}) {
                prepareCarrierHandoverTun(vpn,requested)
                val session=RouteServerSession.start(this,prepared,plan,java.util.function.BooleanSupplier{requested!=epoch})
                routeServerSession=session;prepared=session.config
            }
            if(DnsConfig.appOwned(source)){runningDirectDnsIpv4=directIpv4;if(directIpv4)ClientEvents.record(this,"DNS","У физической сети нет IPv6-маршрута в интернет. Для однозначных прямых TCP-маршрутов включён быстрый отказ недоступного IPv6, позволяющий приложению попробовать IPv4. AAAA, IPv6 через VPN и защита TUN не отключены.")}
        }
        val coreSettings=if(fixtureMode)CoreTuningSettings()else CoreTuningStore(this).load()
        if(nativePreparation!=null)SteerRouteTuning.requireSupported(coreSettings)
        if(!fixtureMode){
            val plan=OptionalCorePlan(prepared,OptionalCoreStore(this).load(),coreSettings)
            plan.notes.forEach {ClientEvents.record(this,"Движки",it)}
            if(plan.requiresWorkers){
                prepareCarrierHandoverTun(vpn,requested)
                // openTun below excludes our UID for every retained native worker.
                val native=ConnectTiming.measure(this,"optional_core_start"){OptionalCoreSession.start(this,plan,java.util.function.BooleanSupplier{requested!=epoch},vpn,true)}
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
        }else localProxyPort=port
        val poolProfiles=template.optJSONObject("mcrf_bs")?.optJSONArray("candidates")?.let {list->
            (0 until list.length()).map {list.getJSONObject(it).getJSONObject("profile")}
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
        names.clear(); ids.clear()
        try{val catalogue=Connections(this);catalogue.load();catalogue.entries().forEach { names[ServerImport.tag(it)]=it.name; ids[ServerImport.tag(it)]=it.id }}catch(_:Exception){}
        if(nativeEntry==null)selectedId = candidate?.getString("id") ?: ids[target].orEmpty()
        if (!hasRouteServers && profile == null && selectedId.isNotEmpty() && preferences.getBoolean(ConnectionAddressFamily.key(selectedId), false)) {
            prepared = ConnectionAddressFamily.apply(prepared, true)
            ClientEvents.record(this, "DNS", ConnectionAddressFamily.NOTE)
        }
        val label=nativeEntry?.name ?: candidate?.getString("name") ?: names[target] ?: if(target=="proxy") "Определяем сервер…" else "JSON-профиль"
        if(keepTraffic) TrafficState.handover(label) else TrafficState.reset(label)
        ClientEvents.record(this,"Маршруты",ConnectionDiagnostics.summary(prepared))
        runningConfig = prepared; selectedOutbound = ""
        val choice=if(nativePreparation!=null)"singbox"else primaryChoice
        require(choice=="singbox") {"Для Steer сначала нужна совместимая конфигурация: текущие параметры не будут отброшены."}
        val runGeneration=generation.coerceAtLeast(1)
        val nativeUidBypass=nativePreparation!=null||carrier!=null||optionalSession!=null||routeServerSession?.requiresUidBypass==true
        nativeVpnExclusion=nativeUidBypass
        if(vpn&&!nativeUidBypass)ProbeCoordinator.awaitQuiescent(this)
        val platform=forRun(runGeneration,{requested!=epoch||runGeneration!=generation},{plan->
            require(requested==epoch&&runGeneration==generation) {"Подключение отменено"}
            openPrimaryTun(plan,nativeUidBypass)
        }) {tx,rx->
            if(requested==epoch&&runGeneration==generation)TrafficState.update(tx,rx,SystemClock.elapsedRealtime())
        }
        if(nativePreparation!=null){
            nativeProof?.let {proof->
                require(proof.moduleSha==nativePreparation.steerSha&&proof.networkHandle==underlying?.networkHandle&&
                    proof.completedAt[nativeEntry!!.id]?.let {SystemClock.elapsedRealtime()-it in 0..300000}==true){"NATIVE_ROUTE_AUTOMATIC_PROOF_RETIRED"}
            }
            val children=NativeRoutePlatforms(platform,runGeneration){requested!=epoch||runGeneration!=generation||destroyed}.also {nativeRoutePlatforms=it}
            val bootstrap=ServerBootstrapResolver(this,ServerBootstrapPolicy.capture(nativePreparation.state()),platform,runGeneration,{underlying?.networkHandle}){requested!=epoch||runGeneration!=generation||destroyed}
                .also {nativeRouteBootstrap=it}
            val session=NativeRouteWorkers.start(this,prepared,nativePreparation.plan,runGeneration,nativePreparation.steerSha,
                java.util.function.BooleanSupplier{requested!=epoch||runGeneration!=generation||destroyed},children::child,bootstrap::ipv4)
            nativeRouteSession=session;prepared=session.config;runningConfig=prepared
            bootstrap.close();if(nativeRouteBootstrap===bootstrap)nativeRouteBootstrap=null
            session.requireListening()
            ClientEvents.record(this,"Движки","Правила/DNS: внешний диспетчер sing-box; протоколы выбранных серверов: Steer. Основной выбор и серверы правил не изменены.")
        }
        val dispatcherDeath=java.util.concurrent.atomic.AtomicBoolean()
        val server=connectPrimary(choice,runGeneration,platform,onDeath={
            if(runGeneration==generation&&requested==epoch){
                if(nativePreparation!=null)synchronized(steerRunLock){dispatcherDeath.set(true)}
                (platform as? AutoCloseable)?.close()
                if(nativePreparation!=null)nativeDispatcherDied(runGeneration,requested)
                else main.post {if(runGeneration==generation&&requested==epoch){status="Ошибка подключения: движок завершился";startService(Intent(this,McrfVpnService::class.java).setAction(STOP))}}
            }
        })
        val mode=if(vpn)Engine.Mode.VPN else Engine.Mode.PROXY
        val settings=ClientPrimarySelection.envelope(prepared,vpn,behavior.metered)
        val validation=ConnectTiming.measure(this,"worker_validate"){server.validate("",settings,mode)}
        require(validation.supported) {"Движок не поддерживает профиль (${validation.code})."}
        ConnectTiming.measure(this,"worker_start"){server.start("",settings,mode)}
        if(nativePreparation!=null){nativeRouteSession?.requireListening();require(health()>=0){"Основной сервер Steer не подтвердил передачу через диспетчер."}}
        if(candidate!=null){val delay=health();record(candidate.getString("id"),delay);if(delay<0) throw IOException("Движок не прошёл проверку передачи данных")}
        else if(optionalSession!=null&&(optionalSession?.isAlive()!=true||health()<0))throw IOException("Дополнительное ядро не подтвердило передачу данных. ${optionalSession?.failureDetails().orEmpty()}")
        carrier?.checkFailure()
        if(requested!=epoch) throw IOException("Подключение отменено")
        val ready=nativeEntry?.let {captureSteerReady(it.id,SteerProbePlan.profile(it),nativeProof)}
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
        val state=routes.snapshot()
        val dispatcher=RouteServerPlan.activeIds(state).isNotEmpty()
        val snapshot=if(dispatcher)null else ClientPrimarySelection.steerSnapshot(this,state,true)
        if(snapshot!=null)require(snapshot.supported) {ClientPrimarySelection.steerFailure(snapshot)}
        val connections=Connections(this);connections.load()
        fun begin(entry:ServerImport.Server,proof:SteerProbeDelays?=null){
            if(dispatcher){
                val preparation=NativeRoutePreparation.capture(this,entry,state,connections.entries())
                startSourceWithCore(preparation.source(),null,true,port,applyRoutes,requested,keepTraffic||tunnel!=null,null,"steer",preparation,entry,proof)
            }else startSteerPrepared(template,SteerPrepared(SteerProbePlan.profile(entry),entry.host,entry.name,entry.id,requireNotNull(requireNotNull(snapshot).settingsJson),proof=proof,serverDns=ServerBootstrapPolicy.capture(state)),
                port,applyRoutes,requested,keepTraffic)
        }
        if(autoAllowed()) {
            fun paused(){
                currentStart=RuntimeStart(JSONObject(template.toString()),null,true,port,applyRoutes,requested)
                status=if(autoAllowed())"VPN · Steer: отмеченные серверы недоступны, трафик приостановлен"else"VPN · Steer: автовыбор выключен, трафик приостановлен"
                foreground(status);if(autoAllowed())scheduleSteerHealth(requireNotNull(currentStart))
            }
            val pool=SteerAutomaticPool(ConnectionSections.entries(connections.entries(),false),connections.autoIds(),System.nanoTime().coerceAtLeast(1))
            require(pool.candidates.isNotEmpty()) {"Нет отмеченных серверов, совместимых с собственным движком Steer. Отметки не изменены; другой движок автоматически не выбирается."}
            val native=ProbeCoordinator.measureSteerRuntime(this,pool.measurements,healthUrl) {
                requested!=epoch||destroyed||currentService!==this||!autoAllowed()
            } as? SteerProbeDelays ?:throw IOException("Не получены собственные результаты Steer.")
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
                }catch(_:Exception){
                    closeCore(keepTun=true)
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
    private class SteerPrepared(val original:String,val host:String,val name:String,val id:String,val settings:String,val fixtureDial:String?=null,val proof:SteerProbeDelays?=null,val serverDns:ServerBootstrapPolicy?=null)
    private fun startCompactWireGuard(template:JSONObject,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long) {
        require(vpn&&applyRoutes&&!fixtureMode&&preferences.getBoolean("catalogueProfile",false)&&!preferences.getBoolean("automatic",false)) {
            "Компактный WG: выбери один WG/AWG-профиль в режиме VPN, без автовыбора или JSON-профиля."
        }
        require(ClientPrimarySelection.effectiveWireGuardChoice(this,ClientPrimarySelection.requireSelected(this),true)=="steer") {
            "При установленном sing-box WG/AWG подключается через sing-box."
        }
        val entries=Connections(this);entries.load()
        val entry=entries.entries().singleOrNull {it.id==preferences.getString("selected","")}
            ?:throw IOException("Выбери WG/AWG-профиль.")
        require(WgAddonPolicy.isWireGuard(entry.protocol)&&entry.carrier==null&&entry.configuration==null) {"Компактному WG нужен WG/AWG-профиль, не другой транспорт."}
        // This prestart owner is retired before the packet worker's forRun.
        // It cannot establish a TUN or replace the chosen server-address DNS.
        val routing=RoutingStore(this).also {it.load()}.snapshot()
        val runGeneration=generation.coerceAtLeast(1)
        val platform=forRun(runGeneration,{requested!=epoch||runGeneration!=generation||destroyed},
            {throw IOException("WG_BOOTSTRAP_TUN_FORBIDDEN")}){_,_->}
        val resolver=ServerBootstrapResolver(this,ServerBootstrapPolicy.capture(routing),platform,runGeneration,{underlying?.networkHandle}){requested!=epoch||runGeneration!=generation||destroyed}
            .also {nativeRouteBootstrap=it}
        val dial=resolver.ipv4(entry.host)
        resolver.close();if(nativeRouteBootstrap===resolver)nativeRouteBootstrap=null
        closePlatform(keepTun=true)
        val prepared=ClientPacketSelection.prepare(this,entry.original,dial)
        val pin=prepared.module.apkSha256
        val run=startPacketPrepared(entry.original,dial,pin,prepared.plan,prepared.routing.routes,prepared.routing.dns,
            prepared.routing.directApps,emptyList(),"MCRF · WG/AWG",requested,prepared.routing.captured.settings,false,prepared.storageGeneration)
        val test=run.second.openConnection(java.net.URL(healthUrl),java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
        val verified=try {test.connectTimeout=10000;test.readTimeout=10000;test.instanceFollowRedirects=false;test.responseCode==204}finally{test.disconnect()}
        check(verified&&requested==epoch&&!destroyed&&core===run.first) {"WG/AWG запущен, но передача данных не подтверждена."}
        TrafficState.reset(entry.name,countersSupported=false) // Distinct from waiting for a supported core's first sample.
        selectedId=entry.id;target=entry.id;runningConfig=null
        status="VPN подключён · WG/AWG · счётчики недоступны";ClientBehaviorUi.applyRecents(this)
        currentStart=RuntimeStart(JSONObject(template.toString()),null,true,port,true,requested)
        rememberVpnSession(requireNotNull(currentStart))
        foreground(status);ClientEvents.record(this,"Клиент","Компактный WG/AWG: передача данных подтверждена")
    }
    private fun startPacketFixture(template:JSONObject,fixture:ClientPacketFixture.Result,port:Int,requested:Long) {
        check(BuildConfig.DEBUG&&fixtureMode&&requested==epoch&&!destroyed)
        val run=startPacketPrepared(fixture.original,fixture.dial,fixture.apkHash,fixture.plan,
            fixture.tunRoutes,fixture.dns,emptyList(),listOf(packageName),"MCRF · WG/AWG fixture",requested,
            fixture.captured.settings,fixture.captureBlocked)
        val server=run.first;val network=run.second
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
                socket.getOutputStream().apply {write(("GET ${fixture.path} HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII));flush()}
                PacketFixtureHttp.verify(socket.getInputStream(),fixture.body)
            }
            if(fixture.managedDns==null)java.net.DatagramSocket(null).use {socket->
                network.bindSocket(socket);socket.soTimeout=5000
                socket.bind(java.net.InetSocketAddress(local,0));socket.connect(target,53)
                val query=PacketFixtureDns.query(fixture.body.removePrefix("mcrf-fixture-"),ipv6,java.security.SecureRandom().nextInt(65536))
                socket.send(java.net.DatagramPacket(query,query.size))
                val packet=java.net.DatagramPacket(ByteArray(513),513);socket.receive(packet)
                PacketFixtureDns.verify(query,packet.data.copyOfRange(packet.offset,packet.offset+packet.length),ipv6)
            }
        }
        if(fixture.managedDns!=null)PacketManagedDnsFixture.verify(network,fixture){check(requested==epoch&&!destroyed&&core===server)}
        else for(ipv6 in listOf(false,true)) {
            val cancellation=CancellationSignal()
            val answer=java.util.concurrent.CompletableFuture<List<java.net.InetAddress>>()
            try {
                android.net.DnsResolver.getInstance().query(network,fixture.body.removePrefix("mcrf-fixture-")+".invalid",
                    if(ipv6)android.net.DnsResolver.TYPE_AAAA else android.net.DnsResolver.TYPE_A,
                    android.net.DnsResolver.FLAG_NO_CACHE_LOOKUP or android.net.DnsResolver.FLAG_NO_CACHE_STORE,
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
        status="VPN подключён · WG/AWG fixture";foreground(status)
        currentStart=RuntimeStart(JSONObject(template.toString()),null,true,port,false,requested)
        rememberVpnSession(requireNotNull(currentStart))
    }
    /** Shared packet ingress construction; admission belongs to the caller, not the native backend. */
    private fun startPacketPrepared(original:String,dial:String,apkHash:String,plan:CompactWireGuardPlan.Plan,
        routes:List<String>,dns:List<String>,excludedApps:List<String>,includedApps:List<String>,label:String,requested:Long,routeSettings:String="{}",ownedBlockFixture:Boolean=false,storageGeneration:String?=null):Pair<ClientPrimaryWorker,Network> {
        check(vpnMode&&requested==epoch&&!destroyed)
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
            if(runGeneration==generation&&requested==epoch){(platform as? AutoCloseable)?.close();main.post {if(runGeneration==generation&&requested==epoch)startService(Intent(this,McrfVpnService::class.java).setAction(STOP))}}
        })
        core=server
        val physical=underlying?:throw IOException("PACKET_PHYSICAL_NETWORK_REQUIRED")
        val builder=Builder().setSession(label).setMtu(plan.mtu).setBlocking(false).setMetered(behavior.metered)
            .setUnderlyingNetworks(arrayOf(physical))
        plan.addresses.forEach {cidr->val parts=cidr.split('/');builder.addAddress(parts[0],parts[1].toInt())}
        require(routes.isNotEmpty()) {"PACKET_TUN_NO_ROUTES"}
        routes.forEach {cidr->val checked=ClientTunCidr.parse(cidr);val parts=checked.toString().split('/');builder.addRoute(parts[0],parts[1].toInt())}
        dns.forEach {builder.addDnsServer(it)}
        excludedApps.forEach {name->RouteRules.validatePackage(name);require(name!=packageName) {"PACKET_HOST_MUST_BE_INSIDE_VPN"};builder.addDisallowedApplication(name)}
        if(includedApps.isNotEmpty()) {
            require(packageName in includedApps) {"PACKET_HOST_MUST_BE_INSIDE_VPN"}
            includedApps.forEach {name->RouteRules.validatePackage(name);builder.addAllowedApplication(name)}
        }
        ProbeCoordinator.awaitQuiescent(this)
        if(storageGeneration!=null)require(ProfileStore.generation(this)==storageGeneration){"PACKET_SETTINGS_GENERATION_CHANGED"}
        check(requested==epoch&&!destroyed&&currentService===this&&core===server) {"PACKET_START_RETIRED"}
        val descriptor=ConnectTiming.measure(this,"tun_establish"){builder.establish()}?:throw IOException("PACKET_TUN_NOT_GRANTED")
        val previous=tunnel;tunnel=descriptor;previous?.close();nativeVpnExclusion=false;localProxyPort=0
        val cm=getSystemService(android.net.ConnectivityManager::class.java)
        val deadline=SystemClock.elapsedRealtime()+8000
        var selected:Network?=null
        while(requested==epoch&&!destroyed&&core===server&&SystemClock.elapsedRealtime()<deadline&&selected==null) {
            selected=cm.allNetworks.firstOrNull {network->
                val caps=cm.getNetworkCapabilities(network)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true&&caps.ownerUid==android.os.Process.myUid()&&
                    cm.getLinkProperties(network)?.linkAddresses?.let {actual->actual.size==plan.addresses.size&&plan.addresses.all {cidr->
                        val parts=cidr.split('/');actual.any {it.address==java.net.InetAddress.getByName(parts[0])&&it.prefixLength==parts[1].toInt()}
                    }}==true
            }
            if(selected==null)Thread.sleep(50)
        }
        val network=selected?:throw IOException("PACKET_VPN_NETWORK_REQUIRED")
        if(storageGeneration!=null)require(ProfileStore.generation(this)==storageGeneration){"PACKET_SETTINGS_GENERATION_CHANGED"}
        check(requested==epoch&&!destroyed&&currentService===this&&core===server) {"PACKET_START_RETIRED"}
        val managed=PacketRouteEnvelope.decode(routeSettings,plan.routes,plan.addresses).managed!=null
        server.startPacket(original,descriptor,dial,routeSettings,ownedBlockFixture,if(managed)network.networkHandle else 0)
        return server to network
    }

    private fun startSteerPrepared(template:JSONObject,entry:SteerPrepared,port:Int,applyRoutes:Boolean,requested:Long,keepTraffic:Boolean) {
        require(vpnMode&&(!fixtureMode||BuildConfig.DEBUG&&entry.fixtureDial!=null)) {"Steer требует системный VPN."}
        if(requested!=epoch||destroyed||currentService!==this)throw IOException("Подключение отменено")
        engineUsage=EngineUpdateGate.acquireUsage(this)?:throw IOException("Заверши установку движка.")
        val runGeneration=generation.coerceAtLeast(1)
        var proofPinned=false
        val unexpectedDeath=java.util.concurrent.atomic.AtomicBoolean()
        val platform=forRun(runGeneration,{requested!=epoch||runGeneration!=generation||
            proofPinned&&entry.proof?.networkHandle?.let {underlying?.networkHandle!=it}==true},{throw IOException("Steer использует заранее созданный TUN")}) {tx,rx->
            if(requested==epoch&&runGeneration==generation)TrafficState.update(tx,rx,SystemClock.elapsedRealtime())
        }
        entry.proof?.let {proof->
            val installed=EngineUpdatePrimary.installation(this,"steer")
            require(ClientPrimaryWorkerWire.hashFile(File(installed.sourceDir))==proof.moduleSha&&
                underlying?.networkHandle==proof.networkHandle&&
                proof.completedAt[entry.id]?.let {SystemClock.elapsedRealtime()-it in 0..300000}==true) {"STEER_AUTOMATIC_PROOF_RETIRED"}
        }
        proofPinned=true
        val server=connectPrimary("steer",runGeneration,platform,onDeath={
            val recovery=synchronized(steerRunLock) {
                if(runGeneration==generation&&requested==epoch){
                    unexpectedDeath.set(true)
                    currentStart?.also {if(autoAllowed())status="VPN · Steer: сервер недоступен, трафик приостановлен"}
                }else null
            }
            if(runGeneration==generation&&requested==epoch) {
                if(autoAllowed()) {
                    if(recovery!=null)foreground(status)
                    (platform as? AutoCloseable)?.close() // Exact old callback owner, never a newer run/TUN.
                    if(recovery!=null)main.post {
                        if(runGeneration==generation&&requested==epoch&&!destroyed&&!worker.isShutdown&&currentService===this@McrfVpnService&&autoAllowed()) {
                            ProbeCoordinator.cancelRuntime(this)
                            worker.execute {
                                if(runGeneration==generation&&requested==epoch&&!destroyed&&currentService===this@McrfVpnService&&autoAllowed()) {
                                    try {startSource(recovery.template,null,true,recovery.port,recovery.applyRoutes,requested,true)}
                                    catch(_:Exception){if(requested==epoch&&!destroyed&&autoAllowed()){
                                        closeCore(keepTun=true);currentStart=recovery
                                        status="VPN · Steer: сервер недоступен, трафик приостановлен";foreground(status);scheduleSteerHealth(recovery)
                                    }}
                                }
                            }
                        }
                    }
                }else {(platform as? AutoCloseable)?.close();main.post {if(runGeneration==generation&&requested==epoch&&!destroyed)startService(Intent(this,McrfVpnService::class.java).setAction(STOP))}}
            }
        })
        val settings=entry.settings
        require(server.validate(entry.original,settings,Engine.Mode.VPN).supported) {"Steer не поддерживает параметры выбранного сервера. Профиль не изменён."}
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
        admittedPlan.excludedApps.forEach {name->require(name!=packageName);builder.addDisallowedApplication(name)}
        admittedPlan.includedApps.forEach {name->builder.addAllowedApplication(name)}
        if(entry.fixtureDial!=null&&JSONObject(settings).optInt("steerVersion",1)==2){
            // Exact ClientSteerFixture.p1Snapshot ONLY, admitted before this
            // entry exists. Narrow the synthetic VPN to our UID, not user apps.
            // Does not rewrite ordinary model2 app scope or any saved setting.
            check(BuildConfig.DEBUG&&fixtureMode&&admittedPlan.includedApps.isEmpty()&&admittedPlan.excludedApps.isEmpty()) {"STEER_P1_OWNED_UID_SCOPE"}
            val scope=fixtureP1OwnerHelperSha?.let {ClientP1OwnerFixtureScope.packages(this,it)}?:listOf(packageName)
            scope.forEach {builder.addAllowedApplication(it)}
        }
        val descriptor=ConnectTiming.measure(this,"tun_establish"){builder.establish()}?:throw IOException("Android не разрешил открыть VPN.")
        val previous=tunnel;tunnel=descriptor;previous?.close();nativeVpnExclusion=false;localProxyPort=0
        val connectivity=getSystemService(android.net.ConnectivityManager::class.java)
        val deadline=SystemClock.elapsedRealtime()+8000
        var vpnNetwork:Network?=null
        while(requested==epoch&&SystemClock.elapsedRealtime()<deadline&&vpnNetwork==null){
            vpnNetwork=connectivity.allNetworks.firstOrNull {network->
                val caps=connectivity.getNetworkCapabilities(network)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true&&caps.ownerUid==android.os.Process.myUid()&&
                    connectivity.getLinkProperties(network)?.linkAddresses?.any {it.address.hostAddress=="10.89.0.2"}==true
            }
            if(vpnNetwork==null)Thread.sleep(50)
        }
        val selectedNetwork=vpnNetwork?:throw IOException("Не удалось получить сеть VPN Steer.")
        steerVpnNetwork=selectedNetwork
        if(keepTraffic)TrafficState.handover(entry.name)else TrafficState.reset(entry.name)
        server.start(entry.original,settings,Engine.Mode.VPN,descriptor,dial,bootstrap,selectedNetwork.networkHandle)
        val verified=if(entry.fixtureDial!=null) verifySteerFixtureHealth(selectedNetwork,requested) else {
            val test=selectedNetwork.openConnection(java.net.URL(healthUrl),java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            try {test.connectTimeout=10000;test.readTimeout=10000;test.instanceFollowRedirects=false;test.responseCode==204}finally{test.disconnect()}
        }
        require(verified&&requested==epoch) {"Steer запущен, но передача данных не подтверждена."}
        val ready=captureSteerReady(entry.id,entry.original,entry.proof)
        synchronized(steerRunLock) {
            require(!unexpectedDeath.get()&&runGeneration==generation&&core===server&&server.state in setOf("STARTING","RUNNING")) {"STEER_START_OWNER_DIED"}
            require(ready==null||ready.ready(autoAllowed(),underlying?.networkHandle,SystemClock.elapsedRealtime())){"STEER_READY_PROOF_RETIRED"}
            selectedId=entry.id;target=entry.id;runningConfig=null
            currentStart=RuntimeStart(JSONObject(template.toString()),null,true,port,applyRoutes,requested)
            localProxyPort=server.steerProxyPort
            status="VPN подключён · Steer"+(if(localProxyPort>0)" · прокси 127.0.0.1:$localProxyPort"else"")
        }
        ClientBehaviorUi.applyRecents(this)
        rememberVpnSession(requireNotNull(currentStart))
        startTelemetry();foreground(status);ClientEvents.record(this,"Клиент","Steer: передача данных подтверждена")
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
    private fun steerHealth():Long {
        val selectedNetwork=steerVpnNetwork?:return -1
        if(core?.choiceId!="steer")return -1
        val captured=generation;val requested=epoch;val started=SystemClock.elapsedRealtime()
        return try {
            require(java.net.URI(healthUrl).scheme=="https") {"STEER_HEALTH_HTTPS_REQUIRED"}
            val test=selectedNetwork.openConnection(java.net.URL(healthUrl),java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            val success=try {test.connectTimeout=5000;test.readTimeout=5000;test.instanceFollowRedirects=false;test.responseCode==204}finally{test.disconnect()}
            if(success&&captured==generation&&requested==epoch)SystemClock.elapsedRealtime()-started else -1
        }catch(_:Exception){-1}
    }
    private fun captureSteerReady(id:String,profile:String,proof:SteerProbeDelays?):SteerReadyAdmission? {
        if(proof==null)return null
        val latest=Connections(this);latest.load()
        val entry=latest.entries().singleOrNull {it.id==id}
        val matches=entry!=null&&try{SteerProbePlan.profile(entry)==profile}catch(_:Exception){false}
        return SteerReadyAdmission(id in latest.autoIds(),matches,proof.completedAt[id]?:Long.MIN_VALUE,proof.networkHandle)
    }
    private fun nativeDispatcherDied(captured:Long,requested:Long){
        if(captured!=generation||requested!=epoch||destroyed||currentService!==this)return
        val retry=currentStart?:return // Startup's existing caller owns rollback/retry.
        status="VPN · Steer: диспетчер остановился, трафик приостановлен"
        try{foreground(status)}catch(_:Exception){}
        main.post {
            if(captured==generation&&requested==epoch&&!destroyed&&!worker.isShutdown&&currentService===this)try {worker.execute {
                if(captured!=generation||requested!=epoch||destroyed||currentService!==this)return@execute
                closeCore(keepTun=true)
                currentStart=retry
                if(!autoAllowed())return@execute // Preserve the captured TUN; no Direct fallback.
                try{startSource(retry.template,null,true,retry.port,retry.applyRoutes,requested,true)}
                catch(_:Exception){if(requested==epoch&&!destroyed&&currentService===this){
                    closeCore(keepTun=true);currentStart=retry
                    status="VPN · Steer: отмеченные серверы недоступны, трафик приостановлен";foreground(status)
                    scheduleSteerHealth(retry)
                }}
            }}catch(_:java.util.concurrent.RejectedExecutionException){ /* Destruction owns retirement; never restart a replacement run. */ }
        }
    }
    private fun scheduleSteerHealth(source:RuntimeStart,immediate:Boolean=false) {
        if(!autoAllowed()||source.requested!=epoch||destroyed||networkRecovery!=null)return
        healthTask?.let(main::removeCallbacks)
        val captured=generation
        val task=Runnable {
            if(autoAllowed()&&source.requested==epoch&&captured==generation&&!destroyed&&networkRecovery==null)worker.execute {
                if(!autoAllowed()||source.requested!=epoch||captured!=generation||destroyed||networkRecovery!=null)return@execute
                if(ProbeCoordinator.measurements!=null){scheduleSteerHealth(source);return@execute}
                val healthy=(if(nativeRouteSession!=null)health()else steerHealth())>=0;val previous=selectedId
                if(!fixtureMode&&AutoSelectionDeviation.keepHealthy(healthy,AutoSelectionDeviation.failureOnly(preferences))){scheduleSteerHealth(source);return@execute}
                var replaced=false
                try {
                    val catalogue=Connections(this);catalogue.load()
                    val pool=SteerAutomaticPool(ConnectionSections.entries(catalogue.entries(),false),catalogue.autoIds(),System.nanoTime().coerceAtLeast(1))
                    require(pool.candidates.isNotEmpty()) {"STEER_AUTOMATIC_NO_COMPATIBLE_MARKS"}
                    val native=ProbeCoordinator.measureSteerRuntime(this,pool.measurements,healthUrl) {
                        source.requested!=epoch||destroyed||currentService!==this||!autoAllowed()||captured!=generation
                    } as? SteerProbeDelays ?:throw IOException("STEER_AUTOMATIC_WRONG_BACKEND")
                    if(source.requested!=epoch||captured!=generation||!autoAllowed())return@execute
                    val latest=Connections(this);latest.load()
                    val ranked=pool.stillMarked(pool.rank(native,SystemClock.elapsedRealtime(),if(healthy)null else previous),latest.entries(),latest.autoIds())
                    val next=ranked.firstOrNull()?:throw IOException("STEER_AUTOMATIC_NO_FRESH_SUCCESS")
                    if(healthy&&(next.id==previous||previous in latest.autoIds()&&!SteerAutomaticPool.better(native.getValue(next.id),native[previous]?:-1,AutoSelectionDeviation.millis(preferences)))) {
                        scheduleSteerHealth(source);return@execute
                    }
                    val routes=RoutingStore(this);routes.load()
                    val state=routes.snapshot()
                    val useDispatcher=RouteServerPlan.activeIds(state).isNotEmpty()
                    val prepared=if(useDispatcher)null else ClientPrimarySelection.steerSnapshot(this,state,true)
                    if(prepared!=null)require(prepared.supported) {"STEER_AUTOMATIC_CONFIG_CHANGED"}
                    val dispatch=if(useDispatcher)NativeRoutePreparation.capture(this,next,state,latest.entries())else null
                    require(SystemClock.elapsedRealtime()-native.completedAt.getValue(next.id) in 0..300000) {"STEER_AUTOMATIC_PROOF_EXPIRED"}
                    replaced=true
                    closeCore(keepTun=true)
                    if(source.requested!=epoch||!autoAllowed()||destroyed)return@execute
                    if(dispatch!=null)startSourceWithCore(dispatch.source(),null,true,source.port,source.applyRoutes,source.requested,true,null,"steer",dispatch,next,native)
                    else startSteerPrepared(source.template,SteerPrepared(SteerProbePlan.profile(next),next.host,next.name,next.id,requireNotNull(requireNotNull(prepared).settingsJson),proof=native,serverDns=ServerBootstrapPolicy.capture(state)),
                        source.port,source.applyRoutes,source.requested,true)
                    ClientEvents.record(this,"Автовыбор","Steer: выполнено переключение на проверенный отмеченный сервер")
                    scheduleSteerHealth(requireNotNull(currentStart))
                }catch(_:Exception){
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
        healthTask=task;main.postDelayed(task,if(immediate)0L else preferences.getInt("interval",60).coerceIn(5,86400)*1000L)
    }
    private fun health():Long {
        return try{core?.health(target,healthUrl,10000)?:-1L}catch(_:Exception){-1L}
    }
    private fun record(id:String,delay:Long){
        if(fixtureMode)return
        bsHotMeasurements.remove(id);bsHotMeasurements[id]=SystemClock.elapsedRealtime() to delay
        if(bsHotMeasurements.size>1024)bsHotMeasurements.remove(bsHotMeasurements.keys.first())
        try{val catalogue=Connections(this);catalogue.load();catalogue.recordProbes(mapOf(id to delay))}catch(_:Exception){}
    }
    private fun readFixture(path:String):JSONObject {
        check(BuildConfig.DEBUG)
        val file=File(path);val parent=file.parentFile ?: throw IOException("Missing fixture")
        require(parent.name.matches(Regex("mcrf-test-[0-9a-f-]{36}"))&&parent.canonicalFile.parentFile==cacheDir.canonicalFile&&file.name=="service-fixture.json"&&file.canonicalFile.parentFile==parent.canonicalFile&&
            !java.nio.file.Files.isSymbolicLink(parent.toPath())&&!java.nio.file.Files.isSymbolicLink(file.toPath()))
        val data=file.inputStream().use { JSONObject(String(ImportIO.read(it),Charsets.UTF_8)) }
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
        return data.getJSONObject("source")
    }
    private fun chooseEngine(source:JSONObject,candidates:List<JSONObject>,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long):JSONObject {
        val ordered=if(!fixtureMode&&preferences.getBoolean(BsDetectionMonitor.KEY,false))
            BsAutoSelectionPolicy.targets(candidates,BsAutoSelectionPolicy.last(preferences,true),BsAutoSelectionPolicy.selected(preferences)){it.getString("id")}else candidates
        for(candidate in ordered){
            if(requested!=epoch) throw IOException("Подключение отменено")
            try{startSource(source,candidate,vpn,port,applyRoutes,requested,true);return candidate}
            catch(_:Exception){record(candidate.getString("id"),-1L);closeCore(vpn)}
        }
        throw IOException("Отмеченные движки БС не подтвердили передачу данных.")
    }
    private fun refreshBsPool(source:JSONObject,requested:Long):Pair<JSONObject,List<JSONObject>> {
        val fresh=if(fixtureMode)source else {
            val catalogue=Connections(this).apply {load()}
            BsPlan.refresh(source,catalogue.bsStartupEntries(),preferences.getInt("interval",60).coerceIn(5,86400))
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
                if(!autoAllowed()||networkRecovery!=null||requested!=epoch||session!=generation) return@execute
                val refreshed=try{refreshBsPool(source,requested)}catch(_:Exception){
                    // Empty marks/storage failure must not resurrect the stale launch pool or stop healthy flows.
                    if(autoAllowed()&&requested==epoch&&session==generation)
                        scheduleHealth(source,candidates,selected,interval,vpn,port,applyRoutes,requested)
                    return@execute
                }
                val freshSource=refreshed.first;val freshCandidates=refreshed.second
                val freshInterval=freshSource.getJSONObject("mcrf_bs").getInt("interval")
                val delay=health();record(selected.getString("id"),delay)
                if (!autoAllowed() || networkRecovery!=null || requested!=epoch || session!=generation) return@execute
                val reconsidered=reconsiderCarrier(freshCandidates,selected,delay,requested,session)
                if(reconsidered!=null) scheduleHealth(freshSource,freshCandidates,reconsidered,freshInterval,vpn,port,applyRoutes,requested)
                else if(delay>=0) scheduleHealth(freshSource,freshCandidates,selected,freshInterval,vpn,port,applyRoutes,requested)
                else try{
                    status="Переключаем движок БС…";foreground(status)
                    val next=chooseEngine(freshSource,freshCandidates.filter { it.getString("id")!=selected.getString("id") },vpn,port,applyRoutes,requested)
                    scheduleHealth(freshSource,freshCandidates,next,freshInterval,vpn,port,applyRoutes,requested)
                }catch(_:Exception){if(requested==epoch){closeCore(vpn);if(requested==epoch&&!destroyed&&currentService===this){status=if(vpn) "VPN подключён · движки БС недоступны, трафик приостановлен" else "Прокси 127.0.0.1:$port · движки БС недоступны";foreground(status);scheduleRetry(freshSource,freshCandidates,freshInterval,vpn,port,applyRoutes,requested)}}}
            }
        }
        healthTask=task;main.postDelayed(task,(if(fixtureMode)interval else preferences.getInt("interval",60).coerceIn(5,86400))*1000L)
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
            candidate.getString("id")!=selected.getString("id")&&
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
                val delay=CarrierSocksSwitch.probe(transport,healthUrl,3000);record(id,delay)
            if(!live()||!AutoSelection.prefer(candidate.getJSONObject("profile").getString("type"),delay,currentKind,if(currentMarked)currentDelay else -1L,AutoSelectionDeviation.millis(preferences)))continue
                if(existing!=null)board.selectExisting(id)else board.select(id,transport)
                adopted=true;carrier=transport;selectedId=id
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
            try{
                val (freshSource,freshCandidates)=refreshBsPool(source,requested)
                val next=chooseEngine(freshSource,freshCandidates,vpn,port,applyRoutes,requested)
                scheduleHealth(freshSource,freshCandidates,next,freshSource.getJSONObject("mcrf_bs").getInt("interval"),vpn,port,applyRoutes,requested)
            }
            catch(_:Exception){if(requested==epoch){closeCore(vpn);if(requested==epoch&&!destroyed&&currentService===this)scheduleRetry(source,candidates,interval,vpn,port,applyRoutes,requested)}}
        } }
        healthTask=task;main.postDelayed(task,(if(fixtureMode)interval else preferences.getInt("interval",60).coerceIn(5,86400))*1000L)
    }
    private fun autoAllowed() = !autoDisabled && (fixtureMode || preferences.getBoolean("automatic", false))
    private fun freezeSelection() {
        val server = core ?: return
        if(server.choiceId=="steer"||nativeRouteSession!=null) {
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
        val state = TrafficState.current; val server = state.server.ifEmpty { "Подключение" }
        val counters=preferences.getBoolean(NotificationTrafficSettings.ENABLED,true)
        val traffic=if(counters)TrafficState.notificationTraffic(state)else if(ConnectionPresentation.connected(message))"Подключено к $server"else"$message · $server"
        val content=Triple(message,server,traffic)
        if(lastForegroundContent==content)return
        if(!foregroundChannelCreated){
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("vpn", "Подключение MCRF", NotificationManager.IMPORTANCE_LOW))
            foregroundChannelCreated=true
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, McrfVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = AndroidNotification.Builder(this, "vpn").setContentTitle(if(counters)"MCRF · $server"else"MCRF")
            .setContentText(traffic)
            .setStyle(AndroidNotification.BigTextStyle().bigText(if(counters)"$message\n$server\n$traffic"else traffic))
            .setSmallIcon(R.drawable.status_icon).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(AndroidNotification.Action.Builder(null, "Остановить", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, notification)
        lastForegroundContent=content
    }
    private fun connectPrimary(choice:String,runGeneration:Long,platform:Platform,onDeath:()->Unit):ClientPrimaryWorker = try {
        val connected=ConnectTiming.measure(this,"core_init"){ClientPrimaryWorker.connect(this,choice,runGeneration,platform,onDeath=onDeath)}
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
        releaseBsPowerLease()
        currentStart=null
        steerVpnNetwork=null
        healthTask?.let(main::removeCallbacks);healthTask=null
        generation++; TrafficState.stop()
        val client = telemetry; telemetry = null; if(client!=null)main.removeCallbacks(client)
        notificationTraffic?.close();notificationTraffic=null
        runningConfig=null;runningDirectDnsIpv4=null
        val batch=retirement?:run {
            val previous=core
            val transport=carrier
            val board=carrierSwitchboard
            val extra=optionalSession
            val routeExtras=routeServerSession
            val nativeRoutes=nativeRouteSession
            val nativePlatforms=nativeRoutePlatforms
            val bootstrap=nativeRouteBootstrap
            val usage=engineUsage
            val hold=fixtureRetirementHold.takeIf {BuildConfig.DEBUG&&fixtureMode}
            ClientPrimaryRetirement(previous,
                requestStop={previous?.requestStop()},
                retireCallbacks={closePlatform(keepTun=true)},
                closeOwner={
                    if(hold!=null){
                        require(!java.nio.file.Files.isSymbolicLink(hold.toPath()))
                        check(!hold.exists()) {"DEBUG_PARENT_RETIREMENT_ACK_HELD"}
                    }
                    it.close()
                },
                dependents=listOf({closePlatform(keepTun)},{board?.close();transport?.close();Unit},{bootstrap?.close();Unit},{nativeRoutes?.close();Unit},{nativePlatforms?.close();Unit},{routeExtras?.close();Unit},{extra?.close();Unit},{usage?.close();Unit}),
                completed={
                    if(core===previous)core=null
                    if(carrier===transport)carrier=null
                    if(carrierSwitchboard===board)carrierSwitchboard=null
                    if(optionalSession===extra)optionalSession=null
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
    private fun startTelemetry() {
        scheduleBsWifiCheck()
        val session = generation
        val requested = epoch
        notificationTraffic?.close()
        val fixedOutbound=OutboundObservation.fixed(runningConfig,target)
        fun live()=session==generation&&requested==epoch&&core!=null&&!destroyed&&currentService===this@McrfVpnService
        notificationTraffic=try{NotificationTrafficSampler(this,main,worker,{live()&&TrafficState.current.countersSupported},{
            val current=core
            if(live()&&current!=null){
                val stats=current.stats()
                val tx=stats.txPayload.value;val rx=stats.rxPayload.value
                if(live()&&core===current&&tx!=null&&rx!=null)TrafficState.update(tx,rx,SystemClock.elapsedRealtime())
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
                optionalSession?.reportFailures {tag->
                    ClientEvents.record(this@McrfVpnService,"Маршруты",if(tag.startsWith("mcrf-route-"))
                        "Модуль сервера отдельного правила остановился. Трафик этого правила не переводится на основной сервер."
                        else "Дополнительный модуль остановился. Его выходы не заменяются другим сервером автоматически.")
                    optionalSession?.failureDetail(tag)?.let {ClientEvents.record(this@McrfVpnService,"Ошибка движка",it)}
                }
                val transportFailure=if(automaticCarrier)null else carrier?.runtimeFailure()
                if(transportFailure!=null){
                    val failure="Ошибка подключения: $transportFailure"+
                        if(vpnMode)" Трафик приостановлен до нового подключения или отключения VPN."else""
                    status=failure;try{foreground(status)}catch(_:Exception){}
                    ClientEvents.record(this@McrfVpnService,"Ошибка",failure)
                    if(!fixtureMode)preferences.edit().putBoolean("wantedRunning",false).apply()
                    // Retain an established TUN while retiring its exact owner;
                    // do not silently expose VPN traffic directly on failure.
                    closeCore(keepTun=vpnMode)
                    if(requested==epoch&&!destroyed&&currentService===this@McrfVpnService){status=failure;try{foreground(status)}catch(_:Exception){}}
                    return@execute
                }
                val outbound=if(current.choiceId=="singbox")fixedOutbound?:current.selectedOutbound(target)else""
                if(session==generation&&outbound.isNotEmpty()){
                    selectedOutbound=outbound
                    val hot=currentStart?.candidate?.takeIf {carrierSwitchboard!=null}
                    if(hot!=null){selectedId=hot.getString("id");TrafficState.server(hot.getString("name"))}
                    else {ids[outbound]?.let {selectedId=it};names[outbound]?.let(TrafficState::server)}
                }
                carrierSwitchboard?.retireDrained()
            }catch(_:Exception){}finally{main.post {if(session==generation&&requested==epoch&&core!=null&&!destroyed&&currentService===this@McrfVpnService){foreground(status);main.postDelayed(this,2000)}}}}
        }}
        telemetry=task;main.post(task)
    }
    private fun openPrimaryTun(options: Platform.TunPlan,nativeUidBypass:Boolean):ParcelFileDescriptor {
        if (!vpnMode) throw IOException("TUN выключен.")
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
        var restricted = false
        val ownerScope=fixtureP1OwnerHelperSha?.let { sha ->
            check(BuildConfig.DEBUG&&fixtureMode&&!nativeUidBypass&&options.includePackage.isEmpty()&&options.excludePackage.isEmpty()) { "P1_OWNER_SCOPE_CONFLICT" }
            ClientP1OwnerFixtureScope.packages(this,sha)
        }
        if(ownerScope!=null){
            // The UID lookup API sees VPN-covered UIDs, even for loopback
            // sockets. Include the actual helper, but never capture user apps.
            ownerScope.forEach(builder::addAllowedApplication);restricted=true
        }else{
            options.includePackage.forEach {name->if (!nativeUidBypass || name != packageName) { builder.addAllowedApplication(name); restricted = true } }
            options.excludePackage.forEach(builder::addDisallowedApplication)
        }
        // Only carriers need UID exclusion. Ordinary app requests must still traverse the VPN.
        if (nativeUidBypass && !restricted) builder.addDisallowedApplication(packageName)
        underlying?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        val next = ConnectTiming.measure(this,"tun_establish"){builder.establish()} ?: throw IOException("Android не разрешил открыть VPN.")
        return next // HostRun alone publishes/closes this descriptor after its final generation check.
    }
    override fun onRevoke() {
        ServerSwitchTransition.observeCommand(null);val requested=++epoch
        bsWifiPaused=null;releaseBsPowerLease()
        ProbeCoordinator.cancelRuntime(this);clearNetworkRecovery();core?.requestStop()
        if(!fixtureMode)preferences.edit().putBoolean("wantedRunning",false).apply()
        if(!destroyed)worker.execute {closeCore();if(!destroyed&&currentService===this&&requested==epoch){
            status="VPN-разрешение отозвано";ClientEvents.record(this,"Клиент",status);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        }}
    }
    override fun onDestroy() {
        ProbeCoordinator.cancelRuntime(this)
        bsDetection?.close();bsDetection=null
        bsWifiPaused=null;main.removeCallbacks(bsWifiCheck);bsWifiMonitor?.close();bsWifiMonitor=null
        // Register synchronously before Android can create the replacement
        // Service: even a queued cleanup task must block its new native START.
        retiringServices.add(this)
        destroyed=true;epoch++;clearNetworkRecovery();core?.requestStop()
        releaseBsPowerLease()
        if(wakeRegistered){try{unregisterReceiver(wakeReceiver)}catch(_:IllegalArgumentException){};wakeRegistered=false}
        ClientBehaviorUi.applyRecents(this);localProxyPort=0
        worker.execute {
            closeCore()
            if(currentService===this){
                if(status.startsWith("VPN подключён")||status.startsWith("Прокси ")||status=="Подключение…"||status=="Восстановление соединения…"||status=="Возвращаемся в VPN…"||status=="Остановка движка…")status="Отключено"
                currentService=null
            }
            retiringServices.remove(this)
        }
        worker.shutdown()
        super.onDestroy()
    }
    companion object {
        @Volatile private var currentService:McrfVpnService?=null
        private val retiringServices=ConcurrentHashMap.newKeySet<McrfVpnService>()
        @JvmStatic fun cleanupPending()=status=="Остановка движка…"||ClientPrimaryRetirement.hasPending()||retiringServices.isNotEmpty()
        @JvmStatic fun ownsCurrentTun()=currentService?.let {it.tunnel!=null&&!it.destroyed}==true
        @JvmStatic fun wifiPaused()=currentService?.let {!it.destroyed&&it.bsWifiPaused?.requested==it.epoch}==true
        internal fun refreshBsEnvironment(context:Context) {
            if(currentService?.destroyed==false)context.startService(Intent(context,McrfVpnService::class.java).setAction(REFRESH_BS_ENVIRONMENT))
        }
        const val START = "org.mcrf.client.START"
        const val STOP = "org.mcrf.client.STOP"
        const val AUTO_OFF = "org.mcrf.client.AUTO_OFF"
        const val RELOAD_ROUTES = "org.mcrf.client.RELOAD_ROUTES"
        private const val REFRESH_BS_ENVIRONMENT="org.mcrf.client.REFRESH_BS_ENVIRONMENT"
        @JvmField @Volatile var status = "Отключено"
        @JvmField @Volatile var selectedId = ""
        @JvmField @Volatile var pendingWhitelists:Boolean?=null
        @JvmField @Volatile var autoBsSwitchSerial=0L
        @JvmField @Volatile var autoVpnSwitchSerial=0L
        @JvmField @Volatile var nativeVpnExclusion = false
        @JvmField @Volatile var localProxyPort=0
    }
}

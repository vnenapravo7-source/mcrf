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
    private var carrier: NativeCarrier? = null
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
                status="Переключаем движок БС…";foreground("Сайты не ответили напрямую; переключаемся на белые списки")
                val chosen=chooseEngine(template,candidates,previous.vpn,previous.port,previous.applyRoutes,stamp.epoch)
                if(autoAllowed())scheduleHealth(template,candidates,chosen,preferences.getInt("interval",60),previous.vpn,previous.port,previous.applyRoutes,stamp.epoch)
                autoBsSwitchSerial++
                ClientEvents.record(this,"Клиент","Автоопределение БС: подключение белых списков подтверждено. Возврат к сохранённому VPN — после восстановления доступа.")
                if(autoSectionNotice.accept(true,autoBsSwitchSerial,stamp.epoch,epoch,!destroyed&&currentService===this))AutoSectionNotifications.post(this,true)
            }catch(_:Exception){
                if(!destroyed&&currentService===this&&epoch==stamp.epoch){
                    autoDetectionRetryAfter=SystemClock.elapsedRealtime()+300000
                    ClientEvents.record(this,"Предупреждение","Автоопределение БС: переключение не выполнено. Проверь серверы, движки и выбранное основное ядро.")
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
        return try{AutoVpnReturn.save(this,session.template,session.vpn,session.port,session.applyRoutes,requireNotNull(ClientPrimarySelection.selected(this)));true}
        catch(_:Exception){ClientEvents.record(this,"Предупреждение","Не удалось сохранить цель автоматического возврата в VPN. Текущее подключение не изменено.");false}
    }
    private fun switchAutomaticallyToVpn(stamp:BsDetectionMonitor.Stamp){
        if(worker.isShutdown)return
        try{worker.execute {
            fun live()=!destroyed&&currentService===this&&epoch==stamp.epoch&&generation==stamp.generation&&preferences.getBoolean(BsDetectionMonitor.KEY,false)
            val previous=currentStart?:return@execute
            if(!live()||SystemClock.elapsedRealtime()<autoDetectionRetryAfter||
                (!previous.template.has("mcrf_carrier")&&!previous.template.has("mcrf_bs")))return@execute
            var switching=false
            try{
                val saved=AutoVpnReturn.load(this)?:throw IOException("Нет сохранённого VPN-подключения")
                if(!live())return@execute
                clearNetworkRecovery();switching=true
                status="Возвращаемся в VPN…";foreground("Прямой доступ восстановлен; проверяем сохранённое VPN-подключение")
                // Same reverse policy: retain the currently chosen mode/listeners/routing, not stale cached controls.
                startSource(saved.template,null,previous.vpn,previous.port,previous.applyRoutes,stamp.epoch,true)
                require(core?.health(target,"https://youtube.com/",2500)?.let {it>=0}==true) {"VPN пока не подтвердил ответ YouTube"}
                require(epoch==stamp.epoch&&!destroyed&&currentService===this)
                autoVpnSwitchSerial++
                ClientEvents.record(this,"Клиент","Автоопределение БС: сохранённое VPN-подключение восстановлено.")
                if(autoSectionNotice.accept(false,autoVpnSwitchSerial,stamp.epoch,epoch,!destroyed&&currentService===this))AutoSectionNotifications.post(this,false)
            }catch(_:Exception){
                autoDetectionRetryAfter=SystemClock.elapsedRealtime()+300000
                if(!destroyed&&currentService===this&&epoch==stamp.epoch){
                    ClientEvents.record(this,"Предупреждение","Автовозврат в VPN не выполнен: нужна сохранённая совместимая VPN-цель с доступом к YouTube. Белые списки сохраняются; повтор не раньше чем через 5 минут.")
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
            }else if(optionalSession!=null||carrier!=null||ClientPrimarySelection.selected(this)=="steer"||
                DirectDnsFamily.requiresRestart(runningDirectDnsIpv4,DirectDnsNetwork.ipv4Only(this,startingPhysical))){
                val frozen=if(autoDisabled)selectedOutbound.takeIf{it.isNotEmpty()}else null
                optionalSession?.networkChanged()
                startSource(session.template,session.candidate,session.vpn,session.port,session.applyRoutes,session.requested,true,frozen)
            }else if(core!=null){core?.resetNetwork();if(status=="Восстановление соединения…"){
                status=if(session.vpn)"VPN подключён"else"Прокси 127.0.0.1:${session.port}";foreground(status)
            }}
            else startSource(session.template,session.candidate,session.vpn,session.port,session.applyRoutes,session.requested,true)
            clearNetworkRecovery()
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
        if(incoming?.action!=STOP&&(EngineUpdates.installationPending(this)||EngineUpdateScripts.mutationPending())){
            status="Обновление движка · завершите установку перед подключением";stopSelf(startId);return START_NOT_STICKY
        }
        if (incoming?.action == AUTO_OFF) {
            autoDisabled = true
            healthTask?.let(main::removeCallbacks); healthTask = null
            worker.execute { freezeSelection() }
            return START_STICKY
        }
        val requested = ++epoch
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
        sessionPrimaryChoice=null;fallbackAttempted=false
        val testInput=if(BuildConfig.DEBUG)intent.getStringExtra("test_fixture") else null
        fixtureMode=testInput!=null
        status="Подключение…"
        foreground("Подключение…")
        val vpn = if(systemAlwaysOn)true else intent.getBooleanExtra("tun", true)
        val preflightPort=intent.getIntExtra("preparedProxyPort",0).takeIf {it in 1024..65535}
        val port = if(fixtureMode)intent.getIntExtra("port",10808)else preflightPort?:try{ClientTunStore(this).load().choosePort()}catch(_:Exception){intent.getIntExtra("port",10808)}
        val applyRoutes = intent.getBooleanExtra("applyRoutes", false)
        if(!fixtureMode) preferences.edit().putBoolean("wantedRunning", true).apply()
        worker.execute {
            try {
                if (requested != epoch) return@execute
                closeCore()
                if(requested!=epoch||destroyed||currentService!==this)return@execute
                fixtureP1OwnerHelperSha=null
                vpnMode = vpn; nativeVpnExclusion = false
                if(!fixtureMode)behavior=ClientBehaviorStore.load(this)
                if (vpn && VpnService.prepare(this) != null) throw IOException("Сначала разреши VPN в приложении.")
                status = "Подключение…"
                val source=if(testInput==null){carrierTimeout=60000;healthUrl="https://www.gstatic.com/generate_204";JSONObject(ProfileStore(this).read())}
                    else readFixture(testInput)
                val bs=source.optJSONObject("mcrf_bs")
                if(bs==null) startVpnWithBsPreflight(source,vpn,port,applyRoutes,requested)
                else {
                    val candidates=BsPlan.candidates(bs)
                    TrafficState.reset("Выбираем движок БС…")
                    val chosen=chooseEngine(source,candidates,vpn,port,applyRoutes,requested)
                    scheduleHealth(source,candidates,chosen,bs.getInt("interval"),vpn,port,applyRoutes,requested)
                }
            } catch (error: Exception) {
                if (requested != epoch) { closeCore(); return@execute }
                closeCore()
                if(requested!=epoch||destroyed||currentService!==this)return@execute
                val primaryFailure=ClientPrimaryPreparationFailure.code(error)
                val explanation=primaryFailure?.let(ClientPrimaryPreparationFailure::message)
                    ?:ClientEvents.redact(error.message)
                status = "Ошибка подключения: $explanation"; ClientEvents.record(this, "Ошибка", status)
                if(!fixtureMode)preferences.edit().putBoolean("wantedRunning", false).apply(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
            }
        }
        return START_STICKY
    }
    // P046_BEGIN: startup BS preflight; extracted alone into the P0-only snapshot.
    private fun prepareAutoBsTemplate():JSONObject {
        val catalogue=Connections(this).apply {load()}
        val marked=catalogue.bsStartupEntries()
        val pool=(if(marked.isNotEmpty())marked else catalogue.entries().filter {AutoSelection.eligible(it,AutoSelection.BS_ENGINES)}
            .sortedBy {AutoSelection.ENGINE_PRIORITY.indexOf(it.protocol)}).filter {entry->
            runCatching {NativeCarrier.executable(this,EngineModules.carrierKind(requireNotNull(entry.carrier)));true}.getOrDefault(false)
        }
        require(pool.isNotEmpty()) {"Автоопределение БС: нет серверов с установленным совместимым движком. Проверь серверы БС и движки."}
        val template=BsPlan.build(pool,preferences.getInt("interval",60),preferences.getInt("tolerance",50),"Cloudflare")
        val routes=RoutingStore(this).apply {load()}
        DnsConfig.applyGenerated(template,routes.snapshot().getJSONObject("globalDns"))
        return template
    }
    private fun startVpnWithBsPreflight(source:JSONObject,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long) {
        fun cancelled()=requested!=epoch||destroyed||currentService!==this
        val decision=BsStartupPolicy.start(
            enabled={preferences.getBoolean(BsDetectionMonitor.KEY,false)},fixture=fixtureMode,
            alreadyBs=source.has("mcrf_bs")||source.has("mcrf_carrier"),cancelled={cancelled()},
            probe={
                require(ClientPrimarySelection.selected(this)!="steer") {"Автоопределение БС: выбранное ядро пока не поддерживает транспорты БС."}
                status="Проверяем доступность сайтов…";foreground("Проверка сети перед подключением VPN")
                DirectAccessCheck.check(this,cancelled={cancelled()})
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
                ClientEvents.record(this,"Клиент","Автоопределение БС: ограничения обнаружены до запуска VPN; сразу подключены белые списки. Минутные проверки и возврат к сохранённому VPN остаются включены.")
                if(autoSectionNotice.accept(true,autoBsSwitchSerial,requested,epoch,!cancelled()))AutoSectionNotifications.post(this,true)
            })
        check(decision!=BsStartupPolicy.Decision.CANCELLED) {"Подключение отменено или сеть изменилась во время проверки"}
    }
    // P046_END
    private fun startSource(template:JSONObject,candidate:JSONObject?,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long,keepTraffic:Boolean,frozenOutbound:String?=null) {
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
    private fun startSourceWithCore(template:JSONObject,candidate:JSONObject?,vpn:Boolean,port:Int,applyRoutes:Boolean,requested:Long,keepTraffic:Boolean,frozenOutbound:String?,primaryChoice:String) {
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
        if(!fixtureMode&&primaryChoice=="steer") {
            require(candidate==null&&!template.has("mcrf_carrier")&&!template.has("mcrf_bs")) {"Steer пока не поддерживает дополнительные транспорты и автовыбор БС."}
            if(WgAddonPolicy.usesWireGuard(template)) {
                startCompactWireGuard(template,vpn,port,applyRoutes,requested)
                return
            }
            startSteer(template,vpn,port,applyRoutes,requested,keepTraffic)
            return
        }
        engineUsage=EngineUpdateGate.acquireUsage(this)?:throw IOException("Движок обновляется. Завершите установку перед подключением.")
        val source=JSONObject(template.toString())
        val profile=candidate?.getJSONObject("profile") ?: source.optJSONObject("mcrf_carrier")
        val bridgeTag=source.optJSONObject("mcrf_carrier")?.optString("outbound_tag").orEmpty()
        source.remove("mcrf_carrier");source.remove("mcrf_bs")
        if(frozenOutbound!=null)source.optJSONArray("outbounds")?.let {list->repeat(list.length()){i->val node=list.getJSONObject(i);if(node.optString("type") in setOf("urltest","selector")&&node.optJSONArray("outbounds")?.let {members->(0 until members.length()).any{members.getString(it)==frozenOutbound}}==true){node.put("type","selector").put("default",frozenOutbound);listOf("url","interval","tolerance").forEach(node::remove)}}}
        profile?.let {
            prepareCarrierHandoverTun(vpn,requested)
            val transport=NativeCarrier.start(this,it,java.util.function.BooleanSupplier { requested!=epoch },carrierTimeout);carrier=transport
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
        if(applyRoutes){val routes=RoutingStore(this);routes.load();val settings=routes.snapshot();val directIpv4=DirectDnsNetwork.ipv4Only(this,underlying);prepared=RouteRules.apply(prepared,settings,vpn,PresetAssets.resolve(this,settings),PresetAssets.ipRuleSetTags(this,settings),directIpv4);if(DnsConfig.appOwned(source)){runningDirectDnsIpv4=directIpv4;if(directIpv4)ClientEvents.record(this,"DNS","У физической сети нет IPv6-маршрута в интернет. Для однозначных прямых TCP-маршрутов включён быстрый отказ недоступного IPv6, позволяющий приложению попробовать IPv4. AAAA, IPv6 через VPN и защита TUN не отключены.")}}
        val coreSettings=if(fixtureMode)CoreTuningSettings()else CoreTuningStore(this).load()
        if(!fixtureMode){
            val plan=OptionalCorePlan(prepared,OptionalCoreStore(this).load(),coreSettings)
            plan.notes.forEach {ClientEvents.record(this,"Движки",it)}
            if(plan.requiresWorkers){
                prepareCarrierHandoverTun(vpn,requested)
                // openTun below excludes our UID for every retained native worker.
                val native=OptionalCoreSession.start(this,plan,java.util.function.BooleanSupplier{requested!=epoch},vpn,true)
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
        val familyPrepared=CarrierAddressFamily.apply(prepared,CarrierAddressFamily.forPool(poolProfiles,profile?.let(EngineModules::carrierKind)))
        if(familyPrepared!==prepared)ClientEvents.record(this,"DNS",if(poolProfiles.isEmpty())CarrierAddressFamily.NOTE else CarrierAddressFamily.POOL_NOTE)
        prepared=familyPrepared
        names.clear(); ids.clear()
        try{val catalogue=Connections(this);catalogue.load();catalogue.entries().forEach { names[ServerImport.tag(it)]=it.name; ids[ServerImport.tag(it)]=it.id }}catch(_:Exception){}
        selectedId = candidate?.getString("id") ?: ids[target].orEmpty()
        if (profile == null && selectedId.isNotEmpty() && preferences.getBoolean(ConnectionAddressFamily.key(selectedId), false)) {
            prepared = ConnectionAddressFamily.apply(prepared, true)
            ClientEvents.record(this, "DNS", ConnectionAddressFamily.NOTE)
        }
        val label=candidate?.getString("name") ?: names[target] ?: if(target=="proxy") "Определяем сервер…" else "JSON-профиль"
        if(keepTraffic) TrafficState.handover(label) else TrafficState.reset(label)
        ClientEvents.record(this,"Маршруты",ConnectionDiagnostics.summary(prepared))
        runningConfig = prepared; selectedOutbound = ""
        val choice=primaryChoice
        require(choice=="singbox") {"Для Steer сначала нужна совместимая конфигурация: текущие параметры не будут отброшены."}
        val runGeneration=generation.coerceAtLeast(1)
        val nativeUidBypass=carrier!=null||optionalSession!=null
        nativeVpnExclusion=nativeUidBypass
        if(vpn&&!nativeUidBypass)ProbeCoordinator.awaitQuiescent(this)
        val platform=forRun(runGeneration,{requested!=epoch||runGeneration!=generation},{plan->
            require(requested==epoch&&runGeneration==generation) {"Подключение отменено"}
            openPrimaryTun(plan,nativeUidBypass)
        }) {tx,rx->
            if(requested==epoch&&runGeneration==generation)TrafficState.update(tx,rx,SystemClock.elapsedRealtime())
        }
        val server=connectPrimary(choice,runGeneration,platform,onDeath={
            if(runGeneration==generation&&requested==epoch){
                closePlatform()
                main.post {if(runGeneration==generation&&requested==epoch){status="Ошибка подключения: движок завершился";startService(Intent(this,McrfVpnService::class.java).setAction(STOP))}}
            }
        })
        val mode=if(vpn)Engine.Mode.VPN else Engine.Mode.PROXY
        val settings=ClientPrimarySelection.envelope(prepared,vpn,behavior.metered)
        val validation=server.validate("",settings,mode)
        require(validation.supported) {"Движок не поддерживает профиль (${validation.code})."}
        server.start("",settings,mode)
        if(candidate!=null){val delay=health();record(candidate.getString("id"),delay);if(delay<0) throw IOException("Движок не прошёл проверку передачи данных")}
        else if(optionalSession!=null&&(optionalSession?.isAlive()!=true||health()<0))throw IOException("Дополнительное ядро не подтвердило передачу данных")
        carrier?.checkFailure()
        if(requested!=epoch) throw IOException("Подключение отменено")
        status=if(vpn) "VPN подключён"+(if(localProxyPort>0)" · прокси 127.0.0.1:$localProxyPort"else"") else if(localProxyPort>0) "Прокси 127.0.0.1:$localProxyPort" else "Прокси подключён · пользовательские входы"
        ClientBehaviorUi.applyRecents(this)
        try{startTelemetry()}catch(_:Exception){ClientEvents.record(this,"Предупреждение","Счётчики ядра пока недоступны")}
        ClientEvents.record(this,"Клиент",if(candidate!=null) "Автовыбор БС: ${candidate.getString("name")}" else if(vpn) "Системный VPN подключён" else "Локальный прокси запущен")
        foreground(status)
        currentStart=RuntimeStart(JSONObject(template.toString()),candidate?.let{JSONObject(it.toString())},vpn,port,applyRoutes,requested)
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
        val snapshot=ClientPrimarySelection.steerSnapshot(this,routes.snapshot(),true)
        require(snapshot.supported) {ClientPrimarySelection.steerFailure(snapshot)}
        val connections=Connections(this);connections.load()
        val entry=connections.entries().singleOrNull {it.id==preferences.getString("selected","")}
            ?:throw IOException("Выбери один сервер для Steer.")
        require(entry.carrier==null&&entry.configuration==null&&!preferences.getBoolean("automatic",false)) {"Steer: нужен один сервер по ссылке, без дополнительного транспорта и автовыбора."}
        startSteerPrepared(template,SteerPrepared(entry.original,entry.host,entry.name,entry.id,requireNotNull(snapshot.settingsJson)),port,applyRoutes,requested,keepTraffic)
    }
    /** Immutable capture shared by ordinary selection and the strictly local DEBUG fixture.
     * No user choice/settings are written by the native run itself. */
    private class SteerPrepared(val original:String,val host:String,val name:String,val id:String,val settings:String,val fixtureDial:String?=null)
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
        val physical=underlying?:throw IOException("Нет физической сети для WG/AWG.")
        val dial=RouteRules.literal(entry.host)?.let {literal->
            require(literal is java.net.Inet4Address) {"Внешний IPv6 сервера компактного WG ещё не прошёл приёмку."};literal.hostAddress
        }?:physical.getAllByName(entry.host).filterIsInstance<java.net.Inet4Address>().firstOrNull()?.hostAddress
            ?:throw IOException("Не найден IPv4 сервера WG/AWG в физической сети.")
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
            if(runGeneration==generation&&requested==epoch){closePlatform();main.post {if(runGeneration==generation&&requested==epoch)startService(Intent(this,McrfVpnService::class.java).setAction(STOP))}}
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
        val descriptor=builder.establish()?:throw IOException("PACKET_TUN_NOT_GRANTED")
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
        val platform=forRun(runGeneration,{requested!=epoch||runGeneration!=generation},{throw IOException("Steer использует заранее созданный TUN")}) {tx,rx->
            if(requested==epoch&&runGeneration==generation)TrafficState.update(tx,rx,SystemClock.elapsedRealtime())
        }
        val server=connectPrimary("steer",runGeneration,platform,onDeath={
            if(runGeneration==generation&&requested==epoch){closePlatform();main.post {if(runGeneration==generation&&requested==epoch)startService(Intent(this,McrfVpnService::class.java).setAction(STOP))}}
        })
        val settings=entry.settings
        require(server.validate(entry.original,settings,Engine.Mode.VPN).supported) {"Steer не поддерживает параметры выбранного сервера. Профиль не изменён."}
        val plan=JSONObject(server.tunPlan(entry.original,settings,Engine.Mode.VPN))
        val admittedPlan=SteerTunPlanPolicy.parse(plan,JSONObject(settings),packageName)
        val physical=underlying?:throw IOException("Нет физической сети для Steer.")
        val dial=entry.fixtureDial?.let {literal->
            require(BuildConfig.DEBUG&&fixtureMode&&literal==entry.host)
            // The strict fixture parser already pinned a literal RFC1918 address.
            // Materialize it without a resolver, never a physical/default fallback.
            java.net.InetAddress.getByAddress(literal.split('.').map {it.toInt().toByte()}.toByteArray()).hostAddress
        }?:physical.getAllByName(entry.host).filterIsInstance<java.net.Inet4Address>().firstOrNull()?.hostAddress
            ?:throw IOException("Сервер Steer не имеет IPv4-адреса.")
        val bootstrap=JSONObject(settings).getJSONObject("globalDns").let {dns->
            if(dns.optString("provider")=="Свой"&&dns.optString("protocol") in setOf("DoH","DoT")) {
                val address=dns.getString("custom");val host=java.net.URI(if("://" in address)address else "tls://$address").host?:throw IOException("Неверный DNS Steer.")
                physical.getAllByName(host).filterIsInstance<java.net.Inet4Address>().mapNotNull {it.hostAddress}.distinct().take(8).toTypedArray()
            }else emptyArray()
        }
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
        val descriptor=builder.establish()?:throw IOException("Android не разрешил открыть VPN.")
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
        if(keepTraffic)TrafficState.handover(entry.name)else TrafficState.reset(entry.name)
        server.start(entry.original,settings,Engine.Mode.VPN,descriptor,dial,bootstrap,selectedNetwork.networkHandle)
        val verified=if(entry.fixtureDial!=null) verifySteerFixtureHealth(selectedNetwork,requested) else {
            val test=selectedNetwork.openConnection(java.net.URL(healthUrl),java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            try {test.connectTimeout=10000;test.readTimeout=10000;test.instanceFollowRedirects=false;test.responseCode==204}finally{test.disconnect()}
        }
        require(verified&&requested==epoch) {"Steer запущен, но передача данных не подтверждена."}
        selectedId=entry.id;target=entry.id;runningConfig=null
        localProxyPort=server.steerProxyPort
        status="VPN подключён · Steer"+(if(localProxyPort>0)" · прокси 127.0.0.1:$localProxyPort"else"");ClientBehaviorUi.applyRecents(this)
        currentStart=RuntimeStart(JSONObject(template.toString()),null,true,port,applyRoutes,requested)
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
        for(candidate in candidates){
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
        board.retireDrained()
        val currentKind=selected.getJSONObject("profile").getString("type")
        val currentTier=AutoSelection.ENGINE_PRIORITY.indexOf(currentKind)
        val currentMarked=candidates.any {it.getString("id")==selected.getString("id")}
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
                if(!live()||!AutoSelection.prefer(candidate.getJSONObject("profile").getString("type"),delay,currentKind,if(currentMarked)currentDelay else -1L,preferences.getInt("tolerance",50)))continue
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
    private fun foreground(message: String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("vpn", "Подключение MCRF", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, McrfVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val state = TrafficState.current; val server = state.server.ifEmpty { "Подключение" }
        val notification = AndroidNotification.Builder(this, "vpn").setContentTitle("MCRF · $server")
            .setContentText(TrafficState.notificationTraffic(state))
            .setStyle(AndroidNotification.BigTextStyle().bigText("$message\n$server\n${TrafficState.notificationTraffic(state)}"))
            .setSmallIcon(R.drawable.status_icon).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(AndroidNotification.Action.Builder(null, "Остановить", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, notification)
    }
    private fun connectPrimary(choice:String,runGeneration:Long,platform:Platform,onDeath:()->Unit):ClientPrimaryWorker = try {
        val connected=ClientPrimaryWorker.connect(this,choice,runGeneration,platform,onDeath=onDeath)
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
        currentStart=null
        healthTask?.let(main::removeCallbacks);healthTask=null
        generation++; TrafficState.stop()
        val client = telemetry; telemetry = null; if(client!=null)main.removeCallbacks(client)
        runningConfig=null;runningDirectDnsIpv4=null
        val batch=retirement?:run {
            val previous=core
            val transport=carrier
            val board=carrierSwitchboard
            val extra=optionalSession
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
                dependents=listOf({closePlatform(keepTun)},{board?.close();transport?.close();Unit},{extra?.close();Unit},{usage?.close();Unit}),
                completed={
                    if(core===previous)core=null
                    if(carrier===transport)carrier=null
                    if(carrierSwitchboard===board)carrierSwitchboard=null
                    if(optionalSession===extra)optionalSession=null
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
        val session = generation
        val requested = epoch
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
                val stats=current.stats()
                val tx=stats.txPayload.value;val rx=stats.rxPayload.value
                if(session==generation&&tx!=null&&rx!=null)TrafficState.update(tx,rx,SystemClock.elapsedRealtime())
                val outbound=if(current.choiceId=="singbox")current.selectedOutbound(target)else""
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
        val next = builder.establish() ?: throw IOException("Android не разрешил открыть VPN.")
        return next // HostRun alone publishes/closes this descriptor after its final generation check.
    }
    override fun onRevoke() { ServerSwitchTransition.observeCommand(null);val requested=++epoch;clearNetworkRecovery();core?.requestStop(); if(!fixtureMode)preferences.edit().putBoolean("wantedRunning", false).apply(); if(!destroyed)worker.execute { closeCore(); if(!destroyed&&currentService===this&&requested==epoch){status = "VPN-разрешение отозвано"; ClientEvents.record(this, "Клиент", status); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()} } }
    override fun onDestroy() {
        bsDetection?.close();bsDetection=null
        // Register synchronously before Android can create the replacement
        // Service: even a queued cleanup task must block its new native START.
        retiringServices.add(this)
        destroyed=true;epoch++;clearNetworkRecovery();core?.requestStop()
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
        const val START = "org.mcrf.client.START"
        const val STOP = "org.mcrf.client.STOP"
        const val AUTO_OFF = "org.mcrf.client.AUTO_OFF"
        @JvmField @Volatile var status = "Отключено"
        @JvmField @Volatile var selectedId = ""
        @JvmField @Volatile var autoBsSwitchSerial=0L
        @JvmField @Volatile var autoVpnSwitchSerial=0L
        @JvmField @Volatile var nativeVpnExclusion = false
        @JvmField @Volatile var localProxyPort=0
    }
}

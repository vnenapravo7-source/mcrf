package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import android.system.Os
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.UUID
import java.util.function.BooleanSupplier

class NativeCarrier private constructor(@JvmField val port: Int) : AutoCloseable {
    private val backgroundAdmission=java.util.concurrent.atomic.AtomicBoolean()
    private var promotable=false
    /** Only a service-owned hot candidate, never an ordinary measurement, may become interactive. */
    fun promoteForActive()=synchronized(inputLock){
        check(promotable&&!closed.get()&&!captchaSeen.get())
        promotable=false;backgroundAdmission.set(false)
    }
    @JvmField val username = UUID.randomUUID().toString()
    @JvmField val password = UUID.randomUUID().toString()
    private var process: Process? = null
    private var ipc: CarrierIpcWriter? = null
    private var processGroup:CarrierProcessGroup?=null
    private var originalControl:CarrierOriginalPacketControl?=null
    private var originalEndpoint:CarrierOriginalPacketControl.Endpoint?=null
    private var originalReceive:java.util.concurrent.FutureTask<CarrierOriginalPacketControl.Endpoint>?=null
    private var originalReceiveThread:Thread?=null
    private val originalAdopted=java.util.concurrent.atomic.AtomicBoolean()
    internal val originalPackets:Boolean get()=originalControl!=null
    /** Complete the staged handoff only once the final immutable config and
     * exact primary worker generation are known. Must precede core START. */
    internal fun attachOriginalPackets(worker:ClientPrimaryWorker,settings:String,tag:String,cancelled:BooleanSupplier) {
        val endpoint=originalEndpoint?:return
        endpoint.transferOwned {fd,metadata->
            check(!closed.get()&&!cancelled.asBoolean){"ORIGINAL_CARRIER_STOPPED"}
            worker.attachOriginalCarrier(settings,OriginalCarrierPacketSpec(tag,metadata.nonce,metadata.local,metadata.mtu),fd,
                metadata.peerPid,metadata.generation)
            originalAdopted.set(true)
        }
        originalEndpoint=null
        val deadline=android.os.SystemClock.elapsedRealtime()+25_000L
        while(!connected.get()) {
            checkFailure()
            check(!closed.get()&&!cancelled.asBoolean&&process?.isAlive==true){"ORIGINAL_CARRIER_STOPPED"}
            check(android.os.SystemClock.elapsedRealtime()<deadline){"ORIGINAL_CARRIER_READY_TIMEOUT"}
            Thread.sleep(40)
        }
    }
    private val ownershipFailure=java.util.concurrent.atomic.AtomicBoolean(false)
    private val openFluxProtocolRejected=java.util.concurrent.atomic.AtomicBoolean(false)
    private val openFluxVerificationSeen=java.util.concurrent.atomic.AtomicBoolean(false)
    private val openFluxChild=UUID.randomUUID().toString().replace("-", "")
    private var openFluxContext:Context?=null
    private var openFluxUiId:String?=null
    private var openFluxTicketObserved:String?=null // Survives ACK/cancel until this exact child retires.
    private var directory: File? = null
    private var runtimeParent: File? = null
    private val connected = java.util.concurrent.atomic.AtomicBoolean(false)
    private val streamState=TurnStreamState()
    internal fun actualStreams():Int=streamState.current()
    private var startedStreamProfile:JSONObject?=null
    /** Exact resolved input/count for this child, before any later preference/cache change. */
    internal fun streamProfile():JSONObject?=startedStreamProfile?.let {JSONObject(it.toString())}
    private var kind = ""
    private val challenges=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val challengeFlights=CarrierChallengeFlights()
    private val captchaSeen=java.util.concurrent.atomic.AtomicBoolean(false)
    private val startupFailure=java.util.concurrent.atomic.AtomicReference<CsqttNativeFailure.Kind?>()
    private val vkCredentialLimit=java.util.concurrent.atomic.AtomicReference<VkCredentialLimit?>()
    private val inputLock=Any()
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    private var closeComplete=false
    private var failureDetail:CarrierFailureDetail?=null
    private var retirement:ClientCarrierRetirement?=null
    private var lifecycleDiagnostic:((String)->Unit)?=null
    private fun lifecycle(message:String){try{lifecycleDiagnostic?.invoke(message)}catch(_:Exception){/* Logging must not change child retirement. */}}
    private var providerFinished=false
    private var providerReader:ClientOpenFluxProviderStage?=null
    @Volatile private var openFluxObservation:OpenFluxObservation?=null
    private var providerCallback:((Map<String,Boolean>?)->Unit)?=null
    private var outputThread:Thread?=null
    private val outputDrained=java.util.concurrent.atomic.AtomicBoolean(false)
    private val deadOutputSince=java.util.concurrent.atomic.AtomicLong()
    private fun failureMessage(stage:String):String = failureDetail?.describe(stage,
        process?.let {runCatching {it.exitValue()}.getOrNull()}) ?: "$kind · $stage · код и причина недоступны"
    fun checkFailure() {
        vkCredentialLimit.get()?.let {throw VkCredentialLimit.Blocked(it)}
        require(!openFluxProtocolRejected.get()) {"Запрос проверки OpenFlux отклонён: неверный формат или неподдерживаемая страница. Подключение остановлено."}
        require(!ownershipFailure.get()) {"Не удалось подтвердить владельца дерева процессов. ${failureMessage("подтверждение владельца")}"}
        startupFailure.get()?.let {throw CsqttNativeFailure.Rejected(it,failureMessage("отказ клиента"))}
    }
    /** Nonblocking; the Service calls this only for its exact, already-started owner. */
    fun runtimeFailure():String? {
        if(closed.get())return null
        if(openFluxProtocolRejected.get())return "Запрос проверки OpenFlux отклонён; текущее подключение остановлено."
        if(process?.isAlive==false&&outputThread?.isAlive==true&&!outputDrained.get()) {
            val now=android.os.SystemClock.elapsedRealtime()
            deadOutputSince.compareAndSet(0L,now)
            if(now-deadOutputSince.get()<500L)return null
        }
        if(ownershipFailure.get())return "Нарушено владение деревом процессов. ${failureMessage("работающее соединение")}" 
        val failure=CarrierRuntimeHealth.failure(kind,false,process?.isAlive==true,startupFailure.get())?:return null
        // Nonblocking on the service thread: reader retains final stderr as it arrives.
        return "$failure ${failureMessage("работающее соединение")}" 
    }
    @Synchronized override fun close() {
        if(closeComplete)return
        val observationContext=openFluxContext
        synchronized(inputLock){
            closed.set(true)
            originalControl?.close()
            streamState.stop()
            if(kind=="openflux")ipc?.requestStopDiscardingPending() else ipc?.requestStop()
        }
        val verificationIds=(OpenFluxVerificationRequests.registry.closeChild(openFluxChild)+listOfNotNull(openFluxUiId)).distinct()
        openFluxUiId=null
        openFluxContext?.let {app->verificationIds.forEach {id->OpenFluxVerificationUi.cancel(app,id)}}
        openFluxContext=null
        val child = process
        challenges.forEach(CarrierChallenge::remove);challenges.clear()
        // Blocking on the retiring worker also keeps start's local owner alive
        // on failure. Never return a STOP acknowledgement for a living child.
        val graceSeconds=if(kind=="csqtt")22L else 3L
        val stopStarted=android.os.SystemClock.elapsedRealtime()
        if(child!=null)try{lifecycle(CarrierStopDiagnostics.requested(child.isAlive,graceSeconds))}catch(_:Exception){/* Diagnostics cannot prevent retirement. */}
        val retiring=child?.let {retirement?:ClientCarrierRetirement(it,graceSeconds,ipc=requireNotNull(ipc)).also {retirement=it}}
        retiring?.close()
        processGroup?.awaitDeath()
        originalReceiveThread?.join(1000)
        check(originalReceiveThread?.isAlive!=true){"ORIGINAL_CARRIER_RECEIVER_RETIREMENT"}
        if(retiring?.complete==true)lifecycle(CarrierStopDiagnostics.completed(
            android.os.SystemClock.elapsedRealtime()-stopStarted,retiring.forced,try{child?.exitValue()}catch(_:Exception){null}))
        if(openFluxObservation!=null) {
            try {outputThread?.join(1000)}catch(_:InterruptedException){Thread.currentThread().interrupt()}
            openFluxObservation?.finish(retiring?.normalExit==true,outputDrained.get()&&outputThread?.isAlive==false)?.let {summary->
                observationContext?.let {app->ClientEvents.record(app,"Движки",summary)}
            }
            openFluxObservation=null
        }
        if(!providerFinished)providerReader?.let {reader->
            try {outputThread?.join(1000)}catch(_:InterruptedException){Thread.currentThread().interrupt()}
            // A reviewed wrapper can complete cleanly with exit 1 after a startup error.
            // Killed/live children, unread output and missing records are never all-false.
            val graceful=retiring?.normalExit==true
            val stage=reader.finish(graceful,outputDrained.get()&&outputThread?.isAlive==false)
            providerFinished=true
            try {providerCallback?.invoke(stage?.events)}catch(_:Exception){}
        }
        val folder = directory
        if (folder != null) try { deleteRuntimeDirectory(requireNotNull(runtimeParent), folder) } catch (_: IOException) { }
        directory=null
        runtimeParent=null
        process=null
        ipc=null
        processGroup=null
        closeComplete=true
        lifecycleDiagnostic=null
    }
    companion object {
        @JvmStatic @Throws(Exception::class) fun executable(context: Context, kind: String): File {
            require(kind in setOf("olcrtc", "dnstt", VayDnsProfile.KIND, SlipstreamProfile.KIND, SstpProfile.KIND, MasterDnsProfile.KIND, "openflux", "warpscout","csqtt25",S3BypassProfile.KIND) + SwgProfile.TYPES + CsqttGenerations.legacyEngines) { "Этот движок ещё переносится." }
            val plugin=installation(context,kind)
            if(plugin.packageName==BsEngineContract.PACKAGE){
                require(kind in BsEngineContract.engines){"WDTT устанавливается отдельным движком."}
                return BsEnginePackage.executable(context,kind)
            }
            val base = File(plugin.nativeLibraryDir).canonicalFile
            if(kind in CsqttGenerations.legacyEngines) require(File(base,"libcsqtt_transport.so").canExecute()) { "В пакете нет клиента CSQTT нужного поколения для этой архитектуры." }
            if(kind=="csqtt25")require(File(base,"libcsqtt25_transport.so").canExecute()) {"В отдельном пакете нет транспорта CSQTT 2.5 для этой архитектуры."}
            if(kind==SlipstreamProfile.KIND)require(File(base,"libslipstream_core.so").canExecute()) {"В отдельном модуле нет клиента Slipstream для этой архитектуры."}
            return File(base, "lib${if(kind=="csqtt") "qwdtt" else kind}.so").canonicalFile.also {
                require(it.parentFile == base && it.isFile && it.canExecute()) { "Движок не установлен для этой архитектуры Android." }
            }
        }
        fun installation(context:Context,kind:String):android.content.pm.ApplicationInfo {
            BsEnginePackage.preferred(context,kind)?.let{return it}
            val dedicated=EngineModules.packageName(kind)
            val pm = context.packageManager
            val plugin = try { pm.getApplicationInfo(dedicated, PackageManager.GET_META_DATA) }
                catch (_: PackageManager.NameNotFoundException) {
                    require(kind!=VayDnsProfile.KIND) {"Нужен отдельный модуль VayDNS для архитектуры устройства. DNSTT не используется вместо него."}
                    require(kind!=SlipstreamProfile.KIND) {"Нужен отдельный модуль Slipstream. Другой DNS-туннель вместо него не используется."}
                    require(kind!=SstpProfile.KIND) {"Нужен отдельный модуль SSTP. Другой протокол вместо него не используется."}
                    require(kind!=MasterDnsProfile.KIND) {"Нужен отдельный модуль MasterDnsVPN. Другой DNS-туннель вместо него не используется."}
                    require(kind!="csqtt25") {"Нужен отдельный пакет CSQTT 2.5 для архитектуры устройства. Старый CSQTT сохранён и не будет использован вместо него."}
                    require(kind !in setOf("csqtt20","csqtt215")) {"Скачайте отдельный модуль ${CsqttGenerations.legacy.single {it.engine==kind}.label}. Другой движок не будет использован вместо него."}
                    try{pm.getApplicationInfo(EngineModules.LEGACY,PackageManager.GET_META_DATA)}catch(_:PackageManager.NameNotFoundException){throw IllegalArgumentException("Установи отдельный пакет ${kind.uppercase()} для архитектуры телефона. Основное приложение обновлять не нужно.")}}
            require(plugin.enabled && plugin.metaData?.getInt("org.mcrf.engines.interface") == 1 &&
                (plugin.packageName==EngineModules.LEGACY||plugin.metaData?.getString("org.mcrf.engines.kind")==kind)&&
                pm.checkSignatures(context.packageName, plugin.packageName) == PackageManager.SIGNATURE_MATCH) {
                "Подпись или версия пакета движков не совпадает с MCRF."
            }
            if (kind == "openflux") require((plugin.metaData?.getInt("org.mcrf.engines.openflux") ?: 0) >= 2 &&
                plugin.packageName==dedicated && plugin.metaData?.getString("org.mcrf.engines.source.repository")==EngineUpdatePolicy.UPSTREAM &&
                OpenFluxProfile.supportsFreshProfile(plugin.metaData?.getString("org.mcrf.engines.source.version"),plugin.metaData?.getString("org.mcrf.engines.source.commit")) &&
                plugin.metaData?.getInt("org.mcrf.engines.openflux.scriptEngine")==1 && plugin.metaData?.getInt("org.mcrf.engines.openflux.scriptHostApi")==1 &&
                plugin.metaData?.getString("org.mcrf.engines.openflux.scriptSignature")=="ed25519-official") { "Обновите отдельный модуль до официального OpenFlux ${OpenFluxProfile.FRESH_VERSION} с поддержкой подписанных JS-транспортов." }
            if (kind in SwgProfile.TYPES+CsqttGenerations.legacyEngines) require((plugin.metaData?.getInt("org.mcrf.engines.swg") ?: 0) >= 1) { "Обнови пакет дополнительных движков для SWG." }
            if(kind in setOf("csqtt20","csqtt215")){
                val family=CsqttGenerations.legacy.single {it.engine==kind}
                require(plugin.packageName==dedicated&&plugin.metaData?.getString("org.mcrf.engines.csqtt_generation")==family.id&&plugin.metaData?.getString("org.mcrf.engines.source.commit")==family.revision){"Поколение и исходник модуля CSQTT не совпадают. Автоматическая подмена запрещена."}
            }
            if(kind=="csqtt25")require(plugin.packageName==dedicated&&plugin.metaData?.getString("org.mcrf.engines.csqtt_generation")=="2.5") {"Нужен отдельный совместимый пакет CSQTT 2.5; подмена старым пакетом запрещена."}
            if(kind==VayDnsProfile.KIND)require(plugin.packageName==dedicated&&VayDnsProfile.compatible(plugin.metaData?.getString("org.mcrf.engines.kind"),plugin.metaData?.getString("org.mcrf.engines.source.repository"),plugin.metaData?.getString("org.mcrf.engines.source.commit"),plugin.metaData?.getInt("org.mcrf.engines.vaydns")?:0)) {"Исходник или защищённый интерфейс модуля VayDNS не совпадает с MCRF."}
            if(kind==SlipstreamProfile.KIND)require(plugin.packageName==dedicated&&SlipstreamProfile.compatible(plugin.metaData?.getString("org.mcrf.engines.kind"),plugin.metaData?.getString("org.mcrf.engines.source.repository"),plugin.metaData?.getString("org.mcrf.engines.source.commit"),plugin.metaData?.getInt("org.mcrf.engines.slipstream")?:0)) {"Исходник или защищённый интерфейс модуля Slipstream не совпадает с MCRF."}
            if(kind==SstpProfile.KIND)require(plugin.packageName==dedicated&&SstpProfile.compatible(plugin.metaData?.getString("org.mcrf.engines.kind"),plugin.metaData?.getString("org.mcrf.engines.source.repository"),plugin.metaData?.getString("org.mcrf.engines.source.commit"),plugin.metaData?.getInt("org.mcrf.engines.sstp")?:0)) {"Исходник или защищённый интерфейс модуля SSTP не совпадает с MCRF."}
            if(kind==MasterDnsProfile.KIND)require(plugin.packageName==dedicated&&MasterDnsProfile.compatible(plugin.metaData?.getString("org.mcrf.engines.kind"),plugin.metaData?.getString("org.mcrf.engines.source.repository"),plugin.metaData?.getString("org.mcrf.engines.source.commit"),plugin.metaData?.getInt("org.mcrf.engines.masterdnsvpn")?:0)) {"Исходник или защищённый интерфейс модуля MasterDnsVPN не совпадает с MCRF."}
            if(kind in CsqttGenerations.engines)require(plugin.metaData?.getInt("org.mcrf.engines.csqtt_process_group")==1) {"Обновите внешний модуль CSQTT: нужен контракт безопасной остановки всего дерева процессов."}
            if(kind==S3BypassProfile.KIND)require(plugin.packageName==dedicated&&plugin.metaData?.getInt("org.mcrf.engines.s3bypass")==1&&plugin.metaData?.getString("org.mcrf.engines.source.repository")==S3BypassProfile.REPOSITORY&&plugin.metaData?.getString("org.mcrf.engines.source.commit")==S3BypassProfile.COMMIT) {"S3 Bypass требует отдельного модуля fedarisha; обычный Xray не подходит."}
            if(kind=="warpscout") require((plugin.metaData?.getInt("org.mcrf.engines.warp") ?: 0)>=1){"Обнови пакет дополнительных движков для подбора WARP."}
            return plugin
        }
        @JvmStatic @JvmOverloads @Throws(Exception::class) fun start(context: Context, profile: JSONObject, cancelled: BooleanSupplier, timeoutMillis: Long = 60000,
            providerDiagnostic:Boolean=false,providerVersion:Int=1,onProviderStage:((Map<String,Boolean>?)->Unit)?=null): NativeCarrier =
            startInternal(context,profile,cancelled,timeoutMillis,providerDiagnostic,providerVersion,onProviderStage,false)
        internal fun startForServerProbe(context:Context,profile:JSONObject,cancelled:BooleanSupplier):NativeCarrier =
            startInternal(context,profile,cancelled,60000,false,1,null,false,serverProbe=true)
        internal fun confirmConnectionPing(context:Context,profile:JSONObject,cancelled:BooleanSupplier,onProbe:()->Unit={}) {
            val backoff=VkCredentialBackoffStore(context)
            try {
                backoff.requireConnectionAllowed(profile)
                return
            }catch(waiting:VkCredentialLimit.AwaitingServerPing) {
                val ticket=backoff.serverPingTicket()
                onProbe()
                val request=JSONObject(profile.toString()).put("probe_only",true)
                val bound=BsNativeContract.routerTransport(context,profile,true)
                val physical=if(bound)DirectAccessCheck.physical(context)?:throw waiting else null
                if(!bound&&McrfVpnService.ownsSystemTunnel()&&!McrfVpnService.nativeVpnExclusion)throw waiting
                val passed=try {
                    check(!cancelled.asBoolean)
                    val owner=startInternal(context,request,cancelled,15000,false,1,null,false,
                        serverProbe=true,routerNetwork=physical?.networkHandle)
                    try {
                        CarrierSocksSwitch.probe(owner,"https://www.gstatic.com/generate_204",5000)>=0 &&
                            !cancelled.asBoolean && (physical==null||DirectAccessCheck.physical(context)==physical)
                    }finally {OptionalCoreRetirement.drain(owner)}
                }catch(error:VkCredentialLimit.Blocked){throw error}
                catch(_:Exception){false}
                if(!passed||!backoff.confirmServerPing(ticket,profile))throw waiting
                backoff.requireConnectionAllowed(profile)
            }
        }
        internal fun startForBsRouter(context:Context,profile:JSONObject,physical:android.net.Network,cancelled:BooleanSupplier,
            timeoutMillis:Long=60000,onRetiredFailure:(()->Unit)?=null):NativeCarrier {
            require(BsNativeContract.routerTransport(context,profile,true)&&DirectAccessCheck.physical(context)==physical){
                "БС: модуль или физическая сеть изменились перед запуском транспорта."
            }
            val networkLost=java.util.concurrent.atomic.AtomicBoolean()
            val boundCancellation=BooleanSupplier {
                if(cancelled.asBoolean||networkLost.get())true
                else {
                    val current=try {DirectAccessCheck.physical(context)}catch(_:SecurityException){null}
                    if(current!=physical)networkLost.set(true)
                    networkLost.get()
                }
            }
            val owner=startInternal(context,profile,boundCancellation,timeoutMillis,false,1,null,false,onRetiredFailure,
                routerNetwork=physical.networkHandle)
            try {
                if(boundCancellation.asBoolean)throw IOException("Подключение отменено или физическая сеть изменилась.")
                return owner
            }catch(error:Exception){
                OptionalCoreRetirement.drain(owner)
                throw error
            }
        }
        internal fun startRouted(context:Context,profile:JSONObject,cancelled:BooleanSupplier,generation:Long,timeoutMillis:Long=60000):NativeCarrier {
            val original=generation.takeIf {it>0&&OriginalCarrierMode.available(context,profile)}?:0L
            return startInternal(context,profile,cancelled,timeoutMillis,false,1,null,false,originalGeneration=original)
        }
        /** Only an explicit isolated receipt can select the fresh nonproduction package. */
        internal fun startFreshOpenFluxCandidate(context:Context,profile:JSONObject,
            expected:OpenFluxFreshCandidatePolicy.Expected,cancelled:BooleanSupplier,timeoutMillis:Long=60000):NativeCarrier {
            require(profile.optString("type")=="openflux"&&!profile.optBoolean("probe_only"))
            val verified=ClientOpenFluxFreshGate.verify(context,expected)
            return startInternal(context,profile,cancelled,timeoutMillis,false,1,null,false,freshCandidate=verified)
        }
        fun startBackground(context:Context,profile:JSONObject,cancelled:BooleanSupplier,timeoutMillis:Long):NativeCarrier =
            startInternal(context,profile,cancelled,timeoutMillis,false,1,null,true)
        /** A failed pool member is skippable ONLY after start's own exact child
         * and IPC owner have completed retirement. Cleanup failures/cancellation
         * are not an unavailable-server result and must abort the parent start. */
        internal fun startPoolMember(context:Context,profile:JSONObject,cancelled:BooleanSupplier,
                                     physical:android.net.Network?=null):NativeCarrier? {
            var retired=false
            val timeout=if(profile.optString("type")=="openflux")OpenFluxProfile.poolStartupMillis(profile)else 15_000L
            return try {
                if(physical==null)startInternal(context,profile,cancelled,timeout,false,1,null,false,{retired=true})
                else startForBsRouter(context,profile,physical,cancelled,timeout){retired=true}
            }
            catch(error:Exception) {
                if(!retired||cancelled.asBoolean||Thread.currentThread().isInterrupted||physical!=null&&DirectAccessCheck.physical(context)!=physical)throw error
                null
            }
        }
        internal fun requireOptions(context:Context,profile:JSONObject) {
            if(Csqtt25Profile.isProfile(profile)){Csqtt25Profile.srtpMode(profile);CsqttGooby.validate(profile)}
            if(CsqttGenerations.automatic(profile))return // The chosen concrete attempt validates its own capabilities.
            val capabilities=CarrierOptionContract.requiredAll(profile)
            if(capabilities.isEmpty())return
            val kind=EngineModules.carrierKind(profile)
            val info=try{installation(context,kind)}catch(_:android.content.pm.PackageManager.NameNotFoundException){throw ClientOptionalCoreRequired(kind)}
            if(CarrierOptionContract.missing(profile){info.metaData?.getInt(it)?:0})throw ClientOptionalCoreRequired(kind)
        }
        private fun startInternal(context:Context,rawProfile:JSONObject,cancelled:BooleanSupplier,timeoutMillis:Long,
            providerDiagnostic:Boolean,providerVersion:Int,onProviderStage:((Map<String,Boolean>?)->Unit)?,backgroundCandidate:Boolean,
            onRetiredFailure:(()->Unit)?=null,freshCandidate:ClientOpenFluxFreshGate.Verified?=null,originalGeneration:Long=0,
            serverProbe:Boolean=false,routerNetwork:Long?=null):NativeCarrier {
            if(CsqttAutoDetection.negotiates(rawProfile)) {
                var retired=false
                try {return CsqttAutoRuntime.connect(context,rawProfile,cancelled) {candidate->
                    retired=false
                    startInternal(context,candidate,cancelled,timeoutMillis,providerDiagnostic,providerVersion,onProviderStage,backgroundCandidate,{retired=true},freshCandidate,originalGeneration,serverProbe,routerNetwork)
                }}catch(error:Exception){if(retired)onRetiredFailure?.invoke();throw error}
            }
            require(timeoutMillis in 1000..180000)
            require(providerDiagnostic||providerVersion==1)
            val profile=if(rawProfile.optString("type") in SwgProfile.TYPES)
                CarrierStreamDefaults.apply(VkRelayRuntime.withManualSupplements(rawProfile,VkRelayAccount(AndroidDeployStore(context))),CarrierStreamDefaults.load(context))else JSONObject(rawProfile.toString())
            val kind = profile.getString("type")
            val vkBackoff=if(kind in SwgProfile.TYPES)VkCredentialBackoffStore(context)else null
            vkBackoff?.let {if(serverProbe)it.requireAllowed()else it.requireConnectionAllowed(profile)}
            if (kind == "openflux") profile.remove("mcrf_verification_ui") // Never trust a host capability from imported data.
            val moduleKind=EngineModules.carrierKind(profile)
            if(freshCandidate!=null) {
                ClientOpenFluxFreshGate.requireCaller(context)
                require(kind=="openflux"&&moduleKind=="openflux"&&!providerDiagnostic&&!backgroundCandidate&&!profile.optBoolean("probe_only"))
            }
            if(freshCandidate==null)requireOptions(context,profile)
            else if(CarrierOptionContract.requiredAll(profile).any {(freshCandidate.application.metaData?.getInt(it)?:0)<1})throw ClientOptionalCoreRequired(moduleKind)
            if (kind == "openflux" && freshCandidate == null) {
                val metadata = freshCandidate?.application?.metaData ?: installation(context, kind).metaData
                require(!providerDiagnostic && metadata?.getString("org.mcrf.engines.source.repository")==EngineUpdatePolicy.UPSTREAM && OpenFluxProfile.supportsFreshProfile(
                    metadata?.getString("org.mcrf.engines.source.version"),
                    metadata?.getString("org.mcrf.engines.source.commit"))) {
                    "Для этого профиля нужен модуль OpenFlux ${OpenFluxProfile.FRESH_VERSION}. Обнови модуль; старый движок не будет использован вместо него."
                }
            }
            val restricted=profile.optBoolean("probe_only")||backgroundCandidate
            val openFluxMetadata=if(kind=="openflux")freshCandidate?.application?.metaData ?: installation(context,kind).metaData else null
            val verificationUi=kind=="openflux"&&OpenFluxProfile.supportsVerification(
                openFluxMetadata?.getString("org.mcrf.engines.source.version"),openFluxMetadata?.getString("org.mcrf.engines.source.commit"),
                openFluxMetadata?.getInt("org.mcrf.engines.openflux.verification")?:0,restricted,providerDiagnostic)
            val ownVerificationUi=OpenFluxOwnVerificationPolicy.productionEnabled&&OpenFluxOwnVerificationPolicy.sourceCompatible(
                verificationUi,openFluxMetadata?.getInt("org.mcrf.engines.openflux.ownDocument")?:0)
            val ownHttpVerificationUi=OpenFluxHttpVerificationPolicy.productionEnabled&&OpenFluxHttpVerificationPolicy.sourceCompatible(
                verificationUi,openFluxMetadata?.getInt("org.mcrf.engines.openflux.ownHttp")?:0)
            if(moduleKind=="csqtt25"&&restricted&&profile.optString("vk_hash_mode")=="auto_js")throw IllegalArgumentException("CSQTT 2.5 Auto JS проверяется при ручном подключении; фоновый замер не создаёт VK-звонки.")
            val binary=freshCandidate?.executable ?: if(providerDiagnostic){require(kind=="openflux");ClientOpenFluxProviderGate.verify(context,providerVersion).executable}else executable(context,moduleKind)
            val multicall=binary.name=="libbs.so"&&BsEnginePackage.usesMulticall(context)
            val strictCarrier=ProfilePolicyRuntime.preferences(context).getBoolean(VpnOwnerIsolation.KEY,VpnOwnerIsolation.DEFAULT_ENABLED)
            val privateUdpKinds=setOf("qwdtt","wdtt","openflux",MasterDnsProfile.KIND,S3BypassProfile.KIND)
            if(moduleKind in CsqttGenerations.engines||strictCarrier&&moduleKind in privateUdpKinds){
                val capabilities=(freshCandidate?.application?:installation(context,moduleKind)).metaData
                val marker="MCRF_SOCKS_UDP_SOURCE_V1_$moduleKind"
                require(capabilities?.get("org.mcrf.engines.socks_udp_source.$moduleKind")==1&&
                    binary.inputStream().use {BsNativeContract.hasMarkers(it,listOf(marker))}){
                    "Обновите модуль $moduleKind для защищённого локального UDP."
                }
            }
            if(moduleKind in CsqttGenerations.engines){
                val transport=File(binary.parentFile,"libcsqtt_transport.so")
                val contract=listOf("MCRF_CSQTT_PRIVATE_UDP_KEY_V1")
                require(multicall&&transport.isFile&&transport.canExecute()&&
                    binary.inputStream().use {BsNativeContract.hasMarkers(it,contract)}&&
                    transport.inputStream().use {BsNativeContract.hasMarkers(it,contract)}){
                    "Обновите BS Engine для защищённого локального транспорта CSQTT."
                }
            }
            val underlayEnv=routerNetwork?.let {network->
                require(network>0&&(multicall||kind in setOf("wdtt","openflux","olcrtc","dnstt",VayDnsProfile.KIND,SlipstreamProfile.KIND,SstpProfile.KIND,MasterDnsProfile.KIND,S3BypassProfile.KIND))&&BsNativeContract.carrierRouter(context,profile,true))
                val key=when(kind){
                    "qwdtt"->BsNativeContract.QWDTT_UNDERLAY_ENV
                    "wdtt"->BsNativeContract.WDTT_UNDERLAY_ENV
                    "openflux"->BsNativeContract.OPENFLUX_UNDERLAY_ENV
                    "olcrtc"->BsNativeContract.OLCRTC_UNDERLAY_ENV
                    VayDnsProfile.KIND->BsNativeContract.VAYDNS_UNDERLAY_ENV
                    S3BypassProfile.KIND->BsNativeContract.S3BYPASS_UNDERLAY_ENV
                    "dnstt"->BsNativeContract.DNSTT_UNDERLAY_ENV
                    SlipstreamProfile.KIND->BsNativeContract.SLIPSTREAM_UNDERLAY_ENV
                    SstpProfile.KIND->BsNativeContract.SSTP_UNDERLAY_ENV
                    MasterDnsProfile.KIND->BsNativeContract.MASTERDNS_UNDERLAY_ENV
                    else->if(moduleKind=="csqtt25")BsNativeContract.MODERN_UNDERLAY_ENV else BsNativeContract.LEGACY_UNDERLAY_ENV
                }
                val transport=if(kind=="csqtt")BsEnginePackage.executable(context,"csqtt")else binary
                require(binary.inputStream().use {BsNativeContract.hasMarkers(it,listOf(key))}&&
                    (transport==binary||transport.inputStream().use {BsNativeContract.hasMarkers(it,listOf(key))})){
                    "БС: установленный транспорт не подтверждает привязку сокетов."
                }
                key
            }
            if(CsqttSwgVersions.needsNewRuntime(profile)&&!multicall)require(File(binary.parentFile,CsqttSwgVersions.LIBRARY).canExecute()) {
                "Обновите модуль CSQTT 2.5: отсутствует транспорт SWG 2.5.2 / 2.5.3 для этой архитектуры."
            }
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val owner = NativeCarrier(port)
            owner.failureDetail=CarrierFailureDetail(moduleKind,profile,listOf(owner.username,owner.password))
            owner.backgroundAdmission.set(restricted);owner.promotable=backgroundCandidate&&!profile.optBoolean("probe_only")
            owner.kind = kind
            if(kind in SwgProfile.TYPES)owner.startedStreamProfile=CarrierStreamRestartPolicy.startedProfile(profile,rawProfile)
            if(kind=="openflux"){owner.openFluxContext=context.applicationContext;owner.openFluxObservation=OpenFluxObservation()}
            val startedAt=android.os.SystemClock.elapsedRealtime()
            val lifecycleLogs=kind in SwgProfile.TYPES || kind in setOf(VayDnsProfile.KIND,SlipstreamProfile.KIND,SstpProfile.KIND,MasterDnsProfile.KIND)
            fun diagnostic(message:String){if(lifecycleLogs)ClientEvents.record(context,"Движки","${moduleKind.uppercase(java.util.Locale.ROOT)} · +${android.os.SystemClock.elapsedRealtime()-startedAt} мс · $message")}
            if(lifecycleLogs){val app=context.applicationContext;owner.lifecycleDiagnostic={message->ClientEvents.record(app,"Движки","${moduleKind.uppercase(java.util.Locale.ROOT)} · $message")}}
            diagnostic("Подготовка транспорта; лимит сети $timeoutMillis мс; замер=${profile.optBoolean("probe_only")}")
            if(providerDiagnostic){owner.providerReader=when(providerVersion){3->ClientOpenFluxProviderStage.v3();2->ClientOpenFluxProviderStage.v2();else->ClientOpenFluxProviderStage()};owner.providerCallback=onProviderStage}
            try {
                // Unix-domain socket names have a small byte limit. Keep the
                // Slipstream owner directory short, still random and app-private.
                val directory = File(context.cacheDir, if(kind==SlipstreamProfile.KIND)"mcrf-s-${UUID.randomUUID().toString().replace("-","").take(16)}"else "mcrf-carrier-${UUID.randomUUID()}")
                if (!directory.mkdir()) throw IOException("Cannot create carrier directory")
                owner.runtimeParent = context.cacheDir
                owner.directory = directory; Os.chmod(directory.absolutePath, 0x1c0)
                if(moduleKind in CsqttGenerations.engines)owner.processGroup=CarrierProcessGroup(binary,directory)
                if(originalGeneration>0) {
                    require(!backgroundCandidate&&!profile.optBoolean("probe_only")&&moduleKind in CsqttGenerations.engines){"ORIGINAL_CARRIER_ROLE"}
                    owner.originalControl=CarrierOriginalPacketControl(requireNotNull(owner.processGroup),originalGeneration){
                        owner.closed.get()||cancelled.asBoolean||owner.process?.isAlive==false||owner.ownershipFailure.get()||owner.startupFailure.get()!=null
                    }
                }
                val config = if(kind==MasterDnsProfile.KIND)MasterDnsProfile.privateConfig(profile,port,owner.username,owner.password) else if(kind==SstpProfile.KIND)SstpProfile.privateConfig(profile,port,owner.username,owner.password) else if(kind==SlipstreamProfile.KIND)SlipstreamProfile.privateConfig(profile,port,owner.username,owner.password) else if(kind==VayDnsProfile.KIND)VayDnsProfile.privateConfig(profile,port,owner.username,owner.password) else if(kind==S3BypassProfile.KIND)S3BypassProfile.privateConfig(profile,port,owner.username,owner.password) else if(kind in SwgProfile.TYPES) {
                    val udpPort=DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                    val effective=if(moduleKind=="csqtt25")JSONObject(profile.toString())else {
                        val account=VkRelayAccount(AndroidDeployStore(context))
                        if(restricted)VkRelayRuntime.prepareForProbe(profile,account){cancelled.asBoolean}
                        else VkRelayRuntime.prepare(profile,account){cancelled.asBoolean}
                    }
                    SwgProfile.privateConfig(effective,udpPort,port,owner.username,owner.password,profile.optString("device_id").ifEmpty { SubscriptionUpdates.identity(context) })
                } else if(kind=="dnstt")DnsttPrivateConfig.create(profile,port,owner.username,owner.password)
                else if(kind=="olcrtc")OlcPrivateConfig.create(profile,port,owner.username,owner.password,profile.optString("device_id").ifEmpty {UUID.randomUUID().toString()})
                else JSONObject(profile.toString()).put("socks", "127.0.0.1:$port")
                    .put("socks_user", owner.username).put("socks_pass", owner.password).put("device_id", profile.optString("device_id").ifEmpty { UUID.randomUUID().toString() })
                if(moduleKind=="wdtt"&&config.optBoolean("rt_masque")) {
                    if(!WdttMasqueStore.accepted(context))throw WdttMasqueConsentRequired()
                    val account=WdttMasqueStore.load(context)?:throw IllegalArgumentException("Сначала подготовьте MASQUE ручным подключением; фоновая проверка регистрацию не создаёт.")
                    config.put("masque_account",account)
                }
                if(kind=="openflux") {
                    config.put("mcrf_verification_ui",verificationUi)
                    // Document renderer/Binder/native end-to-end acceptance is still open.
                    // Never allow an imported profile to enable the future own-page channel.
                    config.put("mcrf_own_inline_ui",ownVerificationUi)
                    config.put("mcrf_own_http_ui",ownHttpVerificationUi) // Host gate overwrites imports; currently always false.
                }
                if(profile.optBoolean("probe_only")&&kind !in setOf("dnstt","olcrtc",S3BypassProfile.KIND,VayDnsProfile.KIND,SlipstreamProfile.KIND,SstpProfile.KIND,MasterDnsProfile.KIND)) config.put("probe_only",true)
                val bytes = config.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 65536) { "Профиль движка больше 64 КБ." }
                val file = File(directory, "config.json")
                if (!file.createNewFile()) throw IOException("Cannot create carrier configuration")
                Os.chmod(file.absolutePath, 0x180); FileOutputStream(file).use { it.write(bytes) }
                val flag = if(providerDiagnostic)ClientOpenFluxProviderGate.cli(providerVersion) else when(moduleKind) {"csqtt25"->"-csqtt25-config"; "dnstt",VayDnsProfile.KIND,SlipstreamProfile.KIND,SstpProfile.KIND,MasterDnsProfile.KIND,"openflux","wdtt" -> "--mcrf-config"; "qwdtt" -> "-config"; "csqtt","csqtt20","csqtt215" -> "-csqtt-config"; else -> "--qthrone-config" }
                val command=if(kind==S3BypassProfile.KIND)listOf(binary.absolutePath,"run","-config",file.absolutePath)else listOf(binary.absolutePath,flag,file.absolutePath)
                val builder = ProcessBuilder(command)
                    .directory(directory).redirectErrorStream(true)
                builder.environment()["XDG_CACHE_HOME"] = directory.absolutePath
                // Trusted host choice only; never copied from an imported server profile.
                BsEngineContract.configureHostMode(builder.environment(),binary.name,moduleKind,
                    multicall,
                    if(moduleKind=="csqtt25")CsqttSwgVersions.nativeImplementation(profile)else "")
                builder.environment().remove(BsNativeContract.QWDTT_UNDERLAY_ENV)
                builder.environment().remove(BsNativeContract.LEGACY_UNDERLAY_ENV)
                builder.environment().remove(BsNativeContract.MODERN_UNDERLAY_ENV)
                builder.environment().remove(BsNativeContract.WDTT_UNDERLAY_ENV)
                builder.environment().remove(BsNativeContract.OPENFLUX_UNDERLAY_ENV)
                builder.environment().remove(BsNativeContract.OLCRTC_UNDERLAY_ENV)
                builder.environment().remove(BsNativeContract.VAYDNS_UNDERLAY_ENV)
                builder.environment().remove(BsNativeContract.S3BYPASS_UNDERLAY_ENV)
                builder.environment().remove(BsNativeContract.DNSTT_UNDERLAY_ENV)
                builder.environment().remove(BsNativeContract.SLIPSTREAM_UNDERLAY_ENV)
                builder.environment().remove(BsNativeContract.SSTP_UNDERLAY_ENV)
                builder.environment().remove(BsNativeContract.MASTERDNS_UNDERLAY_ENV)
                underlayEnv?.let {builder.environment()[it]=checkNotNull(routerNetwork).toString()}
                owner.processGroup?.let {builder.environment()["MCRF_PROCESS_OWNER"]=it.nonce}
                builder.environment().remove(OriginalCarrierMode.ENVIRONMENT)
                owner.originalControl?.let {builder.environment()[OriginalCarrierMode.ENVIRONMENT]=it.privateConfiguration().toString()}
                val child = builder.start(); owner.process = child
                owner.ipc=CarrierIpcWriter(child.outputStream,maxCommandBytes=if(ownHttpVerificationUi)131072 else 16384)
                diagnostic("Процесс транспорта запущен; ожидаем сигнал готовности и проверку локального SOCKS")
                owner.outputThread=Thread({ var eof=false;try { child.inputStream.use { input -> val buffer=ByteArray(4096);val line=ByteArrayOutputStream();var dropped=false
                    fun announce(request:OpenFluxVerificationRegistry.Request){
                        val previousUiId=owner.openFluxUiId
                        owner.openFluxUiId=request.id;owner.openFluxTicketObserved=request.nativeId
                        owner.openFluxVerificationSeen.set(true)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if(previousUiId!=null&&previousUiId!=request.id)OpenFluxVerificationUi.cancel(context.applicationContext,previousUiId)
                            OpenFluxVerificationUi.show(context.applicationContext,request.id)
                        }
                    }
                    fun received(bytes:ByteArray){
                        if(TurnStreams.reserved(bytes)){
                            if(kind in SwgProfile.TYPES)owner.streamState.offer(bytes)
                            return // Consume invalid reserved records too; never log raw IPC.
                        }
                        if(kind=="openflux"&&OpenFluxHttpWire.recognized(bytes)){
                            synchronized(owner.inputLock){
                                val result=OpenFluxHttpDispatch.receive(bytes,owner.openFluxChild,ownHttpVerificationUi,owner.closed.get(),
                                    OpenFluxVerificationRequests.registry,owner.openFluxUiId,owner.openFluxTicketObserved,
                                    {!owner.closed.get()&&child.isAlive},{command->synchronized(owner.inputLock){!owner.closed.get()&&owner.ipc?.offer(command)==true}})
                                if(result.state==OpenFluxHttpDispatch.State.REJECTED)owner.openFluxProtocolRejected.set(true)
                                result.request?.let(::announce)
                            }
                            return // Reserved HTTP addresses/headers/body never reach diagnostics.
                        }
                        if(kind=="openflux"&&OpenFluxDocumentWire.recognized(bytes)) {
                            synchronized(owner.inputLock) {
                                val result=OpenFluxDocumentDispatch.receive(bytes,owner.openFluxChild,ownVerificationUi,owner.closed.get(),
                                    OpenFluxVerificationRequests.registry,owner.openFluxUiId,owner.openFluxTicketObserved,
                                    {!owner.closed.get()&&child.isAlive},{command->synchronized(owner.inputLock){!owner.closed.get()&&owner.ipc?.offer(command)==true}})
                                if(result.state==OpenFluxDocumentDispatch.State.REJECTED)owner.openFluxProtocolRejected.set(true)
                                result.request?.let(::announce)
                            }
                            return // Reserved HTML/data never reaches any diagnostic sink.
                        }
                        // Final diagnostics arrive during close, after the normal READY/
                        // challenge path has retired. Retain only the strict Boolean DTO.
                        owner.providerReader?.offer(bytes)
                        val text=bytes.toString(Charsets.UTF_8).trimEnd('\r')
                        if(kind=="openflux"&&text.startsWith(OpenFluxObservation.PREFIX)) {
                            owner.openFluxObservation?.offer(bytes)
                            return // Also consume late private records after a failed output-drain barrier.
                        }
                        if(kind=="openflux"&&OpenFluxVerificationWire.recognized(text)) {
                            // Consume private URL-bearing IPC before any diagnostic path.
                            synchronized(owner.inputLock) {
                                if(owner.closed.get())return
                                if(!verificationUi){owner.openFluxProtocolRejected.set(true);return}
                                if(text.startsWith(OpenFluxVerificationWire.PENDING)) {
                                    val pending=OpenFluxVerificationWire.pending(bytes)
                                    if(pending==null){owner.openFluxProtocolRejected.set(true);return}
                                    val request=OpenFluxVerificationRequests.registry.register(owner.openFluxChild,pending.nativeId,pending.page,
                                        {!owner.closed.get()&&child.isAlive}, {command->synchronized(owner.inputLock){!owner.closed.get()&&owner.ipc?.offer(command)==true}})
                                    if(request==null){owner.openFluxProtocolRejected.set(true);return}
                                    announce(request)
                                } else {
                                    val result=OpenFluxVerificationWire.reply(bytes)
                                    if(result==null){owner.openFluxProtocolRejected.set(true);return}
                                    OpenFluxVerificationRequests.registry.complete(owner.openFluxChild,result.nativeId)?.let {id->
                                        if(owner.openFluxUiId==id)owner.openFluxUiId=null
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {OpenFluxVerificationUi.cancel(context.applicationContext,id)}
                                        if(result.status!="accepted")owner.openFluxProtocolRejected.set(true)
                                    }
                                }
                            }
                            return
                        }
                        owner.failureDetail?.offer(text)
                        if(owner.closed.get())return
                        if(vkBackoff!=null)VkCredentialLimit.read(text)?.let {limit->
                            // Publish the typed cause even if recording a local
                            // backoff fails. No further auth/protocol fallback.
                            if(owner.vkCredentialLimit.compareAndSet(null,limit))diagnostic("VK ограничил получение TURN-данных: тип=${limit.kind}; пауза клиента=${limit.seconds} с; локальная пауза=${VkCredentialBackoffStore.backoff(limit).seconds} с")
                            CsqttProviderCooldown.observe(VkCredentialBackoffStore.backoff(limit))
                            runCatching {vkBackoff.observe(limit)}
                        }
                        DnsCarrierFailure.stage(moduleKind,text)?.let {diagnostic(it)}
                        if(text.startsWith(CarrierProcessGroupProof.PREFIX)&&owner.processGroup!=null) {
                            synchronized(owner.inputLock) {
                                if(!owner.closed.get())try {
                                    val ack=requireNotNull(owner.processGroup).bind(text).toByteArray(Charsets.US_ASCII)
                                    try {check(owner.ipc?.offer(ack)==true)}finally{ack.fill(0)}
                                }catch(_:Exception){owner.ownershipFailure.set(true)}
                            }
                            return
                        }
                        if(kind in SwgProfile.TYPES&&CarrierStartupFailure.isVerificationSignal(text)) {
                            if(owner.captchaSeen.compareAndSet(false,true))diagnostic("Фоновая проверка остановлена: VK запросил капчу; аккаунт и остальные вызовы не перебираются")
                        }
                        if(kind=="csqtt")CsqttNativeFailure.read(text)?.let {failure->
                            if(owner.startupFailure.compareAndSet(null,failure))diagnostic(failure.explanation)
                        }
                        if(text==OriginalCarrierMode.READY&&owner.originalPackets) {
                            if(owner.processGroup?.admitted()!=true||!owner.originalAdopted.get()) {owner.ownershipFailure.set(true);return}
                            owner.connected.set(true)
                            diagnostic("Исходный IP-канал принят основным движком; доступ к сайтам ещё не проверен")
                            return
                        }
                        if(text=="MCRF_READY"&&!owner.originalPackets) {
                            if(owner.processGroup?.admitted()==false){owner.ownershipFailure.set(true);return}
                            if(owner.connected.compareAndSet(false,true))diagnostic("Транспорт сообщил READY; это ещё не подтверждение доступа к сайтам")
                        }
                        // S3/Xray diagnostics are not a CAPTCHA IPC channel. Do not
                        // reinterpret a remote storage error line as a VK UI request.
                        if(kind!=S3BypassProfile.KIND)CarrierChallenge.envelope(text,strictSwg=kind in SwgProfile.TYPES)?.let { challenge ->
                            val id=challenge.nativeId;val url=challenge.url
                            owner.captchaSeen.set(true)
                            var flight:CarrierChallengeFlights.Ticket?=null
                            val respond:(String)->Unit={value->
                                if(value=="error:auto_rejected")flight?.let(owner.challengeFlights::reject)
                                val command=CarrierChallenge.command(id,value).toByteArray(Charsets.UTF_8)
                                try {synchronized(owner.inputLock){
                                    if(!owner.closed.get()&&owner.ipc?.offer(command)!=true)
                                        diagnostic("Ответ проверки VK не принят: очередь этого процесса закрыта или заполнена")
                                }} finally {command.fill(0)}
                            }
                            if(owner.backgroundAdmission.get()) respond("error:cancelled")
                            else {
                                if(kind in SwgProfile.TYPES&&owner.challengeFlights.rejected(challenge.sessionKey)) {
                                    diagnostic("Проверка VK для уже отклонённой сессии не повторялась")
                                    respond("error:provider_rejected");return@let
                                }
                                flight=if(kind in SwgProfile.TYPES)try {
                                    owner.challengeFlights.claim(challenge.flightKey,challenge.sessionKey)?:run {respond("error:cancelled");return@let}
                                }catch(_:CarrierChallengeFlights.CapacityExceeded){respond("error:cancelled");return@let} else null
                                // The profile type stays "csqtt" for every generation.
                                // Deadlines/expiry commands belong to the actual module,
                                // not to the generic import protocol name.
                                val challengeTimeout=CarrierChallenge.nativeTimeoutMillis(moduleKind,challenge.mode,challenge.nativeId)
                                diagnostic("Запрос проверки VK; режим=${if(challenge.mode in setOf("auto","manual","selected"))challenge.mode else "other"}; бюджет=$challengeTimeout мс")
                                val retired:(String)->Unit={request->
                                    flight?.let(owner.challengeFlights::retire)
                                    owner.challenges.remove(request);CarrierChallengeNotifications.cancel(context,request)
                                }
                                val request=try {
                                    if(kind in SwgProfile.TYPES)CarrierChallenge.registerBounded(id,url,respond,retired,challengeTimeout,challenge.mode=="auto",expired={
                                        // WDTT's original manual waiter outlives our bounded UI.
                                        // Release only that exact waiter; no proof and no global fallback.
                                        if(CarrierChallenge.scopedExpirySupported(moduleKind,id)&&!CarrierChallenge.cancelExpiredScopedAsync(moduleKind,id,respond))
                                            diagnostic("Проверка VK истекла; очередь отмены занята, действует штатный таймаут движка")
                                    })
                                    else CarrierChallenge.register(id,url,respond,retired)
                                }catch(error:Exception){flight?.let(owner.challengeFlights::retire);throw error}
                                if(request==null){flight?.let(owner.challengeFlights::retire);respond("error:cancelled")}
                                else {
                                    owner.challenges.add(request)
                                    if(owner.closed.get())CarrierChallenge.remove(request)
                                    else if(challenge.mode=="auto"&&kind in SwgProfile.TYPES)CarrierCaptchaAutomatic.start(context,request,moduleKind)
                                    else CarrierChallengeNotifications.show(context,request)
                                }
                            }
                        }
                    }
                    while(true){val count=input.read(buffer);if(count<0){eof=true;break};for(i in 0 until count){val value=buffer[i].toInt() and 255
                        if(value==10){if(!dropped)received(line.toByteArray());line.reset();dropped=false}
                        else if(line.size()<32768&&!dropped)line.write(value) else {
                            if(!dropped){
                                val oversized=line.toByteArray()
                                val privateHttp=kind=="openflux"&&OpenFluxHttpWire.discardDisabled(oversized,owner.closed.get()){
                                    owner.openFluxProtocolRejected.set(true)
                                }
                                val privateDocument=kind=="openflux"&&!privateHttp&&OpenFluxDocumentWire.discardUnsupported(oversized,owner.closed.get()){
                                    owner.openFluxProtocolRejected.set(true)
                                }
                                if(!privateHttp&&!privateDocument){
                                    owner.providerReader?.offer(oversized)
                                    owner.openFluxObservation?.offer(oversized)
                                    if(kind=="openflux"&&OpenFluxVerificationWire.recognized(oversized.copyOfRange(0,minOf(64,oversized.size)).toString(Charsets.US_ASCII)))owner.openFluxProtocolRejected.set(true)
                                }
                            }
                            line.reset();dropped=true
                        }
                    }}
                    if(!dropped&&line.size()>0)received(line.toByteArray())
                } } catch (error: Exception) {
                    owner.failureDetail?.offer("error: чтение ответа клиента: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                } finally {owner.outputDrained.set(eof)} },
                    "mcrf-carrier-output").apply { isDaemon = true; start() }
                var deadline = System.nanoTime() + timeoutMillis*1_000_000L;var extended=false
                var nextVkBackoffCheck=0L
                val manualBudget=if(kind in SwgProfile.TYPES)CarrierStartupBudget(timeoutMillis,android.os.SystemClock.elapsedRealtime())else null
                while (if(manualBudget!=null)manualBudget.canContinue(android.os.SystemClock.elapsedRealtime(),owner.challenges.any(CarrierChallenge::isManualPending))else System.nanoTime() < deadline) {
                    // An independent probe/route may have observed the same
                    // provider limit. Retire only THIS still-starting child.
                    val backoffNow=android.os.SystemClock.elapsedRealtime()
                    if(backoffNow>=nextVkBackoffCheck){
                        nextVkBackoffCheck=backoffNow+500L
                        vkBackoff?.current()?.let {throw VkCredentialLimit.Blocked(it)}
                    }
                    owner.checkFailure()
                    if (owner.captchaSeen.get() && owner.backgroundAdmission.get()) throw IllegalArgumentException(CarrierStartupFailure.verificationRequired)
                    if(manualBudget==null&&!extended&&owner.captchaSeen.get()&&!owner.backgroundAdmission.get()){deadline+=120_000_000_000L;extended=true}
                    if(manualBudget==null&&!extended&&owner.openFluxVerificationSeen.get()&&!owner.backgroundAdmission.get()){deadline+=150_000_000_000L;extended=true}
                    if (cancelled.asBoolean) throw IOException("Подключение отменено")
                    if(!child.isAlive) {
                        // Process exit can beat the output reader. Drain only on
                        // this startup worker, never on Android's UI/receiver.
                        owner.outputThread?.join(250)
                        owner.checkFailure()
                        if(owner.captchaSeen.get()&&owner.backgroundAdmission.get())throw IllegalArgumentException(CarrierStartupFailure.verificationRequired)
                    }
                    require(child.isAlive) {
                        owner.failureDetail?.describe("выход до готовности",runCatching {child.exitValue()}.getOrNull())
                            ?:"Движок завершился до готовности; код и причина недоступны."
                    }
                    if(owner.originalPackets&&owner.processGroup?.admitted()==true) {
                        if(owner.originalReceive==null) {
                            val receive=java.util.concurrent.FutureTask {
                                // The outer startup/CAPTCHA budget owns cancellation.
                                // Several carriers can wait for one slow member;
                                // this one-shot receiver must not shorten that budget.
                                requireNotNull(owner.originalControl).await(android.os.SystemClock.elapsedRealtime()+360_000L)
                            }
                            owner.originalReceive=receive
                            owner.originalReceiveThread=Thread(receive,"mcrf-original-packet-admission").apply {isDaemon=true;start()}
                        }
                        if(owner.originalReceive?.isDone==true) {
                            owner.originalEndpoint=try {requireNotNull(owner.originalReceive).get()}
                                catch(error:Exception){owner.checkFailure();throw IOException("ORIGINAL_CARRIER_ADMISSION_FAILED",error)}
                            owner.checkFailure()
                            diagnostic("Получен исходный IP-канал; ожидаем передачи владения основному движку")
                            return owner
                        }
                    }
                    if (!owner.originalPackets&&(kind !in SwgProfile.TYPES && kind !in setOf("openflux",VayDnsProfile.KIND,SlipstreamProfile.KIND,SstpProfile.KIND,MasterDnsProfile.KIND) || owner.connected.get()) && socksReady(port, owner.username, owner.password)) {
                        diagnostic("Локальный SOCKS прошёл проверку аутентификации; передаём транспорт основному движку")
                        owner.checkFailure()
                        return owner
                    }
                    Thread.sleep(120)
                }
                diagnostic("Истёк запуск; ready=${owner.connected.get()}; проверкаVK=${owner.captchaSeen.get()}; живыхПроверок=${owner.challenges.count {CarrierChallenge.get(it)!=null}}; процессЖив=${child.isAlive}")
                val timeout=if(kind in SwgProfile.TYPES)
                    CarrierStartupFailure.timeout(owner.connected.get(),owner.captchaSeen.get())
                    else "Движок не подключился вовремя."
                throw IllegalArgumentException("$timeout ${owner.failureDetail?.describe("ожидание готовности истекло",runCatching {child.exitValue()}.getOrNull()).orEmpty()}")
            } catch (error: Exception) {
                if(error is InterruptedException)Thread.currentThread().interrupt()
                owner.close()
                onRetiredFailure?.invoke()
                throw error
            }
        }
        @JvmStatic fun socksReady(port: Int, user: String, pass: String): Boolean = try {
            socket(port).use { it.outputStream.write(byteArrayOf(5, 1, 0)); if (it.inputStream.read() != 5 || it.inputStream.read() != 255) return false }
            socket(port).use { socket ->
                val output = socket.outputStream; val input = socket.inputStream
                output.write(byteArrayOf(5, 1, 2)); if (input.read() != 5 || input.read() != 2) return false
                val username = user.toByteArray(Charsets.UTF_8); val password = pass.toByteArray(Charsets.UTF_8)
                output.write(1); output.write(username.size); output.write(username); output.write(password.size); output.write(password)
                input.read() == 1 && input.read() == 0
            }
        } catch (_: IOException) { false }
        private fun socket(port: Int): Socket = Socket().apply {
            try { connect(InetSocketAddress("127.0.0.1", port), 250); soTimeout = 500 }
            catch (error: IOException) { close(); throw error }
        }
        internal fun deleteRuntimeDirectory(parent: File, root: File) {
            if (!root.name.matches(Regex("mcrf-(?:carrier-[0-9a-f-]{36}|s-[0-9a-f]{16})")) ||
                root.canonicalFile.parentFile != parent.canonicalFile || Files.isSymbolicLink(root.toPath())) {
                throw IOException("Unsafe runtime path")
            }
            delete(root, root)
        }
        private fun delete(root: File, item: File) {
            val path = item.canonicalPath; val base = root.canonicalPath
            if (!(path == base || path.startsWith(base + File.separator)) ||
                Files.isSymbolicLink(item.toPath())) throw IOException("Unsafe runtime path")
            item.listFiles()?.forEach { delete(root, it) }
            if (!item.delete() && item.exists()) throw IOException("Carrier runtime cleanup failed")
        }
    }
}

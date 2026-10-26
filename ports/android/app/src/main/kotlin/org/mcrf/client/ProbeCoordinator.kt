package org.mcrf.client

import android.content.Context
import android.content.Intent
import android.os.*
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ProbeCoordinator {
    private val cancellation = java.util.concurrent.atomic.AtomicLong()
    private val explicitCancellation = java.util.concurrent.atomic.AtomicLong()
    private val startLock = Any()
    private val measureLock = Any()
    @Volatile private var runtimeOwner:McrfVpnService?=null
    @Volatile private var runtimeProbeSubmitted=false
    private val observers=java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun observe(observer: () -> Unit) {observers.add(observer)}
    fun unobserve(observer: () -> Unit) {observers.remove(observer)}
    private fun changed() = presentationChanged()
    internal fun presentationChanged() {observers.forEach {observer->try{observer()}catch(_:Exception){/* Detached presentation observer is not a probe failure. */}}}
    fun cancellationToken() = cancellation.get()
    @Volatile var progress = ""
        private set
    @Volatile var measurements: ProbeProgress? = null
        private set
    @Volatile var diagnostic=""
        private set
    @Volatile var nativeDiagnostic=""
        private set
    @Volatile var providerStage=""
        private set
    fun cancel(context: Context) = synchronized(startLock) {
        explicitCancellation.incrementAndGet();cancellation.incrementAndGet(); context.stopService(Intent(context, ProbeService::class.java)); Unit
    }
    /** At most one temporary helper at a time. Its native probe has actually
     * quiesced before close. Selection later starts and probes a NEW ingress;
     * these ranking-only bindings never admit that replacement process. */
    internal fun measureSteerCandidatesRuntime(context:McrfVpnService,entries:List<ServerImport.Server>,state:JSONObject,
        testUrl:String,cancelled:()->Boolean):SteerProbeDelays = synchronized(measureLock) {
        val token=context.runtimeSteerProbeToken()?:error("STEER_PROBE_RUNTIME_OWNER")
        measureSteerCandidates(context,entries,state,{cancelled()||context.runtimeSteerProbeToken()!=token}) {nodes,retired->
            measureSteerRuntime(context,nodes,testUrl,retired) as SteerProbeDelays
        }
    }
    private fun measureSteerCandidates(context:Context,entries:List<ServerImport.Server>,state:JSONObject,
        cancelled:()->Boolean,measureNative:(List<ServerImport.Server>,()->Boolean)->SteerProbeDelays):SteerProbeDelays {
        require(entries.isNotEmpty()&&entries.size<=256&&entries.map {it.id}.toSet().size==entries.size&&entries.all {SteerAutomaticPool.compatible(it)})
        val cancelToken=explicitCancellation.get()
        val network=DirectAccessCheck.physical(context)?.networkHandle?:error("STEER_PROBE_PHYSICAL_NETWORK")
        val installed=EngineUpdatePrimary.installation(context,"steer")
        val sha=ClientPrimaryWorkerWire.hashFile(File(installed.sourceDir))
        fun retired()=cancelled()||explicitCancellation.get()!=cancelToken||DirectAccessCheck.physical(context)?.networkHandle!=network
        fun checkOwner(){check(!retired()) {"STEER_ASSISTED_PROBE_RETIRED"}}
        val values=LinkedHashMap<String,Long>();val times=LinkedHashMap<String,Long>()
        val bindings=LinkedHashMap<String,AssistedSteerProbeBinding>()
        fun append(result:SteerProbeDelays){
            checkOwner();require(result.moduleSha==sha&&result.networkHandle==network){"STEER_ASSISTED_PROBE_ORIGIN"}
            require(values.keys.intersect(result.keys).isEmpty())
            values.putAll(result);times.putAll(result.completedAt)
        }
        val lease=EngineUpdateGate.acquireUsage(context)?:error("Заверши установку движка перед замером.")
        try {
            checkOwner()
            val native=entries.filterNot(CompactProtocolPolicy::eligible)
            if(native.isNotEmpty())append(measureNative(native,::retired))
            for(entry in entries.filter(CompactProtocolPolicy::eligible)) {
                checkOwner()
                try {
                    val helper=CompactProtocolSession.start(context,entry,state,network,java.util.function.BooleanSupplier(::retired))
                    try {
                        val result=measureNative(listOf(helper.effective),::retired)
                        helper.requireAlive(DirectAccessCheck.physical(context)?.networkHandle)
                        helper.requireHealthy(result.getValue(entry.id)>=0)
                        append(result);bindings[entry.id]=helper.binding
                    }finally{OptionalCoreRetirement.drain(helper)}
                }catch(error:Exception){
                    checkOwner()
                    if(!PrimaryFallbackPolicy.canRetryFailure(error))throw error
                    values[entry.id]=-1;times[entry.id]=SystemClock.elapsedRealtime()
                    ClientEvents.record(context,"Замер Steer","${entry.name}: ${error.message.orEmpty()}")
                }
            }
            checkOwner()
            require(ClientPrimaryWorkerWire.hashFile(File(EngineUpdatePrimary.installation(context,"steer").sourceDir))==sha){"STEER_ASSISTED_MODULE_CHANGED"}
            return SteerProbeDelays(values,times,sha,network,bindings).also {SteerProbeCache.publish(installed.sourceDir,sha,network,entries,it)}
        }finally{OptionalCoreRetirement.drain(lease)}
    }
    /** Wait for the other process's actual child/core cleanup before an ordinary TUN can capture its UID. */
    fun awaitQuiescent(context: Context) {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Ожидание замера не должно блокировать интерфейс." }
        val closed=CountDownLatch(1)
        val receiver=object:ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode:Int,resultData:Bundle?) {if(resultCode==0)closed.countDown()}
        }
        synchronized(startLock) {
            cancellation.incrementAndGet()
            context.startService(Intent(context,ProbeService::class.java).setAction(ProbeService.QUIESCE).putExtra("result",receiver))
        }
        check(closed.await(20,TimeUnit.SECONDS)) { "Замер ещё останавливается. Повторите подключение после его завершения." }
    }
    /** Caller uses the client worker; the probe core itself lives in a separate Android process. */
    @JvmOverloads fun measure(context: Context, entries: List<ServerImport.Server>, testUrl: String = "https://www.gstatic.com/generate_204", expectedCancellation: Long = cancellationToken(), providerDiagnostic:Boolean=false,providerVersion:Int=1): Map<String, Long> = synchronized(measureLock) {
        if(!providerDiagnostic&&ClientPrimarySelection.selected(context)=="steer"&&entries.any(CompactProtocolPolicy::eligible)){
            check(cancellation.get()==expectedCancellation){"Замер отменён. Предыдущие результаты сохранены."}
            require(providerVersion==1)
            val routing=RoutingStore(context);routing.load()
            measureSteerCandidates(context,entries,routing.snapshot(),{ClientPrimarySelection.selected(context)!="steer"}) {nodes,retired->
                check(!retired()) {"STEER_ASSISTED_PROBE_RETIRED"}
                try {measureInternal(context,nodes,testUrl,cancellationToken(),false,1,null) as SteerProbeDelays}
                finally {awaitProbeRetirement(context)}
            }
        }else measureInternal(context,entries,testUrl,expectedCancellation,providerDiagnostic,providerVersion,null)
    }
    private fun awaitProbeRetirement(context:Context){
        var interrupted=Thread.interrupted()
        try {
            while(true){
                try{awaitQuiescent(context);return}
                catch(error:Exception){if(error is InterruptedException||Thread.interrupted())interrupted=true}
                try{Thread.sleep(250)}catch(_:InterruptedException){interrupted=true}
            }
        }finally{if(interrupted)Thread.currentThread().interrupt()}
    }
    /** The service's explicit runtime scope can measure while retaining its
     * own TUN. UI callers still cannot probe during a startup/cleanup. */
    internal fun measureSteerRuntime(context:McrfVpnService,entries:List<ServerImport.Server>,testUrl:String,cancelled:()->Boolean):Map<String,Long> = synchronized(measureLock) {
        val ownerToken=context.runtimeSteerProbeToken()
        check(runtimeOwner==null&&ownerToken!=null) {"STEER_PROBE_RUNTIME_OWNER"}
        runtimeOwner=context;runtimeProbeSubmitted=false
        try {measureInternal(context,entries,testUrl,cancellationToken(),false,1) {
            cancelled()||context.runtimeSteerProbeToken()!=ownerToken
        }}
        finally {
            try {
                if(runtimeProbeSubmitted)awaitProbeRetirement(context)
            }finally{runtimeOwner=null;runtimeProbeSubmitted=false}
        }
    }
    internal fun cancelRuntime(context:McrfVpnService) {if(runtimeOwner===context)cancel(context)}
    private fun measureInternal(context:Context,entries:List<ServerImport.Server>,testUrl:String,expectedCancellation:Long,
        providerDiagnostic:Boolean,providerVersion:Int,runtimeCancelled:(()->Boolean)?):Map<String,Long> = synchronized(measureLock) {
        providerStage=""
        fun ensureNotCancelled() {
            if (cancellation.get() != expectedCancellation) throw java.util.concurrent.CancellationException("Замер отменён. Предыдущие результаты сохранены.")
            if(runtimeCancelled!=null) {
                check(context is McrfVpnService&&runtimeOwner===context&&!runtimeCancelled()) {"STEER_PROBE_RUNTIME_RETIRED"}
            }else require(!McrfVpnService.cleanupPending()&&McrfVpnService.status!="Подключение…"&&!McrfVpnService.status.startsWith("Переключаем движок")) {"Дождитесь завершения подключения или остановки перед замером серверов."}
        }
        ensureNotCancelled()
        require(!EngineUpdates.installationPending(context)&&!EngineUpdateScripts.mutationPending()){"Завершите обновление движка перед замером серверов."}
        require(entries.isNotEmpty()) { "Нет серверов для замера." }
        require(providerDiagnostic||providerVersion==1)
        if(providerDiagnostic){ClientOpenFluxProviderGate.requireApproved(entries);ClientOpenFluxProviderGate.verify(context,providerVersion)}
        require(entries.size <= 256) { "За один замер допускается до 256 серверов." }
        val steer=ProbeBackendScope.usesSteer(ClientPrimarySelection.selected(context),
            runtimeCancelled!=null&&runtimeOwner===context,providerDiagnostic)
        val steerModule=if(steer)EngineUpdatePrimary.installation(context,"steer")else null
        val steerSha=steerModule?.let {ClientPrimaryWorkerWire.hashFile(File(it.sourceDir))}
        val steerNetwork=if(steer)DirectAccessCheck.physical(context)?.networkHandle
            ?:throw IllegalArgumentException("Нет физической сети для проверки Steer.")else 0L
        if(steer)require(ClientPrimarySelection.steerContract(context).independentProbe) {
            "Установленный Steer не заявляет независимую проверку HTTPS. Нужен совместимый модуль Steer; замер через sing-box вместо него не запускается."
        }
        val steerProfiles=if(steer)JSONArray(entries.map(SteerProbePlan::request))else null
        require(entries.all { it.configuration == null && (!it.needsEngine() || it.carrier != null) }) { "Отдельные JSON-профили не входят в групповой замер." }
        require(entries.none { it.carrier != null } || !McrfVpnService.status.startsWith("VPN подключён") || McrfVpnService.nativeVpnExclusion) {
            "Для замера дополнительных движков отключи обычный VPN MCRF: иначе проверка пройдёт через его выход, а не текущую сеть."
        }
        val carriers = JSONArray()
        val bridges = entries.map { entry -> if (entry.carrier == null) entry else {
            carriers.put(JSONObject().put("id",entry.id).put("tag",ServerImport.tag(entry)).put("profile",entry.carrier))
            ServerImport.Server(entry.original,entry.name,"socks","127.0.0.1",1,
                JSONObject().put("type","socks").put("version","5").put("server","127.0.0.1").put("server_port",1),null,null)
        } }
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val generated = ServerImport.build(bridges, "", true, 60, 50, "Cloudflare")
        val outputs = generated.getJSONArray("outbounds")
        repeat(outputs.length()) { i -> val node = outputs.getJSONObject(i)
            if (node.optString("type") == "urltest") { node.put("type", "selector"); listOf("url", "interval", "tolerance").forEach(node::remove) }
        }
        val config = ProfileConfig.build(generated.toString(), false, port).apply { put("inbounds", JSONArray()) }
        val extraSettings=OptionalCoreStore(context).load()
        val tuningSettings=CoreTuningStore(context).load()
        val extraKinds=OptionalCoreProbe.needed(config,extraSettings)
        require(extraKinds.isEmpty()||!McrfVpnService.status.startsWith("VPN подключён")||McrfVpnService.nativeVpnExclusion) {
            "Для замера дополнительных ядер отключите обычный VPN MCRF: проверка должна пройти через выбранный сервер, а не через активное VPN-подключение."
        }
        val request = JSONObject().put("config", config).put("extraSettings",extraSettings.json()).put("transportSettings",tuningSettings.json()).put("carriers",carriers).put("url",testUrl).put("servers", JSONArray(entries.map { JSONObject().put("id", it.id).put("tag", ServerImport.tag(it)) }))
        if(steer)request.put("backend","steer").put("steerProfiles",steerProfiles).put("moduleSha256",steerSha)
            .put("networkHandle",steerNetwork).put("protectSockets",McrfVpnService.ownsCurrentTun())
        if(providerDiagnostic)request.put("providerDiagnostic",true).put("providerDiagnosticVersion",providerVersion).put("providerDiagnosticOriginalSha",ClientOpenFluxProviderGate.APPROVED_ORIGINAL_SHA)
        val file = File(context.cacheDir, "mcrf-probe-input-${UUID.randomUUID()}.json")
        val requestBytes=request.toString().toByteArray(Charsets.UTF_8)
        require(requestBytes.size<=ServerImport.MAX_BYTES) {"Запрос проверки серверов больше 4 МБ."}
        check(file.createNewFile()); Os.chmod(file.absolutePath, 0x180); file.writeBytes(requestBytes)
        val latch = CountDownLatch(1); var result: Map<String, Long>? = null; var failure: String? = null
        var steerTimes:Map<String,Long>?=null
        var progressRejected=false
        val replies=ProbeReplyWindow()
        fun receive(code:Int,data:Bundle?) {
                if(progressRejected)return
                if (cancellation.get() != expectedCancellation) {
                    if(code!=2){failure="Замер отменён. Предыдущие результаты сохранены.";replies.close();latch.countDown()}
                    return
                }
                if(code==2) {
                    val id=data?.getString("id")
                    if(id!=null && data.containsKey("delay")) {
                        try {measurements=measurements?.record(id,data.getLong("delay"))}
                        catch(_:IllegalArgumentException){
                            progressRejected=true
                            failure="Некорректный результат замера. Предыдущие результаты сохранены."
                            replies.close();latch.countDown();return
                        }
                        progress=measurements?.label().orEmpty()
                        changed()
                    }
                    return
                }
                if(BuildConfig.DEBUG)providerStage=data?.getString("providerStage","").orEmpty()
                if (code == 0 && data != null) {
                    if(steer&&(data.getString("backend")!="steer"||data.getString("moduleSha256")!=steerSha||data.getLong("networkHandle")!=steerNetwork)) {
                        failure="Не совпало происхождение результата Steer.";replies.close();latch.countDown();return
                    }
                    if(steer) {
                        val times=data.getBundle("completedAt")
                        if(times==null||times.keySet()!=measurements?.ids||times.keySet().any {times.get(it) !is Long||times.getLong(it) !in 0..SystemClock.elapsedRealtime()}) {
                            failure="Некорректное время проверки Steer.";replies.close();latch.countDown();return
                        }
                        steerTimes=times.keySet().associateWith {times.getLong(it)}
                    }
                    diagnostic=data.getString("diagnostic","");nativeDiagnostic=data.getString("nativeStage","");val values = data.getBundle("delays") ?: Bundle(); result = values.keySet().associateWith { values.getLong(it, -1) }
                }
                else failure = data?.getString("error") ?: "Не удалось выполнить замер."
                replies.close()
                latch.countDown()
        }
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code:Int,data:Bundle?){replies.deliver {receive(code,data)}}
        }
        try {
            synchronized(startLock) {
                ensureNotCancelled()
                measurements=ProbeProgress(entries.map { it.id }.toSet())
                progress=measurements!!.label()
                diagnostic=""
                nativeDiagnostic=""
                providerStage=""
                changed()
                if(runtimeOwner===context)runtimeProbeSubmitted=true
                context.startForegroundService(Intent(context, ProbeService::class.java).putExtra("input", file.absolutePath).putExtra("result", receiver))
            }
            val timeout = if(steer)15_000L+entries.size*45_000L else 15_000L + ((entries.size + 3) / 4) * 12_000L + carriers.length()*90_000L
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout)
            while(!latch.await(200,TimeUnit.MILLISECONDS)) {
                ensureNotCancelled()
                require(System.nanoTime()<deadline) {"Время замера истекло."}
            }
            ensureNotCancelled()
            require(failure == null) { failure.orEmpty() }
            require(result?.keys == measurements?.ids) { "Получен неполный результат замера. Предыдущие результаты сохранены." }
            require(result?.values?.all {it>=-1}==true) {"Некорректная задержка замера. Предыдущие результаты сохранены."}
            val completed=result?:throw IllegalArgumentException("Не получен результат замера.")
            if(steer) {
                require(DirectAccessCheck.physical(context)?.networkHandle==steerNetwork&&
                    ClientPrimaryWorkerWire.hashFile(File(requireNotNull(steerModule).sourceDir))==steerSha) {"Во время проверки Steer изменились сеть или модуль. Результат не применён."}
                val native=SteerProbeDelays(completed,requireNotNull(steerTimes),requireNotNull(steerSha),steerNetwork)
                SteerProbeCache.publish(steerModule.sourceDir,requireNotNull(steerSha),steerNetwork,entries,native)
                return native
            }
            return completed
        } finally {
            replies.close()
            progress=""
            measurements=null
            changed()
            file.delete()
            if (result == null) context.stopService(Intent(context, ProbeService::class.java))
        }
    }
}

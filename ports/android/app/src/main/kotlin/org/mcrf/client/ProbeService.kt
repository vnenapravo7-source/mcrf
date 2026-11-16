package org.mcrf.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Engine
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import android.app.Notification as AndroidNotification

class ProbeService : ClientPrimaryHostPlatform() {
    private val worker = Executors.newSingleThreadExecutor()
    private val probes = Executors.newFixedThreadPool(4)
    private val diagnosticDeadlines = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var cancelled = false
    @Volatile private var core: ClientPrimaryWorker? = null
    private val generation = AtomicLong(System.nanoTime().coerceAtLeast(1))
    private val running = AtomicBoolean(false)
    @Volatile private var comparisonNetwork: Long? = null
    private val comparisonCompletionLock = Any()
    private var comparisonDestroyed = false
    private var comparisonCompletion: (() -> Unit)? = null

    private fun comparisonRetired(): Boolean {
        val network = comparisonNetwork ?: return false
        val manager = getSystemService(android.net.ConnectivityManager::class.java)
        return ClientPrimarySelection.selected(this) != "singbox" || DirectAccessCheck.physical(this)?.networkHandle != network ||
            McrfVpnService.hasActiveSession() || McrfVpnService.cleanupPending() || manager.allNetworks.any {
                manager.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            }
    }

    private fun cancelProbe() {
        cancelled = true
        // Nonblocking: wakes blocking health/start calls; the worker thread awaits death.
        core?.requestStop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }
        @Suppress("DEPRECATION") val receiver = intent.getParcelableExtra<ResultReceiver>("result")
        if (intent.action == QUIESCE) {
            lifetime.quiesce { cleaned, values ->
                try { receiver?.send(if (cleaned) 0 else 1, Bundle().apply {
                    putBundle("delays", Bundle().apply { values.forEach { (id, delay) -> putLong(id, delay) } })
                }) } finally { if (!running.get()) stopSelf(startId) }
            }
            return START_NOT_STICKY
        }
        val run = if (cancelled) null else lifetime.begin { cancelProbe() }
        if (run == null) {
            receiver?.send(1, Bundle().apply { putString("error", "Предыдущий замер ещё выполняется или останавливается.") })
            return START_NOT_STICKY
        }
        running.set(true)
        if (EngineUpdates.installationPending(this) || EngineUpdateScripts.mutationPending()) {
            running.set(false)
            lifetime.finish(run, true, emptyMap())
            receiver?.send(1, Bundle().apply { putString("error", "Завершите установку движка перед замером серверов.") })
            stopSelf(startId); return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("probe", "Замеры серверов", NotificationManager.IMPORTANCE_LOW))
        val notification = AndroidNotification.Builder(this, "probe").setSmallIcon(R.drawable.status_icon)
            .setContentTitle("MCRF · замер серверов").setContentText("Активное соединение не переключается").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(21, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(21, notification)
        val input = intent.getStringExtra("input")
        val comparisonId = intent.getStringExtra("transportComparisonRun")
        val comparisonDeadline = intent.getLongExtra("transportComparisonDeadline", 0)
        // Bound only this accepted run; never cancel a different service owner's probe.
        val comparisonTimer = if (comparisonId != null) diagnosticDeadlines.schedule({ cancelProbe() },
            (comparisonDeadline - SystemClock.elapsedRealtime()).coerceIn(1, 25_000), TimeUnit.MILLISECONDS) else null
        val comparisonPathWatcher = if (comparisonId != null) diagnosticDeadlines.scheduleWithFixedDelay({
            try { if (comparisonRetired()) cancelProbe() } catch (_: Exception) { cancelProbe() }
        }, 0, 100, TimeUnit.MILLISECONDS) else null
        worker.execute {
            var optionalProbe: OptionalCoreProbe.Session? = null
            var transport: NativeCarrier? = null
            var engineUsage: EngineUpdateGate.Usage? = null
            var results: ClientPrimaryProbeResults? = null
            val diagnostic = AtomicReference("")
            var nativeStage = ""
            var completionCode = 1
            var errorText = "Не удалось проверить серверы. Сохранённые подключения не изменены."
            var cleanupComplete = false
            var providerDiagnostic=false
            var providerVersion=1
            val providerStage=AtomicReference("")
            val completedAt=java.util.concurrent.ConcurrentHashMap<String,Long>()
            var backend="singbox";var moduleSha="";var probeNetwork=0L
            try {
                val file = File(input ?: throw IOException("PROBE_INPUT"))
                require(file.canonicalFile.parentFile == cacheDir.canonicalFile &&
                    file.name.matches(Regex("mcrf-probe-input-[0-9a-f-]{36}\\.json"))) { "PROBE_INPUT_PATH" }
                val request = file.inputStream().use { JSONObject(String(ImportIO.read(it), Charsets.UTF_8)) }
                if (comparisonId != null) {
                    require(comparisonId.matches(Regex("[0-9a-f-]{36}")) && request.getString("transportComparisonRun") == comparisonId &&
                        request.getLong("transportComparisonDeadline") == comparisonDeadline &&
                        SystemClock.elapsedRealtime() < comparisonDeadline && request.getJSONArray("servers").length() == 1 &&
                        request.getJSONArray("carriers").length() == 0 && !request.optBoolean("providerDiagnostic") &&
                        request.optString("backend", "singbox") == "singbox" && request.getString("url") == TransportComparisonPlan.ENDPOINT) { "TRANSPORT_COMPARISON_REQUEST" }
                    comparisonNetwork = request.getLong("transportComparisonNetwork")
                    require(transportComparisonIdentity(this) == request.getString("transportComparisonIdentity")) { "TRANSPORT_COMPARISON_IDENTITY" }
                    ensureActive()
                }
                val array = request.getJSONArray("servers")
                require(array.length() in 1..256) { "PROBE_SERVER_COUNT" }
                val servers = (0 until array.length()).map {
                    val item = array.getJSONObject(it)
                    item.getString("id") to item.getString("tag").also { tag -> require(tag.isNotEmpty() && tag.length <= 8192) }
                }
                val ledger = ClientPrimaryProbeResults(servers.map { it.first }) { count, id, delay ->
                    completedAt[id]=SystemClock.elapsedRealtime()
                    receiver?.send(2, Bundle().apply { putInt("completed", count); putString("id", id); putLong("delay", delay) })
                }
                results = ledger
                val extras = request.optJSONArray("carriers") ?: org.json.JSONArray()
                require(extras.length() <= servers.size) { "PROBE_CARRIER_COUNT" }
                val carriers = (0 until extras.length()).map { extras.getJSONObject(it) }.associateBy { it.getString("id") }
                require(carriers.size == extras.length() && carriers.all { (id, item) -> servers.contains(id to item.getString("tag")) }) {
                    "PROBE_CARRIER_IDS"
                }
                providerDiagnostic=request.optBoolean("providerDiagnostic",false)
                providerVersion=request.optInt("providerDiagnosticVersion",1)
                require(providerDiagnostic||providerVersion==1)
                if(providerDiagnostic){
                    require(BuildConfig.DEBUG&&servers.size==1&&carriers.size==1&&
                        request.getString("providerDiagnosticOriginalSha")==ClientOpenFluxProviderGate.APPROVED_ORIGINAL_SHA&&
                        carriers.values.single().getJSONObject("profile").getString("type")=="openflux")
                    ClientOpenFluxProviderGate.verify(this,providerVersion)
                    providerStage.set(JSONObject().put("state","not_reported").toString())
                }
                val url = request.optString("url", "https://www.gstatic.com/generate_204")
                require(url.length <= 8192 && java.net.URI(url).scheme in setOf("https", "http")) { "PROBE_URL" }
                engineUsage = EngineUpdateGate.acquireUsage(this) ?: throw IOException("PROBE_MODULE_UPDATE")
                ensureActive()
                backend=request.optString("backend","singbox")
                require(backend in setOf("singbox","steer","carrier")) {"PROBE_BACKEND"}
                fun measureCarrier(id:String,item:JSONObject,latency:(NativeCarrier)->Long){
                    try {
                        ensureActive()
                        val profile=JSONObject(item.getJSONObject("profile").toString()).put("probe_only",true)
                        val vk=if(profile.optString("type") in SwgProfile.TYPES)VkCredentialBackoffStore(this)else null
                        val ticket=vk?.serverPingTicket()
                        val cancel=java.util.function.BooleanSupplier {cancelled}
                        val activeTransport=if(vk!=null)NativeCarrier.startForServerProbe(this,profile,cancel)
                        else NativeCarrier.start(this,profile,cancel,60000,providerDiagnostic,providerVersion){events->
                            providerStage.set(if(events==null)JSONObject().put("state","not_reported").toString()
                                else JSONObject().put("state","reported").put("events",JSONObject(events)).toString())
                        }
                        transport=activeTransport
                        ensureActive()
                        val delay=latency(activeTransport)
                        ensureActive()
                        val accepted=delay>=0&&vk?.confirmServerPing(ticket,profile)!=false
                        ledger.record(id,if(accepted)delay else -1)
                    }catch(error:Exception){
                        diagnostic.set("carrier/core: other")
                        nativeStage=EngineUpdateOpenFluxDiagnostics.nativeFailure(error)
                        ledger.record(id,-1)
                        check(core==null){"PROBE_PRIMARY_CLEANUP"}
                    }finally{
                        if(core==null){transport?.close();transport=null}
                    }
                }
                if(backend=="carrier"){
                    require(!providerDiagnostic&&java.net.URI(url).scheme=="https"&&carriers.size==servers.size&&
                        carriers.values.all {ProbePlan.independentCarrier(it.getJSONObject("profile"))}){"PROBE_CARRIER_SCOPE"}
                    for((id,item) in carriers)measureCarrier(id,item){CarrierSocksSwitch.probe(it,url,10000)}
                }else if(backend=="steer") {
                    errorText="Не удалось проверить серверы собственным движком Steer. Замена на sing-box не выполнялась."
                    require(!providerDiagnostic&&carriers.isEmpty()) {"STEER_PROBE_NO_CARRIER"}
                    moduleSha=request.getString("moduleSha256");probeNetwork=request.getLong("networkHandle")
                    require(request.opt("protectSockets") is Boolean) {"STEER_PROBE_PROTECTION"}
                    val installed=EngineUpdatePrimary.installation(this,"steer")
                    require(ClientPrimarySelection.steerContract(this).independentProbe&&
                        ClientPrimaryWorkerWire.hashFile(File(installed.sourceDir))==moduleSha&&probeNetwork>0) {"STEER_PROBE_MODULE_IDENTITY"}
                    val profiles=request.getJSONArray("steerProfiles")
                    require(profiles.length()==servers.size) {"STEER_PROBE_PROFILES"}
                    val captured=(0 until profiles.length()).map {profiles.getJSONObject(it)}
                    require(captured.map {it.getString("id")}.toSet()==servers.map {it.first}.toSet()&&captured.size==captured.map {it.getString("id")}.toSet().size) {"STEER_PROBE_IDS"}
                    for(item in captured) {
                        ensureActive();require(DirectAccessCheck.physical(this)?.networkHandle==probeNetwork) {"STEER_PROBE_NETWORK_CHANGED"}
                        val profile=item.getString("profile");val host=item.getString("host");val id=item.getString("id")
                        SteerProbePlan.captured(item)
                        var delay=-1L
                        try {delay=withSteerCore(profile,host,url,probeNetwork,request.getBoolean("protectSockets"))}
                        catch(error:Exception){
                            diagnostic.set("Steer HTTPS: unavailable")
                            check(core==null) {"STEER_PROBE_CLEANUP_PENDING"}
                            if(cancelled||DirectAccessCheck.physical(this)?.networkHandle!=probeNetwork)throw error
                        }
                        ensureActive();ledger.record(id,delay)
                    }
                }else {
                // Probes explicitly require the real installed sing-box, independent of VPN choice.
                // This loads no native code into this service and never falls back to a base core.
                try { EngineUpdatePrimary.installation(this, "sing-box") } catch (_: Exception) {
                    errorText = "Для замера нужен установленный совместимый движок sing-box. Установи или обнови его отдельный пакет."
                    throw IOException("PROBE_SINGBOX_UNAVAILABLE")
                }
                val untuned = OptionalCoreProbe.isolatedConfig(request.getJSONObject("config"))
                val extensionSettings = request.optJSONObject("extraSettings")?.let(OptionalCoreSettings::fromJson) ?: OptionalCoreSettings()
                val transportSettings = request.optJSONObject("transportSettings")?.let(CoreTuningSettings::fromJson) ?: CoreTuningSettings()
                val extraKinds = OptionalCoreProbe.needed(untuned, extensionSettings)
                var config = if (extraKinds.isEmpty()) untuned else OptionalCoreProbe.start(this, untuned, extensionSettings,
                    transportSettings, java.util.function.BooleanSupplier { cancelled }).also { optionalProbe = it }.config
                config = CoreTuningConfig.applyTransportOnly(config, transportSettings)

                fun measure(primary: ClientPrimaryWorker, id: String, tag: String, stopped: () -> Boolean = { false }) {
                    var delay = -1L
                    try {
                        if (!cancelled && !stopped()) delay = primary.health(tag, url, 10000).takeIf { it >= 0 } ?: -1L
                    } catch (_: Exception) { diagnostic.set("urltest: closed") }
                    finally { ledger.record(id, delay) }
                }

                val ordinary = servers.filter { it.first !in carriers }
                if (ordinary.isNotEmpty()) {
                    val phaseStopped = AtomicBoolean()
                    val jobs = ArrayList<Future<*>>()
                    try {
                        withCore(config) { primary ->
                            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(12_000L * ((ordinary.size + 3) / 4))
                            ordinary.forEach { (id, tag) -> jobs.add(probes.submit { measure(primary, id, tag) { phaseStopped.get() } }) }
                            try {
                                jobs.forEach { it.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
                            } finally {
                                phaseStopped.set(true)
                                if (jobs.any { !it.isDone }) primary.requestStop()
                            }
                        }
                    } catch (error: Exception) {
                        diagnostic.set("urltest: closed")
                        // A failed cleanup must be retried before any next native run/child.
                        check(core == null) { "PROBE_PRIMARY_CLEANUP" }
                        if (jobs.isEmpty()) throw error
                    } finally {
                        phaseStopped.set(true)
                        // withCore closes the process first, waking all pending health calls.
                        // Do not release optional children while any native command is in flight.
                        jobs.forEach { try { it.get() } catch (_: Exception) { } }
                    }
                }
                // Exactly one carrier and one fresh native worker at a time; no native reload.
                for ((id, item) in carriers) measureCarrier(id,item){activeTransport->
                    val tag=item.getString("tag")
                    val profile=item.getJSONObject("profile")
                    val copied=CarrierAddressFamily.apply(ClientPrimaryProbeConfig.carrier(config,tag,activeTransport.port,activeTransport.username,activeTransport.password),EngineModules.carrierKind(profile))
                    var delay=-1L
                    withCore(copied){primary->if(!cancelled)delay=primary.health(tag,url,10000)}
                    delay
                }
                }
                completionCode = if (cancelled) 3 else 0
            } catch (_: Exception) {
                completionCode = if (cancelled) 3 else 1
            } finally {
                val primary=core
                val retiringTransport=transport
                val retiringOptional=optionalProbe
                val retiringUsage=engineUsage
                val retirement=ClientPrimaryRetirement(primary,
                    requestStop={primary?.requestStop()},
                    retireCallbacks={closePlatform(keepTun=true)},
                    closeOwner={it.close()},
                    dependents=listOf({closePlatform()},{retiringTransport?.close();Unit},
                        {retiringOptional?.close();Unit},{retiringUsage?.close();Unit}),
                    completed={
                        if(core===primary)core=null
                        transport=null;optionalProbe=null;engineUsage=null
                        cleanupComplete=true
                    })
                // Never return from this scope with an unadopted local carrier,
                // optional worker or usage lease after a failed close attempt.
                var interrupted=Thread.interrupted()
                try {
                    while(!retirement.attempt()) {
                        try{Thread.sleep(retirement.retryDelayMillis())}catch(_:InterruptedException){interrupted=true}
                    }
                }finally{if(interrupted)Thread.currentThread().interrupt()}
                if (!cleanupComplete) {
                    completionCode = 1
                    errorText = "Замер ещё останавливается. Повторите подключение после его завершения."
                } else if (cancelled) completionCode = 3
                val values = results?.finish().orEmpty()
                val delays = Bundle().apply { values.forEach { (id, delay) -> putLong(id, delay) } }
                val completion = Bundle().apply {
                    putString("backend",backend);if(backend=="steer"){putString("moduleSha256",moduleSha);putLong("networkHandle",probeNetwork)}
                    if(backend=="steer")putBundle("completedAt",Bundle().apply {completedAt.forEach {(id,at)->putLong(id,at)}})
                    putBundle("delays", delays)
                    if (completionCode != 0) putString("error", if (completionCode == 3)
                        "Замер отменён. Предыдущие результаты сохранены." else errorText)
                    if (BuildConfig.DEBUG) { putString("diagnostic", diagnostic.get()); putString("nativeStage", nativeStage) }
                    if(BuildConfig.DEBUG&&providerDiagnostic)putString("providerStage",providerStage.get())
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (comparisonId == null) try { receiver?.send(completionCode, completion) } catch (_: Exception) { }
                cancelled = true
                comparisonTimer?.cancel(false)
                comparisonPathWatcher?.cancel(false)
                running.set(false)
                lifetime.finish(run, cleanupComplete, values)
                if (comparisonId != null) {
                    val notify = { try { receiver?.send(completionCode, completion); Unit } catch (_: Exception) { } }
                    val deliverNow = synchronized(comparisonCompletionLock) {
                        if (comparisonDestroyed) true else { comparisonCompletion = notify; false }
                    }
                    if (deliverNow) notify()
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureActive() { if (cancelled || comparisonRetired()) throw CancellationException("PROBE_CANCELLED") }

    private fun withSteerCore(profile:String,host:String,url:String,network:Long,protectSockets:Boolean):Long {
        ensureActive();check(core==null) {"STEER_PROBE_PRIMARY_STILL_RUNNING"}
        val runGeneration=generation.incrementAndGet()
        var physicalPinned=false
        val platform=forRun(runGeneration,{cancelled||physicalPinned&&underlying?.networkHandle!=network},
            {throw IOException("STEER_PROBE_TUN_DISABLED")},{_,_->},protectSockets)
        try {
            require(underlying?.networkHandle==network) {"STEER_PROBE_NETWORK_CHANGED"}
            physicalPinned=true
            fun resolve(name:String,v4:Boolean):String {
                RouteRules.literal(name)?.let {literal->
                    require(!v4||literal is java.net.Inet4Address) {"STEER_PROBE_IPV4_DIAL"}
                    return requireNotNull(literal.hostAddress)
                }
                for(type in if(v4)listOf(1)else listOf(1,28)) {
                    ensureActive()
                    val query=PacketDnsWire.query(name.trimEnd('.'),type,java.security.SecureRandom().nextInt(65536))
                    val answer=platform.exchangeDns(runGeneration,org.mcrf.engine.spi.v1.Platform.DnsQuery(
                        generation.incrementAndGet(),query,5000))
                    if(answer.errno==0&&answer.rcode==0)PacketDnsWire.addresses(query,answer.packet()).firstOrNull()?.hostAddress?.let {return it}
                }
                throw IOException("STEER_PROBE_DNS_UNAVAILABLE")
            }
            val target=SteerProbeSpec(url,"192.0.2.1")
            val dial=resolve(host,true);val spec=SteerProbeSpec(url,resolve(target.host,false))
            val primary=try {ClientPrimaryWorker.connect(this,"steer",runGeneration,platform,ClientPrimaryProbeWorkerService::class.java)}
                catch(pending:ClientPrimaryWorker.CleanupPending){core=pending.worker;throw pending}
            core=primary;ensureActive()
            return primary.probeSteer(profile,spec,dial)
        }finally {
            check(closePrimary()) {"STEER_PROBE_PRIMARY_CLEANUP"}
            closePlatform()
        }
    }
    private fun <T> withCore(config: JSONObject, action: (ClientPrimaryWorker) -> T): T {
        ensureActive()
        check(core == null) { "PROBE_PRIMARY_STILL_RUNNING" }
        val runGeneration = generation.incrementAndGet()
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val settings = ClientPrimarySelection.envelope(ClientPrimaryProbeConfig.isolated(config, port), false, false)
        val platform = forRun(runGeneration, { cancelled || comparisonRetired() }, { throw IOException("PROBE_TUN_DISABLED") }, { _, _ -> })
        try {
            val primary = try {
                ClientPrimaryWorker.connect(this, "singbox", runGeneration, platform, ClientPrimaryProbeWorkerService::class.java)
            } catch (pending: ClientPrimaryWorker.CleanupPending) {
                core = pending.worker
                throw pending
            }
            core = primary
            if (cancelled) primary.requestStop()
            ensureActive()
            require(primary.validate("", settings, Engine.Mode.PROXY).supported) { "PROBE_NATIVE_CONFIG_REJECTED" }
            ensureActive()
            primary.start("", settings, Engine.Mode.PROXY)
            ensureActive()
            return action(primary)
        } finally {
            check(closePrimary()) { "PROBE_PRIMARY_CLEANUP" }
            closePlatform()
        }
    }

    private fun closePrimary(): Boolean {
        val primary = core ?: return true
        return try {
            primary.close() // idempotent, synchronized and awaits actual worker process death
            if (core === primary) core = null
            true
        } catch (_: Exception) { false }
    }

    override fun onDestroy() {
        cancelProbe()
        // Existing jobs must reach their finally blocks; do not interrupt native child cleanup.
        probes.shutdown()
        diagnosticDeadlines.shutdownNow()
        worker.shutdown()
        super.onDestroy()
        val notify = synchronized(comparisonCompletionLock) {
            comparisonDestroyed = true; comparisonCompletion.also { comparisonCompletion = null }
        }
        notify?.invoke()
    }

    companion object {
        const val QUIESCE = "org.mcrf.client.PROBE_QUIESCE"
        private val lifetime = ClientPrimaryProbeLifecycle()

        /** Beta A/B capability: one disposable sing-box PROXY diagnostic. No active
         * TUN isolation is claimed by that provider; refuse instead of switching it.
         * Fixed 204 endpoint, one node/call, existing finite native URLTest budget.
         * Ordinary failures are samples; path/provider/ownership changes abort the run. */
        internal fun transportComparisonIdentity(context: android.content.Context): String {
            require(ClientPrimarySelection.selected(context) == "singbox") { "TRANSPORT_COMPARISON_PROVIDER" }
            val network = DirectAccessCheck.physical(context)?.networkHandle ?: error("TRANSPORT_COMPARISON_NETWORK")
            val installed = EngineUpdatePrimary.installation(context, "sing-box")
            return "$network:${ClientPrimaryWorkerWire.hashFile(File(installed.sourceDir))}"
        }
        internal fun transportComparisonDiagnostic(context: android.content.Context, profile: ServerImport.Server,
                                                   expectedCancellation: Long, expectedIdentity: String): Long? {
            require(TransportComparisonPlan.supported(profile)) { "TRANSPORT_COMPARISON_UNSUPPORTED_PROFILE" }
            val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
            val physical = DirectAccessCheck.physical(context)?.networkHandle ?: error("Нет физической сети.")
            fun admitted() {
                require(ClientPrimarySelection.selected(context) == "singbox") { "A/B: установленный провайдер не поддерживает этот изолированный замер." }
                require(DirectAccessCheck.physical(context)?.networkHandle == physical && !McrfVpnService.hasActiveSession() &&
                    !McrfVpnService.cleanupPending() && manager.allNetworks.none {
                        manager.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                    }) { "A/B: изоляция при активном VPN или изменении сети недоступна. Соединение не переключалось." }
                require(!SafeModeStore(context).enabled() && !CaptivePortalMonitor.blocksAutomatic(context)) { "A/B: безопасный режим или сеть не готова." }
                require(ProbeCoordinator.cancellationToken() == expectedCancellation) { "TRANSPORT_COMPARISON_RETIRED" }
                require(transportComparisonIdentity(context) == expectedIdentity) { "TRANSPORT_COMPARISON_IDENTITY_CHANGED" }
                require(!EngineUpdates.installationPending(context) && !EngineUpdateScripts.mutationPending()) { "TRANSPORT_COMPARISON_UPDATE" }
            }
            admitted()
            require(ProbeCoordinator.measurements == null) { "Другая диагностика уже выполняется." }
            val generated = ServerImport.build(listOf(profile), profile.id, false, 60, 50, "Cloudflare")
            val extensions = OptionalCoreStore(context).load()
            val tuning = CoreTuningStore(context).load()
            require(!OptionalCorePlan(generated, extensions, tuning).requiresWorkers) { "A/B: профиль требует дополнительного диагностического провайдера." }
            // Use the existing provider service directly. Its lifetime.begin rejects
            // contention immediately, avoiding the general coordinator's blocking queue.
            val runId = java.util.UUID.randomUUID().toString()
            val deadline = SystemClock.elapsedRealtime() + 25_000
            val port = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
            val config = ProfileConfig.build(generated.toString(), false, port).put("inbounds", org.json.JSONArray())
                .put("log", JSONObject().put("disabled", true))
            val request = JSONObject().put("config", config).put("extraSettings", extensions.json())
                .put("transportSettings", tuning.json()).put("carriers", org.json.JSONArray())
                .put("url", TransportComparisonPlan.ENDPOINT).put("transportComparisonRun", runId).put("transportComparisonDeadline", deadline)
                .put("transportComparisonNetwork", physical).put("transportComparisonIdentity", expectedIdentity)
                .put("servers", org.json.JSONArray().put(JSONObject().put("id", profile.id).put("tag", ServerImport.tag(profile))))
            val file = File(context.cacheDir, "mcrf-probe-input-$runId.json")
            val latch = java.util.concurrent.CountDownLatch(1)
            val result = AtomicReference<Long?>(); val completed = AtomicBoolean()
            val replies = ProbeReplyWindow()
            var ownsInput = false
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(code: Int, data: Bundle?) { replies.deliver {
                    if (code != 2) {
                        val delays = data?.getBundle("delays")
                        if (code == 0 && delays != null && delays.keySet() == setOf(profile.id)) {
                            @Suppress("DEPRECATION") val delay = delays.get(profile.id)
                            if (delay is Long && delay in -1..60_000) { result.set(delay.takeIf { it >= 0 }); completed.set(true) }
                        }
                        latch.countDown()
                    }
                } }
            }
            try {
                val bytes = request.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= ServerImport.MAX_BYTES)
                check(file.createNewFile()); ownsInput = true
                android.system.Os.chmod(file.absolutePath, 0x180); file.writeBytes(bytes)
                admitted()
                context.startForegroundService(Intent(context, ProbeService::class.java).putExtra("input", file.absolutePath)
                    .putExtra("result", receiver).putExtra("transportComparisonRun", runId).putExtra("transportComparisonDeadline", deadline))
                require(latch.await(30, TimeUnit.SECONDS) && completed.get()) { "TRANSPORT_COMPARISON_PROVIDER_UNAVAILABLE" }
                admitted()
                return result.get()
            } finally {
                replies.close(); if (ownsInput) file.delete()
                // A late worker drains under its own deadline/lifetime. No global STOP.
            }
        }

        /** Local Beta helper, NOT a service command: never instantiates a core or starts VPN.
         * Async Android DNS has a cancellation signal and a hard wait deadline. */
        internal fun sampleHealth(context: android.content.Context, host: String, port: Int,
                                  network: android.net.Network, cancellation: HealthCancellation): ProtocolHealthCheck.TcpSample {
            require(host.length in 1..253 && host.matches(Regex("[A-Za-z0-9._:-]+")) && port in 1..65535)
            val addresses = AtomicReference<List<InetAddress>>(emptyList())
            val dnsDone = java.util.concurrent.CountDownLatch(1)
            val signal = CancellationSignal()
            val ownership = cancellation.attach { signal.cancel(); dnsDone.countDown() }
            try {
                cancellation.check()
                val literal = RouteRules.literal(host)
                if (literal != null) { addresses.set(listOf(literal)); dnsDone.countDown() }
                else android.net.DnsResolver.getInstance().query(network, host, android.net.DnsResolver.FLAG_EMPTY,
                    java.util.concurrent.Executor { it.run() }, signal,
                    object : android.net.DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            if (rcode == 0) addresses.set(answer.take(8))
                            dnsDone.countDown()
                        }
                        override fun onError(error: android.net.DnsResolver.DnsException) { dnsDone.countDown() }
                    })
                if (!dnsDone.await(HealthSettings.TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
                    throw IOException("HEALTH_DNS_TIMEOUT")
                cancellation.check()
                require(DirectAccessCheck.physical(context)?.networkHandle == network.networkHandle) { "HEALTH_NETWORK_CHANGED" }
                val address = addresses.get().firstOrNull() ?: throw IOException("HEALTH_DNS_UNAVAILABLE")
                return ProtocolHealthCheck.sample(cancellation) {
                    cancellation.check()
                    if (DirectAccessCheck.physical(context)?.networkHandle != network.networkHandle)
                        throw java.util.concurrent.CancellationException("HEALTH_NETWORK_CHANGED")
                    ProtocolHealthCheck.tcpPing(java.net.InetSocketAddress(address, port), cancellation) { network.bindSocket(it) }
                }
            } finally { signal.cancel(); ownership.close() }
        }
    }
}

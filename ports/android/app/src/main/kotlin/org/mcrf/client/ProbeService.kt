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
    @Volatile private var cancelled = false
    @Volatile private var core: ClientPrimaryWorker? = null
    private val generation = AtomicLong(System.nanoTime().coerceAtLeast(1))
    private val running = AtomicBoolean(false)

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
            try {
                val file = File(input ?: throw IOException("PROBE_INPUT"))
                require(file.canonicalFile.parentFile == cacheDir.canonicalFile &&
                    file.name.matches(Regex("mcrf-probe-input-[0-9a-f-]{36}\\.json"))) { "PROBE_INPUT_PATH" }
                val request = file.inputStream().use { JSONObject(String(ImportIO.read(it), Charsets.UTF_8)) }
                val array = request.getJSONArray("servers")
                require(array.length() in 1..256) { "PROBE_SERVER_COUNT" }
                val servers = (0 until array.length()).map {
                    val item = array.getJSONObject(it)
                    item.getString("id") to item.getString("tag").also { tag -> require(tag.isNotEmpty() && tag.length <= 8192) }
                }
                val ledger = ClientPrimaryProbeResults(servers.map { it.first }) { count, id, delay ->
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
                for ((id, item) in carriers) {
                    try {
                        ensureActive()
                        val profile = JSONObject(item.getJSONObject("profile").toString()).put("probe_only", true)
                        val activeTransport = NativeCarrier.start(this, profile, java.util.function.BooleanSupplier { cancelled },60000,providerDiagnostic,providerVersion) {events->
                            providerStage.set(if(events==null)JSONObject().put("state","not_reported").toString()
                                else JSONObject().put("state","reported").put("events",JSONObject(events)).toString())
                        }
                        transport = activeTransport
                        ensureActive()
                        val tag = item.getString("tag")
                        val copied = CarrierAddressFamily.apply(ClientPrimaryProbeConfig.carrier(config, tag, activeTransport.port, activeTransport.username, activeTransport.password),EngineModules.carrierKind(profile))
                        withCore(copied) { primary -> measure(primary, id, tag) }
                    } catch (error: Exception) {
                        diagnostic.set("carrier/core: other")
                        nativeStage = EngineUpdateOpenFluxDiagnostics.nativeFailure(error)
                        ledger.record(id, -1)
                        check(core == null) { "PROBE_PRIMARY_CLEANUP" }
                    } finally {
                        // Keep the bridge alive until its primary worker's confirmed death.
                        if (core == null) { transport?.close(); transport = null }
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
                    putBundle("delays", delays)
                    if (completionCode != 0) putString("error", if (completionCode == 3)
                        "Замер отменён. Предыдущие результаты сохранены." else errorText)
                    if (BuildConfig.DEBUG) { putString("diagnostic", diagnostic.get()); putString("nativeStage", nativeStage) }
                    if(BuildConfig.DEBUG&&providerDiagnostic)putString("providerStage",providerStage.get())
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                try { receiver?.send(completionCode, completion) } catch (_: Exception) { }
                cancelled = true
                running.set(false)
                lifetime.finish(run, cleanupComplete, values)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureActive() { if (cancelled) throw CancellationException("PROBE_CANCELLED") }

    private fun <T> withCore(config: JSONObject, action: (ClientPrimaryWorker) -> T): T {
        ensureActive()
        check(core == null) { "PROBE_PRIMARY_STILL_RUNNING" }
        val runGeneration = generation.incrementAndGet()
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val settings = ClientPrimarySelection.envelope(ClientPrimaryProbeConfig.isolated(config, port), false, false)
        val platform = forRun(runGeneration, { cancelled }, { throw IOException("PROBE_TUN_DISABLED") }, { _, _ -> })
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
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val QUIESCE = "org.mcrf.client.PROBE_QUIESCE"
        private val lifetime = ClientPrimaryProbeLifecycle()
    }
}

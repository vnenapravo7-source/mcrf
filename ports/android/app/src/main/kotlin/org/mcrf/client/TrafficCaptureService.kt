package org.mcrf.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.PowerManager
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.engine.spi.v1.Platform
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Owns a TUN only in ONLY mode. CONNECTED mode is a metadata subscriber, never another VPN. */
class TrafficCaptureService : ClientPrimaryHostPlatform() {
    private val lifecycle = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lost = AtomicLong()
    private val events = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "mcrf-capture-metadata").apply { isDaemon = true } },
        { _, _ -> lost.incrementAndGet() })
    @Volatile private var ending = false
    @Volatile private var destroyed = false
    @Volatile private var options: TrafficCapturePolicy.Options? = null
    @Volatile private var core: ClientPrimaryWorker? = null
    @Volatile private var recorder: TrafficCaptureRecorder? = null
    @Volatile private var ready = false
    private val generation = NEXT.incrementAndGet()
    private var begun = 0L
    private var cleanupAt = 0L
    private var sawServer = false
    private var activationQueued = false
    private var deleteRequested = false
    private var storageRetired = false
    private var wake: PowerManager.WakeLock? = null
    private var wakeAt = 0L
    private var admitted = false
    private var finishMessage = "Запись остановлена"
    private val tick = object : Runnable {
        override fun run() {
            if (destroyed || ending) return
            try {
                if (SafeModeStore(this@TrafficCaptureService).enabled()) { finish("Запись остановлена безопасным режимом"); return }
                if (foreignVpn(this@TrafficCaptureService) || VpnService.prepare(this@TrafficCaptureService) != null) {
                    finish("VPN отозван или заменён; запись остановлена"); return
                }
                val value = options ?: return
                if (value.mode == TrafficCapturePolicy.Mode.CONNECTED) {
                    val active = McrfVpnService.ownsCurrentTun() && ConnectionPresentation.connected(McrfVpnService.status)
                    if (active && !ready && !activationQueued) {
                        sawServer = true
                        activationQueued = true
                        lifecycle.execute { try { activate() } catch (_: Exception) { finish("Не удалось открыть хранилище записи") } }
                    } else if (sawServer && !McrfVpnService.hasActiveSession()) {
                        finish("MCRF VPN завершён; запись остановлена"); return
                    } else if (!ready && SystemClock.elapsedRealtime() - begun > 180_000) {
                        finish("MCRF VPN не запущен; запись не начата"); return
                    }
                }
                val recording = recorder
                if (ready && (recording?.current() == null || recording.remaining() == 0L)) {
                    finish("Таймер завершён; запись остановлена"); return
                }
                if (SystemClock.elapsedRealtime() - cleanupAt >= 60_000) {
                    cleanupAt = SystemClock.elapsedRealtime()
                    events.execute { try { TrafficJournalStore.local(this@TrafficCaptureService).entries() }
                        catch (_: Exception) { finish("Ошибка зашифрованного хранилища; запись остановлена") } }
                }
                if (ready) {
                    renewWake()
                    publish(if (value.mode == TrafficCapturePolicy.Mode.ONLY)
                        if (underlying == null) "Запись · Direct · ожидаем физическую сеть" else "Запись · Direct · без серверного туннеля"
                    else if (!ConnectionPresentation.connected(McrfVpnService.status)) "Запись ожидает восстановления MCRF VPN/БС"
                    else "Запись внутри MCRF VPN/БС · охват текущего VPN")
                }
            } catch (_: Exception) { finish("Ошибка записи; сессия остановлена") }
            if (!ending) main.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        admitted = synchronized(SLOT) { if(instance == null) { instance = this; true } else false }
        if (!admitted) { stopSelf(); return }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Запись метаданных трафика", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!admitted) return START_NOT_STICKY
        if (intent?.action == STOP) { finish("Запись остановлена вручную"); return START_NOT_STICKY }
        if (intent?.action == DELETE) { finish("Запись остановлена; данные удалены", delete = true); return START_NOT_STICKY }
        if (intent?.action != START || options != null || ending) { if (options == null) stopSelf(); return START_NOT_STICKY }
        try {
            val value = decode(intent)
            options = value
            publish("Подготовка записи…") // FGS before off-main native verification
            begun = SystemClock.elapsedRealtime()
            lifecycle.execute { start(value) }
        } catch (_: Exception) { finish("Не удалось начать запись") }
        return START_NOT_STICKY // no automatic recording/consent replay after process death
    }

    private fun start(value: TrafficCapturePolicy.Options) {
        try {
            check(!ending && !SafeModeStore(this).enabled())
            val gate = TrafficCapturePolicy.admit(value.mode, foreignVpn(this), McrfVpnService.hasActiveSession(),
                McrfVpnService.ownsCurrentTun(), false, McrfVpnService.cleanupPending())
            check(gate in setOf(TrafficCapturePolicy.Admission.DIRECT, TrafficCapturePolicy.Admission.ATTACH,
                TrafficCapturePolicy.Admission.CONNECT)) { "CAPTURE_VPN_CONFLICT" }
            check(VpnService.prepare(this) == null) { "CAPTURE_CONSENT_REQUIRED" }
            if (value.mode == TrafficCapturePolicy.Mode.ONLY) {
                // Verify installed same-signer sing-box. No installation/download/native build.
                EngineUpdatePrimary.installation(this, "sing-box")
                check(!ending)
                vpnMode = true
                val platform = forRun(generation, { ending || destroyed }, { plan -> establishCapture(plan, value) }) { _, _ -> }
                val worker = ClientPrimaryWorker.connect(this, "singbox", generation, platform, onDeath = {
                    if (!ending) finish("Движок записи завершился; локальный VPN освобождается")
                })
                core = worker
                check(!ending)
                val config = TrafficCaptureConfig.direct()
                val envelope = ClientPrimaryCoreConfig.envelope(config, true, false)
                check(worker.validate("", envelope, Engine.Mode.VPN).supported)
                check(!ending)
                worker.start("", envelope, Engine.Mode.VPN)
                check(tunnel != null && !ending)
                activate()
            } else if (gate == TrafficCapturePolicy.Admission.CONNECT) {
                check(!ending && !foreignVpn(this))
                val prefs = getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                // Existing ordinary lifecycle reads the user's saved profile/BS selection.
                startForegroundService(Intent(this, McrfVpnService::class.java).setAction(McrfVpnService.START)
                    .putExtra("tun", true).putExtra("port", prefs.getInt("port", 10808))
                    .putExtra("applyRoutes", prefs.getBoolean("applyRoutes", true)))
            }
            main.post(tick)
        } catch (pending: ClientPrimaryWorker.CleanupPending) {
            core = pending.worker
            finish("Запуск отменён; ожидаем освобождение worker")
        } catch (_: Exception) {
            finish(if (foreignVpn(this)) CONFLICT else "Запись не запущена: проверьте разрешение VPN, профиль и установленный sing-box")
        }
    }

    private fun activate() {
        synchronized(SLOT) {
            if (ready || ending || destroyed) return
            val value = requireNotNull(options)
            val next = TrafficCaptureRecorder(TrafficJournalStore.local(this), SystemClock::elapsedRealtime, System::currentTimeMillis)
            next.start(value)
            recorder = next
            ready = true
        }
        renewWake()
    }
    private fun renewWake() = synchronized(SLOT) {
        if (ending || destroyed) return@synchronized
        val now = SystemClock.elapsedRealtime()
        if (now - wakeAt < 60_000 && wake?.isHeld == true) return@synchronized
        val lease = wake ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "MCRF:TrafficCapture").apply { setReferenceCounted(false) }.also { wake = it }
        lease.acquire(120_000) // short renewable lease; crashes cannot leave an unlimited CPU lock
        wakeAt = now
    }

    private fun establishCapture(plan: Platform.TunPlan, value: TrafficCapturePolicy.Options): ParcelFileDescriptor = synchronized(SLOT) {
        check(!ending && !destroyed && !McrfVpnService.hasActiveSession() && !McrfVpnService.cleanupPending() && !foreignVpn(this))
        check(VpnService.prepare(this) == null)
        val builder = Builder().setSession("MCRF · Только запись · Direct").setMtu(plan.mtu)
            .setBlocking(false).setMetered(false)
        (plan.routes.inet4Address + plan.routes.inet6Address).forEach { address ->
            builder.addAddress(address.substringBefore('/'), address.substringAfter('/').toInt())
        }
        builder.addRoute("0.0.0.0", 0).addRoute("::", 0)
        plan.dnsServers.forEach(builder::addDnsServer)
        if (value.selectedOnly) value.selectedApps.forEach(builder::addAllowedApplication)
        underlying?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        builder.establish() ?: error("CAPTURE_TUN_DENIED")
    }

    override fun onConnectionObserved(generation: Long, query: Platform.ConnectionQuery, owner: Platform.ConnectionOwner?) {
        if (generation == this.generation) connection(query, owner, false, generation)
    }
    override fun onDnsObserved(generation: Long, uid: Int, domain: String, ip: String, ttlMs: Long) {
        if (generation == this.generation) dns(uid, domain, ip, ttlMs, false, generation)
    }
    override fun onDnsExchangeObserved(generation: Long, query: ByteArray, reply: ByteArray) {
        if (generation == this.generation) dnsExchange(query, reply, false, generation)
    }

    private fun sourceLive(server: Boolean, sourceGeneration: Long): Boolean = !ending && !destroyed && ready &&
        (options?.mode == TrafficCapturePolicy.Mode.CONNECTED) == server &&
        (if (server) McrfVpnService.captureGenerationLive(sourceGeneration) else sourceGeneration == generation)

    private fun connection(query: Platform.ConnectionQuery, owner: Platform.ConnectionOwner?, server: Boolean, sourceGeneration: Long,
                           attribution: TrafficDomainAttribution.Match? = null, bytes: Long? = null) {
        if (!sourceLive(server, sourceGeneration)) return
        val session = recorder?.current() ?: return
        if (options?.selectedOnly == true && owner == null) { lost.incrementAndGet(); return }
        // DTOs contain numeric endpoint metadata only; no packet bytes enter this queue.
        val observation = TrafficJournalStore.Observation(System.currentTimeMillis(), query.destination,
            query.destinationPort.takeIf { it > 0 }, owner?.uid, owner?.packageNames?.singleOrNull(),
            when (query.protocol) { 6 -> TrafficJournalStore.Protocol.TCP; 17 -> TrafficJournalStore.Protocol.UDP
                1, 58 -> TrafficJournalStore.Protocol.ICMP; else -> TrafficJournalStore.Protocol.OTHER },
            if (server) TrafficJournalStore.Route.UNKNOWN else TrafficJournalStore.Route.DIRECT,
            domain = attribution?.domain,
            packages = owner?.packageNames?.take(64).orEmpty(), bytes = bytes,
            domainSource = attribution?.source ?: TrafficJournalStore.DomainSource.METADATA)
        events.execute {
            if (sourceLive(server, sourceGeneration)) try { recorder?.record(session, owner?.packageNames, observation) }
            catch (_: Exception) { finish("Ошибка хранилища; запись остановлена") }
        }
    }
    /** Optional transport adapter boundary, not an instruction to read from a socket/TUN.
     * Current numeric-only SPI cannot call it; native integration is deliberately not fabricated.
     * Adapter must invoke once per new TCP flow instead of its numeric connection callback,
     * with the existing generation and an already-observed first-flight prefix, max 4 KiB.
     * Only the parsed hostname/byte count enters the bounded queue; bytes are never retained. */
    private fun initialMetadata(query: Platform.ConnectionQuery, owner: Platform.ConnectionOwner?, prefix: ByteArray,
                                bytes: Long?, server: Boolean, sourceGeneration: Long) {
        if (!sourceLive(server, sourceGeneration) || query.protocol != 6 ||
            bytes != null && bytes < 0 || options?.selectedOnly == true && owner == null) return
        val domain = if (prefix.size <= TrafficDomainAttribution.MAX_PREFIX) TrafficDomainAttribution.fromPrefix(prefix) else null
        connection(query, owner, server, sourceGeneration, domain, bytes)
    }
    private fun dns(uid: Int, domain: String, ip: String, ttlMs: Long, server: Boolean, sourceGeneration: Long) {
        if (!sourceLive(server, sourceGeneration)) return
        val session = recorder?.current() ?: return
        val packages = packageManager.getPackagesForUid(uid)?.toList()
        events.execute { if (sourceLive(server, sourceGeneration)) try { recorder?.dns(session, ip, domain, uid, packages, ttlMs) }
            catch (_: Exception) { finish("Ошибка хранилища; запись остановлена") } }
    }
    private fun dnsExchange(query: ByteArray, reply: ByteArray, server: Boolean, sourceGeneration: Long) {
        if (!sourceLive(server, sourceGeneration) || options?.selectedOnly == true) return
        val session = recorder?.current() ?: return
        // Parse synchronously; only bounded validated DNS metadata, never packet bytes, are queued.
        val addresses = runCatching { PacketDnsWire.observedAddresses(query, reply).take(64) }.getOrDefault(emptyList())
        addresses.forEach { address -> events.execute {
            if (sourceLive(server, sourceGeneration)) try { recorder?.record(session, null,
                TrafficJournalStore.Observation(System.currentTimeMillis(), address.ip, domain = address.domain, event = "dns-answer")) }
            catch (_: Exception) { finish("Ошибка хранилища; запись остановлена") }
        } }
    }

    private fun publish(message: String) {
        if (ending || destroyed) return
        val remaining = recorder?.remaining()?.let { " · ещё ${(it + 999) / 1000} сек" }.orEmpty()
        status = message + remaining + if (lost.get() > 0) " · пропущено: ${lost.get()}" else ""
        val open = PendingIntent.getActivity(this, 30, Intent(this, TrafficCaptureActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 31, Intent(this, TrafficCaptureService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(options?.mode?.label ?: "Запись трафика").setContentText(status)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET).addAction(Notification.Action.Builder(null, "Остановить запись", stop).build()).build()
        startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun finish(message: String, delete: Boolean = false) {
        synchronized(SLOT) {
            if (delete) deleteRequested = true
            if (ending) {
                return
            }
            ending = true
            finishMessage = message
        }
        main.removeCallbacks(tick)
        synchronized(SLOT) { wake?.let { if (it.isHeld) it.release() }; wake = null }
        core?.requestStop()
        // A dedicated control lane cannot be dropped by the bounded metadata queue.
        // sourceLive is already false; synchronized recorder fences any in-progress disk write.
        lifecycle.execute { retireStorage(); retireTransport() }
    }
    private fun retireStorage() {
        try {
            if (deleteRequested) {
                recorder?.delete() ?: TrafficJournalStore.local(this).delete()
                deleteRequested = false; storageRetired = true
            } else if (!storageRetired) {
                recorder?.stop() ?: TrafficJournalStore.local(this).setEnabled(false)
                storageRetired = true
            }
        } catch (_: Exception) { finishMessage = "Запись остановлена; операция с хранилищем не выполнена" }
    }
    private fun retireTransport() {
        try {
            core?.close() // real worker-death barrier before closing original TUN
            core = null
            closePlatform()
            synchronized(SLOT) {
                retireStorage() // also consumes Delete arriving during the worker-death barrier
                if (instance === this) instance = null
            }
            status = finishMessage
            events.shutdown()
            lifecycle.shutdown()
            main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        } catch (_: Exception) {
            status = "Запись остановлена; ожидаем завершения worker"
            main.postDelayed({ if (!lifecycle.isShutdown) lifecycle.execute { retireTransport() } }, 1000)
        }
    }
    override fun onRevoke() { finish("Разрешение VPN отозвано; запись остановлена"); super.onRevoke() }
    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(tick)
        if (admitted && !ending) finish("Служба записи завершена")
        if (!admitted) lifecycle.shutdown()
        // Cleanup is already queued and must drain. Never interrupt worker retirement/storage writes.
        events.shutdown()
        super.onDestroy()
    }

    companion object {
        private val SLOT = Any()
        private val NEXT = AtomicLong(SystemClock.elapsedRealtime().coerceAtLeast(1))
        @Volatile private var instance: TrafficCaptureService? = null
        @Volatile var status = "Запись выключена"
            private set
        const val START = "org.mcrf.client.CAPTURE_START"
        const val STOP = "org.mcrf.client.CAPTURE_STOP"
        const val DELETE = "org.mcrf.client.CAPTURE_DELETE"
        const val CONFLICT = "Другой VPN занимает единственный Android VPN slot. Отключите его вручную в настройках VPN и повторите запуск."
        private const val CHANNEL = "traffic-capture"
        private const val NOTIFICATION = 3701
        fun busy() = instance != null
        fun blocksServerStart() = instance?.options?.mode == TrafficCapturePolicy.Mode.ONLY
        fun wantsServerMetadata() = instance?.let { !it.ending && it.options?.mode == TrafficCapturePolicy.Mode.CONNECTED } == true
        fun foreignVpn(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val vpns = manager.allNetworks.mapNotNull { manager.getNetworkCapabilities(it) }
                .filter { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            if (vpns.isEmpty()) return false
            if (android.os.Build.VERSION.SDK_INT >= 30) return vpns.any { it.ownerUid != android.os.Process.myUid() }
            return VpnService.prepare(context) != null || !McrfVpnService.ownsCurrentTun() && instance?.tunnel == null
        }
        fun request(context: Context, value: TrafficCapturePolicy.Options) = Intent(context, TrafficCaptureService::class.java).setAction(START)
            .putExtra("mode", value.mode.name).putExtra("seconds", value.seconds).putExtra("selectedOnly", value.selectedOnly)
            .putStringArrayListExtra("apps", ArrayList(value.selectedApps))
        private fun decode(intent: Intent) = TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.valueOf(requireNotNull(intent.getStringExtra("mode"))),
            intent.getStringArrayListExtra("apps")?.toSet().orEmpty(), intent.getLongExtra("seconds", 0), intent.getBooleanExtra("selectedOnly", false))
        fun serverConnection(query: Platform.ConnectionQuery, owner: Platform.ConnectionOwner?, generation: Long) { instance?.connection(query, owner, true, generation) }
        fun serverDns(uid: Int, domain: String, ip: String, ttl: Long, generation: Long) { instance?.dns(uid, domain, ip, ttl, true, generation) }
        fun serverDnsExchange(query: ByteArray, reply: ByteArray, generation: Long) { instance?.dnsExchange(query, reply, true, generation) }
        fun serverInitialMetadata(query: Platform.ConnectionQuery, owner: Platform.ConnectionOwner?, prefix: ByteArray,
                                  bytes: Long?, generation: Long) { instance?.initialMetadata(query, owner, prefix, bytes, true, generation) }
    }
}

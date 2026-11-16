package org.mcrf.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class DegradationSettings(val enabled: Boolean = false, val targetIds: Set<String> = setOf("connection"),
    val intervalSeconds: Int = 60, val factor: Double = 2.0, val minimumIncreaseMs: Long = 150,
    val repeated: Int = 3, val cooldownMs: Long = 900_000, val window: Int = 12, val minimumBaseline: Int = 5) {
    init {
        require(targetIds.size in 1..4 && targetIds.all { it=="connection"||ServiceTestCatalog.find(it)?.check == DiagnosticCheck.HTTPS })
        require(intervalSeconds in 60..900 && factor in 1.5..10.0 && minimumIncreaseMs in 50..10_000)
        require(repeated in 2..10 && cooldownMs in 60_000..86_400_000 && window in 5..60 && minimumBaseline in 3..window)
    }
}
data class DegradationRecommendation(val targetId: String, val baselineMs: Long, val observedMs: Long?, val failures: Int,
    val directResponded: Boolean, val fallbackConfigured: Boolean)
data class DegradationSnapshot(val enabled: Boolean, val baselineSamples: Int = 0, val completedRounds: Int = 0,
    val last: DegradationRecommendation? = null, val issue: DiagnosticIssue = DiagnosticIssue.NONE,val server:String="")

/** Require a majority across independent origins; one unavailable site cannot condemn a server. */
internal object ConnectionDegradationEvidence {
    val probes=setOf("google-site","cloudflare-site","microsoft-store-site")
    fun aggregate(results:List<DiagnosticResult>,path:DiagnosticPath):DiagnosticResult {
        val rows=results.filter {it.path==path&&it.targetId in probes}.distinctBy {it.targetId}
        val good=rows.filter {it.state==DiagnosticState.AVAILABLE&&it.elapsedMs!=null}
        val failures=rows.count {it.state==DiagnosticState.ERROR&&it.issue in setOf(DiagnosticIssue.TIMEOUT,DiagnosticIssue.CONNECT_FAILED,DiagnosticIssue.TLS_FAILED,DiagnosticIssue.DNS_FAILED)}
        return when {
            good.size>=2->DiagnosticResult("connection",path,DiagnosticFamily.AUTOMATIC,DiagnosticState.AVAILABLE,
                DiagnosticIssue.NONE,good.mapNotNull {it.elapsedMs}.sorted().let {it[(it.size-1)/2]})
            failures>=2->DiagnosticResult("connection",path,DiagnosticFamily.AUTOMATIC,DiagnosticState.ERROR,DiagnosticIssue.CONNECT_FAILED,null)
            else->DiagnosticResult("connection",path,DiagnosticFamily.AUTOMATIC,DiagnosticState.UNAVAILABLE,DiagnosticIssue.CAPACITY,null)
        }
    }
}

/** Stable successful samples roll; anomalous samples don't train away a fault.
 * Unmeasured/HTTP policy results break consecutive evidence. No automatic action. */
class DiagnosticsDegradationPolicy(private val settings: DegradationSettings) {
    private class Track {
        val values = ArrayDeque<Long>()
        var bad = 0
        var lastAt: Long? = null
        var lastGoodAt: Long? = null
        var emittedAt: Long? = null
    }
    private val tracks = mutableMapOf<String, Track>()
    @Synchronized fun reset() { tracks.clear() }
    @Synchronized fun baselineSamples() = tracks.values.sumOf { it.values.size }
    @Synchronized fun sample(result: DiagnosticResult, direct: DiagnosticResult?, now: Long, fallbackConfigured: Boolean): DegradationRecommendation? {
        require(now >= 0)
        if (!settings.enabled || result.path != DiagnosticPath.CURRENT_MCRF || result.family != DiagnosticFamily.AUTOMATIC || result.targetId !in settings.targetIds) return null
        val track = tracks.getOrPut(result.targetId) { Track() }
        if (track.lastAt?.let { now <= it } == true) return null
        track.lastAt = now
        if (track.lastGoodAt?.let { now - it > settings.intervalSeconds * settings.window * 2000L } == true) {
            track.values.clear(); track.bad = 0; track.lastGoodAt = null
        }
        val elapsed = result.elapsedMs?.takeIf { it in 0..30_000 }
        val good = result.state == DiagnosticState.AVAILABLE && elapsed != null
        val transportFailure = result.state == DiagnosticState.ERROR && result.issue in setOf(
            DiagnosticIssue.TIMEOUT, DiagnosticIssue.CONNECT_FAILED, DiagnosticIssue.TLS_FAILED, DiagnosticIssue.DNS_FAILED)
        if (!good && !transportFailure) { track.bad = 0; return null }
        val baseline = track.values.toList().sorted().let { it.getOrNull(it.size / 2) }
        val trained = track.values.size >= settings.minimumBaseline
        val degraded = trained && (transportFailure || elapsed!! >= maxOf((baseline!! * settings.factor).toLong(), baseline + settings.minimumIncreaseMs))
        if (!degraded) {
            track.bad = 0
            if (good) {
                track.values.addLast(elapsed!!)
                track.lastGoodAt = now
                while (track.values.size > settings.window) track.values.removeFirst()
            }
            return null
        }
        track.bad++
        if (track.bad < settings.repeated || track.emittedAt?.let { now - it < settings.cooldownMs } == true) return null
        track.emittedAt = now
        return DegradationRecommendation(result.targetId, baseline!!, elapsed, track.bad,
            direct?.state == DiagnosticState.AVAILABLE, fallbackConfigured)
    }
}

/** Opt-in process-lifetime monitoring. Restored on app open; an active VPN's own
 * service keeps its process alive. This is not a wakeup/alarm/keepalive service. */
object DiagnosticsDegradationMonitor {
    private const val CHANNEL="service-diagnostics"
    internal const val NOTICE=3042
    internal const val CHOOSE_SERVER="org.mcrf.client.degradation.CHOOSE_SERVER"
    internal const val LATER="org.mcrf.client.degradation.LATER"
    private var notificationActionsInstalled=false
    private const val KEY = "betaServiceDegradationEnabled"
    private const val TARGETS = "betaServiceDegradationTargets"
    private const val INTERVAL = "betaServiceDegradationInterval"
    private val lock = Any()
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private val scheduler = ScheduledThreadPoolExecutor(1) { Thread(it, "mcrf-service-degradation").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private var schedule: ScheduledFuture<*>? = null
    private var runner: ServiceTestRunner? = null
    private var run: ServiceTestRunner.Run? = null
    private var generation = 0L
    private var scope: DiagnosticsScope? = null
    private var policy: DiagnosticsDegradationPolicy? = null
    private var notificationAt: Long? = null
    @Volatile private var captured: Lazy<RealServiceProbe>? = null
    @Volatile var snapshot = DegradationSnapshot(false)
        private set

    fun settings(context: Context): DegradationSettings = runCatching {
        val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        DegradationSettings(prefs.getBoolean(KEY, false), setOf("connection"), prefs.getInt(INTERVAL, 60))
    }.getOrDefault(DegradationSettings())
    fun restore(context: Context) {
        installNotificationActions(context)
        val settings = settings(context)
        if (settings.enabled) synchronized(lock) { if (schedule == null) start(context.applicationContext, settings) }
    }
    fun configure(context: Context, settings: DegradationSettings) {
        installNotificationActions(context)
        val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        if (!settings.enabled) synchronized(lock) { stop() }
        check(prefs.edit().putBoolean(KEY, settings.enabled).putStringSet(TARGETS, settings.targetIds.toSet()).putInt(INTERVAL, settings.intervalSeconds).commit())
        synchronized(lock) { stop(); if (settings.enabled) start(context.applicationContext, settings) }
        changed()
    }
    fun observe(listener: () -> Unit): AutoCloseable { observers.add(listener); return AutoCloseable { observers.remove(listener) } }
    private fun changed() { observers.forEach { runCatching { it() } } }
    private fun stop() {
        generation++; schedule?.cancel(false); schedule = null; run?.cancel(); runner?.close(); run = null; runner = null
        captured = null; scope = null; policy = null; notificationAt = null; snapshot = DegradationSnapshot(false)
    }
    private fun start(context: Context, settings: DegradationSettings) {
        val owner = ++generation
        policy = DiagnosticsDegradationPolicy(settings.copy(targetIds=setOf("connection"))); snapshot = DegradationSnapshot(true)
        runner = ServiceTestRunner(ServiceProbe { request, token -> captured!!.value.execute(request, token) }, parallelism = 2, timeoutMs = 6000, budgetMs = 30_000)
        schedule = scheduler.scheduleWithFixedDelay({ runCatching { tick(context, settings, owner) }.onFailure {
            synchronized(lock) { if (generation == owner) snapshot = snapshot.copy(issue = DiagnosticIssue.INTERNAL) }; changed()
        } }, 1, settings.intervalSeconds.toLong(), TimeUnit.SECONDS)
    }
    private fun tick(context: Context, settings: DegradationSettings, owner: Long) = synchronized(lock) {
        if (generation != owner || run != null) return@synchronized
        if (SafeModeStore(context).enabled() || !McrfVpnService.hasActiveSession() || !ConnectionPresentation.connected(McrfVpnService.status) ||
            CaptivePortalMonitor.state(context) != CaptivePortalState.INTERNET) {
            policy?.reset(); scope = null; snapshot = snapshot.copy(baselineSamples = 0, last = null, issue = DiagnosticIssue.NO_MCRF_PROXY)
            changed(); return@synchronized
        }
        val initial = DiagnosticsEnvironmentReader.scope(context)
        if (scope != initial) {
            policy?.reset(); scope = initial
            val name=runCatching {Connections(context).apply {load()}.entries().firstOrNull {it.id==initial.server}?.name}.getOrNull().orEmpty()
            snapshot = DegradationSnapshot(true,server=ClientEvents.redact(name).take(96))
        }
        captured = lazy { RealServiceProbe.capture(context) }
        run = runner!!.start(ConnectionDegradationEvidence.probes) { report ->
            if (report.complete) synchronized(lock) {
                if (generation != owner) return@synchronized
                run = null
                if (DiagnosticsEnvironmentReader.scope(context) != initial) {
                    policy?.reset(); scope = null; snapshot = snapshot.copy(baselineSamples = 0, last = null, issue = DiagnosticIssue.PATH_CHANGED)
                } else {
                    val fallback = runCatching { FallbackChainStore(context).load().enabled }.getOrDefault(false)
                    var latest = snapshot.last
                    val current=ConnectionDegradationEvidence.aggregate(report.results,DiagnosticPath.CURRENT_MCRF)
                    val direct=ConnectionDegradationEvidence.aggregate(report.results,DiagnosticPath.DIRECT)
                    policy?.sample(current, direct, SystemClock.elapsedRealtime(), fallback)?.let { recommendation ->
                            latest = recommendation; notify(context, recommendation, settings.cooldownMs)
                        }
                    snapshot = DegradationSnapshot(true, policy!!.baselineSamples(), snapshot.completedRounds + 1, latest,current.issue,snapshot.server)
                }
                changed()
            }
        }
    }
    private fun notify(context: Context, recommendation: DegradationRecommendation, cooldownMs: Long) {
        // Recommendation only. The existing service's bounded fallback controller
        // keeps its own enabled flag, multi-origin evidence, cooldown and budget.
        runCatching {
            val now = SystemClock.elapsedRealtime()
            if (notificationAt?.let { now - it < cooldownMs } == true) return@runCatching
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return@runCatching
            if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return@runCatching
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ухудшение подключения", NotificationManager.IMPORTANCE_DEFAULT))
            fun action(code:Int,name:String)=PendingIntent.getActivity(context,code,Intent(context,MainActivity::class.java)
                .setAction(name).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val choose=action(NOTICE,CHOOSE_SERVER)
            val later=PendingIntent.getBroadcast(context,NOTICE+1,
                Intent(context,DegradationDismissReceiver::class.java).setAction(LATER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val reason=DegradationNotice.reason(recommendation)
            manager.notify(NOTICE, android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Подключение стало хуже")
                .setContentText(reason).setStyle(android.app.Notification.BigTextStyle().bigText(listOf(snapshot.server,reason).filter {it.isNotBlank()}.joinToString("\n")))
                .setContentIntent(choose).setAutoCancel(true)
                .addAction(android.app.Notification.Action.Builder(null,"Выбрать сервер",choose).build())
                .addAction(android.app.Notification.Action.Builder(null,"Позже",later).build()).build())
            notificationAt = now
        }
    }
    /** Lifecycle delivery keeps these actions local to this feature; opening a notice never selects a server. */
    @Synchronized private fun installNotificationActions(context:Context) {
        if(notificationActionsInstalled)return
        val application=context.applicationContext as? android.app.Application?:return
        notificationActionsInstalled=true
        application.registerActivityLifecycleCallbacks(object:android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity:android.app.Activity) {
                if(activity !is MainActivity)return
                val action=activity.intent?.action
                if(!DegradationNotice.isAction(action))return
                activity.intent.action=null
                activity.getSystemService(NotificationManager::class.java).cancel(NOTICE)
                if(action==CHOOSE_SERVER)activity.window.decorView.post {
                    if(!activity.isFinishing&&!activity.isDestroyed)ConnectionDegradationUi.chooseServer(activity)
                }
            }
            override fun onActivityCreated(activity:android.app.Activity,state:android.os.Bundle?)=Unit
            override fun onActivityStarted(activity:android.app.Activity)=Unit
            override fun onActivityPaused(activity:android.app.Activity)=Unit
            override fun onActivityStopped(activity:android.app.Activity)=Unit
            override fun onActivitySaveInstanceState(activity:android.app.Activity,state:android.os.Bundle)=Unit
            override fun onActivityDestroyed(activity:android.app.Activity)=Unit
        })
    }
}

/** Explicit, non-exported notification action also works after process recreation. */
class DegradationDismissReceiver:android.content.BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==DiagnosticsDegradationMonitor.LATER)
            context.getSystemService(NotificationManager::class.java).cancel(DiagnosticsDegradationMonitor.NOTICE)
    }
}

internal object DegradationNotice {
    fun isAction(action:String?)=action==DiagnosticsDegradationMonitor.CHOOSE_SERVER||action==DiagnosticsDegradationMonitor.LATER
    fun reason(value:DegradationRecommendation)=if(value.observedMs==null)
        "Сервер не отвечает ${value.failures} проверки подряд. Выберите другой сервер."
        else "Задержка: ${value.baselineMs} → ${value.observedMs} мс · ${value.failures} проверки подряд."
}

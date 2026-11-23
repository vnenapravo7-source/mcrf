package org.mcrf.client

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit user-started Beta run. No boot/sticky restart, VPN start/stop or settings write. */
class SpeedTestService : Service() {
    private data class Run(val token: DiagnosticCancellation, val deadline: Long)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var runId = 0L
    private var latestStartId = 0 // Main looper; CANCEL/stale commands also advance Android's startId.
    private var destroyed = false
    private var lastNotice = 0L
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val id = intent?.getLongExtra(RUN, -1) ?: -1
        if (intent?.action == CANCEL) {
            if (id == runId) requestStop("Тест отменён")
            postNotice(true)
            if (runId == 0L) stopSelf(startId)
            return START_NOT_STICKY
        }
        val pending = runs[id]
        val snapshot = state.snapshot
        if (intent?.action != START || pending == null || snapshot.runId != id || !snapshot.busy || runId != 0L) {
            if (runId == 0L) stopSelf(startId)
            return START_NOT_STICKY
        }
        runId = id
        try {
            check(notificationAllowed(this)) { "Notification unavailable" }
            channel(this)
            val notice = notification(snapshot, true)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTICE, notice)
            pending.token.check()
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcrf:speedtest").apply {
                setReferenceCounted(false)
                acquire((pending.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            }
            watchdog = timer.scheduleAtFixedRate({
                if (SystemClock.elapsedRealtime() >= pending.deadline) requestStop("Время теста истекло", id)
            }, 0, 100, TimeUnit.MILLISECONDS)
            worker.execute { measure(id, pending) }
        } catch (_: Exception) {
            pending.token.cancel(); runs.remove(id)
            releaseWake()
            watchdog?.cancel(false)
            state.finish(id, null, "Не удалось запустить фоновый тест; трафик не отправлен")
            runId = 0; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun measure(id: Long, run: Run) {
        var transport: SpeedTestRoute? = null
        var result: SpeedTestResult? = null
        var problem = "Тест не завершён: ошибка сети, TLS или ответа сервера"
        try {
            val request = checkNotNull(state.snapshot.takeIf { it.runId == id }?.request)
            val provider = SpeedTestProviders.adapter(request.providerId) // Before catalogue/native work.
            run.token.check()
            val server = if (request.path == SpeedTestPath.SAVED_SERVER) {
                Connections(this).apply { load() }.entries().singleOrNull { it.id == request.serverId }
                    ?: throw SpeedTestRouteUnavailable()
            } else null
            run.token.check()
            val label = when (request.path) {
                SpeedTestPath.APP_NETWORK -> "Сеть приложения Android"
                SpeedTestPath.CURRENT_MCRF_PROXY -> "Текущий MCRF-прокси (маршрут по его правилам)"
                SpeedTestPath.SAVED_SERVER -> DiagnosticsServerLabel.display(checkNotNull(server)).replace('\n', ' ')
            } + " → " + checkNotNull(SpeedTestProviders.find(request.providerId)).label
            state.progress(id, label)
            val captured = if (server != null) SpeedTestRoute.openSaved(this, server, provider, run.token)
                else SpeedTestRoute.capture(this, request.path, run.token, provider)
            transport = captured
            result = SpeedTestRunner(captured).run(run.token) { update ->
                state.progress(id, label, update)
                postNotice()
            }
            run.token.check()
            if (SystemClock.elapsedRealtime() >= run.deadline) throw SocketTimeoutException()
        } catch (error: Exception) {
            result = null
            problem = when (error) {
                is SocketTimeoutException -> "Время теста истекло"
                is SpeedTestRouteChanged -> "Маршрут изменился — тест остановлен"
                is SpeedTestRouteUnavailable -> "Выбранный маршрут/сервер недоступен; переключения не было"
                is DiagnosticsProxyStartupException -> "Изолированный сервер недоступен: VPN активен, запуск занят или модуль не готов"
                is java.util.concurrent.CancellationException -> "Тест отменён"
                else -> problem
            }
        } finally {
            // Runner closes too, but failed capture/startup must also be retired.
            // This is worker-only and retains admission until actual child exit.
            var retired = true
            try { run.token.cancel() } finally {
                try { transport?.close() } catch (_: Exception) {
                    retired = false; result = null
                    state.stop(id, "Завершение маршрута не подтверждено — новый запуск заблокирован")
                }
            }
            watchdog?.cancel(false)
            main.post {
                releaseWake()
                if (retired) { state.finish(id, result, problem); runs.remove(id) }
                if (!destroyed) {
                    runCatching { getSystemService(NotificationManager::class.java).notify(NOTICE, notification(state.snapshot, !retired)) }
                    if (retired) {
                        try { stopForeground(STOP_FOREGROUND_DETACH) }
                        finally { runId = 0; stopSelfResult(latestStartId) }
                    }
                }
            }
        }
    }

    private fun requestStop(message: String, id: Long = runId) {
        if (id != runId) return
        if (!state.stop(id, message)) return
        runs[id]?.let { run -> cancelWorker.execute { run.token.cancel() } }
        postNotice(true)
    }
    private fun postNotice(force: Boolean = false) = main.post {
        if (!destroyed && state.snapshot.runId == runId && (force || SystemClock.elapsedRealtime() - lastNotice >= 1000)) {
            lastNotice = SystemClock.elapsedRealtime()
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTICE, notification(state.snapshot, state.snapshot.busy)) }
        }
    }
    private fun notification(snapshot: SpeedTestSnapshot, busy: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra(OPEN, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.status_icon)
            .setContentTitle("MCRF · тест скорости · Beta").setContentText(if (busy) progressText(snapshot) else snapshot.message)
            .setContentIntent(open).setOngoing(busy).setOnlyAlertOnce(true).setAutoCancel(!busy)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (busy) {
            val stop = PendingIntent.getService(this, snapshot.runId.toInt(), Intent(this, SpeedTestService::class.java)
                .setAction(CANCEL).setData(android.net.Uri.parse("mcrf-speedtest://run/${snapshot.runId}"))
                .putExtra(RUN, snapshot.runId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Отмена", stop).build())
        }
        return builder.build()
    }
    private fun releaseWake() {
        val owned = wake; wake = null
        runCatching { if (owned?.isHeld == true) owned.release() }
    }
    override fun onDestroy() {
        destroyed = true
        watchdog?.cancel(false)
        if (runId != 0L) requestStop("Фоновый тест остановлен системой")
        releaseWake(); timer.shutdownNow(); worker.shutdown()
        super.onDestroy()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { requestStop("Время фоновой службы истекло"); stopSelf(startId) }

    companion object {
        // Merely checking the cold-start intent must not allocate run state,
        // entropy or executors on the loaded-UI path.
        internal val state by lazy { SpeedTestState() }
        private val runs by lazy { ConcurrentHashMap<Long, Run>() }
        private val cancelWorker by lazy { Executors.newCachedThreadPool { Thread(it, "mcrf-speedtest-cancel").apply { isDaemon = true } } }
        private const val START = "org.mcrf.client.SPEED_TEST_START"
        private const val CANCEL = "org.mcrf.client.SPEED_TEST_CANCEL"
        private const val RUN = "speed_test_run"
        private const val OPEN = "open_speed_test"
        private const val CHANNEL = "mcrf-speedtest"
        private const val NOTICE = 1842
        internal fun notificationAllowed(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                context.getSystemService(NotificationManager::class.java).let {
                    it.areNotificationsEnabled() && it.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
                }
        private fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Тест скорости · Beta", NotificationManager.IMPORTANCE_LOW))
        }
        internal fun start(context: Context, request: SpeedTestRequest): String? {
            val provider = SpeedTestProviders.find(request.providerId) ?: return "Неизвестный провайдер"
            provider.unavailable?.let { return it }
            if (!notificationAllowed(context)) return "Разрешите уведомления приложения для фонового теста с кнопкой отмены"
            val snapshot = state.begin(request) ?: return "Другой тест ещё работает или завершает маршрут"
            runs[snapshot.runId] = Run(DiagnosticCancellation(SpeedTestPlan.RUN_MS), SystemClock.elapsedRealtime() + SpeedTestPlan.RUN_MS)
            try {
                checkNotNull(context.startForegroundService(Intent(context, SpeedTestService::class.java).setAction(START).putExtra(RUN, snapshot.runId)))
            } catch (_: Exception) {
                runs.remove(snapshot.runId)?.token?.cancel()
                state.finish(snapshot.runId, null, "Не удалось запустить фоновую службу")
                return state.snapshot.message
            }
            return null
        }
        internal fun cancel(context: Context, id: Long) {
            if (state.stop(id, "Тест отменён")) {
                runs[id]?.let { run -> cancelWorker.execute { run.token.cancel() } }
                runCatching { context.startService(Intent(context, SpeedTestService::class.java).setAction(CANCEL).putExtra(RUN, id)) }
            }
        }
        internal fun consumeOpen(activity: MainActivity): Boolean {
            if (!activity.intent.getBooleanExtra(OPEN, false) || activity.working()) return false
            activity.intent.removeExtra(OPEN)
            activity.navigate(ClientSettingsNavigation.BETA)
            SpeedTestUi.show(activity)
            return true
        }
        internal fun progressText(snapshot: SpeedTestSnapshot): String {
            if (snapshot.phase != SpeedTestPhase.RUNNING) return snapshot.message
            val value = snapshot.progress ?: return "Подготовка выбранного сервера…"
            return when (value.stage) {
                SpeedTestStage.LATENCY -> "HTTPS-отклик ${value.sample}/${SpeedTestPlan.LATENCY_COUNT}"
                SpeedTestStage.DOWNLOAD -> "Загрузка ${value.bytes / 1024} / ${value.limit / 1024} КиБ"
                SpeedTestStage.UPLOAD -> "Отдача ${value.bytes / 1024} / ${value.limit / 1024} КиБ"
            }
        }
    }
}

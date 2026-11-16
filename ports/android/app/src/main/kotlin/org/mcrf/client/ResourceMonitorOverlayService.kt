package org.mcrf.client

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

/**
 * Explicit foreground-UI start only. Never restarted after task removal, lock, process death or boot.
 * Main integrator must declare SYSTEM_ALERT_WINDOW and this non-exported service, type specialUse,
 * stopWithTask=false, with PROPERTY_SPECIAL_USE_FGS_SUBTYPE describing the user-started resource HUD.
 * FOREGROUND_SERVICE and FOREGROUND_SERVICE_SPECIAL_USE are already present in the app manifest.
 * All readings still belong to the main app process / UID, never to external engine packages.
 * Manifest additions owned by main:
 * <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
 * <service android:name=".ResourceMonitorOverlayService" android:exported="false"
 *     android:foregroundServiceType="specialUse" android:stopWithTask="false">
 *     <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
 *         android:value="User initiated passive resource monitor visible above other apps" />
 * </service>
 */
class ResourceMonitorOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val sampler: ResourceMonitorSampler = ResourceMonitorSampler(changed = ::scheduleRender)
    private var windowManager: WindowManager? = null
    private var hud: ResourceMonitorHud? = null
    private var layout: WindowManager.LayoutParams? = null
    private var finished = false
    private var receiverRegistered = false
    private val deliveryLock = Any()
    private var deliveryPending = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { finish() }
    }
    private val delivery = Runnable {
        synchronized(deliveryLock) { deliveryPending = false }
        if (!finished && instance === this) {
            if (!allowed()) finish()
            else {
                lastSnapshot = sampler.snapshot()
                hud?.render(lastSnapshot)
                publish()
            }
        }
    }
    // Permission/screen safety is independent of a potentially blocked Debug/proc read.
    private val guard = object : Runnable {
        override fun run() {
            if (finished) return
            if (!allowed()) finish() else handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == START && starts.requested && !starts.accepts(intent.getLongExtra(TOKEN, -1L))) {
            // A queued stale start must not stop the newer user-owned overlay.
            return START_NOT_STICKY
        }
        if (intent?.action != START || !starts.requested) {
            finish(); return START_NOT_STICKY
        }
        if (finished) {
            starts.cancel()
            lastError = "Монитор ещё останавливается. Включите его повторно."
            publish(); stopSelf(startId); return START_NOT_STICKY
        }
        try {
            if (!allowed()) { finish(); return START_NOT_STICKY }
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Монитор ресурсов", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 0, Intent(this, ResourceMonitorOverlayService::class.java)
                .setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.status_icon)
                .setContentTitle("MCRF · монитор ресурсов")
                .setContentText("Пассивный замер процесса MCRF и трафика UID")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(Notification.Action.Builder(null, "Выключить", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification)
            if (hud == null) {
                windowManager = getSystemService(WindowManager::class.java)
                layout = WindowManager.LayoutParams(
                    width(), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.LEFT; x = dp(8); y = dp(64) }
                hud = ResourceMonitorHud(this, open = {
                    try { open.send() } catch (_: PendingIntent.CanceledException) { finish() }
                }, close = ::finish, openDescription = "Открыть приложение", move = { dx, dy ->
                    layout?.let { params -> params.x += dx.toInt(); params.y += dy.toInt(); updateWindow(force = true) }
                }).also { view ->
                    view.render(ResourceMonitorSnapshot())
                    view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateWindow() }
                    windowManager!!.addView(view, layout)
                }
                val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED)
                else registerReceiver(screenOff, filter)
                receiverRegistered = true
            }
            instance = this
            publish()
            sampler.start()
            handler.removeCallbacks(guard); handler.post(guard)
        } catch (_: Exception) {
            lastError = "Не удалось открыть системный монитор. Проверьте разрешение Android."
            finish()
        }
        return START_NOT_STICKY
    }

    private fun allowed() = try {
        ResourceMonitorOverlayPolicy.allowed(
            Settings.canDrawOverlays(this),
            getSystemService(PowerManager::class.java).isInteractive,
            getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        )
    } catch (_: Exception) { false }

    private fun scheduleRender(): Unit = synchronized(deliveryLock) {
        if (!finished && !deliveryPending) { deliveryPending = true; handler.post(delivery) }
    }

    private fun width() = ResourceMonitorLayout.widthPx(
        resources.displayMetrics.widthPixels - dp(16), resources.displayMetrics.density)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun updateWindow(force: Boolean = false) {
        if (finished) return
        val view = hud ?: return
        val params = layout ?: return
        val screen = resources.displayMetrics
        params.width = width()
        val nextX = params.x.coerceIn(0, (screen.widthPixels - params.width).coerceAtLeast(0))
        val nextY = params.y.coerceIn(0, (screen.heightPixels - view.height - dp(32)).coerceAtLeast(0))
        val changed = params.x != nextX || params.y != nextY || view.width != params.width
        params.x = nextX; params.y = nextY
        if (changed || force) {
            try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) { finish() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateWindow(force = true)
        hud?.render(sampler.snapshot())
    }

    override fun onTaskRemoved(rootIntent: Intent?) { finish(); super.onTaskRemoved(rootIntent) }

    private fun finish() {
        if (finished) return
        synchronized(deliveryLock) { finished = true; deliveryPending = false; handler.removeCallbacks(delivery) }
        handler.removeCallbacks(guard)
        sampler.stop()
        lastSnapshot = sampler.snapshot()
        sampler.close()
        if (receiverRegistered) { unregisterReceiver(screenOff); receiverRegistered = false }
        hud?.let { view -> try { windowManager?.removeViewImmediate(view) } catch (_: Exception) { /* already removed */ } }
        hud = null; layout = null; windowManager = null
        if (instance === this) instance = null
        starts.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        publish()
    }

    override fun onDestroy() { finish(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "resource-monitor"
        private const val NOTIFICATION = 73
        private const val START = "org.mcrf.client.resource-monitor.START"
        private const val STOP = "org.mcrf.client.resource-monitor.STOP"
        private const val TOKEN = "generation"
        private val starts = ResourceMonitorOverlayStartGate()
        private var instance: ResourceMonitorOverlayService? = null
        private val observers = mutableSetOf<() -> Unit>()
        private var lastSnapshot = ResourceMonitorSnapshot()
        var lastError: String? = null
            private set
        val running get() = instance != null
        val starting get() = starts.requested && instance == null
        internal fun currentSampler(): ResourceMonitorSampler? = instance?.sampler
        internal fun snapshot() = instance?.sampler?.snapshot() ?: lastSnapshot
        internal fun observe(observer: () -> Unit) { observers.add(observer) }
        internal fun unobserve(observer: () -> Unit) { observers.remove(observer) }
        private fun publish() { observers.toList().forEach { it() } }

        /** Call only from the resumed Activity's explicit user action, never from a receiver. */
        internal fun start(context: Context): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!Settings.canDrawOverlays(context)) return false
            lastError = null
            val token = starts.request()
            return try {
                checkNotNull(context.startForegroundService(Intent(context, ResourceMonitorOverlayService::class.java)
                    .setAction(START).putExtra(TOKEN, token)))
                publish(); true
            } catch (_: Exception) {
                starts.cancel()
                lastError = "Android не разрешил запуск. Повторите из открытых настроек Beta."
                publish(); false
            }
        }

        internal fun stop(context: Context) {
            check(Looper.myLooper() == Looper.getMainLooper())
            starts.cancel()
            instance?.finish() ?: context.stopService(Intent(context, ResourceMonitorOverlayService::class.java))
            publish()
        }

        internal fun resetRecording() {
            instance?.sampler?.resetRecording()
            lastSnapshot = (instance?.sampler?.snapshot() ?: lastSnapshot).copy(recording = ResourceMonitorRecording())
            instance?.scheduleRender()
            publish()
        }
    }
}

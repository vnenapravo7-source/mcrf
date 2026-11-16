package org.mcrf.client

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

/** No exact-alarm access, polling service, VPN START or network access. */
internal object TemporaryRulesRuntime {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var foregroundDeadline: Runnable? = null
    @Volatile private var app: Context? = null
    const val EXPIRE = "org.mcrf.client.TEMPORARY_RULES_EXPIRE"
    fun clock(context: Context): TemporaryRules.Clock = TemporaryRules.Clock(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0), ProfileStore.generation(context))
    fun project(state: JSONObject): JSONObject {
        if (!state.has(TemporaryRules.KEY)) return JSONObject(state.toString()).apply {TemporaryRules.removeLegacy(this)}
        val context = app ?: error("Временные правила ещё не проверены перед запуском. Маршруты не применены.")
        return TemporaryRules.project(state, clock(context))
    }
    @Volatile var backgroundAvailable = true
        private set
    fun schedule(context: Context, state: JSONObject) = synchronized(ProfileStore.storageLock) {
        val application = context.applicationContext; app = application
        val now = clock(application)
        val alarm = application.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(application, 314348,
            Intent(application, TemporaryRulesReceiver::class.java).setAction(EXPIRE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val remaining = TemporaryRules.rows(state).filter { !TemporaryRules.expired(it, now) }.minOfOrNull { row ->
            minOf(row.getLong("until") - now.wall, if (row.getInt("boot") == now.boot) row.getLong("elapsedUntil") - now.elapsed else Long.MAX_VALUE)
        }
        backgroundAvailable = runCatching { alarm.cancel(intent) }.isSuccess
        foregroundDeadline?.let(main::removeCallbacks); foregroundDeadline = null
        if (remaining == null) return@synchronized
        // Alarm delivery can be deferred by Android. The in-process check and every
        // runtime projection independently reject expiry, including after reboot.
        backgroundAvailable = runCatching { alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, now.elapsed + remaining.coerceAtLeast(1), intent) }.isSuccess
        val task = Runnable { TemporaryRulesReceiver.reconcile(application) }
        foregroundDeadline = task
        main.postDelayed(task, remaining.coerceAtLeast(1))
    }
}

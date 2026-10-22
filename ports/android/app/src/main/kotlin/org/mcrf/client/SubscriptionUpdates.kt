package org.mcrf.client

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

object SubscriptionUpdates {
    @JvmField val HOURS = SubscriptionSchedule.hours.toIntArray()
    @JvmField val LABELS = SubscriptionSchedule.labels.toTypedArray()
    private const val JOB = 2401
    private const val CHANNEL = "subscription-updates-silent"
    @JvmStatic fun initialize(context: Context, prefs: SharedPreferences) {
        val saved = if (prefs.contains("subscriptionHours")) prefs.getInt("subscriptionHours", SubscriptionSchedule.DEFAULT_HOURS) else null
        val hours = SubscriptionSchedule.selected(saved)
        if (saved != hours) check(prefs.edit().putInt("subscriptionHours", hours).commit())
        scheduleMinutes(context, SubscriptionOptionsStore.load(context).intervalMinutes)
        ensureNotificationChannel(context)
    }
    @JvmStatic fun ensureNotificationChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Обновление подписок", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Результат автообновления подписок — без звука и вибрации"
                setSound(null, null); enableVibration(false); enableLights(false)
                setShowBadge(false)
            })
    }
    @JvmStatic fun notifyResult(context: Context, message: String, success: Boolean) {
        ensureNotificationChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, JOB, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.status_icon)
            .setContentTitle(if (success) "MCRF · подписки обновлены" else "MCRF · подписки не обновлены")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setSound(null).setVibrate(longArrayOf()).setDefaults(0)
            .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        try { manager.notify(JOB, notification) } catch (_: SecurityException) { /* Permission can be revoked during the job. */ }
    }
    @JvmStatic fun identity(context: Context): String = synchronized(this) {
        val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        prefs.getString("hwid", null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("hwid", it).commit()) }
    }
    @JvmStatic fun fetch(context: Context, url: String): SubscriptionClient.Downloaded {
        val options = SubscriptionOptionsStore.load(context)
        return SubscriptionClient.fetch(url, hwid = if(options.sendDeviceHeaders) identity(context) else null,
            options = options, model = if(options.sendDeviceHeaders) Build.MODEL else null,
            osVersion = if(options.sendDeviceHeaders) Build.VERSION.RELEASE else null)
    }
    @JvmStatic fun refresh(context: Context, catalogue: Connections): SubscriptionChanges {
        val fetched = linkedMapOf<String, SubscriptionClient.Downloaded>()
        val snapshot = catalogue.subscriptions()
        val expected = snapshot.associate { it.getString("url") to it.getString("id") }
        snapshot.forEach { sub -> fetched[sub.getString("url")] = fetch(context, sub.getString("url")) }
        val changes = catalogue.refreshFetched(fetched, expected)
        if(fetched.isNotEmpty())SubscriptionOpenRefresh.completed(context)
        ClientEvents.record(context, "Подписки", changes.summary())
        return changes
    }
    @JvmStatic fun schedule(context: Context, hours: Int) {
        require(hours in HOURS)
        setInterval(context, hours * 60)
    }
    @JvmStatic fun setInterval(context: Context, minutes: Int) = synchronized(ProfileStore.storageLock) {
        SubscriptionSchedule.validateMinutes(minutes)
        val previous = SubscriptionOptionsStore.load(context)
        scheduleMinutes(context, minutes)
        try { SubscriptionOptionsStore.save(context, previous.copy(intervalMinutes = minutes)) }
        catch (failure: Exception) { scheduleMinutes(context, previous.intervalMinutes); throw failure }
    }
    @JvmStatic fun scheduleMinutes(context: Context, minutes: Int) {
        SubscriptionSchedule.validateMinutes(minutes)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (minutes == 0) { scheduler.cancel(JOB);scheduler.cancel(SubscriptionOpenRefresh.JOB);return }
        val interval = minutes * 60_000L
        // Opening the app must not postpone the next scheduled update indefinitely.
        if (scheduler.getPendingJob(JOB)?.intervalMillis == interval) return
        check(scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, SubscriptionUpdateService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setPeriodic(interval, minOf(interval / 5, 3_600_000L))
            .setBackoffCriteria(30 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()) == JobScheduler.RESULT_SUCCESS)
    }
}

/** Android batches this periodic work. No exact alarms, wakelocks or permanent refresh loop. */
class SubscriptionUpdateService : JobService() {
    companion object {private val activeFetch=java.util.concurrent.atomic.AtomicBoolean(false)}
    private val worker = Executors.newSingleThreadExecutor()
    private var work: Future<*>? = null
    private var activeLease:SubscriptionRefreshLease?=null
    private val runs=SubscriptionUpdateRuns()
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    override fun onStartJob(params: JobParameters): Boolean {
        val fromOpen=params.extras.getBoolean("onOpen",false)
        if(fromOpen&&!SubscriptionOpenRefresh.idle(this))return false
        if(!activeFetch.compareAndSet(false,true))return false
        val lease=SubscriptionRefreshLease {activeFetch.set(false)};activeLease=lease
        val attempt=runs.begin()
        work?.cancel(true)
        try {work = worker.submit {
            if(!lease.enter())return@submit
            var retry = false
            try {
                if(fromOpen&&!SubscriptionOpenRefresh.dueNow(this))return@submit
                val catalogue = Connections(this); catalogue.load()
                val fetched = linkedMapOf<String, SubscriptionClient.Downloaded>()
                val snapshot = catalogue.subscriptions()
                val expected = snapshot.associate { it.getString("url") to it.getString("id") }
                snapshot.forEach { sub ->
                    attempt.checkActive()
                    if(fromOpen&&!SubscriptionOpenRefresh.idle(this))throw InterruptedException("App-open refresh retired")
                    val url = sub.getString("url"); fetched[url] = SubscriptionUpdates.fetch(this, url)
                }
                if (attempt.active && fetched.isNotEmpty()) {
                    if(fromOpen&&!SubscriptionOpenRefresh.idle(this))throw InterruptedException("App-open refresh retired")
                    // Re-read at commit, never overwrite simultaneous user edits with the old snapshot.
                    catalogue.load()
                    val present = catalogue.subscriptions().map { it.getString("url") }.toSet()
                    val committed = fetched.filterKeys { it in present }
                    if (committed.isNotEmpty()) {
                        val changes = catalogue.refreshFetchedGuarded(committed,expected,attempt::checkActive)
                        attempt.checkActive()
                        val message = changes.summary()
                        SubscriptionOpenRefresh.completed(this)
                        ClientEvents.record(this, "Подписки", "По расписанию · $message")
                        if(attempt.active)SubscriptionUpdates.notifyResult(this, message, true)
                    }
                }
            } catch (_: InterruptedException) { retry = !fromOpen }
            catch (_: Exception) {
                retry = !fromOpen
                if (attempt.active) {
                    val message = "Не удалось обновить подписки. Сохранённые серверы оставлены; повторим позже."
                    ClientEvents.record(this, "Подписки", message)
                    SubscriptionUpdates.notifyResult(this, message, false)
                }
            }
            finally {lease.finish();main.post {runs.finish(attempt){jobFinished(params,retry)}}}
        }}catch(_:Exception){lease.cancelQueued();runs.cancel();return false}
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {runs.cancel();activeLease?.cancelQueued();work?.cancel(true);return params.jobId!=SubscriptionOpenRefresh.JOB}
    override fun onDestroy() {runs.cancel();activeLease?.cancelQueued();work?.cancel(true);worker.shutdownNow();super.onDestroy()}
}

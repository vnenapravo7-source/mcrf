package org.mcrf.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Only the opaque UI ID enters notification intents; never a provider URL/cookie. */
internal object OpenFluxVerificationUi {
    const val REOFFER = OpenFluxVerificationUiEvents.REOFFER
    const val CANCEL = OpenFluxVerificationUiEvents.CANCEL
    fun failedOpening(context: Context, id: String) {
        if (!id.matches(OpenFluxVerificationClient.ID)) return
        // Only an opaque ID crosses back to the main-process registry.
        runCatching { context.sendBroadcast(Intent(context, OpenFluxVerificationCancelReceiver::class.java)
            .setAction(REOFFER).putExtra("request", id)) }
    }
    fun intent(context: Context, id: String, inline: Boolean = false, http: Boolean = false): Intent {
        require(!inline || !http)
        if (http) return httpIntent(context, id)
        return Intent(context, if (inline) OpenFluxInlineVerificationActivity::class.java else OpenFluxVerificationActivity::class.java)
            .setData(Uri.parse("mcrf-openflux-verification://request/$id")).putExtra("request", id)
    }
    /** Source-only private HTTP entry; no URL/body or production capability promotion. */
    fun httpIntent(context: Context, id: String): Intent {
        require(id.matches(OpenFluxVerificationUiEvents.ID))
        return Intent(context, OpenFluxHttpVerificationActivity::class.java)
            .setData(Uri.parse("mcrf-openflux-http-verification://request/$id")).putExtra("request", id)
    }

    fun show(context: Context, id: String) {
        val request = OpenFluxVerificationRequests.registry.get(id) ?: return
        if (request.http && !OpenFluxHttpVerificationPolicy.productionEnabled) return
        if (OpenFluxVerificationRequests.registry.browserOwnedBy(id)) { cancel(context, id); return }
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CarrierChallengeNotifications.CHANNEL,
                "Подтверждение подключения", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ручные проверки провайдера, необходимые для подключения"
            })
            if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CarrierChallengeNotifications.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
                ClientEvents.record(context, "Предупреждение", "Android не показывает запрос проверки OpenFlux. Открой MCRF для текущей проверки.")
                return
            }
            val open = PendingIntent.getActivity(context, 0, intent(context, id, request.inline, request.http).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val cancel = PendingIntent.getBroadcast(context, 0, Intent(context, OpenFluxVerificationCancelReceiver::class.java)
                .setAction(CANCEL).setData(Uri.parse("mcrf-openflux-verification://cancel/$id")).putExtra("request", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, CarrierChallengeNotifications.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Нужна проверка OpenFlux")
                .setContentText(if (request.http) "Страница скрипта: только обработчик текущего ядра, открытие и отправка вручную."
                    else if (request.inline) "Встроенная проверка: страница открывается вручную без доступа к сети." else "Открой проверку и подтверди сайт перед загрузкой страницы.")
                .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setTimeoutAfter(request.remainingMillis().coerceAtLeast(1))
                .addAction(Notification.Action.Builder(null, "Открыть проверку", open).build())
                .addAction(Notification.Action.Builder(null, "Отменить", cancel).build()).build()
            manager.notify(id, 2, notification)
            if (OpenFluxVerificationRequests.registry.get(id) == null) manager.cancel(id, 2)
        } catch (_: Exception) {
            ClientEvents.record(context, "Предупреждение", "Не удалось показать уведомление проверки OpenFlux. Открой MCRF; данные страницы и cookies не записываются в журнал.")
        }
    }
    fun cancel(context: Context, id: String) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(id, 2) }
    }
}

class OpenFluxVerificationCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("request") ?: return
        when (OpenFluxVerificationUiEvents.apply(OpenFluxVerificationRequests.registry, id, intent.action)) {
            OpenFluxVerificationUiEvents.Result.REOFFERED -> OpenFluxVerificationUi.show(context, id)
            OpenFluxVerificationUiEvents.Result.CANCELLED -> OpenFluxVerificationUi.cancel(context, id)
            OpenFluxVerificationUiEvents.Result.IGNORED -> Unit
        }
    }
}

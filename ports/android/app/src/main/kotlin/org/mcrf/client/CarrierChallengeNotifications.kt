package org.mcrf.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Only an opaque, expiring request ID leaves memory; no provider URL or proof. */
object CarrierChallengeNotifications {
    internal const val CHANNEL="carrier-verification"
    private fun manager(context:Context):NotificationManager = context.getSystemService(NotificationManager::class.java).also {
        // Android preserves importance chosen for an existing channel. Never replace a
        // muted channel with a new ID or override the user's notification preferences.
        it.createNotificationChannel(NotificationChannel(CHANNEL,"Подтверждение подключения",NotificationManager.IMPORTANCE_HIGH).apply {
            description="Запросы капчи VK, требующие участия для продолжения подключения"
        })
    }
    fun availability(context:Context):String = try {
        val manager=manager(context)
        when {
            !manager.areNotificationsEnabled()->"Уведомления MCRF запрещены Android. Запрос капчи можно пропустить вне приложения."
            manager.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE->"Уведомления о капче выключены для этого канала."
            (manager.getNotificationChannel(CHANNEL)?.importance?:0)<NotificationManager.IMPORTANCE_HIGH->"Канал капчи включён без высокой важности. Разреши всплывающий баннер в настройках Android."
            else->"Канал капчи имеет высокую важность. Показ баннера также зависит от настроек Android и режима «Не беспокоить»."
        }
    }catch(_:Exception){"Не удалось прочитать настройки уведомлений Android. Проверь канал «Подтверждение подключения» вручную."}
    fun settingsIntent(context:Context):Intent {
        manager(context)
        return Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,CHANNEL)
    }
    internal fun build(context:Context,id:String):Notification {
        require(id.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
        val intent=Intent(context,CarrierCaptchaActivity::class.java)
            .setData(Uri.parse("mcrf-verification://request/$id"))
            .putExtra("request",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancelIntent=Intent(context,CarrierChallengeCancelReceiver::class.java)
            .setData(Uri.parse("mcrf-verification://cancel/$id")).putExtra("request",id)
        val cancel=PendingIntent.getBroadcast(context,0,cancelIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Нужно подтверждение VK")
            .setContentText("Нажми, чтобы пройти проверку и продолжить подключение.")
            .setStyle(Notification.BigTextStyle().bigText("Для продолжения VPN нужно пройти проверку VK. Нажми «Открыть проверку». Запрос ограничен по времени."))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null,"Открыть проверку",pending).build())
            .addAction(Notification.Action.Builder(null,"Отменить проверку",cancel).build())
            .setTimeoutAfter((CarrierChallenge.get(id)?.remainingMillis()?:150000).coerceAtLeast(1)).setContentIntent(pending).build()
    }
    fun show(context:Context,id:String) {
        if(CarrierChallenge.get(id)?.automatic!=false)return
        runCatching {
            val manager=manager(context)
            if(!manager.areNotificationsEnabled()||manager.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE){
                ClientEvents.record(context,"Предупреждение","Android запрещает уведомления о проверке VK. Открой MCRF или настройки уведомлений капчи.")
                return
            }
            manager.notify(id,1,build(context,id))
            if(CarrierChallenge.get(id)==null)manager.cancel(id,1)
        }.onFailure {error->
            // A revoked Android permission can race the availability check. Do
            // not silently turn that failure into an invisible native timeout.
            ClientEvents.record(context,"Предупреждение",if(error is SecurityException)
                "Android не разрешил показать уведомление капчи. Открой MCRF для проверки или разреши уведомления приложения."
                else "Не удалось показать уведомление капчи. Открой MCRF для текущей проверки; подробности страницы и токены не записываются.")
        }
    }
    fun cancel(context:Context,id:String) {
        runCatching {context.getSystemService(NotificationManager::class.java).cancel(id,1)}
    }
}

package org.mcrf.client

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Separate from ongoing VPN/captcha channels. Contains no server, URL or credentials. */
object AutoSectionNotifications {
    internal const val CHANNEL="automatic-section-switch"
    internal const val ID=42
    fun post(context:Context,toWhitelists:Boolean) {
        try {
            val manager=context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,"Автопереключение VPN и БС",NotificationManager.IMPORTANCE_DEFAULT).apply {
                description="Подтверждённые автоматические переходы между VPN и белыми списками"
            })
            if(!manager.areNotificationsEnabled()||manager.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE||
                Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                ClientEvents.record(context,"Предупреждение","Автопереключение выполнено, но Android запретил уведомление. Параметры уведомлений не изменены.")
                return
            }
            val title=if(toWhitelists)"Автоматически переключено на БС"else"Автоматически восстановлен VPN"
            val detail=if(toWhitelists)"Подключение белых списков подтверждено."else"Сохранённое VPN-подключение восстановлено и проверено."
            val intent=Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val open=PendingIntent.getActivity(context,ID,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notice=Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.status_icon)
                .setContentTitle(title).setContentText(detail).setContentIntent(open).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setTimeoutAfter(300_000).build()
            manager.notify("auto-section",ID,notice)
        }catch(_:Exception) {
            // Notification denial/race must never tear down a successfully switched VPN.
            ClientEvents.record(context,"Предупреждение","Не удалось показать уведомление автопереключения. Подключение не изменено.")
        }
    }
}

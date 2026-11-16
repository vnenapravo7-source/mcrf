package org.mcrf.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

internal object AppFreezeRecoveryNotice {
    private const val CHANNEL="app-freeze-recovery"
    private const val ID=314
    private var lastCount=-1

    fun update(context:Context,count:Int){
        if(lastCount==count)return
        lastCount=count
        if(count==0){
            context.getSystemService(NotificationManager::class.java).cancel(CHANNEL,ID)
            return
        }
        ClientEvents.record(context,"Заморозка приложений","Shizuku недоступен. Остаются замороженными: $count.")
        runCatching {
            val manager=context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,"Восстановление приложений",NotificationManager.IMPORTANCE_DEFAULT))
            if(!manager.areNotificationsEnabled())return
            val open=PendingIntent.getActivity(context,ID,Intent(context,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE)
            manager.notify(CHANNEL,ID,Notification.Builder(context,CHANNEL)
                .setSmallIcon(R.drawable.status_icon)
                .setContentTitle("Shizuku недоступен")
                .setContentText("Заморожено приложений: $count. Откройте MCRF для восстановления.")
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build())
        }
    }
}

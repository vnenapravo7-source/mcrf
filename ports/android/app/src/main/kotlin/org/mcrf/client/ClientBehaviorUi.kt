package org.mcrf.client

import android.app.ActivityManager
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Switch

object ClientBehaviorUi {
    fun build(activity:MainActivity):LinearLayout=with(activity){
        var value=ClientBehaviorStore.load(this)
        card().apply {
            addView(text("Поведение подключения",18,Loft.INK),space(0,12))
            fun toggle(label:String,explanation:String,selected:Boolean,change:(Boolean)->ClientBehaviorSettings){
                val control=Switch(this@with).apply {text=label;tune(this);isChecked=selected}
                addView(control,space(8,4));addView(text(explanation,12,Loft.MUTED),space(0,10))
                var restoring=false
                control.setOnCheckedChangeListener {_,checked->
                    if(restoring)return@setOnCheckedChangeListener
                    val next=change(checked)
                    try{ClientBehaviorStore.save(this@with,next);value=next;applyRecents(this@with)}
                    catch(_:Exception){restoring=true;control.isChecked=!checked;restoring=false;alert("Настройки","Не удалось сохранить изменение. Предыдущие настройки сохранены.")}
                }
            }
            fun group(title:String){divider(this,12,10);addView(text(title,15,Loft.INK).apply {setTypeface(null,android.graphics.Typeface.BOLD);setAccessibilityHeading(true)},space(0,8))}
            group("Запуск и смена сети")
            toggle("Восстанавливать подключение","По умолчанию включено, в том числе после первой установки. При запуске MCRF восстанавливает ранее работавшее подключение, только если есть профиль и разрешение Android. После ручной остановки VPN сам не включается.",value.reconnectOnLaunch){value.copy(reconnectOnLaunch=it)}
            toggle("Обновлять соединения при смене сети","Пересоздать исходящие соединения при переходе между Wi-Fi и мобильной сетью. Настройки маршрутов сохраняются.",value.resetOnNetworkChange){value.copy(resetOnNetworkChange=it)}
            toggle("Обновлять соединения при пробуждении","После включения экрана обновить сетевые соединения работающего ядра. Постоянное удержание процессора не используется.",value.resetOnWake){value.copy(resetOnWake=it)}
            group("Поведение в Android")
            toggle("Скрывать из недавних во время работы","Когда окно уходит в фон, закрывает только окно MCRF и убирает его карточку. Работающий VPN и уведомление сохраняются. Окно можно снова открыть значком; запущенные из приложения системные запросы, выбор файлов и экраны MCRF не прерываются.",value.hideRecents){value.copy(hideRecents=it)}
            addView(button("Системные настройки VPN",false).apply {setOnClickListener {
                try{startActivity(Intent(Settings.ACTION_VPN_SETTINGS))}catch(_:Exception){alert("VPN","Откройте настройки Android → Подключения → VPN → MCRF.")}
            }},space(6,0))
            addView(text(if(VpnService.prepare(this@with)==null)"Разрешение VPN предоставлено. Постоянный VPN и блокировка без VPN управляются самим Android."else"Сначала подключите VPN и подтвердите системный запрос. Постоянный VPN настраивается самим Android.",12,Loft.MUTED),space(8,0))
            addView(text("Для переключения между MCRF и другими VPN оставьте «Постоянный VPN» выключенным. Если закрепить MCRF, Android не позволит другому приложению получить VPN-разрешение. MCRF не включает эту настройку автоматически; при передаче VPN другому приложению автоподключение MCRF прекращается.",12,Loft.MUTED),space(8,8))
            group("Работа в фоне и уведомления")
            val unrestricted=getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            addView(button(if(unrestricted)"Работа в фоне · без ограничений"else"Разрешить работу в фоне",false).apply {tag="background-settings";setOnClickListener {openBackgroundSettings()}},space(0,8))
            addView(text("Откроется системная настройка батареи. Постоянное удержание процессора не используется; Android всё равно может остановить приложение при нехватке ресурсов.",12,Loft.MUTED),space(0,12))
            addView(button("Уведомления MCRF",false).apply {tag="notification-settings";setOnClickListener {requestNotificationAccess()}},space(0,8))
            addView(text("Статус соединения и остановка VPN доступны из закреплённого уведомления. Фоновые обновления подписок уведомляют без звука.",12,Loft.MUTED))
        }
    }
    fun applyRecents(context:android.content.Context){
        val excluded=try{ClientBehaviorStore.load(context).hideRecents}catch(_:Exception){return}
        try{context.getSystemService(ActivityManager::class.java).appTasks.forEach {task->
            // appTasks already belongs to this application. baseActivity may be null
            // for a background/excluded task; filtering it silently missed that task.
            try{task.setExcludeFromRecents(excluded)}catch(_:SecurityException){}
        }}catch(_:SecurityException){}
    }
}

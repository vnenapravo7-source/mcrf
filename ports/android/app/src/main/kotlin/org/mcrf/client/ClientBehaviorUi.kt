package org.mcrf.client

import android.app.ActivityManager
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Switch

object ClientBehaviorUi {
    /** Reusable Beta seam; UsageFeaturesUi owns placement, not this editor.
     * Viewing never changes the selected mode/threshold or Android power saver. */
    fun energyView(activity:MainActivity):LinearLayout=
        SettingsSectionUi.build(activity,"beta-energy-saving","Энергосбережение MCRF"){BsWifiUi.screenOff(activity)}
    fun showEnergy(activity:MainActivity) {
        val content=energyView(activity).apply {
            setPadding(activity.dp(16),activity.dp(4),activity.dp(16),activity.dp(8))
        }
        ThemedDialogBuilder(activity,.75f)
            .setView(CompactUi.scroll(activity,content)).setPositiveButton("Закрыть",null).show()
    }
    fun killSwitch(activity:MainActivity)=build(activity,protectionOnly=true)
    fun build(activity:MainActivity,protectionOnly:Boolean=false):LinearLayout=with(activity){
        var value=ClientBehaviorStore.load(this)
        (if(protectionOnly)LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}else card()).apply {
            tag=if(protectionOnly)"vpn-kill-switch"else"connection-behavior"
            if(!protectionOnly)addView(text("Поведение подключения",18,Loft.INK),space(0,12))
            fun toggle(label:String,explanation:String,selected:Boolean,change:(Boolean)->ClientBehaviorSettings){
                val control=Switch(this@with).apply {text=label;tune(this);isChecked=selected}
                addView(control,space(8,4))
                addView(SettingsHelp.view(this@with,"Как работает",explanation,protectionOnly),space(0,10))
                var restoring=false
                control.setOnCheckedChangeListener {_,checked->
                    if(restoring)return@setOnCheckedChangeListener
                    try{
                        value=synchronized(ProfileStore.storageLock) {
                            val edited=change(checked)
                            val latest=ClientBehaviorStore.load(this@with)
                            val next=ClientBehaviorSettings.fromJson(RoutingDefaultsEdits.merge(latest.json(),value.json(),edited.json()))
                            ClientBehaviorStore.save(this@with,next);next
                        }
                        applyRecents(this@with)
                    }
                    catch(_:Exception){restoring=true;control.isChecked=!checked;restoring=false;alert("Настройки","Не удалось сохранить изменение. Предыдущие настройки сохранены.")}
                }
            }
            fun group(title:String){divider(this,12,10);addView(text(title,15,Loft.INK).apply {setTypeface(null,android.graphics.Typeface.BOLD);setAccessibilityHeading(true)},space(0,8))}
            if(!protectionOnly) {
            group("Запуск и смена сети")
            toggle("Восстанавливать подключение","Повторяет подключение после обрыва и при запуске MCRF. Ручная остановка отменяет повторы. Ошибки входа нужно исправить вручную.",value.recoveryEnabled){value.withRecovery(it)}
            }
            if(protectionOnly) {
            toggle("Kill switch","Блокирует трафик при обрыве, пока работает VPN-служба MCRF. Блокировка снимается подключением или ручным отключением.\n\nПосле остановки службы защита не действует. Постоянную блокировку задаёт Android: «Блокировать без VPN».",value.killSwitchBeta){value.copy(killSwitchBeta=it)}
            addView(UnstableNetworkUi.row(this@with),space(0,12))
            }
            if(!protectionOnly) {
            toggle("Обновлять соединения при смене сети","Пересоздаёт соединения при переходе между Wi-Fi и мобильной сетью. Маршруты сохраняются.",value.resetOnNetworkChange){value.copy(resetOnNetworkChange=it)}
            toggle("Проверять туннель после пробуждения","После разблокировки проверяет связь и при необходимости восстанавливает VPN. При выключенном экране не опрашивает сеть.",value.resetOnWake){value.copy(resetOnWake=it)}
            group("Поведение в Android")
            toggle("Скрывать из недавних во время работы","Убирает карточку MCRF при уходе в фон. VPN продолжает работать; открыть приложение можно его значком.",value.hideRecents){value.copy(hideRecents=it)}
            addView(button("Системные настройки VPN",false).apply {setOnClickListener {
                try{startActivity(Intent(Settings.ACTION_VPN_SETTINGS))}catch(_:Exception){alert("VPN","Откройте настройки Android → Подключения → VPN → MCRF.")}
            }},space(6,0))
            addView(text(if(VpnService.prepare(this@with)==null)"Разрешение VPN предоставлено. Постоянный VPN и блокировка без VPN управляются самим Android."else"Сначала подключите VPN и подтвердите системный запрос. Постоянный VPN настраивается самим Android.",12,Loft.MUTED),space(8,0))
            addView(SettingsHelp.view(this@with,"Другие VPN","Для смены VPN оставьте «Постоянный VPN» выключенным. Передача VPN другому приложению отменяет автоподключение MCRF.",true),space(8,8))
            group("Работа в фоне и уведомления")
            addView(SettingsSectionUi.build(this@with,"bs-screen-off","Энергосбережение · Beta") {BsWifiUi.screenOff(this@with)},space(0,8))
            val unrestricted=getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            addView(button(if(unrestricted)"Работа в фоне · без ограничений"else"Разрешить работу в фоне",false).apply {tag="background-settings";setOnClickListener {openBackgroundSettings()}},space(0,8))
            addView(SettingsHelp.view(this@with,"Разрешение Android","Снимает ограничения работы в фоне. При нехватке ресурсов Android всё равно может остановить приложение."),space(0,12))
            addView(button("Уведомления MCRF",false).apply {tag="notification-settings";setOnClickListener {requestNotificationAccess()}},space(0,8))
            addView(text("Статус соединения и остановка VPN доступны из закреплённого уведомления. Фоновые обновления подписок уведомляют без звука.",12,Loft.MUTED))
            }
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

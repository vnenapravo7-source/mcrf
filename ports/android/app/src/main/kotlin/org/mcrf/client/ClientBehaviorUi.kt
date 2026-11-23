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
        fun LinearLayout.toggle(label:String,explanation:String,selected:Boolean,change:(Boolean)->ClientBehaviorSettings){
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
                    if(!TvUi.enabled(this@with))applyRecents(this@with)
                }
                catch(_:Exception){restoring=true;control.isChecked=!checked;restoring=false;alert("Настройки","Не удалось сохранить изменение. Предыдущие настройки сохранены.")}
            }
        }
        if(protectionOnly)return@with LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            tag="vpn-kill-switch"
            toggle("Kill switch","Блокирует трафик при обрыве, пока работает VPN-служба MCRF. Блокировка снимается подключением или ручным отключением.\n\nПосле остановки службы защита не действует. Постоянную блокировку задаёт Android: «Блокировать без VPN».",value.killSwitchBeta){value.copy(killSwitchBeta=it)}
            addView(UnstableNetworkUi.row(this@with),space(0,12))
        }
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            tag="connection-behavior"
            fun section(title:String,key:String,content:LinearLayout.()->Unit) {
                addView(card().apply {
                    tag=key
                    addView(text(title,17,Loft.INK).apply {isAccessibilityHeading=true},space(0,10))
                    content()
                },space(0,12))
            }
            addView(card().apply {
                tag="ru-app-tunnel-notice-settings"
                addView(Switch(this@with).apply {
                    text="Предупреждение RuApp"
                    tune(this)
                    isChecked=RuAppTunnelNotice.enabled(this@with)
                    setOnCheckedChangeListener {_,checked->
                        RuAppTunnelNotice.setEnabled(this@with,checked)
                        if(checked&&!RuAppTunnelNotice.notificationsAllowed(this@with))RuAppTunnelNotice.requestNotificationAccess(this@with)
                    }
                },space(0,4))
                addView(text("Сообщает, когда трафик российских приложений или сайтов идёт через VPN.",12,Loft.MUTED))
                if(!RuAppTunnelNotice.notificationsAllowed(this@with)){
                    addView(text("Android не разрешает уведомления MCRF. Без разрешения предупреждение RuApp не появится.",12,Loft.MUTED),space(8,4))
                    addView(button("Разрешить уведомления",false).apply {
                        tag="ru-app-notification-permission";setOnClickListener {RuAppTunnelNotice.requestNotificationAccess(this@with)}
                    },space(4,0))
                }
            },space(0,12))
            section("Запуск и смена сети","connection-behavior-network") {
                if (TvUi.enabled(this@with)) {
                    addView(android.widget.Switch(this@with).apply {
                        text = "Открывать MCRF при запуске телевизора"
                        tune(this)
                        isChecked = prefs.getBoolean(TvBootReceiver.ENABLED, false)
                        setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(TvBootReceiver.ENABLED, checked).apply() }
                    }, space(0, 8))
                    addView(text("Автозапуск срабатывает после загрузки Android. Для открытия окна может понадобиться системное разрешение «Поверх других приложений».", 12, Loft.MUTED), space(0, 8))
                    if (android.os.Build.MANUFACTURER.contains("TCL", true)) {
                        addView(text("На TCL также разрешите автозапуск MCRF в настройках телевизора.", 12, Loft.MUTED), space(0, 8))
                    }
                    if (!Settings.canDrawOverlays(this@with)) {
                        addView(button("Разрешение для автозапуска", false).apply {
                            setOnClickListener {
                                try {
                                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:" + packageName)))
                                } catch (_: android.content.ActivityNotFoundException) {
                                    alert("Автозапуск", "Откройте системные настройки телевизора и найдите разрешение «Поверх других приложений» для MCRF.")
                                }
                            }
                        }, space(0, 8))
                    }
                }
                toggle("Восстанавливать подключение","Повторяет подключение после обрыва и при запуске MCRF. Ручная остановка отменяет повторы. Ошибки входа нужно исправить вручную.",value.recoveryEnabled){value.withRecovery(it)}
                toggle("Обновлять соединения при смене сети","Пересоздаёт соединения при переходе между Wi-Fi и мобильной сетью. Маршруты сохраняются.",value.resetOnNetworkChange){value.copy(resetOnNetworkChange=it)}
                toggle("Проверять туннель после пробуждения","После разблокировки проверяет связь и при необходимости восстанавливает VPN. При выключенном экране не опрашивает сеть.",value.resetOnWake){value.copy(resetOnWake=it)}
            }
            section("VPN в Android","connection-behavior-android") {
                if(!TvUi.enabled(this@with))toggle("Скрывать из недавних во время работы","Убирает карточку MCRF при уходе в фон. VPN продолжает работать; открыть приложение можно его значком.",value.hideRecents){value.copy(hideRecents=it)}
                addView(button("Системные настройки VPN",false).apply {setOnClickListener {
                    try{startActivity(Intent(Settings.ACTION_VPN_SETTINGS))}catch(_:Exception){alert("VPN","Откройте настройки Android → Подключения → VPN → MCRF.")}
                }},space(6,0))
                addView(text(if(VpnService.prepare(this@with)==null)"Разрешение VPN предоставлено. Постоянный VPN и блокировка без VPN управляются самим Android."else"Сначала подключите VPN и подтвердите системный запрос. Постоянный VPN настраивается самим Android.",12,Loft.MUTED),space(8,0))
                addView(SettingsHelp.view(this@with,"Другие VPN","Для смены VPN оставьте «Постоянный VPN» выключенным. Передача VPN другому приложению отменяет автоподключение MCRF.",true),space(8,8))
            }
            section("Работа в фоне","connection-behavior-background") {
                if(!TvUi.enabled(this@with))addView(SettingsSectionUi.build(this@with,"bs-screen-off","Энергосбережение · Beta") {BsWifiUi.screenOff(this@with)},space(0,8))
                val unrestricted=getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                addView(button(if(unrestricted)"Работа в фоне · без ограничений"else"Разрешить работу в фоне",false).apply {tag="background-settings";setOnClickListener {openBackgroundSettings()}},space(0,8))
                addView(SettingsHelp.view(this@with,"Разрешение Android","Снимает ограничения работы в фоне. При нехватке ресурсов Android всё равно может остановить приложение."),space(0,12))
            }
            section("Уведомления","connection-behavior-notifications") {
                addView(button("Уведомления MCRF",false).apply {tag="notification-settings";setOnClickListener {requestNotificationAccess()}},space(0,8))
                addView(text("Статус соединения и остановка VPN доступны из закреплённого уведомления. Фоновые обновления подписок уведомляют без звука.",12,Loft.MUTED),space(0,12))
                addView(text("Статистика подключения в уведомлении",14,Loft.INK).apply {isAccessibilityHeading=true},space(0,8))
                addView(NotificationTrafficUi.view(this@with))
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

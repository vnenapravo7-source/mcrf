package org.mcrf.client

import android.Manifest
import android.provider.Settings
import android.content.Intent
import android.widget.Switch
import android.widget.LinearLayout
import android.view.View

internal object BsWifiUi {
    private val energyWorker=java.util.concurrent.Executors.newSingleThreadExecutor { task->Thread(task,"energy-settings").apply {isDaemon=true} }
    const val PERMISSION_REQUEST=41
    /** An explicit battery opt-in activates AUTO; changing mode alone retains its saved options. */
    internal fun batteryOptIn(value:BsScreenOffSettings,enabled:Boolean):BsScreenOffSettings = value.copy(
        mode=if(enabled)"auto" else value.mode,
        batteryThresholdPercent=if(enabled)value.batteryThresholdPercent?:BsScreenOffSettings.DEFAULT_THRESHOLD else null,
        batteryWhileCharging=enabled&&value.batteryWhileCharging)
    fun view(activity:MainActivity):View=with(activity) {
        var value=BsWifiStore.load(this)
        card().apply {
            addView(text("БС и Wi-Fi",18,Loft.INK),space(0,12))
            fun save(next:BsWifiSettings){BsWifiStore.save(this@with,next);value=next;McrfVpnService.refreshBsEnvironment(this@with)}
            fun toggle(label:String,checked:Boolean,change:(Boolean)->BsWifiSettings) {
                val control=Switch(this@with).apply {text=label;tune(this);isChecked=checked}
                addView(control,space(0,8))
                var restoring=false
                control.setOnCheckedChangeListener {_,enabled->
                    if(restoring)return@setOnCheckedChangeListener
                    fun restore(){restoring=true;control.isChecked=false;restoring=false}
                    if(enabled)ThemedDialogBuilder(this@with).setTitle("Отключать БС в этих сетях?")
                        .setMessage("Системный VPN БС будет отключён, трафик пойдёт через Wi-Fi напрямую, а его правила VPN перестанут действовать. После выхода из этих сетей MCRF попробует вернуть то же подключение. Обычный VPN и режим локального HTTP/SOCKS5-прокси настройка не меняет. Доверяйте только знакомым сетям: имя Wi-Fi можно подделать.")
                        .setNegativeButton("Отмена"){_,_->restore()}.setOnCancelListener {restore()}
                        .setPositiveButton("Включить"){_,_->try{save(change(true))}catch(_:Exception){restore();alert("БС и Wi-Fi","Не удалось сохранить настройку.")}}.show()
                    else try{save(change(false))}catch(_:Exception){restoring=true;control.isChecked=true;restoring=false;alert("БС и Wi-Fi","Не удалось сохранить настройку.")}
                }
            }
            toggle("Автопауза БС на любом Wi-Fi",value.pauseOnWifi){value.copy(pauseOnWifi=it)}
            toggle("Автопауза в доверенных сетях",value.trustedEnabled){value.copy(trustedEnabled=it)}
            addView(text("Автопауза относится к системному VPN БС. Локальный HTTP/SOCKS5-прокси продолжает работу: его отключение само по себе не переключает приложения на прямой доступ.",12,Loft.MUTED),space(0,12))
            addView(button("Доверенные сети · ${value.trustedSsids.size}",false).apply {setOnClickListener {
                val input=editor(value.trustedSsids.joinToString("\n")).apply {minLines=3}
                val dialog=ThemedDialogBuilder(this@with).setTitle("Имена доверенных Wi-Fi").setMessage("Одно точное имя на строку, до 32 сетей. Регистр и пробелы внутри имени учитываются. Пустой список не доверяет ни одной сети.")
                    .setView(input).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).show()
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val names=input.text.toString().lines().filter(String::isNotBlank)
                    try{save(value.copy(trustedSsids=names));text="Доверенные сети · ${names.size}";dialog.dismiss()}
                    catch(error:Exception){input.error=error.message}
                }
            }},space(0,8))
            addView(text("Android относит имя Wi-Fi к данным местоположения: нужны точное местоположение и включённая геолокация. Для распознавания новых сетей в фоне может потребоваться «Разрешать всегда» в настройках Android. Без доступного имени сеть не считается доверенной; автопауза на любом Wi-Fi работает без разрешения. Координаты не считываются, имена не отправляются на сервер и хранятся зашифрованно.",12,Loft.MUTED),space(0,12))
            addView(button("Разрешить определение имени Wi-Fi",false).apply {setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION),PERMISSION_REQUEST)
            }},space(0,8))
            addView(button("Настройки геолокации Android",false).apply {setOnClickListener {startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))}},space(0,8))
            addView(button("Доверенные Wi-Fi в фоне · разрешения Android",false).apply {setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
            }},space(0,8))
        }
    }

    /** Reusable energy editor: background settings and Beta use the same saved
     * section. Merely opening it never enables AUTO or a battery threshold. */
    fun screenOff(activity:MainActivity):LinearLayout=with(activity) {
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;tag="bs-screen-off-settings"
            val heading=LinearLayout(this@with).apply {orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
            heading.addView(text("Энергосбережение · Beta",15,Loft.INK).apply {setAccessibilityHeading(true)},LinearLayout.LayoutParams(0,-2,1f))
            heading.addView(iconButton("info","Как работает Энергосбережение · Beta").apply {
                tag="bs-screen-off-help"
                setOnClickListener {alert("Энергосбережение · Beta",
                    "Выкл — обычная фоновая работа без удержания CPU.\n\nУдерживать CPU — удерживать процессор для активного подключения независимо от энергосбережения Android.\n\nАвто — освобождать CPU при энергосбережении Android. Дополнительно можно включить порог батареи MCRF: при уровне не выше выбранного CPU освобождается, выше — удерживается снова. Без порога Авто работает по-прежнему.\n\nПо умолчанию порог не применяется на зарядке; это можно включить отдельно. Если уровень батареи недоступен, порог не применяется. При неизвестном состоянии зарядки порог действует только с разрешением работы на зарядке. Энергосбережение Android учитывается независимо.\n\nМеняется только удержание CPU приложением MCRF, не системное энергосбережение Android. Wi-Fi lock сохраняется. CPU удерживается только при активном подключении и выключенном экране. Остановка, пауза Wi-Fi и включение экрана снимают удержание. Без удержания возможны задержки сообщений и загрузок; ограничения Android и прошивки сохраняются.")}
            },LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(heading,space(0,8))
            var screen=BsScreenOffSettings()
            var ready=false
            var saving=false
            val modes=BsScreenOffSettings.MODES
            val modeLabels=listOf("Выкл · обычная работа","Удерживать CPU","Авто · энергосбережение")
            val mode=button("",false).apply {
                tag="bs-screen-off-mode"
                gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                isSingleLine=false;maxLines=3
            }
            addView(mode,space(0,8))
            fun summary(value:BsScreenOffSettings)=when(value.mode) {
                "auto"->if(value.batteryThresholdPercent==null)"Авто по энергосбережению Android. Порог батареи MCRF не включён. Удержание Wi-Fi сохраняется."
                    else "Освобождает CPU при батарее ≤ ${value.batteryThresholdPercent}%${if(value.batteryWhileCharging)" в том числе на зарядке"else" без зарядки"} или при энергосбережении Android. Wi-Fi lock сохраняется."
                "stable"->"Удерживает CPU при активном подключении и выключенном экране. Увеличивает расход батареи."
                else->"Без удержания CPU. При выключенном экране возможны задержки сообщений и загрузок."
            }
            val description=text(summary(screen),12,Loft.MUTED).apply {tag="bs-screen-off-summary"}
            addView(description,space(0,12))
            val battery=Switch(this@with).apply {text="По заряду батареи";tag="bs-energy-battery-enabled";tune(this)}
            val threshold=button("",false).apply {tag="bs-energy-battery-threshold";minWidth=0;minimumWidth=0}
            addView(LinearLayout(this@with).apply {
                gravity=android.view.Gravity.CENTER_VERTICAL
                addView(battery,LinearLayout.LayoutParams(0,-2,1f))
                addView(threshold,LinearLayout.LayoutParams(dp(88),dp(48)).apply {marginStart=dp(8)})
            },space(0,8))
            val charging=Switch(this@with).apply {text="Также на зарядке";tag="bs-energy-battery-charging";tune(this)}
            addView(charging,space(0,8))
            addView(text("Для любого активного подключения при выключенном экране. Включение порога выбирает «Авто». Выбор другого режима сохраняет порог. Подробности — в ⓘ.",12,Loft.MUTED),space(0,8))
            var rendering=false
            fun render() {
                rendering=true
                mode.text="${modeLabels[modes.indexOf(screen.mode)]} ▾"
                mode.contentDescription="Режим энергосбережения · ${modeLabels[modes.indexOf(screen.mode)]}"
                description.text=summary(screen)
                mode.isEnabled=ready&&!saving
                battery.isChecked=screen.watchesBattery;battery.isEnabled=ready&&!saving
                threshold.text="${screen.batteryThresholdPercent?:BsScreenOffSettings.DEFAULT_THRESHOLD}% ▾"
                threshold.isEnabled=ready&&!saving&&screen.watchesBattery
                threshold.visibility=View.VISIBLE
                charging.isChecked=screen.batteryWhileCharging;charging.isEnabled=ready&&!saving&&screen.watchesBattery
                charging.visibility=if(screen.watchesBattery)View.VISIBLE else View.GONE
                rendering=false
            }
            fun save(change:(BsScreenOffSettings)->BsScreenOffSettings):Boolean {
                if(!ready||saving)return false
                saving=true;render()
                energyWorker.execute {
                    val result=runCatching {synchronized(ProfileStore.storageLock) {
                        val latest=BsScreenOffSettings.load(this@with)
                        change(latest).also {if(it!=latest)BsScreenOffSettings.save(this@with,it)}
                    }}
                    // Service refresh can read encrypted storage too; keep it off the UI thread.
                    if(result.isSuccess)runCatching {McrfVpnService.refreshPowerPolicy(this@with)}
                    runOnUiThread {
                        saving=false
                        result.getOrNull()?.let {screen=it}
                        if(!isDestroyed&&!isFinishing) {
                            render()
                            if(result.isFailure)alert("Энергосбережение","Не удалось сохранить настройку. Предыдущие значения сохранены.")
                        }
                    }
                }
                return true
            }
            render()
            energyWorker.execute {
                val loaded=runCatching {BsScreenOffSettings.load(this@with)}
                runOnUiThread {if(!isDestroyed&&!isFinishing) {
                    loaded.getOrNull()?.let {screen=it;ready=true};render()
                    if(loaded.isFailure)description.text="Не удалось прочитать настройки. Откройте раздел повторно."
                }}
            }
            mode.setOnClickListener {
                val items=modeLabels.mapIndexed {index,label->DropdownPopup.Item(label,when(modes[index]) {
                    "stable"->"Удерживает CPU при активном подключении и выключенном экране. Расход батареи выше."
                    "auto"->"Освобождает CPU при энергосбережении Android или включённом пороге батареи. Wi-Fi lock сохраняется."
                    else->"Не удерживает CPU. При выключенном экране возможны задержки."
                })}
                DropdownPopup.show(this@with,mode,"Энергосбережение MCRF",items,modes.indexOf(screen.mode)) {position->
                    modes.getOrNull(position)?.let {next->save {it.copy(mode=next)}}
                }
            }
            battery.setOnCheckedChangeListener {_,enabled->if(!rendering)save {batteryOptIn(it,enabled)}}
            charging.setOnCheckedChangeListener {_,enabled->if(!rendering)save {
                require(it.watchesBattery);it.copy(batteryWhileCharging=enabled)
            }}
            threshold.setOnClickListener {
                val presets=BsScreenOffSettings.THRESHOLD_PRESETS
                DropdownPopup.show(this@with,threshold,"Порог батареи",
                    presets.map {DropdownPopup.Item("$it%")},presets.indexOf(screen.batteryThresholdPercent)) {index->
                    presets.getOrNull(index)?.let {percent->save {require(it.watchesBattery);it.copy(batteryThresholdPercent=percent)}}
                }
            }
        }
    }
}

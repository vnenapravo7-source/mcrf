package org.mcrf.client

import android.Manifest
import android.provider.Settings
import android.content.Intent
import android.widget.Switch
import android.view.View

internal object BsWifiUi {
    const val PERMISSION_REQUEST=41
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
            addView(text("Работа при выключенном экране",15,Loft.INK),space(12,8))
            var screen=BsScreenOffSettings.load(this@with)
            val modes=listOf("balanced","stable")
            val mode=picker(arrayOf("Сбалансированно","Удерживать CPU для БС"),modes.indexOf(screen.mode))
            addView(mode,space(0,8))
            addView(text("Сбалансированно — обычная фоновая работа без удержания CPU. Удержание CPU — только при активном БС и выключенном экране; увеличивает расход батареи. При остановке, паузе Wi-Fi или включении экрана удержание снимается. Сетевые ограничения Android и прошивки всё равно действуют.",12,Loft.MUTED),space(0,8))
            mode.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?)=Unit
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long) {
                    if(screen.mode==modes[position])return
                    try{BsScreenOffSettings.save(this@with,BsScreenOffSettings(modes[position]));screen=BsScreenOffSettings(modes[position]);McrfVpnService.refreshBsEnvironment(this@with)}
                    catch(_:Exception){mode.setSelection(modes.indexOf(screen.mode));alert("Работа в фоне","Не удалось сохранить настройку.")}
                }
            }
        }
    }
}

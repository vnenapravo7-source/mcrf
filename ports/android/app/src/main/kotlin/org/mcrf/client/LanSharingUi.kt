package org.mcrf.client

import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.util.concurrent.Executors

/** Embedded under the local proxy. This is authenticated sharing, not hotspot-wide VPN. */
class LanSharingUi(private val activity:MainActivity) {
    fun build():LinearLayout=with(activity){
        val store=ClientTunStore(this)
        val form=CoreTuningForm(this,store.load().json()){store.save(ClientTunSettings.fromJson(it))}
            .preserving {store.load().json()}.embedded()
        form.view.tag="lan-sharing-page"
        form.section("Прокси для других устройств")
        form.hint("HTTP/SOCKS5 для устройств в той же Wi-Fi-сети или точке доступа телефона. Это прокси для настроенных программ, не VPN всей точки доступа.")
        form.toggle("shareLan","Разрешить подключения других устройств","Использует общую авторизацию выше. Включайте только в доверенной сети; применяется при новом подключении.")
        form.field("lanPort","Порт для других устройств","HTTP и SOCKS5 используют этот общий порт, 1024–65535. Он не зависит от случайного локального порта телефона.",true)
        form.view.addView(button("Настройка точки доступа",false).apply {tag="lan-hotspot-settings";setOnClickListener {
            try{startActivity(Intent("android.settings.TETHER_SETTINGS"))}catch(_:Exception){try{startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))}catch(_:Exception){alert("Точка доступа","Откройте настройки точки доступа Android вручную.")}}
        }},space(0,8))
        form.hint("Адрес телефона в Wi-Fi / точке доступа")
        val addresses=text("Определяем адрес…",13,Loft.INK).apply {tag="lan-sharing-addresses";setTextIsSelectable(true)}
        var found=emptyList<String>()
        val copy=iconButton("copy","Скопировать адрес телефона").apply {tag="lan-sharing-copy-addresses";isEnabled=false;setOnClickListener {
            if(found.isNotEmpty()){getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Адрес телефона",found.joinToString("\n")));toast("Адрес скопирован")}
        }}
        val executor=Executors.newSingleThreadExecutor()
        fun refresh(){if(executor.isShutdown)return;executor.execute {
            val next=try{LanSharingAddresses.discover()}catch(_:Exception){emptyList()}
            runOnUiThread {if(!isDestroyed&&form.view.isAttachedToWindow){found=next;copy.isEnabled=next.isNotEmpty();addresses.text=next.joinToString("\n").ifBlank {"Включите Wi-Fi / точку доступа и обновите адрес"}}}
        }}
        form.view.addView(LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL
            addView(addresses,LinearLayout.LayoutParams(0,-2,1f))
            addView(copy,LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(iconButton("refresh","Обновить адрес телефона").apply {tag="lan-refresh-addresses";setOnClickListener {refresh()}},LinearLayout.LayoutParams(dp(48),dp(48)))
        },space(0,8))
        form.view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){refresh()}
            override fun onViewDetachedFromWindow(v:View){executor.shutdownNow()}
        })
        form.hint("На компьютере выберите ручной HTTP-прокси (настройки сети Windows) или SOCKS5 (например, Telegram Desktop). Для выхода в интернет MCRF должен оставаться подключённым. Обычное подключение к Wi-Fi-точке доступа само по себе VPN не включает.")
        form.view
    }
}

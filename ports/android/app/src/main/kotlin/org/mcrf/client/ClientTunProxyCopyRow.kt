package org.mcrf.client

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.view.Gravity
import android.widget.LinearLayout

/** One row for the VPN/proxy settings page; credentials and ports use existing stores. */
class ClientTunProxyCopyRow(private val activity: MainActivity) {
    fun view(connected: () -> Boolean, runtimePort: () -> Int): LinearLayout = LinearLayout(activity).apply {
        gravity=Gravity.CENTER_VERTICAL
        listOf("socks5" to "SOCKS5", "http" to "HTTP").forEachIndexed { index,(scheme,label) ->
            addView(activity.button(label,false).apply {
                tag="vpn-proxy-copy-$scheme"
                contentDescription="Копировать ссылку $label прокси"
                setCompoundDrawablesWithIntrinsicBounds(activity.icon("copy",Loft.INK),null,null,null)
                compoundDrawablePadding=activity.dp(8)
                setPadding(activity.dp(12),activity.dp(8),activity.dp(12),activity.dp(8))
                setOnClickListener {
                    val store=ClientTunStore(activity);val settings=store.load()
                    if(!settings.mixedEnabled) { activity.alert("Локальный прокси","Включите локальный HTTP/SOCKS прокси на этой странице.");return@setOnClickListener }
                    val active=connected()
                    val actual=if(active)runtimePort() else settings.mixedPort
                    if((settings.randomPort && !active) || actual !in 1024..65535) {
                        activity.alert("Локальный прокси","Сначала включите подключение: ссылка использует фактический порт работающего прокси.");return@setOnClickListener
                    }
                    val link=LocalProxyConfig.link(scheme,actual,store.auth())
                    val clip=ClipData.newPlainText("MCRF · $label",link)
                    if(Build.VERSION.SDK_INT>=33)clip.description.extras=PersistableBundle().apply {putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true)}
                    activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    activity.toast("Ссылка $label скопирована")
                }
            },LinearLayout.LayoutParams(0,activity.dp(48),1f).apply { if(index==0)marginEnd=activity.dp(8) })
        }
    }
}

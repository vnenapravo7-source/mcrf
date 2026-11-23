package org.mcrf.client

import android.content.Intent
import android.widget.LinearLayout
import android.widget.Switch

internal object VpnOwnerIsolationUi {
    fun view(activity:MainActivity):LinearLayout=with(activity) {
        card().apply {
            tag="routing-vpn-owner-isolation"
            val warning=text("Изоляция ещё не применяется. Обновите движок и переподключите VPN.",13,Loft.INK).apply {
                tag="routing-isolation-upgrade";minHeight=dp(48)
                accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
                setOnClickListener {navigate(ClientSettingsNavigation.UPDATES)}
            }
            fun refresh(){warning.visibility=if(McrfVpnService.vpnOwnerIsolationUpgradePending())android.view.View.VISIBLE else android.view.View.GONE}
            val previousRefresh=activity.pageRefresh
            activity.pageRefresh={previousRefresh?.invoke();refresh()}
            addView(Switch(this@with).apply {
                text="Скрывать VPN от приложений"
                tune(this)
                isChecked=prefs.getBoolean(VpnOwnerIsolation.KEY,VpnOwnerIsolation.DEFAULT_ENABLED)
                setOnCheckedChangeListener {_,enabled->
                    prefs.edit().putBoolean(VpnOwnerIsolation.KEY,enabled).apply()
                    if(McrfVpnService.ownsCurrentTun())try {
                        startService(Intent(this@with,McrfVpnService::class.java)
                            .setAction(McrfVpnService.VPN_OWNER_ISOLATION_CHANGED))
                    }catch(_:IllegalStateException) {
                        toast("Настройка сохранена. Переподключите VPN для применения.")
                    }
                }
            })
            addView(text("Блокирует доступ к туннелю для исключённых приложений. Android по-прежнему показывает наличие VPN.",11,Loft.MUTED),space(0,0))
            refresh();addView(warning,space(0,0))
        }
    }
}

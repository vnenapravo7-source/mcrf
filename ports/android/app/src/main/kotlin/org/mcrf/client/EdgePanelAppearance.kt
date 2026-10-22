package org.mcrf.client

import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.Switch

internal object EdgePanelAppearance {
    val items=linkedMapOf("edgeShowVpn" to "VPN","edgeShowBs" to "БС","edgeShowTelegram" to "Telegram WS",
        "edgeShowRoutes" to "Группа маршрутов","edgeShowAutoBs" to "Автоопределение БС")
    fun shown(prefs:SharedPreferences,key:String)=prefs.getBoolean(key,true)
    fun view(activity:MainActivity)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;tag="edge-appearance-settings"
        addView(activity.text("Боковое меню",18,Loft.INK),activity.space(0,8))
        items.forEach {(key,label)->addView(Switch(activity).apply {
            tag=key;text=label;activity.tune(this);isChecked=shown(activity.prefs,key)
            setOnCheckedChangeListener {_,checked->activity.prefs.edit().putBoolean(key,checked).apply();activity.refreshEdgeMenu()}
        },LinearLayout.LayoutParams(-1,activity.dp(48)))}
    }
}

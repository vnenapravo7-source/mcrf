package org.mcrf.client

import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.Switch

internal object EdgePanelAppearance {
    const val HIDDEN="edgePanelHidden"
    const val PINNED="edgePanelPinned"
    const val ORBIT_COLLAPSED="edgeOrbitCollapsed"
    fun hidden(prefs:SharedPreferences)=prefs.getBoolean(HIDDEN,false)
    fun pinned(prefs:SharedPreferences)=prefs.getBoolean(PINNED,false)
    fun left(prefs:SharedPreferences)=prefs.getBoolean("edgeLeft",false)
    val items=linkedMapOf("edgeShowVpn" to "VPN","edgeShowBs" to "БС","edgeShowTelegram" to "Telegram WS",
        "edgeShowRoutes" to "Группа маршрутов","edgeShowAutoBs" to "Автоопределение БС")
    fun shown(prefs:SharedPreferences,key:String)=prefs.getBoolean(key,true)
    fun view(activity:MainActivity)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;tag="edge-appearance-settings"
        addView(Switch(activity).apply {
            val orbit=activity.orbitEnabled()
            tag=HIDDEN;text=if(orbit)"Свернуть боковую панель" else "Скрыть боковой переключатель";activity.tune(this)
            isChecked=!pinned(activity.prefs)&&if(orbit)activity.prefs.getBoolean(ORBIT_COLLAPSED,false) else hidden(activity.prefs)
            isEnabled=!pinned(activity.prefs)
            setOnCheckedChangeListener {_,checked->
                if(orbit){
                    activity.prefs.edit().putBoolean(ORBIT_COLLAPSED,checked).apply()
                    activity.render()
                }else{
                    if(checked&&activity.prefs.getBoolean(ConnectionControlsDock.HIDDEN,false))activity.setConnectionControlsHidden(false)
                    activity.prefs.edit().putBoolean(HIDDEN,checked).apply()
                    activity.refreshEdgeMenu()
                }
            }
        },LinearLayout.LayoutParams(-1,-2))
        addView(Switch(activity).apply {
            tag=PINNED;text="Закрепить боковую панель";activity.tune(this)
            isChecked=pinned(activity.prefs)
            setOnCheckedChangeListener {_,checked->activity.prefs.edit().putBoolean(PINNED,checked).apply();activity.render()}
        },LinearLayout.LayoutParams(-1,-2))
        if(pinned(activity.prefs))addView(ModeSwitch(activity,left(activity.prefs),java.util.function.Consumer {left->
            activity.prefs.edit().putBoolean("edgeLeft",left).apply()
            activity.render()
        },listOf("Слева","Справа"),listOf("",""),compact=true).apply {
            tag="edge-pinned-side";contentDescription="Сторона закреплённой панели"
        },LinearLayout.LayoutParams(-1,activity.dp(48)))
        items.forEach {(key,label)->addView(Switch(activity).apply {
            tag=key;text=label;activity.tune(this);isChecked=shown(activity.prefs,key)
            setOnCheckedChangeListener {_,checked->activity.prefs.edit().putBoolean(key,checked).apply();activity.refreshEdgeMenu()}
        },LinearLayout.LayoutParams(-1,-2))}
    }
}

package org.mcrf.client

import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.Switch

internal object EdgePanelAppearance {
    const val HIDDEN="edgePanelHidden"
    const val PINNED="edgePanelPinned"
    const val ORBIT_COLLAPSED="edgeOrbitCollapsed"
    const val TELEGRAM="edgeShowTelegram"
    fun hidden(prefs:SharedPreferences)=prefs.getBoolean(HIDDEN,false)
    fun pinned(prefs:SharedPreferences)=prefs.getBoolean(PINNED,HomeUiTheme.mode(prefs).orbit)
    fun left(prefs:SharedPreferences)=prefs.getBoolean("edgeLeft",false)
    val items=linkedMapOf("edgeShowVpn" to "VPN","edgeShowBs" to "БС",TELEGRAM to "TGWS Proxy",
        "edgeShowRoutes" to "Группа маршрутов","edgeShowAutoBs" to "Автоопределение БС")
    fun shown(prefs:SharedPreferences,key:String)=prefs.getBoolean(key,true)
    fun view(activity:MainActivity)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;tag="edge-appearance-settings"
        addView(Switch(activity).apply {
            val orbit=activity.orbitEnabled()
            tag=HIDDEN;text=if(orbit)"Свернуть боковую панель" else "Скрыть боковой переключатель";activity.tune(this)
            isChecked=!pinned(activity.prefs)&&if(orbit)activity.prefs.getBoolean(ORBIT_COLLAPSED,false) else hidden(activity.prefs)
            setOnCheckedChangeListener {_,checked->
                if(!orbit&&checked&&activity.prefs.getBoolean(ConnectionControlsDock.HIDDEN,false))activity.setConnectionControlsHidden(false)
                activity.prefs.edit().apply {
                    putBoolean(if(orbit)ORBIT_COLLAPSED else HIDDEN,checked)
                    if(checked)putBoolean(PINNED,false)
                }.apply()
                activity.render()
            }
        },LinearLayout.LayoutParams(-1,-2))
        addView(Switch(activity).apply {
            tag=PINNED;text="Закрепить боковую панель";activity.tune(this)
            isChecked=pinned(activity.prefs)
            setOnCheckedChangeListener {_,checked->
                activity.prefs.edit().apply {
                    putBoolean(PINNED,checked)
                    if(checked)putBoolean(HIDDEN,false).putBoolean(ORBIT_COLLAPSED,false)
                }.apply()
                if(checked)activity.edgeConnectionState.collapsed=false
                activity.render()
            }
        },LinearLayout.LayoutParams(-1,-2))
        if(pinned(activity.prefs))addView(ModeSwitch(activity,left(activity.prefs),java.util.function.Consumer {left->
            activity.prefs.edit().putBoolean("edgeLeft",left).apply()
            activity.render()
        },listOf("Слева","Справа"),listOf("",""),compact=true).apply {
            tag="edge-pinned-side";contentDescription="Сторона закреплённой панели"
        },LinearLayout.LayoutParams(-1,activity.dp(48)))
        val telegram=UsageFeatures.telegram(UsageFeaturesUi.selected(activity))
        items.filterKeys {it!=TELEGRAM||telegram}.forEach {(key,label)->addView(Switch(activity).apply {
            tag=key;text=label;activity.tune(this);isChecked=shown(activity.prefs,key)
            setOnCheckedChangeListener {_,checked->activity.prefs.edit().putBoolean(key,checked).apply();activity.refreshEdgeMenu()}
        },LinearLayout.LayoutParams(-1,-2))}
        if(activity.orbitEnabled())addView(Switch(activity).apply {
            tag=AppearanceMotion.EYE;text="Антистресс";activity.tune(this)
            isChecked=activity.prefs.getBoolean(AppearanceMotion.EYE,false)
            setOnCheckedChangeListener {_,checked->
                activity.prefs.edit().putBoolean(AppearanceMotion.EYE,checked).apply()
                activity.render()
            }
        },LinearLayout.LayoutParams(-1,-2))
    }
}

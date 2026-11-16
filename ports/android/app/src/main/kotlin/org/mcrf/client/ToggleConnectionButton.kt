package org.mcrf.client

import android.content.res.ColorStateList
import android.widget.Switch

/** Uses the same connection action and lifecycle as the circle; no second VPN state. */
class ToggleConnectionButton(activity:MainActivity,action:()->Unit):Switch(activity) {
    private var connected=false
    init {
        tag="connection-primary-toggle";text="";showText=false
        activity.tune(this)
        switchMinWidth=activity.dp(72);minimumHeight=activity.dp(52)
        thumbTintList=ColorStateList.valueOf(Loft.INK)
        setOnClickListener {isChecked=connected;action()}
    }
    fun state(connected:Boolean,pending:Boolean,enabled:Boolean,running:Boolean,failed:Boolean) {
        this.connected=connected;isChecked=connected;isEnabled=enabled
        trackTintList=ColorStateList.valueOf(if(failed)Loft.ERROR else if(connected)Loft.ACCENT else Loft.MUTED)
        contentDescription=if(running)"Отключить"else"Подключить"
        if(android.os.Build.VERSION.SDK_INT>=30)stateDescription=when {
            pending->"Подключение";connected->"Подключено";failed->"Ошибка подключения";else->"Отключено"
        }
    }
}

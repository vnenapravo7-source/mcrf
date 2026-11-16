package org.mcrf.client

import android.content.Context

/** A reversible gate, never a destructive reset of experimental configuration. */
class SafeModeStore internal constructor(private val read:()->Boolean,private val write:(Boolean)->Unit) {
    constructor(context:Context):this(
        {context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getBoolean(KEY,false)},
        {check(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putBoolean(KEY,it).commit())
            McrfVpnService.safeModeChanged(it)
            ProbeCoordinator.refreshHealthPolicy(context)}
    )
    fun enabled()=read()
    fun save(enabled:Boolean)=write(enabled)
    fun restore()=save(false)
    fun allowsExperimental()=!enabled()
    companion object {
        const val KEY="safeModeBeta"
        const val TITLE="Безопасный режим · Beta"
        const val COPY="Приостанавливает профили интерфейса по условиям сети, замеры здоровья, журнал соединений, автовосстановление и внутренний Kill switch. Уже удерживаемая блокировка трафика MCRF снимается. Работающее обычное VPN-подключение сохраняется. Настройки не удаляются; выключение безопасного режима само не запускает VPN."
        const val RESTORE="Восстановить экспериментальные функции"
    }
}

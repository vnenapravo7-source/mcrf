package org.mcrf.client

/** Explicit experimental preference, separate from normal TUN, routing and automatic selection. */
object CsqttDirectTunSettings {
    const val KEY="csqttDirectTunTest"
    const val DEFAULT=false
    const val TITLE="Прямой TUN · тест"
    const val HELP="Для сравнения скорости с оригинальным CSQTT. Только в этом тестовом подключении трафик идёт напрямую из системного VPN в Rust, без правил, автоопределения БС и автовыбора MCRF; обычные настройки не меняются. Только CSQTT, режим VPN."

    fun selected(@Suppress("UNUSED_PARAMETER") read:(String,Boolean)->Boolean)=false

    data class UiState(val visible:Boolean,val selected:Boolean,val canEnable:Boolean,val canDisable:Boolean,val reason:String?) {
        val available get()=if(selected)canDisable else canEnable
        fun canSet(enabled:Boolean)=if(enabled)canEnable else canDisable
    }

    /** A saved ON is not evidence that the native direct path is available or running. */
    fun state(selected:Boolean,bs:Boolean,vpn:Boolean,csqtt:Boolean,busy:Boolean,unsupportedReason:String?):UiState {
        val reason=when {
            !bs->"Только в разделе БС."
            !vpn->"Только в режиме VPN."
            !csqtt->"Выберите сервер CSQTT."
            busy->"Дождитесь завершения подключения или остановки."
            else->unsupportedReason
        }
        // Disabling a saved opt-in never requires the original mode, server or native capability.
        return UiState(bs,selected,bs&&reason==null,bs&&!busy,reason)
    }
}

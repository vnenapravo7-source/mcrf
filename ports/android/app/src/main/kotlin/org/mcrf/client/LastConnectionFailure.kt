package org.mcrf.client

/** Process-local terminal failure, retained across Service teardown until the next user decision. */
object LastConnectionFailure {
    @Volatile private var value:String?=null
    fun remember(detail:String?) {
        value=ClientEvents.redact(detail).trim().ifBlank {"Подключение не удалось: причина не сообщена."}.take(600)
    }
    fun clear(){value=null}
    fun detail():String?=value
}

package org.mcrf.client

import org.json.JSONObject

/** Only the pinned client's structured, fatal configuration rejection; never raw native logs. */
object CsqttNativeFailure {
    enum class Kind(val explanation:String) {
        PROTOCOL("CSQTT: сервер отклонил версию протокола или число потоков. Проверь версию CSQTT сервера и параметры подключения; старый CSQTT и 2.5 нельзя считать взаимозаменяемыми."),
        ACCESS("CSQTT: сервер отклонил доступ. Проверь пароль, срок действия и привязку подключения к устройству.")
    }
    fun read(line:String):Kind? {
        // The currently shipped qThrone bridge consumes Rust event JSON and
        // emits these fixed sanitized literals before cancelling its session.
        when(line) {
            "[CSQTT] Клиент и сервер CSQTT несовместимы по версии протокола или числу потоков."->return Kind.PROTOCOL
            "[CSQTT] Сервер отказал в авторизации CSQTT. Проверьте пароль и его привязку в панели.",
            "[CSQTT] Пароль CSQTT привязан к другому устройству. Создайте отдельный пароль для этого ПК."->return Kind.ACCESS
        }
        val prefix="__CSQTT_EVENT__|ERROR|"
        if(line.length>8192||!line.startsWith(prefix))return null
        return try {
            val event=JSONObject(line.substring(prefix.length))
            if(event.opt("fatal")!=true||event.optString("code")!="handshake_rejected")return null
            val message=event.optString("message")
            if(message.length>4096||"AUTH_RETRY" in message)return null
            when {
                "FATAL_PROTOCOL:" in message->Kind.PROTOCOL
                "FATAL_AUTH:" in message->Kind.ACCESS
                else->null
            }
        }catch(_:Exception){null}
    }
}

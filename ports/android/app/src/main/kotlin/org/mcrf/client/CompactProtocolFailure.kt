package org.mcrf.client

internal object CompactProtocolFailure {
    private fun cronet(line:String):Int?=line.takeIf {it.startsWith("MCRF_ERROR PROTOCOL_CRONET_")}?.removePrefix("MCRF_ERROR PROTOCOL_CRONET_")
        ?.takeIf {it.matches(Regex("[1-9][0-9]{0,4}"))}?.toIntOrNull()?.takeIf {it in 1..10000}
    fun trustFailure(line:String):Boolean=cronet(line)?.let {it in 200..299}==true||line in setOf(
        "MCRF_ERROR PROTOCOL_SSH_HOST_KEY_REQUIRED","MCRF_ERROR PROTOCOL_SSH_HOST_KEY_MISMATCH",
        "MCRF_ERROR PROTOCOL_TLS_UNKNOWN_AUTHORITY","MCRF_ERROR PROTOCOL_TLS_NAME_MISMATCH","MCRF_ERROR PROTOCOL_TLS_CERTIFICATE_INVALID")
    fun describe(engine:String,line:String):String? {
        if(engine!=CompactProtocolPolicy.KIND||!line.startsWith("MCRF_ERROR ")||line.length>100)return null
        val code=line.removePrefix("MCRF_ERROR ")
        cronet(line)?.let {number->
            val reason=when(number){
                200->"Имя Naive-сервера не совпадает с сертификатом."
                201->"Срок действия сертификата Naive-сервера недопустим."
                202->"Не подтверждён доверенный центр сертификата Naive-сервера."
                in 200..299->"Cronet отклонил сертификат Naive-сервера."
                7,118->"Истекло время соединения Naive/Cronet."
                102->"Соединение с Naive-сервером отклонено."
                105->"Cronet не разрешил адрес Naive-сервера."
                127,338,341,364->"Naive-сервер запросил либо отклонил авторизацию."
                else->"Исходный Cronet сообщил сетевую ошибку; сохранён точный технический код."
            }
            return "$code · $reason"
        }
        val reason=when(code){
            "PROTOCOL_CONFIG_REJECTED"->"Протокольный модуль отклонил закрытый профиль; протокол не подменялся."
            "PROTOCOL_SSH_HOST_KEY_REQUIRED"->"Для SSH нужен корректный открытый ключ сервера host_key."
            "PROTOCOL_SSH_HOST_KEY_MISMATCH"->"Ключ SSH-сервера не совпадает с сохранённым; проверка не отключена."
            "PROTOCOL_SERVER_DNS_REQUIRED"->"Для доменного адреса сервера не передан выбранный DNS."
            "PROTOCOL_TLS_UNKNOWN_AUTHORITY"->"Не удалось подтвердить доверенный центр сертификата сервера."
            "PROTOCOL_TLS_NAME_MISMATCH"->"Имя сервера не совпадает с TLS-сертификатом."
            "PROTOCOL_TLS_CERTIFICATE_INVALID"->"TLS-сертификат сервера недействителен."
            "PROTOCOL_TIMEOUT"->"Истекло время операции исходного протокола."
            "PROTOCOL_DNS_TIMEOUT"->"Истекло время разрешения адреса протокольного сервера."
            "PROTOCOL_DNS_FAILURE"->"Выбранный DNS не смог разрешить адрес протокольного сервера."
            "PROTOCOL_CONNECTION_REFUSED"->"Соединение с протокольным сервером отклонено."
            "PROTOCOL_NETWORK_UNREACHABLE"->"Сеть или адрес протокольного сервера недоступны."
            "PROTOCOL_PHYSICAL_NETWORK_REQUIRED"->"Для протокольного модуля не передана физическая сеть; сеть по умолчанию не подставлялась."
            "PROTOCOL_PHYSICAL_NETWORK_BIND_FAILED"->"Android не разрешил привязать протокольный модуль к выбранной физической сети."
            "PROTOCOL_PEER_CLOSED"->"Удалённая сторона закрыла соединение."
            "PROTOCOL_CANCELLED"->"Операция протокольного модуля отменена."
            "PROTOCOL_INTERNAL_PANIC"->"Внутренняя авария протокольного модуля."
            "PROTOCOL_FAILURE_UNCLASSIFIED"->"Ошибка исходного протокола без подтверждённой категории; отказ авторизации не установлен."
            else->return null
        }
        return "$code · $reason"
    }
}

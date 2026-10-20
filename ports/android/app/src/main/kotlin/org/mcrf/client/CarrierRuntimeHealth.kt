package org.mcrf.client

/** Process ownership/liveness, not an Internet probe. No URLs, native text or secrets. */
object CarrierRuntimeHealth {
    fun failure(kind:String,retiring:Boolean,alive:Boolean,fatal:CsqttNativeFailure.Kind?):String? {
        if(retiring)return null
        if(kind=="csqtt"&&fatal!=null)return fatal.explanation
        if(alive)return null
        val label=when(kind){"csqtt"->"CSQTT";"wdtt"->"WDTT";"qwdtt"->"QWDTT";"olcrtc"->"OLC";"dnstt"->"DNSTT";else->"Дополнительный"}
        return "$label: процесс транспорта завершился. Открой логи и проверь сервер и параметры подключения."
    }
}

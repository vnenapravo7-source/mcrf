package org.mcrf.client

/** Actual protocol workers consume URLs, not the dispatcher's process options.
 * Do not silently apply TLS trust/NTP/mux/fragment defaults only to the helper
 * and pretend that the Steer encryption engine received them. Existing explicit
 * profile fields still pass the strict URI roundtrip; no profile is rewritten. */
internal object SteerRouteTuning {
    fun requireSupported(value:CoreTuningSettings){
        value.validate()
        val unsupported=mutableListOf<String>()
        if(value.certificateStore!="system")unsupported+="хранилище сертификатов"
        if(value.ntpEnabled)unsupported+="NTP протокольного движка"
        if(value.insecure)unsupported+="общий пропуск проверки сертификатов"
        if(value.muxDefault)unsupported+="общий multiplex"
        if(value.fragmentDefault)unsupported+="общая фрагментация TLS"
        if(value.disablePmtu||value.initialPacketSize!=0)unsupported+="общие параметры QUIC/PMTU"
        if(value.fingerprint.isNotEmpty())unsupported+="общий TLS fingerprint"
        if(listOf(value.idleTimeout,value.keepAlive,value.streamWindow,value.connectionWindow).any {it.isNotEmpty()}||value.maxStreams!=0)unsupported+="общие параметры HTTP/2"
        require(unsupported.isEmpty()){"Steer для серверов правил пока не применяет: ${unsupported.joinToString()}. Параметры не отброшены и другой движок автоматически не подставляется."}
        // Admin APIs are owned by the sole dispatcher and do not alter protocol
        // encryption. Disabled companion fields remain stored, never emitted.
    }
}

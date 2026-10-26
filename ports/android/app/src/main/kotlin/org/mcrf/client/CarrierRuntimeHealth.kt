package org.mcrf.client

/** Process ownership/liveness, not an Internet probe. No URLs, native text or secrets. */
object CarrierRuntimeHealth {
    fun failure(kind:String,retiring:Boolean,alive:Boolean,fatal:CsqttNativeFailure.Kind?):String? {
        if(retiring)return null
        if(kind=="csqtt"&&fatal!=null)return fatal.explanation
        if(alive)return null
        val label=when(kind){"csqtt"->"CSQTT";"csqtt25"->"CSQTT 2.5";"wdtt"->"WDTT";"qwdtt"->"QWDTT";"olcrtc"->"OLC";"dnstt"->"DNSTT";VayDnsProfile.KIND->"VayDNS";SlipstreamProfile.KIND->"Slipstream";SstpProfile.KIND->"SSTP";MasterDnsProfile.KIND->"MasterDnsVPN";"warpscout"->"WARP";else->"Дополнительный"}
        return "$label: процесс транспорта завершился."
    }
}

package org.mcrf.client

/** Successful automatic transitions only; never a probe, attempted switch or UI redraw. */
class AutoSectionNotice {
    private var bsSerial=0L
    private var vpnSerial=0L
    fun accept(toWhitelists:Boolean,serial:Long,requestEpoch:Long,currentEpoch:Long,confirmed:Boolean):Boolean {
        if(!confirmed||requestEpoch!=currentEpoch||serial<=0)return false
        val previous=if(toWhitelists)bsSerial else vpnSerial
        if(serial<=previous)return false
        if(toWhitelists)bsSerial=serial else vpnSerial=serial
        return true
    }
}

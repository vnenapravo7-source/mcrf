package org.mcrf.client

/** Manual verification pauses the network budget, with one cumulative bounded allowance. */
class CarrierStartupBudget(networkMillis:Long,now:Long,private val manualLimit:Long=150000) {
    private var networkRemaining=networkMillis
    private var manualRemaining=manualLimit
    private var last=now
    private var waiting=false
    init{require(networkMillis in 1000..180000&&now>=0&&manualLimit in 0..180000)}
    fun canContinue(now:Long,manualPending:Boolean):Boolean {
        require(now>=last)
        val elapsed=now-last
        if(waiting){
            val paused=minOf(elapsed,manualRemaining)
            manualRemaining-=paused
            networkRemaining-=elapsed-paused
        }else networkRemaining-=elapsed
        last=now;waiting=manualPending
        return networkRemaining>0
    }
}

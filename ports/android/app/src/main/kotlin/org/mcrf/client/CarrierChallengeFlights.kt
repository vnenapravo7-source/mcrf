package org.mcrf.client

/** One instance per native child. Only opaque IDs/digests; bounded retired replay history. */
class CarrierChallengeFlights(private val historyLimit:Int=128) {
    init {require(historyLimit in 1..1024)}
    class CapacityExceeded:IllegalStateException("CAPTCHA pending limit")
    class Ticket internal constructor(internal val key:String) {
        override fun toString()="ChallengeFlight(redacted)"
    }
    private val active=mutableMapOf<String,Ticket>()
    private val retired=linkedSetOf<String>()
    @Synchronized fun claim(key:String):Ticket? {
        require(key.length in 1..256)
        if(key in active||key in retired)return null
        // Same global pending bound as the host registry, without unbounded native spam.
        if(active.size>=32)throw CapacityExceeded()
        return Ticket(key).also {active[key]=it}
    }
    @Synchronized fun retire(ticket:Ticket) {
        if(active[ticket.key]!==ticket)return
        active.remove(ticket.key);retired.add(ticket.key)
        while(retired.size>historyLimit)retired.remove(retired.first())
    }
}

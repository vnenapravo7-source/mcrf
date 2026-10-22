package org.mcrf.client

/** One instance per native child. Only opaque IDs/digests; bounded retired replay history. */
class CarrierChallengeFlights(private val historyLimit:Int=128) {
    init {require(historyLimit in 1..1024)}
    class CapacityExceeded:IllegalStateException("CAPTCHA pending limit")
    class Ticket internal constructor(internal val key:String,internal val sessionKey:String) {
        override fun toString()="ChallengeFlight(redacted)"
    }
    private val active=mutableMapOf<String,Ticket>()
    private val activeSessions=mutableSetOf<String>()
    private val retired=linkedSetOf<String>()
    private val rejectedSessions=linkedSetOf<String>()
    @Synchronized fun claim(key:String,sessionKey:String=key):Ticket? {
        require(key.length in 1..256);require(sessionKey.length in 1..256)
        if(key in active||key in retired||sessionKey in activeSessions||sessionKey in rejectedSessions)return null
        // Same global pending bound as the host registry, without unbounded native spam.
        if(active.size>=32)throw CapacityExceeded()
        return Ticket(key,sessionKey).also {active[key]=it;activeSessions.add(sessionKey)}
    }
    @Synchronized fun reject(ticket:Ticket){if(active[ticket.key]===ticket)rejectedSessions.add(ticket.sessionKey);trim(rejectedSessions)}
    @Synchronized fun rejected(sessionKey:String)=sessionKey in rejectedSessions
    @Synchronized fun retire(ticket:Ticket) {
        if(active[ticket.key]!==ticket)return
        active.remove(ticket.key);activeSessions.remove(ticket.sessionKey);retired.add(ticket.key);trim(retired)
    }
    private fun trim(values:LinkedHashSet<String>){while(values.size>historyLimit)values.remove(values.first())}
}

package org.mcrf.client

/** One transient proof for one existing request. No persistence, new deadline or native retry. */
internal class CarrierManualReply(private val alive:()->Boolean,private val offer:(String)->Boolean) {
    enum class State { EMPTY, WAITING, SCHEDULED, EXPIRED }
    private var pending:String?=null
    private var closed=false
    override fun toString()="CarrierManualReply(redacted)"
    fun hasPending():Boolean=pending!=null
    fun accept(proof:String):Boolean {
        if(closed||pending!=null||proof.length !in 1..16384||proof.any {it.isISOControl()||it=='|'}||proof.startsWith("error:",true))return false
        pending=proof;return true
    }
    fun attempt():State {
        val proof=pending?:return State.EMPTY
        if(!alive()){clear();return State.EXPIRED}
        // Rejection means not scheduled, so retain exactly this proof. Once an
        // executor accepts it, never send it again or claim native acknowledgement.
        val scheduled=try{offer(proof)}catch(_:Exception){false}
        if(!scheduled)return State.WAITING
        clear();return State.SCHEDULED
    }
    fun clear(){pending=null;closed=true}
}

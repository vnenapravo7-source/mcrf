package org.mcrf.client

/** Pure admission and latest-value queue; never starts or selects a connection. */
internal object CarrierStreamRestartPolicy {
    const val UNVERIFIED="mcrf_stream_snapshot_unverified"
    private val kinds=setOf("csqtt","csqtt25","qwdtt","wdtt")
    fun blockedExplanation(error:Exception):String?=
        (error as? VkCredentialLimit.Blocked)?.limit?.explanation()
    fun startedProfile(effective:org.json.JSONObject,raw:org.json.JSONObject)=org.json.JSONObject(effective.toString()).apply {
        if(raw.has(CarrierStreamDefaults.OVERRIDE))
            put(CarrierStreamDefaults.OVERRIDE,raw.getInt(CarrierStreamDefaults.OVERRIDE))
    }
    fun affected(changed:Set<String>,kind:String,override:Boolean,before:Int,after:Int)=
        kind in kinds && kind in changed && !override && before!=after

    fun owned(requested:Long,current:Long,owner:Boolean,running:Boolean,wanted:Boolean,
              vpn:Boolean,ownsTun:Boolean,policyCurrent:Boolean)=
        requested==current && owner && running && wanted && (!vpn||ownsTun) && policyCurrent

    class Pending {
        data class Ticket(val epoch:Long,val revision:Long)
        private var epoch=Long.MIN_VALUE
        private var revision=0L
        private val values=linkedMapOf<String,Int>()
        @Synchronized fun offer(owner:Long,changes:Map<String,Int>):Ticket {
            require(changes.isNotEmpty()&&changes.keys.all {it in kinds})
            if(epoch!=owner){values.clear();epoch=owner}
            values.putAll(changes)
            return Ticket(epoch,++revision)
        }
        @Synchronized fun snapshot(ticket:Ticket,current:Long):Map<String,Int>? =
            if(current==epoch&&ticket.epoch==epoch&&ticket.revision==revision)values.toMap()else null
        @Synchronized fun complete(ticket:Ticket) {
            if(ticket.epoch==epoch&&ticket.revision==revision)values.clear()
        }
    }
}

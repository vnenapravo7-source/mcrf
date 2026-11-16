package org.mcrf.client

/** Wait for the exact owned STOP and worker retirement; never bypass native validation. */
class ServerSwitchTransition {
    data class Target(val id:String,val whitelists:Boolean,val token:String,val at:Long,val beforeSerial:Long)
    enum class Next { NONE, WAIT, READY, CANCELLED, TIMED_OUT }
    var target:Target?=null;private set
    fun request(id:String,whitelists:Boolean,now:Long):Target {
        val before=observed.serial
        return Target(id,whitelists,"$now:${++sequence}",now,before).also {target=it}
    }
    fun cancel(){target=null}
    fun poll(now:Long,active:Boolean,retiring:Boolean,selected:String,foreground:Boolean):Next {
        val request=target?:return Next.NONE
        fun finish(next:Next):Next {target=null;return next}
        if(selected!=request.id)return finish(Next.CANCELLED)
        if(now<request.at||now-request.at>=30_000)return finish(Next.TIMED_OUT)
        val stop=observed
        if(stop.serial==request.beforeSerial)return Next.WAIT
        if(stop.serial!=request.beforeSerial+1||stop.token!=request.token)return finish(Next.CANCELLED)
        if(active||retiring||!foreground)return Next.WAIT
        return finish(Next.READY)
    }
    companion object {
        const val EXTRA="mcrf.serverSwitchStopToken"
        private data class Observed(val serial:Long,val token:String?)
        @Volatile private var observed=Observed(0,null)
        private var sequence=0L
        /** Every ordinary STOP/START/revoke retires a pending request unless its exact token matches. */
        @Synchronized @JvmStatic fun observeCommand(token:String?){observed=Observed(observed.serial+1,token)}
    }
}

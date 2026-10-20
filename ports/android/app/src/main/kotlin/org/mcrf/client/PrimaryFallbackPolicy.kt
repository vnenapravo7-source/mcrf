package org.mcrf.client

/** A single startup retry, never a protocol substitution or a permanent preference change. */
object PrimaryFallbackPolicy {
    const val KEY="primaryFallbackEnabled"
    fun alternate(primary:String,enabled:Boolean,wireGuard:Boolean,fixture:Boolean,cancelled:Boolean,alreadyRetried:Boolean):String? {
        require(primary in setOf("singbox","steer"))
        if(!enabled||wireGuard||fixture||cancelled||alreadyRetried)return null
        return if(primary=="steer")"singbox"else"steer"
    }
    /** Retirement must really finish before a replacement may use the tunnel. */
    fun <T> handover(retire:()->Unit,cancelled:()->Boolean,start:()->T):T {
        retire()
        check(!cancelled()) {"Подключение отменено"}
        return start()
    }
}

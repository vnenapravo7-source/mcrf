package org.mcrf.client

/** Monotonic coalescing of SCREEN_ON / USER_PRESENT / idle-exit signals. */
internal class WakeTunnelCheck {
    enum class Result { HEALTHY, FAILED, CANCELLED }
    private var last=Long.MIN_VALUE
    private var pending=false
    @Synchronized fun admit(now:Long,enabled:Boolean,active:Boolean):Boolean {
        if(!enabled||!active||pending||last!=Long.MIN_VALUE&&now>=last&&now-last<15000)return false
        pending=true
        return true
    }
    /** A queued check skipped by screen-off/cancellation has not checked the tunnel. */
    @Synchronized fun complete(now:Long,performed:Boolean){pending=false;if(performed)last=now}
    companion object {
        fun verify(urls:List<String>,current:()->Boolean,probe:(String)->Boolean):Result {
            for(url in urls) {
                if(!current())return Result.CANCELLED
                if(runCatching {probe(url)}.getOrDefault(false))
                    return if(current())Result.HEALTHY else Result.CANCELLED
            }
            return if(current())Result.FAILED else Result.CANCELLED
        }
    }
}

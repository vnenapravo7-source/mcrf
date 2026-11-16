package org.mcrf.client

/** Pre-start routing decision only. It ADDS a fresh physical-network check;
 * the connected-session monitor keeps its independently configured cadence. */
object BsStartupPolicy {
    const val MONITOR_INTERVAL_MS=60_000L
    enum class Decision { VPN, BS, CANCELLED }
    fun shouldCheck(enabled:Boolean,fixture:Boolean,alreadyBs:Boolean)=enabled&&!fixture&&!alreadyBs
    fun choose(result:DirectAccessCheck.Result?,enabled:Boolean,cancelled:Boolean):Decision = when {
        cancelled->Decision.CANCELLED
        !enabled||result==null->Decision.VPN
        result.google==DirectAccessCheck.Outcome.CANCELLED||result.gitlab==DirectAccessCheck.Outcome.CANCELLED->Decision.CANCELLED
        result.directComplete&&result.bothUnavailable->Decision.BS
        else->Decision.VPN // no network/permission/partial evidence is NOT proof of BS
    }
    /** Every external action is injected so the ordering/no-provisional-VPN
     * invariant is executable without a phone, DNS, TUN or native module. */
    fun start(enabled:()->Boolean,fixture:Boolean,alreadyBs:Boolean,cancelled:()->Boolean,
              probe:()->DirectAccessCheck.Result,startVpn:()->Unit,startBs:()->Unit):Decision {
        if(cancelled())return Decision.CANCELLED
        val result=if(shouldCheck(enabled(),fixture,alreadyBs))probe()else null
        val decision=choose(result,enabled(),cancelled())
        when(decision){Decision.VPN->startVpn();Decision.BS->startBs();Decision.CANCELLED->Unit}
        return decision
    }
}

package org.mcrf.client

/** A single startup retry, never a protocol substitution or a permanent preference change. */
object PrimaryFallbackPolicy {
    const val KEY="primaryFallbackEnabled"
    /** An opted-in backup is not permission to bypass a rejected trust policy
     * or restart cancelled work. Preserve the original typed failure for UI. */
    fun canRetryFailure(error:Throwable):Boolean {
        var current:Throwable?=error;val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        repeat(16){
            val value=current?:return true
            if(!seen.add(value))return false
            if(value is SecurityException||value is InterruptedException||value is java.util.concurrent.CancellationException)return false
            if(value is java.security.cert.CertificateException||value is java.security.cert.CertPathValidatorException||
                value is javax.net.ssl.SSLPeerUnverifiedException||ClientPrimaryPreparationFailure.blocksRetryCode(value.message))return false
            current=value.cause
        }
        return current==null
    }
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

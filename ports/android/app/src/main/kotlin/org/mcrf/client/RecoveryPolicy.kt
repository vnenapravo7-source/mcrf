package org.mcrf.client

import java.util.concurrent.CancellationException

enum class RecoveryReason {
    NETWORK_LOSS, ENGINE_CRASH, SERVER_FAILURE, AUTHENTICATION, PROTOCOL,
    MANUAL_STOP, ANDROID_REVOKE, CANCELLED, POLICY_DISABLED
}

/** One bounded episode; a short-lived successful reconnect cannot renew its budget. */
data class RecoveryPolicy(
    val maxAttempts:Int=6,
    val initialDelayMs:Long=2_000,
    val maxDelayMs:Long=30_000,
    val maxElapsedMs:Long=180_000,
    val stableConnectionMs:Long=60_000
) {
    init {
        require(maxAttempts in 1..32 && initialDelayMs>0 && maxDelayMs>=initialDelayMs)
        require(maxElapsedMs>0 && stableConnectionMs>0 && maxDelayMs<=Long.MAX_VALUE/2)
    }
    fun retryable(reason:RecoveryReason)=reason in setOf(
        RecoveryReason.NETWORK_LOSS,RecoveryReason.ENGINE_CRASH,RecoveryReason.SERVER_FAILURE)

    /** Equal jitter in [exponential/2, exponential], with a hard cap including jitter. */
    fun delayMs(attempt:Int,jitter:Double):Long {
        require(attempt in 1..maxAttempts && jitter.isFinite() && jitter in 0.0..1.0)
        var ceiling=initialDelayMs
        repeat(attempt-1){ceiling=if(ceiling>=maxDelayMs/2)maxDelayMs else (ceiling*2).coerceAtMost(maxDelayMs)}
        val floor=(ceiling/2).coerceAtLeast(1)
        return floor+((ceiling-floor)*jitter).toLong()
    }

    companion object {
        /** Inspect causes before connectivity: offline must never turn an auth denial into a retry. */
        fun classify(error:Throwable,networkAvailable:Boolean=true):RecoveryReason {
            var current:Throwable?=error
            val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
            var protocol=false
            repeat(16){
                val value=current?:return if(protocol)RecoveryReason.PROTOCOL else
                    if(!networkAvailable)RecoveryReason.NETWORK_LOSS else RecoveryReason.SERVER_FAILURE
                if(!seen.add(value))return RecoveryReason.PROTOCOL
                if(value is VkCredentialLimit.Blocked||value is VkCredentialLimit.AwaitingServerPing)return RecoveryReason.POLICY_DISABLED
                val message=value.message.orEmpty().lowercase(java.util.Locale.ROOT)
                if(value is CancellationException || value is InterruptedException || message=="подключение отменено")
                    return RecoveryReason.CANCELLED
                if(value is SecurityException || value is java.security.cert.CertificateException ||
                    value is java.security.cert.CertPathValidatorException || value is javax.net.ssl.SSLPeerUnverifiedException ||
                    Regex("(^|[^a-z])(auth(entication|orization)?([ _-]failed)?|unauthorized|forbidden|wrong[ _-](password|key)|invalid[ _-](password|credentials)|401|403)([^a-z]|$)").containsMatchIn(message) ||
                    "аутентификац" in message || "неверный пароль" in message || "trust_rejected" in message)
                    return RecoveryReason.AUTHENTICATION
                if(value is IllegalArgumentException || value is UnsupportedOperationException ||
                    value is java.net.ProtocolException || "unsupported" in message || "не поддерживает" in message ||
                    ClientPrimaryPreparationFailure.blocksRetryCode(value.message))protocol=true
                current=value.cause
            }
            return if(current!=null || protocol)RecoveryReason.PROTOCOL else
                if(!networkAvailable)RecoveryReason.NETWORK_LOSS else RecoveryReason.SERVER_FAILURE
        }
    }
}

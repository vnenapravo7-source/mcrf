package org.mcrf.client

/** Recovery is not permission to retry a trust denial or cancelled ownership. */
internal object NetworkRecoveryFailure {
    data class Decision(val retry:Boolean,val detail:String)
    fun classify(error:Exception):Decision = Decision(
        PrimaryFallbackPolicy.canRetryFailure(error),
        ClientEvents.redact(error.message).replace('\r',' ').replace('\n',' ').take(1200)
            .ifBlank {error.javaClass.simpleName}
    )
}

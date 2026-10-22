package org.mcrf.client

/** An RU offer is an explicit recovery action, never an automatic country
 * fallback. A cancelled job cannot resurrect an offer or a registered account. */
internal object WarpRecoveryPolicy {
    fun offerRussia(exhausted:Boolean,foreign:Boolean,hasRetry:Boolean)=exhausted&&foreign&&hasRetry
    fun russia(options:WarpOperations.Options):WarpOperations.Options=options.copy(country="RU",excludeDme=true)
}

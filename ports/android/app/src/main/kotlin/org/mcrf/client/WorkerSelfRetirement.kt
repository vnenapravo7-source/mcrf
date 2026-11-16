package org.mcrf.client

/** A private worker can always retire its own PID after an authenticated STOP.
 * Main still waits for actual Binder death before releasing resources/leases. */
object WorkerSelfRetirement {
    const val DEADLINE_MILLIS=5000L
    fun remaining(elapsedMillis:Long):Long=(DEADLINE_MILLIS-elapsedMillis.coerceAtLeast(0)).coerceAtLeast(0)
    fun shouldTerminate(elapsedMillis:Long,stopping:Boolean,ownerAlive:Boolean):Boolean =
        elapsedMillis>=DEADLINE_MILLIS&&(stopping||!ownerAlive)
}

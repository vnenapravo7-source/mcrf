package org.mcrf.client

/** Cancellation before executor entry releases admission; cancellation while
 * fetching must retain it until the real worker reaches finally. */
internal class SubscriptionRefreshLease(private val released:()->Unit) {
    private val phase=java.util.concurrent.atomic.AtomicInteger(0)
    fun enter()=phase.compareAndSet(0,1)
    fun cancelQueued(){if(phase.compareAndSet(0,2))released()}
    fun finish(){if(phase.compareAndSet(1,2))released()}
}

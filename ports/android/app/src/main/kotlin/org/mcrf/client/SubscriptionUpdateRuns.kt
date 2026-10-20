package org.mcrf.client

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Each scheduled attempt owns its cancellation; a new job never revives an old one. */
internal class SubscriptionUpdateRuns {
    class Attempt internal constructor() {
        private val cancelled=AtomicBoolean(false)
        val active:Boolean get()=!cancelled.get()
        internal fun cancel(){cancelled.set(true)}
        fun checkActive(){if(!active||Thread.currentThread().isInterrupted)throw InterruptedException("Subscription update cancelled")}
    }
    private val current=AtomicReference<Attempt?>()
    fun begin():Attempt=Attempt().also {current.getAndSet(it)?.cancel()}
    fun cancel(){current.getAndSet(null)?.cancel()}
    /** Called on JobService's main thread, serialized with onStartJob/onStopJob. */
    fun finish(attempt:Attempt,complete:()->Unit) {
        if(current.compareAndSet(attempt,null)&&attempt.active)complete()
    }
}

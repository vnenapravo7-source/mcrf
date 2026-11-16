package org.mcrf.client

/** Scheduler observes/cancels only its discovery, never a later manual operation. */
internal class EngineUpdateRun {
    private val running=java.util.concurrent.atomic.AtomicBoolean(true)
    private val cancellation=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var retry=false
        private set
    val active:Boolean get()=running.get()
    val cancelled:Boolean get()=cancellation.get()
    fun cancel(){cancellation.set(true)}
    fun finish(retry:Boolean){this.retry=retry;running.set(false)}
}

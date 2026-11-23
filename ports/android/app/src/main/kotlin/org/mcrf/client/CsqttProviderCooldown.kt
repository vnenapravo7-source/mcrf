package org.mcrf.client

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/** Migration adapter for the shared typed VK backoff. Cancellation under the
 * catalogue lock reads memory only; shared cross-process state is polled outside it. */
internal object CsqttProviderCooldown {
    private val untilElapsed=AtomicLong()
    internal fun deadline(now:Long,limit:VkCredentialLimit?)=if(limit==null)0L else now+limit.seconds*1000L
    fun deadline(context:Context):Long {
        val limit=VkCredentialBackoffStore(context).current()
        if(limit!=null)observe(limit)
        val remaining=(untilElapsed.get()-SystemClock.elapsedRealtime()).coerceAtLeast(0)
        return if(remaining==0L)0L else System.currentTimeMillis()+remaining
    }
    fun active()=untilElapsed.get()>SystemClock.elapsedRealtime()
    fun observe(limit:VkCredentialLimit) {
        val next=deadline(SystemClock.elapsedRealtime(),limit)
        untilElapsed.updateAndGet {maxOf(it,next)}
    }
}

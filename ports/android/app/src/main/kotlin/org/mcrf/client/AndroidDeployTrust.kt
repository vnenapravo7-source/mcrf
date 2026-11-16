package org.mcrf.client

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A request belongs to the operation, never to its Activity. A stale UI cannot approve a later key. */
class AndroidDeployTrust(val endpoint: String,val fingerprint: String) {
    val id: String=UUID.randomUUID().toString()
    private val decision=AtomicReference<Boolean?>(null)
    private val reply=CountDownLatch(1)
    fun approve(requestId: String,accepted: Boolean): Boolean {
        if(requestId!=id || !decision.compareAndSet(null,accepted)) return false
        reply.countDown();return true
    }
    fun await(timeoutMillis: Long=120_000): Boolean {
        require(timeoutMillis in 1..120_000)
        return try {
            if(!reply.await(timeoutMillis,TimeUnit.MILLISECONDS)) approve(id,false)
            decision.get()==true
        } catch(e: InterruptedException) {
            approve(id,false);Thread.currentThread().interrupt();throw e
        }
    }
}

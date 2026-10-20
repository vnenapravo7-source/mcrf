package org.mcrf.client

import java.util.concurrent.ConcurrentHashMap

/** Retains the exact retiring run until its owner has acknowledged death and every
 * dependent close has returned. Exceptions are pending cleanup, never success.
 * The caller supplies a surviving off-UI retry loop; this class owns no threads.
 */
class ClientPrimaryRetirement<T : Any>(
    val owner: T?,
    private val requestStop: () -> Unit,
    private val retireCallbacks: () -> Unit,
    private val closeOwner: (T) -> Unit,
    dependents: List<() -> Unit>,
    private val completed: () -> Unit
) {
    private val dependents=dependents.toList()
    private var stopRequested=false
    private var callbacksRetired=false
    private var ownerClosed=false
    private var dependentIndex=0
    @Volatile var complete=false
        private set
    @Volatile var attempts=0
        private set
    init { pending.add(this) }

    /** A close step must be idempotent/retryable when it throws. No subsequent
     * resource, completion callback or global admission is released on failure.
     */
    @Synchronized fun attempt():Boolean {
        if(complete)return true
        attempts++
        try {
            if(!stopRequested){requestStop();stopRequested=true}
            if(!callbacksRetired){retireCallbacks();callbacksRetired=true}
            if(!ownerClosed){owner?.let(closeOwner);ownerClosed=true}
            while(dependentIndex<dependents.size){dependents[dependentIndex]();dependentIndex++}
            completed()
            complete=true
            pending.remove(this)
            return true
        }catch(error:Exception){
            if(error is InterruptedException)Thread.currentThread().interrupt()
            return false
        }
    }

    fun retryDelayMillis():Long=100L.shl((attempts-1).coerceIn(0,5)).coerceAtMost(3200L)

    companion object {
        // Process-wide: a replacement Service cannot start while a destroyed
        // instance still owns an unacknowledged worker or dependent resources.
        private val pending=ConcurrentHashMap.newKeySet<ClientPrimaryRetirement<*>>()
        @JvmStatic fun hasPending():Boolean=pending.isNotEmpty()
    }
}

package org.mcrf.client

import java.util.concurrent.atomic.AtomicReference

/** Deployment and management share one remote-operation lease, including across Activity recreation. */
object RemoteServerOperationGate {
    private val owner=AtomicReference<String?>()
    fun acquire(id:String):Boolean {
        require(id.isNotBlank()&&id.length<=128)
        return owner.compareAndSet(null,id)
    }
    fun release(id:String){val current=owner.get();if(current==id)owner.compareAndSet(current,null)}
    fun busy()=owner.get()!=null
}

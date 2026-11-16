package org.mcrf.client

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier

/** Bounded parallel preparation; no task/owner may outlive this synchronous boundary. */
internal object RouteCarrierBatch {
    fun <T:AutoCloseable> start(ids:List<String>,cancelled:BooleanSupplier,create:(String,BooleanSupplier)->T?):Map<String,T> {
        require(ids.size<=8&&ids.distinct().size==ids.size)
        if(ids.isEmpty())return emptyMap()
        val stopped=AtomicBoolean(false)
        val failure=AtomicReference<Throwable>()
        val owners=ConcurrentHashMap<String,T>()
        fun cancellation()=try{cancelled.asBoolean}catch(error:Throwable){failure.compareAndSet(null,error);true}
        val stop=BooleanSupplier {stopped.get()||cancellation()}
        val executor=Executors.newFixedThreadPool(minOf(4,ids.size)){task->Thread(task,"mcrf-route-carrier")}
        var interrupted=false
        try {
            try {
                ids.forEach {id->executor.execute {
                    try {
                        check(!stop.asBoolean) {"Запуск серверов правил отменён."}
                        create(id,stop)?.let {owner->owners[id]=owner}
                    }catch(error:Throwable){failure.compareAndSet(null,error);stopped.set(true)}
                }}
            }catch(error:Throwable){failure.compareAndSet(null,error);stopped.set(true)}
            finally {
                // Do not Future.cancel/shutdownNow: a result may contain an exact
                // native owner whose real retirement still needs acknowledgement.
                executor.shutdown()
                while(!executor.isTerminated) {
                    if(cancellation())stopped.set(true)
                    try{executor.awaitTermination(100,TimeUnit.MILLISECONDS)}
                    catch(_:InterruptedException){interrupted=true;stopped.set(true)}
                }
            }
            val wasCancelled=cancellation()
            val error=failure.get()?:if(stopped.get()||wasCancelled)IllegalStateException("Запуск серверов правил отменён.")else null
            if(error!=null)OptionalCoreRetirement.fail(error,OptionalCoreRetirement(ids.mapNotNull(owners::get).asReversed()))
            return ids.mapNotNull {id->owners[id]?.let {id to it}}.toMap()
        }finally{if(interrupted)Thread.currentThread().interrupt()}
    }
}

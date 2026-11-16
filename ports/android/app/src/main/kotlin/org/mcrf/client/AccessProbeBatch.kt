package org.mcrf.client

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded parallel IO. Exhausted executor is NOT evidence of a restricted network. */
class AccessProbeBatch(private val executor:ExecutorService,private val budgetMs:Long=5000) {
    enum class State { RESPONSE, NO_RESPONSE, UNAVAILABLE, CANCELLED }
    fun run(count:Int,cancelled:()->Boolean,abort:()->Unit,probe:(Int)->State):List<State> {
        require(count in 1..3&&budgetMs in 1..10_000)
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val started=Array(count){AtomicBoolean()}
        val futures=ArrayList<Future<State>?>()
        try {
            repeat(count){i->
                futures.add(try{executor.submit<State>{
                    if(cancelled()||Thread.currentThread().isInterrupted)State.CANCELLED
                    else {started[i].set(true);probe(i)}
                }}catch(_:RejectedExecutionException){null})
            }
            val result=MutableList(count){State.UNAVAILABLE}
            val pending=(0 until count).filterTo(mutableSetOf()){futures[it]!=null}
            while(pending.isNotEmpty()&&System.nanoTime()<deadline){
                if(cancelled()||Thread.currentThread().isInterrupted)return List(count){State.CANCELLED}
                val ready=pending.filter {futures[it]!!.isDone}
                for(i in ready){result[i]=try{futures[i]!!.get()}catch(_:Exception){State.UNAVAILABLE};pending.remove(i)}
                if(pending.isNotEmpty())try{Thread.sleep(10)}catch(_:InterruptedException){Thread.currentThread().interrupt();return List(count){State.CANCELLED}}
            }
            for(i in pending)result[i]=if(futures[i]!!.isDone)try{futures[i]!!.get()}catch(_:Exception){State.UNAVAILABLE}
                else if(started[i].get())State.NO_RESPONSE else State.UNAVAILABLE
            return if(cancelled())List(count){State.CANCELLED}else result
        } finally {
            futures.filterNotNull().forEach {it.cancel(true)}
            abort()
            (executor as? ThreadPoolExecutor)?.purge()
        }
    }
}

package org.mcrf.client

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps failed resources reachable; successful children are never closed twice. */
class OptionalCoreRetirement(resources:List<AutoCloseable>):AutoCloseable {
    private val pending=resources.toMutableList()
    private val requested=AtomicBoolean()
    fun isRequested():Boolean=requested.get()
    @Synchronized fun isComplete():Boolean=pending.isEmpty()
    @Synchronized override fun close() {
        requested.set(true)
        var failure:Throwable?=null
        var index=0
        while(index<pending.size) {
            try {pending[index].close();pending.removeAt(index)}
            catch(error:Throwable){if(failure==null)failure=error else if(failure!==error)failure.addSuppressed(error);index++}
        }
        failure?.let {throw IllegalStateException("Очистка optional cores не завершена; повторите close.",it)}
    }
    companion object {
        /** Off-UI local-owner rollback: never escape with an unadopted resource. */
        fun fail(error:Throwable,resource:AutoCloseable):Nothing {
            if(error is InterruptedException)Thread.currentThread().interrupt()
            drain(resource)
            throw error
        }
        /** Bounded backoff, deliberately no total timeout/false ACK; owner stays on this stack. */
        fun drain(resource:AutoCloseable) {
            var interrupted=Thread.interrupted()
            var delay=25L
            try {
                while(true) {
                    try {resource.close();return}
                    catch(cleanup:Throwable) {
                        val flagged=Thread.interrupted()
                        if(cleanup is InterruptedException || flagged)interrupted=true
                    }
                    try {Thread.sleep(delay)}catch(_:InterruptedException){interrupted=true}
                    delay=(delay*2).coerceAtMost(500L)
                }
            }finally {if(interrupted)Thread.currentThread().interrupt()}
        }
    }
}

/** Return means observed process death, never merely a successful kill request. */
object OptionalCoreTermination {
    // A failed attempt retains the same exact stdin owner for the retry. No
    // global PID registry or repeated STOP writes into a replacement process.
    private val writers=java.util.IdentityHashMap<Process,CarrierIpcWriter>()
    private fun writer(child:Process):CarrierIpcWriter=synchronized(writers){
        writers[child]?:CarrierIpcWriter(child.outputStream).also {writers[child]=it}
    }
    private fun drainWriter(child:Process) {
        val ipc=synchronized(writers){writers[child]}?:return
        ipc.childExited()
        check(ipc.awaitCompletion(1000).writerExited){"Optional core stdin ещё освобождается; требуется повторная очистка."}
        synchronized(writers){if(writers[child]===ipc)writers.remove(child)}
    }
    fun release(child:Process,graceful:Boolean) {
        stop(child,graceful)
        OptionalCoreRetirement(listOf(child.outputStream,child.inputStream,child.errorStream)).close()
    }
    fun stop(child:Process,graceful:Boolean) {
        try {
            if(!child.isAlive){drainWriter(child);return}
            if(graceful) {
                writer(child).requestStop()
                if(child.waitFor(3,TimeUnit.SECONDS)&&!child.isAlive){drainWriter(child);return}
            }
            child.destroy()
            if(child.waitFor(1,TimeUnit.SECONDS)&&!child.isAlive){drainWriter(child);return}
            child.destroyForcibly()
            check(child.waitFor(3,TimeUnit.SECONDS)&&!child.isAlive){"Optional core ещё работает; требуется повторная очистка."}
            drainWriter(child)
        }catch(error:InterruptedException) {
            try {child.destroyForcibly()}catch(kill:Throwable){error.addSuppressed(kill)}
            Thread.currentThread().interrupt()
            throw IllegalStateException("Ожидание остановки optional core прервано; требуется повторная очистка.",error)
        }
    }
}

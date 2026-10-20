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
    fun release(child:Process,graceful:Boolean) {
        stop(child,graceful)
        OptionalCoreRetirement(listOf(child.outputStream,child.inputStream,child.errorStream)).close()
    }
    fun stop(child:Process,graceful:Boolean) {
        if(!child.isAlive)return
        try {
            if(graceful) {
                try {child.outputStream.write("STOP\n".toByteArray());child.outputStream.flush();child.outputStream.close()}catch(_:Exception){}
                if(child.waitFor(3,TimeUnit.SECONDS)&&!child.isAlive)return
            }
            child.destroy()
            if(child.waitFor(1,TimeUnit.SECONDS)&&!child.isAlive)return
            child.destroyForcibly()
            check(child.waitFor(3,TimeUnit.SECONDS)&&!child.isAlive){"Optional core ещё работает; требуется повторная очистка."}
        }catch(error:InterruptedException) {
            try {child.destroyForcibly()}catch(kill:Throwable){error.addSuppressed(kill)}
            Thread.currentThread().interrupt()
            throw IllegalStateException("Ожидание остановки optional core прервано; требуется повторная очистка.",error)
        }
    }
}

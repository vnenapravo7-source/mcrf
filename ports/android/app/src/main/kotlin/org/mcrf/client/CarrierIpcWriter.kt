package org.mcrf.client

import java.io.OutputStream
import java.util.ArrayDeque

/** Exact-child bounded stdin owner. Completion means this writer thread exited,
 * NEVER that a native child (or its descendants) exited. */
class CarrierIpcWriter(private val output:OutputStream,private val maxCommands:Int=32,private val maxBytes:Int=131072) {
    init {require(maxCommands in 1..32&&maxBytes in 16384..131072)}
    data class Completion(val writerExited:Boolean,val stopWritten:Boolean,val failed:Boolean)
    private val lock=Object()
    private val pending=ArrayDeque<ByteArray>()
    private var commands=0
    private var bytes=0
    private var sealed=false
    private var childDead=false
    @Volatile private var stopWritten=false
    @Volatile private var failed=false
    private val worker=Thread(::run,"mcrf-carrier-ipc").apply {isDaemon=true;start()}
    override fun toString()="CarrierIpcWriter(redacted)"

    /** A bounded defensive copy; rejection does not edit or retain the caller's proof. */
    fun offer(command:ByteArray):Boolean {
        require(command.size in 1..16384&&command.last()==10.toByte())
        synchronized(lock) {
            if(sealed||commands>=maxCommands||bytes+command.size>maxBytes)return false
            pending.addLast(command.copyOf());commands++;bytes+=command.size
            lock.notifyAll();return true
        }
    }
    /** Reserved terminal command: admission closes even when the data queue is full.
     * No OutputStream method or wait is called while holding this monitor. */
    fun requestStop() {synchronized(lock){sealed=true;lock.notifyAll()}}
    /** The OS has confirmed the exact child died. Discard proofs which can no
     * longer be delivered; never replay them into a replacement process. The
     * in-flight write/close is still owned until awaitCompletion observes exit. */
    fun childExited() {synchronized(lock){
        childDead=true
        sealed=true
        while(pending.isNotEmpty()) {
            val command=pending.removeFirst();commands--;bytes-=command.size;command.fill(0)
        }
        lock.notifyAll()
    }}
    fun awaitCompletion(timeoutMillis:Long):Completion {
        require(timeoutMillis in 0..30000)
        // join(0) means infinite, not a non-blocking poll.
        if(timeoutMillis>0)worker.join(timeoutMillis)
        return Completion(!worker.isAlive,stopWritten,failed)
    }
    private fun run() {
        try {
            while(true) {
                val command:ByteArray?=synchronized(lock) {
                    while(pending.isEmpty()&&!sealed)lock.wait()
                    if(pending.isEmpty())null else pending.removeFirst()
                }
                if(command==null){
                    if(synchronized(lock){childDead})break
                    output.write("STOP\n".toByteArray(Charsets.US_ASCII));output.flush();stopWritten=true
                    break
                }
                try {output.write(command);output.flush()}
                finally {
                    synchronized(lock){commands--;bytes-=command.size}
                    command.fill(0)
                }
            }
        }catch(_:Exception){failed=true}
        finally {
            synchronized(lock){
                sealed=true
                pending.forEach {it.fill(0)};pending.clear();commands=0;bytes=0
                lock.notifyAll()
            }
            try {output.close()}catch(_:Exception){failed=true}
        }
    }
}

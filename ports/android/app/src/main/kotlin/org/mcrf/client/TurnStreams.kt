package org.mcrf.client

import java.util.concurrent.atomic.AtomicInteger

/** Native stdout record, excluding LF (the line reader removes it). No log prefixes,
 * whitespace, signs, Unicode digits or configured worker limits are accepted.
 * -1 means that this child has not supplied a measurement. */
internal object TurnStreams {
    const val PREFIX="MCRF_STREAMS"
    const val MAX=65535
    /** Returns the numeric endpoint selected by Android DNS for display. Native
     * stream telemetry remains authoritative for the live worker count. */
    fun serverIp(host:String):String {
        val value=host.removePrefix("[").removeSuffix("]")
        return runCatching {java.net.InetAddress.getByName(value).hostAddress.orEmpty()}.getOrDefault("")
    }
    fun reserved(bytes:ByteArray)=bytes.size>=PREFIX.length&&PREFIX.indices.all {bytes[it]==PREFIX[it].code.toByte()}
    fun parse(bytes:ByteArray):Int? {
        val length=bytes.size-(if(bytes.lastOrNull()==13.toByte())1 else 0)
        if(length !in PREFIX.length+2..PREFIX.length+6||!reserved(bytes)||bytes[PREFIX.length]!=32.toByte())return null
        val digits=(PREFIX.length+1 until length)
        if(length>PREFIX.length+2&&bytes[digits.first()]==48.toByte())return null
        var count=0
        for(i in digits){val digit=bytes[i].toInt()-48;if(digit !in 0..9)return null;count=count*10+digit}
        return count.takeIf {it<=MAX}
    }
}

internal class TurnStreamState {
    private val count=AtomicInteger(-1)
    private var stopped=false
    fun current()=count.get()
    @Synchronized fun offer(bytes:ByteArray){
        if(!stopped)TurnStreams.parse(bytes)?.let(count::set)
    }
    @Synchronized fun stop(){stopped=true;count.set(-1)}
}

package org.mcrf.client

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Source-only, bounded private native DNS RPC. Large answers never enter the
 * line-based carrier log/owner pipe. verifyPeer must verify the captured child
 * PID AND process birth identity, not merely a shared UID or executable name. */
internal class BsNativeDnsRpc(
    private val generation:Long,private val name:String,private val live:()->Boolean,
    private val verifyPeer:(Int,Int)->Boolean
):Closeable {
    enum class Cause{RETIRED,OWNER,REQUEST,RESPONSE,NATIVE,TIMEOUT,TRANSPORT}
    class Rejected(val reason:Cause,val nativeCode:Int?=null):IOException("BS_DNS_RPC_${reason.name}"+(nativeCode?.let{" code=$it"}.orEmpty()))
    private val closed=AtomicBoolean();private val lock=Any();private val sockets=hashSetOf<LocalSocket>()
    private val timer=ScheduledThreadPoolExecutor(1){Thread(it,"mcrf-bs-dns-rpc-deadline").apply{isDaemon=true}}.apply{removeOnCancelPolicy=true}
    init{require(generation>0&&name.startsWith("mcrf-bs-dns-")&&name.length<=90&&name.matches(Regex("[A-Za-z0-9_-]+")))}
    private fun checkLive(){if(closed.get()||!live())throw Rejected(Cause.RETIRED)}
    private fun peerValid(uid:Int,pid:Int)=try{verifyPeer(uid,pid)}catch(_:Exception){false}
    fun exchange(protocol:Int,translatedPort:Int,query:ByteArray,deadline:Long):ByteArray {
        val wire=query.clone()
        if(protocol !in setOf(6,17)||translatedPort !in 1..65535||wire.size !in 12..4096)throw Rejected(Cause.REQUEST)
        try{PacketDnsWire.question(wire)}catch(_:Exception){throw Rejected(Cause.REQUEST)}
        val remaining=deadline-SystemClock.elapsedRealtime();if(remaining<=0)throw Rejected(Cause.TIMEOUT);if(remaining>10000)throw Rejected(Cause.REQUEST)
        val socket=synchronized(lock){checkLive();if(sockets.size>=16)throw Rejected(Cause.REQUEST);LocalSocket().also{sockets+=it}}
        var timeout:java.util.concurrent.ScheduledFuture<*>?=null
        try{
            timeout=timer.schedule({runCatching{socket.close()}},remaining,TimeUnit.MILLISECONDS)
            checkLive();socket.soTimeout=remaining.toInt();socket.connect(LocalSocketAddress(name,LocalSocketAddress.Namespace.ABSTRACT))
            val peer=socket.peerCredentials
            if(!peerValid(peer.uid,peer.pid))throw Rejected(Cause.OWNER)
            checkLive()
            val output=DataOutputStream(socket.outputStream)
            output.writeInt(0x42444e31);output.writeLong(generation);output.writeByte(protocol);output.writeShort(translatedPort);output.writeShort(wire.size);output.write(wire);output.flush()
            val input=DataInputStream(socket.inputStream);val size=input.readInt()
            if(size in -9..-1)throw Rejected(Cause.NATIVE,-size)
            if(size !in 12..65535)throw Rejected(Cause.RESPONSE)
            val answer=ByteArray(size);input.readFully(answer)
            if(SystemClock.elapsedRealtime()>=deadline)throw Rejected(Cause.TIMEOUT)
            checkLive();if(!peerValid(peer.uid,peer.pid))throw Rejected(Cause.OWNER)
            try{if(PacketDnsWire.response(wire,answer).truncated)throw Rejected(Cause.RESPONSE)}catch(error:Rejected){throw error}catch(_:Exception){throw Rejected(Cause.RESPONSE)}
            return answer
        }catch(error:Rejected){throw error}
        catch(_:Exception){throw Rejected(if(closed.get()||!live())Cause.RETIRED else if(SystemClock.elapsedRealtime()>=deadline)Cause.TIMEOUT else Cause.TRANSPORT)}
        finally{timeout?.cancel(false);runCatching{socket.close()};synchronized(lock){sockets-=socket}}
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        val owned=synchronized(lock){sockets.toList()};owned.forEach{runCatching{it.close()}};timer.shutdownNow()
    }
    fun awaitTimerStopped(timeoutMillis:Long):Boolean{require(timeoutMillis in 0..5000);return timer.awaitTermination(timeoutMillis,TimeUnit.MILLISECONDS)}
    override fun toString()="BsNativeDnsRpc(redacted)"
}

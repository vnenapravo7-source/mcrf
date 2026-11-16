package org.mcrf.client

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Private one-session Go -> module FD handoff. No listener or descriptor is
 * exposed to public profiles. Bridge/Main/SPI admission must explicitly opt in;
 * this utility alone does not advertise or enable the original packet mode. */
internal class CarrierOriginalPacketControl(
    private val group:CarrierProcessGroup,
    private val generation:Long,
    private val cancelled:()->Boolean
):AutoCloseable {
    private val closed=AtomicBoolean()
    private val claimed=AtomicBoolean()
    private val pollLock=Any()
    private val socket=AtomicReference<LocalSocket?>()
    private val endpoint=AtomicReference<Endpoint?>()
    private val server:LocalServerSocket
    init {
        require(generation>0)
        server=LocalServerSocket("mcrf.carrier.${group.nonce}")
    }
    fun privateConfiguration()=JSONObject().put("name","mcrf.carrier.${group.nonce}")
        .put("nonce",group.nonce).put("generation",generation).put("parent_pid",android.os.Process.myPid())

    class Metadata internal constructor(val generation:Long,val peerPid:Int,val nonce:String,val local:String,val mtu:Int) {
        override fun toString()="OriginalPacketMetadata(redacted)"
    }
    private fun live(){check(!closed.get()&&!cancelled()){"ORIGINAL_PACKET_CONTROL_STOPPED"}}

    /** Off UI, within the existing transport's absolute preparation deadline. */
    fun await(deadlineMillis:Long):Endpoint {
        check(claimed.compareAndSet(false,true)){"ORIGINAL_PACKET_CONTROL_ONCE"}
        try {
            while(true) {
                live()
                val remaining=deadlineMillis-SystemClock.elapsedRealtime()
                check(remaining>0){"ORIGINAL_PACKET_CONTROL_TIMEOUT"}
                val client=synchronized(pollLock) {
                    live()
                    val poll=StructPollfd().apply {fd=server.fileDescriptor;events=OsConstants.POLLIN.toShort()}
                    Os.poll(arrayOf(poll),minOf(100L,remaining).toInt())
                    check(poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)==0){"ORIGINAL_PACKET_CONTROL_CLOSED"}
                    if(poll.revents.toInt() and OsConstants.POLLIN==0)null else server.accept()
                }?:continue
                socket.set(client)
                live()
                val peer=client.peerCredentials
                if(peer.uid!=android.os.Process.myUid()||peer.pid!=group.verifiedRootPid()) {
                    socket.compareAndSet(client,null);client.close();continue
                }
                client.soTimeout=minOf(30_000L,(deadlineMillis-SystemClock.elapsedRealtime()).coerceAtLeast(1)).toInt()
                val prepared=receive(client,peer.pid)
                endpoint.set(prepared)
                try {live()}catch(error:Throwable){prepared.close();throw error}
                return prepared
            }
        }catch(error:Throwable){close();throw error}
    }

    private fun receive(client:LocalSocket,pid:Int):Endpoint {
        val input=client.inputStream
        val marker=input.read()
        val descriptors=client.ancillaryFileDescriptors.orEmpty()
        try {
            check(marker=='P'.code&&descriptors.size==1){"ORIGINAL_PACKET_CONTROL_FD_COUNT"}
            val original=descriptors.single()
            check(Os.fstat(original).st_mode and OsConstants.S_IFMT==OsConstants.S_IFSOCK){"ORIGINAL_PACKET_CONTROL_FD_TYPE"}
            val bytes=ByteArrayOutputStream()
            while(true) {
                live()
                val value=input.read()
                val extra=client.ancillaryFileDescriptors.orEmpty()
                if(extra.isNotEmpty()){closeAll(extra);error("ORIGINAL_PACKET_CONTROL_EXTRA_FD")}
                check(value>=0){"ORIGINAL_PACKET_CONTROL_METADATA_EOF"}
                if(value=='\n'.code)break
                check(bytes.size()<512){"ORIGINAL_PACKET_CONTROL_METADATA_SIZE"}
                bytes.write(value)
            }
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            val token=JSONTokener(text)
            val metadata=token.nextValue() as? JSONObject?:error("ORIGINAL_PACKET_CONTROL_METADATA")
            check(token.nextClean()=='\u0000'&&metadata.keys().asSequence().toSet()==setOf("schema","nonce","generation","local","mtu","role")) {"ORIGINAL_PACKET_CONTROL_SCHEMA"}
            val rawGeneration=metadata.opt("generation")
            val mtu=metadata.opt("mtu")
            val local=metadata.opt("local")
            check(metadata.opt("schema")==1&&metadata.opt("role")=="original_ip_stack"&&metadata.opt("nonce")==group.nonce&&
                (rawGeneration is Int||rawGeneration is Long)&&(rawGeneration as Number).toLong()==generation&&
                mtu is Int&&mtu in 576..1984&&local is String&&validIPv4(local)){"ORIGINAL_PACKET_CONTROL_METADATA"}
            val owned=ParcelFileDescriptor.dup(original)
            return Endpoint(owned,client,Metadata(generation,pid,group.nonce,local,mtu))
        }finally{closeAll(descriptors)}
    }
    private fun validIPv4(value:String):Boolean {
        val values=value.split('.')
        if(values.size!=4)return false
        val parts=values.map {part->part.toIntOrNull()?.takeIf {it in 0..255&&it.toString()==part}?:return false}
        return parts[0]!=0&&parts[0]!=127&&parts[0]<224
    }
    private fun closeAll(descriptors:Array<out FileDescriptor>){descriptors.forEach {runCatching {Os.close(it)}}}

    inner class Endpoint internal constructor(
        private val descriptor:ParcelFileDescriptor,
        private val control:LocalSocket,
        val metadata:Metadata
    ):AutoCloseable {
        private val lock=Any()
        private var retired=false
        private var transferred=false
        /** The module attach must synchronously duplicate and validate this FD
         * under its existing generation/configuration/usage admission. No ACK
         * is sent for a queued async request or after owner cancellation. */
        fun transferOwned(attach:(ParcelFileDescriptor,Metadata)->Unit) {
            try {
                val borrowed=synchronized(lock){
                    live();check(!retired&&!transferred){"ORIGINAL_PACKET_CONTROL_ALREADY_TRANSFERRED"}
                    transferred=true;ParcelFileDescriptor.dup(descriptor.fileDescriptor)
                }
                borrowed.use {attach(it,metadata)}
                live()
                check(group.verifiedRootPid()==metadata.peerPid){"ORIGINAL_PACKET_CONTROL_OWNER_CHANGED"}
                synchronized(lock) {
                    live();check(!retired){"ORIGINAL_PACKET_CONTROL_RETIRED"}
                    control.outputStream.apply {
                        write("MCRF_PACKET_OWNED|${metadata.nonce}|${metadata.generation}\n".toByteArray(Charsets.US_ASCII));flush()
                    }
                }
            }finally{this@CarrierOriginalPacketControl.close()}
        }
        override fun close() {
            synchronized(lock){if(!retired){retired=true;descriptor.close()}}
        }
        override fun toString()="OriginalPacketEndpoint(redacted)"
    }
    override fun close() {
        if(!closed.compareAndSet(false,true))return
        try {endpoint.getAndSet(null)?.close()}
        finally {
            socket.getAndSet(null)?.let {runCatching {it.close()}}
            synchronized(pollLock){runCatching {server.close()}}
        }
    }
    override fun toString()="CarrierOriginalPacketControl(redacted)"
}

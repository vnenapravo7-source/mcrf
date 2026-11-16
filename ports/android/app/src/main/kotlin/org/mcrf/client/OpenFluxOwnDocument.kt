package org.mcrf.client

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Separate own-inline document foundation. Not wired to a browser or cookie IPC.
 * Construct only for an explicitly opened exact child/request, not all pending pages. */
internal class OpenFluxOwnDocument(private val child:String,private val ticket:String,
                                   private val size:Int,private val sha256:String,private val alive:()->Boolean) : AutoCloseable {
    private var bytes:ByteArray?
    private var received=0
    init {
        require(child.matches(ID)&&ticket.matches(ID)&&size in 1..MAX_BYTES&&sha256.matches(Regex("[0-9a-f]{64}")))
        bytes=ByteArray(size)
    }
    override fun toString()="OpenFluxOwnDocument(redacted)"
    private fun current()=try{alive()}catch(_:Exception){false}
    @Synchronized fun append(expectedChild:String,expectedTicket:String,offset:Int,chunk:ByteArray):Boolean {
        if(expectedChild!=child||expectedTicket!=ticket)return false // Stale child cannot poison a replacement.
        val target=bytes?:return false
        if(!current()){close();return false}
        val expected=minOf(CHUNK_BYTES,size-received)
        if(expected==0||offset!=received||offset%CHUNK_BYTES!=0||chunk.size!=expected){close();return false}
        chunk.copyInto(target,received);received+=chunk.size
        return true
    }
    /** Nothing is exposed before complete exact digest/UTF8 validation. Immutable
     * returned text is transient but cannot be securely wiped by this class. */
    @Synchronized fun take(expectedChild:String,expectedTicket:String):String? {
        if(expectedChild!=child||expectedTicket!=ticket)return null
        val target=bytes?:return null
        if(!current()){close();return null}
        if(received!=size)return null
        return try {
            val digest=MessageDigest.getInstance("SHA-256").digest(target).joinToString(""){"%02x".format(it.toInt() and 255)}
            if(digest!=sha256)null else Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(target)).toString()
        }catch(_:Exception){null}finally{close()}
    }
    @Synchronized override fun close(){bytes?.fill(0);bytes=null;received=0}
    companion object {
        const val MAX_BYTES=512*1024
        const val CHUNK_BYTES=8192
        private val ID=Regex("[0-9a-f]{32}")
    }
}

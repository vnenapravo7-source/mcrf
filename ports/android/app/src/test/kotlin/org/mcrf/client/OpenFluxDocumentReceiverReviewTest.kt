package org.mcrf.client

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** New receiver control-flow only; predicates are synthetic, not Android UI consent. */
class OpenFluxDocumentReceiverReviewTest {
    private val child="a".repeat(32);private val ticket="b".repeat(32);private val old="c".repeat(32)
    private fun frame(prefix:String,data:JSONObject)=(prefix+data).toByteArray()
    private fun pending(id:String=ticket)=OpenFluxDocumentWire.pending(frame(OpenFluxDocumentWire.PENDING,
        JSONObject().put("id",id).put("kind","inline")))!!
    private fun sha(raw:ByteArray)=MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun meta(raw:ByteArray,id:String=ticket,digest:String=sha(raw))=frame(OpenFluxDocumentWire.META,
        JSONObject().put("id",id).put("bytes",raw.size).put("sha256",digest))
    private fun chunk(raw:ByteArray,offset:Int=0,id:String=ticket)=frame(OpenFluxDocumentWire.CHUNK,
        JSONObject().put("id",id).put("offset",offset).put("data",Base64.getEncoder().encodeToString(raw)))
    private fun end(id:String=ticket)=frame(OpenFluxDocumentWire.END,JSONObject().put("id",id))
    private fun stream(receiver:OpenFluxDocumentReceiver,raw:ByteArray,id:String=ticket) {
        assertEquals(OpenFluxDocumentReceiver.Result.CONSUMED,receiver.receive(child,meta(raw,id)))
        var offset=0
        while(offset<raw.size){val finish=minOf(offset+8192,raw.size);assertEquals(OpenFluxDocumentReceiver.Result.CONSUMED,
            receiver.receive(child,chunk(raw.copyOfRange(offset,finish),offset,id)));offset=finish}
        assertEquals(OpenFluxDocumentReceiver.Result.COMPLETE,receiver.receive(child,end(id)))
    }
    @Test fun exactExplicitRequestRequiresMetadataChunksEndAndOneShotTake() {
        val receiver=OpenFluxDocumentReceiver(child){true};val raw=("x".repeat(8191)+"я<html>synthetic</html>").toByteArray()
        assertTrue(receiver.requestAfterUserOpen(pending()){true})
        assertNull(receiver.take(child,ticket))
        stream(receiver,raw);assertEquals(raw.toString(Charsets.UTF_8),receiver.take(child,ticket));assertNull(receiver.take(child,ticket))
        assertEquals("OpenFluxDocumentReceiver(redacted)",receiver.toString())
    }
    @Test fun pendingAndUnsolicitedFramesCannotOpenDocumentOrBypassOwnership() {
        val receiver=OpenFluxDocumentReceiver(child){true};val raw="synthetic".toByteArray()
        assertEquals(OpenFluxDocumentReceiver.Result.REJECTED,receiver.receive(child,
            frame(OpenFluxDocumentWire.PENDING,JSONObject().put("id",ticket).put("kind","inline"))))
        assertFalse(receiver.requestAfterUserOpen(pending()){false})
        assertEquals(OpenFluxDocumentReceiver.Result.REJECTED,receiver.receive(child,meta(raw)))
        assertNull(receiver.take(child,ticket))
        assertTrue(receiver.requestAfterUserOpen(pending()){true});stream(receiver,raw)
        assertEquals("synthetic",receiver.take(child,ticket))
    }
    @Test fun validStaleTicketAndForeignChildCannotPoisonCurrentRequest() {
        val receiver=OpenFluxDocumentReceiver(child){true};val raw="synthetic".toByteArray()
        assertTrue(receiver.requestAfterUserOpen(pending()){true})
        assertEquals(OpenFluxDocumentReceiver.Result.CONSUMED,receiver.receive("d".repeat(32),meta(raw)))
        for(bytes in listOf(meta(raw,old),chunk(raw,id=old),end(old)))
            assertEquals(OpenFluxDocumentReceiver.Result.CONSUMED,receiver.receive(child,bytes))
        stream(receiver,raw);assertNull(receiver.take(child,old));assertEquals("synthetic",receiver.take(child,ticket))
    }
    @Test fun orderingDuplicateAndDigestOrUtf8FailureExposeNothing() {
        val raw=ByteArray(9000){65}
        for(mode in 0..3){
            val receiver=OpenFluxDocumentReceiver(child){true};assertTrue(receiver.requestAfterUserOpen(pending()){true})
            val bad=when(mode){
                0->end()
                1->chunk(raw.copyOfRange(0,8192))
                2->{receiver.receive(child,meta(raw));meta(raw)}
                else->{receiver.receive(child,meta(raw));receiver.receive(child,chunk(raw.copyOfRange(0,8192)));chunk(raw.copyOfRange(0,8192))}
            }
            assertEquals(OpenFluxDocumentReceiver.Result.REJECTED,receiver.receive(child,bad));assertNull(receiver.take(child,ticket))
        }
        for(invalidUtf8 in listOf(false,true)) {
            val value=if(invalidUtf8)byteArrayOf(0xff.toByte())else "synthetic".toByteArray()
            val receiver=OpenFluxDocumentReceiver(child){true};assertTrue(receiver.requestAfterUserOpen(pending()){true})
            receiver.receive(child,meta(value,digest=if(invalidUtf8)sha(value)else"0".repeat(64)));receiver.receive(child,chunk(value))
            assertEquals(OpenFluxDocumentReceiver.Result.REJECTED,receiver.receive(child,end()));assertNull(receiver.take(child,ticket))
        }
    }
    @Test fun lostOwnershipOrChildRetirementDoesNotAutomaticallyReviveBuffers() {
        var alive=true;var owns=true;val raw="synthetic".toByteArray()
        val receiver=OpenFluxDocumentReceiver(child){alive};assertTrue(receiver.requestAfterUserOpen(pending()){owns})
        stream(receiver,raw);owns=false;assertNull(receiver.take(child,ticket));owns=true
        assertEquals(OpenFluxDocumentReceiver.Result.REJECTED,receiver.receive(child,meta(raw)))
        assertTrue(receiver.requestAfterUserOpen(pending()){owns});receiver.receive(child,meta(raw));alive=false
        assertEquals(OpenFluxDocumentReceiver.Result.CONSUMED,receiver.receive(child,chunk(raw)));alive=true
        assertNull(receiver.take(child,ticket));assertEquals(OpenFluxDocumentReceiver.Result.REJECTED,receiver.receive(child,meta(raw)))
        val throwing=OpenFluxDocumentReceiver(child){true};assertFalse(throwing.requestAfterUserOpen(pending()){error("synthetic")})
    }
    @Test fun replacementAndCloseRetireOldDocumentWithoutRedirectingLateReads() {
        val receiver=OpenFluxDocumentReceiver(child){true};val raw="synthetic-new".toByteArray()
        assertTrue(receiver.requestAfterUserOpen(pending(old)){true});stream(receiver,"synthetic-old".toByteArray(),old)
        assertTrue(receiver.requestAfterUserOpen(pending()){true});assertNull(receiver.take(child,old))
        stream(receiver,raw);assertNull(receiver.take(child,old));assertEquals("synthetic-new",receiver.take(child,ticket))
        assertTrue(receiver.requestAfterUserOpen(pending()){true});receiver.receive(child,meta(raw));receiver.close()
        assertEquals(OpenFluxDocumentReceiver.Result.REJECTED,receiver.receive(child,chunk(raw)));assertNull(receiver.take(child,ticket))
    }
}

package org.mcrf.client

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenFluxDocumentWireReviewTest {
    private val id="a".repeat(32)
    private fun meta()=JSONObject().put("id",id).put("bytes",524288).put("sha256","b".repeat(64))
    private fun frame(prefix:String,data:JSONObject)=(prefix+data).toByteArray(Charsets.UTF_8)
    private fun chunk(offset:Any=0,data:Any=Base64.getEncoder().encodeToString("synthetic-private".toByteArray()))=
        JSONObject().put("id",id).put("offset",offset).put("data",data)

    @Test fun ownPendingNoticeIsTypedInlineOnlyWithoutHtmlOrProxy() {
        fun data()=JSONObject().put("id",id).put("kind","inline")
        val parsed=OpenFluxDocumentWire.pending(frame(OpenFluxDocumentWire.PENDING,data()))!!
        assertEquals(id,parsed.id);assertEquals("OpenFluxOwnPending(redacted)",parsed.toString())
        for(bad in listOf(data().put("kind","url"),data().put("kind",true),data().put("html","synthetic-private"),
            data().put("proxy","127.0.0.1:1"),data().put("id","bad")))
            assertNull(OpenFluxDocumentWire.pending(frame(OpenFluxDocumentWire.PENDING,bad)))
    }

    @Test fun metadataIsTypedBoundedAndPrintableWithoutPrivateFields() {
        val parsed=OpenFluxDocumentWire.metadata(frame(OpenFluxDocumentWire.META,meta()))!!
        assertEquals(id,parsed.id);assertEquals(524288,parsed.bytes);assertEquals("b".repeat(64),parsed.sha256)
        assertEquals("OpenFluxDocumentMetadata(redacted)",parsed.toString())
    }
    @Test fun metadataRejectsCoercionUnknownFieldsTrailingBytesAndBadUtf8() {
        for(data in listOf(meta().put("bytes",0),meta().put("bytes",524289),meta().put("bytes","1"),
            meta().put("sha256","BAD"),meta().put("html","synthetic-private"),meta().put("id",true)))
            assertNull(OpenFluxDocumentWire.metadata(frame(OpenFluxDocumentWire.META,data)))
        assertNull(OpenFluxDocumentWire.metadata((OpenFluxDocumentWire.META+"{\"id\":\"$id\",\"bytes\":1.0,\"sha256\":\"${"b".repeat(64)}\"}").toByteArray()))
        val good=frame(OpenFluxDocumentWire.META,meta())
        for(tail in listOf(" trailing".toByteArray(),"\u0000 trailing".toByteArray(),byteArrayOf(0xff.toByte())))
            assertNull(OpenFluxDocumentWire.metadata(good+tail))
        assertNull(OpenFluxDocumentWire.metadata(ByteArray(1025){65}))
    }
    @Test fun chunkCanonicalBase64IsBoundedOwnedAndWipeable() {
        val parsed=OpenFluxDocumentWire.chunk(frame(OpenFluxDocumentWire.CHUNK,chunk()))!!
        assertEquals(id,parsed.id);assertEquals(0,parsed.offset);assertEquals("synthetic-private",parsed.bytes.toString(Charsets.UTF_8))
        assertEquals("OpenFluxDocumentChunk(redacted)",parsed.toString());parsed.close();assertTrue(parsed.bytes.all{it==0.toByte()})
        val max=OpenFluxDocumentWire.chunk(frame(OpenFluxDocumentWire.CHUNK,chunk(516096,Base64.getEncoder().encodeToString(ByteArray(8192){65}))))!!
        assertEquals(8192,max.bytes.size);max.close()
    }
    @Test fun chunkRejectsOffsetsCoercionsNoncanonicalAndOversizedData() {
        for(offset in listOf<Any>(-1,1,524288,Long.MAX_VALUE,"0",true))
            assertNull(OpenFluxDocumentWire.chunk(frame(OpenFluxDocumentWire.CHUNK,chunk(offset))))
        assertNull(OpenFluxDocumentWire.chunk((OpenFluxDocumentWire.CHUNK+"{\"id\":\"$id\",\"offset\":0.0,\"data\":\"Zg==\"}").toByteArray()))
        for(data in listOf<Any>("","Zg","Zh==","Zg==\n",true,Base64.getEncoder().encodeToString(ByteArray(8193){65})))
            assertNull(OpenFluxDocumentWire.chunk(frame(OpenFluxDocumentWire.CHUNK,chunk(data=data))))
        assertNull(OpenFluxDocumentWire.chunk(frame(OpenFluxDocumentWire.CHUNK,chunk().put("extra","synthetic"))))
        assertNull(OpenFluxDocumentWire.chunk(ByteArray(12001){65}))
    }
    @Test fun endRequiresOnlyExactTicketAndNoEmbeddedNulOrTrailingData() {
        val data=JSONObject().put("id",id);val good=frame(OpenFluxDocumentWire.END,data)
        assertEquals(id,OpenFluxDocumentWire.end(good)!!.id)
        assertEquals("OpenFluxDocumentEnd(redacted)",OpenFluxDocumentWire.end(good).toString())
        assertNull(OpenFluxDocumentWire.end(good+"\u0000 trailing".toByteArray()))
        assertNull(OpenFluxDocumentWire.end(good+" trailing".toByteArray()))
        assertNull(OpenFluxDocumentWire.end(frame(OpenFluxDocumentWire.END,data.put("html","synthetic-private"))))
    }
    @Test fun unsupportedPrivateFamilyIsConsumedEvenMalformedOversizedOrAfterClose() {
        var rejected=0
        for(bytes in listOf(frame(OpenFluxDocumentWire.META,meta()),
            "MCRF_OPENFLUX_DOCUMENT_VENDOR synthetic-private".toByteArray(),
            "MCRF_OPENFLUX_DOC\u0000 synthetic-private".toByteArray(),
            OpenFluxDocumentWire.CHUNK.toByteArray()+ByteArray(40000){65})) {
            assertTrue(OpenFluxDocumentWire.discardUnsupported(bytes,false){rejected++})
            val before=rejected
            assertTrue(OpenFluxDocumentWire.discardUnsupported(bytes,true){rejected++})
            assertEquals(before,rejected)
        }
        assertEquals(4,rejected)
        val detail=CarrierFailureDetail("openflux",JSONObject())
        detail.offer("MCRF_OPENFLUX_DOCUMENT_VENDOR error synthetic-private $id")
        val description=detail.describe("выход",1)
        assertFalse(description.contains("synthetic-private"));assertFalse(description.contains(id))
        for(bytes in listOf("ordinary output".toByteArray(),"MCRF_READY".toByteArray(),OpenFluxVerificationWire.PENDING.toByteArray()))
            assertFalse(OpenFluxDocumentWire.discardUnsupported(bytes,false){rejected++})
        assertEquals(4,rejected)
    }
}

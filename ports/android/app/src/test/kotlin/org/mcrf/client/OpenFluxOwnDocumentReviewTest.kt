package org.mcrf.client

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class OpenFluxOwnDocumentReviewTest {
    private val child="a".repeat(32);private val ticket="b".repeat(32)
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun feed(doc:OpenFluxOwnDocument,bytes:ByteArray){var offset=0;while(offset<bytes.size){val end=minOf(offset+8192,bytes.size);assertTrue(doc.append(child,ticket,offset,bytes.copyOfRange(offset,end)));offset=end}}
    @Test fun splitUnicodeDocumentRequiresCompleteHashAndIsOneShot() {
        val text="x".repeat(8191)+"я"+"<html>synthetic</html>";val raw=text.toByteArray()
        val doc=OpenFluxOwnDocument(child,ticket,raw.size,sha(raw)){true}
        assertNull(doc.take(child,ticket));feed(doc,raw)
        assertEquals(text,doc.take(child,ticket));assertNull(doc.take(child,ticket))
        assertEquals("OpenFluxOwnDocument(redacted)",doc.toString())
    }
    @Test fun staleIdentityDoesNotPoisonCurrentDocumentAndCallerMutationCannotRewriteIt() {
        val raw="synthetic own page".toByteArray();val original=raw.copyOf()
        val doc=OpenFluxOwnDocument(child,ticket,raw.size,sha(raw)){true}
        assertFalse(doc.append("c".repeat(32),ticket,0,raw));assertFalse(doc.append(child,"d".repeat(32),0,raw))
        assertTrue(doc.append(child,ticket,0,raw));raw.fill(0)
        assertNull(doc.take(child,"d".repeat(32)));assertEquals(original.toString(Charsets.UTF_8),doc.take(child,ticket))
    }
    @Test fun gapDuplicateShortAndOversizedChunksCloseTheExactDocument() {
        val raw=ByteArray(9000){65}
        for(kind in 0..3){
            val doc=OpenFluxOwnDocument(child,ticket,raw.size,sha(raw)){true}
            when(kind){
                0->assertFalse(doc.append(child,ticket,8192,raw.copyOfRange(8192,raw.size)))
                1->{assertTrue(doc.append(child,ticket,0,raw.copyOfRange(0,8192)));assertFalse(doc.append(child,ticket,0,raw.copyOfRange(0,8192)))}
                2->assertFalse(doc.append(child,ticket,0,ByteArray(8191)))
                else->assertFalse(doc.append(child,ticket,0,ByteArray(8193)))
            }
            assertFalse(doc.append(child,ticket,0,raw.copyOfRange(0,8192)));assertNull(doc.take(child,ticket))
        }
    }
    @Test fun wrongDigestInvalidUtf8AndRetiredChildExposeNoDocument() {
        val raw="synthetic".toByteArray();val bad=byteArrayOf(0xff.toByte())
        val wrong=OpenFluxOwnDocument(child,ticket,raw.size,"0".repeat(64)){true};feed(wrong,raw);assertNull(wrong.take(child,ticket))
        val invalid=OpenFluxOwnDocument(child,ticket,1,sha(bad)){true};feed(invalid,bad);assertNull(invalid.take(child,ticket))
        var alive=true;val retired=OpenFluxOwnDocument(child,ticket,raw.size,sha(raw)){alive};feed(retired,raw);alive=false
        assertNull(retired.take(child,ticket));assertFalse(retired.append(child,ticket,0,raw))
    }
    @Test fun metadataBoundsAndExactMaximumAreEnforced() {
        for(size in listOf(0,524289))assertThrows(IllegalArgumentException::class.java){OpenFluxOwnDocument(child,ticket,size,"0".repeat(64)){true}}
        assertThrows(IllegalArgumentException::class.java){OpenFluxOwnDocument("bad",ticket,1,"0".repeat(64)){true}}
        assertThrows(IllegalArgumentException::class.java){OpenFluxOwnDocument(child,ticket,1,"BAD"){true}}
        val raw=ByteArray(524288){65};val doc=OpenFluxOwnDocument(child,ticket,raw.size,sha(raw)){true};feed(doc,raw)
        assertEquals(raw.size,doc.take(child,ticket)?.length)
    }
}

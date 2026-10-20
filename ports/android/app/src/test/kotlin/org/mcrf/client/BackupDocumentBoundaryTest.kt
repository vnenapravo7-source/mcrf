package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Queued with P0; no tests executed during individual edits. */
class BackupDocumentBoundaryTest {
    @Test fun validUnicodeDocumentIsPreserved(){
        val raw="""{"message":"Белые списки","brackets":"[[[","escaped":"\"["}"""
        assertEquals("Белые списки",AndroidBackup.decodeDocument(raw.toByteArray()).getString("message"))
    }
    @Test fun malformedUtf8IsRejectedInsteadOfSilentlyChangingData(){
        assertThrows(IllegalArgumentException::class.java){AndroidBackup.decodeDocument(byteArrayOf(0xc3.toByte(),0x28))}
    }
    @Test fun excessiveNestingIsRejectedBeforeRecursiveJsonParse(){
        val deep="{\"nested\":"+"[".repeat(70)+"0"+"]".repeat(70)+"}"
        val singleQuoted="{\"pad\":'"+"}".repeat(100)+"',\"nested\":"+deep+"}"
        val commented="{\"pad\":0,/*"+"}".repeat(100)+"*/\"nested\":"+deep+"}"
        for(raw in listOf(deep,singleQuoted,commented))
            assertThrows(IllegalArgumentException::class.java){AndroidBackup.decodeDocument(raw.toByteArray())}
    }
    @Test fun secondDocumentIsNotIgnored(){
        for(raw in listOf("{} {}","{}\u0000{}","{}\u0000","{\"hidden\":\"\u0000\"}"))
            assertThrows(IllegalArgumentException::class.java){AndroidBackup.decodeDocument(raw.toByteArray())}
        assertEquals("\u0000",AndroidBackup.decodeDocument("""{"escaped":"\u0000"}""".toByteArray()).getString("escaped"))
    }
}

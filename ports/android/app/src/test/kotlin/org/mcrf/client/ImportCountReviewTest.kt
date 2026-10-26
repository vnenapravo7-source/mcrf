package org.mcrf.client

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** New plaintext/base64 count-bound cases; no network or stored catalogue. */
class ImportCountReviewTest {
    private fun links(count:Int)=(1..count).joinToString("\n") {"socks://127.0.0.1:${10000+it}#owned-$it"}
    @Test fun plaintextAtExistingJsonLimitStillImportsAllEntries(){
        assertEquals(1000,ServerImport.read(links(1000)).size)
    }
    @Test fun plaintextCannotBypassJsonCollectionLimit(){
        try{ServerImport.read(links(1001));fail("Must reject rather than retaining unbounded entries")}
        catch(error:IllegalArgumentException){assertEquals("В импорте больше 1000 подключений.",error.message)}
    }
    @Test fun encodedSubscriptionCannotBypassTheSameLimit(){
        val encoded=Base64.getEncoder().encodeToString(links(1001).toByteArray())
        try{ServerImport.read(encoded);fail("Decoded lists must have the same bound")}
        catch(error:IllegalArgumentException){assertEquals("В импорте больше 1000 подключений.",error.message)}
    }
    @Test fun duplicateLinksDoNotConsumeDistinctServerCapacity(){
        assertEquals(1,ServerImport.read(List(1001){"socks://127.0.0.1:1080#same"}.joinToString("\n")).size)
    }
    @Test fun failedImportPlanNeverWritesPartOfTheList(){
        var fetched=0
        try{ImportPlan.prepare(links(1001)){fetched++;error("No URL fetch allowed")};fail("No partial plan")}
        catch(error:IllegalArgumentException){assertEquals("В импорте больше 1000 подключений.",error.message)}
        assertEquals(0,fetched)
    }
}

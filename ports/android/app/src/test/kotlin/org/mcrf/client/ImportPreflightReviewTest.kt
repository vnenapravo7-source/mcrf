package org.mcrf.client

import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImportPreflightReviewTest {
    private fun result()=SubscriptionClient.Downloaded(ServerImport.read("socks://127.0.0.1:1080#fixture"),JSONObject())
    @Test fun tooManySubscriptionAddressesFailBeforeAnyFetch() {
        var calls=0
        try {ImportPlan.prepare((0..ConnectionCatalogue.MAX_SUBSCRIPTIONS).joinToString("\n"){"https://fixture.invalid/sub/$it"}){calls++;result()};fail("Must reject")}
        catch(_:IllegalArgumentException) { }
        assertEquals(0,calls)
    }
    @Test fun invalidLocalEntryFailsBeforeAnyFetch() {
        var calls=0
        try {ImportPlan.prepare("https://fixture.invalid/sub\nnot-a-profile"){calls++;result()};fail("Must reject")}
        catch(_:IllegalArgumentException) { }
        assertEquals(0,calls)
    }
    @Test fun existingSubscriptionBoundaryIsStillAccepted() {
        var calls=0
        val plan=ImportPlan.prepare((0 until ConnectionCatalogue.MAX_SUBSCRIPTIONS).joinToString("\n"){"https://fixture.invalid/sub/$it"}){calls++;result()}
        assertEquals(ConnectionCatalogue.MAX_SUBSCRIPTIONS,calls);assertEquals(1,plan.entries.size)
    }
    @Test fun duplicateAddressesAreFetchedOnceAndKeepOrder() {
        val calls=mutableListOf<String>()
        ImportPlan.prepare("https://fixture.invalid/first\nhttps://fixture.invalid/second\nhttps://fixture.invalid/first"){calls.add(it);result()}
        assertEquals(listOf("https://fixture.invalid/first","https://fixture.invalid/second"),calls)
    }
    @Test fun fileUtf8DoesNotReplaceMalformedCredentialBytes() {
        try {ImportIO.readText(ByteArrayInputStream(byteArrayOf(0x61,0xc3.toByte(),0x28)));fail("Must reject")}
        catch(error:IllegalArgumentException) {assertTrue(error.message.orEmpty().contains("UTF-8"))}
    }
    @Test fun fileUtf8RejectsTruncatedMultibyteText() {
        try {ImportIO.readText(ByteArrayInputStream(byteArrayOf(0xe2.toByte(),0x82.toByte())));fail("Must reject")}
        catch(_:IllegalArgumentException) { }
    }
    @Test fun fileUtf8PreservesUnicodeAndLineBreaks() {
        val text="socks://127.0.0.1:1080#Тест\r\n"
        assertEquals(text,ImportIO.readText(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }
}

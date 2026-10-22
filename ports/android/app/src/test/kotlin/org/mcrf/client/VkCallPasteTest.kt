package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class VkCallPasteTest {
    @Test fun multilinePasteSplitsBeforeSingleLineNormalizationWithoutBreakingPlus() {
        assertEquals(listOf("first+hash","https://vk.com/call/join/second","third_hash"),VkCallPaste.split("first+hash\r\nhttps://vk.com/call/join/second\nthird_hash"))
    }
    @Test fun distributingFirstSlotRetainsOthersWithoutLegacySixCallLimit() {
        val old=listOf("old-first","keep-second")
        assertEquals(listOf("new-first","new-second","keep-second"),VkCallPaste.distribute(old,listOf("new-first","new-second"),6))
        assertEquals(listOf("one","two","three","four","five","six","keep-second"),VkCallPaste.distribute(old,listOf("one","two","three","four","five","six"),6))
        assertEquals(listOf("old-first","keep-second"),old)
    }
    @Test fun duplicateCallsAreNotAddedAsExtraSlotsAndLargePasteIsRejected() {
        assertEquals(listOf("a","b"),VkCallPaste.distribute(listOf("old","b"),listOf("a","b"),6))
        try{VkCallPaste.split("x".repeat(65537));fail("unbounded paste accepted")}catch(_:IllegalArgumentException){}
    }
}

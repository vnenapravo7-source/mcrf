package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class FallbackChainStoreTest {
    private val first = "a".repeat(64)
    private val second = "b".repeat(64)
    @Test fun roundTripKeepsOrderAndScopesWithoutConnectionSecrets() {
        val settings = FallbackChainStore.Settings(true, listOf(second, first), listOf(first))
        val encoded = FallbackChainStore.encode(settings)
        assertEquals(settings, FallbackChainStore.decode(encoded))
        assertEquals(setOf("schema", "enabled", "vpn", "bs"), org.json.JSONObject(encoded).keys().asSequence().toSet())
        assertFalse(encoded.contains("password")); assertFalse(encoded.contains("https://"))
    }
    @Test fun allUnselectedRemainsAnEmptyOptIn() {
        val settings = FallbackChainStore.Settings(true)
        assertEquals(settings, FallbackChainStore.decode(FallbackChainStore.encode(settings)))
        assertTrue(settings.ids(false).isEmpty()); assertTrue(settings.ids(true).isEmpty())
    }
    @Test fun corruptUnknownVersionDuplicateAndOversizedDataAreNotSilentlyReplaced() {
        val valid = FallbackChainStore.encode(FallbackChainStore.Settings(true, listOf(first)))
        val invalid = listOf("{", valid + "unexpected", valid.replace("\"schema\":1", "\"schema\":2"),
            "{\"schema\":1,\"enabled\":true,\"vpn\":[\"$first\",\"$first\"],\"bs\":[]}", " ".repeat(8193))
        for (source in invalid) {
            try { FallbackChainStore.decode(source); fail("Invalid sidecar accepted") }
            catch (_: Exception) { /* Caller surfaces the failure and preserves the file. */ }
        }
    }
}

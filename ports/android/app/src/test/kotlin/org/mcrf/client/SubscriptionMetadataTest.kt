package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionMetadataTest {
    private val nodes = ServerImport.read("vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#Server")
    @Test fun quotaSurvivesEncryptedModelRoundTrip() {
        val data = ConnectionCatalogue(""); val meta = JSONObject().put("total", 10_000_000_000L).put("download", 1_000_000_000L).put("expire", 2_000_000_000L).put("deviceLimit", 3)
        data.replaceSubscription("https://example.com/token", nodes, meta)
        val restored = ConnectionCatalogue(data.serialize()).subscriptions().single()
        assertEquals(10_000_000_000L, restored.getJSONObject("metadata").getLong("total"))
        assertEquals(3, restored.getJSONObject("metadata").getInt("deviceLimit"))
    }
    @Test fun updatedHeadersReplaceStaleLimits() {
        val data = ConnectionCatalogue("")
        data.replaceSubscription("https://example.com/token", nodes, JSONObject().put("total", 100L))
        data.replaceSubscription("https://example.com/token", nodes, JSONObject())
        assertFalse(data.subscriptions().single().getJSONObject("metadata").has("total"))
    }
    @Test fun editUrlRetainsIdAndRefreshesQuota() {
        val data = ConnectionCatalogue(""); data.replaceSubscription("https://example.com/old", nodes)
        val id = data.subscriptions().single().getString("id")
        data.editSubscription(id, "https://example.com/new", nodes, JSONObject().put("deviceLimit", 5))
        val sub = data.subscriptions().single()
        assertEquals(id, sub.getString("id")); assertEquals(5, sub.getJSONObject("metadata").getInt("deviceLimit"))
    }
}

package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionChangesTest {
    private val a = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#A"
    private val b = "trojan://fixture@example.com:444?security=tls#B"
    private val c = "trojan://fixture@example.com:445?security=tls#C"
    private val url = "https://example.com/sub"
    @Test fun clearCountsAndManualServersSurvive() {
        val data = ConnectionCatalogue(""); data.add(c); data.replaceSubscription(url,ServerImport.read("$a\n$b"))
        val changed = data.replaceSubscription(url,ServerImport.read("${a.substringBefore('#')}#Renamed\n$c"),JSONObject(),true)
        assertEquals(SubscriptionChanges(1,1,1,1),changed)
        assertTrue(data.entries().any { it.original == c })
        assertEquals(2,data.entries().size)
    }
    @Test fun retainOldModeUpdatesNamesAndMetadataWithoutRemovingStale() {
        val data = ConnectionCatalogue(""); data.replaceSubscription(url,ServerImport.read("$a\n$b"))
        val result = data.replaceSubscription(url,ServerImport.read("${a.substringBefore('#')}#Renamed\n$c"),JSONObject().put("total",9),false)
        assertEquals(SubscriptionChanges(1,1,0,1),result)
        assertEquals(3,data.entries().size)
        val restored = ConnectionCatalogue(data.serialize())
        assertEquals(9,restored.subscriptions().single().getJSONObject("metadata").getInt("total"))
        assertTrue(restored.entries().any { it.original == b })
    }
    @Test fun unchangedRefreshPreservesSelectionEditsAndProbes() {
        val data = ConnectionCatalogue(""); data.replaceSubscription(url,ServerImport.read(a)); val id = data.entries().single().id
        data.setAuto(id,true); data.recordProbe(id,42,100)
        assertEquals(SubscriptionChanges(0,0,0,1),data.replaceSubscription(url,ServerImport.read(a)))
        assertTrue(id in data.autoIds()); assertEquals(42,data.probes().getJSONObject(id).getLong("delay"))
    }
    @Test fun renamePreservesAutoPoolAndProbe() {
        val data = ConnectionCatalogue(""); data.replaceSubscription(url,ServerImport.read(a)); val id = data.entries().single().id
        data.setAuto(id,true); data.recordProbe(id,42,100)
        data.replaceSubscription(url,ServerImport.read("${a.substringBefore('#')}#Renamed"))
        val next = data.entries().single().id
        assertTrue(next in data.autoIds()); assertEquals(42,data.probes().getJSONObject(next).getLong("delay"))
    }
    @Test fun urlEditRetainsSubscriptionIdAndHonorsKeepMode() {
        val data = ConnectionCatalogue(""); data.replaceSubscription(url,ServerImport.read(a)); val id = data.subscriptions().single().getString("id")
        assertEquals(SubscriptionChanges(1,0,0,1),data.editSubscription(id,"https://example.com/new",ServerImport.read(b),JSONObject(),false))
        assertEquals(id,data.subscriptions().single().getString("id")); assertEquals(2,data.entries().size)
    }
    @Test fun commitUsesCurrentSnapshotAndCannotResurrectDeletedOrRecreatedSubscription() {
        val data = ConnectionCatalogue(""); data.replaceSubscription(url,ServerImport.read(a))
        val expected = mapOf(url to data.subscriptions().single().getString("id"))
        val fetched = mapOf(url to SubscriptionClient.Downloaded(ServerImport.read(b),JSONObject()))
        data.add(c); data.setAuto(data.entries().first { it.original==c }.id,true)
        data.removeSubscription(expected.getValue(url))
        assertEquals(SubscriptionChanges(),data.refreshFetched(fetched,true,expected))
        data.replaceSubscription(url,ServerImport.read(a))
        assertEquals(SubscriptionChanges(),data.refreshFetched(fetched,true,expected))
        assertTrue(data.entries().any { it.original==a }); assertTrue(data.entries().any { it.original==c })
        assertTrue(data.entries().first { it.original==c }.id in data.autoIds())
    }
    @Test fun changesAreAggregatedPerSubscriptionEvenWithSharedServers() {
        val data = ConnectionCatalogue(""); data.replaceSubscription(url,ServerImport.read(a)); data.replaceSubscription("https://example.org/sub",ServerImport.read(a))
        val fetched = mapOf(url to SubscriptionClient.Downloaded(ServerImport.read(b),JSONObject()),"https://example.org/sub" to SubscriptionClient.Downloaded(ServerImport.read(b),JSONObject()))
        assertEquals(SubscriptionChanges(2,0,2,2),data.refreshFetched(fetched,true))
    }
}

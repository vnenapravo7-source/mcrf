package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class InviteCatalogueSnapshotTest {
    private fun server(port:Int)=ServerImport.read("socks://127.0.0.1:$port").single()
    @Test fun updateReplacesOwnedGroupAndRetainsUnrelatedManualAndSubscriptions() {
        val catalogue=ConnectionCatalogue("");val manual=server(10001);val first=server(10002);val second=server(10003)
        catalogue.add(manual.original);catalogue.replaceSubscription("https://example.invalid/sub",listOf(first))
        catalogue.replaceInvite(listOf(first,second));catalogue.replaceInvite(listOf(server(10004)))
        assertEquals(setOf(manual.id,first.id,server(10004).id),catalogue.entries().map {it.id}.toSet())
        assertEquals(listOf(server(10004).id),catalogue.inviteEntries().map {it.id})
        assertEquals(catalogue.serialize(),ConnectionCatalogue(catalogue.serialize()).serialize())
    }
    @Test fun firstManagedSnapshotAdoptsMatchingLegacyRecordsOnlyOnce() {
        val catalogue=ConnectionCatalogue("");val manual=server(10001);val legacy=server(10002)
        catalogue.add(manual.original+"\n"+legacy.original)
        catalogue.replaceInvite(listOf(legacy));catalogue.replaceInvite(emptyList())
        assertEquals(listOf(manual.id),catalogue.entries().map {it.id})
    }
    @Test fun successfulEmptySnapshotRemovesOwnedGroupOnly() {
        val catalogue=ConnectionCatalogue("");val manual=server(10001)
        catalogue.add(manual.original);catalogue.replaceInvite(listOf(server(10002)))
        catalogue.replaceInvite(emptyList());assertEquals(listOf(manual.id),catalogue.entries().map {it.id})
    }
    @Test fun removingAnOwnedServerWorksAndNextSnapshotCanRestoreIt() {
        val catalogue=ConnectionCatalogue("");val owned=server(10002)
        catalogue.replaceInvite(listOf(owned));catalogue.remove(owned.id);assertTrue(catalogue.entries().isEmpty())
        catalogue.replaceInvite(listOf(owned));assertEquals(listOf(owned.id),catalogue.entries().map {it.id})
    }
    @Test fun invalidOwnedSectionFailsRestoreValidation() {
        try {ConnectionCatalogue("{\"version\":1,\"servers\":[],\"subscriptions\":[],\"inviteServers\":\"broken\"}");fail()}
        catch(_:org.json.JSONException){}
    }
}

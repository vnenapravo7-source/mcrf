package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionGroupingTest {
    private val a="vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#A"
    private val b="trojan://fixture@example.com:444?security=tls#B"
    private val c="trojan://fixture@example.com:445?security=tls#C"
    @Test fun allServersGroupedOnceAndManualOrderRetained() {
        val data=ConnectionCatalogue("");data.add(c);data.replaceSubscription("https://one.example/sub?token=private-fixture",ServerImport.read("$a\n$b"))
        data.replaceSubscription("https://two.example/sub",ServerImport.read(b))
        val before=data.serialize();val groups=SubscriptionGrouping.groups(data.entries(),data.subscriptions())
        assertEquals(listOf("Подключения","one.example","two.example"),groups.map {it.title})
        assertEquals(listOf("C"),groups[0].entries.map {it.name});assertEquals(listOf("A","B"),groups[1].entries.map {it.name})
        assertTrue(groups[2].entries.isEmpty());assertNotNull(groups[2].subscription)
        assertEquals(3,groups.flatMap {it.entries}.map {it.id}.toSet().size)
        assertEquals(before,data.serialize());assertFalse(groups.any {it.title.contains("private-fixture")})
    }
    @Test fun subscriptionTakesSharedManualServerWithoutDuplicateAndUsesValidatedTitle() {
        val data=ConnectionCatalogue("");data.add(a);data.replaceSubscription("https://one.example/sub",ServerImport.read(a),JSONObject().put("title","Provider title"))
        val groups=SubscriptionGrouping.groups(data.entries()+data.entries(),data.subscriptions()+data.subscriptions())
        assertEquals(1,groups.size);assertEquals("Provider title",groups.single().title);assertEquals(1,groups.single().entries.size)
        assertEquals(data.subscriptions().single().getString("id"),groups.single().id)
        groups.single().subscription!!.put("url","https://changed.example/sub")
        assertEquals("https://one.example/sub",data.subscriptions().single().getString("url"))
    }
    @Test fun globallyMeasuredOrderSelectionAndProbesSurviveGrouping() {
        val data=ConnectionCatalogue("");data.replaceSubscription("https://example.com/sub",ServerImport.read("$a\n$b"))
        val entries=data.entries();data.setAuto(entries[0].id,true);data.recordProbe(entries[0].id,42,100)
        val before=data.serialize();val grouped=SubscriptionGrouping.groups(entries.reversed(),data.subscriptions())
        assertEquals(listOf("B","A"),grouped.single().entries.map {it.name});assertEquals(before,data.serialize())
    }
    @Test fun oldManualCatalogueAndEmptyCatalogueStillWork() {
        val data=ConnectionCatalogue("");assertTrue(SubscriptionGrouping.groups(data.entries(),data.subscriptions()).isEmpty())
        data.add(a);assertEquals(listOf("A"),SubscriptionGrouping.groups(data.entries(),data.subscriptions()).single().entries.map {it.name})
    }
    @Test fun emptySubscriptionsKeepActionsAndDeletedSubscriptionsDoNotRegainRows() {
        val data=ConnectionCatalogue("");data.add(c);data.replaceSubscription("https://one.example/sub",ServerImport.read("$a\n$b"))
        data.replaceSubscription("https://two.example/sub",ServerImport.read(b))
        val removed=data.subscriptions()[0].getString("id");data.removeSubscription(removed)
        val groups=SubscriptionGrouping.groups(data.entries(),data.subscriptions())
        assertEquals(listOf("Подключения","two.example"),groups.map {it.title})
        assertEquals(listOf("C","B"),groups.flatMap {it.entries}.map {it.name})
        data.remove(data.entries().first {it.original==b}.id)
        val empty=SubscriptionGrouping.groups(data.entries(),data.subscriptions()).last()
        assertTrue(empty.entries.isEmpty());assertNotNull(empty.subscription);assertEquals("two.example",empty.title)
    }
    @Test fun invalidMetadataTitleFallsBackToHostAndSubscriptionOrderIsDeterministic() {
        val data=ConnectionCatalogue("");data.replaceSubscription("https://one.example/sub?secret=fixture",ServerImport.read(a),JSONObject().put("title","bad\ntitle"))
        data.replaceSubscription("https://two.example/sub",ServerImport.read(b),JSONObject().put("title","Second"))
        val groups=SubscriptionGrouping.groups(data.entries().reversed(),data.subscriptions())
        assertEquals(listOf("one.example","Second"),groups.map {it.title})
        assertEquals(listOf("A","B"),groups.flatMap {it.entries}.map {it.name})
    }
}

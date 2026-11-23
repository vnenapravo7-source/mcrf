package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionPresentationTest {
    private val a="trojan://fixture@one.example:443?security=tls#A"
    private val b="trojan://fixture@two.example:443?security=tls#B"
    private fun catalogue()=ConnectionCatalogue("").apply {replaceSubscription("https://provider.example/sub",ServerImport.read(a),JSONObject().put("title","Provider"));add(b)}
    @Test fun hiddenSubscriptionKeepsProfilesAndCanBeRestoredAfterReload(){
        val data=catalogue();val id=data.subscriptions().single().getString("id");val originalIds=data.entries().map {it.id}
        data.setSubscriptionHidden(id,true)
        val restored=ConnectionCatalogue(data.serialize())
        assertEquals(originalIds,restored.entries().map {it.id})
        assertEquals(setOf(ServerImport.read(a).single().id),SubscriptionGrouping.hiddenEntryIds(restored.entries(),restored.subscriptions()))
        restored.setSubscriptionHidden(id,false)
        assertTrue(SubscriptionGrouping.hiddenEntryIds(restored.entries(),restored.subscriptions()).isEmpty())
    }
    @Test fun customTitleAndVisibilitySurviveRefreshAndUrlChange(){
        val data=catalogue();val id=data.subscriptions().single().getString("id")
        data.renameSubscription(id,"My subscription");data.setSubscriptionHidden(id,true)
        data.replaceSubscription("https://provider.example/sub",ServerImport.read(a),JSONObject().put("title","New provider title"))
        data.editSubscription(id,"https://replacement.example/sub",ServerImport.read(a),JSONObject().put("title","Replacement"))
        val sub=ConnectionCatalogue(data.serialize()).subscriptions().single()
        assertEquals("My subscription",SubscriptionGrouping.title(sub));assertTrue(sub.getBoolean("hidden"));assertEquals(id,sub.getString("id"))
    }
    @Test fun hideAppliesToNewNodesAfterSubscriptionRefresh(){
        val data=catalogue();val id=data.subscriptions().single().getString("id");data.setSubscriptionHidden(id,true)
        data.replaceSubscription("https://provider.example/sub",ServerImport.read("$a\n$b"))
        assertEquals(2,SubscriptionGrouping.hiddenEntryIds(data.entries(),data.subscriptions()).size)
    }
    @Test fun deletingHiddenSubscriptionKeepsManualProfiles(){
        val data=catalogue();val id=data.subscriptions().single().getString("id");data.setSubscriptionHidden(id,true);data.removeSubscription(id)
        assertEquals(listOf("B"),data.entries().map {it.name});assertTrue(data.subscriptions().isEmpty())
    }
    @Test fun invalidNamesAndMissingSubscriptionsDoNotMutateCatalogue(){
        val data=catalogue();val id=data.subscriptions().single().getString("id");val before=data.serialize()
        for(title in listOf(" ","a\nb","x".repeat(257))){try{data.renameSubscription(id,title);fail()}catch(_:IllegalArgumentException){}}
        try{data.setSubscriptionHidden("missing",true);fail()}catch(_:IllegalArgumentException){}
        assertEquals(before,data.serialize())
    }
}

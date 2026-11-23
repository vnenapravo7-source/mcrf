package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VkSettingsDeferredTest {
    private class Memory:VkRelayPersistence {
        val root=JSONObject().put("vk",JSONObject().put("token","old").put("user","42"))
            .put("vkSettings",JSONObject().put("manual",JSONArray().put("A".repeat(43))).put("manualSupplementAll",true))
        var reads=0
        override fun get(section:String):JSONObject {reads++;return JSONObject(root.optJSONObject(section)?.toString()?:"{}")}
        override fun update(section:String,change:(JSONObject)->Unit){
            val value=JSONObject(root.optJSONObject(section)?.toString()?:"{}");change(value);root.put(section,value)
        }
        override fun remove(section:String){root.remove(section)}
    }

    @Test fun initialControlsUseOneGenerationAndRuntimeReturnsToLiveAccount() {
        val live=Memory()
        val snapshot=VkSettingsReadSnapshot(live,JSONObject(live.root.toString()))
        val account=VkRelayAccount(snapshot)
        live.root.remove("vk")
        live.root.getJSONObject("vkSettings").put("manual",JSONArray().put("B".repeat(43)))
        assertTrue(account.linked())
        assertEquals(listOf("A".repeat(43)),account.manualDefaults())
        assertTrue(account.supplementAll())
        snapshot.get("vkSettings").remove("manual")
        assertEquals(1,account.manualDefaults().size)
        assertEquals(0,live.reads)
        snapshot.release()
        assertFalse(account.linked())
        assertEquals(listOf("B".repeat(43)),account.manualDefaults())
        assertEquals(2,live.reads)
    }

    @Test fun editsAndUnlinkPreserveManualHashesAndOtherSections() {
        val live=Memory()
        live.root.put("other",JSONObject().put("keep",true))
        val snapshot=VkSettingsReadSnapshot(live,JSONObject(live.root.toString()))
        val account=VkRelayAccount(snapshot)
        snapshot.release()
        account.setManualDefaults(listOf("B".repeat(43)))
        account.setSupplementAll(false)
        account.unlink()
        assertFalse(account.linked())
        assertEquals(listOf("https://vk.ru/call/join/"+"B".repeat(43)),account.manualDefaults())
        assertFalse(account.supplementAll())
        assertTrue(live.root.getJSONObject("other").getBoolean("keep"))
    }

    @Test fun retryAndNavigationRejectOldDeliveryEvenAfterReattachment() {
        val delivery=VkSettingsDelivery()
        val first=delivery.begin()
        assertTrue(delivery.accepts(first))
        val retry=delivery.begin()
        assertFalse(delivery.accepts(first));assertTrue(delivery.accepts(retry))
        delivery.cancel()
        assertFalse(delivery.accepts(retry))
        assertFalse(delivery.accepts(delivery.begin()))
        val nextPage=VkSettingsDelivery()
        assertTrue(nextPage.accepts(nextPage.begin()))
    }
}

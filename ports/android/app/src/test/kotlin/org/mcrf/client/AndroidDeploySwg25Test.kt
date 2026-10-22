package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidDeploySwg25Test {
    @Test fun upgradingThreeDraftsAddsDisabledModernChoiceWithoutMutatingOldDrafts(){
        val old=JSONArray().apply {
            listOf("qwdtt","wdtt","csqtt").forEachIndexed {i,kind->put(JSONObject()
                .put("kind",kind).put("enabled",true).put("relay_port",56003+i*10)
                .put("wg_port",51823+i*10).put("call_count",6).put("vk_calls",JSONArray()))}
        }
        val before=old.toString()
        val upgraded=AndroidDeploySwg.drafts(JSONObject().put("swg_profiles",old))
        assertEquals(before,old.toString());assertEquals(4,upgraded.length())
        repeat(3){assertEquals(old.getJSONObject(it).toString(),upgraded.getJSONObject(it).toString())}
        val modern=upgraded.getJSONObject(3)
        assertFalse(modern.getBoolean("enabled"));assertEquals("csqtt25",modern.getString("kind"))
        assertEquals("56033",modern.getString("relay_port"))
        assertEquals("51853",modern.getString("wg_port"))
        assertEquals(upgraded.toString(),AndroidDeploySwg.drafts(JSONObject().put("swg_profiles",upgraded)).toString())
    }

    @Test fun modernQuickChoiceUsesCsqttCallsAndNoWireGuardPort(){
        val calls=(1..8).map {i->"https://vk.ru/call/join/"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){i.toByte()})}
        val selected=AndroidDeployQuickConnection.options("swg","csqtt25",19001,19002,"","",calls,false,6)
        AndroidDeploySwg.validateCalls(selected)
        assertEquals("csqtt25",selected.getString("relay_kind"))
        assertEquals("csqtt",AndroidDeploySwg.callKind("csqtt25"))
        assertEquals(listOf(19001),AndroidDeploySwg.ports(selected))
        assertNotEquals(AndroidDeploySwg.groupId("fixture","csqtt"),AndroidDeploySwg.groupId("fixture","csqtt25"))
        AndroidDeployBackupSettings.validate(JSONObject().put("deployDrafts",JSONObject().put("swg",JSONObject().put("options",selected))))
    }

    @Test fun inlineServerExpansionNeverPushesHistoryAndSurvivesPanelBack(){
        val nav=AndroidDeployBrowserUi.Navigation()
        val servers=AndroidDeployBrowserUi.Destination("servers")
        nav.push(servers)
        assertTrue(nav.toggleServer("fixture-server"));assertEquals(1,nav.history.size)
        nav.push(AndroidDeployBrowserUi.Destination("panel","fixture-panel"))
        assertTrue(nav.back());assertEquals(servers,nav.destination)
        assertTrue("fixture-server" in nav.expandedServers)
        assertFalse(nav.toggleServer("fixture-server"));assertEquals(1,nav.history.size)
    }
}

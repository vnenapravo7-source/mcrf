package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidDeployInventoryTest {
    private fun result(host:String="192.0.2.1",panel:Int=8443)=JSONObject().put("recipe_id","swg")
        .put("panel_url","https://$host:$panel").put("transport_template",JSONObject().put("host",host).put("sshPort","22"))
    @Test fun sameProtocolAndPanelKeepDistinctDeploymentInstances(){
        val history=JSONObject().put("first",result()).put("second",result())
        val inventory=AndroidDeployInventory.fromHistory(JSONObject(),history)
        assertEquals(1,inventory.getJSONObject("servers").length())
        assertEquals(1,inventory.getJSONObject("panels").length())
        assertEquals(2,inventory.getJSONObject("instances").length())
        assertEquals(2,history.length())
    }
    @Test fun severalServersAndPanelsDoNotOverwriteEachOther(){
        val history=JSONObject().put("a",result()).put("b",result(panel=9443)).put("c",result("198.51.100.1"))
        val inventory=AndroidDeployInventory.fromHistory(JSONObject(),history)
        assertEquals(2,inventory.getJSONObject("servers").length())
        assertEquals(3,inventory.getJSONObject("panels").length())
        assertEquals(3,inventory.getJSONObject("instances").length())
    }
    @Test fun migrationIsIdempotentAndPreservesUserNames(){
        val history=JSONObject().put("a",result())
        val original=AndroidDeployInventory.fromHistory(JSONObject(),history)
        val key=original.getJSONObject("instances").keys().next()
        val renamed=AndroidDeployInventory.rename(original,"instances",key,"Мой сервер")
        val updated=AndroidDeployInventory.fromHistory(renamed,history)
        assertEquals("Мой сервер",updated.getJSONObject("instances").getJSONObject(key).getString("name"))
        assertEquals(1,updated.getJSONObject("instances").length())
        assertNotEquals("Мой сервер",original.getJSONObject("instances").getJSONObject(key).getString("name"))
    }
    @Test fun unknownServerReferencesAndUnsafeNamesAreRejected(){
        val inventory=AndroidDeployInventory.fromHistory(JSONObject(),JSONObject().put("a",result()))
        val key=inventory.getJSONObject("instances").keys().next()
        assertTrue(runCatching {AndroidDeployInventory.rename(inventory,"instances",key,"bad\nname")}.isFailure)
        inventory.getJSONObject("instances").getJSONObject(key).put("serverId","missing")
        assertTrue(runCatching {AndroidDeployInventory.validate(inventory)}.isFailure)
    }
    @Test fun legacyConnectionOnlyRecordRemainsInHistoryWithoutInventingServer(){
        val history=JSONObject().put("legacy",JSONObject().put("recipe_id","swg"))
        val inventory=AndroidDeployInventory.fromHistory(JSONObject(),history)
        assertEquals(0,inventory.getJSONObject("instances").length())
        assertTrue(history.has("legacy"))
    }
    @Test fun sameNamedDeploymentKeepsAllOperationResults(){
        val id=java.util.UUID.randomUUID().toString()
        val history=JSONObject().put("a",result().put("deployment_id",id).put("deployment_name","Первый"))
            .put("b",result().put("deployment_id",id).put("deployment_name","Первый"))
        val inventory=AndroidDeployInventory.fromHistory(JSONObject(),history)
        assertEquals(1,inventory.getJSONObject("instances").length())
        assertEquals(2,inventory.getJSONObject("instances").getJSONObject(id).getJSONArray("resultKeys").length())
    }
    @Test fun namedDeploymentCannotSilentlyMoveToAnotherVps(){
        val id=java.util.UUID.randomUUID().toString()
        val history=JSONObject().put("a",result().put("deployment_id",id).put("deployment_name","Первый"))
            .put("b",result("198.51.100.1").put("deployment_id",id).put("deployment_name","Первый"))
        assertTrue(runCatching {AndroidDeployInventory.fromHistory(JSONObject(),history)}.isFailure)
    }
}

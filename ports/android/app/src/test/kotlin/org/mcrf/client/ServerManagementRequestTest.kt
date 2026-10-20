package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerManagementRequestTest {
    private val all=ServerManagementRequest.Action.values().map {it.id}.toSet()
    @Test fun unknownOrExcludedCapabilitiesCannotEnableActions(){
        assertTrue(ServerManagementRequest.available("swg",setOf("delete-client","reset-traffic","server-limits")).isEmpty())
        assertTrue(ServerManagementRequest.available("wdtt",emptySet()).isEmpty())
        assertEquals(ServerManagementRequest.Action.values().size,ServerManagementRequest.available("3xui",all).size)
        try{ServerManagementRequest.build("swg",ServerManagementRequest.Action.RESTART,emptySet(),confirmed=true);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun mutationsNeedConfirmationAndExactParameters(){
        try{ServerManagementRequest.build("wdtt",ServerManagementRequest.Action.RESTART,all);fail()}catch(_:IllegalArgumentException){}
        val parameters=JSONObject().put("client_id","fixture-client").put("expires_at",0L)
        val request=ServerManagementRequest.build("wdtt",ServerManagementRequest.Action.CLIENT_EXPIRY,all,parameters,true)
        parameters.put("client_id","changed")
        assertEquals("fixture-client",request.getJSONObject("parameters").getString("client_id"))
        try{ServerManagementRequest.build("swg",ServerManagementRequest.Action.STATUS,all,JSONObject().put("command","anything"));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun opaqueClientIdsCannotBecomePathsOrShellCommands(){
        for(id in listOf("../client","client;restart","a\nb","/api/users")){
            try{ServerManagementRequest.build("swg",ServerManagementRequest.Action.CLIENT_LINK,all,JSONObject().put("client_id",id));fail()}catch(_:IllegalArgumentException){}
        }
    }
}

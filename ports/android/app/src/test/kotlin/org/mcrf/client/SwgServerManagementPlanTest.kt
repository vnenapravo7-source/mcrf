package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SwgServerManagementPlanTest {
    @Test fun disablingUsesUserBlockNotDeletionOrPeerMutation(){
        val request=ServerManagementRequest.build("swg",ServerManagementRequest.Action.DISABLE_CLIENT,SwgServerManagementPlan.implementedActions,JSONObject().put("client_id","fixture-user"),true)
        val plan=SwgServerManagementPlan.build(request,confirmed=true)
        assertEquals("/api/user/block",plan.path)
        assertEquals("POST",plan.method)
        assertEquals("fixture-user",plan.body()!!.getString("user_id"))
        assertFalse(plan.toString().contains("fixture-user"))
    }
    @Test fun unsafeSwgExpiryCannotBeAdvertisedOrForgedEvenAfterConfirmation(){
        assertFalse(SwgServerManagementPlan.implementedActions.contains("client-expiry"))
        assertThrows(IllegalArgumentException::class.java){ServerManagementRequest.build("swg",ServerManagementRequest.Action.CLIENT_EXPIRY,SwgServerManagementPlan.implementedActions,JSONObject().put("client_id","fixture-user").put("expires_at",123L),true)}
        val forged=ServerManagementRequest.build("swg",ServerManagementRequest.Action.CLIENT_EXPIRY,setOf("client-expiry"),JSONObject().put("client_id","fixture-user").put("expires_at",123L),true)
        assertThrows(IllegalArgumentException::class.java){SwgServerManagementPlan.build(forged,true)}
    }
    @Test fun readOnlyStatusIsExact(){
        val status=SwgServerManagementPlan.build(ServerManagementRequest.build("swg",ServerManagementRequest.Action.STATUS,SwgServerManagementPlan.implementedActions))
        assertEquals("GET",status.method);assertEquals("/api/v1/summary",status.path);assertNull(status.body())
    }
}

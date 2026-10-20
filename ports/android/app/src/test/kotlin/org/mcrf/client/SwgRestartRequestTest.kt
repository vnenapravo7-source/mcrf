package org.mcrf.client
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SwgRestartRequestTest {
    private fun parameters()=JSONObject().put("node","fixture-node").put("iface","wg0")
    @Test fun restartNeedsExplicitTupleAndLiveConfirmation(){
        for(input in listOf(JSONObject(),parameters().put("command","reboot"),parameters().put("iface","wg0;reboot"),parameters().put("iface","a".repeat(16)))){
            assertThrows(IllegalArgumentException::class.java){ServerManagementRequest.build("swg",ServerManagementRequest.Action.RESTART,SwgServerManagementPlan.implementedActions,input,true)}
        }
        assertThrows(IllegalArgumentException::class.java){ServerManagementRequest.build("swg",ServerManagementRequest.Action.RESTART,SwgServerManagementPlan.implementedActions,parameters())}
    }
    @Test fun requestCapturesExactInterfaceAndPlanNeverCallsGenericReboot(){
        val input=parameters()
        val request=ServerManagementRequest.build("swg",ServerManagementRequest.Action.RESTART,SwgServerManagementPlan.implementedActions,input,true)
        input.put("iface","changed")
        val plan=SwgServerManagementPlan.build(request,true)
        assertEquals("POST",plan.method);assertEquals("/api/iface/restart",plan.path)
        assertEquals("wg0",plan.body()!!.getString("iface"))
        assertEquals("fixture-node",plan.body()!!.getString("node"))
        assertFalse(plan.toString().contains("fixture-node"))
    }
    @Test fun listIsReadOnlyAndQueuedMutationRetainsNoTargetSecretsInJournal(){
        val read=ServerManagementRequest.build("swg",ServerManagementRequest.Action.INTERFACES,SwgServerManagementPlan.implementedActions)
        val plan=SwgServerManagementPlan.build(read)
        assertEquals("GET",plan.method);assertNull(plan.body())
        val restart=ServerManagementRequest.build("swg",ServerManagementRequest.Action.RESTART,SwgServerManagementPlan.implementedActions,parameters(),true)
        val journal=ServerManagementJournal.begin(JSONObject(),restart,"pinned-endpoint",100)
        assertFalse(journal.toString().contains("fixture-node"))
        assertEquals("pending",journal.getJSONObject(restart.getString("request_id")).getString("state"))
    }
}

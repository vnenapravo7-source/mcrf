package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidDeployDraftDeletionTest {
    private val first="11111111-1111-1111-1111-111111111111"
    private val second="22222222-2222-2222-2222-222222222222"
    private val operation="33333333-3333-3333-3333-333333333333"
    private fun workspace()=AndroidDeployWorkspace.empty().apply {
        getJSONObject("drafts")
            .put(first,AndroidDeployWorkspace.record("openflux","Первый",JSONObject().put("operationId",operation)))
            .put(second,AndroidDeployWorkspace.record("openflux","Второй",JSONObject().put("host","192.0.2.2")))
        getJSONObject("selected").put("openflux",first)
    }
    @Test fun onlyRequestedDraftAndItsSelectionAreRemoved() {
        val current=workspace();val before=current.toString()
        val next=AndroidDeployWorkspace.withoutDraft(current,first,JSONObject(),AndroidDeployInventory.empty())
        assertEquals(before,current.toString())
        assertFalse(next.getJSONObject("drafts").has(first))
        assertEquals(current.getJSONObject("drafts").getJSONObject(second).toString(),next.getJSONObject("drafts").getJSONObject(second).toString())
        assertFalse(next.getJSONObject("selected").has("openflux"))
        AndroidDeployWorkspace.validate(next)
    }
    @Test fun removingUnselectedDraftKeepsCurrentSelection() {
        val next=AndroidDeployWorkspace.withoutDraft(workspace(),second,JSONObject(),AndroidDeployInventory.empty())
        assertEquals(first,next.getJSONObject("selected").getString("openflux"))
    }
    @Test fun staleConfirmationCannotDeleteBoundInstallation() {
        val current=workspace()
        current.getJSONObject("drafts").getJSONObject(first).put("panelResultKey","fixture-result")
        val before=current.toString()
        assertThrows(IllegalArgumentException::class.java) {
            AndroidDeployWorkspace.withoutDraft(current,first,JSONObject(),AndroidDeployInventory.empty())
        }
        assertEquals(before,current.toString())
    }
    @Test fun persistedResultsProtectDraftBeforeCompletionMarkerIsWritten() {
        for(result in listOf(JSONObject().put("deployment_id",first),JSONObject().put("operation_id",operation))) {
            val current=workspace();val results=JSONObject().put("fixture-result",result)
            val before=current.toString();val resultsBefore=results.toString()
            assertThrows(IllegalArgumentException::class.java) {
                AndroidDeployWorkspace.withoutDraft(current,first,results,AndroidDeployInventory.empty())
            }
            assertEquals(before,current.toString());assertEquals(resultsBefore,results.toString())
        }
    }
    @Test fun inventoryInstancePreventsDraftDeletion() {
        val inventory=AndroidDeployInventory.empty().apply {getJSONObject("instances").put(first,JSONObject())}
        assertThrows(IllegalArgumentException::class.java) {
            AndroidDeployWorkspace.withoutDraft(workspace(),first,JSONObject(),inventory)
        }
    }
    @Test fun repeatedDeletionCannotChooseAnotherDraft() {
        val next=AndroidDeployWorkspace.withoutDraft(workspace(),first,JSONObject(),AndroidDeployInventory.empty())
        val before=next.toString()
        assertThrows(IllegalArgumentException::class.java) {
            AndroidDeployWorkspace.withoutDraft(next,first,JSONObject(),AndroidDeployInventory.empty())
        }
        assertEquals(before,next.toString())
    }
}

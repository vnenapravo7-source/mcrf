package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AndroidDeployWorkspaceTest {
    @Test fun legacyDraftsMigrateOnceWithoutLosingCredentials(){
        val old=JSONObject().put("swg",JSONObject().put("host","192.0.2.1").put("sshPassword","fixture-only"))
        val root=AndroidDeployWorkspace.migrate(JSONObject(),old)
        val id=root.getJSONObject("selected").getString("swg")
        assertEquals("fixture-only",root.getJSONObject("drafts").getJSONObject(id).getJSONObject("values").getString("sshPassword"))
        assertEquals(root.toString(),AndroidDeployWorkspace.migrate(root,JSONObject()).toString())
        assertEquals(1,old.length())
    }
    @Test fun sameRecipeHasIndependentNamesHostsAndCredentials(){
        val root=AndroidDeployWorkspace.empty();val a=UUID.randomUUID().toString();val b=UUID.randomUUID().toString()
        root.getJSONObject("drafts").put(a,AndroidDeployWorkspace.record("swg","Первый",JSONObject().put("host","192.0.2.1").put("sshPassword","fixture-A")))
            .put(b,AndroidDeployWorkspace.record("swg","Второй",JSONObject().put("host","198.51.100.1").put("sshPassword","fixture-B")))
        root.getJSONObject("selected").put("swg",b)
        AndroidDeployWorkspace.validate(root)
        assertEquals(2,root.getJSONObject("drafts").length())
        assertEquals("fixture-A",root.getJSONObject("drafts").getJSONObject(a).getJSONObject("values").getString("sshPassword"))
        AndroidDeployBackupSettings.validate(JSONObject().put(AndroidDeployWorkspace.SECTION,JSONObject(root.toString())))
    }
    @Test fun selectionCannotPointAtDifferentRecipe(){
        val root=AndroidDeployWorkspace.empty();val id=UUID.randomUUID().toString()
        root.getJSONObject("drafts").put(id,AndroidDeployWorkspace.record("swg","Первый",JSONObject()))
        root.getJSONObject("selected").put("3xui",id)
        assertTrue(runCatching {AndroidDeployWorkspace.validate(root)}.isFailure)
    }
    @Test fun recordCopiesValuesAndUsesStrictExistingDraftSchema(){
        val source=JSONObject().put("host","192.0.2.1")
        val item=AndroidDeployWorkspace.record("swg","Имя",source)
        source.put("host","198.51.100.1")
        assertEquals("192.0.2.1",item.getJSONObject("values").getString("host"))
        item.getJSONObject("values").put("unexpected",true)
        val root=AndroidDeployWorkspace.empty().apply {getJSONObject("drafts").put(UUID.randomUUID().toString(),item)}
        assertTrue(runCatching {AndroidDeployWorkspace.validate(root)}.isFailure)
    }
}

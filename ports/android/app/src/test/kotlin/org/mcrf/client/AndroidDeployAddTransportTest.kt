package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidDeployAddTransportTest {
    @Test fun encryptedHistoryTemplateKeepsPortsButNotSshSecretsOrOldAutomaticGroups(){
        val options=JSONObject().put("tcp_port",12345).put("automatic_calls",true).put("vk_call_group","deploy:previous").put("vk_calls",org.json.JSONArray().put("previous-call"))
        val record=AndroidDeployHistory.record(saved(),target.copy(options=options),"3xui")
        val template=record.getJSONObject("transport_template")
        assertFalse(template.has("sshPassword"));assertFalse(template.has("privateKey"))
        assertEquals(12345,template.getJSONObject("options").getInt("tcp_port"))
        assertFalse(template.getJSONObject("options").has("vk_call_group"))
        assertEquals(0,template.getJSONObject("options").getJSONArray("vk_calls").length())
        AndroidDeployBackupSettings.validate(JSONObject().put("deployResults",JSONObject().put("fixture",record)))
    }
    private val target=AndroidDeployTarget("192.0.2.1",sshPassword="fixture-only")
    private fun saved()=JSONObject().put("panel_url","https://192.0.2.1:8443/panel/")
        .put("username","fixture").put("password","fixture-password").put("ready",true)
        .put("certificate_sha256","a".repeat(64)).put("connections",org.json.JSONArray())
    @Test fun existingPanelScriptNeverInstallsRestartsOrRegeneratesCertificates(){
        val script=AndroidDeployScripts.addTransport(AndroidDeployRecipe.all.first {it.id=="3xui"},target,saved())
        for(forbidden in listOf("installer.sh","systemctl restart","openssl req","source /etc/","git clone"))assertFalse(forbidden,script.contains(forbidden))
        assertTrue(script.contains("shlex.split"));assertTrue(script.contains("MCRF_EXPECTED_CERT"))
        assertTrue(script.indexOf("fingerprint != expected")<script.indexOf("r=configure_initial"))
    }
    @Test fun invalidSavedIdentityRejectedBeforeScriptGeneration(){
        val recipe=AndroidDeployRecipe.all.first {it.id=="3xui"}
        assertThrows(IllegalArgumentException::class.java){AndroidDeployScripts.addTransport(recipe,target,saved().put("panel_url","https://other.invalid:8443/"))}
        assertThrows(IllegalArgumentException::class.java){AndroidDeployScripts.addTransport(recipe,target,saved().put("certificate_sha256",""))}
    }
    @Test fun deferredOpenfluxCannotEnterProvisionOnlyPath(){
        assertThrows(IllegalArgumentException::class.java){AndroidDeployScripts.addTransport(AndroidDeployRecipe.all.first {it.id=="openflux"},target,saved())}
    }
}

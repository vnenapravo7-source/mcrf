package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Unexecuted source fixtures; these model receipt admission, not provider connectivity. */
class EngineUpdateOpenFluxScriptPolicyTest {
    private val old="1".repeat(64)
    private val next="2".repeat(64)
    private fun row(id:String="yandex")=JSONObject().put("status","verified_current").put("id",id)
        .put("version","1.1.0").put("sha256",old).put("currentVersion","1.1.0").put("currentSha256",old)
        .put("coreCommit",OpenFluxProfile.FRESH_COMMIT).put("abi","arm64-v8a").put("wire",1).put("api",1)
        .put("runtimePreflight",true).put("runtimeAccepted",false)
    private fun inventory()=JSONObject().put("status","verified_inventory").put("interface",1)
        .put("scriptUpdater",2).put("cli","official-pinned-v1").put("coreCommit",OpenFluxProfile.FRESH_COMMIT)
        .put("abi","arm64-v8a").put("runtimeAccepted",false)
        .put("packages",JSONArray(EngineUpdateOpenFluxScriptPolicy.ids.map {row(it)}))
    @Test fun onlyPinnedProductionProtocolAndPublishedIdsCanBeRequested() {
        val request=EngineUpdateOpenFluxScriptPolicy.request("apply","yandex",next)
        assertEquals(setOf("action","id","expectedSha256"),request.keys().asSequence().toSet())
        assertThrows(IllegalArgumentException::class.java){EngineUpdateOpenFluxScriptPolicy.request("apply","yandex","")}
        for(id in listOf("vkteams","mtslink-webrtc","../yandex"))assertThrows(IllegalArgumentException::class.java){EngineUpdateOpenFluxScriptPolicy.request("check",id)}
        assertThrows(IllegalArgumentException::class.java){EngineUpdateOpenFluxScriptPolicy.request("recover","yandex",next)}
    }
    @Test fun inventoryCannotDowngradeProtocolOrClaimRuntimeAcceptance() {
        assertEquals(10,EngineUpdateOpenFluxScriptPolicy.inventory(inventory(),"arm64-v8a").size)
        for((key,value) in listOf("scriptUpdater" to 1,"coreCommit" to "0".repeat(40),"abi" to "x86_64","runtimeAccepted" to true)) {
            assertThrows(IllegalArgumentException::class.java){EngineUpdateOpenFluxScriptPolicy.inventory(inventory().put(key,value),"arm64-v8a")}
        }
        for((key,value) in listOf("api" to 2,"wire" to 2,"runtimePreflight" to false)) {
            assertThrows(IllegalArgumentException::class.java){EngineUpdateOpenFluxScriptPolicy.receipt(row().put(key,value),"arm64-v8a")}
        }
    }
    @Test fun exactDigestAndBeforeStateAreRequiredForCommitOrLostResponseRecovery() {
        val result=row().put("status","installed_for_next_session").put("sha256",next).put("version","1.1.1")
        assertEquals(next,EngineUpdateOpenFluxScriptPolicy.verifiedResult("apply","yandex",next,old,result,"arm64-v8a").sha)
        assertThrows(IllegalArgumentException::class.java){EngineUpdateOpenFluxScriptPolicy.verifiedResult("apply","yandex","3".repeat(64),old,result,"arm64-v8a")}
        assertThrows(IllegalArgumentException::class.java){EngineUpdateOpenFluxScriptPolicy.verifiedResult("apply","yandex",next,"3".repeat(64),result,"arm64-v8a")}
        val current=EngineUpdateOpenFluxScriptPolicy.receipt(row().put("sha256",next).put("currentSha256",next),"arm64-v8a")
        assertTrue(EngineUpdateOpenFluxScriptPolicy.reconcile(current,"yandex",old,next))
        assertFalse(EngineUpdateOpenFluxScriptPolicy.reconcile(current.copy(sha=old,currentSha=old),"yandex",old,next))
        assertThrows(IllegalArgumentException::class.java){EngineUpdateOpenFluxScriptPolicy.reconcile(current.copy(sha="3".repeat(64),currentSha="3".repeat(64)),"yandex",old,next)}
    }
}

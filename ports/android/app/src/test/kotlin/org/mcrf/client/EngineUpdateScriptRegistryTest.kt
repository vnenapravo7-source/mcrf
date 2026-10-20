package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineUpdateScriptRegistryTest {
    private val first=EngineUpdateScriptRecord("demo","demo.flux","1.0.0",1,1,"a".repeat(64),false)
    private fun rejects(block:()->Unit){try{block();fail("must reject")}catch(_:IllegalArgumentException){}}
    @Test fun thirdPartyNeedsExplicitTrustAndRoundTripRetainsPinnedIdentity(){
        val registry=EngineUpdateScriptCatalogue();rejects{registry.addVerified(first,false)}
        assertEquals(0,registry.entries().size);registry.addVerified(first,true)
        assertEquals(first,EngineUpdateScriptCatalogue(registry.serialize()).get("demo"))
        rejects{registry.addVerified(first,true)}
    }
    @Test fun unknownFieldsDuplicateFileAndTraversalFailClosed(){
        rejects{EngineUpdateScriptRecord.fromJson(first.json().put("token","fixture-only"))}
        rejects{first.copy(file="../outside.flux")}
        val registry=EngineUpdateScriptCatalogue();registry.addVerified(first,true)
        rejects{registry.addVerified(first.copy(id="other"),true)}
        rejects{EngineUpdateScriptCatalogue(JSONObject(registry.serialize()).put("unvalidated",true).toString())}
    }
    @Test fun authorChangesAndUnsignedInspectorClaimsNeverReplaceExisting(){
        val registry=EngineUpdateScriptCatalogue();registry.addVerified(first,true)
        rejects{registry.replaceVerified(first,first.copy(key="b".repeat(64),version="1.1.0"),false)}
        rejects{registry.replaceVerified(first,first.copy(id="other"),false)}
        rejects{EngineUpdateScriptRecord.inspected(first.json().put("status","available"))}
        assertEquals(first,registry.get("demo"))
    }
    @Test fun wireBreakRequiresConsentWhileVerifiedOfficialRotationCanPersist(){
        val registry=EngineUpdateScriptCatalogue();val official=first.copy(official=true);registry.addVerified(official,false)
        val rotated=official.copy(key="b".repeat(64),version="1.1.0")
        registry.replaceVerified(official,rotated,false);assertEquals(rotated,registry.get("demo"))
        rejects{registry.replaceVerified(rotated,rotated.copy(wire=2),false)}
        registry.replaceVerified(rotated,rotated.copy(wire=2),true)
        assertEquals(2,EngineUpdateScriptCatalogue(registry.serialize()).get("demo").wire)
    }
}

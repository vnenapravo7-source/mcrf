package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CsqttImportResolutionTest {
    private val call=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){1})
    private fun link(port:Int=46000)="csqtt://connect?v=2&host=fixture.invalid&peer=$port&password=fixture-only&hashes=$call"
    private fun prepare(source:String)=ImportPlan.prepare(source){error("Unexpected subscription request")}
    @Test fun plainV2DoesNotRevealProtocolAndCannotImportWithoutChoice(){
        val plan=prepare(link());assertTrue(CsqttImportResolution.ambiguous(plan.entries.single()))
        assertThrows(CsqttImportResolution.Required::class.java){plan.resolveCsqtt(emptyMap())}
        assertEquals(setOf("csqtt20"),plan.resolveCsqtt(mapOf(plan.entries.single().id to "2.0")).engines)
        assertEquals(setOf("csqtt215"),plan.resolveCsqtt(mapOf(plan.entries.single().id to "2.1.5")).engines)
    }
    @Test fun explicitGenerationIsDetectedWithoutManualChoiceOrCrossFamilyCoercion(){
        for(generation in listOf("2.0","2.1.5","2.1.9")){
            val plan=prepare(link()+"&csqtt_generation=$generation")
            assertFalse(CsqttImportResolution.ambiguous(plan.entries.single()))
            assertEquals(CsqttGenerations.family(plan.entries.single().carrier!!).engine,plan.resolveCsqtt(emptyMap()).engines.single())
        }
        val plan=prepare(link());val id=plan.entries.single().id
        assertThrows(IllegalArgumentException::class.java){plan.resolveCsqtt(mapOf(id to "rebirth-2.5.0"))}
    }
    @Test fun mixedUnknownServersRequireSeparateChoicesAndRetainOriginalIds(){
        val source=link()+"\n"+link(46001)+"\nsocks5://fixture.invalid:1080"
        val base=prepare(source);val first=base.entries[0];val second=base.entries[1]
        assertThrows(CsqttImportResolution.Required::class.java){base.resolveCsqtt(mapOf(first.id to "2.0"))}
        val resolved=base.resolveCsqtt(mapOf(first.id to "2.0",second.id to "2.1.5"))
        assertEquals(base.entries.map {it.id},resolved.entries.map {it.id})
        assertEquals(base.manual,resolved.manual)
        assertEquals(setOf("csqtt20","csqtt215"),resolved.engines)
        assertEquals("socks5://fixture.invalid:1080",resolved.entries[2].original)
    }
    @Test fun subscriptionChoiceKeepsUrlMetadataAndDoesNotFetchAgain(){
        val url="https://fixture.invalid/subscription";var fetches=0
        val metadata=JSONObject().put("title","Fixture subscription")
        val base=ImportPlan.prepare(url){fetches++;SubscriptionClient.Downloaded(ServerImport.read(link()),metadata)}
        val entry=base.entries.single()
        assertThrows(CsqttImportResolution.Required::class.java){base.resolveCsqtt(emptyMap())}
        val chosen=base.resolveCsqtt(mapOf(entry.id to "2.1.5"))
        assertEquals(1,fetches);assertEquals(setOf(url),chosen.subscriptions.keys)
        assertEquals(metadata.toString(),chosen.subscriptions.getValue(url).metadata.toString())
        assertEquals(entry.id,chosen.entries.single().id);assertEquals("csqtt215",chosen.engines.single())
    }
    @Test fun exactExistingBindingKeepsCustomSettingsWithoutPromptOrVersionRewrite(){
        val base=prepare(link());val original=base.entries.single()
        val chosen=CsqttImportResolution.resolve(original,"2.0")
        val options=ConnectionEdits.snapshot(chosen).put("name","Custom name")
        val existing=ConnectionEdits.apply(chosen,options)
        val resolved=base.resolveCsqtt(emptyMap(),mapOf(existing.id to existing))
        assertEquals("Custom name",resolved.entries.single().name);assertEquals("csqtt20",resolved.engines.single())
        assertEquals(existing.id,resolved.entries.single().id)
    }
    @Test fun narrowVersionChoiceDoesNotFreezeSubscriptionFieldsAndDetectsExplicitProtocolChange(){
        val original=prepare(link()).entries.single()
        val choice=JSONObject().put("csqttVersion","2.1.5")
        val changed=JSONObject(original.carrier!!.toString()).put("password","changed-fixture-only").put("server_port",46002)
        val fresh=SwgProfile.fromJson(original.original,changed)
        val resolved=ConnectionEdits.apply(fresh,choice)
        assertEquals("changed-fixture-only",resolved.carrier!!.getString("password"));assertEquals(46002,resolved.port)
        assertEquals("csqtt215",EngineModules.carrierKind(resolved.carrier!!))
        val newer=SwgProfile.fromJson(original.original,JSONObject().put("type","csqtt").put("csqtt_generation","2.5").put("server","fixture.invalid").put("server_port",46002).put("password","fixture-only").put("hashes",JSONArray(listOf(call))).put("workers",10))
        assertThrows(IllegalArgumentException::class.java){ConnectionEdits.apply(newer,choice)}
    }
    @Test fun catalogueStoresVersionAsLocalChoiceWithoutChangingSubscriptionContents(){
        val raw=ServerImport.read(link()).single();val catalogue=ConnectionCatalogue("")
        catalogue.replaceSubscription("https://fixture.invalid/subscription",listOf(raw),JSONObject().put("title","Fixture"),true)
        catalogue.edit(raw.id,JSONObject().put("csqttVersion","2.0"))
        val restored=ConnectionCatalogue(catalogue.serialize())
        assertEquals("csqtt20",EngineModules.carrierKind(restored.entries().single().carrier!!))
        assertEquals(raw.original,restored.subscriptions().single().getJSONArray("servers").getString(0))
        restored.replaceSubscription("https://fixture.invalid/subscription",listOf(raw),null,true)
        assertEquals("csqtt20",EngineModules.carrierKind(restored.entries().single().carrier!!))
    }
    @Test fun engineLabelsShowVersionAndOriginInsteadOfInternalModuleIds(){
        assertEquals("CSQTT v2.0.0 · Original",CsqttGenerations.engineLabel("csqtt20"))
        assertEquals("CSQTT v2.1.5 · Original",CsqttGenerations.engineLabel("csqtt215"))
        for(label in CsqttGenerations.importChoices.values.drop(1))assertTrue(label.startsWith("CSQTT v2.")&&listOf("Original","SWG","Rebirth").any {it in label})
    }
}

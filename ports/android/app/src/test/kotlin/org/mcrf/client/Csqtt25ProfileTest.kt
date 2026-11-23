package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class Csqtt25ProfileTest {
    @Test fun omittedSrtpRetainsOldProfilesAndNativeAutoWithoutInventingAStoredField() {
        for(implementation in listOf("compatible-2.5","swg-panel-2.5.1","swg-panel-2.5.2","swg-panel-2.5.3")) {
            val raw=fixture().put("csqtt_implementation",implementation)
            val before=raw.toString()
            val normal=Csqtt25Profile.fromJson("fixture",raw).carrier!!
            assertFalse(normal.has("srtp_mode"))
            assertFalse(Csqtt25Profile.privateConfig(normal,22001,22002,"user","pass","binding").has("srtp_mode"))
            assertEquals(before,raw.toString())
        }
    }
    @Test fun explicitSrtpModesReach252253PrivateConfigAndLeaveOtherTransportOptionsUntouched() {
        for(implementation in listOf("swg-panel-2.5.2","swg-panel-2.5.3"))for(srtp in listOf("auto","wrap-srtp","dtls-srtp")) {
            val raw=fixture().put("csqtt_implementation",implementation).put("srtp_mode",srtp)
                .put("turn_transport","tcp").put("obfs","unified").put("link_workers",7)
            val before=raw.toString()
            val normal=Csqtt25Profile.fromJson("fixture",raw).carrier!!
            val config=Csqtt25Profile.privateConfig(normal,22001,22002,"user","pass","binding")
            assertEquals(srtp,normal.getString("srtp_mode"))
            if(srtp=="auto")assertFalse(config.has("srtp_mode"))else assertEquals(srtp,config.getString("srtp_mode"))
            assertEquals("tcp",config.getString("turn_transport"));assertEquals("unified",config.getString("obfs"))
            assertEquals(7,config.getInt("workers"));assertTrue(config.getBoolean("exact_workers"))
            assertEquals(before,raw.toString())
        }
    }
    @Test fun explicitInvalidSrtpCannotCoerceOrFallBackToAuto() {
        for(value in listOf<Any>(JSONObject.NULL,"","AUTO"," auto","auto ","wrap","dtls","future","wrap-srtp\n",true,1,JSONArray(),JSONObject())) {
            val raw=fixture().put("csqtt_implementation","swg-panel-2.5.3").put("srtp_mode",value)
            val before=raw.toString()
            val error=assertThrows(IllegalArgumentException::class.java) { Csqtt25Profile.fromJson("fixture",raw) }
            assertFalse(error.message!!.contains("fixture-only"));assertEquals(before,raw.toString())
            assertThrows(IllegalArgumentException::class.java) { Csqtt25Profile.privateConfig(raw,22001,22002,"user","pass","binding") }
        }
        val automatic=fixture().put(CsqttGenerations.SELECTION,CsqttGenerations.AUTO).put("srtp_mode","dtls-srtp")
        assertThrows(IllegalArgumentException::class.java) { Csqtt25Profile.fromJson("fixture",automatic) }
    }
    @Test fun srtpSurvivesCatalogueReloadEditAndJsonShareWithoutChangingTheOriginalInput() {
        val raw=fixture().put("csqtt_implementation","swg-panel-2.5.3").put("srtp_mode","dtls-srtp")
        val original=raw.toString()
        val catalogue=ConnectionCatalogue("");catalogue.add(JSONArray().put(original).toString())
        val entry=catalogue.entries().single()
        catalogue.edit(entry.id,ConnectionEdits.captcha(entry,false))
        val reloaded=ConnectionCatalogue(catalogue.serialize()).entries().single()
        assertEquals(original,reloaded.original);assertEquals("dtls-srtp",reloaded.carrier!!.getString("srtp_mode"))
        val shared=ServerImport.read(ConnectionEdits.share(reloaded,true)).single().carrier!!
        assertEquals("dtls-srtp",shared.getString("srtp_mode"));assertEquals("wv",shared.getString("captcha_mode"))
        assertEquals(original,raw.toString())
    }
    @Test fun expandedHashPoolIsDedupedBeforeWorkerCapacityAndReachesPrivateConfig() {
        val hashes=(1..12).map {"fixture-call-0123456789-$it"}
        val raw=fixture().put("hashes",JSONArray(hashes+hashes)).put("workers",100)
        val normal=Csqtt25Profile.fromJson("fixture",raw).carrier!!
        assertEquals(12,normal.getJSONArray("hashes").length())
        assertEquals(12,Csqtt25Profile.privateConfig(normal,22001,22002,"user","pass","binding").getJSONArray("hashes").length())
        val duplicates=fixture().put("hashes",JSONArray(List(12){hashes.first()})).put("workers",20)
        assertThrows(IllegalArgumentException::class.java){Csqtt25Profile.fromJson("fixture",duplicates)}
        duplicates.remove("workers")
        assertEquals(10,Csqtt25Profile.fromJson("fixture",duplicates).carrier!!.getInt("workers"))
    }
    private fun fixture()=JSONObject().put("type","csqtt").put("csqtt_generation","2.5").put("server","fixture.invalid").put("server_port",46000)
        .put("password","fixture-only").put("hashes",JSONArray().put("fixture-call-0123456789abcdef"))
    @Test fun swg252253ImportAndPrivateConfigPreserveFullManualRangeAndOptions() {
        for(implementation in CsqttSwgVersions.revisions.keys)for(count in listOf(80,100)) {
            val raw=fixture().put("csqtt_implementation",implementation).put("workers",count)
                .put("hashes",JSONArray((1..6).map {"fixture-call-0123456789-$it"}))
                .put("allow_hash_redistribution",false).put("captcha_mode","wv")
                .put("fingerprint","firefox").put("turn_transport","tcp").put("obfs","video")
            val before=raw.toString()
            val imported=Csqtt25Profile.fromJson("fixture",raw).carrier!!
            assertEquals(count,imported.getInt("workers"));assertFalse(imported.getBoolean("allow_hash_redistribution"))
            val direct=Csqtt25Profile.privateConfig(imported,22001,22002,"user","pass","binding")
            assertEquals(count,direct.getInt("workers"));assertFalse(direct.getBoolean("exact_workers"))
            val applied=CarrierStreamDefaults.apply(imported,JSONObject().put("csqtt25",count))
            val privateConfig=Csqtt25Profile.privateConfig(applied,22001,22002,"user","pass","binding")
            assertEquals(count,privateConfig.getInt("workers"));assertTrue(privateConfig.getBoolean("exact_workers"))
            assertEquals(implementation,privateConfig.getString("csqtt_implementation"))
            assertFalse(privateConfig.getBoolean("allow_hash_redistribution"))
            for(key in listOf("captcha_mode","fingerprint","turn_transport","obfs","hashes"))
                assertEquals(raw.get(key).toString(),privateConfig.get(key).toString())
            assertEquals(before,raw.toString())
        }
    }
    @Test fun earlierImplementationImportStillRejectsExcessManualWorkersButKeepsExactCounts() {
        for(implementation in listOf("swg-panel-2.5.1","compatible-2.5")) {
            val raw=fixture().put("csqtt_implementation",implementation).put("workers",80)
                .put("hashes",JSONArray((1..6).map {"fixture-call-0123456789-$it"}))
                .put("allow_hash_redistribution",false)
            val before=raw.toString()
            assertThrows(IllegalArgumentException::class.java) { Csqtt25Profile.fromJson("fixture",raw) }
            assertEquals(before,raw.toString())
            val exact=Csqtt25Profile.fromJson("fixture",JSONObject(raw.toString()).put("link_workers",80)).carrier!!
            assertEquals(80,exact.getInt("workers"))
            assertTrue(Csqtt25Profile.privateConfig(exact,22001,22002,"user","pass","binding").getBoolean("exact_workers"))
        }
    }
    @Test fun independentlyEncryptedNewLinkSelects25AndPreservesServerExactSevenWorkers() {
        val encoded="AAECAwQFBgcICQoLiDAk-G_tWw5zdEU6hMH5KxLvy85_74Ka499aqbf3i8LAJU-u9HWCdRYBYQXbLXevMgsHDRME4bvHlEI5KnurmlZqrWbqPXwXzWRje9Ib6WHN9WUHSsTEYE0x5MOaWYEX9uiCyaqoaRDfZv0hNnT6uxbcYa3WOhN2"
        val entry=Csqtt25Profile.encryptedLink("csqtt://$encoded")
        assertEquals("csqtt",entry.protocol);assertEquals("2.5",entry.carrier!!.getString("csqtt_generation"))
        assertEquals(7,entry.carrier!!.getInt("link_workers"));assertEquals(7,entry.carrier!!.getInt("workers"))
        assertEquals("unified",entry.carrier!!.getString("obfs"))
        assertEquals(7,SwgProfile.fromJson("new",JSONObject(entry.carrier!!.toString())).carrier!!.getInt("workers"))
        assertEquals("csqtt25",EngineModules.carrierKind(entry.carrier!!))
    }
    @Test fun legacyOrUnknownGenerationCannotBeReadBy25AndDoesNotChangeLegacyDefaults() {
        for(version in listOf("legacy","2.1.9","future",""))assertThrows(IllegalArgumentException::class.java){Csqtt25Profile.fromJson("fixture",fixture().put("csqtt_generation",version))}
        val legacy=fixture().apply {remove("csqtt_generation")}
        assertEquals("audio",SwgProfile.fromJson("fixture",legacy).carrier!!.getString("obfs"))
        assertFalse(Csqtt25Profile.isProfile(SwgProfile.fromJson("fixture",legacy).carrier!!))
        assertEquals("csqtt25",EngineModules.carrierKind(SwgProfile.fromJson("fixture",fixture().put("workers",10).put("obfs","audio")).carrier!!))
    }
    @Test fun typed25PrivateConfigUsesSeparateExactWorkerAndAuthenticatedBridgeContract() {
        val raw=fixture().put("link_workers",7).put("turn_transport","tcp").put("custom_turn_ips",JSONArray().put("192.0.2.1"))
            .put("device_id","existing-fixture-binding").put("generation",17).put("salt","fixture-salt")
        val before=raw.toString();val c=Csqtt25Profile.privateConfig(raw,22001,22002,"fixture-user","fixture-password","unused-fallback")
        assertEquals(before,raw.toString());assertEquals("2.5",c.getString("csqtt_generation"));assertEquals(7,c.getInt("workers"));assertTrue(c.getBoolean("exact_workers"))
        assertEquals("fixture.invalid:46000",c.getString("peer"));assertEquals("127.0.0.1:22002",c.getString("socks"))
        assertEquals("existing-fixture-binding",c.getString("device_id"));assertEquals("tcp",c.getString("turn_transport"))
        assertEquals(17,c.getInt("generation"));assertEquals("fixture-salt",c.getString("salt"))
        for(key in listOf("type","name","server","server_port","link_workers"))assertFalse(c.has(key))
    }
    @Test fun ownAutoJsTokenModeDoesNotUseOrInventOldAnonymousCalls() {
        val raw=fixture().put("hashes",JSONArray()).put("vk_token","fixture-private-token").put("vk_hash_mode","auto_js")
        val p=Csqtt25Profile.fromJson("fixture",raw).carrier!!
        assertEquals("auto_js",p.getString("vk_auth_mode"));assertEquals(0,p.getJSONArray("hashes").length())
        assertEquals("fixture-private-token",p.getString("vk_token"))
        assertThrows(IllegalArgumentException::class.java){Csqtt25Profile.fromJson("fixture",raw.put("hashes",fixture().getJSONArray("hashes")))}
    }
    @Test fun malformedParametersAndUnsupportedOptionsCannotBeSilentlyDropped() {
        for(bad in listOf(fixture().put("link_workers","bad"),fixture().put("link_workers",101),fixture().put("workers",7),
            fixture().put("workers",36).put("csqtt_implementation",Csqtt25Profile.IMPLEMENTATION),fixture().put("future_required_option",true),fixture().put("custom_turn_ips",JSONArray().put("127.0.0.1")))) {
            val error=assertThrows(IllegalArgumentException::class.java){Csqtt25Profile.fromJson("fixture",bad)}
            assertFalse(error.message!!.contains("fixture-only"))
        }
    }
    @Test fun panelWorkerCadenceIsTenAndExactServerCountMayReachOneHundred() {
        assertEquals(10,Csqtt25Profile.fromJson("fixture",fixture()).carrier!!.getInt("workers"))
        assertEquals(100,Csqtt25Profile.fromJson("fixture",fixture().put("link_workers",100)).carrier!!.getInt("workers"))
        assertEquals(100,Csqtt25Profile.fromJson("fixture",fixture().put("workers",100).put("allow_hash_redistribution",true)).carrier!!.getInt("workers"))
        val migrated=Csqtt25Profile.fromJson("fixture",fixture().put("workers",18)).carrier!!
        assertEquals(18,migrated.getInt("link_workers"))
        assertTrue(Csqtt25Profile.privateConfig(migrated,22001,22002,"user","pass","device").getBoolean("exact_workers"))
        assertThrows(IllegalArgumentException::class.java){Csqtt25Profile.fromJson("fixture",fixture().put("workers",18).put("csqtt_implementation",Csqtt25Profile.IMPLEMENTATION))}
    }
    @Test fun mixedCatalogueAndEditedShareRetainBothGenerationsAndFullNewSettings() {
        val old=fixture().apply {remove("csqtt_generation")}.toString()
        val fresh=fixture().put("link_workers",7).put("password"," exact fixture ").put("salt"," exact salt ").put("turn_transport","tcp").put("device_id","existing-binding").toString()
        val catalogue=ConnectionCatalogue("");catalogue.add(JSONArray().put(old).put(fresh).toString())
        val before=catalogue.entries();assertEquals(2,before.size)
        assertEquals(setOf("csqtt","csqtt25"),before.map {EngineModules.carrierKind(it.carrier!!)}.toSet())
        assertEquals(setOf("csqtt","csqtt25"),ImportPlan.prepare(JSONArray().put(old).put(fresh).toString()){error("no network expected")}.engines)
        val newer=before.single {Csqtt25Profile.isProfile(it.carrier!!)}
        catalogue.edit(newer.id,ConnectionEdits.captcha(newer,false))
        val after=ConnectionCatalogue(catalogue.serialize()).entries()
        val changed=after.single {it.id==newer.id}
        assertEquals(newer.original,changed.original);assertEquals("wv",changed.carrier!!.getString("captcha_mode"))
        val shared=ServerImport.read(ConnectionEdits.share(changed,true)).single()
        assertEquals("csqtt25",EngineModules.carrierKind(shared.carrier!!));assertEquals(7,shared.carrier!!.getInt("workers"))
        assertEquals(" exact fixture ",shared.carrier!!.getString("password"));assertEquals(" exact salt ",shared.carrier!!.getString("salt"))
        assertEquals("tcp",shared.carrier!!.getString("turn_transport"));assertEquals("existing-binding",shared.carrier!!.getString("device_id"))
        val unchanged=after.single {!Csqtt25Profile.isProfile(it.carrier!!)}
        assertEquals(old,unchanged.original);assertEquals("audio",unchanged.carrier!!.getString("obfs"))
    }
    @Test fun generationSpecificArtifactsCannotBeSubstitutedOrPretendNewAbiAvailability() {
        assertNotEquals(EngineModules.packageName("csqtt"),EngineModules.packageName("csqtt25"))
        val entries=listOf("lib/arm64-v8a/libcsqtt25.so","lib/arm64-v8a/libcsqtt25_transport.so")
        assertTrue(EngineModules.validNativeEntries("csqtt25","arm64-v8a",entries))
        assertFalse(EngineModules.validNativeEntries("csqtt","arm64-v8a",entries))
        // Shape admission checks the requested ABI. Binary identity is checked
        // independently; upstream now supplies all three supported client ABIs.
        assertTrue(EngineModules.validNativeEntries("csqtt25","x86_64",entries.map {it.replace("arm64-v8a","x86_64")}))
        assertThrows(IllegalArgumentException::class.java){EnginePackage.find(JSONArray(),"x86_64","csqtt25")}
        assertThrows(IllegalArgumentException::class.java){EngineModules.carrierKind(fixture().put("csqtt_generation","future"))}
    }
}

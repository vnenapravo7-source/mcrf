package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.Executor

class CsqttAutoGenerationTest {
    private val link="csqtt://connect?v=2&host=fixture.invalid&peer=46000&password=fixture-only&hashes=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private fun entry()=ServerImport.read(link).single()
    private fun auto()=CsqttGenerations.select(entry().carrier!!,CsqttGenerations.AUTO)
    private fun prepare()=ImportPlan.prepare(link){error("No network")}

    @Test fun ambiguousChoiceOffersFourGenerationsAndAutoWithOneOrAllPackages() {
        assertEquals(listOf("2.5","2.1.9","2.1.5","2.0","Определить автоматически"),CsqttGenerations.generationChoices.values.toList())
        val base=prepare();val id=base.entries.single().id
        for((choice,engine)in listOf("2.5" to "csqtt25","legacy" to "csqtt","2.1.5" to "csqtt215","2.0" to "csqtt20")) {
            val plan=base.resolveCsqtt(mapOf(id to choice))
            assertEquals(setOf(engine),plan.engines);assertEquals(id,plan.entries.single().id)
        }
        val plan=base.resolveCsqtt(mapOf(id to CsqttGenerations.AUTO))
        assertEquals(link,plan.manual.trim());assertTrue(CsqttGenerations.automatic(plan.entries.single().carrier!!))
        assertEquals(linkedSetOf("csqtt25","csqtt","csqtt215","csqtt20"),plan.engines)
        assertEquals(4,plan.engines.map(EngineModules::packageName).distinct().size)
    }
    @Test fun exactProbeOrderUsesOneModernPackage() {
        val plan=CsqttAutoDetection.plan(auto(),null)
        assertEquals(listOf("2.5/swg-panel-2.5.3","2.5/swg-panel-2.5.2","2.5/swg-panel-2.5.1","legacy/","2.1.5/","2.0/"),plan.map {it.token})
        assertEquals(listOf("csqtt25","csqtt25","csqtt25","csqtt","csqtt215","csqtt20"),plan.map {it.engine})
        plan.forEach {target->
            val profile=CsqttAutoDetection.profile(auto(),target)
            assertFalse(CsqttGenerations.automatic(profile));assertEquals(target.engine,EngineModules.carrierKind(profile))
            assertEquals("fixture-only",profile.getString("password"));assertEquals("fixture.invalid",profile.getString("server"))
        }
    }
    @Test fun manual25NegotiatesOnlyModernImplementations() {
        val profile=CsqttGenerations.select(entry().carrier!!,"2.5")
        assertEquals(CsqttAutoDetection.order.take(3),CsqttAutoDetection.plan(profile,CsqttAutoDetection.order.last()))
        val attempts=mutableListOf<String>()
        val error=assertThrows(CsqttNativeFailure.Rejected::class.java) {
            CsqttAutoDetection.connect(profile,null,{false},{target->attempts.add(target.token);throw CsqttNativeFailure.Rejected(CsqttNativeFailure.Kind.PROTOCOL)},{fail("No success")})
        }
        assertEquals(CsqttNativeFailure.Kind.PROTOCOL,error.kind);assertEquals(3,attempts.size)
    }
    @Test fun onlyExplicitProtocolRejectionAdvancesAndSuccessIsRemembered() {
        val attempts=mutableListOf<CsqttAutoDetection.Target>();var remembered:CsqttAutoDetection.Target?=null
        val value=CsqttAutoDetection.connect(auto(),null,{false},{target->
            attempts.add(target)
            if(target.generation!="2.1.5")throw CsqttNativeFailure.Rejected(CsqttNativeFailure.Kind.PROTOCOL)
            "connected"
        },{remembered=it})
        assertEquals("connected",value);assertEquals(CsqttAutoDetection.order.take(5),attempts)
        assertEquals(attempts.last(),remembered)
        attempts.clear()
        CsqttAutoDetection.connect(auto(),remembered,{false},{attempts.add(it);"cached"},{remembered=it})
        assertEquals(listOf(remembered),attempts)
    }
    @Test fun timeoutAuthAndGeneralFailureNeverProbeAnotherGenerationOrPoisonCache() {
        for(error in listOf(SocketTimeoutException("timeout"),CsqttNativeFailure.Rejected(CsqttNativeFailure.Kind.ACCESS),IllegalArgumentException("general"))) {
            var attempts=0;var writes=0
            val thrown=assertThrows(Exception::class.java) {CsqttAutoDetection.connect(auto(),null,{false},{attempts++;throw error},{writes++})}
            assertSame(error,thrown);assertEquals(1,attempts);assertEquals(0,writes)
        }
    }
    @Test fun cancellationDuringCachePublicationRetiresReadyOwner() {
        var cancelled=false
        var closed=false
        assertThrows(java.io.IOException::class.java) {
            CsqttAutoDetection.connect<AutoCloseable>(auto(),null,{cancelled},{AutoCloseable {closed=true}},{cancelled=true})
        }
        assertTrue(closed)
    }

    @Test fun cancellationStopsFallbackAndClosesAJustReadyOwnerWithoutCaching() {
        var cancelled=false;var closed=false;var writes=0
        assertThrows(java.io.IOException::class.java) {CsqttAutoDetection.connect<AutoCloseable>(auto(),null,{cancelled},{cancelled=true;AutoCloseable {closed=true}},{writes++})}
        assertTrue(closed);assertEquals(0,writes)
        cancelled=false
        var attempts=0
        assertThrows(CsqttNativeFailure.Rejected::class.java) {CsqttAutoDetection.connect(auto(),null,{cancelled},{attempts++;cancelled=true;throw CsqttNativeFailure.Rejected(CsqttNativeFailure.Kind.PROTOCOL)},{fail()})}
        assertEquals(1,attempts)
    }
    @Test fun schedulerReturnsBeforeProbeAndRetainsDeferredAutoOnFailure() {
        val queue=ArrayDeque<Runnable>();val executor=Executor {queue.add(it)};val state=CsqttAutoDetection.State();var calls=0
        state.schedule(executor,false){calls++;null}
        assertEquals(CsqttAutoDetection.Phase.DEFERRED,state.phase);assertTrue(queue.isEmpty())
        state.schedule(executor,true){calls++;assertEquals(CsqttAutoDetection.Phase.PROBING,state.phase);throw SocketTimeoutException()}
        assertEquals(CsqttAutoDetection.Phase.QUEUED,state.phase);assertEquals(0,calls)
        state.schedule(executor,true){fail("Duplicate work");null};assertEquals(1,queue.size)
        queue.remove().run();assertEquals(CsqttAutoDetection.Phase.DEFERRED,state.phase)
        state.schedule(executor,true){CsqttAutoDetection.order[1]};queue.remove().run()
        assertEquals(CsqttAutoDetection.Phase.RESOLVED,state.phase);assertEquals(CsqttAutoDetection.order[1],state.target)
    }
    @Test fun executorRefusalLeavesAutoForFirstConnection() {
        val state=CsqttAutoDetection.State()
        state.schedule(Executor {throw java.util.concurrent.RejectedExecutionException()},true){fail();null}
        assertEquals(CsqttAutoDetection.Phase.DEFERRED,state.phase)
    }
    @Test fun cacheIsPerServerResetChangesNamespaceAndManualOverrideWins() {
        val source=auto();val originalKey=CsqttAutoDetection.key(source)
        assertEquals(originalKey,CsqttAutoDetection.key(JSONObject(source.toString()).put("name","renamed").put("probe_only",true)))
        for(key in listOf("server","password"))assertNotEquals(originalKey,CsqttAutoDetection.key(JSONObject(source.toString()).put(key,"changed-fixture")))
        val reset=CsqttAutoDetection.reset(source)
        assertTrue(CsqttGenerations.automatic(reset));assertNotEquals(originalKey,CsqttAutoDetection.key(reset))
        assertEquals(64,originalKey.length);assertFalse(originalKey.contains("fixture"))
        val manual=CsqttGenerations.select(reset,"2.0")
        val private=SwgProfile.privateConfig(manual,22001,22002,"u","p","fixture-installation")
        assertFalse(private.has(CsqttGenerations.EPOCH));assertFalse(private.has(CsqttGenerations.SELECTION))
        assertFalse(CsqttGenerations.automatic(manual));assertEquals(listOf(CsqttAutoDetection.Target("2.0")),CsqttAutoDetection.plan(manual,CsqttAutoDetection.order.first()))
        var attempts=0
        assertThrows(CsqttNativeFailure.Rejected::class.java) {CsqttAutoDetection.connect(manual,null,{false},{attempts++;throw CsqttNativeFailure.Rejected(CsqttNativeFailure.Kind.PROTOCOL)},{fail()})}
        assertEquals(1,attempts)
    }
    @Test fun catalogueAndShareKeepAutoResetAndManualChoicesAcrossReload() {
        val raw=entry();val catalogue=ConnectionCatalogue("");catalogue.add(link)
        catalogue.edit(raw.id,JSONObject().put("csqttVersion",CsqttGenerations.AUTO))
        val autoEntry=ConnectionCatalogue(catalogue.serialize()).entries().single()
        assertTrue(CsqttGenerations.automatic(autoEntry.carrier!!))
        val reset=CsqttAutoDetection.reset(autoEntry.carrier!!)
        catalogue.edit(raw.id,ConnectionEdits.snapshot(autoEntry).put("carrier",reset))
        val restored=ConnectionCatalogue(catalogue.serialize()).entries().single()
        assertEquals(reset.getString(CsqttGenerations.EPOCH),restored.carrier!!.getString(CsqttGenerations.EPOCH))
        val shared=ServerImport.read(ConnectionEdits.share(restored,true)).single()
        assertTrue(CsqttGenerations.automatic(shared.carrier!!));assertFalse(CsqttImportResolution.ambiguous(shared))
        val manual=ConnectionEdits.apply(restored,ConnectionEdits.snapshot(restored).put("carrier",CsqttGenerations.select(restored.carrier!!,"2.5")))
        assertEquals("csqtt25",EngineModules.carrierKind(manual.carrier!!));assertFalse(CsqttGenerations.automatic(manual.carrier!!))
    }
    @Test fun copyExplainsPackagesLaterSettingsAndDeferredDetection() {
        assertEquals("Выберите поколение CSQTT",CsqttGenerations.CHOICE_TITLE)
        assertTrue(CsqttGenerations.CHOICE_HELP.contains("позже"));assertTrue(CsqttGenerations.CHOICE_HELP.contains("настройках сервера"))
        for(part in listOf("BS Engine","в фоне","настройках сервера"))assertTrue(CsqttGenerations.AUTO_HELP.contains(part))
    }
    @Test fun familyConversionPreservesExplicitVkAuthorizationMethod() {
        for(auth in listOf("vkcalls","legacy")) {
            val legacy=JSONObject(entry().carrier!!.toString()).put("vk_anon_path",auth)
            val modern=CsqttGenerations.select(legacy,"2.5")
            assertEquals(auth,modern.getString("vk_auth_mode"))
            for(generation in listOf("legacy","2.1.5","2.0"))
                assertEquals(auth,CsqttGenerations.select(modern,generation).getString("vk_anon_path"))
        }
    }
    @Test fun schedulerDeduplicatesProfilesAndReleasesFailedOrRefusedAdmission() {
        val scheduler=CsqttAutoDetection.Scheduler();val queue=ArrayDeque<Runnable>();val executor=Executor {queue.add(it)}
        val key=CsqttAutoDetection.key(auto());var calls=0
        scheduler.schedule(key,executor,false){fail();null};assertTrue(queue.isEmpty())
        scheduler.schedule(key,executor,true){calls++;throw SocketTimeoutException()}
        scheduler.schedule(key,executor,true){fail("Duplicate same-profile probe");null}
        scheduler.schedule("other-profile",executor,true){calls++;null}
        assertEquals(2,queue.size);assertEquals(0,calls)
        queue.remove().run();queue.remove().run();assertEquals(2,calls)
        scheduler.schedule(key,Executor {throw java.util.concurrent.RejectedExecutionException()},true){fail();null}
        scheduler.schedule(key,executor,true){calls++;CsqttAutoDetection.order.first()}
        assertEquals(1,queue.size);queue.remove().run();assertEquals(3,calls)
    }
    @Test fun classifiedAuthWithProtocolDetailCannotAdvanceNegotiation() {
        val line="__CSQTT_EVENT__|ERROR|"+JSONObject().put("fatal",true).put("code","handshake_rejected")
            .put("message","FATAL_AUTH: diagnostic FATAL_PROTOCOL: mismatch")
        var attempts=0
        val error=assertThrows(CsqttNativeFailure.Rejected::class.java) {
            CsqttAutoDetection.connect(auto(),null,{false},{attempts++;throw CsqttNativeFailure.Rejected(requireNotNull(CsqttNativeFailure.read(line)))},{fail()})
        }
        assertEquals(CsqttNativeFailure.Kind.ACCESS,error.kind);assertEquals(1,attempts)
    }
}

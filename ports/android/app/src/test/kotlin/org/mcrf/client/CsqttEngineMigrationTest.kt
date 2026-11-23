package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class CsqttEngineMigrationTest {
    private val newest=CsqttAutoDetection.order.first()
    private val old=CsqttAutoDetection.order[2]
    private val replacement="verified-unified-engine"
    private fun profile(implementation:String=old.implementation,host:String="migration.invalid")=
        Csqtt25Profile.fromJson("fixture",JSONObject().put("type","csqtt").put("csqtt_generation","2.5")
            .put("csqtt_implementation",implementation).put("server",host).put("server_port",46000)
            .put("password","fixture-only").put("hashes",JSONArray().put("fixture-call-0123456789abcdef"))).carrier!!
    private fun catalogue(profile:JSONObject=profile())=ConnectionCatalogue("").apply {add(profile.toString())}
    private fun claim(data:ConnectionCatalogue,now:Long=0,generation:String="generation-a"):CsqttEngineMigration.Ticket {
        CsqttEngineMigration.initialize(data,replacement,"epoch-a")
        return requireNotNull(CsqttEngineMigration.claim(data,generation,replacement,now))
    }
    private fun proof(target:CsqttAutoDetection.Target=newest)=CsqttEngineMigration.Evidence(target,target.implementation,true)
    private fun row(data:ConnectionCatalogue,id:String)=data.csqttMigrationState()!!.getJSONObject("rows").getJSONObject(id)
    private fun users(data:ConnectionCatalogue)=JSONObject(data.serialize()).apply {remove(CsqttEngineMigration.KEY)}.let(CsqttEngineMigration::canonical)

    @Test fun pinnedAndCached251MigrationAlwaysStartsFreshAt253() {
        val source=profile()
        assertEquals(listOf(old),CsqttAutoDetection.plan(source,newest)) // Ordinary manual semantics unchanged.
        val attempts=mutableListOf<CsqttAutoDetection.Target>()
        val result=CsqttEngineMigration.probe(source,{false}) {candidate,target->
            attempts.add(target)
            assertEquals("swg-panel-2.5.3",candidate.getString("csqtt_implementation"))
            assertFalse(CsqttGenerations.automatic(candidate))
            proof(target)
        }
        assertEquals(listOf(newest),attempts);assertEquals(newest,result!!.target)
    }

    @Test fun allSavedCsqttAreIncludedRegardlessOfManualSubscriptionOrAutoSelection() {
        val data=catalogue()
        val automatic=profile(host="auto.invalid").put(CsqttGenerations.SELECTION,CsqttGenerations.AUTO)
        val subscription=profile(host="subscription.invalid")
        data.add(automatic.toString())
        data.replaceSubscription("https://subscription.invalid/list",ServerImport.read(subscription.toString()))
        data.add("vless://11111111-1111-4111-8111-111111111111@vpn.invalid:443?security=tls#VPN")
        CsqttEngineMigration.initialize(data,replacement,"epoch-a")
        val rows=data.csqttMigrationState()!!.getJSONObject("rows")
        val expected=data.entries().filter {it.carrier?.optString("type")=="csqtt"}.map {it.id}.toSet()
        assertEquals(3,expected.size);assertEquals(expected,rows.keys().asSequence().toSet())
        assertFalse(CsqttEngineMigration.initialize(data,"changed-engine","different-epoch"))
        assertEquals("epoch-a",data.csqttMigrationState()!!.getString("epoch"))
    }

    @Test fun duplicateManualAndSubscriptionOccurrencesKeepOneTicketAndBothOriginalLinks() {
        val source=profile().toString();val data=ConnectionCatalogue("")
        data.add(source);data.replaceSubscription("https://subscription.invalid/list",ServerImport.read(source))
        val before=JSONObject(data.serialize());val ticket=claim(data)
        assertEquals(1,data.csqttMigrationState()!!.getJSONObject("rows").length())
        assertTrue(CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,proof()))
        val after=JSONObject(data.serialize())
        assertEquals(before.getJSONArray("servers").toString(),after.getJSONArray("servers").toString())
        assertEquals(before.getJSONArray("subscriptions").toString(),after.getJSONArray("subscriptions").toString())
        assertEquals(ticket.id,data.entries().single().id)
        assertEquals(newest.implementation,data.entries().single().carrier!!.getString("csqtt_implementation"))
    }

    @Test fun successChangesOnlyImplementationPreservingOptionsIdentityAndPool() {
        val source=profile().put("fingerprint","firefox").put("turn_transport","tcp")
            .put("custom_turn_ips",JSONArray().put("1.1.1.1")).put("captcha_mode","rjs")
            .put("link_workers",37).put("workers",37).put("allow_hash_redistribution",true)
            .put("device_id","fixture-device").put("salt","fixture-salt")
            .put(CsqttGenerations.SELECTION,CsqttGenerations.AUTO)
            .put(CsqttGenerations.EPOCH,"a".repeat(32))
        val data=catalogue(source);val entry=data.entries().single();data.setAuto(entry.id,true)
        val before=JSONObject(entry.carrier!!.toString());val ticket=claim(data)
        assertTrue(CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,proof()))
        val saved=data.entries().single()
        assertEquals(entry.id,saved.id);assertEquals(entry.original,saved.original)
        assertEquals(entry.name,saved.name);assertTrue(saved.id in data.autoIds())
        before.put("csqtt_implementation",newest.implementation)
        assertTrue(CsqttEngineMigration.equivalent(before,saved.carrier))
        assertEquals("confirmed",row(data,ticket.id).getString("status"))
        assertFalse(row(data,ticket.id).has("serverVersion")) // Compatibility proof, not patch fingerprinting.
    }

    @Test fun readyOrUnprovenResultCannotChangeOrConfirmAnything() {
        val data=catalogue();val ticket=claim(data);val before=users(data)
        assertFalse(CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,
            CsqttEngineMigration.Evidence(newest,newest.implementation,false)))
        assertEquals(before,users(data));assertEquals("pending",row(data,ticket.id).getString("status"))
        assertNull(CsqttEngineMigration.claim(data,ticket.generation,replacement,0))
        assertNotNull(CsqttEngineMigration.claim(data,ticket.generation,replacement,CsqttEngineMigration.DEFER_MS))
    }

    @Test fun failedNetworkIsDurableAndRetryableWithoutChangingUserData() {
        val data=catalogue();val ticket=claim(data);val before=users(data)
        val result=CsqttEngineMigration.probe(ticket.profile,{false}) {_,_->throw SocketTimeoutException()}
        assertNull(result)
        assertFalse(CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,result))
        val reloaded=ConnectionCatalogue(data.serialize())
        assertEquals(before,users(reloaded));assertEquals(CsqttEngineMigration.DEFER_MS,CsqttEngineMigration.nextAt(reloaded))
        assertNull(CsqttEngineMigration.claim(reloaded,ticket.generation,replacement,CsqttEngineMigration.DEFER_MS-1))
        assertEquals(2L,CsqttEngineMigration.claim(reloaded,ticket.generation,replacement,CsqttEngineMigration.DEFER_MS)!!.attempt)
    }

    @Test fun shortBurstBecomesDailyCooldownAndOfflineServerIsNeverAbandoned() {
        val data=catalogue();val before=users(data)
        var ticket=claim(data)
        var now=0L
        repeat(CsqttEngineMigration.SHORT_RETRY_BURST+3) {index->
            assertEquals(index+1L,ticket.attempt)
            CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,null)
            val due=requireNotNull(CsqttEngineMigration.nextAt(data))
            if(index>=CsqttEngineMigration.SHORT_RETRY_BURST-1)assertEquals(24*60*60_000L,due-now)
            assertNull(CsqttEngineMigration.claim(data,ticket.generation,replacement,due-1))
            now=due
            ticket=requireNotNull(CsqttEngineMigration.claim(data,ticket.generation,replacement,now))
        }
        assertEquals("pending",row(data,ticket.id).getString("status"));assertNotNull(CsqttEngineMigration.nextAt(data))
        assertEquals(before,users(data))
        assertTrue(CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,proof()))
        assertEquals(newest.implementation,data.entries().single().carrier!!.getString("csqtt_implementation"))
    }

    @Test fun protocolRejectionAloneCanAdvanceNotAuthTimeoutOrGenericErrors() {
        val visited=mutableListOf<CsqttAutoDetection.Target>()
        val result=CsqttEngineMigration.probe(profile(),{false}) {_,target->
            visited.add(target)
            if(target!=old)throw CsqttNativeFailure.Rejected(CsqttNativeFailure.Kind.PROTOCOL)
            proof(target)
        }
        assertEquals(CsqttAutoDetection.order.take(3),visited);assertEquals(old,result!!.target)
        for(error in listOf(SocketTimeoutException(),CsqttNativeFailure.Rejected(CsqttNativeFailure.Kind.ACCESS),IllegalStateException())) {
            var calls=0
            assertNull(CsqttEngineMigration.probe(profile(),{false}) {_,_->calls++;throw error})
            assertEquals(1,calls)
        }
    }

    @Test fun cancelledBeforeAndAfterProofCannotBePublished() {
        assertNull(CsqttEngineMigration.probe(profile(),{true}) {_,_->fail("Must not launch");proof()})
        var cancelled=false
        assertNull(CsqttEngineMigration.probe(profile(),{cancelled}) {_,_->cancelled=true;proof()})
    }

    @Test fun mismatchedNativeImplementationOrWrongTargetIsRejected() {
        assertNull(CsqttEngineMigration.probe(profile(),{false}) {_,_->proof(old)})
        assertNull(CsqttEngineMigration.probe(profile(),{false}) {_,_->
            CsqttEngineMigration.Evidence(newest,old.implementation,true)
        })
        val data=catalogue();val ticket=claim(data);val before=data.serialize()
        assertThrows(IllegalArgumentException::class.java) {
            CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,
                CsqttEngineMigration.Evidence(newest,old.implementation,true))
        }
        assertEquals(before,data.serialize())
    }

    @Test fun restoreGenerationReplacementAndOldAttemptCannotCommit() {
        val data=catalogue();val ticket=claim(data);val before=data.serialize()
        assertFalse(CsqttEngineMigration.complete(data,ticket,"restored-generation",replacement,proof()))
        assertFalse(CsqttEngineMigration.complete(data,ticket,ticket.generation,"replacement-changed",proof()))
        assertEquals(before,data.serialize())
        val newer=requireNotNull(CsqttEngineMigration.claim(data,ticket.generation,replacement,CsqttEngineMigration.DEFER_MS))
        assertFalse(CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,proof()))
        assertTrue(CsqttEngineMigration.complete(data,newer,newer.generation,replacement,proof()))
    }

    @Test fun editDeleteReaddAndSubscriptionRemovalInvalidateInflightProof() {
        for(kind in listOf("edit","aba","subscription")) {
            val data=if(kind=="subscription")ConnectionCatalogue("").apply {
                replaceSubscription("https://subscription.invalid/list",ServerImport.read(profile().toString()))
            }else catalogue()
            val ticket=claim(data);val before=ConnectionCatalogue(data.serialize())
            when(kind) {
                "edit"->data.edit(ticket.id,ConnectionEdits.snapshot(data.entries().single()).put("name","Changed"))
                "aba"->{data.remove(ticket.id);CsqttEngineMigration.invalidateChanged(before,data);data.add(profile().toString())}
                else->data.removeSubscription(data.subscriptions().single().getString("id"))
            }
            CsqttEngineMigration.invalidateChanged(before,data)
            assertFalse(CsqttEngineMigration.accepts(data,ticket,ticket.generation,replacement))
            assertFalse(CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,proof()))
        }
    }

    @Test fun unrelatedHealthAndPoolChangesDoNotInvalidateFreshCarrier() {
        val data=catalogue();val ticket=claim(data);val before=ConnectionCatalogue(data.serialize())
        data.recordProbe(ticket.id,42,1000);data.setAuto(ticket.id,true)
        CsqttEngineMigration.invalidateChanged(before,data)
        assertTrue(CsqttEngineMigration.accepts(data,ticket,ticket.generation,replacement))
        assertTrue(CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,proof()))
    }

    @Test fun conversionRefusesUnknownOptionInsteadOfDroppingIt() {
        val source=profile().put("future_option","must-survive")
        var launches=0
        assertNull(CsqttEngineMigration.probe(source,{false}) {_,_->launches++;proof()})
        assertEquals(0,launches);assertEquals("must-survive",source.getString("future_option"))
    }

    @Test fun legacyConversionKeepsExactStreamsAndAuthWhenRepresentable() {
        val legacy=SwgProfile.fromJson("fixture",JSONObject().put("type","csqtt")
            .put("server","legacy.invalid").put("server_port",46000).put("password","fixture-only")
            .put("hashes",JSONArray().put("fixture-call-0123456789abcdef"))
            .put("workers",18).put("vk_anon_path","legacy")).carrier!!
        val converted=CsqttGenerations.migrationProfile(legacy,newest)
        assertEquals(18,converted.getInt("workers"));assertEquals(18,converted.getInt("link_workers"))
        assertEquals("legacy",converted.getString("vk_auth_mode"));assertEquals(legacy.getString("password"),converted.getString("password"))
        assertEquals(newest.implementation,converted.getString("csqtt_implementation"))
    }

    @Test fun legacyCatalogueCanTraverseAllSixCandidatesWithPreservedOptions() {
        val source=ServerImport.read("csqtt://connect?v=2&host=legacy.invalid&peer=46000&password=fixture-only&hashes=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").single().carrier!!
        val visited=mutableListOf<CsqttAutoDetection.Target>()
        val result=CsqttEngineMigration.probe(source,{false}) {candidate,target->
            assertEquals(source.getString("password"),candidate.getString("password"))
            assertEquals(18,candidate.getInt("workers"));visited.add(target)
            if(target!=CsqttAutoDetection.order.last())throw CsqttNativeFailure.Rejected(CsqttNativeFailure.Kind.PROTOCOL)
            CsqttEngineMigration.Evidence(target,target.generation,true)
        }
        assertEquals(CsqttAutoDetection.order,visited);assertEquals("2.0",result!!.target.generation)
        val data=catalogue(source);val ticket=claim(data)
        assertTrue(CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,result))
        assertEquals(ticket.id,data.entries().single().id)
        assertEquals("2.0",CsqttGenerations.family(data.entries().single().carrier!!).id)
    }

    @Test fun savedNormalizedManualAutoAndResetProfilesReachAConcreteProbe() {
        val legacy=ServerImport.read("csqtt://connect?v=2&host=saved.invalid&peer=46000&password=fixture-only&hashes=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").single().carrier!!
        val account=SwgProfile.fromJson("fixture",JSONObject().put("type","csqtt")
            .put("server","account.invalid").put("password","fixture-only")
            .put("hashes",JSONArray()).put("vk_anon_path","auto_js").put("workers",126)).carrier!!
        for(source in listOf(legacy,profile(),CsqttAutoDetection.reset(legacy),CsqttAutoDetection.reset(profile()),account)) {
            val data=ConnectionCatalogue(catalogue(source).serialize())
            val ticket=claim(data)
            var launches=0
            val evidence=CsqttEngineMigration.probe(ticket.profile,{false}) {candidate,target->
                launches++
                assertFalse(candidate.has(CsqttGenerations.SELECTION))
                assertFalse(candidate.has(CsqttGenerations.EPOCH))
                CsqttEngineMigration.Evidence(target,if(target.generation=="2.5")target.implementation else target.generation,true)
            }
            assertEquals(1,launches)
            assertNotNull(evidence)
            assertTrue(CsqttEngineMigration.complete(data,ticket,ticket.generation,replacement,evidence))
            val saved=ConnectionCatalogue(data.serialize()).entries().single()
            assertEquals(ticket.id,saved.id)
            assertTrue(CsqttEngineMigration.equivalent(
                CsqttGenerations.migrationProfile(ticket.profile,evidence!!.target),saved.carrier))
        }
    }

    @Test fun unrepresentableModernStreamsStillProbeOwnLegacyFamilyLosslessly() {
        val source=SwgProfile.fromJson("fixture",JSONObject().put("type","csqtt")
            .put("csqtt_generation","legacy").put("server","legacy.invalid").put("server_port",46000)
            .put("password","fixture-only").put("hashes",JSONArray((1..6).map {"fixture-call-0123456789abcdef-$it"}))
            .put("workers",126).put("extra_streams",true)).carrier!!
        val visited=mutableListOf<CsqttAutoDetection.Target>()
        val result=CsqttEngineMigration.probe(source,{false}) {candidate,target->
            visited.add(target)
            assertTrue(CsqttEngineMigration.equivalent(source,candidate))
            CsqttEngineMigration.Evidence(target,target.generation,true)
        }
        assertEquals(listOf(CsqttAutoDetection.Target("legacy")),visited)
        assertEquals("legacy",result!!.target.generation);assertEquals(126,source.getInt("workers"))
    }
}

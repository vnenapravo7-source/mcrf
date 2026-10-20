package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineUpdatePolicyTest {
    private val old = "74cac6d47bf4c27947348ee957538a2c0728a485"
    private val latest = "f12e981629a18c8c01007d59ac002254c02972bb"
    private fun release(tag: String, beta: Boolean = false, draft: Boolean = false) = JSONObject().put("tag_name", tag).put("prerelease", beta).put("draft", draft)
    private fun rejects(block: () -> Unit) { try { block(); fail("must reject") } catch (_: IllegalArgumentException) {} }
    @Test fun stableUsesNumericOrderAndNeverMovingBranchOrBeta() {
        val input = JSONArray().put(release("v0.9.0")).put(release("v0.10.0")).put(release("v9.0.0", beta = true))
            .put(release("v8.0.0", draft = true)).put(release("main")).put(release("v0.11.0-beta.1"))
        assertEquals("v0.10.0", EngineUpdatePolicy.stableTag(input))
    }
    @Test fun noEligibleReleaseIsNotUpToDate() { rejects { EngineUpdatePolicy.stableTag(JSONArray().put(release("nightly"))) } }
    @Test fun revisionRequiresFullImmutableCommit() {
        listOf("main", "f12e981", "F12E981629A18C8C01007D59AC002254C02972BB", "../etc").forEach { value -> rejects { EngineUpdatePolicy.Revision("v0.7.3", value) } }
        listOf("v0.7.3-beta", "v00.7.3", "v2147483648.1.1").forEach { value -> rejects { EngineUpdatePolicy.Revision(value, latest) } }
    }
    @Test fun compareDistinguishesRetagAndDoesNotDowngrade() {
        val current = EngineUpdatePolicy.Revision("v0.4.2", old)
        assertTrue(EngineUpdatePolicy.needsUpdate(current, EngineUpdatePolicy.Revision("v0.7.3", latest)))
        assertTrue(EngineUpdatePolicy.needsUpdate(current, EngineUpdatePolicy.Revision("v0.4.2", latest)))
        assertFalse(EngineUpdatePolicy.needsUpdate(current, current))
        assertFalse(EngineUpdatePolicy.needsUpdate(current, EngineUpdatePolicy.Revision("v0.4.1", latest)))
    }
    @Test fun signedApkMustNameOriginalSource() {
        val m = JSONObject().put("repository", EngineUpdatePolicy.UPSTREAM).put("version", "v0.7.3").put("commit", latest)
        assertEquals(latest, EngineUpdatePolicy.source(m).commit)
        rejects { EngineUpdatePolicy.source(m.put("repository", "attacker/OpenFlux")) }
    }
    @Test fun activeConnectionQueuesConsentAndLimitationsRemainExplicit() {
        assertEquals(EngineUpdatePolicy.Phase.QUEUED, EngineUpdatePolicy.readyPhase(true))
        assertEquals(EngineUpdatePolicy.Phase.CONSENT, EngineUpdatePolicy.readyPhase(false))
        assertTrue(EngineUpdatePolicy.SCRIPT_LIMITATION.contains(".flux"))
        assertTrue(EngineUpdatePolicy.ROLLBACK_LIMITATION.contains("versionCode"))
    }
    @Test fun exactModuleArchiveRejectsExtraCoreDuplicateAndWrongAbi() {
        val good=listOf("lib/arm64-v8a/libopenflux.so")
        assertTrue(EngineModules.validNativeEntries("openflux","arm64-v8a",good))
        listOf(good+"lib/arm64-v8a/libbox.so",good+good,listOf("lib/x86_64/libopenflux.so"),listOf("lib/arm64-v8a/../libopenflux.so"))
            .forEach { assertFalse(EngineModules.validNativeEntries("openflux","arm64-v8a",it)) }
        assertFalse(EngineModules.validNativeEntries("csqtt","arm64-v8a",listOf("lib/arm64-v8a/libqwdtt.so")))
        assertTrue(EngineModules.validNativeEntries("csqtt","arm64-v8a",listOf("lib/arm64-v8a/libqwdtt.so","lib/arm64-v8a/libcsqtt_transport.so")))
    }
    @Test fun automaticDefaultsToInstalledOnlyAndPreservesExplicitOptOut() {
        assertTrue(EngineUpdatePolicy.automaticEligible(null,true))
        assertFalse(EngineUpdatePolicy.automaticEligible(null,false))
        assertFalse(EngineUpdatePolicy.automaticEligible(false,true))
        assertFalse(EngineUpdatePolicy.automaticEligible(true,false))
        assertTrue(EngineUpdatePolicy.automaticEligible(true,true))
    }
}

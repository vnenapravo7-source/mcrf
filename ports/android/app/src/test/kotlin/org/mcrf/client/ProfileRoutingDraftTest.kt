package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProfileRoutingDraftTest {
    private fun rule(id:String="first")=JSONObject().put("id",id).put("name",id).put("text","example.test")
        .put("mode","addresses").put("target","proxy").put("enabled",true)
    private fun state()=RouteRules.defaults().put("rules",JSONArray().put(rule()))
        .put("future",JSONObject().put("opaque",JSONArray().put("retained")))
    @Test fun cancelAndUncommittedSnapshotsCannotMutateSourceOrAnotherProfile() {
        val live=state();val before=live.toString()
        val profile=ProfileRoutingDraft(before);val other=ProfileRoutingDraft(before)
        profile.snapshot().getJSONArray("rules").getJSONObject(0).put("target","block")
        assertEquals(before,profile.read());assertEquals(before,other.read());assertEquals(before,live.toString())
        profile.edit {it.getJSONArray("rules").getJSONObject(0).put("target","direct")}
        assertEquals(before,live.toString());assertEquals(before,other.read())
        assertEquals("direct",JSONObject(profile.read()).getJSONArray("rules").getJSONObject(0).getString("target"))
    }
    @Test fun invalidChangeAndAssetFailureLeaveRevisionAndFullDraftUntouched() {
        val draft=ProfileRoutingDraft(state().toString());val before=draft.read();val revision=draft.revision()
        assertThrows(IllegalArgumentException::class.java){draft.edit {it.put("defaultTarget","invalid")}}
        assertEquals(before,draft.read());assertEquals(revision,draft.revision())
        assertThrows(IllegalArgumentException::class.java){draft.edit(validate={throw IllegalArgumentException("asset unavailable")}) {it.put("defaultTarget","direct")}}
        assertEquals(before,draft.read());assertEquals(revision,draft.revision())
    }
    @Test fun staleRuleCannotOverwriteLaterEditsOrChangedGroup() {
        val draft=ProfileRoutingDraft(state().toString());val old=draft.revision()
        draft.edit {RoutingGroups.select(it,"bs")}
        val before=draft.read()
        assertThrows(IllegalArgumentException::class.java){draft.edit(old) {it.put("defaultTarget","block")}}
        assertThrows(IllegalArgumentException::class.java){draft.edit(generation="another-profile") {it.put("defaultTarget","block")}}
        assertEquals(before,draft.read())
    }
    @Test fun nativeAndUnknownFieldsSurviveOutputEditAndGroupRoundTrip() {
        val native=rule().put("text","").put("nativeMatch",JSONObject().put("domain",JSONArray().put("native.test")))
            .put("futureRule",JSONObject().put("keep",true))
        val source=state().put("rules",JSONArray().put(native));val draft=ProfileRoutingDraft(source.toString())
        draft.edit {it.getJSONArray("rules").getJSONObject(0).put("target","block")}
        draft.edit {RoutingGroups.select(it,"bs");it.getJSONArray("rules").put(rule("bs-rule"))}
        draft.edit {RoutingGroups.select(it,"vpn")}
        val next=draft.snapshot();val saved=next.getJSONArray("rules").getJSONObject(0)
        assertEquals(native.getJSONObject("nativeMatch").toString(),saved.getJSONObject("nativeMatch").toString())
        assertEquals("block",saved.getString("target"));assertTrue(saved.getJSONObject("futureRule").getBoolean("keep"))
        assertEquals(source.getJSONObject("future").toString(),next.getJSONObject("future").toString())
    }
    @Test fun successfulEditDoesNotRetainMutableReferencesFromCallback() {
        val draft=ProfileRoutingDraft(state().toString());var escaped:JSONObject?=null
        val returned=draft.edit {escaped=it;it.put("defaultTarget","direct")}
        escaped!!.put("defaultTarget","block");returned.getJSONObject("future").put("opaque","changed")
        assertEquals("direct",draft.snapshot().getString("defaultTarget"))
        assertEquals("retained",draft.snapshot().getJSONObject("future").getJSONArray("opaque").getString(0))
    }
    @Test fun compiledGeoRuleRemainsEditableWithoutLosingMetadata() {
        val source=state();source.getJSONArray("rules").getJSONObject(0).put("text","geosite:youtube")
        TextGeoRules.compile(source)
        val draft=ProfileRoutingDraft(source.toString());val original=draft.snapshot().getJSONArray("rules").getJSONObject(0)
        val editable=TextGeoRules.editable(original)
        assertEquals("geosite:youtube",editable.getString("text"));assertFalse(editable.has("nativeMatch"))
        draft.edit {it.getJSONArray("rules").getJSONObject(0).put("name","Renamed")}
        assertEquals("geosite:youtube",TextGeoRules.editable(draft.snapshot().getJSONArray("rules").getJSONObject(0)).getString("text"))
    }
    @Test fun olderProfileOnlyOverridesPreferencesItContains() {
        val config=NetworkProfileConfiguration(state().toString(),"[]","{\"automatic\":false}")
        val prefs=JSONObject().put("autoPolicy",AutoSelection.BS_ENGINES).put("tolerance",90)
            .put("bsLastManualVpn","a".repeat(64)).put("theme","dark").put("automatic",true)
        val values=mapOf("routing.enc" to state().toString(),"connections.enc" to "{\"servers\":[],\"custom\":true}","preferences.enc" to prefs.toString())
        val before=values.toMap();val next=NetworkProfileSwitch.apply(config,values)
        val saved=JSONObject(next.getValue("preferences.enc"))
        assertFalse(saved.getBoolean("automatic"));assertEquals(90,saved.getInt("tolerance"))
        assertEquals(AutoSelection.BS_ENGINES,saved.getString("autoPolicy"));assertEquals("dark",saved.getString("theme"))
        assertEquals(prefs.getString("bsLastManualVpn"),saved.getString("bsLastManualVpn"))
        assertEquals(before,values);assertTrue(JSONObject(next.getValue("connections.enc")).getBoolean("custom"))
    }
    @Test fun cancellationDuringValidationDoesNotBlockOrPublishEvenWhenRevisionIsUnchanged() {
        val source=state();val before=source.toString();val draft=ProfileRoutingDraft(before)
        val revision=draft.revision();val token=draft.editToken()
        val validating=CountDownLatch(1);val release=CountDownLatch(1)
        val threads=Executors.newFixedThreadPool(2)
        try {
            val commit=threads.submit(Callable {runCatching {
                draft.edit(revision,validate={
                    validating.countDown()
                    check(release.await(5,TimeUnit.SECONDS)){"Validation was not released"}
                },token=token) {it.getJSONArray("rules").getJSONObject(0).put("target","block")}
            }})
            assertTrue("Validation must start",validating.await(5,TimeUnit.SECONDS))
            // This must finish while validation is still blocked: cancellation cannot wait for it.
            threads.submit(Runnable {token.cancel()}).get(2,TimeUnit.SECONDS)
            assertFalse(token.isActive());assertEquals(before,draft.read())
            release.countDown()
            assertTrue(commit.get(5,TimeUnit.SECONDS).exceptionOrNull() is CancellationException)
            assertEquals(revision,draft.revision());assertEquals(before,draft.read());assertEquals(before,source.toString())
        }finally {
            release.countDown();threads.shutdownNow();threads.awaitTermination(5,TimeUnit.SECONDS)
        }
    }
    @Test fun changeDuringValidationIsRetainedAndStalePreparedResultCannotPublish() {
        val draft=ProfileRoutingDraft(state().toString());val revision=draft.revision()
        val validating=CountDownLatch(1);val release=CountDownLatch(1)
        val threads=Executors.newFixedThreadPool(2)
        try {
            val commit=threads.submit(Callable {runCatching {
                draft.edit(revision,validate={
                    validating.countDown();check(release.await(5,TimeUnit.SECONDS))
                },token=draft.editToken()) {it.put("defaultTarget","block")}
            }})
            assertTrue(validating.await(5,TimeUnit.SECONDS))
            threads.submit(Runnable {draft.edit {it.put("defaultTarget","direct")}}).get(2,TimeUnit.SECONDS)
            val newer=draft.read();val newerRevision=draft.revision()
            release.countDown()
            assertTrue(commit.get(5,TimeUnit.SECONDS).exceptionOrNull() is IllegalArgumentException)
            assertEquals(newer,draft.read());assertEquals(newerRevision,draft.revision())
            assertEquals("direct",draft.snapshot().getString("defaultTarget"))
        }finally {
            release.countDown();threads.shutdownNow();threads.awaitTermination(5,TimeUnit.SECONDS)
        }
    }
    @Test fun cancelledDialogTokenCannotBeReusedButFreshDialogCanSave() {
        val draft=ProfileRoutingDraft(state().toString());val cancelled=draft.editToken()
        val before=draft.read();var validated=false
        cancelled.cancel()
        assertThrows(CancellationException::class.java) {
            draft.edit(validate={validated=true},token=cancelled) {it.put("defaultTarget","block")}
        }
        assertFalse(validated);assertEquals(before,draft.read())
        draft.edit(token=draft.editToken()) {it.put("defaultTarget","direct")}
        assertEquals("direct",draft.snapshot().getString("defaultTarget"))
    }
}

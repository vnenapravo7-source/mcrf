package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkProfilesTest {
    private val mobile=ConditionEnvironment(cellular=true)
    private val home=ConditionEnvironment(wifi=true,ssids=setOf("Home"),locationAllowed=true)
    private fun config(id:Char,target:String="proxy",automatic:Boolean=false):NetworkProfileConfiguration {
        val routes=RouteRules.defaults().put("defaultTarget",target)
        RoutingGroups.ensure(routes)
        val prefs=JSONObject().put("selected",id.toString().repeat(64)).put("automatic",automatic)
            .put("autoDetectBs",automatic).put("bsDetectionSelection",if(automatic)"fastest"else"off")
            .put("bsLastManualVpn",id.toString().repeat(64)).put("bsLastManualBs","")
            .put("interval",120).put("bsDetectionInterval",60).put("autoPolicy",AutoSelection.LATENCY)
        return NetworkProfileConfiguration.capture(routes.toString(),JSONObject().put("autoServers",JSONArray().put(id.toString().repeat(64))).toString(),prefs)
    }
    private fun profiles(a:NetworkProfileConfiguration?,b:NetworkProfileConfiguration?)=listOf(
        ConditionProfile("mobile","Mobile data",ProfileCondition.CELLULAR,configuration=a),
        ConditionProfile("home","Дом",ProfileCondition.SSID,ssid="Home",configuration=b))
    @Test fun safeModePreservesCurrentAndSavedNetworkConfigurations() {
        val a=config('a');val b=config('b',"direct",true)
        val section=NetworkProfileSwitch.transition(ConditionProfiles.json(profiles(a,b)),mobile,a).section
        val before=section.toString()
        val held=NetworkProfileSwitch.transition(section,home,a,safeMode=true)
        assertFalse(held.changed);assertNull(held.target);assertEquals(before,held.section.toString())
        assertEquals(b,NetworkProfileSwitch.transition(held.section,home,a).target)
    }
    @Test fun roundtripPreservesAllIndependentSettingsAndLegacyMode() {
        val a=config('a');val b=config('b',"direct",true)
        val original=profiles(a,b)
        assertEquals(original,ConditionProfiles.fromJson(ConditionProfiles.json(original)))
        assertEquals(a,NetworkProfileConfiguration.fromJson(a.json()))
        assertNotEquals(a.routing,b.routing);assertNotEquals(a.pool,b.pool);assertNotEquals(a.preferences,b.preferences)
    }
    @Test fun mobileWifiFallbackRoundtripSavesOutgoingEditsAndRestoresEveryField() {
        val initial=config('a');val wifi=config('b',"direct",true);val edited=config('c',"block",true)
        val enter=NetworkProfileSwitch.transition(ConditionProfiles.json(profiles(initial,wifi)),mobile,initial)
        assertEquals(initial,enter.target)
        val leave=NetworkProfileSwitch.transition(enter.section,home,edited)
        assertEquals(wifi,leave.target)
        assertEquals(edited,ConditionProfiles.fromJson(leave.section).first().configuration)
        val offline=NetworkProfileSwitch.transition(leave.section,ConditionEnvironment(),wifi)
        assertEquals(initial,offline.target)
        val back=NetworkProfileSwitch.transition(offline.section,mobile,initial)
        assertEquals(edited,back.target)
        assertEquals(edited.preferences,back.target!!.preferences)
    }
    @Test fun permissionRevocationUnknownSsidOtherWifiAndCaseMismatchUseFallback() {
        val a=config('a');val b=config('b',"direct")
        val section=NetworkProfileSwitch.transition(ConditionProfiles.json(profiles(a,b)),home,a).section
        for(network in listOf(home.copy(locationAllowed=false),home.copy(ssids=emptySet()),
            home.copy(ssids=setOf("<unknown ssid>")),home.copy(ssids=setOf("home")),home.copy(ssids=setOf("Other")))) {
            val result=NetworkProfileSwitch.transition(section,network,b)
            assertEquals(NetworkProfileSwitch.DEFAULT,result.section.getString("activeId"))
            assertEquals(a,result.target)
        }
    }
    @Test fun duplicateNetworkEventsDoNotOverwriteUnsavedCurrentEdits() {
        val a=config('a');val b=config('b');val edited=config('c')
        val entered=NetworkProfileSwitch.transition(ConditionProfiles.json(profiles(a,b)),mobile,a)
        val duplicate=NetworkProfileSwitch.transition(entered.section,mobile,edited)
        assertNull(duplicate.target);assertFalse(duplicate.changed)
        assertEquals(edited,ConditionProfiles.fromJson(NetworkProfileSwitch.transition(duplicate.section,home,edited).section).first().configuration)
    }
    @Test fun applyPreservesCatalogueImportsSecretsUnrelatedPreferencesAndSourceObjects() {
        val a=config('a');val b=config('b',"direct",true)
        val catalogue=JSONObject().put("servers",JSONArray().put("PRIVATE_SENTINEL"))
            .put("subscriptions",JSONArray()).put("edits",JSONObject().put("keep","PRIVATE_EDIT"))
            .put("autoServers",JSONArray().put("a".repeat(64)))
        val values=mapOf("profile.enc" to "PRIVATE_RAW", "connections.enc" to catalogue.toString(),
            "routing.enc" to a.routing,"preferences.enc" to JSONObject().put("theme","dark")
                .put("generalOptions",JSONObject().put("hideRecents",true)).put("selected","a".repeat(64)).put("tolerance",90).toString())
        val before=values.toMap();val result=NetworkProfileSwitch.apply(b,values)
        assertEquals(before,values);assertEquals("PRIVATE_RAW",result["profile.enc"])
        val next=JSONObject(result.getValue("connections.enc"))
        assertEquals(catalogue.getJSONArray("servers").toString(),next.getJSONArray("servers").toString())
        assertEquals(catalogue.getJSONObject("edits").toString(),next.getJSONObject("edits").toString())
        assertEquals("b".repeat(64),next.getJSONArray("autoServers").getString(0))
        val prefs=JSONObject(result.getValue("preferences.enc"))
        assertEquals("dark",prefs.getString("theme"));assertTrue(prefs.getJSONObject("generalOptions").getBoolean("hideRecents"))
        assertEquals(90,prefs.getInt("tolerance"));assertTrue(prefs.getBoolean("autoDetectBs"));assertTrue(prefs.getBoolean("automatic"))
        assertEquals(b.routing,result["routing.enc"])
    }
    @Test fun legacyProfilesAcquireConfigurationAndDefaultWithoutLosingAnyProfile() {
        val a=config('a');val original=profiles(null,null)
        val result=NetworkProfileSwitch.transition(ConditionProfiles.json(original),mobile,a)
        assertEquals(original.map {it.copy(configuration=a)},ConditionProfiles.fromJson(result.section))
        assertEquals(a,NetworkProfileConfiguration.fromJson(result.section.getJSONObject("fallback")))
        assertEquals(2,ConditionProfiles.fromJson(result.section).size)
        val empty=NetworkProfileSwitch.transition(ConditionProfiles.json(emptyList()),home,a)
        assertFalse(empty.changed);assertNull(empty.target)
    }
    @Test fun profileEditorPreservesActiveIdAndDefaultAndRejectsStaleConfiguration() {
        val a=config('a');val initial=NetworkProfileSwitch.transition(ConditionProfiles.json(profiles(a,config('b'))),mobile,a).section
        var document=JSONObject().put(ConditionProfiles.KEY,initial).put("keep",true).toString()
        val store=ConditionProfilesStore({document},{document=it});val opened=store.load()
        store.saveIfUnchanged(opened,opened.map {it.copy(name=it.name+"!")})
        val updated=JSONObject(document).getJSONObject(ConditionProfiles.KEY)
        assertEquals("mobile",updated.getString("activeId"));assertEquals(a,NetworkProfileConfiguration.fromJson(updated.getJSONObject("fallback")))
        assertTrue(JSONObject(document).getBoolean("keep"))
        assertThrows(IllegalArgumentException::class.java){store.saveIfUnchanged(opened,emptyList())}
    }
    @Test fun untrustedNestedSettingsCannotWriteCommandsOrInvalidRoutes() {
        val base=config('a')
        for(mutate in listOf<(JSONObject)->Unit>(
            {it.getJSONObject("preferences").put("wantedRunning",true)},
            {it.getJSONObject("preferences").put("automatic","true")},
            {it.getJSONObject("routing").put("defaultTarget","invalid")},
            {it.getJSONObject("preferences").put("bsDetectionSelection","invalid")},
            {it.put("pool",JSONArray().put("not-an-id"))})) {
            val json=base.json();mutate(json)
            assertThrows(Exception::class.java){NetworkProfileConfiguration.fromJson(json)}
        }
    }
}

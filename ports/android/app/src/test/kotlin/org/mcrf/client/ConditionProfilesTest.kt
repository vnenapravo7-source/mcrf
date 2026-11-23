package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConditionProfilesTest {
    private val wifi=ConditionProfile("wifi","Wi-Fi",ProfileCondition.WIFI)
    private val ssid=ConditionProfile("ssid","Дом",ProfileCondition.SSID,ssid="Home")
    private val cellular=ConditionProfile("cellular","Мобильная",ProfileCondition.CELLULAR)
    private val roaming=ConditionProfile("roaming","Роуминг",ProfileCondition.ROAMING)
    private val all=listOf(wifi,ssid,cellular,roaming)

    @Test fun staleEditorCannotOverwriteAnotherProfileChange() {
        var document="{}"
        val store=ConditionProfilesStore({document},{document=it})
        store.save(listOf(wifi))
        val opened=store.load()
        val newer=listOf(wifi,cellular)
        store.save(newer)
        try {store.saveIfUnchanged(opened,emptyList());fail("stale editor overwrote newer profiles")}
        catch(_:IllegalArgumentException){}
        assertEquals(newer,store.load())
        store.saveIfUnchanged(newer,listOf(cellular))
        assertEquals(listOf(cellular),store.load())
    }

    @Test fun matchesWifiCellularAndRoamingWithExplicitSpecificity() {
        assertEquals(wifi,ConditionProfiles.resolve(all,ConditionEnvironment(wifi=true),false))
        assertEquals(cellular,ConditionProfiles.resolve(all,ConditionEnvironment(cellular=true),false))
        assertEquals(roaming,ConditionProfiles.resolve(all,ConditionEnvironment(cellular=true,roaming=true),false))
        assertNull(ConditionProfiles.resolve(all,ConditionEnvironment(roaming=true),false))
        assertNull(ConditionProfiles.resolve(all,ConditionEnvironment(),false))
    }
    @Test fun ssidNeverUsesNamesWithoutCurrentLocationAccessOrWifi() {
        val network=ConditionEnvironment(wifi=true,ssids=setOf("Home"),locationAllowed=true)
        assertEquals(ssid,ConditionProfiles.resolve(all,network,false))
        assertEquals(wifi,ConditionProfiles.resolve(all,network.copy(locationAllowed=false),false))
        assertNull(ConditionProfiles.resolve(listOf(ssid),network.copy(wifi=false),false))
        assertNull(ConditionProfiles.resolve(listOf(ssid),network.copy(ssids=emptySet()),false))
        assertNull(ConditionProfiles.resolve(listOf(ssid),network.copy(ssids=setOf("home")),false))
        assertNull(ConditionProfiles.resolve(listOf(ssid),network.copy(ssids=setOf("<unknown ssid>")),false))
    }
    @Test fun priorityWinsThenSpecificityThenStableIdRegardlessOfOrder() {
        val network=ConditionEnvironment(true,true,true,setOf("Home"),true)
        val winner=cellular.copy(priority=10)
        assertEquals(winner,ConditionProfiles.resolve(all+winner,network,false))
        assertEquals(ssid,ConditionProfiles.resolve(all,network,false))
        assertEquals(roaming,ConditionProfiles.resolve(listOf(cellular,roaming,wifi),network,false))
        val earlier=wifi.copy(id="a")
        val later=wifi.copy(id="z")
        for(profiles in listOf(listOf(earlier,later),listOf(later,earlier)))
            assertEquals(earlier,ConditionProfiles.resolve(profiles,network,false))
        assertEquals(wifi,ConditionProfiles.resolve(listOf(wifi,ssid.copy(enabled=false)),network,false))
    }
    @Test fun encryptedSectionRoundtripPreservesUnrelatedSettingsAndDisabledProfiles() {
        var document=JSONObject().put("advancedOptions",JSONObject().put("generation","legacy")).toString()
        val store=ConditionProfilesStore({document},{document=it})
        assertEquals(emptyList<ConditionProfile>(),store.load())
        val profiles=all+wifi.copy(id="off",enabled=false,mode=ExperienceMode.EXPERT)
        store.save(profiles)
        assertEquals(profiles,ConditionProfilesStore({document},{document=it}).load())
        assertEquals("legacy",JSONObject(document).getJSONObject("advancedOptions").getString("generation"))
        store.save(emptyList())
        assertEquals("legacy",JSONObject(document).getJSONObject("advancedOptions").getString("generation"))
    }
    private fun rejects(profiles:List<ConditionProfile>) {
        try {ConditionProfiles.fromJson(ConditionProfiles.json(profiles));fail("invalid profile accepted")}
        catch(_:IllegalArgumentException){}
    }
    @Test fun validationRejectsAmbiguousOrOversizedProfilesBeforeWriting() {
        rejects(listOf(wifi,wifi))
        rejects(listOf(ssid.copy(ssid=null)))
        rejects(listOf(ssid.copy(ssid="<unknown ssid>")))
        rejects(listOf(ssid.copy(ssid="я".repeat(17))))
        rejects(listOf(wifi.copy(ssid="Home")))
        rejects(listOf(wifi.copy(priority=-1)))
        rejects(listOf(wifi.copy(priority=1001)))
        rejects(listOf(wifi.copy(name="\n")))
        rejects((0..32).map {wifi.copy(id="p$it")})
        var writes=0
        val store=ConditionProfilesStore({"{}"},{writes++})
        try {store.save(listOf(wifi,wifi));fail("invalid save accepted")}catch(_:IllegalArgumentException){}
        assertEquals(0,writes)
    }
    @Test fun untrustedRestoreCannotAddConnectionCommandsOrCoerceTypes() {
        for((key,value)in listOf("startVpn" to true,"priority" to "9","enabled" to "true","mode" to "future")) {
            val root=ConditionProfiles.json(listOf(wifi))
            root.getJSONArray("profiles").getJSONObject(0).put(key,value)
            try {ConditionProfiles.fromJson(root);fail("invalid $key accepted")}catch(_:IllegalArgumentException){}
        }
    }
}

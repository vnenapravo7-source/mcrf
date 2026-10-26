package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutingConnectionBindingTest {
    private val a="a".repeat(64);private val b="b".repeat(64);private val c="c".repeat(64)
    private fun server(host:String)=ServerImport.read("socks5://127.0.0.1:$host").single()
    private fun bs()=OpenFluxProfile.fromJson("fixture",JSONObject().put("type","openflux").put("name","UDP").put("negotiate",true).put("secret","synthetic-fixture-secret").put("transports",JSONArray().put(JSONObject().put("type","fluxfast").put("dial","127.0.0.1:32123"))))
    private fun state(group:String="vpn")=RouteRules.defaults().apply{RoutingGroups.ensure(this);RoutingGroups.select(this,group);put(RoutingConnectionBinding.KEY,JSONObject().put("enabled",true));getJSONArray("rules").put(JSONObject().put("id","test").put("name","test").put("text","example.com").put("mode","addresses").put("target","proxy").put(RouteServerPlan.KEY,a))}
    private fun policy(mode:String=BsAutoSelectionPolicy.FASTEST,last:String="")=RoutingConnectionBinding.Policy(mode,last,last,60,45,false)
    @Test fun disabledIsBytePreserving(){val s=state().apply{getJSONObject(RoutingConnectionBinding.KEY).put("enabled",false)};assertEquals(s.toString(),RoutingConnectionBinding.prepare(s,emptyList(),false,policy(),JSONObject(),1).toString())}
    @Test fun emptyVpnAssignmentMeansAllVpnAndNeverBs(){val vpn=listOf(server("1080"),server("1081"));val s=state();val before=s.toString();val runtime=RoutingConnectionBinding.prepare(s,vpn+bs(),false,policy(),JSONObject(),1);assertEquals(vpn.map{it.id}.toSet(),RouteServerPlan.activeIds(runtime).toSet());assertEquals(before,s.toString());assertEquals(a,s.getJSONArray("rules").getJSONObject(0).getString(RouteServerPlan.KEY));assertEquals(2,RouteServerPlan(runtime,vpn).entries.size)}
    @Test fun explicitAssignmentAndDeletedChoiceNeverExpandSilently(){val first=server("1080");val second=server("1081");val s=state();RoutingConnectionBinding.assign(s,false,listOf(second.id));val runtime=RoutingConnectionBinding.prepare(s,listOf(first,second),false,policy(),JSONObject(),1);assertEquals(listOf(second.id),RouteServerPlan.activeIds(runtime));assertThrows(IllegalArgumentException::class.java){RoutingConnectionBinding.prepare(s,listOf(first),false,policy(),JSONObject(),1)}}
    @Test fun bsFamilyIncludesOpenfluxButVpnDoesNot(){val entry=bs();assertTrue(RoutingConnectionBinding.eligible(entry,true));assertFalse(RoutingConnectionBinding.eligible(entry,false));val runtime=RoutingConnectionBinding.prepare(state("bs"),listOf(entry,server("1080")),false,policy(),JSONObject(),1);assertEquals(listOf(entry.id),RouteServerPlan.activeIds(runtime));assertEquals(1,RouteServerPlan(runtime,listOf(entry)).entries.size)}
    @Test fun autoGroupUsesNetworkFamilyAndNeverPerRuleType(){val vpn=server("1080");val bs=bs();val s=state("auto");s.getJSONArray("rules").getJSONObject(0).put("connectionType","bs");val before=s.toString();for(restricted in listOf(false,true)){val runtime=RoutingConnectionBinding.prepare(s,listOf(vpn,bs),restricted,policy(),JSONObject(),1);assertEquals(listOf(if(restricted)bs.id else vpn.id),RouteServerPlan.activeIds(runtime));assertFalse(runtime.getJSONArray("rules").getJSONObject(0).has("connectionType"))};assertEquals(before,s.toString())}
    @Test fun offUsesOnlyManualOrSingleAssignedServer(){val first=server("1080");val second=server("1081");val s=state();assertEquals(listOf(second.id),RouteServerPlan.activeIds(RoutingConnectionBinding.prepare(s,listOf(first,second),false,policy(BsAutoSelectionPolicy.OFF,second.id),JSONObject(),1)));assertThrows(IllegalArgumentException::class.java){RoutingConnectionBinding.prepare(s,listOf(first,second),false,policy(BsAutoSelectionPolicy.OFF),JSONObject(),1)}}
    @Test fun bindingSurvivesGroupChangesAndDoesNotDropInactiveRules(){val s=state();RoutingConnectionBinding.assign(s,true,listOf(b));RoutingGroups.select(s,"bs");assertEquals(listOf(b),RoutingConnectionBinding.selected(s,true));RoutingGroups.select(s,"vpn");assertEquals(a,s.getJSONArray("rules").getJSONObject(0).getString(RouteServerPlan.KEY));RoutingStore.validate(s);assertFalse(RoutingGroups.runtime(s).has(RoutingConnectionBinding.KEY))}
    @Test fun malformedBindingRejected(){val s=state();assertThrows(IllegalArgumentException::class.java){RoutingConnectionBinding.assign(s,false,listOf(a,a))};val invalid=state().put(RoutingConnectionBinding.KEY,"bad");assertThrows(IllegalArgumentException::class.java){RoutingStore.validate(invalid)}}
    @Test fun boundSelectionReloadTracksOnlySelectionPreferences(){assertTrue(BsAutoSelectionPolicy.KEY in RouteBindingSelection.preferenceKeys);assertTrue(AutoCheckIntervals.SELECTION in RouteBindingSelection.preferenceKeys);assertTrue(AutoSelectionDeviation.FAILURE_ONLY in RouteBindingSelection.preferenceKeys);assertFalse("wantedRunning" in RouteBindingSelection.preferenceKeys);assertFalse("lastPage" in RouteBindingSelection.preferenceKeys)}
    @Test fun automaticTypeSwitchNeedsBothCheckboxesAndAutoGroup(){for(group in listOf("vpn","bs","auto"))for(binding in listOf(false,true))for(detection in listOf(false,true)){val s=state(group);s.getJSONObject(RoutingConnectionBinding.KEY).put("enabled",binding);assertEquals(group=="auto"&&binding&&detection,RoutingConnectionBinding.networkDriven(s,detection))}}
    @Test fun providerRulesAndDnsUseAssignedFamilyEvenWithDirectDefault(){val vpn=server("1080");val s=state().put("defaultTarget","direct");val provider=JSONObject(s.getJSONArray("rules").getJSONObject(0).toString()).put("id","provider");s.put("geoData",GeoDataConfig.defaults().put("providerRules",JSONArray().put(provider)));val original=s.toString();val runtime=RoutingConnectionBinding.prepare(s,listOf(vpn),false,policy(),JSONObject(),1);assertEquals(listOf(vpn.id),RouteServerPlan.ids(runtime.getJSONObject("geoData").getJSONArray("providerRules").getJSONObject(0)));assertEquals(listOf(vpn.id),RouteServerPlan.ids(runtime.getJSONObject(RoutingConnectionBinding.DEFAULT)));assertEquals("direct",runtime.getString("defaultTarget"));assertEquals(original,s.toString())}
    @Test fun recentFailureOnlyToleranceAndFamilyStayIndependent(){
        fun choose(mode:String,old:Long,failure:Boolean=false,tolerance:Int=45):String? {
            return RouteBindingSelection.choose(listOf(a,b),a,mapOf(a to old,b to 20L),false,mode,b,tolerance,failure,emptyMap())
        }
        assertEquals(b,choose(BsAutoSelectionPolicy.RECENT,100))
        assertEquals(a,choose(BsAutoSelectionPolicy.RECENT,100,true))
        assertEquals(b,choose(BsAutoSelectionPolicy.RECENT,-1))
        assertEquals(a,choose(BsAutoSelectionPolicy.FASTEST,100,true))
        assertEquals(b,choose(BsAutoSelectionPolicy.FASTEST,-1,true))
        assertEquals(a,choose(BsAutoSelectionPolicy.FASTEST,50))
        assertEquals(b,choose(BsAutoSelectionPolicy.FASTEST,100))
        assertNull(choose(BsAutoSelectionPolicy.OFF,100))
    }
    @Test fun bsPriorityAndUnavailableCandidatesAreHonored(){assertEquals(a,RouteBindingSelection.choose(listOf(a,b,c),b,mapOf(a to 100L,b to 10L,c to -1L),true,BsAutoSelectionPolicy.FASTEST,"",45,false,mapOf(a to "csqtt",b to "openflux",c to "qwdtt")));assertNull(RouteBindingSelection.choose(listOf(a,b),a,emptyMap(),false,BsAutoSelectionPolicy.FASTEST,"",45,false,emptyMap()))}
}

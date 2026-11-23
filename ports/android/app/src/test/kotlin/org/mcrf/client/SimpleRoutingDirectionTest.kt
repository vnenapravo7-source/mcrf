package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SimpleRoutingDirectionTest {
    private fun base()=JSONObject("""{"outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1234}],"inbounds":[{"type":"tun","tag":"tun"}],"route":{"final":"proxy","rules":[]}}""")
    private fun source()=listOf(java.io.File("src/main/kotlin/org/mcrf/client/SimpleRoutingUi.kt"),
        java.io.File("app/src/main/kotlin/org/mcrf/client/SimpleRoutingUi.kt")).first {it.isFile}.readText()

    @Test fun directToVpnAndBackRoundTripsStoredBankAndChangesActualRuntimeOutput() {
        var stored=RouteRules.defaults().apply {ExperienceRouting.ensure(this);RoutingGroups.ensure(this)}
        val expert=ExperienceRouting.project(stored,ExperienceMode.EXPERT).toString()
        val packages=setOf("org.example.second","org.example.first")
        for(direct in listOf(true,false,true,false)) {
            val draft=ExperienceRouting.project(stored,ExperienceMode.SIMPLE)
            SimpleRoutingUi.applySelection(draft,packages,direct)
            RoutingGroups.synchronize(draft) // The same save preparation as RoutingStore.
            stored=JSONObject(ExperienceRouting.merge(stored,draft,ExperienceMode.SIMPLE).toString())
            RoutingStore.validate(stored)
            val reopened=ExperienceRouting.project(stored,ExperienceMode.SIMPLE)
            assertEquals(direct,reopened.getString("defaultTarget")!="direct")
            assertEquals(packages.sorted().toString(),reopened.getJSONArray(if(direct)"excludedApps"else"vpnApps")
                .let {a->(0 until a.length()).map {a.getString(it)}.toString()})
            assertEquals(0,reopened.getJSONArray(if(direct)"vpnApps"else"excludedApps").length())
            val config=RouteRules.apply(base(),reopened,true)
            val route=config.getJSONObject("route")
            val inbound=config.getJSONArray("inbounds").getJSONObject(0)
            assertEquals(if(direct)"proxy"else"direct",route.getString("final"))
            assertEquals(direct,inbound.has("exclude_package"))
            if(direct)assertEquals(reopened.getJSONArray("excludedApps").toString(),inbound.getJSONArray("exclude_package").toString())
            val rules=route.getJSONArray("rules")
            val appRules=(0 until rules.length()).map {rules.getJSONObject(it)}.filter {it.has("package_name")}
            if(direct)assertTrue(appRules.isEmpty())else {
                assertEquals(1,appRules.size)
                assertEquals("proxy",appRules.single().getString("outbound"))
                assertEquals(reopened.getJSONArray("vpnApps").toString(),appRules.single().getJSONArray("package_name").toString())
            }
            assertEquals(expert,ExperienceRouting.project(stored,ExperienceMode.EXPERT).toString())
        }
    }

    @Test fun emptySelectionKeepsAllTrafficInVpnAfterReopening() {
        var stored=RouteRules.defaults()
        ExperienceRouting.ensure(stored)
        for(direct in listOf(false,true)) {
            val draft=ExperienceRouting.project(stored,ExperienceMode.SIMPLE)
            SimpleRoutingUi.applySelection(draft,emptySet(),direct)
            stored=JSONObject(ExperienceRouting.merge(stored,draft,ExperienceMode.SIMPLE).toString())
            val reopened=ExperienceRouting.project(stored,ExperienceMode.SIMPLE)
            assertEquals("proxy",reopened.getString("defaultTarget"))
            assertEquals("proxy",RouteRules.apply(base(),reopened,true).getJSONObject("route").getString("final"))
        }
    }

    @Test fun invalidAppCannotPartiallyChangeTheSavedDirection() {
        val draft=RouteRules.defaults()
        SimpleRoutingUi.applySelection(draft,setOf("org.example.app"),true)
        val before=draft.toString()
        assertThrows(IllegalArgumentException::class.java){SimpleRoutingUi.applySelection(draft,setOf("not a package"),false)}
        assertEquals(before,draft.toString())
    }

    @Test fun asyncSuccessUpdatesThePillAndFailureRestoresTheCommittedDirection() {
        val ui=source()
        val callback=ui.substringAfter("RoutingUiMutation.submit(activity,store,list,{next->")
        val edit=callback.substringBefore("},{_,savedRevision->")
        assertTrue(edit.contains("ProfilePolicyRuntime.requireOrdinaryEditsAllowed(activity)"))
        assertTrue(ui.contains("ProfilePolicyRuntime.statusHint(activity)"))
        assertTrue(edit.indexOf("requireOrdinaryEditsAllowed")<edit.indexOf("applySelection(next,chosen,chosenDirect)"))
        val success=callback.substringAfter("},{_,savedRevision->").substringBefore("},{")
        assertTrue(success.contains("persistedDirect=chosenDirect"))
        assertTrue(success.contains("direct=chosenDirect;direction.setMode(direct,true)"))
        val failure=callback.substringAfter("},{_,savedRevision->").substringAfter("},{").substringBefore("},expectedRevision=revision)")
        assertTrue(failure.contains("direct=persistedDirect"))
        assertTrue(failure.contains("direction.setMode(direct,false)"))
        assertTrue(ui.contains("direct=pendingDirect;direction.setMode(direct,false)"))
        assertTrue(ui.contains("russian.isEnabled=!pending&&available.isNotEmpty()"))
    }

    @Test fun writesUseTheDisplayedSnapshotRevisionAndAdvanceOnlyOnSuccess() {
        val ui=source()
        assertTrue(ui.contains("val (initialRevision,state)=store.presentationSnapshot()"))
        assertTrue(ui.contains("var revision=initialRevision"))
        assertTrue(ui.contains("},expectedRevision=revision)"))
        val callback=ui.substringAfter("RoutingUiMutation.submit(activity,store,list,{next->")
        val success=callback.substringAfter("},{_,savedRevision->").substringBefore("},{")
        assertTrue(success.contains("revision=savedRevision"))
        val failure=callback.substringAfter("},{_,savedRevision->").substringAfter("},{").substringBefore("},expectedRevision=revision)")
        assertFalse(failure.contains("revision="))
    }
}

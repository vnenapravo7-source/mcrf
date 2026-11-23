package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Ownership and lifecycle regressions; measured geometry lives in EngineSettingsLayoutChecks. */
class EngineSettingsUiTest {
    private fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()

    @Test fun wgInstallationCallbackRequiresSuccessfulPersistedSelection() {
        var current:String?=null;var accepted=false;var installs=0
        val host=object:EngineUpdatePrimaryUi.Host {
            override fun selected()=current
            override fun validateFullSnapshot(choiceId:String)=emptyList<String>()
            override fun selectValidated(choiceId:String) {if(accepted)current=choiceId}
        }
        host.selectValidated("steer"){installs++}
        assertEquals(0,installs)
        accepted=true;host.selectValidated("steer"){installs++}
        assertEquals("steer",current);assertEquals(1,installs)
        val main=source("MainActivity").substringAfter("override fun selectValidated(choiceId:String,onSelected:").substringBefore("catch(e:ClientPrimarySelection.DnsModelConsentRequired)")
        assertTrue(main.indexOf("ClientPrimarySelection.select(")<main.indexOf("onSelected()"))
        assertTrue(main.indexOf("busy=false")<main.indexOf("onSelected()"))
    }
    @Test fun extrasAndScriptsUseSeparateTablesAndKeepUpdateCancellation() {
        val inventory=source("EngineUpdateInventoryUi")
        assertTrue(inventory.contains("body.addView(EngineUpdateOpenFluxScriptUi.view(this),space(12,0))"))
        assertFalse(inventory.contains("EngineUpdateOpenFluxScriptUi.appendRows(this,extras)"))
        assertTrue(inventory.contains("iconButton(\"info\",\"О движке ${'$'}label\",20)"))
        assertTrue(inventory.contains("additionalHelp(kind).takeIf {it.isNotBlank()}"))
        val details=inventory.substringAfter("val details=LinearLayout").substringBefore("EngineTableUi.row")
        assertTrue(details.contains("gravity=Gravity.CENTER_VERTICAL;isBaselineAligned=false"))
        assertTrue(inventory.contains("EngineUpdateScreenPolicy.additionalHelp(kind)"))
        assertFalse(inventory.contains("background=Loft.glass"))
        val scripts=source("EngineUpdateOpenFluxScriptUi")
        assertTrue(scripts.contains("EngineTableUi.row(this,table,body,actions)"))
        assertFalse(scripts.contains("body.background="))
        assertTrue(scripts.contains("handler.removeCallbacks(refresh);owned?.cancel();owned=null"))
        assertFalse(source("EngineUpdateUi").contains("EngineUpdateOpenFluxScriptUi.view"))
        val policy=source("EngineUpdateScreenPolicyUi")
        for(kind in listOf("bs","wdtt","tgws","warpscout","xray","tls-core","protocols"))assertTrue(policy.contains("\"$kind\"->"))
    }
    @Test fun engineHelpDescribesConcreteFeaturesAndUnknownKindsHaveNoInfoButton() {
        assertEquals("",EngineUpdateScreenPolicy.additionalHelp("unknown-addon"))
        for(kind in listOf("bs","wdtt","tgws","warpscout","xray","tls-core","protocols",WgAddonPolicy.KIND)) {
            val help=EngineUpdateScreenPolicy.additionalHelp(kind)
            assertTrue(kind,help.isNotBlank())
            assertFalse(kind,help.contains("для совместимых подключений"))
        }
        assertTrue(EngineUpdateScreenPolicy.additionalHelp("protocols").contains("TUIC"))
        assertTrue(EngineUpdateScreenPolicy.additionalHelp("tls-core").contains("SNI"))
    }
    @Test fun vkKeepsItsHostAndDoesNotFadeOrRerenderAfterLoading() {
        val main=source("MainActivity")
        val vk=main.substringAfter("ClientSettingsNavigation.VK -> {").substringBefore("ClientSettingsNavigation.UPDATES -> {")
        assertFalse(vk.contains("Открываем VK"))
        assertTrue(vk.contains("page!==owner||deployUi!==controller"))
        assertFalse(vk.contains("host.removeAllViews()"))
        assertFalse(vk.contains(";render()"))
        assertTrue(main.contains("animatePage && current!=ClientSettingsNavigation.VK"))
    }
    @Test fun editorSectionAccentsDifferAndStayStableInBothThemes() {
        for(dark in listOf(false,true)) {
            val colors=(0..3).map {ServerEditorLayout.sectionTint(it,dark)}
            assertEquals(4,colors.distinct().size)
            assertEquals(colors[0],ServerEditorLayout.sectionTint(4,dark))
            assertTrue(colors.all {it ushr 24==255})
        }
    }
}

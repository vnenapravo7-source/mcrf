package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guards shared ownership; on-device layout/font checks remain separate acceptance. */
class UiSliceAContractTest {
    private fun file(name:String)=listOf(File("app/src/main/kotlin/org/mcrf/client/$name.kt"),File("src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}
    @Test fun removedFeatureHasNoSourceOrSettingsEntry() {
        for(name in listOf("DnsTamperPolicy","DnsTamperRunner","DnsTamperUi","DnsTamperWire"))assertFalse(file("DnsConfig").resolveSibling("$name.kt").exists())
        for(name in listOf("MainActivity","DnsConfig","AdvancedDnsConfig","PacketManagedDnsPlan","TransportComparison"))assertFalse(name,file(name).readText().contains("DnsTamper"))
        assertFalse(file("SettingsSearchIndex").readText().contains("защита dns"))
    }
    @Test fun updatesAreAvailableInBothModesAndUseDedicatedCards() {
        val entry=file("MainActivity").readText().substringAfter("ClientSettingsNavigation.UPDATES -> {").substringBefore("private fun settingsExperience")
        assertFalse(entry.contains("ExperienceMode.SIMPLE)return"))
        assertTrue(entry.contains("EngineUpdateAppUi.view"));assertTrue(entry.contains("EngineUpdateInventoryUi.view"))
        val card=file("EngineUpdateAppUi").readText()
        assertTrue(card.contains("AppUpdates.checkInline"));assertTrue(card.contains("progress.visibility"));assertTrue(card.contains("if(error)Loft.ERROR"))
    }
}

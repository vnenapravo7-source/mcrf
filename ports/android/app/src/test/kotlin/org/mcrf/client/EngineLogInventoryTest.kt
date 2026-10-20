package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class EngineLogInventoryTest {
    @Test fun versionSnapshotExpiresAndExportMakesItsAgeExplicit() {
        assertTrue(EngineLogInventory.refreshDue(1,null));assertFalse(EngineLogInventory.refreshDue(60000,1))
        assertTrue(EngineLogInventory.refreshDue(60001,1));assertTrue(EngineLogInventory.refreshDue(0,100))
        assertTrue(EngineLogInventory.snapshotAge(12000,1000).contains("11 с назад"))
        assertTrue(EngineLogInventory.snapshotAge(1,null).contains("ещё не получен"))
    }
    @Test fun separatesLegacyCsqttNewGenerationAndAggregateVersionSequences() {
        val text=EngineLogInventory.format(listOf(EngineLogInventory.Item("csqtt",5),EngineLogInventory.Item("csqtt25",1),EngineLogInventory.Item("wdtt",12,true)))
        assertTrue(text.contains("CSQTT: versionCode=5"));assertTrue(text.contains("CSQTT 2.5: versionCode=1"))
        assertTrue(text.contains("WDTT: versionCode=12 · общий старый пакет"));assertTrue(text.contains("не проверка актуальности"))
    }
    @Test fun absentIncompatibleAndUnknownInputsCannotClaimCurrentOrLeakValues() {
        val text=EngineLogInventory.format(listOf(EngineLogInventory.Item("csqtt25",null),EngineLogInventory.Item("wdtt",null,unavailable=true),EngineLogInventory.Item("private-value",123)))
        assertTrue(text.contains("CSQTT 2.5: не установлен"));assertTrue(text.contains("WDTT: сведения недоступны/пакет несовместим"));assertFalse(text.contains("private-value"))
        assertEquals("Движки: сведения недоступны.",EngineLogInventory.format(emptyList()))
    }
}

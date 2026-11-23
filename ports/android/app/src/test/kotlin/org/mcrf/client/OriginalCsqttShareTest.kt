package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OriginalCsqttShareTest {
    @Test fun originalCsqttLinkSurvivesLocalEditsWhileSettingsExportKeepsEdits() {
        val source="csqtt://connect?v=2&host=fixture.invalid&peer=56005&password=fixture-only&hashes=abcdefghijklmnop&workers=18"
        val catalogue=ConnectionCatalogue("")
        catalogue.add(source)
        val entry=catalogue.entries().single()
        catalogue.edit(entry.id,ConnectionEdits.snapshot(entry).put("name","Edited fixture"))
        assertEquals(source,ServerSharePayload.current(catalogue,entry.id,true).source)
        assertNotEquals(source,ServerSharePayload.current(catalogue,entry.id,false).source)
        assertEquals("Edited fixture",ServerSharePayload.current(catalogue,entry.id,true).name)
    }
}

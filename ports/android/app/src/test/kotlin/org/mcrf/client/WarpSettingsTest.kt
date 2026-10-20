package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WarpSettingsTest {
    @Test fun searchesAlwaysExcludeDmeAndLegacyAnyBecomesForeign() {
        val options=WarpSettings(country="any",excludeDme=false).options()
        assertEquals("foreign",options.country);assertTrue(options.excludeDme)
        assertTrue(WarpSettings(country="RU",excludeDme=false).options().excludeDme)
    }
    @Test fun defaultsAndRoundTrip() {
        val defaults = WarpSettings.from(JSONObject())
        assertEquals("auto", defaults.protocol); assertEquals("foreign", defaults.country); assertFalse(defaults.termsAccepted)
        val custom = WarpSettings("wg", "NL", false, true, true)
        assertEquals(custom, WarpSettings.from(custom.json()))
        assertEquals("NL", custom.options().country)
    }
    @Test fun invalidSettingsFailInsteadOfCoercion() {
        for(value in listOf(JSONObject().put("country", "ZZ"), JSONObject().put("protocol", "masque"), JSONObject().put("extended", "true"), JSONObject().put("country", 1), JSONObject().put("unknown", true))) {
            assertThrows(Exception::class.java) { WarpSettings.validate(value) }
        }
    }
}

package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SettingsSectionFailureTest {
    @Test fun failedSectionHasFallbackAndOtherSectionsStillLoad() {
        val sections=listOf(
            SettingsSectionUi.read<String>({throw IOException("PRIVATE_VALUE")},{"inline error"}),
            SettingsSectionUi.read({"DNS settings"},{"inline error"}))
        assertEquals(listOf("inline error","DNS settings"),sections)
        assertFalse(sections.toString().contains("PRIVATE_VALUE"))
    }
    @Test fun unavailableNativeDependencyAlsoBecomesInlineError() {
        assertEquals("inline error",SettingsSectionUi.read<String>({throw UnsatisfiedLinkError("native unavailable")},{"inline error"}))
    }
    @Test fun failureDoesNotResetOrReplaceSavedDataAndRetryCanSucceed() {
        val saved=JSONObject().put("geoData",JSONObject().put("source","broken"))
        val before=saved.toString()
        assertEquals("inline error",SettingsSectionUi.read({GeoDataEditor.editorSettings(saved);"ok"},{"inline error"}))
        assertEquals(before,saved.toString())
        assertEquals("ok",SettingsSectionUi.read({GeoDataEditor.editorSettings(JSONObject());"ok"},{"inline error"}))
    }
    @Test fun partialLegacyGeoSettingsUseDetachedDefaultsWithoutChangingSavedSource() {
        val selectors=JSONArray().put(JSONObject().put("kind","geosite").put("code","ru").put("target","direct"))
        val saved=JSONObject().put("geoData",JSONObject().put("selectors",selectors))
        val before=saved.toString();val edited=GeoDataEditor.editorSettings(saved)
        assertEquals("Loyalsoldier",edited.getString("source"));assertEquals(1,edited.getJSONArray("selectors").length())
        assertTrue(edited.getString("geoipUrl").startsWith("https://"))
        edited.getJSONArray("selectors").getJSONObject(0).put("code","cn")
        assertEquals(before,saved.toString())
    }
    @Test fun malformedGeoObjectOrSelectorsAreContainedRatherThanSilentlyReset() {
        for(saved in listOf(JSONObject().put("geoData","bad"),JSONObject().put("geoData",JSONObject().put("selectors","bad")))) {
            val raw=saved.toString()
            assertEquals("inline error",SettingsSectionUi.read({GeoDataEditor.editorSettings(saved);"ok"},{"inline error"}))
            assertEquals(raw,saved.toString())
        }
    }
}

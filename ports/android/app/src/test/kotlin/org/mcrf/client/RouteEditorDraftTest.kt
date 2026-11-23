package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouteEditorDraftTest {
    @Test fun largePreviewNeverReplacesSavedAddresses(){
        val source=(0 until 12000).joinToString("\n"){"10.${it/256}.${it%256}.0/24"}
        val draft=RouteEditorDraft.prepare(JSONObject().put("text",source))
        assertTrue(draft.large);assertEquals(12000,draft.lines);assertEquals(5,draft.preview.lines().size)
        assertEquals(source,draft.content(null,emptySet()));assertTrue(draft.content(null,emptySet()).endsWith("10.46.223.0/24"))
    }
    @Test fun appSelectionsRemainSeparateFromAddressPreview(){
        val draft=RouteEditorDraft.prepare(JSONObject().put("text","example.test\napp:org.example.app"))
        assertEquals(setOf("org.example.app"),draft.apps);assertEquals("example.test",draft.addresses)
        assertEquals("example.test\napp:org.example.app",draft.content(null,draft.apps))
    }
    @Test fun explicitAddressEditIsRespected(){val draft=RouteEditorDraft.prepare(JSONObject().put("text","old.test"));assertEquals("new.test",draft.content("new.test",emptySet()))}
    @Test fun preparingDoesNotMutateRule(){val rule=JSONObject().put("text","example.test").put("invert",true);val old=rule.toString();RouteEditorDraft.prepare(rule);assertEquals(old,rule.toString())}
    @Test fun russiaDirectPresetReplacesConditionsOnlyInPrivateDraft(){
        val original=JSONObject().put("id","draft-id").put("name","Personal").put("text","app:org.example.app")
            .put("mode","apps").put("target","block").put("enabled",false).put("geoPreset","geosite:google")
            .put("nativeMatch",JSONObject().put("domain",org.json.JSONArray().put("old.test")))
            .put("presetIds",org.json.JSONArray().put("0123456789abcdef")).put("opaque",JSONObject().put("keep",true))
        val before=original.toString();val rule=RouteRules.russiaDirectDraft(original,"ru\n192.0.2.0/24")
        assertEquals(before,original.toString());assertEquals("draft-id",rule.getString("id"));assertTrue(rule.getJSONObject("opaque").getBoolean("keep"))
        assertEquals("РФ напрямую",rule.getString("name"));assertEquals("addresses",rule.getString("mode"));assertEquals("direct",rule.getString("target"))
        assertFalse(rule.getBoolean("invert"));assertTrue(rule.getBoolean("enabled"));assertEquals("ru-direct",rule.getString("builtinPreset"))
        assertFalse(rule.has("nativeMatch"));assertFalse(rule.has("geoPreset"));assertFalse(rule.has("presetIds"))
        val match=RouteRules.matches(rule).getJSONObject(0)
        assertFalse(match.optBoolean("invert"));assertEquals("or",match.getString("mode"));assertEquals(2,match.getJSONArray("rules").length())
    }
    @Test fun migratesCountryRuleInEachSavedGroupAndExperienceBank() {
        fun legacy() = RouteRules.defaults().put("defaultTarget", "direct").apply {
            getJSONArray("rules").put(RouteRules.outsideRussia("ru").put("enabled", false))
        }
        val root = legacy()
        RoutingGroups.ensure(root)
        RoutingGroups.select(root, "bs")
        root.put("defaultTarget", "block")
        ExperienceRouting.ensure(root)
        val simple = legacy()
        root.getJSONObject(ExperienceRouting.KEY).put("simple", simple)

        assertTrue(LegacyRoutingMigration.replaceCountryRules(root))
        assertEquals("proxy", root.getString("defaultTarget"))
        assertEquals("proxy", simple.getString("defaultTarget"))
        assertEquals("direct", simple.getJSONArray("rules").getJSONObject(0).getString("target"))
        assertTrue(RoutingGroups.select(root, "vpn"))
        val rule = root.getJSONArray("rules").getJSONObject(0)
        assertEquals("proxy", root.getString("defaultTarget"))
        assertEquals("outside-ru", rule.getString("id"))
        assertEquals("РФ напрямую", rule.getString("name"))
        assertFalse(rule.getBoolean("enabled"))
        assertFalse(rule.getBoolean("invert"))
        assertFalse(LegacyRoutingMigration.replaceCountryRules(root))
    }

    @Test fun emptyDirectFallbackMovesToVpnOnceAndCanBeChangedBack(){
        val state = RouteRules.defaults().put("defaultTarget", "direct")
        assertTrue(LegacyRoutingMigration.replaceCountryRules(state))
        assertEquals("proxy", state.getString("defaultTarget"))
        assertFalse(RoutingGroups.runtime(state).has(LegacyRoutingMigration.VPN_FALLBACK_KEY))
        state.put("defaultTarget", "direct")
        assertFalse(LegacyRoutingMigration.replaceCountryRules(state))
        assertEquals("direct", state.getString("defaultTarget"))
    }

    @Test fun emptyCountryPresetCannotCreateCatchall(){
        assertThrows(IllegalArgumentException::class.java){RouteRules.russiaDirectDraft(JSONObject().put("id","draft")," ")}
    }
}

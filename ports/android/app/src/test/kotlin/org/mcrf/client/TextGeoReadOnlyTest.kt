package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TextGeoReadOnlyTest {
    private fun rule(text:String)=JSONObject().put("id","readonly-geo").put("name","Geo")
        .put("text",text).put("mode","addresses").put("target","proxy").put("enabled",true)
    private fun state(rule:JSONObject)=RouteRules.defaults().put("rules",JSONArray().put(rule))
    private fun assertLegacyText(rule:JSONObject) {
        val before=rule.toString()
        assertEquals(legacyEditableText(rule),TextGeoRules.editableText(rule))
        assertEquals(before,rule.toString())
    }

    @Test fun textSourcePresenceAndRemovalMatchDetachedEditor() {
        val top=rule("top.example.test")
        assertLegacyText(top)
        assertLegacyText(JSONObject())
        val missing=JSONObject(top.toString()).put("textGeoSource",JSONObject())
        assertLegacyText(missing);assertEquals("",TextGeoRules.editableText(missing))
        val compiled=JSONObject(top.toString()).put("textGeoSource",JSONObject().put("text","geosite:ru"))
        assertLegacyText(compiled);assertEquals("geosite:ru",TextGeoRules.editableText(compiled))
    }

    @Test fun nullNumbersContainersAndUnexpectedSourceTypesKeepRuntimeCoercion() {
        val values=listOf<Any>(JSONObject.NULL,true,7,1.0,1.25,JSONArray().put("example.test"),
            JSONObject().put("text","nested"),mapOf("text" to "mapped.example.test"))
        for(value in values) {
            assertLegacyText(JSONObject().put("text",value))
            assertLegacyText(rule("top.example.test").put("textGeoSource",JSONObject().put("text",value)))
            assertLegacyText(rule("top.example.test").put("textGeoSource",value))
        }
        // Missing differs from explicit JSON null on some org.json runtimes.
        assertLegacyText(rule("top.example.test").put("textGeoSource",JSONObject.NULL))
    }

    @Test fun plainRuleCompileKeepsIdentityAndUnrelatedNestedDataWithoutSerializingIt() {
        val opaque=object:JSONObject(){override fun toString():String=error("unrelated data serialized")}
        val plain=rule("example.test").put("opaque",opaque)
        val data=state(plain)
        assertEquals("example.test",TextGeoRules.editableText(plain))
        TextGeoRules.compile(data)
        assertSame(plain,data.getJSONArray("rules").getJSONObject(0))
        assertSame(opaque,plain.getJSONObject("opaque"))
        assertFalse(data.has("geoData"))
    }

    @Test fun serializingRuleAndSourceSubclassesRetainLegacyText() {
        val source=object:JSONObject(){override fun toString()="""{"text":"geoip:youtube"}"""}
        val original=rule("top.example.test").put("textGeoSource",source)
        assertLegacyText(original)
        // Nested JSONObject serialization may use its fields rather than its toString override.
        // Preserve the frozen algorithm's runtime behavior, not an assumed materialization.
        val fieldSource=object:JSONObject(){override fun toString()="""{"text":"geoip:youtube"}"""}
            .apply {put("text","geoip:youtube")}
        val withField=rule("top.example.test").put("textGeoSource",fieldSource)
        assertLegacyText(withField)
        assertEquals("geoip:youtube",legacyEditableText(withField))
        assertEquals("geoip:youtube",TextGeoRules.editableText(withField))
        val customRule=object:JSONObject(){override fun toString()="""{"text":"geosite:ru"}"""}
        assertLegacyText(customRule)
        assertEquals("geosite:ru",TextGeoRules.editableText(customRule))
    }

    @Test fun serializedSourceSubclassCannotBypassGeoValidation() {
        val source=object:JSONObject(){override fun toString()="""{"text":"geoip:youtube"}"""}
            .apply {put("text","geoip:youtube")}
        val invalid=state(rule("example.test").put("textGeoSource",source))
        val before=invalid.toString()
        val legacyText=legacyEditableText(invalid.getJSONArray("rules").getJSONObject(0))
        assertEquals("geoip:youtube",legacyText)
        assertThrows(IllegalArgumentException::class.java){RouteEditorDraft.validateGeo(legacyText,invalid)}
        assertThrows(IllegalArgumentException::class.java){RoutingStore.validate(invalid)}
        assertEquals(before,invalid.toString())
    }

    @Test fun publicEditableStillReturnsDetachedNestedObjects() {
        val original=rule("example.test").put("opaque",JSONObject().put("items",JSONArray().put("keep")))
        val edited=TextGeoRules.editable(original)
        edited.getJSONObject("opaque").getJSONArray("items").put(0,"changed")
        assertEquals("keep",original.getJSONObject("opaque").getJSONArray("items").getString(0))
    }

    @Test fun geoCompilationMatchesOriginalAlgorithmIncludingSourceAndSelectors() {
        val current=state(rule("geosite:ru\ngeoip:ru\nexample.test"))
        val expected=JSONObject(current.toString())
        legacyCompile(expected);TextGeoRules.compile(current)
        assertTrue(ChangeHistoryStore.equivalent(expected.toString(),current.toString()))
        val compiled=current.getJSONArray("rules").getJSONObject(0)
        assertLegacyText(compiled)
        val once=current.toString();TextGeoRules.compile(current)
        assertTrue(ChangeHistoryStore.equivalent(once,current.toString()))
    }

    @Test fun nestedInactiveGroupsStillCompileWithoutTouchingPlainRules() {
        val nested=state(rule("geoip:ru"))
        val inactive=state(rule("geosite:youtube")).put(RoutingGroups.KEY,banks(nested))
        val plain=rule("example.test")
        val current=state(plain).put(RoutingGroups.KEY,banks(inactive))
        val expected=JSONObject(current.toString())
        legacyCompile(expected);TextGeoRules.compile(current)
        assertTrue(ChangeHistoryStore.equivalent(expected.toString(),current.toString()))
        assertSame(plain,current.getJSONArray("rules").getJSONObject(0))
        assertTrue(nested.getJSONArray("rules").getJSONObject(0).has("nativeMatch"))
    }

    @Test fun validationAndCompilerGuardsStillRejectWithoutMutatingInput() {
        for(compiled in listOf(false,true)) {
            val invalid=state(rule("geoip:youtube"))
            if(compiled)TextGeoRules.compile(invalid)
            val before=invalid.toString()
            assertThrows(IllegalArgumentException::class.java){RoutingStore.validate(invalid)}
            assertEquals(before,invalid.toString())
        }
        for(key in listOf("nativeMatch","dns")) {
            val guarded=state(rule("geosite:ru").put(key,JSONObject()))
            val snapshot=guarded.toString()
            assertThrows(IllegalArgumentException::class.java){TextGeoRules.compile(guarded)}
            assertEquals(snapshot,guarded.toString())
        }
        val bank=RouteRules.defaults().apply {RoutingGroups.ensure(this);ExperienceRouting.ensure(this)}
        RoutingStore.validate(bank)
        val broken=JSONObject(bank.toString()).apply {
            getJSONObject(RoutingGroups.KEY).getJSONArray("groups").getJSONObject(1)
                .getJSONObject("data").put("defaultTarget","invalid")
        }
        assertThrows(IllegalArgumentException::class.java){RoutingStore.validate(broken)}
    }

    private fun banks(inactive:JSONObject)=JSONObject().put("active","active").put("groups",JSONArray()
        .put(JSONObject().put("id","active").put("data",JSONObject()))
        .put(JSONObject().put("id","inactive").put("data",inactive)))

    /** Frozen pre-optimization clone/source/text extraction, including this runtime's serialization. */
    private fun legacyEditableText(rule:JSONObject):String {
        val result=JSONObject(rule.toString())
        val source=result.optJSONObject("textGeoSource")?:return result.optString("text")
        result.remove("textGeoSource");result.remove("nativeMatch")
        for(key in listOf("text","mode","invert","presetIds")) {
            result.remove(key)
            if(source.has(key))result.put(key,source.get(key))
        }
        return result.optString("text")
    }

    /** Frozen pre-optimization algorithm, kept here as the equivalence oracle. */
    private fun legacyCompile(state:JSONObject) {
        val rules=state.getJSONArray("rules")
        repeat(rules.length()) {index->
            val draft=TextGeoRules.editable(rules.getJSONObject(index))
            if(!TextGeoRules.hasGeo(draft))return@repeat
            require(!draft.has("nativeMatch"));require(!draft.has("dns"))
            val geo=GeoDataConfig.value(state);val selectors=geo.getJSONArray("selectors")
            val groups=RouteRules.parse(draft.getString("text"));val tags=JSONArray()
            for(kind in listOf("geoip","geosite"))for(raw in groups.getValue(kind)) {
                val code=GeoDataConfig.categoryCode(geo,kind,raw)
                var slot=(0 until selectors.length()).firstOrNull {i->selectors.getJSONObject(i).let {
                    it.optString("kind")==kind&&it.optString("code").equals(code,true)&&it.optBoolean("managedRule")
                }}
                if(slot==null) {
                    require(selectors.length()<64);slot=selectors.length()
                    selectors.put(JSONObject().put("kind",kind).put("code",code).put("target","proxy").put("managedRule",true))
                }
                tags.put("mcrf-geo-$slot")
            }
            val matches=RouteRules.matchesWithGeo(draft,JSONObject().put("rule_set",tags))
            val match=if(matches.length()==1)matches.getJSONObject(0)else JSONObject()
                .put("type","logical").put("mode","or").put("rules",matches)
            val source=JSONObject()
            for(key in listOf("text","mode","invert","presetIds"))if(draft.has(key))source.put(key,draft.get(key))
            draft.put("textGeoSource",source).put("text","").put("mode","addresses").put("nativeMatch",match)
            draft.remove("invert");draft.remove("presetIds");rules.put(index,draft);state.put("geoData",geo)
        }
        state.optJSONObject(RoutingGroups.KEY)?.let {banks->val groups=banks.getJSONArray("groups")
            repeat(groups.length()) {i->val group=groups.getJSONObject(i)
                if(group.getString("id")!=banks.getString("active"))legacyCompile(group.getJSONObject("data"))
            }
        }
    }
}

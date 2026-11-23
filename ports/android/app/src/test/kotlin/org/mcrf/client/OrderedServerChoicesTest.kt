package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrderedServerChoicesTest {
    @Test fun arrowsChangePriorityWithoutRemovingOtherSelections() {
        val draft=OrderedServerChoices(listOf("b","a","c","b"))
        draft.move("c",-1);assertEquals(listOf("b","c","a"),draft.ids())
        draft.move("b",1);assertEquals(listOf("c","b","a"),draft.ids())
        draft.move("missing",1);draft.move("c",-1);draft.move("a",1)
        assertEquals(listOf("c","b","a"),draft.ids())
    }
    @Test fun cancelDoesNotMutateTheCallerAndRecheckingAppendsLast() {
        val source=mutableListOf("a","b");val draft=OrderedServerChoices(source)
        draft.select("a",false);draft.select("a",true);draft.select("a",true)
        assertEquals(listOf("b","a"),draft.ids());assertEquals(listOf("a","b"),source)
        val captured=draft.ids();draft.clear();assertEquals(listOf("b","a"),captured)
    }
    @Test fun removedRecentStrategyHasACompatibleEditingDefault() {
        assertFalse(RouteEditorAuto.pickerModes.contains(BsAutoSelectionPolicy.RECENT))
        assertEquals(BsAutoSelectionPolicy.FASTEST,RouteEditorAuto.editableMode(BsAutoSelectionPolicy.RECENT))
        assertEquals(AutoSelection.SEQUENTIAL,RouteEditorAuto.editableMode(AutoSelection.SEQUENTIAL))
    }
    @Test fun savedPriorityReachesNativeSelectorAndFailoverWorker() {
        val a=ServerImport.read("socks5://127.0.0.1:1080").single()
        val b=ServerImport.read("socks5://127.0.0.1:1081").single()
        val c=ServerImport.read("socks5://127.0.0.1:1082").single()
        val draft=OrderedServerChoices(listOf(a.id,b.id,c.id));draft.move(c.id,-1);draft.move(c.id,-1)
        val rule=JSONObject().put("id","priority").put("name","Priority").put("text","example.test")
            .put("mode","addresses").put("target","proxy").put("enabled",true)
            .put(RouteServerPlan.POOL,JSONArray(draft.ids())).put(RouteServerPlan.AUTO,JSONObject()
                .put("mode",AutoSelection.SEQUENTIAL).put("interval",60).put("tolerance",50).put("preferred",c.id))
        val state=RouteRules.defaults().put("rules",JSONArray().put(rule))
        val prepared=RouteEditorAuto.prepare(state,listOf(a,b,c),JSONObject(),0)
        val tag=RouteServerPlan.outbound(rule,"")
        val pool=prepared.getJSONObject(RoutingConnectionBinding.RUNTIME).getJSONObject("pools").getJSONObject(tag)
        assertEquals(draft.ids(),pool.getJSONArray("ids").let {x->(0 until x.length()).map {x.getString(it)}})
        val config=RouteServerPlan(prepared,listOf(a,b,c)).append(ServerImport.build(listOf(a),a.id,false,60,50,"Cloudflare"))
        val native=config.getJSONArray("outbounds").let {x->(0 until x.length()).map {x.getJSONObject(it)}.single {it.getString("tag")==tag}}
        assertEquals(RouteServerPlan.tag(c.id),native.getString("default"))
        val switches=mutableListOf<String>()
        RouteBindingSelection(prepared,listOf(a,b,c)).tick(1,{id->if(id==RouteServerPlan.tag(c.id))-1 else 20},{_,next->switches.add(next)},{false})
        assertEquals(listOf(RouteServerPlan.tag(a.id)),switches)
    }
}

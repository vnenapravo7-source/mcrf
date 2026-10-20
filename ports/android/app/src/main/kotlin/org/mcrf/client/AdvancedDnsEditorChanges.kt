package org.mcrf.client

import org.json.JSONObject

/** No-edit reads retain the entire original object; real edits use the existing canonical resolver policy. */
object AdvancedDnsEditorChanges {
    private fun fields(value:JSONObject)=listOf("provider","protocol","custom").map {value.optString(it,"")}
    class VisibleDraft(initial:JSONObject) {
        private var emitted=fields(initial)
        fun take(value:JSONObject):Boolean {val next=fields(value);if(next==emitted)return false;emitted=next;return true}
    }
    fun value(original:JSONObject,visible:JSONObject,policyEdited:Boolean=false):JSONObject {
        if(!policyEdited&&fields(original)==fields(visible))return JSONObject(original.toString())
        return JSONObject().put("provider",visible.getString("provider")).put("protocol",visible.getString("protocol")).put("custom",visible.optString("custom",""))
    }
    fun resolver(original:JSONObject,visible:JSONObject):JSONObject? {
        if(fields(original)==fields(visible))return null
        val result=value(original,visible)
        DnsConfig.build(result,"test","bootstrap")
        return result
    }
}

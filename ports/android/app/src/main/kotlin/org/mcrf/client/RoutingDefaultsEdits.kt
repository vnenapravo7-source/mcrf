package org.mcrf.client

import org.json.JSONObject

/** A quickly reopened form must change only keys edited in that form, preserving queued previous edits. */
object RoutingDefaultsEdits {
    /** Android JSONObject has no similar(): compare objects without key-order sensitivity, arrays in order. */
    fun equivalent(left:Any?,right:Any?):Boolean {
        if(left==null||left===JSONObject.NULL)return right==null||right===JSONObject.NULL
        if(right==null||right===JSONObject.NULL)return false
        if(left is JSONObject&&right is JSONObject){val keys=left.keys().asSequence().toSet();return keys==right.keys().asSequence().toSet()&&keys.all {equivalent(left.opt(it),right.opt(it))}}
        if(left is org.json.JSONArray&&right is org.json.JSONArray)return left.length()==right.length()&&(0 until left.length()).all {equivalent(left.opt(it),right.opt(it))}
        if(left is Number&&right is Number)return runCatching {java.math.BigDecimal(left.toString()).compareTo(java.math.BigDecimal(right.toString()))==0}.getOrDefault(false)
        return left==right
    }
    fun merge(current:JSONObject,opened:JSONObject,edited:JSONObject):JSONObject {
        val result=JSONObject(current.toString())
        (opened.keys().asSequence().toSet()+edited.keys().asSequence().toSet()).forEach {key ->
            val before=opened.opt(key);val after=edited.opt(key)
            if(before?.toString()==after?.toString())return@forEach
            if(!edited.has(key))result.remove(key)
            else if(before is JSONObject&&after is JSONObject)result.put(key,merge(result.optJSONObject(key)?:JSONObject(),before,after))
            else result.put(key,after)
        }
        return result
    }
}

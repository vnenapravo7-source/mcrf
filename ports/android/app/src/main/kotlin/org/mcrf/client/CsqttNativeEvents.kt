package org.mcrf.client

import org.json.JSONObject

/** Control events must arrive complete. A malformed policy never becomes a
 * successful no-op; transport framing is responsible for large messages. */
internal object CsqttNativeEvents {
    private const val PREFIX="__CSQTT_EVENT__|"
    private val consumed=setOf("PROGRESS","CONFIG","READY","TUN_LOST","STOPPED","CAPTCHA","ACTIVE_ZERO","ERROR","STATS","ROUTING","FLOW_ROUTED")
    data class Event(val kind:String,val data:JSONObject)
    fun read(line:String):Event? {
        if(!line.startsWith(PREFIX))return null
        val parts=line.split('|',limit=3)
        if(parts.size!=3||parts[1] !in consumed)return null
        // Malformed events that affect session state still fail closed.
        return Event(parts[1],JSONObject(parts[2]))
    }
}

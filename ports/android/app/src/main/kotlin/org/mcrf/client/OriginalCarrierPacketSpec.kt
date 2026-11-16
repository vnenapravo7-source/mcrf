package org.mcrf.client

import org.json.JSONObject

/** Ephemeral authenticated endpoint description; never a persisted profile. */
internal class OriginalCarrierPacketSpec(val tag:String,val nonce:String,val local:String,val mtu:Int) {
    init {
        require(tag.matches(Regex("[A-Za-z0-9._-]{1,96}")) && nonce.matches(Regex("[0-9a-f]{32}")) && mtu in 576..1984){"ORIGINAL_CARRIER_INPUT"}
        val octets=local.split('.')
        require(octets.size==4 && octets.all {it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255} &&
            octets.first().toInt() in 1..223 && octets.first()!="127"){"ORIGINAL_CARRIER_LOCAL"}
    }
    fun json()=JSONObject().put("schema",1).put("tag",tag).put("nonce",nonce).put("local",local).put("mtu",mtu).toString()
    override fun toString()="OriginalCarrierPacketSpec(redacted)"
    companion object {
        const val CAPABILITY="owned_original_ip_stack_v1"
        fun parse(source:String):OriginalCarrierPacketSpec {
            require(source.toByteArray(Charsets.UTF_8).size<=512){"ORIGINAL_CARRIER_INPUT"}
            val spec=JSONObject(source)
            require(spec.keys().asSequence().toSet()==setOf("schema","tag","nonce","local","mtu") && spec.opt("schema")==1){"ORIGINAL_CARRIER_SCHEMA"}
            val tag=spec.opt("tag");val nonce=spec.opt("nonce");val local=spec.opt("local");val mtu=spec.opt("mtu")
            require(tag is String && nonce is String && local is String && mtu is Int){"ORIGINAL_CARRIER_INPUT"}
            return OriginalCarrierPacketSpec(tag,nonce,local,mtu)
        }
    }
}

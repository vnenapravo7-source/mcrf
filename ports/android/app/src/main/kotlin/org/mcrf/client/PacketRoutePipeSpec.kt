package org.mcrf.client

import org.json.JSONObject
import java.security.SecureRandom

/** Private dispatcher attachment, never persisted/exported as a user profile.
 * Assigned interface HOST addresses must survive, including non-host prefixes. */
internal class PacketRoutePipeSpec private constructor(val tag:String,val nonce:String,val mtu:Int,addresses:List<String>) {
    val addresses:List<String> = java.util.Collections.unmodifiableList(addresses.toList())
    init {
        require(tag.matches(Regex("[A-Za-z0-9._-]{1,96}"))&&nonce.matches(Regex("[0-9a-f]{32}"))&&mtu in 1280..9000){"PACKET_ROUTE_PIPE_INPUT"}
        require(addresses.size in 1..8&&addresses.joinToString(",").length<=512){"PACKET_ROUTE_PIPE_ADDRESSES"}
        val unique=hashSetOf<String>()
        addresses.forEach {text->
            require(text.length in 3..64&&text==text.trim()&&text.count {it=='/'}==1){"PACKET_ROUTE_PIPE_ADDRESSES"}
            val address=RouteRules.literal(text.substringBefore('/'))?:throw IllegalArgumentException("PACKET_ROUTE_PIPE_NUMERIC_ADDRESS")
            val prefix=text.substringAfter('/').toIntOrNull()
            require(prefix!=null&&prefix in 0..address.address.size*8&&!(text.substringBefore('/').contains(':')&&address.address.size==4)&&!address.isAnyLocalAddress&&!address.isMulticastAddress&&
                unique.add(address.address.joinToString(""){"%02x".format(it.toInt() and 255)})){"PACKET_ROUTE_PIPE_ADDRESSES"}
        }
    }
    fun json()=JSONObject().put("schema",1).put("tag",tag).put("nonce",nonce).put("mtu",mtu).put("addresses",addresses.joinToString(","))
    override fun toString()="PacketRoutePipeSpec(redacted)"
    companion object {
        const val CAPABILITY="owned_pipe_stack_v1"
        fun create(tag:String,mtu:Int,addresses:List<String>):PacketRoutePipeSpec {
            val bytes=ByteArray(16).also(SecureRandom()::nextBytes)
            return PacketRoutePipeSpec(tag,bytes.joinToString(""){"%02x".format(it.toInt() and 255)},mtu,addresses)
        }
        fun parse(text:String):PacketRoutePipeSpec {
            require(text.toByteArray(Charsets.UTF_8).size<=1024){"PACKET_ROUTE_PIPE_INPUT"}
            val input=JSONObject(text)
            require(input.keys().asSequence().toSet()==setOf("schema","tag","nonce","mtu","addresses")&&input.opt("schema")==1){"PACKET_ROUTE_PIPE_SCHEMA"}
            val tag=input.opt("tag");val nonce=input.opt("nonce");val mtu=input.opt("mtu");val addresses=input.opt("addresses")
            require(tag is String&&nonce is String&&mtu is Int&&addresses is String){"PACKET_ROUTE_PIPE_INPUT"}
            return PacketRoutePipeSpec(tag,nonce,mtu,addresses.split(','))
        }
    }
}

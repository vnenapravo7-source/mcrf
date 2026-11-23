package org.mcrf.client

import org.json.JSONObject
import org.mcrf.engine.spi.v1.Platform

/** Bounded authenticated-child metadata. Never contains payloads or credentials. */
internal data class BsNativeRoutedFlow(val generation:Long,val query:Platform.ConnectionQuery,val uid:Int,val domain:String?,val tunneled:Boolean){
    companion object {
        fun parse(data:JSONObject,expected:Long):BsNativeRoutedFlow {
            require(data.toString().toByteArray(Charsets.UTF_8).size<=2048)
            require(data.keys().asSequence().toSet()==setOf("contract","generation","protocol","source","source_port","destination","destination_port","uid","domain","target"))
            require(data.getString("contract")=="BS_NATIVE_FLOW_OBSERVATION_CONTRACT_1")
            fun number(key:String):Long {val value=data.get(key);require(value is Int||value is Long);return (value as Number).toLong()}
            val generation=number("generation");require(generation==expected&&generation>0)
            val protocol=number("protocol");require(protocol==6L||protocol==17L)
            fun port(key:String)=number(key).also {require(it in 1..65535)}.toInt()
            fun address(key:String):String {require(data.get(key) is String);return data.getString(key).also {require(it.length in 1..45);ClientTunCidr.parse("$it/${if(':' in it)128 else 32}")}}
            val uid=number("uid").also {require(it in 0..Int.MAX_VALUE.toLong())}.toInt()
            val domain=if(data.isNull("domain"))null else {require(data.get("domain") is String);TrafficDomainAttribution.canonicalHost(data.getString("domain"))?:throw IllegalArgumentException("FLOW_DOMAIN")}
            require(data.get("target") is String);val target=data.getString("target");require(target in setOf("direct","proxy"))
            return BsNativeRoutedFlow(generation,Platform.ConnectionQuery(protocol.toInt(),address("source"),port("source_port"),address("destination"),port("destination_port")),uid,domain,target=="proxy")
        }
    }
}

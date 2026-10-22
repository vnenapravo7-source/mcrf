package org.mcrf.client

import org.json.JSONObject
import java.security.SecureRandom

/** Private internal bridge credentials. Never an application-facing proxy or
 * proof of original socket UID. The sole dispatcher owns rule/DNS decisions.
 * No domain/system DNS fallback is permitted at the module's boundary. */
internal class SteerRouteBackendSpec private constructor(val username:String,val password:String) {
    fun json()=JSONObject().put("schema",1).put("username",username).put("password",password)
        .put("destinationDns",DNS)
    override fun toString()="SteerRouteBackendSpec(redacted)"
    companion object {
        const val CAPABILITY="native_protocol_internal_socks_1"
        const val DNS="numeric_resolved_by_dispatcher"
        private val fields=setOf("schema","username","password","destinationDns")
        fun create():SteerRouteBackendSpec {
            val random=SecureRandom()
            fun token(size:Int)=ByteArray(size).also(random::nextBytes).joinToString(""){"%02x".format(it.toInt() and 255)}
            return SteerRouteBackendSpec(token(16),token(32))
        }
        fun parse(text:String):SteerRouteBackendSpec {
            require(text.toByteArray(Charsets.UTF_8).size<=1024){"STEER_ROUTE_INPUT"}
            val input=JSONObject(text)
            require(input.keys().asSequence().toSet()==fields&&input.opt("schema")==1&&input.opt("destinationDns")==DNS){"STEER_ROUTE_SCHEMA"}
            val user=input.opt("username");val password=input.opt("password")
            require(user is String&&password is String&&user.matches(Regex("[0-9a-f]{32}"))&&password.matches(Regex("[0-9a-f]{64}"))){"STEER_ROUTE_CREDENTIALS"}
            return SteerRouteBackendSpec(user,password)
        }
        fun port(text:String):Int {
            require(text.toByteArray(Charsets.UTF_8).size<=256){"STEER_ROUTE_REPLY"}
            val input=JSONObject(text);val port=input.opt("port")
            require(input.keys().asSequence().toSet()==setOf("schema","port")&&input.opt("schema")==1&&port is Int&&port in 1024..65535){"STEER_ROUTE_REPLY"}
            return port
        }
    }
}

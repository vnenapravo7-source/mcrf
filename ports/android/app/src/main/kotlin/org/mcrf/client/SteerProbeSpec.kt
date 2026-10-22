package org.mcrf.client

import org.json.JSONObject
import java.net.URI

/** A private, one-shot HTTPS request. No TUN/routing settings or fallback.
 * Target DNS is resolved on the captured physical network by the host. */
internal data class SteerProbeSpec(val url:String,val targetIp:String,val timeoutMs:Int=10000) {
    val host:String
    val path:String
    init {
        require(timeoutMs in 1..30000&&url.length<=2304&&url.all {it.code in 33..126}) {"STEER_PROBE_INPUT"}
        val parsed=URI(url)
        require(parsed.scheme=="https"&&parsed.host!=null&&parsed.rawUserInfo==null&&parsed.rawFragment==null&&parsed.port in setOf(-1,443)) {"STEER_PROBE_HTTPS_REQUIRED"}
        host=parsed.host
        require(host.length in 1..253&&host.matches(Regex("[A-Za-z0-9.-]+"))) {"STEER_PROBE_HOST"}
        path=parsed.rawPath.orEmpty().ifEmpty {"/"}+parsed.rawQuery?.let {"?$it"}.orEmpty()
        require(path.startsWith('/')&&path.length<=2048) {"STEER_PROBE_PATH"}
        require(RouteRules.literal(targetIp)!=null) {"STEER_PROBE_NUMERIC_TARGET"}
    }
    fun json()=JSONObject().put("schema",1).put("url",url).put("targetIp",targetIp).put("timeoutMs",timeoutMs)
    companion object {
        const val CAPABILITY="native_https_204_fresh_worker_v1"
        fun requestSha(profile:String,spec:String,dial:String):String {
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            for(value in listOf(profile,spec,dial)) {
                val bytes=value.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
            }
            return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        }
        fun parse(value:String):SteerProbeSpec {
            require(value.toByteArray(Charsets.UTF_8).size<=4096) {"STEER_PROBE_INPUT"}
            val source=JSONObject(value)
            require(source.keys().asSequence().toSet()==setOf("schema","url","targetIp","timeoutMs")&&source.opt("schema")==1) {"STEER_PROBE_SCHEMA"}
            val timeout=source.get("timeoutMs")
            require(timeout is Int&&source.get("url") is String&&source.get("targetIp") is String) {"STEER_PROBE_INPUT"}
            return SteerProbeSpec(source.getString("url"),source.getString("targetIp"),timeout)
        }
    }
}

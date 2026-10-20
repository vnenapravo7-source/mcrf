package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Read-only, no network/process launch. Never serializes the supplied profile or exception text. */
object EngineUpdateOpenFluxDiagnostics {
    private val types=setOf("direct","yandex","vyandex","boards","mailru","cupsonline")
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun shape(profile:JSONObject):JSONObject {
        val transports=profile.optJSONArray("transports")
        val count=transports?.length()?:0
        val known=linkedSetOf<String>();var unknown=false
        if(count in 1..64)repeat(count){i->val type=transports?.optJSONObject(i)?.optString("type").orEmpty();if(type in types)known.add(type)else unknown=true}
        val mode=when(profile.optString("mode")){""->"classic";"stream"->"stream";else->"unknown"}
        val codec=profile.optString("codec")
        return JSONObject().put("mode",mode).put("codec",if(codec in setOf("","batched","legacy"))codec.ifEmpty{"default"}else"unknown")
            .put("negotiate",profile.optBoolean("negotiate")).put("key_present",profile.optString("secret").isNotEmpty())
            .put("context_present",profile.optString("context").isNotEmpty()).put("transport_count",if(count in 1..64)count else -1)
            .put("transport_types",org.json.JSONArray(known.sorted())).put("unknown_transport",unknown||count !in 1..64)
    }
    /** Keep only the coordinator's existing fixed probe categories, never raw native/provider errors. */
    fun probeCategory(value:String):String {
        val errors=setOf("IOException","IllegalArgumentException","IllegalStateException","CancellationException")
        if(value.startsWith("carrier/core: "))return "carrier/core: "+value.removePrefix("carrier/core: ").takeIf{it in errors}.orEmpty().ifEmpty{"other"}
        val words=setOf("timeout","deadline","eof","connect","socks","status","resolve","refused","reset","protocol","handshake","closed","certificate","http","tls","unexpected","unreachable","network","i/o","dns","udp","invalid","403","404","429","500","502","503")
        if(value.startsWith("urltest: "))return "urltest: "+value.removePrefix("urltest: ").split(',').filter{it in words}.distinct().sorted().joinToString(",")
        return "unclassified"
    }
    /** Optional ProbeService hook: exact known wrapper messages map to fixed stages; all else hidden. */
    fun nativeFailure(error:Throwable):String=when(error.message){
        "Движок завершился при запуске. Проверь профиль и совместимость сервера."->"native_exited_before_ready"
        "Движок не подключился вовремя. Проверь доступность сервиса и параметры сервера."->"native_ready_timeout"
        "Подключение отменено"->"cancelled"
        "Движок не установлен для этой архитектуры Android."->"native_abi_missing"
        "Подпись или версия пакета движков не совпадает с MCRF."->"module_identity_rejected"
        else->"carrier_or_core_unclassified"
    }
    /** Off UI thread. Coordinator passes the exact already-approved stored original and its digest. */
    fun snapshot(context:Context,original:String,profile:JSONObject,approvedSha256:String):JSONObject {
        require(approvedSha256.matches(Regex("[0-9a-f]{64}"))&&sha(original.toByteArray(Charsets.UTF_8))==approvedSha256){"OpenFlux diagnostic profile is not the approved original."}
        require(profile.optString("type")=="openflux")
        val result=shape(profile)
        try{
            val decoded=requireNotNull(OpenFluxProfile.link(original).carrier)
            val fields=listOf("mode","codec","negotiate","secret","context","transports")
            result.put("stored_matches_original",fields.all{key->decoded.opt(key)?.toString()==profile.opt(key)?.toString()})
        }catch(_:Exception){result.put("stored_matches_original",false)}
        try{
            val info=EngineModules.installation(context,"openflux");val executable=EngineModules.executable(context,"openflux")
            result.put("module_identity","verified").put("module_apk_sha256",hash(File(info.sourceDir))).put("native_sha256",hash(executable))
            try{val m=requireNotNull(info.metaData);val source=EngineUpdatePolicy.source(JSONObject().put("repository",m.getString("org.mcrf.engines.source.repository"))
                .put("version",m.getString("org.mcrf.engines.source.version")).put("commit",m.getString("org.mcrf.engines.source.commit")))
                result.put("source_version",source.version).put("source_commit",source.commit)
            }catch(_:Exception){result.put("source_metadata","unverified")}
        }catch(_:Exception){result.put("module_identity","unverified")}
        return result
    }
}

package org.mcrf.client

import android.content.Context
import org.json.JSONObject

/** Local defaults never rewrite a subscription. A per-server override always wins. */
internal object CarrierStreamDefaults {
    const val SECTION="vkTurnStreams"
    const val OVERRIDE="workers_override"
    val kinds=listOf("csqtt","csqtt25","qwdtt","wdtt")
    fun values(kind:String):List<Int> {require(kind in kinds);return if(kind=="csqtt25")(10..100 step 10).toList()else(9..when(kind){"csqtt"->126;"wdtt"->108;else->162} step 9).toList()}
    fun default(kind:String)=when(kind){"csqtt25"->20;"qwdtt"->9;else->18}
    fun validate(source:JSONObject) {source.keys().forEach {kind->require(kind in kinds&&source.get(kind) is Int&&source.getInt(kind) in values(kind)){"Некорректное общее число потоков."}}}
    fun validateOverride(profile:JSONObject) {
        if(!profile.has(OVERRIDE))return
        require(profile.get(OVERRIDE) is Int)
        val number=profile.getInt(OVERRIDE)
        require(if(Csqtt25Profile.isProfile(profile))number in 3..100 else number in values(profile.getString("type"))) {"Число потоков не поддерживается этим транспортом."}
    }
    fun apply(profile:JSONObject,settings:JSONObject):JSONObject {
        validate(settings);validateOverride(profile)
        val p=JSONObject(profile.toString());val engine=EngineModules.carrierKind(p)
        val kind=if(engine in CsqttGenerations.legacyEngines)"csqtt"else engine
        if(kind !in kinds)return p
        val explicit=p.has(OVERRIDE)
        val requested=if(explicit)p.getInt(OVERRIDE)else settings.optInt(kind,default(kind))
        val calls=p.optJSONArray("hashes")?.length()?:0
        val automatic=p.optString("vk_anon_path")=="auto_js"||p.optString("hash_source")=="auto"||p.optString("vk_hash_mode")=="auto_js"
        val maximum=if(kind=="csqtt25")minOf(100,if(automatic||p.optBoolean("allow_hash_redistribution"))100 else calls.coerceAtLeast(1)*10)
            else minOf(values(kind).last(),(if(automatic)maxOf(6,p.optInt("vk_call_count",6))else calls.coerceAtLeast(1))*27)
        require(!explicit||requested<=maximum){"Ручное число потоков больше доступного для звонков этого сервера."}
        val count=requested.coerceAtMost(maximum)
        p.put("workers",count).remove(OVERRIDE)
        if(kind=="csqtt25")p.put("link_workers",count) // Explicit runtime count, not a link rewrite.
        if(kind=="csqtt")p.put("extra_streams",count>72)
        return p
    }
    fun load(context:Context):JSONObject=synchronized(ProfileStore.storageLock) {
        val source=ProfileStore(context,"preferences.enc").read()
        val value=(if(source.isBlank())JSONObject()else JSONObject(source)).optJSONObject(SECTION)?:JSONObject()
        validate(value);JSONObject(value.toString())
    }
    fun save(context:Context,value:JSONObject)=synchronized(ProfileStore.storageLock) {
        validate(value);val store=ProfileStore(context,"preferences.enc");val source=store.read()
        val root=if(source.isBlank())JSONObject()else JSONObject(source)
        root.put(SECTION,JSONObject(value.toString()));store.write(root.toString());SteerProbeCache.clear()
    }
}

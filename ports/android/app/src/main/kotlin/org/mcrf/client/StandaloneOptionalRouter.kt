package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject

/** Admission for one explicitly selected Xray or TLS Core egress of the native router. */
internal object StandaloneOptionalRouter {
    fun hint(context:Context,entry:ServerImport.Server):Boolean {
        val output=entry.outbound?:return false
        val type=output.optString("type")
        if(type=="xray")return true
        val tls=output.optJSONObject("tls")
        if(type!="vless"&&(tls?.optBoolean("enabled")!=true||type !in setOf("vmess","trojan","http","socks")))return false
        val settings=OptionalCoreStore(context).load()
        if(type=="vless"&&settings.xrayVlessDefault)return true
        return tls?.optBoolean("enabled")==true&&
            (output.has("tls_fragment")||tls.has("tls_tricks")||
                settings.customFragmentDefault&&!tls.has("fragment")||settings.mixedCaseSniDefault)
    }

    fun request(context:Context,source:JSONObject,tag:String):OptionalCoreConfig.Request? =
        request(source,tag,OptionalCoreStore(context).load(),CoreTuningStore(context).load())

    internal fun request(source:JSONObject,tag:String,settings:OptionalCoreSettings,
                         tuning:CoreTuningSettings):OptionalCoreConfig.Request? {
        if(tag.isBlank()||source.optJSONObject("route")?.optString("final")!=tag||
            source.has("mcrf_carrier")||source.has("mcrf_bs")||
            (source.optJSONArray("endpoints")?.length()?:0)>0)return null
        val outputs=source.optJSONArray("outbounds")?:return null
        val active=(0 until outputs.length()).map(outputs::getJSONObject).filter {it.optString("tag")==tag}
        if(active.size!=1||active.single().optString("type") !in setOf("xray","vless","vmess","trojan","http","socks"))return null
        val entries=OptionalCorePlan(source,settings,tuning).entries()
        val selected=entries.singleOrNull {it.tag==tag}?:return null
        require(entries.size==1){"Автономный VPN поддерживает один выход Xray или TLS Core."}
        return selected.request.takeIf {it.kind=="xray"||it.kind=="tls-core"}
    }

    fun requirePhysical(context:Context,request:OptionalCoreConfig.Request) {
        require(request.kind=="xray"||request.kind=="tls-core")
        val available=try {OptionalCoreProcess.advertisesPhysicalNetwork(context,request.kind)}
        catch(_:PackageManager.NameNotFoundException){throw ClientOptionalCoreRequired(request.kind)}
        require(available){"Обновите пакет ${request.kind} для привязки к физической сети."}
    }
}

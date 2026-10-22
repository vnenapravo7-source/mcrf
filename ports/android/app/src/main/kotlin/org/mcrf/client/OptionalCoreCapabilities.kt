package org.mcrf.client

import org.json.JSONObject

/** Pure optional-worker bridge checks, not the primary owner's full profile validator. */
object OptionalCoreCapabilities {
    data class Primary(val id:String,val verified:Boolean,val capabilities:Set<String>)
    data class Result(val eligibleForNativeCheck:Boolean,val required:Set<String>,val blockers:List<String>,val notes:List<String>,val nativeCheckRequired:Boolean=true,val confirmationRequired:Boolean=true,val requiredWorkerCapabilities:Map<String,Set<String>> = emptyMap())
    fun preflight(config:JSONObject,optional:OptionalCoreSettings,core:CoreTuningSettings,primary:Primary):Result {
        val blockers=mutableListOf<String>();val required=mutableSetOf<String>();val notes=mutableListOf<String>()
        val workers=linkedMapOf<String,MutableSet<String>>()
        if(!primary.verified)blockers.add("Возможности установленного основного ядра ещё не подтверждены.")
        try {
            val plan=OptionalCorePlan(config,optional,core)
            notes.addAll(plan.notes)
            plan.entries().forEach {entry ->
                required.addAll(setOf("outbound:socks5","socks5:authentication","socks5:tcp"))
                if(entry.request.kind=="xray")required.add("socks5:udp")
                else notes.add("TLS-модуль для ${entry.tag} поддерживает только TCP.")
                val request=entry.request;val caps=workers.getOrPut(request.kind){mutableSetOf()}
                if(request.serverDns!=null)caps.add("server-address-dns-v1")
                if(request.kind=="xray") {
                    caps.addAll(setOf("vless","socks5-udp-associate"))
                    val stream=request.outbound.optJSONObject("streamSettings")
                    if(stream?.optString("network")=="xhttp")caps.add("xhttp")
                    if(stream?.optString("security")=="reality")caps.add("reality")
                    if(request.outbound.optJSONObject("mux")?.optBoolean("enabled")==true)caps.add("mux")
                }else {
                    val tls=request.outbound.optJSONObject("tls")
                    if(request.outbound.optJSONObject("tls_fragment")?.optBoolean("enabled")==true)caps.add("tls-clienthello-record-fragment-size-sleep")
                    if(tls?.optJSONObject("tls_tricks")?.optBoolean("mixedcase_sni")==true)caps.add("tls-mixedcase-sni")
                    if(tls?.optJSONObject("utls")?.optBoolean("enabled")==true)caps.add("utls")
                    if(request.certificateStore!=null)caps.add("certificate-store")
                }
            }
        }catch(e:IllegalArgumentException){blockers.add(e.message?:"Профиль несовместим с optional core.")}
        catch(_:org.json.JSONException){blockers.add("Структура профиля optional core некорректна.")}
        required.filter {it !in primary.capabilities}.forEach {blockers.add("Основное ядро ${primary.id} не подтвердило возможность $it.")}
        return Result(blockers.isEmpty(),required.toSet(),blockers.toList(),notes.distinct(),requiredWorkerCapabilities=workers.mapValues {it.value.toSet()})
    }
    /** Inputs must come from validated same-signer descriptors; no discovery or install here. */
    fun workerBlockers(result:Result,verifiedWorkers:Map<String,Set<String>>):List<String> = result.requiredWorkerCapabilities.flatMap {(kind,required) ->
        val available=verifiedWorkers[kind]
        if(available==null)listOf("Подписанный модуль $kind ещё не проверен или не установлен.")
        else (required-available).sorted().map {"Модуль $kind не подтвердил возможность $it."}
    }
}

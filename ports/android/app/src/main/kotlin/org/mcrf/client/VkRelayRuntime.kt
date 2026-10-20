package org.mcrf.client

import org.json.JSONObject
import org.json.JSONArray

/** Coordinator calls these before SwgProfile validates the effective runtime copy. */
object VkRelayRuntime {
    fun defaultsForNewQwdtt(raw: JSONObject,account: VkRelayAccount): JSONObject {
        val out=JSONObject(raw.toString())
        if(out.optString("type")!="qwdtt" || out.has("hash_source")) return out
        val existing=values(out)
        if(existing.isNotEmpty()) return out.put("hash_source","manual")
        val auto=account.defaultSource()==VkRelayProtocol.HashSource.AUTOMATIC
        out.put("hash_source",if(auto) "auto" else "manual")
        if(auto) out.put("vk_call_count",account.defaultCount())
        if(!auto) out.put("hashes",JSONArray(account.manualDefaults().map { VkRelayProtocol.hash(it) }))
        return out
    }
    /** Saved profile is never mutated. Manual fields have precedence even if hash_source=auto. */
    fun prepare(raw: JSONObject,account: VkRelayAccount,cancelled: () -> Boolean={ false }): JSONObject {
        return prepareForConnection(connectionGroup(raw),raw,account,cancelled)
    }
    private fun connectionGroup(raw: JSONObject): String {
        val fields=listOf("type","id","name","server","server_port","password","device_id") + if(raw.optInt("vk_call_count",6)!=6) listOf("vk_call_count") else emptyList()
        val identity=fields.joinToString("|") { k -> raw.optString(k).let { "${it.length}:$it" } }
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "runtime:$digest"
    }
    fun prepareForProbe(raw: JSONObject,account: VkRelayAccount,cancelled: () -> Boolean={false}): JSONObject =
        prepareForProbeConnection(connectionGroup(raw),raw,account,cancelled)
    /** Manual hashes work without any account read. Auto requires an already-complete owner-bound group. */
    fun prepareForProbeConnection(connectionId: String,raw: JSONObject,account: VkRelayAccount,cancelled: () -> Boolean={false}): JSONObject {
        check(!cancelled() && !Thread.currentThread().isInterrupted) { "Проверка остановлена" }
        val out=JSONObject(raw.toString());val type=out.optString("type")
        if(type !in listOf("qwdtt","wdtt","csqtt")) return out
        val manual=values(out)
        if(manual.isNotEmpty()) { out.put("hashes",JSONArray(manual.map { VkRelayProtocol.hash(it,type) })).put("hash_source","manual");return out }
        require(type=="qwdtt" && out.optString("hash_source")=="auto") { "Для проверки нужны ручные хеши или ранее созданная группа QWDTT" }
        val calls=account.cachedFor(connectionId,type,out.optInt("vk_call_count",6))
        check(!cancelled() && !Thread.currentThread().isInterrupted) { "Проверка остановлена" }
        return out.put("hashes",JSONArray(calls.map { VkRelayProtocol.hash(it,type) })).put("hash_source","manual")
    }
    fun prepareForConnection(connectionId: String,raw: JSONObject,account: VkRelayAccount,cancelled: () -> Boolean={false}): JSONObject {
        val out=JSONObject(raw.toString());val type=out.optString("type")
        if(type !in listOf("qwdtt","wdtt","csqtt")) return out
        val manual=values(out)
        if(manual.isNotEmpty()) { out.put("hashes",JSONArray(manual.map { VkRelayProtocol.hash(it,type) }));out.put("hash_source","manual");return out }
        require(type=="qwdtt" && out.optString("hash_source")=="auto") { "Для подключения нужны ручные хеши звонков" }
        val calls=account.resolveCountFor(connectionId,emptyList(),true,type,out.optInt("vk_call_count",6),cancelled)
        out.put("hashes",JSONArray(calls.map { VkRelayProtocol.hash(it,type) })).put("hash_source","manual")
        return out
    }
    private fun values(raw: JSONObject): List<String> {
        val field=if(raw.has("hashes")) "hashes" else "vkHashes"
        return if(raw.opt(field) is JSONArray) raw.getJSONArray(field).let { a -> (0 until a.length()).map(a::getString).filter(String::isNotBlank) }
        else raw.optString(field).split(Regex("[,;+\\s]+" )).filter(String::isNotBlank)
    }
}

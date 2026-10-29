package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.net.URI
import java.net.SocketTimeoutException
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Server-owned invite binding. The six-digit code is never persisted or logged. */
object InviteCodeSync {
    private val endpoints=listOf(
        URI("https://vnenapravo7.ru/wdtt/api/invite"),
        URI("https://outright7.site/wdtt/api/invite")
    )
    data class State(val token:String,val linkedAt:Long,val lastSync:Long,val lastCount:Int,val subscriptionAvailable:Boolean)
    data class Manifest(val token:String?,val subscription:String?,val bypass:String)
    fun state(context:Context):State?=synchronized(ProfileStore.storageLock){
        val root=preferences(context);if(!root.has(SECTION))return@synchronized null
        val value=root.getJSONObject(SECTION);State(value.getString("token"),value.optLong("linkedAt"),value.optLong("lastSync"),value.optInt("lastCount"),value.optBoolean("subscriptionAvailable",true))
    }
    private fun save(context:Context,state:State?)=synchronized(ProfileStore.storageLock){
        val store=ProfileStore(context,"preferences.enc");val root=preferences(context)
        if(state==null)root.remove(SECTION) else root.put(SECTION,JSONObject().put("token",state.token).put("linkedAt",state.linkedAt).put("lastSync",state.lastSync).put("lastCount",state.lastCount).put("subscriptionAvailable",state.subscriptionAvailable))
        store.write(root.toString())
    }
    private fun preferences(context:Context):JSONObject{val raw=ProfileStore(context,"preferences.enc").read();return if(raw.isBlank())JSONObject()else JSONObject(raw)}
    private fun deviceId(context:Context):String=synchronized(ProfileStore.storageLock){
        val store=ProfileStore(context,"preferences.enc");val root=preferences(context);val existing=root.optString(DEVICE_ID)
        if(existing.matches(Regex("[0-9a-f-]{36}")))existing else UUID.randomUUID().toString().also {root.put(DEVICE_ID,it);store.write(root.toString())}
    }
    fun unlink(context:Context)=save(context,null)
    fun redeem(context:Context,code:String):Int {
        require(code.matches(Regex("[0-9]{6}"))){"Инвайт-код состоит из шести цифр."}
        val manifest=request(JSONObject().put("action","redeem").put("code",code).put("deviceId",deviceId(context)))
        val token=requireNotNull(manifest.token){"Сервер не выдал защищённую привязку."}
        val count=commit(context,manifest)
        save(context,State(token,System.currentTimeMillis(),System.currentTimeMillis(),count,manifest.subscription!=null))
        return count
    }
    fun refreshIfLinked(context:Context):Int? {
        val current=state(context)?:return null
        val manifest=request(JSONObject().put("action","sync").put("token",current.token))
        val count=commit(context,manifest)
        save(context,current.copy(lastSync=System.currentTimeMillis(),lastCount=count,subscriptionAvailable=manifest.subscription!=null))
        return count
    }
    private fun commit(context:Context,manifest:Manifest):Int {
        val plans=buildList {
            manifest.subscription?.let {add(ImportPlan.prepare(it){url->SubscriptionUpdates.fetch(context,url)})}
            add(ImportPlan.prepare(SwgInviteSubscription.fetch(manifest.bypass)){url->SubscriptionUpdates.fetch(context,url)})
        }
        var plan=ImportPlan.combine(plans)
        require(plan.entries.isNotEmpty()){"В наборе инвайта пока нет доступных подключений."}
        val legacyIds=plan.entries.filter {entry->entry.carrier?.let {it.optString("type")=="csqtt"&&!it.has("csqtt_generation")}==true}.mapTo(linkedSetOf()){it.id}
        val choices=plan.entries.filter {it.id in legacyIds&&CsqttImportResolution.ambiguous(it)}.associate {it.id to "legacy"}
        plan=plan.resolveCsqtt(choices,Connections(context).apply{load()}.entries().associateBy {it.id})
        return plan.withNewQwdttDefaults(Connections(context).apply{load()}.entries().mapTo(hashSetOf()){it.id}).commit(Connections(context).apply{load()})
    }
    private fun request(body:JSONObject):Manifest {
        try{return request(endpoints.first(),body)}
        catch(_:SocketTimeoutException){return request(endpoints[1],body)}
    }
    private fun request(endpoint:URI,body:JSONObject):Manifest {
        val connection=endpoint.toURL().openConnection() as HttpsURLConnection
        try{
            connection.instanceFollowRedirects=false;connection.useCaches=false;connection.connectTimeout=15_000;connection.readTimeout=20_000
            connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","MCRF-Android")
            val bytes=body.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=4096);connection.setFixedLengthStreamingMode(bytes.size);connection.outputStream.use {it.write(bytes)}
            require(connection.responseCode==200){"Инвайт-код не принят или временно недоступен."}
            val raw=connection.inputStream.use {ImportIO.read(it)};require(raw.size<=256*1024){"Ответ инвайт-сервиса слишком большой."}
            val value=JSONObject(String(raw,Charsets.UTF_8));require(value.optInt("schema")==1){"Неизвестный формат инвайт-сервиса."}
            return Manifest(value.optString("token").takeIf {it.isNotBlank()},optionalSubscription(value.optString("subscriptionUrl")),SwgInviteSubscription.address(value.getString("bypassUrl")))
        }finally{connection.disconnect()}
    }
    internal fun optionalSubscription(value:String):String?=value.takeIf {it.isNotBlank()}?.let {SubscriptionClient.address(it).toString()}
    fun requiredEngines()=linkedSetOf("qwdtt","wdtt","csqtt","csqtt25")
    fun groupSummary()="WDTT · QWDTT · CSQTT SWG 2.1.9 · CSQTT SWG 2.5.1"
    private const val SECTION="inviteCode"
    private const val DEVICE_ID="inviteDeviceId"
}

package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Server-owned invite binding. The six-digit code is never persisted or logged. */
object InviteCodeSync {
    internal val endpoint=URI("https://179.254.154.28/wd/api/invite")
    data class State(val token:String,val linkedAt:Long,val lastSync:Long,val lastCount:Int,val subscriptionAvailable:Boolean,val ruAppsApplied:Boolean=true)
    data class Manifest(val token:String?,val subscription:String?,val bypass:String?,val serverLinks:List<String> = emptyList()) {
        val vpnAvailable:Boolean get()=subscription!=null||serverLinks.isNotEmpty()
    }
    private var operation=0L // Access only under storageLock; unlink invalidates pending network replies.
    private data class Admission(val operation:Long,val generation:String,val state:State?)
    private fun admission(context:Context)=Admission(++operation,ProfileStore.generation(context),state(context))
    private fun checkAdmission(context:Context,expected:Admission) {
        require(operation==expected.operation&&ProfileStore.generation(context)==expected.generation&&state(context)==expected.state) {
            "Привязка изменилась. Повторите действие из текущего экрана инвайта."
        }
    }
    fun state(context:Context):State?=synchronized(ProfileStore.storageLock){
        val root=preferences(context);if(!root.has(SECTION))return@synchronized null
        val value=root.getJSONObject(SECTION);State(value.getString("token"),value.optLong("linkedAt"),value.optLong("lastSync"),value.optInt("lastCount"),value.optBoolean("subscriptionAvailable",true),value.optBoolean("ruAppsApplied",true))
    }
    private fun save(context:Context,state:State?)=synchronized(ProfileStore.storageLock){
        val store=ProfileStore(context,"preferences.enc");val root=preferences(context)
        if(state==null)root.remove(SECTION) else root.put(SECTION,JSONObject().put("token",state.token).put("linkedAt",state.linkedAt).put("lastSync",state.lastSync).put("lastCount",state.lastCount).put("subscriptionAvailable",state.subscriptionAvailable).put("ruAppsApplied",state.ruAppsApplied))
        store.write(root.toString())
    }
    private fun preferences(context:Context):JSONObject{val raw=ProfileStore(context,"preferences.enc").read();return if(raw.isBlank())JSONObject()else JSONObject(raw)}
    private fun deviceId(context:Context):String=synchronized(ProfileStore.storageLock){
        val store=ProfileStore(context,"preferences.enc");val root=preferences(context);val existing=root.optString(DEVICE_ID)
        if(existing.matches(Regex("[0-9a-f-]{36}")))existing else UUID.randomUUID().toString().also {root.put(DEVICE_ID,it);store.write(root.toString())}
    }
    fun unlink(context:Context)=synchronized(ProfileStore.storageLock){operation++;save(context,null)}
    fun redeem(context:Context,code:String):Int {
        require(code.matches(Regex("[0-9]{6}"))){"Инвайт-код состоит из шести цифр."}
        val expected=synchronized(ProfileStore.storageLock){admission(context)}
        val manifest=request(JSONObject().put("action","redeem").put("code",code).put("deviceId",deviceId(context)))
        val token=requireNotNull(manifest.token){"Сервер не выдал защищённую привязку."}
        require(token.length in 1..4096){"Некорректная защищённая привязка."}
        val pending=State(token,System.currentTimeMillis(),0,0,manifest.vpnAvailable,false)
        val importAdmission=synchronized(ProfileStore.storageLock){
            checkAdmission(context,expected)
            // Persist before fetching subscriptions too, so their failure remains retryable.
            save(context,pending)
            expected.copy(state=pending)
        }
        val plan=prepare(context,manifest)
        val count=synchronized(ProfileStore.storageLock){
            checkAdmission(context,importAdmission)
            val count=commit(context,plan)
            val linked=pending.copy(lastSync=System.currentTimeMillis(),lastCount=count)
            save(context,linked)
            finishRussianApps(context,linked)
            count
        }
        return count
    }
    fun refreshIfLinked(context:Context):Int? {
        val expected=synchronized(ProfileStore.storageLock){admission(context)}
        val current=expected.state?:return null
        val manifest=request(JSONObject().put("action","sync").put("token",current.token))
        val plan=prepare(context,manifest)
        val count=synchronized(ProfileStore.storageLock){
            checkAdmission(context,expected)
            val count=commit(context,plan)
            val linked=current.copy(lastSync=System.currentTimeMillis(),lastCount=count,subscriptionAvailable=manifest.vpnAvailable)
            save(context,linked)
            if(!linked.ruAppsApplied)finishRussianApps(context,linked)
            count
        }
        return count
    }
    private fun prepare(context:Context,manifest:Manifest):ImportPlan = prepareAvailable(manifest,
        {source->ImportPlan.prepare(source){url->SubscriptionUpdates.fetch(context,url)}},
        {val links=SwgInviteSubscription.fetch(requireNotNull(manifest.bypass));if(links.isBlank())ImportPlan.empty()else ImportPlan.prepare(links){url->SubscriptionUpdates.fetch(context,url)}})
    internal fun prepareAvailable(manifest:Manifest,subscription:(String)->ImportPlan,bypass:()->ImportPlan):ImportPlan {
        val plans=buildList {
            manifest.subscription?.let {add(subscription(it))}
            if(manifest.serverLinks.isNotEmpty())add(ImportPlan.prepare(manifest.serverLinks.joinToString("\n")){error("Отдельный сервер не должен загружать подписку.")})
            if(manifest.bypass!=null)add(bypass())
        }
        return if(plans.isEmpty())ImportPlan.empty()else ImportPlan.combine(plans)
    }
    /** No network work while the admission and local writers are locked. */
    private fun commit(context:Context,prepared:ImportPlan):Int {
        var plan=prepared
        val catalogue=Connections(context).apply{load()}
        val legacyIds=plan.entries.filter {entry->entry.carrier?.let {it.optString("type")=="csqtt"&&!it.has("csqtt_generation")}==true}.mapTo(linkedSetOf()){it.id}
        val choices=plan.entries.filter {it.id in legacyIds&&CsqttImportResolution.ambiguous(it)}.associate {it.id to "legacy"}
        plan=plan.resolveCsqtt(choices,catalogue.entries().associateBy {it.id})
        return plan.withNewQwdttDefaults(catalogue.entries().mapTo(hashSetOf()){it.id}).commitInvite(catalogue)
    }
    private fun request(body:JSONObject):Manifest {
        return request(endpoint,body)
    }
    private fun request(endpoint:URI,body:JSONObject):Manifest {
        val connection=endpoint.toURL().openConnection() as HttpsURLConnection
        try{
            connection.instanceFollowRedirects=false;connection.useCaches=false;connection.connectTimeout=15_000;connection.readTimeout=20_000
            connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","MCRF-Android")
            val bytes=body.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=4096);connection.setFixedLengthStreamingMode(bytes.size);connection.outputStream.use {it.write(bytes)}
            require(connection.responseCode==200){"Инвайт-код не принят или временно недоступен."}
            val raw=connection.inputStream.use {ImportIO.read(it)};require(raw.size<=256*1024){"Ответ инвайт-сервиса слишком большой."}
            return parseManifest(JSONObject(String(raw,Charsets.UTF_8)))
        }finally{connection.disconnect()}
    }
    internal fun parseManifest(value:JSONObject):Manifest {
        require(value.optInt("schema")==1){"Неизвестный формат инвайт-сервиса."}
        return Manifest(
            value.optString("token").takeIf {it.isNotBlank()},
            optionalSubscription(value.optString("subscriptionUrl")),
            value.optString("bypassUrl").takeIf {it.isNotBlank()}?.let(SwgInviteSubscription::address),
            directLinks(value)
        )
    }
    internal fun optionalSubscription(value:String):String?=value.takeIf {it.isNotBlank()}?.let {SubscriptionClient.address(it).toString()}
    internal fun directLinks(value:JSONObject):List<String> {
        if(!value.has("serverLinks"))return emptyList()
        val links=value.getJSONArray("serverLinks");require(links.length()<=3){"Слишком много отдельных серверов инвайта."}
        return (0 until links.length()).map {index->
            val link=links.getString(index).trim()
            require(link.length in 1..16384&&!SubscriptionClient.isAddress(link)&&ServerImport.read(link).size==1){"Некорректный отдельный сервер инвайта."}
            link
        }.distinct()
    }
    private fun finishRussianApps(context:Context,state:State):State {
        try {InviteRussianApps.apply(context)}catch(e:Exception) {
            throw IllegalArgumentException("Инвайт привязан. Не удалось сохранить правило RU-app; нажми «Обновить сейчас», чтобы повторить. ${ClientEvents.redact(e.message)}")
        }
        return state.copy(ruAppsApplied=true).also {save(context,it)}
    }
    internal fun registerBackup() {
        AndroidBackup.registerSection(SECTION,BackupDefaults.Group.PRESERVE) {value->
            require(value.keys().asSequence().all {it in setOf("token","linkedAt","lastSync","lastCount","subscriptionAvailable","ruAppsApplied")})
            require(value.getString("token").length in 1..4096)
            for(key in listOf("linkedAt","lastSync","lastCount"))if(value.has(key))require(value.get(key) is Number&&value.getLong(key)>=0)
            for(key in listOf("subscriptionAvailable","ruAppsApplied"))if(value.has(key))require(value.get(key) is Boolean)
        }
    }
    internal fun historyPreferences(source:JSONObject):JSONObject=JSONObject(source.toString()).apply {
        // This encrypted installation identifier is not a SharedPreferences setting.
        // Validate it without adding it to preferenceKeys(), whose overlay would drop it.
        if(has(DEVICE_ID)) {
            require(get(DEVICE_ID) is String&&getString(DEVICE_ID).matches(Regex("[0-9a-f-]{36}"))){"Повреждён идентификатор инвайта в снимке."}
            remove(DEVICE_ID)
        }
    }
    internal fun localPreferenceKeys()=setOf(DEVICE_ID)
    internal fun validateLocalPreferences(value:JSONObject) { historyPreferences(value) }
    fun requiredEngines(context:Context)=ImportPlan.requiredEngines(Connections(context).apply {load()}.inviteEntries())
    fun groupSummary()="WDTT · QWDTT · CSQTT SWG 2.1.9 · CSQTT SWG 2.5.1"
    private const val SECTION="inviteCode"
    private const val DEVICE_ID="inviteDeviceId"
}

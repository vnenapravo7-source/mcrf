package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class VkRelayAccount(private val store: VkRelayPersistence,
                     private val callRequest: ((String) -> JSONObject)?=null,
                     private val pause: (Long) -> Unit = { Thread.sleep(it) }) {
    fun linked() = store.get("vk").optString("token").isNotBlank()
    /** Runtime-only access to the existing encrypted login. Never persist in a server profile. */
    internal fun runtimeToken(): String {
        val session=store.get("vk")
        val token=session.optString("token")
        check(token.isNotBlank() && token.length<=8192 && token.none(Char::isISOControl) &&
            Regex("[1-9][0-9]{0,31}").matches(session.optString("user")) &&
            session.optLong("expires")>System.currentTimeMillis()+60_000) {
            "Откройте VK Turn и подтвердите вход VK."
        }
        return token
    }
    fun defaultSource() = runCatching { VkRelayProtocol.HashSource.valueOf(store.get("vkSettings").optString("source","MANUAL")) }.getOrDefault(VkRelayProtocol.HashSource.MANUAL)
    fun setDefaultSource(source: VkRelayProtocol.HashSource) = store.update("vkSettings") { it.put("source",source.name) }
    fun defaultCount() = store.get("vkSettings").optInt("callCount",6)
    fun setDefaultCount(count: Int) { VkRelayProtocol.count(count,"qwdtt");if(defaultCount()==count)return;store.update("vkSettings") { it.put("callCount",count) } }
    fun manualDefaults(): List<String> = store.get("vkSettings").optJSONArray("manual")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList()
    fun supplementAll()=store.get("vkSettings").optBoolean("manualSupplementAll",false)
    fun setSupplementAll(enabled:Boolean)=store.update("vkSettings"){it.put("manualSupplementAll",enabled)}
    data class ManualGeneration(val group:String,val calls:List<String>,val requested:Int,val problem:String?=null)
    /** Persist each successful provider reply in the existing owner-bound store.
     * A partial result is recoverable after closing the editor or API failure. */
    fun generatedManual(count:Int,cancelled:()->Boolean={false}):ManualGeneration {
        val id="manual:"+java.util.UUID.randomUUID().toString()
        return try {ManualGeneration(id,callsFor(id,"qwdtt",count,cancelled),count)}
        catch(error:Exception) {
            val partial=pendingManual().firstOrNull {it.group==id}
            if(partial==null){
                acknowledgeManual(listOf(id))
                throw IllegalArgumentException(error.message?:"Не удалось создать звонки VK",error)
            }
            partial.copy(problem=error.message?:"Создание остановлено")
        }
    }
    fun pendingManual():List<ManualGeneration> {
        val owner=store.get("vk").optString("user")
        if(owner.isEmpty())return emptyList()
        val groups=store.get("vkCallGroups")
        return groups.keys().asSequence().filter {it.startsWith("manual:")}.mapNotNull {id->
            val group=groups.optJSONObject(id)?:return@mapNotNull null
            if(group.optString("owner")!=owner||group.optString("kind")!="qwdtt")return@mapNotNull null
            val calls=group.optJSONArray("hashes")?:return@mapNotNull null
            if(calls.length()==0)return@mapNotNull null
            ManualGeneration(id,(0 until calls.length()).map(calls::getString),group.optInt("count",calls.length()))
        }.toList()
    }
    /** Only acknowledge delivery after the receiving editor accepted the list. */
    fun acknowledgeManual(groups:List<String>) {
        val owner=store.get("vk").optString("user")
        store.update("vkCallGroups") {saved->groups.forEach {id->
            if(id.startsWith("manual:")&&saved.optJSONObject(id)?.optString("owner")==owner)saved.remove(id)
        }}
    }
    /** Explicitly generated calls remain in the manual library even if the
     * receiving server editor is subsequently closed without saving. */
    fun preserveGenerated(calls:List<String>) {
        store.update("vkSettings") {settings->
            val old=settings.optJSONArray("manual")
            val existing=if(old==null)emptyList()else(0 until old.length()).map(old::getString)
            settings.put("manual",JSONArray(VkRelayProtocol.calls(VkHashPool.append(existing,calls),"qwdtt")))
        }
    }
    fun setManualDefaults(calls: List<String>) {
        val values = if(calls.isEmpty()) emptyList() else VkRelayProtocol.calls(calls,"qwdtt")
        store.update("vkSettings") { it.put("manual",JSONArray(values)) }
    }
    fun link(auth: VkRelayProtocol.Authorization) {
        require(auth.token.isNotBlank() && auth.token.length<=8192 && auth.token.none { it.isISOControl() })
        require(Regex("[1-9][0-9]{0,31}").matches(auth.user)) { "VK не подтвердил идентификатор аккаунта" }
        store.update("vk") { s ->
            if(s.optString("user") != auth.user) s.remove("hashes")
            s.put("token",auth.token).put("user",auth.user).put("expires",if(auth.expiresIn == 0L) Long.MAX_VALUE else System.currentTimeMillis()+auth.expiresIn.coerceAtMost(31_536_000)*1000)
        }
    }
    fun unlink() { store.remove("vk");setDefaultSource(VkRelayProtocol.HashSource.MANUAL) }
    /** Manual input always wins and never performs a VK request. Six remains the legacy default. */
    fun resolve(manual: List<String>, automatic: Boolean, kind: String, cancelled: () -> Boolean = { false }): List<String> {
        return resolveFor("legacy-runtime",manual,automatic,kind,cancelled)
    }
    fun resolveFor(groupId: String,manual: List<String>,automatic: Boolean,kind: String,cancelled: () -> Boolean={false}): List<String> {
        return resolveCountFor(groupId,manual,automatic,kind,6,cancelled)
    }
    fun resolveCountFor(groupId: String,manual: List<String>,automatic: Boolean,kind: String,count: Int,cancelled: () -> Boolean={false}): List<String> {
        if(manual.isNotEmpty()) return VkRelayProtocol.calls(manual,kind)
        if(!automatic) return VkRelayProtocol.calls(manualDefaults(),kind)
        return callsFor(groupId,kind,count,cancelled)
    }
    /** Compatibility entry point; deployment and runtime MUST use their explicit operation/connection scope. */
    fun sixCalls(kind: String, cancelled: () -> Boolean = { false }): List<String> = sixCallsFor("legacy-runtime",kind,cancelled)
    /** Probe-process path: getters only. Never authorize, refresh, create calls or change any section. */
    fun cachedSixFor(groupId: String,kind: String): List<String> = cachedFor(groupId,kind,6)
    fun cachedFor(groupId: String,kind: String,count: Int): List<String> {
        VkRelayProtocol.count(count,kind)
        require(Regex("[A-Za-z0-9_.:-]{1,128}").matches(groupId)) { "Некорректный идентификатор группы звонков" }
        require(kind=="qwdtt") { "Автоматическая проверка поддерживает только сохранённую группу QWDTT" }
        val session=store.get("vk")
        check(session.optString("token").isNotBlank() && Regex("[1-9][0-9]{0,31}").matches(session.optString("user"))) { "Сначала включите подключение и привяжите VK; фоновая проверка не создаёт звонки" }
        val group=store.get("vkCallGroups").optJSONObject(groupId)
            ?: error("Сначала включите подключение, чтобы создать его звонки; фоновая проверка не создаёт звонки")
        check(group.optString("owner")==session.optString("user") && group.optString("kind")==kind) { "Сохранённая группа относится к другому аккаунту или протоколу; сначала включите подключение" }
        val a=group.optJSONArray("hashes")
        check(a!=null && a.length()==count && group.optInt("count",6)==count) { "Сначала включите подключение и завершите создание выбранного числа звонков; фоновая проверка не дополняет группу" }
        val calls=VkRelayProtocol.calls((0 until count).map(a::getString),kind)
        val latest=store.get("vk")
        check(latest.optString("token")==session.optString("token") && latest.optString("user")==session.optString("user")) { "Аккаунт VK изменился во время проверки; повторите после подключения" }
        return calls
    }
    fun sixCallsFor(groupId: String,kind: String,cancelled: () -> Boolean={false}): List<String> = callsFor(groupId,kind,6,cancelled)
    fun callsFor(groupId: String,kind: String,count: Int,cancelled: () -> Boolean={false}): List<String> = synchronized(groupLock) {
        VkRelayProtocol.count(count,kind)
        require(Regex("[A-Za-z0-9_.:-]{1,128}").matches(groupId)) { "Некорректный идентификатор группы звонков" }
        require(kind in listOf("qwdtt","wdtt","csqtt"))
        val session=store.get("vk"); val token=session.optString("token")
        check(token.isNotBlank()) { "Откройте VK и подтвердите вход" }
        check(Regex("[1-9][0-9]{0,31}").matches(session.optString("user"))) { "Перепривяжите VK: аккаунт не подтвердил идентификатор владельца звонков" }
        val groups=store.get("vkCallGroups")
        val group=groups.optJSONObject(groupId)
        if(group!=null) check(group.optString("owner")==session.optString("user") && group.optString("kind")==kind) { "Группа звонков относится к другому аккаунту или протоколу; создайте новый деплой" }
        if(group!=null) check(group.optInt("count",6)==count) { "Количество звонков изменилось; создайте новую группу" }
        val cached=group?.optJSONArray("hashes") ?: JSONArray()
        if(cached.length()==count) return@synchronized VkRelayProtocol.calls((0 until count).map(cached::getString),kind)
        check(session.optLong("expires") > System.currentTimeMillis()+60_000) { "Откройте VK и подтвердите вход" }
        check(session.optLong("retryAfter") <= System.currentTimeMillis()) { "VK ограничил запросы; используйте ручные ссылки или повторите позже" }
        check(!cancelled() && !Thread.currentThread().isInterrupted) { "Создание звонков остановлено" }
        if(group==null) {
            require(groups.length()<256) { "Достигнут лимит сохранённых групп звонков" }
            store.update("vkCallGroups") { it.put(groupId,JSONObject().put("owner",session.optString("user")).put("kind",kind).put("count",count).put("createdAt",System.currentTimeMillis()).put("hashes",JSONArray())) }
        }
        val hashes=(0 until cached.length()).map(cached::getString).toMutableList()
        while(hashes.size < count) {
            check(!cancelled() && !Thread.currentThread().isInterrupted) { "Создание звонков остановлено" }
            if(hashes.isNotEmpty()) pause(2000)
            check(!cancelled() && !Thread.currentThread().isInterrupted) { "Создание звонков остановлено" }
            val first=callRequest?.invoke(token) ?: request(token)
            val json=if(callRequest==null)VkApiChallenge.continueOnce(first,{challenge->
                store.verifyCaptcha(challenge.url,cancelled)
            }) {fields->
                check(!cancelled()&&!Thread.currentThread().isInterrupted) {"Создание звонков остановлено"}
                check(store.get("vk").optString("token")==token) {"Аккаунт VK изменился; создание звонков остановлено"}
                request(token,fields)
            }else first
            json.optJSONObject("error")?.let { error ->
                val code=error.optInt("error_code")
                store.update("vk") { latest -> if(latest.optString("token")==token) {
                    if(code in listOf(4,5,27,28)) latest.put("expires",0)
                    if(code in listOf(6,9,14,29)) latest.put("retryAfter",System.currentTimeMillis()+3_600_000)
                } }
                throw IllegalStateException("VK не создал звонок: код $code. Запрос не повторялся")
            }
            val response=json.optJSONObject("response") ?: error("VK не вернул звонок")
            val hash=VkRelayProtocol.hash(response.optString("join_link"),kind)
            check(hash !in hashes) { "VK вернул повторный звонок; создание остановлено" };hashes.add(hash)
            val otherGroups=store.get("vkCallGroups")
            check(otherGroups.keys().asSequence().filter { it!=groupId }.none { id ->
                val a=otherGroups.getJSONObject(id).getJSONArray("hashes");(0 until a.length()).any { a.getString(it)==hash }
            }) { "VK вернул звонок другой группы; создание остановлено без повторного запроса" }
            store.update("vkCallGroups") { latest ->
                check(store.get("vk").optString("token")==token) { "Аккаунт VK изменился во время создания звонков" }
                val current=latest.getJSONObject(groupId)
                check(current.optString("owner")==session.optString("user")) { "Аккаунт группы звонков изменился" }
                current.put("hashes",JSONArray(hashes))
            }
        }
        // CSQTT/WDTT compatibility is validated, never substitute ok_join_link/call_id lifecycle.
        VkRelayProtocol.calls(hashes.take(count),kind)
    }
    private fun request(token: String,continuation:Map<String,String> = emptyMap()): JSONObject {
        val connection=URL("https://api.vk.ru/method/calls.start").openConnection() as HttpURLConnection
        connection.requestMethod="POST";connection.connectTimeout=15000;connection.readTimeout=20000;connection.instanceFollowRedirects=false;connection.doOutput=true
        connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
        try {
            val fields=mapOf("access_token" to token,"v" to "5.199")+continuation
            val body=fields.entries.joinToString("&"){URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8")}
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode==200) { "VK: HTTP ${connection.responseCode}; запрос не повторялся" }
            val bytes=connection.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { val n=input.read(buffer);if(n<0) break;check(out.size()+n<=1_048_576);out.write(buffer,0,n) };out.toByteArray()
            }
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    companion object { private val groupLock=Any() }
}


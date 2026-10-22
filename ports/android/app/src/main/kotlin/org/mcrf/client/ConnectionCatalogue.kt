package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Pure transaction model. The v1 schema remains compatible with existing encrypted catalogues. */
class ConnectionCatalogue(source: String) {
    private val data = if (source.isEmpty()) JSONObject().put("version", 1)
        .put("servers", JSONArray()).put("subscriptions", JSONArray()) else JSONObject(source)
    init {
        require(data.getInt("version") == 1) { "Неизвестный формат каталога." }
        if (!data.has("subscriptions")) data.put("subscriptions", JSONArray())
        data.getJSONArray("servers")
        val subscriptions = data.getJSONArray("subscriptions")
        require(subscriptions.length() <= 256) { "Слишком много подписок." }
        val ids = hashSetOf<String>(); val urls = hashSetOf<String>()
        repeat(subscriptions.length()) { i ->
            val sub = subscriptions.getJSONObject(i)
            sub.getJSONArray("servers")
            require(sub.getString("id").isNotEmpty() && ids.add(sub.getString("id")) &&
                urls.add(SubscriptionClient.address(sub.getString("url")).toString())) { "Некорректный каталог подписок." }
        }
        entries()
        data.optJSONObject("warpAccounts")?.let {accounts ->require(accounts.length()<=128);accounts.keys().forEach {id ->require(entries().any {it.id==id&&WarpProfile.isWarp(it)});WarpProfile.account(accounts.getString(id))}}
        if(data.has("warpCommitReceipts"))validateWarpReceipts(data.getJSONObject("warpCommitReceipts"))
    }
    fun serialize(): String = data.toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES) { "Каталог подключений больше 4 МБ." }
    }
    fun entries(): List<ServerImport.Server> {
        val result = linkedMapOf<String, ServerImport.Server>()
        fun append(links: JSONArray) = repeat(links.length()) { i ->
            ServerImport.read(links.getString(i)).forEach { result.putIfAbsent(it.id, ConnectionEdits.apply(it, data.optJSONObject("edits")?.optJSONObject(it.id))) }
        }
        append(data.getJSONArray("servers"))
        val subscriptions = data.getJSONArray("subscriptions")
        repeat(subscriptions.length()) { append(subscriptions.getJSONObject(it).getJSONArray("servers")) }
        return result.values.toList()
    }
    fun subscriptions(): List<JSONObject> {
        val list = data.getJSONArray("subscriptions")
        return (0 until list.length()).map { JSONObject(list.getJSONObject(it).toString()) }
    }
    /** Called against the fresh commit candidate, after every fetch succeeds. No resurrection of removed subscriptions. */
    fun refreshFetched(downloaded: Map<String, SubscriptionClient.Downloaded>, clearOnSuccess: Boolean,
        expectedIds: Map<String, String>? = null): SubscriptionChanges {
        var result = SubscriptionChanges()
        val present = subscriptions().associate { it.getString("url") to it.getString("id") }
        downloaded.forEach { (url, fetched) ->
            if (present.containsKey(url) && (expectedIds == null || expectedIds[url] == present[url]))
                result += replaceSubscription(url, fetched.servers, fetched.metadata, clearOnSuccess)
        }
        return result
    }
    fun add(text: String): Int {
        val imported = ServerImport.read(text)
        val ids = entries().mapTo(hashSetOf()) { it.id }
        var count = 0
        imported.forEach { if (ids.add(it.id)) { data.getJSONArray("servers").put(it.original); count++ } }
        return count
    }
    @JvmOverloads fun replaceSubscription(address: String, imported: List<ServerImport.Server>, metadata: JSONObject? = null, clearOnSuccess: Boolean = true): SubscriptionChanges {
        val url = SubscriptionClient.address(address).toString()
        require(imported.isNotEmpty()) { "В подписке нет подключений. Старые данные сохранены." }
        val subscriptions = data.getJSONArray("subscriptions")
        val index = (0 until subscriptions.length()).firstOrNull { subscriptions.getJSONObject(it).getString("url") == url }
        val previous = index?.let { subscriptions.getJSONObject(it) }
        val sub = previous?.let { JSONObject(it.toString()) } ?: JSONObject().put("id", UUID.randomUUID().toString())
        val before = previous?.getJSONArray("servers")?.let { links -> (0 until links.length()).flatMap { ServerImport.read(links.getString(it)) } }.orEmpty()
        val after = merged(before, imported, clearOnSuccess)
        val changes = SubscriptionChanges.between(before, after)
        sub.put("url", url).put("servers", JSONArray(after.map { it.original })).put("updatedAt", System.currentTimeMillis())
        if (metadata != null) sub.put("metadata", JSONObject(metadata.toString()))
        if (index == null) {
            require(subscriptions.length() < 256) { "Слишком много подписок." }
            subscriptions.put(sub)
        } else subscriptions.put(index, sub)
        preserveRenamedChoices(before, after)
        pruneSelection()
        return changes
    }
    private fun merged(before: List<ServerImport.Server>, imported: List<ServerImport.Server>, clear: Boolean): List<ServerImport.Server> {
        if (clear) return imported.distinctBy { it.id }
        val newKeys = imported.map { if(it.original.contains("://")) it.original.substringBefore('#') else it.original }.toSet()
        return (before.filter { (if(it.original.contains("://")) it.original.substringBefore('#') else it.original) !in newKeys } + imported).distinctBy { it.id }
    }
    private fun preserveRenamedChoices(before: List<ServerImport.Server>, after: List<ServerImport.Server>) {
        fun key(s: ServerImport.Server) = if(s.original.contains("://")) s.original.substringBefore('#') else s.original
        val old = before.groupBy(::key); val new = after.groupBy(::key)
        val selected = data.optJSONArray("autoServers")?.let { values -> (0 until values.length()).map { values.getString(it) }.toMutableSet() } ?: mutableSetOf()
        old.forEach { (key, values) ->
            val next = new[key].orEmpty()
            if(values.size == 1 && next.size == 1 && values[0].id != next[0].id) {
                val from = values[0].id; val to = next[0].id
                if(from in selected) selected.add(to)
                listOf("edits", "probes").forEach { section -> data.optJSONObject(section)?.let { if(it.has(from) && !it.has(to)) it.put(to,it.get(from)) } }
            }
        }
        data.put("autoServers", JSONArray(selected))
    }
    fun remove(id: String) {
        fun removeFrom(links: JSONArray) {
            for (i in links.length() - 1 downTo 0) if (ServerImport.read(links.getString(i)).any { it.id == id }) links.remove(i)
        }
        removeFrom(data.getJSONArray("servers"))
        val subscriptions = data.getJSONArray("subscriptions")
        repeat(subscriptions.length()) { removeFrom(subscriptions.getJSONObject(it).getJSONArray("servers")) }
        pruneSelection()
    }
    fun removeSubscription(id: String) {
        val list = data.getJSONArray("subscriptions")
        for (i in list.length() - 1 downTo 0) if (list.getJSONObject(i).getString("id") == id) list.remove(i)
        pruneSelection()
    }
    @JvmOverloads fun editSubscription(id: String, address: String, imported: List<ServerImport.Server>, metadata: JSONObject? = null, clearOnSuccess: Boolean = true): SubscriptionChanges {
        val url = SubscriptionClient.address(address).toString()
        require(imported.isNotEmpty()) { "В подписке нет подключений. Старые данные сохранены." }
        val list = data.getJSONArray("subscriptions")
        val index = (0 until list.length()).firstOrNull { list.getJSONObject(it).getString("id") == id }
            ?: throw IllegalArgumentException("Подписка уже удалена.")
        require((0 until list.length()).none { it != index && list.getJSONObject(it).getString("url") == url }) { "Эта подписка уже добавлена." }
        val old = list.getJSONObject(index)
        val links = old.getJSONArray("servers")
        val before = (0 until links.length()).flatMap { ServerImport.read(links.getString(it)) }
        val after = merged(before, imported, clearOnSuccess)
        val changed = JSONObject(old.toString()).put("url", url)
            .put("servers", JSONArray(after.map { it.original })).put("updatedAt", System.currentTimeMillis())
        if (metadata != null) changed.put("metadata", JSONObject(metadata.toString()))
        list.put(index, changed)
        preserveRenamedChoices(before, after)
        pruneSelection()
        return SubscriptionChanges.between(before, after)
    }
    fun autoIds(): Set<String> {
        val list = data.optJSONArray("autoServers") ?: return emptySet()
        val existing = entries().mapTo(hashSetOf()) { it.id }
        return (0 until list.length()).map { list.getString(it) }.filterTo(linkedSetOf()) { it in existing }
    }
    fun setAuto(id: String, selected: Boolean) {
        require(entries().any { it.id == id && it.configuration == null && (!it.needsEngine() || it.carrier != null) }) { "Этот сервер пока не доступен для автовыбора." }
        val pool = autoIds().toMutableSet()
        if (selected) pool.add(id) else pool.remove(id)
        data.put("autoServers", JSONArray(pool))
    }
    fun probes(): JSONObject = JSONObject((data.optJSONObject("probes") ?: JSONObject()).toString())
    fun clearProbes(){data.remove("probes")}
    fun edited(id: String) = data.optJSONObject("edits")?.has(id) == true
    fun edit(id: String, options: JSONObject?) {
        val entry = entries().firstOrNull { it.id == id } ?: throw IllegalArgumentException("Подключение уже удалено.")
        if (options != null) ConnectionEdits.apply(entry, options)
        val edits = data.optJSONObject("edits") ?: JSONObject()
        if (options == null) edits.remove(id) else edits.put(id, JSONObject(options.toString()))
        data.put("edits", edits)
        data.put("probes", probes().apply { remove(id) })
    }
    fun recordProbe(id: String, delay: Long, now: Long) {
        require(entries().any { it.id == id }) { "Проверяемый сервер уже удалён." }
        val probes = probes().put(id, JSONObject().put("delay", delay).put("at", now))
        data.put("probes", probes)
    }
    fun warpAccount(id:String):String?=data.optJSONObject("warpAccounts")?.optString(id)?.takeIf {it.isNotEmpty()}
    /** Historical commit proof contains IDs only; it is not evidence that a deleted profile still exists. */
    fun warpReceipt(id:String):JSONObject? {
        validateReceiptId(id)
        return data.optJSONObject("warpCommitReceipts")?.optJSONObject(id)?.let {JSONObject(it.toString())}
    }
    /** One transaction preserves the account even when the endpoint changes. */
    @JvmOverloads fun saveWarp(rawAccount:String,config:String,previousId:String?=null,receiptId:String?=null):String {
        val receipts=data.optJSONObject("warpCommitReceipts") ?: JSONObject()
        if(receiptId!=null) { validateReceiptId(receiptId);require(!receipts.has(receiptId)){"Операция WARP уже зафиксирована."} }
        val sequence=if(receiptId!=null) {
            val last=receipts.keys().asSequence().map {receipts.getJSONObject(it).getLong("sequence")}.maxOrNull() ?: 0L
            require(last<Long.MAX_VALUE){"Счётчик операций WARP исчерпан."};last+1
        } else 0L
        val account=WarpProfile.account(rawAccount);val entry=WarpProfile.prepare(config,account.toString())
        val previous=previousId?.let {id ->entries().firstOrNull {it.id==id&&WarpProfile.isWarp(it)} ?: throw IllegalArgumentException("Профиль WARP уже удалён.")}
        if(previous!=null)require(previous.endpoint!!.getString("private_key")==account.getString("private_key")){"Нельзя заменить ключ существующего WARP."}
        val wasAutoSelected=previous?.id?.let {it in autoIds()} == true
        val accounts=data.optJSONObject("warpAccounts") ?: JSONObject()
        if(previous!=null){remove(previous.id);accounts.remove(previous.id)}
        require(accounts.length()<128||accounts.has(entry.id)){"Допускается до 128 аккаунтов WARP."}
        add(entry.original);accounts.put(entry.id,account.toString());data.put("warpAccounts",accounts)
        // Transfer the explicit pool choice inside the same encrypted catalogue commit.
        // remove() prunes the previous endpoint's probe; a rescan must earn a fresh measurement.
        if(wasAutoSelected)setAuto(entry.id,true)
        if(previous!=null)data.put("probes",probes().apply {remove(entry.id)})
        if(receiptId!=null) {
            while(receipts.length()>=64) {
                val oldest=receipts.keys().asSequence().minByOrNull {receipts.getJSONObject(it).getLong("sequence")}!!
                receipts.remove(oldest)
            }
            val proof=JSONObject().put("newId",entry.id).put("sequence",sequence)
            if(previousId!=null)proof.put("previousId",previousId)
            receipts.put(receiptId,proof);data.put("warpCommitReceipts",receipts)
        }
        return entry.id
    }
    private fun validateReceiptId(id:String) {
        require(id.length==36 && try {UUID.fromString(id).toString()==id} catch(_:IllegalArgumentException){false}) {"Некорректный идентификатор операции WARP."}
    }
    private fun validateWarpReceipts(receipts:JSONObject) {
        require(receipts.length()<=64){"Слишком много квитанций WARP."}
        val sequences=hashSetOf<Long>()
        receipts.keys().forEach {id ->
            validateReceiptId(id)
            val proof=receipts.getJSONObject(id)
            require(proof.keys().asSequence().all {it in setOf("previousId","newId","sequence")}) {"Неизвестные поля квитанции WARP."}
            fun profileId(key:String) {val value=proof.get(key);require(value is String && value.matches(Regex("[0-9a-f]{64}"))) {"Некорректный ID профиля в квитанции WARP."}}
            profileId("newId");if(proof.has("previousId"))profileId("previousId")
            val sequence=proof.get("sequence")
            require((sequence is Int || sequence is Long) && (sequence as Number).toLong()>0 && sequences.add(sequence.toLong())) {"Некорректный порядок квитанций WARP."}
        }
    }
    private fun pruneSelection() {
        val existing = entries().mapTo(hashSetOf()) { it.id }
        data.put("autoServers", JSONArray(autoIds()))
        val probes = probes()
        probes.keys().asSequence().toList().filter { it !in existing }.forEach { probes.remove(it) }
        data.put("probes", probes)
        data.optJSONObject("edits")?.let { edits -> edits.keys().asSequence().toList().filter { it !in existing }.forEach(edits::remove) }
        data.optJSONObject("warpAccounts")?.let {accounts ->accounts.keys().asSequence().toList().filter {it !in existing}.forEach(accounts::remove)}
    }
}

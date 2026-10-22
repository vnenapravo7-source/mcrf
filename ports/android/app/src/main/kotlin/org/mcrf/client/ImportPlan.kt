package org.mcrf.client

/** Nothing is written while links are parsed, subscriptions fetched or engines installed. */
class ImportPlan private constructor(
    val manual: String, val subscriptions: Map<String, SubscriptionClient.Downloaded>,
    val entries: List<ServerImport.Server>,
    private val versionEdits:Map<String,org.json.JSONObject> = emptyMap()
) {
    val engines: Set<String> = entries.filter { it.needsEngine() }.mapTo(linkedSetOf()) { it.carrier?.let(EngineModules::carrierKind)?:it.protocol }
    fun commit(catalogue: Connections) = catalogue.importFetched(manual, subscriptions,versionEdits)
    fun resolveCsqtt(choices:Map<String,String>,existing:Map<String,ServerImport.Server> = emptyMap()):ImportPlan {
        if(CsqttImportResolution.unresolved(entries,existing,choices).isNotEmpty())throw CsqttImportResolution.Required()
        val edits=linkedMapOf<String,org.json.JSONObject>()
        val resolved=entries.map {entry->
            existing[entry.id]?:if(CsqttImportResolution.ambiguous(entry)){
                CsqttImportResolution.resolve(entry,choices[entry.id].orEmpty()).also {edits[entry.id]=org.json.JSONObject().put("csqttVersion",choices.getValue(entry.id))}
            }else entry
        }
        return ImportPlan(manual,subscriptions,resolved,edits)
    }
    companion object {
        fun prepare(value: String, fetch: (String) -> SubscriptionClient.Downloaded): ImportPlan {
            require(value.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES) { "Импорт больше 4 МБ." }
            val source = CompatibilityImportLinks.unwrapDocument(value.trim())
            HappCompatibility.rejectOpaque(source)
            require(!CompatibilityImportEncrypted.isEncrypted(source)) { "Для шифрованной ссылки MCRF нужен пароль." }
            val structured = source.startsWith('{') || source.startsWith('[')
            val downloaded = linkedMapOf<String, SubscriptionClient.Downloaded>()
            val manual = if (structured) source else buildString {
                source.lines().forEach { raw -> val line = CompatibilityImportLinks.unwrap(raw.trim())
                    if (SubscriptionClient.isAddress(line)) {
                        val url = SubscriptionClient.address(line).toString()
                        if (url !in downloaded) downloaded[url] = fetch(url)
                    } else append(line).append('\n')
                }
            }
            val entries = (if (manual.isBlank()) emptyList() else ServerImport.read(manual)) + downloaded.values.flatMap { it.servers }
            require(entries.isNotEmpty()) { "В импорте нет подключений." }
            // A placeholder is not an implemented transport. Never silently accept it.
            require(entries.none { it.needsEngine() && it.carrier == null }) { "Формат этого дополнительного движка ещё переносится. Подключение не добавлено." }
            return ImportPlan(manual, downloaded, entries.distinctBy { it.id })
        }
        fun prepareEncrypted(value: String, password: CharArray, fetch: (String) -> SubscriptionClient.Downloaded): ImportPlan =
            prepare(CompatibilityImportEncrypted.decrypt(value, password), fetch)
        fun prepareNew(value: String, fetch: (String) -> SubscriptionClient.Downloaded, defaults: (org.json.JSONObject) -> org.json.JSONObject): ImportPlan =
            prepare(CompatibilityImportQwdtt.applyNew(value, defaults), fetch)
    }
}

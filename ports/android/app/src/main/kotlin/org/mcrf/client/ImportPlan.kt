package org.mcrf.client

/** Nothing is written while links are parsed, subscriptions fetched or engines installed. */
class ImportPlan private constructor(
    val manual: String, val subscriptions: Map<String, SubscriptionClient.Downloaded>,
    val entries: List<ServerImport.Server>,
    private val versionEdits:Map<String,org.json.JSONObject> = emptyMap(),
    private val newOnlyEdits:Map<String,org.json.JSONObject> = emptyMap()
) {
    val engines: Set<String> = entries.filter { it.needsEngine() }.mapTo(linkedSetOf()) { it.carrier?.let(EngineModules::carrierKind)?:it.protocol }
    fun commit(catalogue: Connections) = catalogue.importFetched(manual, subscriptions,versionEdits,newOnlyEdits)
    /** Subscription URLs bypass the manual source transformer. Keep provider IDs/links,
     * and persist a local RAW default only for entries newly added by this transaction. */
    fun withNewQwdttDefaults(existing:Set<String>):ImportPlan {
        val edits=newOnlyEdits.toMutableMap()
        val prepared=entries.map {entry->
            if(entry.protocol!="qwdtt"||entry.id in existing)return@map entry
            val source=CompatibilityImportQwdtt.applyNew(entry.original){it}
            if(source==entry.original)return@map entry
            val parsed=ServerImport.read(source).single()
            val options=org.json.JSONObject().put("carrier",requireNotNull(parsed.carrier))
            edits[entry.id]=options
            ConnectionEdits.apply(entry,options)
        }
        return ImportPlan(manual,subscriptions,prepared,versionEdits,edits)
    }
    fun resolveCsqtt(choices:Map<String,String>,existing:Map<String,ServerImport.Server> = emptyMap()):ImportPlan {
        if(CsqttImportResolution.unresolved(entries,existing,choices).isNotEmpty())throw CsqttImportResolution.Required()
        val edits=linkedMapOf<String,org.json.JSONObject>()
        val resolved=entries.map {entry->
            existing[entry.id]?:if(CsqttImportResolution.ambiguous(entry)){
                CsqttImportResolution.resolve(entry,choices[entry.id].orEmpty()).also {edits[entry.id]=org.json.JSONObject().put("csqttVersion",choices.getValue(entry.id))}
            }else entry
        }
        return ImportPlan(manual,subscriptions,resolved,edits,newOnlyEdits)
    }
    companion object {
        fun combine(plans:List<ImportPlan>):ImportPlan {
            require(plans.isNotEmpty())
            return ImportPlan(plans.joinToString("\n"){it.manual},plans.flatMap {it.subscriptions.entries}.associate {it.toPair()},plans.flatMap {it.entries})
        }
        fun prepare(value: String, fetch: (String) -> SubscriptionClient.Downloaded): ImportPlan {
            require(value.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES) { "Импорт больше 4 МБ." }
            val source = CompatibilityImportLinks.unwrapDocument(value.trim())
            HappCompatibility.rejectOpaque(source)
            require(!CompatibilityImportEncrypted.isEncrypted(source)) { "Для шифрованной ссылки MCRF нужен пароль." }
            val structured = source.startsWith('{') || source.startsWith('[')
            val downloaded = linkedMapOf<String, SubscriptionClient.Downloaded>()
            val addresses = linkedSetOf<String>()
            val manual = if (structured) source else buildString {
                source.lines().forEach { raw -> val line = CompatibilityImportLinks.unwrap(raw.trim())
                    if (SubscriptionClient.isAddress(line)) {
                        val url = SubscriptionClient.address(line).toString()
                        addresses.add(url)
                    } else append(line).append('\n')
                }
            }
            require(addresses.size <= ConnectionCatalogue.MAX_SUBSCRIPTIONS) { "Слишком много подписок." }
            // Reject local errors before making any provider request.
            val local = if (manual.isBlank()) emptyList() else ServerImport.read(manual)
            val budget=SubscriptionPayloadBudget().apply {acceptManual(local)}
            addresses.forEach { url ->
                val response=fetch(url);budget.accept(url,response);downloaded[url]=response
            }
            val entries = local + downloaded.values.flatMap { it.servers }
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

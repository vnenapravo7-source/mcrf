package org.mcrf.client

/** Measurement scope is independent of the marked automatic-selection pool. */
object ProbePlan {
    fun supports(entry: ServerImport.Server) = entry.configuration == null && (!entry.needsEngine() || entry.carrier != null)
    fun all(entries: List<ServerImport.Server>) = entries.filter(::supports)
    fun automatic(entries: List<ServerImport.Server>, policy: String): List<ServerImport.Server> {
        require(policy in setOf(AutoSelection.LATENCY,AutoSelection.BS_ENGINES))
        // Policy limits selection, never the initial measurement of the catalogue.
        return all(entries)
    }
}

/** A completed result, including failure, is counted once for its requested server ID. */
class ProbeProgress(ids: Set<String>, results: Map<String, Long> = emptyMap()) {
    val ids:Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(ids))
    val results:Map<String,Long> = java.util.Collections.unmodifiableMap(LinkedHashMap(results))
    init {require(this.results.keys.all {it in this.ids}&&this.results.values.all {it>=-1}) {"Некорректный результат проверки серверов."}}
    val total get() = ids.size
    val completed get() = results.size
    val passed get() = results.values.count { it >= 0 }
    fun record(id: String, delay: Long): ProbeProgress {
        require(id in ids) { "Получен результат для неизвестного сервера." }
        require(delay>=-1) {"Некорректная задержка сервера."}
        return ProbeProgress(ids, results + (id to delay))
    }
    fun label() = "Проверено: $completed из $total · отвечают: $passed"
}

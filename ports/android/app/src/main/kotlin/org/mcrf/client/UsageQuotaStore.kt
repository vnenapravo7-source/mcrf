package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in encrypted aggregate ledger, separate from short-lived connection recording consent.
 * At most 32 rules, 1024 daily dimension rows, 93 days and 1 MiB. No payload/URL/IP is stored.
 * Native totals and optional attributed byte deltas are separate views, never summed twice. */
class UsageQuotaStore(private val storage: LocalDiagnosticStorage, private val clock: () -> Long = System::currentTimeMillis,
                      private val lock: Any = Any()) {
    data class Hit(val rule: UsageQuotaPolicy.Rule, val window: UsageQuotaPolicy.Window, val used: Long)
    private var cached: JSONObject? = null
    private var dirty = false
    private var savedAt = 0L
    private fun <T> transaction(change: () -> T): T {
        val before=root().toString();val wasDirty=dirty;val previousSavedAt=savedAt
        try {return change()} catch(error:Exception) {cached=JSONObject(before);dirty=wasDirty;savedAt=previousSavedAt;throw error}
    }
    private fun root(): JSONObject = cached ?: storage.read().let { raw ->
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        (if (raw.isBlank()) JSONObject().put("version", 1).put("enabled", false).put("rules", JSONArray())
            .put("rows", JSONArray()).put("counters", JSONObject()).put("claims", JSONObject()).put("cost", JSONObject())
        else JSONObject(raw).also {
            require(it.getInt("version") == 1 && it.getJSONArray("rules").length() <= 32 && it.getJSONArray("rows").length() <= MAX_ROWS)
            require(it.getJSONObject("counters").length() <= 32 && it.getJSONObject("claims").length() <= 32)
        }).also { cached = it }
    }
    fun enabled(): Boolean = synchronized(lock) { val doc=root();prune(clock());flush();doc.getBoolean("enabled") }
    fun accountingState():Pair<Boolean,String> = synchronized(lock) {enabled() to root().optString("accountingEpoch", "")}
    fun setEnabled(value: Boolean) = synchronized(lock) { transaction {
        if(root().getBoolean("enabled")!=value)root().put("accountingEpoch",java.util.UUID.randomUUID().toString())
        root().put("enabled", value); dirty = true; flush(true)
    } }
    fun rules(): List<UsageQuotaPolicy.Rule> = synchronized(lock) {
        val rows = root().getJSONArray("rules"); (0 until rows.length()).map { decodeRule(rows.getJSONObject(it)) }
    }
    fun configure(rules: List<UsageQuotaPolicy.Rule>) = synchronized(lock) {
        require(rules.size <= 32 && rules.map { it.id }.distinct().size == rules.size)
        transaction {
            val old=this.rules().associateBy {it.id}
            for(key in listOf("counters","claims")) {
                val values=root().getJSONObject(key)
                values.keys().asSequence().toList().forEach {id->if(rules.none {it.id==id&&it==old[id]})values.remove(id)}
            }
            root().put("rules", JSONArray(rules.map(::encodeRule)))
            root().put("countedSince", clock()); dirty = true; flush(true)
        }
    }
    fun record(delta: UsageQuotaPolicy.Delta, expectedEpoch:String?=null) = synchronized(lock) {
        if (!enabled()) return@synchronized
        if(expectedEpoch!=null && expectedEpoch!=root().optString("accountingEpoch",""))return@synchronized
        val now = clock(); require(delta.at in (now - 60_000)..now)
        if(now<root().optLong("lastAccountingAt",0))return@synchronized
        root().put("lastAccountingAt",now)
        prune(now)
        if(!delta.attributedOnly){root().put("nativeCounterObserved",true);dirty=true}
        val dimensions = if(delta.bytes==0L) emptyList() else if (delta.attributedOnly) listOf(UsageQuotaPolicy.Scope.APP, UsageQuotaPolicy.Scope.DOMAIN)
            else listOf(UsageQuotaPolicy.Scope.TOTAL, UsageQuotaPolicy.Scope.SERVER, UsageQuotaPolicy.Scope.SUBSCRIPTION)
        dimensions.forEach { scope ->
            var rows = root().getJSONArray("rows"); val day = delta.at / UsageQuotaPolicy.DAY
            val subject = delta.subject(scope)
            var row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull {
                it.getLong("day") == day && it.getString("scope") == scope.name && it.getString("subject") == subject
            }
            if(row==null&&scope==UsageQuotaPolicy.Scope.TOTAL&&rows.length()>=MAX_ROWS) {
                val evict=(0 until rows.length()).firstOrNull {rows.getJSONObject(it).getString("scope")!=UsageQuotaPolicy.Scope.TOTAL.name}
                if(evict!=null) {rows.remove(evict);root().put("breakdownTruncated",true)}
            }
            if (row == null && rows.length() < MAX_ROWS) {
                row = JSONObject().put("day", day).put("scope", scope.name).put("subject", subject).put("bytes", 0L); rows.put(row)
            }
            // Per-rule counters below remain exact if high-cardinality breakdown rows fill up.
            if (row == null) root().put("breakdownTruncated", true)
            else row.put("bytes", UsageQuotaPolicy.add(row.getLong("bytes"), delta.bytes))
        }
        rules().forEach { rule ->
            val window = rule.window(now)
            if (delta.at >= window.start && delta.at < window.end && delta.counts(rule)) {
                val counter = counter(rule, window)
                counter.put("bytes", UsageQuotaPolicy.add(counter.getLong("bytes"), delta.bytes)).put("observed",true)
            }
        }
        dirty = true; flush()
    }
    private fun counter(rule: UsageQuotaPolicy.Rule, window: UsageQuotaPolicy.Window): JSONObject {
        val all = root().getJSONObject("counters")
        val old = all.optJSONObject(rule.id)
        if (old != null && old.getLong("start") == window.start) return old
        return JSONObject().put("start", window.start).put("bytes", 0L).also { all.put(rule.id, it) }
    }
    fun hits(): List<Hit> = synchronized(lock) {
        if (!enabled()) return@synchronized emptyList()
        val now = clock()
        if(now<root().optLong("lastAccountingAt",0))return@synchronized emptyList()
        rules().mapNotNull { rule ->
            val window = rule.window(now); val used = counter(rule, window).getLong("bytes")
            if (now in window.start until window.end && used >= rule.bytes) Hit(rule, window, used) else null
        }
    }
    fun usage(rule:UsageQuotaPolicy.Rule):Long? = synchronized(lock) {
        val value=counter(rule,rule.window(clock()))
        if(value.optBoolean("observed"))value.getLong("bytes")else null
    }
    /** Persist admission before a switch or warning. One action per rule/window survives process death. */
    fun claim(hit: Hit): Boolean = synchronized(lock) {
        if (!enabled() || hits().none { it.rule == hit.rule && it.window == hit.window }) return@synchronized false
        val claims = root().getJSONObject("claims")
        if (claims.optJSONObject(hit.rule.id)?.optLong("start", -1) == hit.window.start) return@synchronized false
        transaction {claims.put(hit.rule.id, JSONObject().put("start", hit.window.start));dirty = true; flush(true); true}
    }
    fun measured(action: BatteryCostReport.Action, wall: Long, cpu: Long) = synchronized(lock) {
        if (!enabled()) return@synchronized
        prune(clock())
        val cost = root().getJSONObject("cost"); val key = "${clock() / UsageQuotaPolicy.DAY}:${action.name}"
        val old = cost.optJSONObject(key)
        val next = BatteryCostReport.Measurement(old?.optLong("count") ?: 0, old?.optLong("wall") ?: 0, old?.optLong("cpu") ?: 0).plus(wall, cpu)
        cost.put(key, JSONObject().put("count", next.count).put("wall", next.wallMs).put("cpu", next.cpuMs))
        dirty = true; flush()
    }
    fun admitBackgroundUpdate(interval: Long): Boolean = synchronized(lock) {
        val now = clock(); val old = root().optLong("backgroundUpdateAt", 0)
        if (interval > 0 && old > 0 && (now < old || now - old < interval)) return@synchronized false
        transaction { root().put("backgroundUpdateAt", now); dirty = true; flush(true) }
        true
    }
    fun admitSwitch(current: String, target: String, manual: Boolean): Boolean = synchronized(lock) {
        if(manual||current==target||target.isBlank())return@synchronized false
        val now=clock();val doc=root();val last=doc.optLong("switchAt",0)
        if(last>0&&(now<last||now-last<120_000))return@synchronized false
        val visited=doc.optJSONObject("visited")?:JSONObject().also {doc.put("visited",it)}
        visited.keys().asSequence().toList().forEach {key->val at=visited.getLong(key);if(now>=at&&now-at>=30*60_000)visited.remove(key)}
        val needed=(if(visited.has(current))0 else 1)+(if(visited.has(target))0 else 1)
        if(visited.has(target)||visited.length()+needed>128)return@synchronized false
        transaction {visited.put(current,now).put(target,now);doc.put("switchAt",now);dirty=true;flush(true);true}
    }
    fun batteryRows():List<Pair<BatteryCostReport.Action,BatteryCostReport.Measurement>> = synchronized(lock) {
        prune(clock());flush()
        val cost=root().getJSONObject("cost")
        BatteryCostReport.Action.entries.map {action->
            var count=0L;var wall=0L;var cpu=0L
            cost.keys().forEach {key->if(key.endsWith(":${action.name}")){
                val row=cost.getJSONObject(key);count=UsageQuotaPolicy.add(count,row.getLong("count"))
                wall=UsageQuotaPolicy.add(wall,row.getLong("wall"));cpu=UsageQuotaPolicy.add(cpu,row.getLong("cpu"))
            }}
            action to BatteryCostReport.Measurement(count,wall,cpu)
        }
    }
    fun batteryReport():String=batteryRows().joinToString("\n\n") {(action,value)->BatteryCostReport.description(action,value)}
    fun report(): String = synchronized(lock) {
        prune(clock()); flush()
        val doc = root()
        val rows = doc.getJSONArray("rows")
        val sums = linkedMapOf<String, Long>()
        repeat(rows.length()) { val row = rows.getJSONObject(it); val key = "${row.getString("scope")}: ${row.getString("subject")}"; sums[key] = UsageQuotaPolicy.add(sums[key] ?: 0, row.getLong("bytes")) }
        val total = sums["TOTAL: unknown"] ?: 0
        // Totals and independently sourced metadata cannot be reconciled without segment identity.
        for (scope in listOf("APP", "DOMAIN")) {
            sums["$scope: unknown"] = total
        }
        buildString {
            append("Локальные байты MCRF; хранение до 93 дней. "); append(if (enabled()) "Учёт включён.\n" else "Учёт выключен.\n")
            if(!doc.optBoolean("nativeCounterObserved"))append("Общий byte counter ещё не получен: объём неизвестен.\n")
            append("Квоты считают новые измерения с включения/изменения правила; прошлый трафик не восстанавливается.\n")
            append("APP/DOMAIN: только байтовые метаданные; текущий SPI не поставляет их, unknown — неизвестно, не ноль.\n")
            append("Атрибутированные метаданные — отдельный источник; они не вычитаются из unknown и не прибавляются к общим байтам.\n")
            val priority=listOf("TOTAL: unknown","APP: unknown","DOMAIN: unknown")
            priority.forEach {key->append("$key: ${if(doc.optBoolean("nativeCounterObserved"))TrafficState.bytes(sums[key]?:0)else "нет измерения"}\n")}
            sums.entries.filter {it.key !in priority}.take(77).forEach { append("${it.key}: ${TrafficState.bytes(it.value)}\n") }
            if (doc.optBoolean("breakdownTruncated")) append("Детализация ограничена; счётчики квот сохранены.\n")
            val cost = doc.getJSONObject("cost")
            BatteryCostReport.Action.values().forEach { action ->
                var count = 0L; var wall = 0L; var cpu = 0L
                cost.keys().forEach { key -> if (key.endsWith(":${action.name}")) { val row = cost.getJSONObject(key); count = UsageQuotaPolicy.add(count, row.getLong("count")); wall = UsageQuotaPolicy.add(wall, row.getLong("wall")); cpu = UsageQuotaPolicy.add(cpu, row.getLong("cpu")) } }
                append(BatteryCostReport.description(action, BatteryCostReport.Measurement(count, wall, cpu))); append('\n')
            }
            append(BatteryCostReport.LIMITATION)
        }
    }
    fun flush(force: Boolean = false) = synchronized(lock) {
        val now = clock()
        if (dirty && (force || now < savedAt || now - savedAt >= 60_000)) {
            val encoded = root().toString(); require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            storage.write(encoded)
            check(storage.read()==encoded) {"Не удалось сохранить счётчики трафика."}
            dirty = false; savedAt = now
        }
    }
    fun delete() = synchronized(lock) { storage.delete(); cached = null; dirty = false; savedAt = 0 }
    private fun prune(observedNow: Long) {
        val doc = root(); val now=maxOf(observedNow,doc.optLong("lastAccountingAt",0))
        val rows = doc.getJSONArray("rows"); val kept = JSONArray()
        val oldest=(now-UsageQuotaPolicy.RETENTION+UsageQuotaPolicy.DAY-1)/UsageQuotaPolicy.DAY
        repeat(rows.length()) { val row = rows.getJSONObject(it); if (row.getLong("day") in oldest..(now / UsageQuotaPolicy.DAY)) kept.put(row) }
        if (kept.length() != rows.length()) { doc.put("rows", kept); dirty = true }
        val cost = doc.getJSONObject("cost")
        cost.keys().asSequence().toList().forEach { key -> if (key.substringBefore(':').toLong() !in oldest..(now / UsageQuotaPolicy.DAY)) { cost.remove(key); dirty = true } }
        for(key in listOf("counters","claims")) {
            val values=doc.getJSONObject(key)
            values.keys().asSequence().toList().forEach {id->if(values.getJSONObject(id).getLong("start")<now-UsageQuotaPolicy.RETENTION){values.remove(id);dirty=true}}
        }
        doc.optJSONObject("visited")?.let {visited->
            visited.keys().asSequence().toList().forEach {key->val at=visited.getLong(key);if(now>=at&&now-at>=30*60_000){visited.remove(key);dirty=true}}
        }
    }
    companion object {
        const val MAX_ROWS = 1024
        const val MAX_BYTES = 1024 * 1024
        private var instance: UsageQuotaStore? = null
        private var instancePath = ""
        fun local(context: Context): UsageQuotaStore = synchronized(ProfileStore.storageLock) {
            val app = context.applicationContext; val path = app.filesDir.canonicalPath
            if (instance == null || instancePath != path) { instancePath = path; instance = UsageQuotaStore(ChangeHistoryStore.encrypted(app, "usage-policy.enc"), lock = ProfileStore.storageLock) }
            requireNotNull(instance)
        }
        fun encodeRule(r: UsageQuotaPolicy.Rule): JSONObject = JSONObject().put("id", r.id).put("bytes", r.bytes).put("period", r.period.name)
            .put("action", r.action.name).put("scope", r.scope.name).put("subject", r.subject).put("start", r.customStart).put("end", r.customEnd).put("zone", r.zone)
        fun decodeRule(r: JSONObject) = UsageQuotaPolicy.Rule(r.getString("id"), r.getLong("bytes"), UsageQuotaPolicy.Period.valueOf(r.getString("period")),
            UsageQuotaPolicy.Action.valueOf(r.getString("action")), UsageQuotaPolicy.Scope.valueOf(r.getString("scope")), r.getString("subject"), r.getLong("start"), r.getLong("end"), r.getString("zone"))
    }
}

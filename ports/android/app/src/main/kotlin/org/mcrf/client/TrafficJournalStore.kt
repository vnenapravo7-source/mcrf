package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Metadata supplied by the existing platform callback. No payload capture or VPN ownership. */
class TrafficJournalStore(
    private val storage: LocalDiagnosticStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxCount: Int = MAX_COUNT,
    private val maxBytes: Int = MAX_BYTES,
    private val retentionMs: Long = RETENTION_MS,
    private val lock: Any = Any(),
    private val accountBytes: ((Entry) -> Unit)? = null
) {
    enum class Protocol { TCP, UDP, ICMP, OTHER }
    enum class Route { DIRECT, VPN, BLOCK, UNKNOWN }
    enum class Result { ALLOWED, BLOCKED, FAILED, UNKNOWN }
    enum class DomainSource { UNKNOWN, METADATA, DNS, SNI, HTTP_HOST, DNS_ANSWER }
    data class Observation(val timestamp: Long, val ip: String, val port: Int? = null,
                           val uid: Int? = null, val app: String? = null,
                           val protocol: Protocol = Protocol.OTHER, val route: Route = Route.UNKNOWN,
                           val result: Result = Result.UNKNOWN, val domain: String? = null,
                           val event: String = "connection", val session: String? = null,
                           val packages: List<String> = emptyList(), val bytes: Long? = null,
                           val domainSource: DomainSource = DomainSource.METADATA)
    data class Entry(val timestamp: Long, val uid: Int?, val app: String?, val domain: String?,
                     val ip: String, val port: Int?, val protocol: Protocol, val route: Route, val result: Result,
                     val event: String = "connection", val session: String? = null,
                     val packages: List<String> = emptyList(), val bytes: Long? = null,
                     val domainSource: DomainSource = DomainSource.UNKNOWN) {
        val attribution: String get() = "UID: ${uid?.toString() ?: "неизвестен"}; приложение: ${app ?: packages.takeIf { it.isNotEmpty() }?.joinToString() ?: "неизвестно"}; домен: ${domain ?: "неизвестен"}"
    }
    private data class State(val enabled: Boolean, val entries: List<Entry>)
    private data class Dns(val name: String, val at: Long, val expires: Long)
    private val dns = LinkedHashMap<Pair<Int?, String>, List<Dns>>()
    init { require(maxCount in 1..MAX_COUNT && maxBytes in 128..MAX_BYTES && retentionMs in 1..RETENTION_MS) }

    fun enabled(): Boolean = synchronized(lock) { load().enabled }
    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        val state = load()
        if (state.enabled != enabled) save(State(enabled, retain(state.entries, clock())))
        if (!enabled) dns.clear()
    }
    /** DNS answers are held only in a bounded memory cache and are never independently persisted.
     * Hook must pass the observed UID; ambiguous IP mappings are shown as unknown domain. */
    fun correlateDns(ip: String, domain: String, uid: Int?, observedAt: Long, ttlMs: Long = DNS_TTL_MS): Boolean = synchronized(lock) {
        if (!load().enabled) return@synchronized false
        val now = clock()
        require(validIp(ip) && validDomain(domain) && (uid == null || uid >= 0) && ttlMs in 1..DNS_TTL_MS)
        require(observedAt in (now - DNS_TTL_MS)..now)
        if(uid==null)return@synchronized false // No global/IP-only attribution across applications.
        pruneDns(now)
        val key = uid to canonicalIp(ip)
        val name = domain.lowercase(Locale.ROOT).trimEnd('.')
        dns[key] = dns[key].orEmpty().filter { it.name != name }.takeLast(7) + Dns(name, observedAt, observedAt + ttlMs)
        while (dns.size > MAX_DNS) dns.remove(dns.keys.first())
        true
    }
    /** Thread-safe synchronous ingestion: opt-out returns before validation, mutation or disk writes. */
    fun ingest(observation: Observation): Boolean = synchronized(lock) {
        val state = load()
        if (!state.enabled) return@synchronized false
        val now = clock()
        require(observation.timestamp in (now - retentionMs)..now && validIp(observation.ip))
        require(observation.port == null || observation.port in 1..65535)
        require(observation.uid == null || observation.uid >= 0)
        require(observation.bytes == null || observation.bytes >= 0)
        require(observation.domain == null || validDomain(observation.domain))
        require(observation.event in setOf("connection", "dns-answer"))
        require(observation.session == null || observation.session.matches(Regex("[a-f0-9-]{36}")))
        require(observation.packages.size <= 64 && observation.packages.all { it.length <= 255 && PACKAGE.matches(it) })
        require(observation.packages.isEmpty() || observation.uid != null)
        pruneDns(now)
        val ip = canonicalIp(observation.ip)
        val matches = dns[observation.uid to ip].orEmpty().filter {
            observation.timestamp >= it.at && observation.timestamp < it.expires
        }.map { it.name }.distinct()
        val app = observation.app?.takeIf { observation.uid != null && it.length <= 255 && PACKAGE.matches(it) }
        val domain = observation.domain?.lowercase(Locale.ROOT)?.trimEnd('.') ?: matches.singleOrNull()
        val source = when {
            domain == null -> DomainSource.UNKNOWN
            observation.event == "dns-answer" -> DomainSource.DNS_ANSWER
            observation.domain == null -> DomainSource.DNS
            else -> observation.domainSource
        }
        val entry = Entry(observation.timestamp, observation.uid, app, domain, ip,
            observation.port, observation.protocol, observation.route, observation.result, observation.event, observation.session,
            observation.packages.distinct(), observation.bytes, source)
        val entries = retain(state.entries + entry, now)
        if (encode(State(true, listOf(entry))).toByteArray(Charsets.UTF_8).size > maxBytes) return@synchronized false
        save(State(true, entries))
        // bytes must be a newly observed byte delta, not a cumulative flow counter.
        // DNS metadata is never traffic volume. Journal and quota consent are independent.
        if(entry.event=="connection"&&entry.bytes!=null)runCatching {accountBytes?.invoke(entry)}
        true
    }
    fun entries(): List<Entry> = synchronized(lock) {
        val state = load()
        val retained = retain(state.entries, clock())
        if (retained != state.entries) save(State(state.enabled, retained))
        retained.asReversed()
    }
    /** Detached metadata only. Suggestions must not prune/write the journal or enable recording. */
    fun metadataSnapshot(): List<Entry> = synchronized(lock) {
        val state=load()
        if(!state.enabled) emptyList() else retain(state.entries,clock()).map {it.copy(packages=it.packages.toList())}.asReversed()
    }
    /** Manual action only: returns metadata to the caller; never uploads or sends it to event logs. */
    fun export(): String = synchronized(lock) {
        JSONObject().put("version", 1).put("privacy", PRIVACY).put("entries", JSONArray().apply {
            entries().forEach { entry -> put(json(entry).put("uidAttribution", if(entry.uid == null) UNKNOWN_UID else "known")
                .put("appAttribution", if(entry.app == null && entry.packages.isEmpty()) UNKNOWN_APP else "known")
                .put("domainAttribution", if(entry.domain == null) UNKNOWN_DOMAIN else when(entry.domainSource) {
                    DomainSource.DNS_ANSWER -> "DNS-answer-unattributed"
                    DomainSource.DNS -> "DNS-correlated"
                    else -> entry.domainSource.name
                })) }
        }).toString(2)
    }
    /** Deletion also revokes consent and discards DNS correlations. No empty file is recreated. */
    fun delete() = synchronized(lock) { storage.delete(); dns.clear() }
    fun clearDnsCorrelations() = synchronized(lock) { dns.clear() }
    private fun pruneDns(now: Long) {
        val iterator = dns.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            item.setValue(item.value.filter { it.at <= now && it.expires > now })
            if (item.value.isEmpty()) iterator.remove()
        }
    }
    private fun retain(values: List<Entry>, now: Long): List<Entry> {
        val retained = values.filter { it.timestamp in (now - retentionMs)..now }.takeLast(maxCount).toMutableList()
        while (retained.isNotEmpty() && encode(State(true, retained)).toByteArray(Charsets.UTF_8).size > maxBytes) retained.removeAt(0)
        return retained
    }
    private fun save(state: State) {
        val entries=state.entries.toMutableList()
        while(entries.isNotEmpty() && encode(State(state.enabled,entries)).toByteArray(Charsets.UTF_8).size > maxBytes) entries.removeAt(0)
        val raw=encode(State(state.enabled,entries))
        require(raw.toByteArray(Charsets.UTF_8).size <= maxBytes)
        storage.write(raw)
    }
    private fun load(): State {
        val raw = storage.read()
        if (raw.isEmpty()) return State(false, emptyList())
        require(raw.toByteArray(Charsets.UTF_8).size <= maxBytes)
        return try { decode(raw) } catch (error: org.json.JSONException) {
            throw IllegalStateException("Повреждён локальный журнал. Исходные данные сохранены.", error)
        }
    }
    private fun decode(raw: String): State {
        val doc = JSONObject(raw)
        require(doc.getInt("version") == 1)
        val rows = doc.getJSONArray("entries")
        require(rows.length() <= maxCount)
        val entries = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Entry(row.getLong("timestamp"), if(row.isNull("uid")) null else row.getInt("uid"),
                if(row.isNull("app")) null else row.getString("app"), if(row.isNull("domain")) null else row.getString("domain"),
                row.getString("ip"), if(row.isNull("port")) null else row.getInt("port"),
                Protocol.valueOf(row.getString("protocol")), Route.valueOf(row.getString("route")), Result.valueOf(row.getString("result")),
                row.optString("event", "connection"), if(row.isNull("session")) null else row.getString("session"),
                row.optJSONArray("packages")?.let { values -> require(values.length()<=64); (0 until values.length()).map { values.getString(it) } }.orEmpty(),
                if(row.isNull("bytes")) null else row.getLong("bytes"),
                if(row.has("domainSource")) DomainSource.valueOf(row.getString("domainSource")) else when {
                    row.isNull("domain") -> DomainSource.UNKNOWN
                    row.optString("event") == "dns-answer" -> DomainSource.DNS_ANSWER
                    else -> DomainSource.METADATA
                })
                .also { entry ->
                    require(validIp(entry.ip) && (entry.uid == null || entry.uid >= 0) && (entry.port == null || entry.port in 1..65535))
                    require(entry.bytes == null || entry.bytes >= 0)
                    require(entry.app == null || entry.uid != null && entry.app.length <= 255 && PACKAGE.matches(entry.app))
                    require(entry.domain == null || validDomain(entry.domain))
                    require(entry.event in setOf("connection", "dns-answer"))
                    require(entry.session == null || entry.session.matches(Regex("[a-f0-9-]{36}")))
                    require(entry.packages.isEmpty() || entry.uid != null)
                    require(entry.packages.all { it.length <= 255 && PACKAGE.matches(it) })
                }
        }
        return State(doc.getBoolean("enabled"), entries)
    }
    private fun encode(state: State): String = JSONObject().put("version", 1).put("enabled", state.enabled)
        .put("entries", JSONArray().apply { state.entries.forEach { put(json(it)) } }).toString()
    private fun json(entry: Entry) = JSONObject().put("timestamp", entry.timestamp).put("uid", entry.uid ?: JSONObject.NULL)
        .put("app", entry.app ?: JSONObject.NULL).put("domain", entry.domain ?: JSONObject.NULL).put("ip", entry.ip)
        .put("port", entry.port ?: JSONObject.NULL).put("protocol", entry.protocol.name).put("route", entry.route.name).put("result", entry.result.name)
        .put("event", entry.event).put("session", entry.session ?: JSONObject.NULL)
        .put("packages", JSONArray(entry.packages))
        .put("bytes", entry.bytes ?: JSONObject.NULL).put("domainSource", entry.domainSource.name)
    companion object {
        const val MAX_COUNT = 1000
        const val MAX_BYTES = 512 * 1024
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
        internal const val DNS_TTL_MS = 5_000L
        private const val MAX_DNS = 256
        const val UNKNOWN_UID = "unknown UID"
        const val UNKNOWN_APP = "unknown app"
        const val UNKNOWN_DOMAIN = "unknown domain"
        const val PRIVACY = TrafficCapturePolicy.PRIVACY
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        private fun validDomain(value: String) = value.length in 1..253 && value.trimEnd('.').split('.').all {
            it.length in 1..63 && it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
        }
        // Validate first: getByName is safe here only for a numeric IPv6 literal.
        // Android DNS answers and engine callbacks may spell the same IPv6 differently.
        private fun canonicalIp(value: String): String = if (':' in value)
            requireNotNull(java.net.InetAddress.getByName(value).hostAddress).lowercase(Locale.ROOT)
        else value.split('.').joinToString(".") { it.toInt().toString() }
        // Numeric literals only; never resolve an untrusted hostname while ingesting metadata.
        private fun validIp(value: String): Boolean {
            if (value.length !in 2..45) return false
            if (':' !in value) return value.split('.').let { it.size == 4 && it.all { part -> part.matches(Regex("[0-9]{1,3}")) && part.toInt() in 0..255 } }
            if (!value.matches(Regex("[0-9a-fA-F:]+")) || ":::" in value) return false
            val halves = value.split("::")
            if (halves.size > 2) return false
            if (halves.any { it.isNotEmpty() && (it.startsWith(':') || it.endsWith(':')) }) return false
            val groups = halves.filter(String::isNotEmpty).flatMap { it.split(':') }
            return groups.all { it.length in 1..4 } && if(halves.size == 2) groups.size < 8 else groups.size == 8
        }
        // One production instance keeps the bounded DNS correlations shared across hook callers.
        private val instances = mutableMapOf<String, TrafficJournalStore>()
        fun local(context: Context): TrafficJournalStore = synchronized(ProfileStore.storageLock) {
            val app = context.applicationContext
            instances.getOrPut(app.filesDir.canonicalPath) {
                TrafficJournalStore(ChangeHistoryStore.encrypted(app, "traffic-journal.enc"), lock = ProfileStore.storageLock,
                    accountBytes = { entry -> UsageQuotaStore.local(app).record(UsageQuotaPolicy.Delta(entry.timestamp,
                        requireNotNull(entry.bytes), app = entry.app ?: UsageQuotaPolicy.UNKNOWN,
                        domain = entry.domain ?: UsageQuotaPolicy.UNKNOWN, attributedOnly = true)) })
            }
        }
    }
}

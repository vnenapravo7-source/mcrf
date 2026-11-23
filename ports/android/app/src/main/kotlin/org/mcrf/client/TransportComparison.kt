package org.mcrf.client

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Saved profiles are candidates, never edited to invent a server-side transport. */
object TransportComparisonPlan {
    private fun normalizeHost(raw:String):String {
        require(raw.length in 1..254&&raw==raw.trim()&&!raw.endsWith(".."))
        val name=java.net.IDN.toASCII(raw.removeSuffix("."),java.net.IDN.USE_STD3_ASCII_RULES).lowercase(java.util.Locale.ROOT)
        require(name.length in 1..253&&name.split('.').all {it.length in 1..63&&it.first()!='-'&&it.last()!='-'})
        return name
    }
    const val MAX_PROFILES = 8
    const val REPEATS = 3
    const val BUDGET_MS = 120_000L
    const val SERVER_BUDGET_MS = 30_000L
    const val ENDPOINT = "https://www.gstatic.com/generate_204"
    fun host(entry: ServerImport.Server): String = RouteRules.literal(entry.host)?.hostAddress
        ?: normalizeHost(entry.host)
    fun supported(entry: ServerImport.Server): Boolean = entry.carrier == null && !entry.needsEngine() &&
        entry.configuration == null && entry.endpoint == null && entry.outbound != null && ProbePlan.supports(entry) && entry.port in 1..65535 &&
        entry.protocol in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "https", "anytls", "naive", "ssh", "hysteria", "hysteria2", "tuic") &&
        entry.outbound.optString("type") in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "anytls", "naive", "ssh", "hysteria", "hysteria2", "tuic") &&
        entry.outbound.optInt("server_port") == entry.port && runCatching {
            val actual = entry.outbound.getString("server")
            (RouteRules.literal(actual)?.hostAddress ?: normalizeHost(actual)) == host(entry)
        }.getOrDefault(false)
    fun candidates(server: ServerImport.Server, entries: List<ServerImport.Server>): List<ServerImport.Server> = entries.filter {
        supported(it) && runCatching { host(it) == host(server) }.getOrDefault(false)
    }.distinctBy { it.id }
    /** Scope first, so VPN comparison can never hide or test BS entries. */
    fun groups(entries: List<ServerImport.Server>, whitelists: Boolean): List<List<ServerImport.Server>> =
        ConnectionSections.entries(entries, whitelists).filter(::supported).distinctBy { it.id }
            .mapNotNull { entry -> runCatching { host(entry) to entry }.getOrNull() }
            .groupBy({ it.first }, { it.second }).values.filter { it.size > 1 }
    /** A/B is meaningful only when the same address has at least two variants. */
    fun diagnosticGroups(entries: List<ServerImport.Server>, whitelists: Boolean): List<List<ServerImport.Server>> =
        ConnectionSections.entries(entries, whitelists).distinctBy { it.id }
            .mapNotNull { entry -> runCatching { host(entry) to entry }.getOrNull() }
            .groupBy({it.first},{it.second}).filterKeys { !it.startsWith("invalid:") }.values.filter { it.size > 1 }

    fun unavailableReason(entry: ServerImport.Server): String? = when {
        entry.carrier != null -> "Carrier-профиль: изолированный A/B провайдер не допускает дополнительные движки."
        entry.needsEngine() -> "Для этого профиля нужен отдельный диагностический движок."
        entry.configuration != null -> "Полная конфигурация не поддерживает изолированный замер одного сервера."
        entry.endpoint != null -> "Endpoint-профиль не поддерживается изолированным A/B провайдером."
        !supported(entry) -> "Протокол, адрес или порт не прошли проверку изолированного A/B."
        else -> null
    }

    /** Country is metadata from an explicit, valid flag, never inferred from a hostname. */
    fun countryLabel(group: List<ServerImport.Server>): String {
        val countries = java.util.Locale.getISOCountries().toSet()
        val codes = group.flatMap { entry ->
            Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}").findAll(entry.name).mapNotNull { match ->
                val points = match.value.codePoints().toArray()
                val code = points.map { ('A'.code + it - 0x1F1E6).toChar() }.joinToString("")
                code.takeIf { it in countries }
            }.toList()
        }.distinct()
        if (codes.size != 1) return "🌐 Страна не указана"
        val code = codes.single()
        val flag = code.map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("")
        val country=java.util.Locale.Builder().setRegion(code).build()
        return "$flag ${country.getDisplayCountry(java.util.Locale.forLanguageTag("ru"))}"
    }

    fun groupTitle(group: List<ServerImport.Server>): String {
        val address=runCatching {host(group.first())}.getOrDefault(group.first().host)
        val source=ClientEvents.redact(group.first().name).replace(Regex("[\\r\\n\\t]")," ")
        val flag=Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}").find(source)?.value
        val plain=source.replace(Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}"),"").trim()
        val cut=Regex("(?i)\\s+\\d{2}\\.\\d{2}|\\s+(?:VLESS|VMESS|TROJAN|HYSTERIA|SS)\\b").find(plain)?.range?.first
        val name=plain.substring(0,cut?:plain.length).trim().take(32).ifBlank {"Сервер"}
        return listOfNotNull(flag,name,address).joinToString(" ").replace(" $address"," · $address")
    }

    fun hiddenRowsScroll(count: Int) = count > 6
    fun groupLabel(group:List<ServerImport.Server>):String {
        val name=ClientEvents.redact(group.first().name).replace(Regex("[\\r\\n\\t]")," ")
        val short=name.substringBefore(Regex("(?i)\\s+\\d{2}\\.\\d{2}|\\s+(?:VLESS|VMESS|TROJAN|HYSTERIA|SS)\\b").find(name)?.value ?: "\u0000")
        return "${short.take(48)} · ${group.size}"
    }
    fun hideExceptWinner(report: TransportComparisonReport, profiles: List<ServerImport.Server>): Set<String> {
        val slot = report.suggestedSlot ?: return emptySet()
        if (!report.complete || report.exhausted || profiles.size !in 2..MAX_PROFILES ||
            profiles.map { it.id }.distinct().size != profiles.size ||
            report.results.any { it.attempted != REPEATS } || report.results.size != profiles.size ||
            report.results.map { it.slot } != (1..profiles.size).toList() || slot !in 1..profiles.size) return emptySet()
        // Untested variants and other hosts keep their visibility.
        return profiles.filterIndexed { index, _ -> index + 1 != slot }.mapTo(linkedSetOf()) { it.id }
    }
    fun validate(server: ServerImport.Server, profiles: List<ServerImport.Server>) {
        require(profiles.size in 2..MAX_PROFILES && profiles.map { it.id }.distinct().size == profiles.size) { "Выберите 2–8 разных профилей." }
        require(profiles.all { it.whitelists == server.whitelists }) { "Нельзя смешивать разделы VPN и БС." }
        require(profiles.all { supported(it) && host(it) == host(server) }) { "Профили должны принадлежать одному адресу сервера и поддерживать изолированный замер." }
    }
    fun label(entry: ServerImport.Server, slot: Int): String {
        // Imported names and transport strings may contain secrets. Only allowlisted labels.
        val protocol = entry.protocol.takeIf { it in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "https", "anytls", "naive", "ssh", "hysteria2", "tuic") } ?: "профиль"
        val transport = entry.outbound?.optJSONObject("transport")?.optString("type")
            ?.takeIf { it in setOf("ws", "http", "httpupgrade", "grpc", "quic") } ?: "native/TCP"
        return "Профиль $slot · $protocol · $transport"
    }
}

class TransportComparisonCancellation(private val parent: TransportComparisonCancellation? = null) : Closeable {
    private val cancelled = AtomicBoolean()
    fun check() {
        parent?.check()
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("TRANSPORT_COMPARISON_CANCELLED")
    }
    override fun close() { cancelled.set(true) }
}
fun interface TransportComparisonProvider {
    /** Finite provider call; cancellation drains this call without stopping another owner's probe. */
    fun measure(profile: ServerImport.Server, token: TransportComparisonCancellation): Long?
}
class TransportComparisonResult(val slot: Int, samples: List<Long?>) {
    val samples: List<Long?> = samples.toList()
    val successes get() = samples.count { it != null }
    val attempted get() = samples.size
    val medianMs: Long? get() = samples.filterNotNull().sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    val spreadMs: Long? get() = samples.filterNotNull().let { if (it.isEmpty()) null else it.maxOrNull()!! - it.minOrNull()!! }
    fun summary() = "Профиль $slot · успех $successes/$attempted · медиана ${medianMs?.toString() ?: "—"} мс · разброс ${spreadMs?.toString() ?: "—"} мс"
}
class TransportComparisonReport(val results: List<TransportComparisonResult>, val complete: Boolean, val exhausted: Boolean = false,
    val stopReason: String? = null, val currentSlot: Int? = null,
    val remainingMs: Long = TransportComparisonPlan.BUDGET_MS) {
    val completed get() = results.sumOf { it.attempted }
    val total get() = results.size * TransportComparisonPlan.REPEATS
    // A suggestion never writes; the UI offers a separate explicit list-visibility action.
    val suggestedSlot: Int? get() = if (!complete || exhausted) null else results.filter { it.successes == TransportComparisonPlan.REPEATS }
        .minWithOrNull(compareBy<TransportComparisonResult> { it.medianMs }.thenBy { it.spreadMs })?.slot
    fun summary() = results.joinToString("\n") { it.summary() } +
        "\n${if (complete) "Завершено" else if (exhausted) "Время вышло; результат неполный" else "Проверка…"}. Задержка HTTPS."
}
class TransportComparison(private val provider: TransportComparisonProvider,
    private val serverBudgetMs: Long, private val overallBudgetMs: Long, private val now: () -> Long) {
    constructor(provider: TransportComparisonProvider, now: () -> Long) :
        this(provider, TransportComparisonPlan.SERVER_BUDGET_MS, TransportComparisonPlan.BUDGET_MS, now)
    init { require(serverBudgetMs > 0 && overallBudgetMs > 0) }

    companion object {
        // A timed-out non-cooperative call retains this slot until it actually drains.
        // No unbounded queue, replacement threads or global probe STOP are allowed.
        private val providerBusy = AtomicBoolean()
    }

    private fun measure(profile: ServerImport.Server, token: TransportComparisonCancellation, remaining: () -> Long): Long? {
        token.check()
        check(providerBusy.compareAndSet(false, true)) { "TRANSPORT_COMPARISON_DRAINING" }
        val callToken = TransportComparisonCancellation(token)
        val result = CompletableFuture<Long?>()
        val worker = Thread({
            val outcome = runCatching { callToken.check(); provider.measure(profile, callToken) }
            providerBusy.set(false)
            outcome.fold({ result.complete(it) }, { result.completeExceptionally(it) })
        }, "mcrf-ab-provider").apply { isDaemon = true }
        try {
            try { worker.start() } catch (error: Throwable) { providerBusy.set(false); throw error }
            while (true) {
                token.check()
                val left = remaining()
                if (left <= 0) throw TimeoutException()
                try { return result.get(minOf(left, 50L), TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { if (remaining() <= 0) throw TimeoutException() }
                catch (error: ExecutionException) { throw error.cause ?: error }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("TRANSPORT_COMPARISON_CANCELLED")
        } finally {
            callToken.close()
            if (worker.isAlive) worker.interrupt()
            // Allow a cooperative timed-out call to drain before admitting the next
            // profile. A stuck call keeps the global lease; never spawn replacements.
            if (worker.isAlive && !Thread.currentThread().isInterrupted) worker.join(200)
        }
    }

    fun run(server: ServerImport.Server, profiles: List<ServerImport.Server>, token: TransportComparisonCancellation,
        progress: (TransportComparisonReport) -> Unit = {}): TransportComparisonReport {
        TransportComparisonPlan.validate(server, profiles)
        val started = now()
        val wallStarted = System.nanoTime()
        fun elapsed(start: Long, wall: Long) = maxOf((now() - start).coerceAtLeast(0), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wall))
        fun overallRemaining() = overallBudgetMs - elapsed(started, wallStarted)
        val spent = LongArray(profiles.size)
        val samples = profiles.map { mutableListOf<Long?>() }
        fun report(complete: Boolean, exhausted: Boolean = false, reason: String? = null, current: Int? = null) =
            TransportComparisonReport(samples.mapIndexed { index, values -> TransportComparisonResult(index + 1, values) },
                complete, exhausted, reason, current, overallRemaining().coerceAtLeast(0))
        // Alternate starting candidate across rounds to reduce temporal/order bias.
        repeat(TransportComparisonPlan.REPEATS) { round ->
            profiles.indices.forEach { offset ->
                token.check()
                if (overallRemaining() <= 0) return report(false, true, "Общее время сравнения истекло.")
                val index = (offset + round) % profiles.size
                progress(report(false, current = index + 1))
                if (spent[index] >= serverBudgetMs) {
                    samples[index] += null
                    progress(report(false))
                    return@forEach
                }
                val callStarted = now()
                val callWall = System.nanoTime()
                fun remaining() = minOf(overallRemaining(), serverBudgetMs - spent[index] - elapsed(callStarted, callWall))
                val value = try { measure(profiles[index], token, ::remaining) }
                catch (_: TimeoutException) {
                    token.check()
                    samples[index] += null
                    spent[index] = serverBudgetMs
                    progress(report(false))
                    if (overallRemaining() <= 0) return report(false, true, "Общее время сравнения истекло.")
                    if (providerBusy.get()) return report(false, true, "Время профиля ${index + 1} истекло. Движок завершает замер.")
                    return@forEach
                }
                token.check()
                spent[index] += elapsed(callStarted, callWall)
                if (overallRemaining() <= 0) {
                    samples[index] += null
                    return report(false, true, "Время замера истекло; поздний результат не принят.")
                }
                if (spent[index] >= serverBudgetMs) {
                    samples[index] += null
                    progress(report(false))
                    return@forEach
                }
                require(value == null || value in 0..60_000) { "TRANSPORT_COMPARISON_RESULT" }
                samples[index] += value
                progress(report(false))
            }
        }
        token.check()
        return report(true)
    }
}

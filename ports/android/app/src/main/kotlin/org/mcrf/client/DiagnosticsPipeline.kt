package org.mcrf.client

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

enum class DiagnosticPath { DIRECT, CURRENT_MCRF }
enum class DiagnosticFamily { AUTOMATIC, IPV4, IPV6 }
enum class DiagnosticState { WAITING, RUNNING, AVAILABLE, ERROR, UNAVAILABLE, CANCELLED }
enum class DiagnosticStage { ROUTE, DNS, CONNECT, TLS, HTTP }
enum class DiagnosticIssue {
    NONE, NO_DIRECT_NETWORK, NO_MCRF_PROXY, UNSUPPORTED_PROTOCOL, DNS_NOT_OBSERVED,
    NO_ADDRESS, DNS_FAILED, CONNECT_FAILED, TLS_FAILED, HTTP_REJECTED, TIMEOUT,
    CANCELLED, PATH_CHANGED, CAPACITY, BUDGET_EXHAUSTED, INTERNAL
}
enum class DiagnosticCheck { HTTPS, DNS, UNSUPPORTED }

enum class DiagnosticDnsScope { PLATFORM, CONTROL_DOH_THROUGH_PROXY }
enum class DiagnosticDnsFailure { CONTROL_TRANSPORT, CONTROL_RESPONSE, TARGET_NO_ADDRESS }
enum class DiagnosticProxyFailure { AUTHENTICATION, CONNECT_REJECTED, PROTOCOL, STOPPED }

/** Safe protocol category only: never retain native messages or credentials. */
internal class DiagnosticProxyException(val failure: DiagnosticProxyFailure) : java.io.IOException("DIAGNOSTIC_PROXY_${failure.name}")

/** Only public catalog keys and numeric evidence cross this boundary. No URL, IP,
 * response headers/body, native exception messages, profile IDs or credentials. */
data class DiagnosticRequest(
    val target: ServiceTestTarget,
    val path: DiagnosticPath,
    val family: DiagnosticFamily = DiagnosticFamily.AUTOMATIC
)

data class DiagnosticEvidence(
    val stage: DiagnosticStage,
    val state: DiagnosticState,
    val issue: DiagnosticIssue = DiagnosticIssue.NONE,
    val elapsedMs: Long? = null,
    val httpStatus: Int? = null,
    val ipv4Answers: Int? = null,
    val ipv6Answers: Int? = null,
    val dnsRcode: Int? = null
)

data class DiagnosticResult(
    val targetId: String,
    val path: DiagnosticPath,
    val family: DiagnosticFamily,
    val state: DiagnosticState,
    val issue: DiagnosticIssue = DiagnosticIssue.NONE,
    val elapsedMs: Long? = null,
    val evidence: List<DiagnosticEvidence> = emptyList(),
    val dnsScope: DiagnosticDnsScope? = null,
    val proxyFailure: DiagnosticProxyFailure? = null,
    val dnsFailure: DiagnosticDnsFailure? = null
) {
    init { require(targetId.matches(Regex("[a-z0-9][a-z0-9-]{0,95}"))) }
    companion object {
        fun of(request: DiagnosticRequest, state: DiagnosticState, issue: DiagnosticIssue = DiagnosticIssue.NONE) =
            DiagnosticResult(request.target.id, request.path, request.family, state, issue)
    }
}

/** A single immutable run is reusable by explanations, comparisons and trends.
 * runId is random, never a profile/server identity. Comparisons must verify their
 * own session identity and matching target/path/family before using samples. */
data class DiagnosticReport(val runId: String, val startedAtMs: Long, val results: List<DiagnosticResult>, val complete: Boolean = false)

/** Resource ownership survives cancellation races, including attach-after-cancel. */
class DiagnosticCancellation(timeoutMs: Long) {
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    private val closers = linkedSetOf<AutoCloseable>()
    @Volatile private var stopped = false
    init { require(timeoutMs in 1..900_000) }
    fun checkCancellation() {
        if (stopped || Thread.currentThread().isInterrupted) throw CancellationException()
    }
    fun check() {
        checkCancellation()
        if (System.nanoTime() >= deadline) throw java.net.SocketTimeoutException()
    }
    fun remainingMs(): Int {
        check()
        return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(1, 30_000).toInt()
    }
    fun attach(resource: AutoCloseable): AutoCloseable {
        synchronized(this) {
            if (!stopped) { closers.add(resource); return AutoCloseable { synchronized(this) { closers.remove(resource) }; Unit } }
        }
        runCatching { resource.close() }
        throw CancellationException()
    }
    fun cancel() {
        val pending = synchronized(this) { stopped = true; closers.toList().also { closers.clear() } }
        pending.forEach { runCatching { it.close() } }
    }
}

fun interface ServiceProbe {
    fun execute(request: DiagnosticRequest, cancellation: DiagnosticCancellation): DiagnosticResult
}

/** Child socket/DNS budgets don't consume all time reserved for later layers. */
internal fun <T> withDiagnosticBudget(parent: DiagnosticCancellation, timeoutMs: Long, action: (DiagnosticCancellation) -> T): T {
    parent.check()
    val child = DiagnosticCancellation(minOf(timeoutMs, parent.remainingMs().toLong()))
    val lease = parent.attach(AutoCloseable { child.cancel() })
    return try { action(child).also { parent.checkCancellation() } } finally { child.cancel(); lease.close() }
}

/** Network adapters return typed evidence. Exceptions never become UI/log text. */
class DiagnosticsPipeline(private val probe: ServiceProbe) : ServiceProbe {
    override fun execute(request: DiagnosticRequest, cancellation: DiagnosticCancellation): DiagnosticResult = try {
        cancellation.check()
        probe.execute(request, cancellation).also {
            require(it.targetId == request.target.id && it.path == request.path && it.family == request.family)
            require(it.state in setOf(DiagnosticState.AVAILABLE, DiagnosticState.ERROR, DiagnosticState.UNAVAILABLE, DiagnosticState.CANCELLED))
            cancellation.checkCancellation()
            if (it.state == DiagnosticState.AVAILABLE) cancellation.check()
        }
    } catch (_: CancellationException) {
        DiagnosticResult.of(request, DiagnosticState.CANCELLED, DiagnosticIssue.CANCELLED)
    } catch (_: java.net.SocketTimeoutException) {
        DiagnosticResult.of(request, DiagnosticState.ERROR, DiagnosticIssue.TIMEOUT)
    } catch (_: Exception) {
        DiagnosticResult.of(request, DiagnosticState.ERROR, DiagnosticIssue.INTERNAL)
    }
}

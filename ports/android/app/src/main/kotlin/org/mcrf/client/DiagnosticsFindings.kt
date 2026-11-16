package org.mcrf.client

enum class DiagnosticConfidence { OBSERVED, POSSIBLE, UNKNOWN }
enum class DiagnosticStep { NETWORK, CAPTIVE_DNS, ROUTE, ENGINE, SERVER, SERVICE }
enum class DiagnosticCause {
    NETWORK_ABSENT, PORTAL_REPORTED, INTERNET_UNCONFIRMED, DNS_FAILURE, NO_AAAA,
    SAVED_BLOCK_RULE, SAVED_DIRECT_RULE, ROUTE_NOT_OBSERVED, ENGINE_MISSING,
    ENGINE_NOT_RUNNING, SERVER_NOT_SELECTED, SERVER_NOT_ISOLATED, PROVIDER_UNSUPPORTED,
    PROBE_BUSY, VPN_ISOLATION_UNAVAILABLE, HTTP_POLICY, TLS_ERROR, CONNECTION_ERROR,
    TIMEOUT, PATH_CHANGED, SERVICE_RESPONDED, UNKNOWN, CANCELLED
}

/** All text is rendered from enums. No host, profile name/ID, URL or native error. */
data class DiagnosticFinding(
    val step: DiagnosticStep,
    val state: DiagnosticState,
    val cause: DiagnosticCause,
    val confidence: DiagnosticConfidence,
    val measurements: List<DiagnosticResult> = emptyList(),
    val ruleNumber: Int? = null,
    val serverMeasurement: ServerServiceMeasurement? = null
)
data class GuidedDiagnosticsReport(val targetId: String, val findings: List<DiagnosticFinding>, val complete: Boolean)
enum class DiagnosticRoutePolicy { BLOCK, DIRECT, PROXY, UNKNOWN }
data class DiagnosticRouteEvidence(val policy: DiagnosticRoutePolicy, val ruleNumber: Int? = null)
data class DiagnosticsEnvironment(
    val networkPresent: Boolean,
    val portal: Boolean,
    val validated: Boolean,
    val physicalIpv6Route: Boolean?,
    val engineInstalled: Boolean?,
    val engineRunning: Boolean,
    val serverSelected: Boolean,
    val ipv6Configured: Boolean?,
    val engineIpv6Capability: Boolean?,
    val route: DiagnosticRouteEvidence,
    val fallbackConfigured: Boolean
)

enum class ServerComparisonLimit {
    NONE, UNSUPPORTED_SERVICE, UNSUPPORTED_PROVIDER, ACTIVE_VPN_ISOLATION, BUSY, NETWORK, PATH_CHANGED, BUDGET, CANCELLED, PROVIDER_FAILURE,
    SAFE_MODE, INTERNET_UNCONFIRMED, PROXY_CONFIG, PROXY_VALIDATION, PROXY_START, PROXY_START_TIMEOUT,
    PROXY_EXITED, PROXY_AUTH, PROXY_CONNECT, PROXY_PROTOCOL, SINGBOX_MISSING, SINGBOX_UNVERIFIED
}

internal class DiagnosticsProxyStartupException(val limitation: ServerComparisonLimit) : java.io.IOException("DIAGNOSTIC_${limitation.name}")
data class ServerServiceMeasurement(val serverSlot: Int, val targetId: String, val state: DiagnosticState,
    val latencyMs: Long? = null, val limitation: ServerComparisonLimit = ServerComparisonLimit.NONE,
    val diagnostic: DiagnosticResult? = null) {
    init {
        require(serverSlot >= 1 && targetId.matches(Regex("[a-z0-9][a-z0-9-]{0,95}")))
        require(diagnostic == null || diagnostic.targetId == targetId && diagnostic.state == state && diagnostic.path == DiagnosticPath.CURRENT_MCRF)
    }
}
data class ServerComparisonReport(val measurements: List<ServerServiceMeasurement>, val complete: Boolean)

internal fun serverServiceMeasurement(slot: Int, result: DiagnosticResult): ServerServiceMeasurement =
    ServerServiceMeasurement(slot, result.targetId, result.state,
        if (result.state == DiagnosticState.AVAILABLE) result.elapsedMs else null,
        when (result.proxyFailure) {
            DiagnosticProxyFailure.AUTHENTICATION -> ServerComparisonLimit.PROXY_AUTH
            DiagnosticProxyFailure.CONNECT_REJECTED -> ServerComparisonLimit.PROXY_CONNECT
            DiagnosticProxyFailure.PROTOCOL -> ServerComparisonLimit.PROXY_PROTOCOL
            DiagnosticProxyFailure.STOPPED -> ServerComparisonLimit.PROXY_EXITED
            null -> when (result.issue) {
                DiagnosticIssue.PATH_CHANGED -> ServerComparisonLimit.PATH_CHANGED
                DiagnosticIssue.CANCELLED -> ServerComparisonLimit.CANCELLED
                DiagnosticIssue.TIMEOUT, DiagnosticIssue.BUDGET_EXHAUSTED -> ServerComparisonLimit.BUDGET
                else -> ServerComparisonLimit.NONE
            }
        }, result)

enum class Ipv6Capability { OBSERVED, ADVERTISED, DISABLED, NOT_MEASURED }
data class Ipv6PathReport(val path: DiagnosticPath, val dns: DiagnosticState, val aaaaCount: Int?,
    val reachability: DiagnosticResult?, val route: Ipv6Capability, val server: Ipv6Capability,
    val controlDoh: Boolean, val issue: DiagnosticIssue = DiagnosticIssue.NONE)
data class Ipv6DiagnosticsReport(val targetId: String, val paths: List<Ipv6PathReport>, val environment: DiagnosticsEnvironment)

/** Private scope identity is deliberately absent from exported reports. */
internal data class DiagnosticsScope(val network: Long?, val epoch: Long, val attempt: Int, val server: String, val proxyPort: Int, val core: String?, val settingsRevision: String)

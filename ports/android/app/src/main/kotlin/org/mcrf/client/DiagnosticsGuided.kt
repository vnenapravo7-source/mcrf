package org.mcrf.client

/** Ordered evidence gathering; successful transport never certifies account,
 * playback or application health. Each layer retains unavailable evidence. */
class DiagnosticsGuided(private val environment: () -> DiagnosticsEnvironment, private val probe: ServiceProbe,
    private val serverProbe: ((ServiceTestTarget, DiagnosticCancellation) -> ServerServiceMeasurement)?) {
    fun run(target: ServiceTestTarget, token: DiagnosticCancellation, progress: (GuidedDiagnosticsReport) -> Unit = {}): GuidedDiagnosticsReport {
        val env = environment()
        val findings = mutableListOf<DiagnosticFinding>()
        fun add(value: DiagnosticFinding) { token.check(); findings.add(value); progress(GuidedDiagnosticsReport(target.id, findings.toList(), false)) }
        fun pending(step: DiagnosticStep) {
            token.check()
            progress(GuidedDiagnosticsReport(target.id, findings + DiagnosticFinding(step, DiagnosticState.RUNNING, DiagnosticCause.UNKNOWN, DiagnosticConfidence.UNKNOWN), false))
        }
        add(DiagnosticFinding(DiagnosticStep.NETWORK, if (env.networkPresent) DiagnosticState.AVAILABLE else DiagnosticState.ERROR,
            if (env.networkPresent) DiagnosticCause.UNKNOWN else DiagnosticCause.NETWORK_ABSENT, DiagnosticConfidence.OBSERVED))
        pending(DiagnosticStep.CAPTIVE_DNS)
        val direct = withDiagnosticBudget(token, 6000) { probe.execute(DiagnosticRequest(target, DiagnosticPath.DIRECT), it) }
        add(captiveDns(env, direct))
        add(DiagnosticFinding(DiagnosticStep.ROUTE, DiagnosticState.UNAVAILABLE, when (env.route.policy) {
            DiagnosticRoutePolicy.BLOCK -> DiagnosticCause.SAVED_BLOCK_RULE
            DiagnosticRoutePolicy.DIRECT -> DiagnosticCause.SAVED_DIRECT_RULE
            else -> DiagnosticCause.ROUTE_NOT_OBSERVED
        }, if (env.route.policy in setOf(DiagnosticRoutePolicy.BLOCK, DiagnosticRoutePolicy.DIRECT)) DiagnosticConfidence.POSSIBLE else DiagnosticConfidence.UNKNOWN,
            ruleNumber = env.route.ruleNumber))
        add(DiagnosticFinding(DiagnosticStep.ENGINE, when {
            env.engineInstalled == false -> DiagnosticState.ERROR
            env.engineRunning -> DiagnosticState.AVAILABLE
            else -> DiagnosticState.UNAVAILABLE
        }, when {
            env.engineInstalled == false -> DiagnosticCause.ENGINE_MISSING
            !env.engineRunning -> DiagnosticCause.ENGINE_NOT_RUNNING
            else -> DiagnosticCause.UNKNOWN
        }, DiagnosticConfidence.OBSERVED))
        pending(DiagnosticStep.SERVER)
        val server = if (env.serverSelected && env.networkPresent && !env.portal) try { serverProbe?.invoke(target, token) }
            catch (_: Exception) { token.check(); null } else null
        add(DiagnosticFinding(DiagnosticStep.SERVER, server?.state ?: DiagnosticState.UNAVAILABLE, when {
            !env.serverSelected -> DiagnosticCause.SERVER_NOT_SELECTED
            server?.state == DiagnosticState.AVAILABLE -> DiagnosticCause.SERVICE_RESPONDED
            server?.state == DiagnosticState.ERROR -> DiagnosticCause.UNKNOWN
            server?.limitation == ServerComparisonLimit.BUSY -> DiagnosticCause.PROBE_BUSY
            server?.limitation == ServerComparisonLimit.ACTIVE_VPN_ISOLATION -> DiagnosticCause.VPN_ISOLATION_UNAVAILABLE
            else -> DiagnosticCause.SERVER_NOT_ISOLATED
        }, if (server?.state == DiagnosticState.AVAILABLE) DiagnosticConfidence.OBSERVED else DiagnosticConfidence.UNKNOWN, serverMeasurement = server))
        pending(DiagnosticStep.SERVICE)
        val current = withDiagnosticBudget(token, 6000) { probe.execute(DiagnosticRequest(target, DiagnosticPath.CURRENT_MCRF), it) }
        add(service(direct, current))
        return GuidedDiagnosticsReport(target.id, findings.toList(), true)
    }
    companion object {
        fun captiveDns(env: DiagnosticsEnvironment, direct: DiagnosticResult): DiagnosticFinding = when {
            env.portal -> DiagnosticFinding(DiagnosticStep.CAPTIVE_DNS, DiagnosticState.ERROR, DiagnosticCause.PORTAL_REPORTED, DiagnosticConfidence.OBSERVED, listOf(direct))
            direct.issue == DiagnosticIssue.DNS_FAILED -> DiagnosticFinding(DiagnosticStep.CAPTIVE_DNS, DiagnosticState.ERROR, DiagnosticCause.DNS_FAILURE, DiagnosticConfidence.OBSERVED, listOf(direct))
            !env.validated -> DiagnosticFinding(DiagnosticStep.CAPTIVE_DNS, DiagnosticState.UNAVAILABLE, DiagnosticCause.INTERNET_UNCONFIRMED, DiagnosticConfidence.UNKNOWN, listOf(direct))
            direct.evidence.any { it.stage == DiagnosticStage.DNS && it.state == DiagnosticState.AVAILABLE } ->
                DiagnosticFinding(DiagnosticStep.CAPTIVE_DNS, DiagnosticState.AVAILABLE, DiagnosticCause.UNKNOWN, DiagnosticConfidence.OBSERVED, listOf(direct))
            else -> DiagnosticFinding(DiagnosticStep.CAPTIVE_DNS, DiagnosticState.UNAVAILABLE, DiagnosticCause.UNKNOWN, DiagnosticConfidence.UNKNOWN, listOf(direct))
        }
        fun service(direct: DiagnosticResult, current: DiagnosticResult): DiagnosticFinding {
            val cause = when (current.issue) {
                DiagnosticIssue.NONE -> if (current.state == DiagnosticState.AVAILABLE) DiagnosticCause.SERVICE_RESPONDED else DiagnosticCause.UNKNOWN
                DiagnosticIssue.HTTP_REJECTED -> DiagnosticCause.HTTP_POLICY
                DiagnosticIssue.TLS_FAILED -> DiagnosticCause.TLS_ERROR
                DiagnosticIssue.CONNECT_FAILED -> DiagnosticCause.CONNECTION_ERROR
                DiagnosticIssue.TIMEOUT -> DiagnosticCause.TIMEOUT
                DiagnosticIssue.PATH_CHANGED -> DiagnosticCause.PATH_CHANGED
                else -> DiagnosticCause.UNKNOWN
            }
            return DiagnosticFinding(DiagnosticStep.SERVICE, current.state, cause,
                if (cause in setOf(DiagnosticCause.SERVICE_RESPONDED, DiagnosticCause.HTTP_POLICY, DiagnosticCause.TLS_ERROR)) DiagnosticConfidence.OBSERVED else DiagnosticConfidence.UNKNOWN,
                listOf(direct, current))
        }
    }
}

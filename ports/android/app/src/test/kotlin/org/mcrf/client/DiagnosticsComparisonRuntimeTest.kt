package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsComparisonRuntimeTest {
    private val target = requireNotNull(ServiceTestCatalog.find("github-site"))
    private val other = requireNotNull(ServiceTestCatalog.find("netflix-site"))
    @Test fun startupRejectionIsPreservedAndNotRetriedForEveryService() {
        for (limit in listOf(ServerComparisonLimit.PROXY_START, ServerComparisonLimit.PROXY_VALIDATION,
            ServerComparisonLimit.PROXY_START_TIMEOUT, ServerComparisonLimit.SINGBOX_MISSING, ServerComparisonLimit.SINGBOX_UNVERIFIED)) {
            var starts = 0
            val runtime = DiagnosticsComparisonRuntime { _, _ -> starts++; throw DiagnosticsProxyStartupException(limit) }
            val token = DiagnosticCancellation(5000)
            val first = runtime.measure(1, target, token)
            val second = runtime.measure(1, other, token)
            assertEquals(1, starts)
            for (row in listOf(first, second)) {
                assertEquals(DiagnosticState.UNAVAILABLE, row.state); assertEquals(limit, row.limitation)
            }
            runtime.finishServer()
            runtime.measure(2, target, token); assertEquals(2, starts)
        }
    }
    @Test fun httpAndDnsEvidenceSurviveComparisonWithoutGenericProviderFailure() {
        val token = DiagnosticCancellation(5000)
        val reply = DiagnosticResult(target.id, DiagnosticPath.CURRENT_MCRF, DiagnosticFamily.AUTOMATIC,
            DiagnosticState.ERROR, DiagnosticIssue.HTTP_REJECTED, 30,
            listOf(DiagnosticEvidence(DiagnosticStage.HTTP, DiagnosticState.ERROR, DiagnosticIssue.HTTP_REJECTED, httpStatus = 403)))
        var closes = 0
        val runtime = DiagnosticsComparisonRuntime { _, _ -> object : DiagnosticsComparisonSession {
            override val probe = ServiceProbe { _, _ -> reply }
            override fun awaitClosed() { closes++ }
        } }
        val row = runtime.measure(1, target, token)
        assertEquals(reply, row.diagnostic); assertEquals(ServerComparisonLimit.NONE, row.limitation)
        assertTrue(DiagnosticsPresentation.comparisonEntries(listOf(row), setOf(1), setOf(target.id)).single().reason.contains("HTTP 403"))
        runtime.finishServer(); assertEquals(1, closes)
    }
    @Test fun childExitIsNotRetriedAndFinishMustRetireBeforeAnotherServer() {
        var starts = 0; var probes = 0; var closes = 0
        val runtime = DiagnosticsComparisonRuntime { _, _ -> starts++; object : DiagnosticsComparisonSession {
            override val probe = ServiceProbe { request, _ -> probes++
                DiagnosticResult.of(request, DiagnosticState.ERROR, DiagnosticIssue.INTERNAL)
                    .copy(proxyFailure = DiagnosticProxyFailure.STOPPED)
            }
            override fun awaitClosed() { closes++ }
        } }
        val token = DiagnosticCancellation(5000)
        assertEquals(ServerComparisonLimit.PROXY_EXITED, runtime.measure(1, target, token).limitation)
        assertEquals(ServerComparisonLimit.PROXY_EXITED, runtime.measure(1, other, token).limitation)
        assertEquals(1, probes); assertEquals(1, starts); assertEquals(0, closes)
        try { runtime.measure(2, target, token); fail() } catch (_: IllegalStateException) { }
        runtime.finishServer(); assertEquals(1, closes)
        runtime.measure(2, target, token); assertEquals(2, starts)
        runtime.finishServer()
    }
    @Test fun failedCleanupRetainsExactOwnerAndRequiresRetryBeforeNextServer() {
        var attempts = 0
        val runtime = DiagnosticsComparisonRuntime { _, _ -> object : DiagnosticsComparisonSession {
            override val probe = ServiceProbe { request, _ -> DiagnosticResult.of(request, DiagnosticState.AVAILABLE).copy(elapsedMs = 1) }
            override fun awaitClosed() { if (++attempts == 1) throw IllegalStateException() }
        } }
        val token = DiagnosticCancellation(5000)
        runtime.measure(1, target, token)
        try { runtime.finishServer(); fail() } catch (_: IllegalStateException) { }
        try { runtime.measure(2, target, token); fail() } catch (_: IllegalStateException) { }
        runtime.finishServer(); assertEquals(2, attempts)
        runtime.measure(2, target, token); runtime.finishServer()
    }
    @Test fun cancellationDuringOpenIsNeverCachedAsProviderFailure() {
        val token = DiagnosticCancellation(5000)
        val runtime = DiagnosticsComparisonRuntime { _, _ -> token.cancel(); throw java.io.IOException("private") }
        try { runtime.measure(1, target, token); fail() } catch (_: java.util.concurrent.CancellationException) { }
        runtime.finishServer()
    }
    @Test fun eachTargetRenewsTheWatchdogAndExpiredIngressIsNeverProbed() {
        var renewals=0;var probes=0
        val runtime=DiagnosticsComparisonRuntime {_,_->object:DiagnosticsComparisonSession {
            override val probe=ServiceProbe {request,_->probes++;DiagnosticResult.of(request,DiagnosticState.AVAILABLE).copy(elapsedMs=1)}
            override fun refresh()=++renewals<3
            override fun awaitClosed()=Unit
        }}
        val token=DiagnosticCancellation(5000)
        runtime.measure(1,target,token);runtime.measure(1,other,token)
        assertEquals(ServerComparisonLimit.PROXY_EXITED,runtime.measure(1,target,token).limitation)
        assertEquals(2,probes);assertEquals(3,renewals)
        runtime.finishServer()
    }
    @Test fun comparisonDoesNotRequireOrWriteTheGloballySelectedPrimary() {
        fun source(name: String): String = listOf(java.io.File("app/src/main/kotlin/org/mcrf/client/$name.kt"),
            java.io.File("src/main/kotlin/org/mcrf/client/$name.kt")).first { it.isFile }.readText()
        for (name in listOf("DiagnosticsServerComparison", "DiagnosticsTemporaryProxy")) {
            val text = source(name)
            assertFalse(text.contains("ClientPrimarySelection.selected("))
            assertFalse(text.contains("ClientPrimarySelection.select("))
            assertFalse(text.contains("putString(\"primaryCore\""))
        }
        val temporary = source("DiagnosticsTemporaryProxy")
        assertTrue(temporary.contains("EngineUpdatePrimary.installation(app, \"sing-box\")"))
        assertTrue(temporary.contains("ClientPrimaryWorker.connect(app, \"singbox\""))
    }
}

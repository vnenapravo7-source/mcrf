package org.mcrf.client

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsPipelineTest {
    private val request = DiagnosticRequest(ServiceTestCatalog.find("github-site")!!, DiagnosticPath.DIRECT)
    @Test fun nativeExceptionTextNeverLeavesTypedBoundary() {
        val pipeline = DiagnosticsPipeline(ServiceProbe { _, _ -> throw IllegalStateException("https://user:secret@example.org/?token=secret") })
        val result = pipeline.execute(request, DiagnosticCancellation(1000))
        assertEquals(DiagnosticIssue.INTERNAL, result.issue)
        assertFalse(result.toString().contains("secret")); assertFalse(result.toString().contains("https://"))
        assertFalse(request.toString().contains("github.com"))
    }
    @Test fun cancellationAndTimeoutAreDifferentFromConnectivityFailure() {
        for ((error, state, issue) in listOf(
            Triple(CancellationException(), DiagnosticState.CANCELLED, DiagnosticIssue.CANCELLED),
            Triple(java.net.SocketTimeoutException(), DiagnosticState.ERROR, DiagnosticIssue.TIMEOUT))) {
            val result = DiagnosticsPipeline(ServiceProbe { _, _ -> throw error }).execute(request, DiagnosticCancellation(1000))
            assertEquals(state, result.state); assertEquals(issue, result.issue)
        }
    }
    @Test fun cancelledBeforeStartNeverInvokesProbeAndClosesLateResources() {
        val token = DiagnosticCancellation(1000); token.cancel()
        val closed = AtomicInteger()
        try { token.attach(AutoCloseable { closed.incrementAndGet() }); fail() } catch (_: CancellationException) { }
        assertEquals(1, closed.get())
        val result = DiagnosticsPipeline(ServiceProbe { _, _ -> error("must not run") }).execute(request, token)
        assertEquals(DiagnosticState.CANCELLED, result.state)
    }
    @Test fun ownershipDetachesAndRepeatedCancelDoesNotCloseTwice() {
        val token = DiagnosticCancellation(1000); val detached = AtomicInteger(); val owned = AtomicInteger()
        token.attach(AutoCloseable { detached.incrementAndGet() }).close()
        token.attach(AutoCloseable { owned.incrementAndGet() })
        token.cancel(); token.cancel()
        assertEquals(0, detached.get()); assertEquals(1, owned.get())
    }
    @Test fun adapterCannotPublishAnotherTargetOrPathOrNonterminalState() {
        val forged = listOf(
            DiagnosticResult.of(request.copy(path = DiagnosticPath.CURRENT_MCRF), DiagnosticState.AVAILABLE),
            DiagnosticResult.of(request.copy(target = ServiceTestCatalog.find("netflix-site")!!), DiagnosticState.AVAILABLE),
            DiagnosticResult.of(request, DiagnosticState.RUNNING))
        forged.forEach { value ->
            assertEquals(DiagnosticIssue.INTERNAL, DiagnosticsPipeline(ServiceProbe { _, _ -> value }).execute(request, DiagnosticCancellation(1000)).issue)
        }
    }
}

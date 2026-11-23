package org.mcrf.client

import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class CsqttDirectTunDiagnosticTest {
    @Test fun localSocketConnectCreatesFdBeforeAnyTimeoutOption() {
        val source=File("src/main/kotlin/org/mcrf/client/CsqttDirectTunSession.kt").readText()
        val start=source.indexOf("private fun adopt(")
        val end=source.indexOf("private fun line(",start)
        assertTrue(start>=0&&end>start)
        val adopt=source.substring(start,end)
        val connect=adopt.indexOf("socket.connect(LocalSocketAddress(")
        assertTrue(connect>=0)
        val options=Regex("socket\\.soTimeout\\s*=").findAll(adopt).toList()
        assertEquals(1,options.size)
        assertTrue(options.single().range.first>connect)
        assertTrue(options.single().range.first<adopt.indexOf("socket.inputStream.read()"))
        assertTrue(adopt.contains("socket.soTimeout=(deadline-SystemClock.elapsedRealtime()).coerceIn(1,10_000).toInt()"))
    }
    @Test fun exceptionMessagesAndCausesNeverEnterTheDiagnostic() {
        val canary="password=SECRET hash=PRIVATE csqtt://secret host=example.invalid"
        for(error in listOf(IOException(canary),IllegalStateException(canary),SocketTimeoutException(canary))) {
            val failure=CsqttDirectTunDiagnostic.failure(error,CsqttDirectTunDiagnostic.Phase.ACK)
            assertTrue(failure.code.matches(Regex("DT1/[A-Z_]+/[A-Z_]+ flags=[0-9]+")))
            for(secret in listOf("SECRET","PRIVATE","csqtt://","example.invalid"))assertFalse(failure.detail.contains(secret))
            val safe=CsqttDirectTunDiagnostic.Rejected(failure)
            assertNull(safe.cause)
            assertFalse(safe.toString().contains(canary))
        }
    }
    @Test fun firstSpecificFailureSurvivesOutputEofAndCleanup() {
        val state=CsqttDirectTunDiagnostic.State()
        state.phase=CsqttDirectTunDiagnostic.Phase.ACK
        state.mark(CsqttDirectTunDiagnostic.FD_SENT)
        val first=state.retain(CsqttDirectTunDiagnostic.Cause.TIMEOUT)
        state.phase=CsqttDirectTunDiagnostic.Phase.ACTIVE
        state.mark(CsqttDirectTunDiagnostic.READY)
        assertSame(first,state.retain(CsqttDirectTunDiagnostic.Cause.OUTPUT_EOF))
        assertEquals("DT1/ACK/TIMEOUT flags=4",first.code)
    }
    @Test fun adoptionAndWorkerReadyAreIndependentAndTimeoutIsUnambiguous() {
        val adopted=CsqttDirectTunDiagnostic.State().apply {
            phase=CsqttDirectTunDiagnostic.Phase.WORKER_READY
            mark(CsqttDirectTunDiagnostic.IDENTITY);mark(CsqttDirectTunDiagnostic.CONFIG)
            mark(CsqttDirectTunDiagnostic.FD_SENT);mark(CsqttDirectTunDiagnostic.ACK);mark(CsqttDirectTunDiagnostic.ADOPTED)
        }
        assertEquals("DT1/WORKER_READY/TIMEOUT flags=31",adopted.retain(CsqttDirectTunDiagnostic.Cause.TIMEOUT).code)
        val ready=CsqttDirectTunDiagnostic.State().apply {
            phase=CsqttDirectTunDiagnostic.Phase.ADOPTED
            mark(CsqttDirectTunDiagnostic.READY);mark(CsqttDirectTunDiagnostic.ACK)
        }
        assertEquals("DT1/ADOPTED/TIMEOUT flags=40",ready.retain(CsqttDirectTunDiagnostic.Cause.TIMEOUT).code)
    }
    @Test fun typedFailureKeepsItsPhaseAndFlagsAcrossTheServiceCatch() {
        val failure=CsqttDirectTunDiagnostic.Failure(CsqttDirectTunDiagnostic.Phase.FD_PEER,CsqttDirectTunDiagnostic.Cause.OWNER,3)
        assertSame(failure,CsqttDirectTunDiagnostic.failure(CsqttDirectTunDiagnostic.Rejected(failure),CsqttDirectTunDiagnostic.Phase.START))
    }
    @Test fun sourceKeepsOneFdFailClosedAndRetainsDiagnosticBeforeTeardown() {
        val session=File("src/main/kotlin/org/mcrf/client/CsqttDirectTunSession.kt").readText()
        assertTrue(session.contains("if(sent)throw CsqttDirectTunDiagnostic.Rejected"))
        assertTrue(session.contains("deadline,SystemClock.elapsedRealtime()+10_000"))
        assertTrue(session.contains("while(!adopted.get()||!nativeReady.get()||!routingReady.get())"))
        assertTrue(session.contains("finally{if(control)signalFailure(CsqttDirectTunDiagnostic.Cause.OUTPUT_EOF)}"))
        val source=File("src/main/kotlin/org/mcrf/client/McrfVpnService.kt").readText()
        val begin=source.indexOf("if(directAttempt||directTunOwned)")
        val end=source.indexOf("val portalSession",begin)
        val failure=source.substring(begin,end)
        assertTrue(failure.indexOf("LastConnectionFailure.remember(diagnostic.detail)")<failure.indexOf("closeCore(keepTun=vpn)"))
        assertTrue(failure.contains("ClientEvents.record(this,\"Direct TUN\",diagnostic.code)"))
        assertFalse(failure.contains("error.message"))
    }
    @Test fun protocolAndDiagnosticsAreReadSeparately() {
        val session=File("src/main/kotlin/org/mcrf/client/CsqttDirectTunSession.kt").readText()
        assertFalse(session.contains("redirectErrorStream(true)"))
        assertTrue(session.contains("readOutput(child.inputStream,true)"))
        assertTrue(session.contains("readOutput(child.errorStream,false)"))
        assertTrue(session.contains("if(control)line(text)else diagnosticLine(text)"))
        assertTrue(session.contains("diagnosticReader.join(1000)"))
    }
    @Test fun startupHealthReturnsFirstSuccessWhileAnotherProbeIsPending() {
        val first=java.util.concurrent.CompletableFuture<Long>()
        val second=java.util.concurrent.CompletableFuture<Long>()
        val result=CsqttDirectTunSession.firstHealthy(listOf(first,second))
        second.complete(203)
        assertEquals(203L,result.getNow(-1))
        assertFalse(first.isDone)
    }
    @Test fun startupHealthWaitsForTheRemainingProbeAfterFailure() {
        val first=java.util.concurrent.CompletableFuture<Long>()
        val second=java.util.concurrent.CompletableFuture<Long>()
        val result=CsqttDirectTunSession.firstHealthy(listOf(first,second))
        first.complete(-1)
        assertFalse(result.isDone)
        second.complete(0)
        assertEquals(0L,result.getNow(-1))
        assertEquals(-1L,CsqttDirectTunSession.firstHealthy(listOf(first)).getNow(-2))
    }
}

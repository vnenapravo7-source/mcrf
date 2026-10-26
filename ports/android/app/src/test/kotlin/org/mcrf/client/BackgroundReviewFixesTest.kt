package org.mcrf.client

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** New scheduler/recovery review regressions, not a replay of earlier suites. */
class BackgroundReviewFixesTest {
    private fun source(name:String):String = listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
    @Test fun allApplicationSchedulerIdsAreDistinctAndExistingPeriodicIdsStayStable(){
        val ids=listOf(BackgroundJobIds.SUBSCRIPTIONS,BackgroundJobIds.ENGINES,
            BackgroundJobIds.SUBSCRIPTIONS_ON_OPEN,BackgroundJobIds.ROUTING)
        assertEquals(4,ids.toSet().size);assertEquals(2401,BackgroundJobIds.SUBSCRIPTIONS)
        assertEquals(2402,EngineUpdateSchedule.JOB)
        assertEquals(0x4d435244,RemoteRoutingSchedule.JOB_ID)
        assertEquals(2403,SubscriptionOpenRefresh.JOB)
    }
    @Test fun cancellingOnOpenOrPeriodicSubscriptionDoesNotCancelEngineDiscovery(){
        val scheduled=mutableMapOf(EngineUpdateSchedule.JOB to "engines",
            SubscriptionOpenRefresh.JOB to "onOpen",BackgroundJobIds.SUBSCRIPTIONS to "subscriptions")
        scheduled.remove(BackgroundJobIds.SUBSCRIPTIONS);scheduled.remove(SubscriptionOpenRefresh.JOB)
        assertEquals(mapOf(EngineUpdateSchedule.JOB to "engines"),scheduled)
    }
    @Test fun onOpenAndEngineScheduleCannotReplaceEachOtherInEitherOrder(){
        for(order in listOf(listOf(EngineUpdateSchedule.JOB,SubscriptionOpenRefresh.JOB),
            listOf(SubscriptionOpenRefresh.JOB,EngineUpdateSchedule.JOB))){
            val scheduled=mutableMapOf<Int,Int>();order.forEach {scheduled[it]=it}
            assertEquals(2,scheduled.size)
        }
    }
    @Test fun actualSchedulersAndCancellationUseTheCentralIdAliases(){
        assertTrue(source("SubscriptionUpdates").contains("private const val JOB = BackgroundJobIds.SUBSCRIPTIONS"))
        assertTrue(source("SubscriptionUpdates").contains("scheduler.cancel(SubscriptionOpenRefresh.JOB)"))
        assertTrue(source("SubscriptionOpenRefresh").contains("JobInfo.Builder(JOB,ComponentName(context,SubscriptionUpdateService::class.java))"))
        assertTrue(source("EngineUpdateSchedule").contains("JobInfo.Builder(JOB, ComponentName(context, EngineUpdateService::class.java))"))
    }
    @Test fun transientIoRecoveryStillRetriesAndKeepsTheSpecificCause(){
        val result=NetworkRecoveryFailure.classify(IOException("PROTOCOL_CONNECTION_REFUSED: peer unavailable"))
        assertTrue(result.retry);assertTrue(result.detail.contains("PROTOCOL_CONNECTION_REFUSED"))
    }
    @Test fun trustDenialAndWrappedTrustDenialAreTerminalForRecovery(){
        for(error in listOf(SecurityException("PROTOCOL_TLS_DENIED"),
            IOException("health failed",SecurityException("trust rejected"))))
            assertFalse(NetworkRecoveryFailure.classify(error).retry)
    }
    @Test fun cancellationAndInterruptCannotRestartRecovery(){
        for(error in listOf(CancellationException("owner cancelled"),InterruptedException("worker interrupted"),
            IOException("outer",CancellationException("inner cancelled"))))
            assertFalse(NetworkRecoveryFailure.classify(error).retry)
    }
    @Test fun recoveryDetailIsBoundedAndRedactsUrlCredentialsAndParameters(){
        val result=NetworkRecoveryFailure.classify(IOException("Failure https://owned:fixture-secret@unit.invalid/?token=fixture-secret\n"+"x".repeat(2000)))
        assertFalse(result.detail.contains("fixture-secret"));assertFalse(result.detail.contains('\n'))
        assertTrue(result.detail.length<=1200)
    }
    @Test fun emptyFailureStillHasAVisibleTechnicalType(){
        assertEquals("IOException",NetworkRecoveryFailure.classify(IOException()).detail)
    }
    @Test fun serviceTerminalRecoveryRetainsTunAndClearsRetryBeforeReporting(){
        val body=source("McrfVpnService");val start=body.indexOf("private fun resetConnections(reason:String,forceRestart:Boolean=false)")
        assertTrue("Exact function must exist",start>=0)
        val end=body.indexOf("private val preferences",start);assertTrue(end>start)
        val reset=body.substring(start,end)
        assertTrue(reset.contains("val failure=NetworkRecoveryFailure.classify(error)"))
        assertTrue(reset.contains("closeCore(keepTun=recovery?.vpn==true)"))
        assertTrue(reset.contains("requested==epoch&&recovery?.requested==requested"))
        val terminal=reset.substringAfter("if(failure.retry){").substringAfter("}else{").substringBefore("}finally{")
        assertTrue(terminal.contains("clearNetworkRecovery()"));assertTrue(terminal.contains("putBoolean(\"wantedRunning\",false)"))
        assertFalse(terminal.contains("retryNetworkRecovery("));assertTrue(terminal.contains("Ошибка восстановления"))
    }
}

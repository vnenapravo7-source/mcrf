package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CancellationException

class HealthBetaTest {
    @Test fun disabledByDefaultAndRevocationInvalidatesInFlightPermit() {
        val store=HealthStore();assertFalse(HealthSettings().enabled)
        assertFalse(store.settings().enabled);assertNull(store.reserve())
        assertFalse(store.ingest("server",HealthEvent(1,HealthEventKind.SESSION_STARTED)))
        store.setEnabled(true);val permit=store.reserve()!!
        store.setEnabled(false)
        assertFalse(store.recordSample("server",permit,10,0,HealthNetwork.WIFI))
        assertEquals(HealthSnapshot(),store.snapshot("server"))
        assertTrue(store.usedBytes()>0)
    }
    @Test fun budgetAndCooldownSurviveReloadAndCannotBeResetByClockRollback() {
        val path=Files.createTempDirectory("health-test").resolve("health.bin").toFile()
        var now=1_000_000L;var tick=10_000L
        try {
            var store=HealthStore(path,{now},{tick});store.setEnabled(true)
            repeat(10) {
                assertNotNull(store.reserve());now+=HealthSettings.INTERVAL_MS;tick+=HealthSettings.INTERVAL_MS
                store=HealthStore(path,{now},{tick})
            }
            assertNull(store.reserve());assertTrue(store.usedBytes()<=HealthSettings.DAILY_BYTES)
            now-=HealthSettings.DAY_MS;tick=1
            store=HealthStore(path,{now},{tick});assertNull(store.reserve())
        } finally {path.delete();path.parentFile.delete()}
    }
    @Test fun samplesAreBoundedAndOldDimensionsAreUnknown() {
        var now=1000L;var tick=1000L
        val store=HealthStore(wall={now},elapsed={tick});store.setEnabled(true)
        val permit=store.reserve()!!
        assertTrue(store.recordSample("a",permit,50,0,HealthNetwork.WIFI))
        assertFalse(store.recordSample("a",permit,50,0,HealthNetwork.WIFI))
        assertEquals(100,HealthScore.calculate(store.snapshot("a"),now).server)
        now+=HealthSettings.FRESH_MS;tick+=HealthSettings.FRESH_MS
        assertNull(HealthScore.calculate(store.snapshot("a"),now).server)
        now+=HealthSettings.RETENTION_MS;tick+=HealthSettings.RETENTION_MS
        assertEquals(HealthSnapshot(),store.snapshot("a"))
    }
    @Test fun lifecycleIsTypedOrderedDeduplicatedAndStopsUptimeOnFailure() {
        var now=10_000L;var tick=10_000L;var id=0L
        val store=HealthStore(wall={now},elapsed={tick});store.setEnabled(true)
        val events=mutableListOf<HealthEvent>()
        val lifecycle=RuntimeHealthLifecycle({++id}) { server,event ->events.add(event);store.ingest(server,event)}
        lifecycle.connected(1,"server",HealthNetwork.WIFI,HealthEngine.STEER)
        lifecycle.connected(1,"server",HealthNetwork.WIFI,HealthEngine.STEER)
        tick+=200;now+=200;lifecycle.failed(RecoveryReason.NETWORK_LOSS)
        tick+=500;now+=500
        assertEquals(200L,store.snapshot("server").uptimeMs)
        assertEquals(1L,store.snapshot("server").errors[HealthDimension.NETWORK.ordinal])
        lifecycle.connected(2,"server",HealthNetwork.CELLULAR,HealthEngine.STEER)
        assertEquals(HealthEventKind.RECOVERED,events.last().kind)
        assertEquals(HealthDimension.NETWORK,events.last().dimension)
        assertFalse(store.ingest("server",events.first().copy(kind=HealthEventKind.ERROR)))
        lifecycle.stopped(HealthDisconnect.USER);lifecycle.stopped(HealthDisconnect.USER)
        assertEquals(2L,store.snapshot("server").sessions)
        assertEquals(HealthDisconnect.USER,store.snapshot("server").lastDisconnect)
        assertEquals(8,events.size)
    }
    @Test fun cancellationClosesResourcesAndDoesNotPublishLoss() {
        val cancellation=HealthCancellation();var closed=false;var attempts=0
        cancellation.attach {closed=true}
        try {
            ProtocolHealthCheck.sample(cancellation) {attempts++;cancellation.cancel();null}
            fail("cancelled sample published")
        } catch(_:CancellationException){}
        assertTrue(closed);assertEquals(1,attempts)
    }
    @Test fun corruptStoreFailsClosedRatherThanGrantingBudget() {
        val path=Files.createTempFile("health-corrupt",".bin").toFile()
        try {val store=HealthStore(path);assertFalse(store.setEnabled(true));assertNull(store.reserve())}
        finally {path.delete()}
    }
}

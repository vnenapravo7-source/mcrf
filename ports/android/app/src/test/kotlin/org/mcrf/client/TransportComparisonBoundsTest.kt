package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TransportComparisonBoundsTest {
    private fun profile(id: String, name: String = "Node", bs: Boolean = false) = ServerImport.Server(
        id, name, "vless", "node.example.com", 443,
        JSONObject().put("type", "vless").put("server", "node.example.com").put("server_port", 443),
        null, null, null, emptyList(), bs)

    @Test fun eightProfilesProduceTwentyFourSamplesAndNinthIsRejected() {
        val profiles = (1..8).map { profile("$it") }
        TransportComparisonPlan.validate(profiles.first(), profiles)
        val progress = mutableListOf<Pair<Int, Int>>()
        val report = TransportComparison(TransportComparisonProvider { _, _ -> 10L }) { 0L }
            .run(profiles.first(), profiles, TransportComparisonCancellation()) { progress += it.completed to it.total }
        assertTrue(report.complete)
        assertEquals(24, report.completed); assertEquals(24, report.total)
        assertEquals((0..24).map { it to 24 }, progress.distinct())
        assertEquals(7, TransportComparisonPlan.hideExceptWinner(report, profiles).size)
        assertThrows(IllegalArgumentException::class.java) {
            TransportComparisonPlan.validate(profiles.first(), profiles + profile("9"))
        }
    }

    @Test fun diagnosticGroupsKeepCarrierReasonsAndSectionBoundaries() {
        fun carrier(id: String, host: String) = ServerImport.Server(id, "Carrier", "openflux", host, 443,
            null, null, null, JSONObject().put("type", "openflux"))
        val a = carrier("a", "node.example.com"); val b = carrier("b", "NODE.EXAMPLE.COM")
        val single = carrier("single", "other.example.com")
        val vpn = profile("vpn"); val bs = profile("bs", bs = true)
        val entries = listOf(a, b, single, vpn, bs, a)
        val groups = TransportComparisonPlan.diagnosticGroups(entries, true)
        assertEquals(listOf(listOf(a, b, bs)), groups)
        assertTrue(TransportComparisonPlan.diagnosticGroups(entries, false).isEmpty())
        for (entry in listOf(a, b, single)) {
            assertFalse(TransportComparisonPlan.supported(entry))
            assertTrue(TransportComparisonPlan.unavailableReason(entry)!!.contains("Carrier"))
        }
        assertNull(TransportComparisonPlan.unavailableReason(bs))
        assertThrows(IllegalArgumentException::class.java) { TransportComparisonPlan.validate(vpn, listOf(vpn, bs)) }
        // BS can also contain ordinary sing-box transports; they must remain admissible.
        TransportComparisonPlan.validate(bs, listOf(bs, profile("bs2", bs = true)))
    }

    @Test fun everyUnsupportedShapeHasASecretFreeReason() {
        val a = profile("a")
        val full = ServerImport.Server("full", "secret", "vless", a.host, a.port, a.outbound, null, JSONObject())
        val endpoint = ServerImport.Server("endpoint", "secret", "wireguard", a.host, a.port, null, JSONObject(), null)
        val malformed = ServerImport.Server("bad", "secret", "vless", "bad host", 0, JSONObject(), null, null)
        for (entry in listOf(full, endpoint, malformed)) {
            assertFalse(TransportComparisonPlan.supported(entry))
            val reason = TransportComparisonPlan.unavailableReason(entry)
            assertNotNull(reason); assertFalse(reason!!.contains("secret"))
            assertTrue(TransportComparisonPlan.diagnosticGroups(listOf(entry), entry.whitelists).isEmpty())
        }
    }

    @Test fun countryComesFromExplicitValidFlagsAndFallsBackForUnknownOrConflictingFlags() {
        assertEquals("🇩🇪 Германия", TransportComparisonPlan.countryLabel(listOf(profile("a", "🇩🇪 Frankfurt"))))
        assertEquals("🇺🇸 Соединенные Штаты", TransportComparisonPlan.countryLabel(listOf(profile("a", "🇺🇸 US"))))
        for (group in listOf(listOf(profile("a")), listOf(profile("a", "🇿🇿 Unknown")),
            listOf(profile("a", "🇩🇪 DE"), profile("b", "🇫🇷 FR")))) {
            assertEquals("🌐 Страна не указана", TransportComparisonPlan.countryLabel(group))
            assertTrue(TransportComparisonPlan.groupTitle(group).contains(" · "))
        }
        val title = TransportComparisonPlan.groupTitle(listOf(profile("a", "🇩🇪 Frankfurt"), profile("b")))
        assertEquals(1, Regex("🇩🇪").findAll(title).count())
        assertTrue(title.contains("Frankfurt"))
    }

    @Test fun hiddenDialogScrollBoundaryIsSixRows() {
        for (count in 0..6) assertFalse(TransportComparisonPlan.hiddenRowsScroll(count))
        assertTrue(TransportComparisonPlan.hiddenRowsScroll(7))
        assertTrue(TransportComparisonPlan.hiddenRowsScroll(1000))
    }

    /** This provider ignores interruption until the fixture explicitly releases it. */
    private class StuckProvider : TransportComparisonProvider {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val token = AtomicReference<TransportComparisonCancellation>()
        override fun measure(profile: ServerImport.Server, token: TransportComparisonCancellation): Long? {
            calls.incrementAndGet(); worker.set(Thread.currentThread()); this.token.set(token); entered.countDown()
            while (true) { try { release.await(); break } catch (_: InterruptedException) { } }
            return 1L
        }
        fun drain() {
            release.countDown(); worker.get()?.join(1000)
            assertFalse("Fixture provider did not drain", worker.get()?.isAlive == true)
        }
    }

    @Test(timeout = 4000) fun perServerDeadlineBoundsNonCooperativeProviderAndKeepsItsLease() {
        val provider = StuckProvider(); val a = profile("a"); val b = profile("b")
        try {
            val started = System.nanoTime()
            val report = TransportComparison(provider, 150, 2000) { 0L }.run(a, listOf(a, b), TransportComparisonCancellation())
            assertTrue(provider.entered.await(1, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
            assertFalse(report.complete); assertTrue(report.exhausted); assertNull(report.suggestedSlot)
            assertEquals(listOf<Long?>(null), report.results[0].samples)
            assertTrue(report.stopReason!!.contains("профиля 1"))
            assertTrue(TransportComparisonPlan.hideExceptWinner(report, listOf(a, b)).isEmpty())
            assertThrows(CancellationException::class.java) { provider.token.get().check() }
            val replacementCalls = AtomicInteger()
            assertThrows(IllegalStateException::class.java) {
                TransportComparison(TransportComparisonProvider { _, _ -> replacementCalls.incrementAndGet(); 1L }) { 0L }
                    .run(a, listOf(a, b), TransportComparisonCancellation())
            }
            assertEquals(0, replacementCalls.get()); assertEquals(1, provider.calls.get())
        } finally { provider.drain() }
        assertTrue(TransportComparison(TransportComparisonProvider { _, _ -> 1L }) { 0L }
            .run(a, listOf(a, b), TransportComparisonCancellation()).complete)
    }

    @Test(timeout = 4000) fun overallDeadlineWinsEvenIfLogicalClockDoesNotAdvance() {
        val provider = StuckProvider(); val a = profile("a")
        try {
            val report = TransportComparison(provider, 2000, 150) { 0L }
                .run(a, listOf(a, profile("b")), TransportComparisonCancellation())
            assertTrue(report.exhausted); assertFalse(report.complete); assertNull(report.suggestedSlot)
            assertTrue(report.stopReason!!.contains("Общее")); assertEquals(1, provider.calls.get())
        } finally { provider.drain() }
    }

    @Test(timeout = 4000) fun cancellationReturnsWithoutWaitingForNonCooperativeProvider() {
        val provider = StuckProvider(); val a = profile("a"); val token = TransportComparisonCancellation()
        val thrown = AtomicReference<Throwable>()
        val caller = Thread {
            try { TransportComparison(provider, 2000, 3000) { 0L }.run(a, listOf(a, profile("b")), token) }
            catch (error: Throwable) { thrown.set(error) }
        }
        try {
            caller.start(); assertTrue(provider.entered.await(1, TimeUnit.SECONDS)); token.close(); caller.join(1000)
            assertFalse("Cancellation hung", caller.isAlive)
            assertTrue(thrown.get() is CancellationException); assertEquals(1, provider.calls.get())
        } finally { token.close(); provider.drain(); caller.join(1000) }
    }

    @Test fun serverBudgetIsCumulativeAcrossInterleavedRoundsAndLateSuccessIsRejected() {
        val a = profile("a"); val b = profile("b"); var clock = 0L
        val report = TransportComparison(TransportComparisonProvider { _, _ -> clock += 1000; 1L }, 2500, 10000) { clock }
            .run(a, listOf(a, b), TransportComparisonCancellation())
        assertFalse(report.exhausted); assertTrue(report.complete); assertNull(report.suggestedSlot)
        assertEquals(6, report.completed); assertEquals(listOf(1L, 1L, null), report.results[0].samples)
        assertEquals(listOf(1L, 1L, null), report.results[1].samples)
    }

    @Test fun deadlineOnFinalSampleCannotReturnACompleteWinner() {
        val a = profile("a"); var clock = 0L
        val report = TransportComparison(TransportComparisonProvider { _, _ -> clock += 1000; 1L }, 10000, 6000) { clock }
            .run(a, listOf(a, profile("b")), TransportComparisonCancellation())
        assertEquals(6, report.completed); assertTrue(report.exhausted); assertFalse(report.complete)
        assertNull(report.suggestedSlot); assertNull(report.results[1].samples.last())
    }

    @Test(timeout = 4000) fun cooperativeProfileTimeoutContinuesOtherProfilesWithoutOverlappingCalls() {
        val a = profile("timeout"); val b = profile("healthy")
        val active = AtomicInteger(); val maximum = AtomicInteger(); val calls = mutableListOf<String>()
        val report = TransportComparison(TransportComparisonProvider { entry, _ ->
            val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
            synchronized(calls) { calls += entry.id }
            try {
                if (entry.id == a.id) { Thread.sleep(2000); 1L } else 20L
            } finally { active.decrementAndGet() }
        }, 120, 2500) { 0L }.run(a, listOf(a, b), TransportComparisonCancellation())
        assertTrue(report.complete); assertFalse(report.exhausted); assertEquals(2, report.suggestedSlot)
        assertEquals(listOf<Long?>(null, null, null), report.results[0].samples)
        assertEquals(listOf<Long?>(20, 20, 20), report.results[1].samples)
        assertEquals(1, maximum.get()); assertEquals(1, calls.count { it == a.id })
        assertEquals(3, calls.count { it == b.id })
    }

    @Test fun progressIdentifiesTheCurrentProfileBeforeEachAttemptAndHasABoundedCountdown() {
        val a = profile("a"); var clock = 0L
        val updates = mutableListOf<TransportComparisonReport>()
        TransportComparison(TransportComparisonProvider { _, _ -> clock += 10; 5L }, 1000, 5000) { clock }
            .run(a, listOf(a, profile("b")), TransportComparisonCancellation(), updates::add)
        assertEquals(listOf(1, 2, 2, 1, 1, 2), updates.mapNotNull { it.currentSlot })
        assertTrue(updates.zipWithNext().all { (before, after) -> before.remainingMs >= after.remainingMs })
        assertTrue(updates.all { it.remainingMs in 0..5000 })
        assertEquals(6, updates.last().completed)
    }
}

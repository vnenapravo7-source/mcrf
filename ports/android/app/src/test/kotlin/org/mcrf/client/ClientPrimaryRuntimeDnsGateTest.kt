package org.mcrf.client

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mcrf.client.ClientPrimaryRuntimeDnsGate.BeginRejection
import org.mcrf.client.ClientPrimaryRuntimeDnsGate.BeginResult
import org.mcrf.client.ClientPrimaryRuntimeDnsGate.CancelResult
import org.mcrf.client.ClientPrimaryRuntimeDnsGate.CompletionResult

class ClientPrimaryRuntimeDnsGateTest {
    private val generation = 7L

    private fun ticket(
        gate: ClientPrimaryRuntimeDnsGate,
        id: Long = 1L,
        now: Long = 0L,
        timeout: Int = 8000
    ): ClientPrimaryRuntimeDnsGate.Ticket {
        val result = gate.begin(gate.generation, id, now, timeout)
        assertTrue("Expected admission, got $result", result is BeginResult.Admitted)
        return (result as BeginResult.Admitted).ticket
    }

    private fun rejected(reason: BeginRejection, result: BeginResult) {
        assertEquals(BeginResult.Rejected(reason), result)
    }

    /** No sleeps, resolver, executor or scheduled tasks; all test threads have bounded joins. */
    private fun <T> race(actions: List<() -> T>): List<T> {
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        val tasks = actions.map { action ->
            FutureTask(Callable {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS)) { "Race start timed out" }
                action()
            })
        }
        val threads = tasks.mapIndexed { index, task ->
            Thread(task, "dns-gate-test-$index").apply { isDaemon = true }
        }
        try {
            threads.forEach { it.start() }
            assertTrue("Race workers did not become ready", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            return tasks.map { it.get(maxOf(1L, end - System.nanoTime()), TimeUnit.NANOSECONDS) }
        } finally {
            start.countDown()
            threads.forEach { if (it.isAlive) it.interrupt() }
            threads.forEach { it.join(100) }
            assertFalse("Race worker leaked", threads.any { it.isAlive })
        }
    }

    @Test fun requiresPositiveGenerationAndAcceptsFullPositiveIdRange() {
        assertThrows(IllegalArgumentException::class.java) { ClientPrimaryRuntimeDnsGate(0) }
        assertThrows(IllegalArgumentException::class.java) { ClientPrimaryRuntimeDnsGate(-1) }
        val gate = ClientPrimaryRuntimeDnsGate(Long.MAX_VALUE)
        val admitted = ticket(gate, Long.MAX_VALUE, 100, 8000)
        assertEquals(Long.MAX_VALUE, admitted.generation)
        assertEquals(Long.MAX_VALUE, admitted.requestId)
        assertEquals(8100L, admitted.deadlineElapsedMillis)
    }

    @Test fun completionCommitsExactlyOnceAndIdCannotBeReused() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val admitted = ticket(gate)
        assertEquals(CompletionResult.COMPLETED, gate.complete(admitted, 1))
        assertEquals(CompletionResult.ALREADY_COMPLETED, gate.complete(admitted, 2))
        assertEquals(CancelResult.ALREADY_COMPLETED, gate.cancel(generation, 1, 3))
        rejected(BeginRejection.ALREADY_COMPLETED, gate.begin(generation, 1, 4, 8000))
    }

    @Test fun cancellationBeforeExchangeRetainsTombstoneForEntireRun() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        assertEquals(CancelResult.TOMBSTONED, gate.cancel(generation, 9, 0))
        assertEquals(CancelResult.ALREADY_CANCELLED, gate.cancel(generation, 9, 1))
        rejected(BeginRejection.CANCELLED, gate.begin(generation, 9, 1_000_000, 8000))
        rejected(BeginRejection.CANCELLED, gate.begin(generation, 9, Long.MAX_VALUE, 1))
    }

    @Test fun cancellationOfOpenRequestPermanentlyBlocksCompletion() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val admitted = ticket(gate)
        assertEquals(CancelResult.CANCELLED, gate.cancel(generation, 1, 1))
        assertEquals(CancelResult.ALREADY_CANCELLED, gate.cancel(generation, 1, 2))
        assertEquals(CompletionResult.CANCELLED, gate.complete(admitted, 3))
        assertEquals(CompletionResult.CANCELLED, gate.complete(admitted, 9000))
        rejected(BeginRejection.CANCELLED, gate.begin(generation, 1, 9000, 8000))
    }

    @Test fun staleAndFutureGenerationsCannotMutateClockOrCreateTombstones() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        for (other in listOf(-1L, 0L, generation - 1, generation + 1, Long.MAX_VALUE)) {
            rejected(BeginRejection.STALE_GENERATION, gate.begin(other, 1, Long.MAX_VALUE, 8000))
            assertEquals(CancelResult.STALE_GENERATION, gate.cancel(other, 1, Long.MAX_VALUE))
        }
        val old = ticket(ClientPrimaryRuntimeDnsGate(generation - 1))
        assertEquals(CompletionResult.STALE_GENERATION, gate.complete(old, Long.MAX_VALUE))
        assertEquals(CompletionResult.COMPLETED, gate.complete(ticket(gate), 0))
    }

    @Test fun malformedInputsDoNotSpendIdsOrAdvanceClock() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        for (id in listOf(0L, -1L, Long.MIN_VALUE)) {
            rejected(BeginRejection.INVALID_REQUEST_ID, gate.begin(generation, id, Long.MAX_VALUE, 1))
            assertEquals(CancelResult.INVALID_REQUEST_ID, gate.cancel(generation, id, Long.MAX_VALUE))
        }
        for (timeout in listOf(Int.MIN_VALUE, -1, 0, 8001, Int.MAX_VALUE)) {
            rejected(BeginRejection.INVALID_TIMEOUT, gate.begin(generation, 1, Long.MAX_VALUE, timeout))
        }
        assertEquals(CompletionResult.COMPLETED, gate.complete(ticket(gate, timeout = 1), 0))
    }

    @Test fun negativeAndBackwardTimesAreRefusedWithoutMutation() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        rejected(BeginRejection.INVALID_TIME, gate.begin(generation, 1, -1, 20))
        assertEquals(CancelResult.INVALID_TIME, gate.cancel(generation, 1, Long.MIN_VALUE))
        val admitted = ticket(gate, now = 100, timeout = 20)
        assertEquals(CancelResult.INVALID_TIME, gate.cancel(generation, 1, 99))
        rejected(BeginRejection.INVALID_TIME, gate.begin(generation, 2, 99, 20))
        assertEquals(CompletionResult.INVALID_TIME, gate.complete(admitted, 99))
        assertEquals(CompletionResult.INVALID_TIME, gate.complete(admitted, -1))
        assertEquals(CompletionResult.COMPLETED, gate.complete(admitted, 110))
        assertEquals(CompletionResult.COMPLETED, gate.complete(ticket(gate, 2, 110), 110))
    }

    @Test fun deadlineIncludesBoundaryAndAllowsOnlyStrictlyEarlierCompletion() {
        for (now in listOf(10L, 11L, Long.MAX_VALUE)) {
            val gate = ClientPrimaryRuntimeDnsGate(generation)
            assertEquals(CompletionResult.TIMED_OUT, gate.complete(ticket(gate, timeout = 10), now))
            rejected(BeginRejection.TIMED_OUT, gate.begin(generation, 1, now, 8000))
            assertEquals(CancelResult.TIMED_OUT, gate.cancel(generation, 1, now))
        }
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        assertEquals(CompletionResult.COMPLETED, gate.complete(ticket(gate, timeout = 10), 9))
    }

    @Test fun duplicateBeginNeitherExtendsNorShortensOriginalDeadline() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val first = ticket(gate, now = 100, timeout = 10)
        rejected(BeginRejection.DUPLICATE, gate.begin(generation, 1, 101, 8000))
        assertEquals(110L, first.deadlineElapsedMillis)
        assertEquals(CompletionResult.TIMED_OUT, gate.complete(first, 110))
        val second = ticket(gate, 2, 110, 10)
        rejected(BeginRejection.DUPLICATE, gate.begin(generation, 2, 111, 1))
        assertEquals(CompletionResult.COMPLETED, gate.complete(second, 119))
    }

    @Test fun cancelAtDeadlineCannotRewriteTimeoutAsCancellation() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val admitted = ticket(gate, timeout = 1)
        assertEquals(CancelResult.TIMED_OUT, gate.cancel(generation, 1, 1))
        assertEquals(CompletionResult.TIMED_OUT, gate.complete(admitted, 1))
    }

    @Test fun timeoutSweepFreesSlotsAndOverflowRefusedIdStillCannotBeReused() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val open = (1L..32L).map { ticket(gate, it, timeout = 10) }
        rejected(BeginRejection.IN_FLIGHT_LIMIT, gate.begin(generation, 33, 9, 8000))
        val next = ticket(gate, 34, 10)
        rejected(BeginRejection.ID_ALREADY_USED, gate.begin(generation, 33, 10, 8000))
        assertEquals(CancelResult.ALREADY_REFUSED, gate.cancel(generation, 33, 10))
        open.forEach { assertEquals(CompletionResult.TIMED_OUT, gate.complete(it, 10)) }
        assertEquals(CompletionResult.COMPLETED, gate.complete(next, 10))
    }

    @Test fun terminalOperationsReleaseEachSlotOnce() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val open = (1L..32L).map { ticket(gate, it) }
        assertEquals(CancelResult.CANCELLED, gate.cancel(generation, 1, 0))
        assertEquals(CancelResult.ALREADY_CANCELLED, gate.cancel(generation, 1, 0))
        assertEquals(CompletionResult.CANCELLED, gate.complete(open[0], 0))
        ticket(gate, 33)
        assertEquals(CompletionResult.COMPLETED, gate.complete(open[1], 0))
        assertEquals(CompletionResult.ALREADY_COMPLETED, gate.complete(open[1], 0))
        ticket(gate, 34)
        rejected(BeginRejection.IN_FLIGHT_LIMIT, gate.begin(generation, 35, 0, 8000))
    }

    @Test fun longRunningMonotonicRequestsReclaimTerminalRecordsWithoutPermanentOutage() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        for (id in 1L..20_000L) {
            assertEquals(CompletionResult.COMPLETED, gate.complete(ticket(gate, id), 0))
        }
        // The lifetime check must also detect a regression to an unbounded ID map.
        val records = gate.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(gate) as Map<*, *>
        assertTrue(records.size <= ClientPrimaryRuntimeDnsGate.MAX_REMEMBERED_IDS)
        for (id in listOf(1L, 1024L, 18_976L)) {
            rejected(BeginRejection.RETIRED_ID, gate.begin(generation, id, 0, 1))
            assertEquals(CancelResult.RETIRED_ID, gate.cancel(generation, id, 0))
        }
        rejected(BeginRejection.ALREADY_COMPLETED, gate.begin(generation, 20_000, 0, 1))
        assertEquals(CompletionResult.COMPLETED, gate.complete(ticket(gate, 20_001), 0))
    }

    @Test fun reclaimedEarlyCancelTombstonesRemainBlockedByTheFloor() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        for (id in 1L..4096L) assertEquals(CancelResult.TOMBSTONED, gate.cancel(generation, id, 0))
        for (id in listOf(1L, 1024L, 3072L)) {
            rejected(BeginRejection.RETIRED_ID, gate.begin(generation, id, 9000, 8000))
            assertEquals(CancelResult.RETIRED_ID, gate.cancel(generation, id, 9000))
        }
        for (id in 3073L..4096L) {
            rejected(BeginRejection.CANCELLED, gate.begin(generation, id, 9000, 8000))
        }
        assertEquals(CompletionResult.COMPLETED, gate.complete(ticket(gate, 4097, 9000), 9000))
    }

    @Test fun floorCanPassActiveIdsWithoutInvalidatingTheirTicketsOrCancellation() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val open = (1L..32L).map { ticket(gate, it) }
        for (id in 33L..4096L) assertEquals(CancelResult.TOMBSTONED, gate.cancel(generation, id, 0))
        assertEquals(CancelResult.CANCELLED, gate.cancel(generation, 1, 0))
        assertEquals(CompletionResult.CANCELLED, gate.complete(open[0], 0))
        open.drop(1).forEach { assertEquals(CompletionResult.COMPLETED, gate.complete(it, 0)) }
        rejected(BeginRejection.RETIRED_ID, gate.begin(generation, 33, 0, 1))
        assertEquals(CompletionResult.COMPLETED, gate.complete(ticket(gate, 4097), 0))
    }

    @Test fun inFlightRefusalsAreReclaimedWithoutReusingTheirIds() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val open = (1L..32L).map { ticket(gate, it) }
        for (id in 33L..4096L) {
            rejected(BeginRejection.IN_FLIGHT_LIMIT, gate.begin(generation, id, 0, 8000))
        }
        open.forEach { assertEquals(CompletionResult.TIMED_OUT, gate.complete(it, 8000)) }
        rejected(BeginRejection.RETIRED_ID, gate.begin(generation, 33, 8000, 8000))
        rejected(BeginRejection.ID_ALREADY_USED, gate.begin(generation, 4096, 8000, 8000))
        assertEquals(CompletionResult.COMPLETED, gate.complete(ticket(gate, 4097, 8000), 8000))
    }

    @Test fun outOfOrderFloorAdvanceNeverAdmitsAnOldIdOrForgedActiveTicket() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val active = ticket(gate, 1)
        assertEquals(CancelResult.TOMBSTONED, gate.cancel(generation, 50_000, 0))
        for (id in 2L..1023L) assertEquals(CancelResult.TOMBSTONED, gate.cancel(generation, id, 0))
        // Reclamation of the oldest terminal ID 50000 makes a delayed 1024 fail closed.
        rejected(BeginRejection.RETIRED_ID, gate.begin(generation, 1024, 0, 8000))
        assertEquals(CancelResult.RETIRED_ID, gate.cancel(generation, 49_999, 0))
        assertEquals(CompletionResult.INVALID_TICKET, gate.complete(
            ClientPrimaryRuntimeDnsGate.Ticket(generation, 1, active.deadlineElapsedMillis), 0))
        assertEquals(CompletionResult.COMPLETED, gate.complete(active, 0))
        assertEquals(CompletionResult.COMPLETED, gate.complete(ticket(gate, 50_001), 0))
    }

    @Test fun overflowingDeadlineSaturatesAndNeverWrapsOrAdmitsAtEndOfClock() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val admitted = ticket(gate, now = Long.MAX_VALUE - 4)
        assertEquals(Long.MAX_VALUE, admitted.deadlineElapsedMillis)
        assertEquals(CompletionResult.COMPLETED, gate.complete(admitted, Long.MAX_VALUE - 1))
        val lateGate = ClientPrimaryRuntimeDnsGate(generation)
        val late = ticket(lateGate, now = Long.MAX_VALUE - 4)
        assertEquals(CompletionResult.TIMED_OUT, lateGate.complete(late, Long.MAX_VALUE))
        rejected(BeginRejection.TIMED_OUT, lateGate.begin(generation, 2, Long.MAX_VALUE, 8000))
        rejected(BeginRejection.TIMED_OUT, lateGate.begin(generation, 2, Long.MAX_VALUE, 1))
    }

    @Test fun ticketIdentityCannotBeForgedOrUsedAcrossGatesWithSameGeneration() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val admitted = ticket(gate)
        val other = ticket(ClientPrimaryRuntimeDnsGate(generation))
        val copy = ClientPrimaryRuntimeDnsGate.Ticket(generation, 1, admitted.deadlineElapsedMillis)
        for (foreign in listOf(other, copy, ClientPrimaryRuntimeDnsGate.Ticket(generation, 2, 8000))) {
            assertEquals(CompletionResult.INVALID_TICKET, gate.complete(foreign, Long.MAX_VALUE))
        }
        assertEquals(CompletionResult.COMPLETED, gate.complete(admitted, 0))
    }

    @Test fun closeIsIdempotentAndBlocksEveryOutstandingAndFutureRequest() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val admitted = ticket(gate)
        gate.cancel(generation, 2, 0)
        gate.close()
        gate.close()
        assertEquals(CompletionResult.CLOSED, gate.complete(admitted, 0))
        for (id in 1L..3L) {
            rejected(BeginRejection.CLOSED, gate.begin(generation, id, 0, 8000))
            assertEquals(CancelResult.CLOSED, gate.cancel(generation, id, 0))
        }
    }

    @Test fun concurrentDuplicateAdmissionsLaunchExactlyOnce() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val results = race(List(16) { { gate.begin(generation, 1, 0, 8000) } })
        assertEquals(1, results.count { it is BeginResult.Admitted })
        assertEquals(15, results.count { it == BeginResult.Rejected(BeginRejection.DUPLICATE) })
    }

    @Test fun concurrentDistinctAdmissionsNeverExceed32AndRefusedIdsStayUsed() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val results = race((1L..40L).map { id -> { gate.begin(generation, id, 0, 8000) } })
        assertEquals(32, results.count { it is BeginResult.Admitted })
        assertEquals(8, results.count { it == BeginResult.Rejected(BeginRejection.IN_FLIGHT_LIMIT) })
        results.filterIsInstance<BeginResult.Admitted>().forEach {
            assertEquals(CompletionResult.COMPLETED, gate.complete(it.ticket, 0))
        }
        results.forEachIndexed { index, result ->
            val reason = if (result is BeginResult.Admitted) BeginRejection.ALREADY_COMPLETED else BeginRejection.ID_ALREADY_USED
            rejected(reason, gate.begin(generation, index + 1L, 0, 8000))
        }
    }

    @Test fun cancellationDeliveredOnAnotherThreadBeforeExchangeNeverAdmits() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val cancelled = CountDownLatch(1)
        val results = race<Any>(listOf(
            {
                val result = gate.cancel(generation, 1, 0)
                cancelled.countDown()
                result
            },
            {
                check(cancelled.await(5, TimeUnit.SECONDS))
                gate.begin(generation, 1, 0, 8000)
            }
        ))
        assertEquals(CancelResult.TOMBSTONED, results[0])
        assertEquals(BeginResult.Rejected(BeginRejection.CANCELLED), results[1])
    }

    @Test fun beginCancelRaceAlwaysLeavesRequestCancelled() {
        repeat(32) {
            val gate = ClientPrimaryRuntimeDnsGate(generation)
            val results = race<Any>(listOf(
                { gate.begin(generation, 1, 0, 8000) },
                { gate.cancel(generation, 1, 0) }
            ))
            val begin = results[0]
            if (begin is BeginResult.Admitted) {
                assertEquals(CancelResult.CANCELLED, results[1])
                assertEquals(CompletionResult.CANCELLED, gate.complete(begin.ticket, 0))
            } else {
                assertEquals(BeginResult.Rejected(BeginRejection.CANCELLED), begin)
                assertEquals(CancelResult.TOMBSTONED, results[1])
            }
        }
    }

    @Test fun completeCancelRaceHasExactlyOneTerminalWinner() {
        repeat(32) {
            val gate = ClientPrimaryRuntimeDnsGate(generation)
            val admitted = ticket(gate)
            val results = race<Any>(listOf(
                { gate.complete(admitted, 1) },
                { gate.cancel(generation, 1, 1) }
            ))
            if (results[0] == CompletionResult.COMPLETED) {
                assertEquals(CancelResult.ALREADY_COMPLETED, results[1])
                assertEquals(CompletionResult.ALREADY_COMPLETED, gate.complete(admitted, 1))
            } else {
                assertEquals(CompletionResult.CANCELLED, results[0])
                assertEquals(CancelResult.CANCELLED, results[1])
                assertEquals(CompletionResult.CANCELLED, gate.complete(admitted, 1))
            }
        }
    }

    @Test fun concurrentCompletionsPermitOnlyOneCommit() {
        val gate = ClientPrimaryRuntimeDnsGate(generation)
        val admitted = ticket(gate)
        val results = race(List(16) { { gate.complete(admitted, 0) } })
        assertEquals(1, results.count { it == CompletionResult.COMPLETED })
        assertEquals(15, results.count { it == CompletionResult.ALREADY_COMPLETED })
    }

    @Test fun timeoutWinsBothCallsAtDeadlineInEitherRaceOrder() {
        repeat(16) {
            val gate = ClientPrimaryRuntimeDnsGate(generation)
            val admitted = ticket(gate, timeout = 10)
            val results = race<Any>(listOf(
                { gate.complete(admitted, 10) },
                { gate.cancel(generation, 1, 10) }
            ))
            assertEquals(CompletionResult.TIMED_OUT, results[0])
            assertEquals(CancelResult.TIMED_OUT, results[1])
        }
    }

    @Test fun closeCompletionRaceCannotPermitACommitAfterCloseWins() {
        repeat(32) {
            val gate = ClientPrimaryRuntimeDnsGate(generation)
            val admitted = ticket(gate)
            val results = race<Any>(listOf({ gate.complete(admitted, 0) }, { gate.close() }))
            assertTrue(results[0] == CompletionResult.COMPLETED || results[0] == CompletionResult.CLOSED)
            assertEquals(CompletionResult.CLOSED, gate.complete(admitted, 0))
            rejected(BeginRejection.CLOSED, gate.begin(generation, 2, 0, 8000))
        }
    }

    @Test fun closeAdmissionRaceNeverLeavesUsableTicket() {
        repeat(16) {
            val gate = ClientPrimaryRuntimeDnsGate(generation)
            val results = race<Any>(listOf({ gate.begin(generation, 1, 0, 8000) }, { gate.close() }))
            val begin = results[0]
            if (begin is BeginResult.Admitted) {
                assertEquals(CompletionResult.CLOSED, gate.complete(begin.ticket, 0))
            } else {
                assertEquals(BeginResult.Rejected(BeginRejection.CLOSED), begin)
            }
        }
    }
}

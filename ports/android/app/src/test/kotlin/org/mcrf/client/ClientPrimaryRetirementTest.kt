package org.mcrf.client

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientPrimaryRetirementTest {
    @Test fun interruptionRetainsOwnershipAndPreservesTheSignal() {
        var pending=true
        val batch=ClientPrimaryRetirement(Any(),{},{},{if(pending)throw InterruptedException()},emptyList(),{})
        try {
            assertFalse(batch.attempt())
            assertTrue(Thread.interrupted())
            assertTrue(ClientPrimaryRetirement.hasPending())
            pending=false
            assertTrue(batch.attempt())
        }finally{
            pending=false;batch.attempt();Thread.interrupted()
        }
    }
    @Test
    fun exactOwnerIsClosedOnlyAfterStopAndCallbackDenial() {
        val f = Fixture()
        try {
            f.onOwnerClose = { owner ->
                assertSame(f.owner, owner)
                assertTrue(f.stopRequested.get())
                assertTrue(f.callbacksDenied.get())
                f.deliverCallback()
            }
            assertSame(f.owner, f.retirement.owner)
            assertTrue(ClientPrimaryRetirement.hasPending())
            assertTrue(f.retirement.attempt())
            assertEquals(0, f.deliveredCallbacks.get())
            assertEquals(ALL_STEPS, f.events.toList())
            assertTrue(f.retirement.complete)
            assertFalse(ClientPrimaryRetirement.hasPending())
            assertTrue(f.retirement.attempt())
            assertEquals(1, f.retirement.attempts)
            ALL_STEPS.forEach { assertEquals(it, 1, f.calls(it)) }
        } finally {
            f.drain()
        }
    }

    @Test
    fun stopFailureRetainsOwnerAndBlocksEveryLaterStep() {
        assertPreludeFailure("stop", listOf("stop"))
    }

    @Test
    fun callbackRetirementFailureRetainsOwnerAndDoesNotRepeatSuccessfulStop() {
        assertPreludeFailure("callbacks", listOf("stop", "callbacks"))
    }

    @Test
    fun repeatedOwnerCloseFailuresKeepDependenciesLeaseAndCompletionPending() {
        val f = Fixture()
        try {
            f.failures.add("owner")
            repeat(4) {
                assertFalse(f.retirement.attempt())
                assertSame(f.owner, f.retirement.owner)
                assertTrue(ClientPrimaryRetirement.hasPending())
                assertFalse(f.retirement.complete)
                listOf("tunnel", "bridge", "lease", "complete").forEach { step ->
                    assertEquals(step, 0, f.calls(step))
                }
            }
            assertEquals(1, f.calls("stop"))
            assertEquals(1, f.calls("callbacks"))
            assertEquals(4, f.calls("owner"))
            f.failures.clear()
            assertTrue(f.retirement.attempt())
            repeat(3) { assertTrue(f.retirement.attempt()) }
            listOf("tunnel", "bridge", "lease", "complete").forEach {
                assertEquals(it, 1, f.calls(it))
            }
            assertEquals(5, f.calls("owner"))
            assertFalse(ClientPrimaryRetirement.hasPending())
        } finally {
            f.drain()
        }
    }

    @Test
    fun workerDeathAcknowledgmentAllowsRetryToDrainExactlyOnce() {
        val f = Fixture()
        val workerStarted = CountDownLatch(1)
        val allowWorkerDeath = CountDownLatch(1)
        val deathAcknowledged = CountDownLatch(1)
        val worker = Thread({
            workerStarted.countDown()
            try {
                allowWorkerDeath.await()
            } finally {
                deathAcknowledged.countDown()
            }
        }, "retirement-test-owner")
        try {
            f.onOwnerClose = {
                check(deathAcknowledged.count == 0L) { "Owner has not acknowledged death" }
            }
            worker.start()
            await(workerStarted)
            repeat(3) { assertFalse(f.retirement.attempt()) }
            assertEquals(listOf("stop", "callbacks", "owner", "owner", "owner"), f.events.toList())
            assertTrue(ClientPrimaryRetirement.hasPending())
            allowWorkerDeath.countDown()
            await(deathAcknowledged)
            join(worker)
            assertTrue(f.retirement.attempt())
            assertTrue(f.retirement.attempt())
            assertEquals(4, f.calls("owner"))
            listOf("tunnel", "bridge", "lease", "complete").forEach {
                assertEquals(it, 1, f.calls(it))
            }
            assertFalse(ClientPrimaryRetirement.hasPending())
        } finally {
            allowWorkerDeath.countDown()
            try {
                if (worker.state != Thread.State.NEW) join(worker)
            } finally {
                f.drain()
            }
        }
    }

    @Test
    fun failingDependentRetainsCurrentAndRemainingStepsWithoutRepeatingEarlierCloses() {
        val f = Fixture()
        try {
            f.failures.add("bridge")
            repeat(3) { assertFalse(f.retirement.attempt()) }
            assertEquals(listOf("stop", "callbacks", "owner", "tunnel", "bridge", "bridge", "bridge"), f.events.toList())
            assertEquals(0, f.calls("lease"))
            assertEquals(0, f.calls("complete"))
            assertTrue(ClientPrimaryRetirement.hasPending())
            f.failures.clear()
            assertTrue(f.retirement.attempt())
            assertTrue(f.retirement.attempt())
            listOf("stop", "callbacks", "owner", "tunnel", "lease", "complete").forEach {
                assertEquals(it, 1, f.calls(it))
            }
            assertEquals(4, f.calls("bridge"))
            assertFalse(ClientPrimaryRetirement.hasPending())
        } finally {
            f.drain()
        }
    }

    @Test
    fun leaseFailurePreventsCompletionAndDoesNotDoubleCloseDependencies() {
        val f = Fixture()
        try {
            f.failures.add("lease")
            repeat(3) { assertFalse(f.retirement.attempt()) }
            assertTrue(ClientPrimaryRetirement.hasPending())
            assertEquals(0, f.calls("complete"))
            f.failures.clear()
            assertTrue(f.retirement.attempt())
            assertTrue(f.retirement.attempt())
            listOf("owner", "tunnel", "bridge", "complete").forEach {
                assertEquals(it, 1, f.calls(it))
            }
            assertEquals(4, f.calls("lease"))
        } finally {
            f.drain()
        }
    }

    @Test
    fun completionExceptionKeepsGlobalBarrierUntilCompletionReturns() {
        val f = Fixture()
        try {
            f.failures.add("complete")
            repeat(3) {
                assertFalse(f.retirement.attempt())
                assertFalse(f.retirement.complete)
                assertTrue(ClientPrimaryRetirement.hasPending())
            }
            f.failures.clear()
            assertTrue(f.retirement.attempt())
            assertTrue(f.retirement.attempt())
            ALL_STEPS.dropLast(1).forEach { assertEquals(it, 1, f.calls(it)) }
            assertEquals(4, f.calls("complete"))
            assertFalse(ClientPrimaryRetirement.hasPending())
        } finally {
            f.drain()
        }
    }

    @Test
    fun concurrentAttemptsSerializeAndReleaseResourcesOnlyOnce() {
        val f = Fixture()
        val ownerEntered = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val successfulAttempts = AtomicInteger()
        val first = Thread({
            try {
                if (f.retirement.attempt()) successfulAttempts.incrementAndGet()
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "retirement-test-first")
        val second = Thread({
            secondStarted.countDown()
            try {
                if (f.retirement.attempt()) successfulAttempts.incrementAndGet()
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "retirement-test-second")
        try {
            f.onOwnerClose = {
                ownerEntered.countDown()
                check(releaseOwner.await(5, TimeUnit.SECONDS)) { "Owner release timed out" }
            }
            first.start()
            await(ownerEntered)
            second.start()
            await(secondStarted)
            // Observe monitor contention, rather than relying on scheduling delays.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (second.isAlive && second.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, second.state)
            assertEquals(1, f.retirement.attempts)
            assertEquals(0, f.calls("tunnel"))
            assertTrue(ClientPrimaryRetirement.hasPending())
            releaseOwner.countDown()
            join(first)
            join(second)
            failure.get()?.let { throw AssertionError("Concurrent attempt failed", it) }
            assertEquals(2, successfulAttempts.get())
            assertEquals(1, f.retirement.attempts)
            ALL_STEPS.forEach { assertEquals(it, 1, f.calls(it)) }
            assertFalse(ClientPrimaryRetirement.hasPending())
        } finally {
            releaseOwner.countDown()
            try {
                if (first.state != Thread.State.NEW) join(first)
                if (second.state != Thread.State.NEW) join(second)
            } finally {
                f.drain()
            }
        }
    }

    @Test
    fun pendingBarrierSurvivesDroppingDestroyedServicesReference() {
        val f = Fixture()
        try {
            f.failures.add("owner")
            var serviceReference: ClientPrimaryRetirement<Any>? = f.retirement
            val survivingRetry = checkNotNull(serviceReference)
            assertFalse(survivingRetry.attempt())
            serviceReference = null // Destruction drops only the service's reference.
            assertNull(serviceReference)
            assertTrue(ClientPrimaryRetirement.hasPending())
            assertSame(f.owner, survivingRetry.owner)
            assertFalse(survivingRetry.complete)
            assertFalse(survivingRetry.attempt())
            assertEquals(0, f.calls("lease"))
            f.failures.clear()
            assertTrue(survivingRetry.attempt())
            assertEquals(1, f.calls("lease"))
            assertEquals(1, f.calls("complete"))
            assertFalse(ClientPrimaryRetirement.hasPending())
        } finally {
            f.drain()
        }
    }

    @Test
    fun completingAnotherRetirementCannotClearAnOlderPendingBarrier() {
        val old = Fixture()
        try {
            old.failures.add("owner")
            assertFalse(old.retirement.attempt())
            val newer = Fixture()
            try {
                assertTrue(newer.retirement.attempt())
                assertTrue(ClientPrimaryRetirement.hasPending())
                assertFalse(old.retirement.complete)
                assertEquals(0, old.calls("lease"))
                old.failures.clear()
                assertTrue(old.retirement.attempt())
                assertFalse(ClientPrimaryRetirement.hasPending())
            } finally {
                newer.drain()
            }
        } finally {
            old.drain()
        }
    }

    private fun assertPreludeFailure(step: String, prefix: List<String>) {
        val f = Fixture()
        try {
            f.failures.add(step)
            repeat(3) {
                assertFalse(f.retirement.attempt())
                assertTrue(ClientPrimaryRetirement.hasPending())
                assertSame(f.owner, f.retirement.owner)
                assertEquals(0, f.calls("owner"))
                assertEquals(0, f.calls("lease"))
                assertEquals(0, f.calls("complete"))
            }
            assertEquals(prefix + List(2) { step }, f.events.toList())
            f.failures.clear()
            assertTrue(f.retirement.attempt())
            assertEquals(4, f.calls(step))
            if (step == "callbacks") assertEquals(1, f.calls("stop"))
            assertEquals(1, f.calls("owner"))
            assertEquals(1, f.calls("lease"))
            assertFalse(ClientPrimaryRetirement.hasPending())
        } finally {
            f.drain()
        }
    }

    // Only resources/fault hooks are simulated. All ordering, retry progress,
    // serialization and process-wide retention belong to the production helper.
    private class Fixture {
        val owner = Any()
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val failures: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val stopRequested = AtomicBoolean()
        val callbacksDenied = AtomicBoolean()
        val deliveredCallbacks = AtomicInteger()
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        var onOwnerClose: (Any) -> Unit = {}
        val retirement = ClientPrimaryRetirement(
            owner = owner,
            requestStop = { step("stop"); stopRequested.set(true) },
            retireCallbacks = { step("callbacks"); callbacksDenied.set(true) },
            closeOwner = { exactOwner -> step("owner"); onOwnerClose(exactOwner) },
            dependents = listOf({ step("tunnel") }, { step("bridge") }, { step("lease") }),
            completed = { step("complete") }
        )

        fun calls(name: String): Int = counts[name]?.get() ?: 0

        fun deliverCallback() {
            if (!callbacksDenied.get()) deliveredCallbacks.incrementAndGet()
        }

        fun drain() {
            failures.clear()
            onOwnerClose = {}
            assertTrue("Test must drain its own retirement", retirement.attempt())
            assertTrue(retirement.complete)
        }

        private fun step(name: String) {
            counts.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
            events.add(name)
            check(name !in failures) { "Injected $name failure" }
        }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Latch timed out", latch.await(5, TimeUnit.SECONDS))
    }

    private fun join(thread: Thread) {
        thread.join(5000)
        assertFalse("Thread did not finish: ${thread.name}", thread.isAlive)
    }

    companion object {
        private val ALL_STEPS = listOf("stop", "callbacks", "owner", "tunnel", "bridge", "lease", "complete")
    }
}

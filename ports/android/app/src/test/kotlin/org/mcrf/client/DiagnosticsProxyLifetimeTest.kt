package org.mcrf.client

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsProxyLifetimeTest {
    @Test fun cancellationDuringStartupStopsLateChildAndRetiresBeforeLease() {
        val events=Collections.synchronizedList(mutableListOf<String>())
        val released=CountDownLatch(1)
        val owner=DiagnosticsProxyLifetime(5000,AutoCloseable {events.add("lease");released.countDown()})
        owner.close()
        assertTrue(owner.isStopped())
        owner.adopt(AutoCloseable {events.add("child")}) {events.add("stop")}
        assertEquals(listOf("stop"),events.toList())
        assertFalse(released.await(30,TimeUnit.MILLISECONDS))
        owner.startupFinished();assertTrue(released.await(2000,TimeUnit.MILLISECONDS))
        owner.awaitClosed();owner.close()
        assertEquals(listOf("stop","child","lease"),events.toList())
    }
    @Test fun watchdogClosesForgottenProxyWithoutUiOrFurtherRequests() {
        val released=CountDownLatch(1);val closes=AtomicInteger()
        val owner=DiagnosticsProxyLifetime(40,AutoCloseable {released.countDown()})
        owner.adopt(AutoCloseable {closes.incrementAndGet()});owner.startupFinished()
        assertTrue(released.await(2000,TimeUnit.MILLISECONDS))
        owner.awaitClosed();assertTrue(owner.isStopped());assertEquals(1,closes.get())
    }
    @Test fun failedChildCloseRetainsLeaseUntilRetryActuallyCompletes() {
        val failed=CountDownLatch(1);val retry=CountDownLatch(1);val released=CountDownLatch(1)
        val attempts=AtomicInteger()
        val owner=DiagnosticsProxyLifetime(5000,AutoCloseable {released.countDown()})
        owner.adopt(AutoCloseable {
            if(attempts.incrementAndGet()==1){failed.countDown();throw IllegalStateException()}
            check(retry.await(2000,TimeUnit.MILLISECONDS))
        })
        owner.startupFinished();owner.close()
        assertTrue(failed.await(2000,TimeUnit.MILLISECONDS));assertEquals(1L,released.count)
        retry.countDown();assertTrue(released.await(2000,TimeUnit.MILLISECONDS));owner.awaitClosed()
        assertEquals(2,attempts.get())
    }
    @Test fun refreshingAStoppedOwnerCannotResurrectItsLeaseOrChild() {
        val closes=AtomicInteger();val releases=AtomicInteger()
        val owner=DiagnosticsProxyLifetime(5000,AutoCloseable {releases.incrementAndGet()})
        owner.adopt(AutoCloseable {closes.incrementAndGet()});owner.startupFinished()
        repeat(10){assertTrue(owner.refresh())}
        owner.close();owner.awaitClosed()
        assertFalse(owner.refresh());assertEquals(1,closes.get());assertEquals(1,releases.get())
    }
}

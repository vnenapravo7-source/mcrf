package org.mcrf.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ServiceDiagnosticsWorkTest {
    @Test fun multiServerBudgetAboveThreeMinutesStartsAndCompletes() {
        val work = ServiceDiagnosticsWork()
        val done = CountDownLatch(1)
        val result = AtomicReference<DiagnosticsWorkResult<Int>>()
        try {
            work.start(900_000, { token -> token.check(); 42 }) { result.set(it); done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(DiagnosticState.AVAILABLE, result.get().state)
            assertEquals(42, result.get().value)
        } finally { work.close() }
    }
    @Test fun deadlineReportsTimeoutAndClosesOwnedResourceExactlyOnce() {
        val work = ServiceDiagnosticsWork(); val entered = CountDownLatch(1); val released = CountDownLatch(1); val completed = CountDownLatch(1)
        val callbacks = AtomicInteger(); val result = AtomicReference<DiagnosticsWorkResult<Int>>()
        try {
            work.start(500, { token ->
                token.attach(AutoCloseable { released.countDown() }); entered.countDown()
                released.await(2, TimeUnit.SECONDS); token.check(); 1
            }) { result.set(it); callbacks.incrementAndGet(); completed.countDown() }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertTrue(completed.await(2, TimeUnit.SECONDS)); assertTrue(released.await(1, TimeUnit.SECONDS))
            assertEquals(DiagnosticIssue.TIMEOUT, result.get().issue); assertEquals(1, callbacks.get())
        } finally { work.close() }
    }
    @Test fun explicitCancelCannotBeOverwrittenByLateSuccess() {
        val work = ServiceDiagnosticsWork(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val drained = CountDownLatch(1)
        val callbacks = AtomicInteger(); val result = AtomicReference<DiagnosticsWorkResult<Int>>()
        val completed = CountDownLatch(1)
        try {
            val job = work.start(1000, { token ->
                try { token.attach(AutoCloseable { release.countDown() }); entered.countDown(); release.await(1, TimeUnit.SECONDS); 42 }
                finally { drained.countDown() }
            }) { callbacks.incrementAndGet(); result.set(it); completed.countDown() }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); job.close(); assertTrue(drained.await(1, TimeUnit.SECONDS))
            assertTrue(completed.await(1, TimeUnit.SECONDS)); assertEquals(DiagnosticState.CANCELLED, result.get().state); assertEquals(1, callbacks.get())
        } finally { release.countDown(); work.close() }
    }
    @Test fun nativeMessagesDoNotCrossCompletionBoundary() {
        val work = ServiceDiagnosticsWork(); val done = CountDownLatch(1); val result = AtomicReference<DiagnosticsWorkResult<Int>>()
        try {
            work.start<Int>(1000, { throw IllegalArgumentException("https://secret@example.org/?token=secret") }) { result.set(it); done.countDown() }
            assertTrue(done.await(1, TimeUnit.SECONDS)); assertEquals(DiagnosticIssue.INTERNAL, result.get().issue)
            assertFalse(result.toString().contains("secret"))
        } finally { work.close() }
    }
    @Test fun parentCancellationClosesChildBudgetResources() {
        val parent = DiagnosticCancellation(1000); val closed = AtomicInteger()
        try {
            withDiagnosticBudget(parent, 500) { child ->
                child.attach(AutoCloseable { closed.incrementAndGet() }); parent.cancel(); child.check()
            }
            fail()
        } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(1, closed.get())
    }
}

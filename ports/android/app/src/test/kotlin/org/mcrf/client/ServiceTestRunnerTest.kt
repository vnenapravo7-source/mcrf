package org.mcrf.client

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ServiceTestRunnerTest {
    private val ids = setOf("github-site", "netflix-site", "signal-site")
    private class Reports : (DiagnosticReport) -> Unit {
        val done = CountDownLatch(1)
        val latest = AtomicReference<DiagnosticReport>()
        val snapshots = CopyOnWriteArrayList<DiagnosticReport>()
        override fun invoke(report: DiagnosticReport) {
            latest.set(report); snapshots.add(report)
            if (report.complete) done.countDown()
        }
        fun await(): DiagnosticReport { assertTrue("run did not finish", done.await(4, TimeUnit.SECONDS)); return latest.get() }
    }
    @Test fun parallelismIsBoundedAndEveryTargetGetsBothDistinctPaths() {
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val entered = CountDownLatch(2); val release = CountDownLatch(1); val reports = Reports()
        val runner = ServiceTestRunner(ServiceProbe { request, token ->
            val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }; entered.countDown()
            try { assertTrue(release.await(2, TimeUnit.SECONDS)); token.check(); DiagnosticResult.of(request, DiagnosticState.AVAILABLE) }
            finally { active.decrementAndGet() }
        }, parallelism = 2, timeoutMs = 2500, budgetMs = 5000)
        try {
            runner.start(ids, reports); assertTrue(entered.await(1, TimeUnit.SECONDS)); release.countDown()
            val report = reports.await()
            assertEquals(2, maximum.get()); assertEquals(6, report.results.size)
            assertTrue(report.results.all { it.state == DiagnosticState.AVAILABLE })
            ids.forEach { id -> assertEquals(DiagnosticPath.values().toSet(), report.results.filter { it.targetId == id }.map { it.path }.toSet()) }
            assertTrue(reports.snapshots.first().results.all { it.state == DiagnosticState.WAITING })
        } finally { release.countDown(); runner.close() }
    }
    @Test fun cancelClosesInflightIoAndFinalizesQueuedRows() {
        val entered = CountDownLatch(2); val closed = AtomicInteger(); val reports = Reports()
        val runner = ServiceTestRunner(ServiceProbe { request, token ->
            val wait = CountDownLatch(1)
            token.attach(AutoCloseable { closed.incrementAndGet(); wait.countDown() })
            entered.countDown(); wait.await(2, TimeUnit.SECONDS); token.check()
            DiagnosticResult.of(request, DiagnosticState.AVAILABLE)
        }, parallelism = 2)
        try {
            val run = runner.start(ids, reports); assertTrue(entered.await(1, TimeUnit.SECONDS)); run.cancel()
            assertTrue(reports.await().results.all { it.state == DiagnosticState.CANCELLED })
            assertEquals(2, closed.get())
        } finally { runner.close() }
    }
    @Test fun deadlineClosesBlockingResourceAndIsNotReportedAsAvailability() {
        val closed = AtomicInteger(); val reports = Reports()
        val runner = ServiceTestRunner(ServiceProbe { request, token ->
            val wait = CountDownLatch(1)
            token.attach(AutoCloseable { closed.incrementAndGet(); wait.countDown() })
            wait.await(2, TimeUnit.SECONDS); token.check()
            DiagnosticResult.of(request, DiagnosticState.AVAILABLE)
        }, parallelism = 2, timeoutMs = 80, budgetMs = 2000)
        try {
            runner.start(setOf("github-site"), reports)
            assertTrue(reports.await().results.all { it.state == DiagnosticState.ERROR && it.issue == DiagnosticIssue.TIMEOUT })
            assertEquals(2, closed.get())
        } finally { runner.close() }
    }
    @Test fun wholeRunBudgetFinalizesWaitingRowsAsUnmeasured() {
        val reports = Reports()
        val runner = ServiceTestRunner(ServiceProbe { request, token ->
            val wait = CountDownLatch(1); token.attach(AutoCloseable { wait.countDown() })
            wait.await(2, TimeUnit.SECONDS); token.check(); DiagnosticResult.of(request, DiagnosticState.AVAILABLE)
        }, parallelism = 1, timeoutMs = 2000, budgetMs = 60)
        try {
            runner.start(ids, reports)
            assertTrue(reports.await().results.all { it.state == DiagnosticState.UNAVAILABLE && it.issue == DiagnosticIssue.BUDGET_EXHAUSTED })
        } finally { runner.close() }
    }
    @Test fun secondRunCannotOverlapAndUnknownKeysCannotStart() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val reports = Reports()
        val runner = ServiceTestRunner(ServiceProbe { request, token ->
            token.attach(AutoCloseable { release.countDown() }); entered.countDown()
            release.await(2, TimeUnit.SECONDS); token.check(); DiagnosticResult.of(request, DiagnosticState.AVAILABLE)
        }, parallelism = 1)
        try {
            try { runner.start(setOf("https://secret@host/path"), reports); fail() } catch (_: IllegalArgumentException) { }
            val run = runner.start(ids, reports); assertTrue(entered.await(1, TimeUnit.SECONDS))
            try { runner.start(ids, reports); fail() } catch (_: IllegalStateException) { }
            run.cancel(); reports.await()
        } finally { release.countDown(); runner.close() }
    }
    @Test fun callbacksReceiveTypedErrorsWithoutThrowableDetails() {
        val reports = Reports()
        val runner = ServiceTestRunner(ServiceProbe { _, _ -> throw IllegalArgumentException("https://secret@example.org/?token=secret") })
        try {
            runner.start(setOf("github-site"), reports)
            val report = reports.await()
            assertTrue(report.results.all { it.issue == DiagnosticIssue.INTERNAL })
            assertFalse(report.toString().contains("secret")); assertFalse(report.toString().contains("example.org"))
        } finally { runner.close() }
    }
    @Test fun explicitFamilyIsPreservedForFutureIpv6Consumers() {
        val reports = Reports()
        val runner = ServiceTestRunner(ServiceProbe { request, _ ->
            DiagnosticResult.of(request, DiagnosticState.UNAVAILABLE, DiagnosticIssue.UNSUPPORTED_PROTOCOL)
        })
        try {
            runner.start(setOf("github-site"), DiagnosticFamily.IPV6, reports)
            assertTrue(reports.await().results.all { it.family == DiagnosticFamily.IPV6 })
        } finally { runner.close() }
    }
    @Test fun immediateCloseStillProducesAFinalCancelledSnapshot() {
        val reports = Reports(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val runner = ServiceTestRunner(ServiceProbe { request, token ->
            token.attach(AutoCloseable { release.countDown() }); entered.countDown()
            release.await(2, TimeUnit.SECONDS); token.check(); DiagnosticResult.of(request, DiagnosticState.AVAILABLE)
        }, parallelism = 1)
        try {
            runner.start(ids, reports); assertTrue(entered.await(1, TimeUnit.SECONDS)); runner.close()
            assertTrue(reports.await().results.all { it.state == DiagnosticState.CANCELLED })
        } finally { release.countDown(); runner.close() }
    }
}

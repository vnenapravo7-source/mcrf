package org.mcrf.client

import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** One run per owner; at most parallelism blocking probes, bounded executor queue,
 * per-probe and whole-run deadlines. Cancellation closes owned IO and drains all
 * waiting/running rows. Callbacks are serialized on the coordinator, never UI. */
class ServiceTestRunner(
    probe: ServiceProbe,
    private val parallelism: Int = 4,
    private val timeoutMs: Long = 6000,
    private val budgetMs: Long = 60_000
) : AutoCloseable {
    private val pipeline = DiagnosticsPipeline(probe)
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val workers: ThreadPoolExecutor
    private val coordinator: ThreadPoolExecutor
    @Volatile private var active: Run? = null
    init {
        require(parallelism in 1..8 && timeoutMs in 1..30_000 && budgetMs in 1..120_000)
        fun factory(name: String) = ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }
        workers = ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(parallelism), factory("mcrf-service-probe"))
        coordinator = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1), factory("mcrf-service-tests"))
    }
    class Run internal constructor() {
        internal val cancelled = AtomicBoolean()
        internal val tokens = ConcurrentHashMap.newKeySet<DiagnosticCancellation>()
        fun cancel() { cancelled.set(true); tokens.forEach { it.cancel() } }
    }
    private class Pending(val index: Int, val token: DiagnosticCancellation, val future: Future<DiagnosticResult>, val deadline: Long, val started: AtomicBoolean)

    fun start(targetIds: Set<String>, update: (DiagnosticReport) -> Unit): Run {
        return start(targetIds, DiagnosticFamily.AUTOMATIC, update)
    }
    @Synchronized fun start(targetIds: Set<String>, family: DiagnosticFamily, update: (DiagnosticReport) -> Unit): Run {
        require(targetIds.isNotEmpty() && targetIds.size <= ServiceTestCatalog.targets.size)
        val targets = ServiceTestCatalog.targets.filter { it.id in targetIds }
        require(targets.size == targetIds.size) // No arbitrary or stale endpoints.
        check(!closed.get() && busy.compareAndSet(false, true))
        val run = Run()
        active = run
        try {
            coordinator.execute { perform(run, targets, family, update) }
        } catch (failure: RejectedExecutionException) {
            active = null; busy.set(false); throw failure
        }
        return run
    }
    private fun perform(run: Run, targets: List<ServiceTestTarget>, family: DiagnosticFamily, update: (DiagnosticReport) -> Unit) {
        val requests = targets.flatMap { target -> DiagnosticPath.values().map { DiagnosticRequest(target, it, family) } }
        val results = requests.map { DiagnosticResult.of(it, DiagnosticState.WAITING) }.toMutableList()
        val runId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val pending = mutableListOf<Pending>()
        var cursor = 0
        fun publish(complete: Boolean = false) { runCatching { update(DiagnosticReport(runId, startedAt, results.toList(), complete)) } }
        fun terminal(index: Int, state: DiagnosticState, issue: DiagnosticIssue) {
            results[index] = DiagnosticResult.of(requests[index], state, issue)
        }
        try {
            publish()
            while (cursor < requests.size || pending.isNotEmpty()) {
                val cancelled = run.cancelled.get() || closed.get() || Thread.currentThread().isInterrupted
                val expired = System.nanoTime() >= deadline
                if (cancelled || expired) {
                    for (i in results.indices) if (results[i].state in setOf(DiagnosticState.WAITING, DiagnosticState.RUNNING))
                        terminal(i, if (cancelled) DiagnosticState.CANCELLED else DiagnosticState.UNAVAILABLE,
                            if (cancelled) DiagnosticIssue.CANCELLED else DiagnosticIssue.BUDGET_EXHAUSTED)
                    break
                }
                var changed = false
                while (cursor < requests.size && pending.size < parallelism) {
                    val index = cursor++
                    val token = DiagnosticCancellation(timeoutMs)
                    run.tokens.add(token)
                    if (run.cancelled.get()) token.cancel()
                    val started = AtomicBoolean()
                    try {
                        val future = workers.submit<DiagnosticResult> {
                            started.set(true)
                            pipeline.execute(requests[index], token)
                        }
                        results[index] = DiagnosticResult.of(requests[index], if (started.get()) DiagnosticState.RUNNING else DiagnosticState.WAITING)
                        pending.add(Pending(index, token, future, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs), started))
                    } catch (_: RejectedExecutionException) {
                        run.tokens.remove(token); token.cancel()
                        terminal(index, DiagnosticState.UNAVAILABLE, DiagnosticIssue.CAPACITY)
                    }
                    changed = true
                }
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    if (item.started.get() && results[item.index].state == DiagnosticState.WAITING) {
                        results[item.index] = DiagnosticResult.of(requests[item.index], DiagnosticState.RUNNING); changed = true
                    }
                    if (!item.future.isDone && System.nanoTime() < item.deadline) continue
                    results[item.index] = if (item.future.isDone) try { item.future.get() }
                        catch (_: Exception) { DiagnosticResult.of(requests[item.index], DiagnosticState.ERROR, DiagnosticIssue.INTERNAL) }
                    else DiagnosticResult.of(requests[item.index], if (item.started.get()) DiagnosticState.ERROR else DiagnosticState.UNAVAILABLE,
                        if (item.started.get()) DiagnosticIssue.TIMEOUT else DiagnosticIssue.CAPACITY)
                    item.token.cancel(); item.future.cancel(true); run.tokens.remove(item.token)
                    iterator.remove(); changed = true
                }
                if (changed) publish()
                if (pending.isNotEmpty()) Thread.sleep(20)
            }
        } catch (_: InterruptedException) {
            run.cancelled.set(true); Thread.currentThread().interrupt()
            results.indices.filter { results[it].state in setOf(DiagnosticState.RUNNING, DiagnosticState.WAITING) }
                .forEach { terminal(it, DiagnosticState.CANCELLED, DiagnosticIssue.CANCELLED) }
        } finally {
            pending.forEach { it.token.cancel(); it.future.cancel(true) }
            run.tokens.forEach { it.cancel() }; run.tokens.clear(); workers.purge()
            active = null; busy.set(false)
            publish(true)
        }
    }
    @Synchronized override fun close() {
        if (closed.compareAndSet(false, true)) {
            // A queued coordinator must still emit the cancelled final snapshot.
            active?.cancel(); coordinator.shutdown(); workers.shutdownNow()
        }
    }
}

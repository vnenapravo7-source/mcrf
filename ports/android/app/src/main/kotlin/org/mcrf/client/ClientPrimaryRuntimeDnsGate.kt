package org.mcrf.client

/**
 * Host admission and result-commit gate for one positive Engine.Request.generation.
 * No resolver, packet, IPC, callback invocation, timer, or worker is owned here.
 *
 * Main integration hook (host implementation of the pinned Platform SPI):
 * - Keep ONE instance for the active run, shared by exchangeDns and cancelDns. Check
 *   the active generation before selecting it; never create a gate for an incoming ID.
 * - In exchangeDns, call begin(generation, query.requestId, elapsedNow, query.timeoutMillis)
 *   BEFORE dispatching resolver work. Only BeginResult.Admitted permits one dispatch.
 *   Retain its exact host-local ticket; use its absolute deadline, including queue time.
 * - In cancelDns, call cancel on that same gate even if no resolver job exists yet.
 *   Separately cancel/close any host resolver resource associated with the request ID.
 * - Before committing a resolver success OR failure to the reply, call complete with
 *   the retained ticket and fresh elapsed time. Only COMPLETED permits that one commit.
 *   All other results discard the late/duplicate reply. This is not the module's
 *   callback guard and does not invoke or manufacture any module callback.
 * - Call close before retiring a run (stop, worker death, core switch). The host still
 *   owns resolver cancellation, deadline wakeups and resource cleanup; this gate only
 *   denies admission/commit. It does not acknowledge physical resolver termination.
 *
 * Calls are serialized by a private monitor. The first terminal transition wins;
 * cancellation after a committed completion cannot retract it. Inputs use one
 * nonnegative monotonic elapsed clock. A time below the last accepted time is refused
 * without mutation; callers racing with an older sampled time must resample it.
 * Equal times are valid. Expiration is inclusive (now >= deadline), evaluated lazily
 * on valid timed calls; it frees admission slots without requiring a scheduler.
 *
 * IDs are positive, monotonically allocated by the module, and never wrap or repeat.
 * At most 1024 records are retained, including at most 32 active tickets. To make room,
 * reclaim the oldest terminal record and advance a permanent retired-ID floor to its
 * ID. An absent ID at/below that floor is always refused, including an early cancel
 * whose individual tombstone has been reclaimed. Out-of-order old requests may fail
 * closed; their caller can retry using a fresh larger ID. Active tickets are NEVER
 * reclaimed or invalidated by advancing the floor, even when their IDs are below it.
 * This bounds memory without imposing a lifetime request-count limit on a healthy run.
 * Malformed/stale calls consume neither clock nor ID budget.
 */
internal class ClientPrimaryRuntimeDnsGate(val generation: Long) : AutoCloseable {
    init {
        require(generation > 0) { "A positive run generation is required" }
    }

    companion object {
        const val MAX_TIMEOUT_MILLIS = 8000
        const val MAX_IN_FLIGHT = 32
        const val MAX_REMEMBERED_IDS = 1024
    }

    /** Immutable host-local capability. Even identical fields cannot replace its identity. */
    class Ticket internal constructor(
        val generation: Long,
        val requestId: Long,
        val deadlineElapsedMillis: Long
    )

    sealed class BeginResult {
        data class Admitted(val ticket: Ticket) : BeginResult()
        data class Rejected(val reason: BeginRejection) : BeginResult()
    }

    enum class BeginRejection {
        STALE_GENERATION, CLOSED, INVALID_REQUEST_ID, INVALID_TIMEOUT, INVALID_TIME,
        DUPLICATE, CANCELLED, TIMED_OUT, ALREADY_COMPLETED, ID_ALREADY_USED,
        IN_FLIGHT_LIMIT, RETIRED_ID
    }

    enum class CancelResult {
        CANCELLED, TOMBSTONED, ALREADY_CANCELLED, TIMED_OUT, ALREADY_COMPLETED,
        ALREADY_REFUSED, STALE_GENERATION, CLOSED, INVALID_REQUEST_ID, INVALID_TIME,
        RETIRED_ID
    }

    enum class CompletionResult {
        COMPLETED, ALREADY_COMPLETED, CANCELLED, TIMED_OUT, STALE_GENERATION,
        CLOSED, INVALID_TICKET, INVALID_TIME
    }

    private enum class State { OPEN, CANCELLED, TIMED_OUT, COMPLETED, REFUSED }

    private class Entry(val ticket: Ticket?, var state: State)

    private val lock = Any()
    private val entries = LinkedHashMap<Long, Entry>()
    private var retiredIdFloor = 0L
    private var lastElapsedMillis = 0L
    private var inFlight = 0
    private var closed = false

    fun begin(
        generation: Long,
        requestId: Long,
        nowElapsedMillis: Long,
        timeoutMillis: Int
    ): BeginResult = synchronized(lock) {
        fun reject(reason: BeginRejection) = BeginResult.Rejected(reason)
        if (generation != this.generation) return@synchronized reject(BeginRejection.STALE_GENERATION)
        if (closed) return@synchronized reject(BeginRejection.CLOSED)
        if (requestId <= 0) return@synchronized reject(BeginRejection.INVALID_REQUEST_ID)
        if (timeoutMillis !in 1..MAX_TIMEOUT_MILLIS) return@synchronized reject(BeginRejection.INVALID_TIMEOUT)
        if (!advance(nowElapsedMillis)) return@synchronized reject(BeginRejection.INVALID_TIME)

        entries[requestId]?.let { entry ->
            return@synchronized reject(when (entry.state) {
                State.OPEN -> BeginRejection.DUPLICATE
                State.CANCELLED -> BeginRejection.CANCELLED
                State.TIMED_OUT -> BeginRejection.TIMED_OUT
                State.COMPLETED -> BeginRejection.ALREADY_COMPLETED
                State.REFUSED -> BeginRejection.ID_ALREADY_USED
            })
        }
        if (requestId <= retiredIdFloor) return@synchronized reject(BeginRejection.RETIRED_ID)
        makeRoom()
        // Reclaiming an out-of-order terminal record can raise the floor past this ID.
        if (requestId <= retiredIdFloor) return@synchronized reject(BeginRejection.RETIRED_ID)

        // Saturation may shorten an unrepresentable deadline, but can never extend it.
        val timeout = timeoutMillis.toLong()
        val deadline = if (nowElapsedMillis > Long.MAX_VALUE - timeout) Long.MAX_VALUE else nowElapsedMillis + timeout
        if (deadline <= nowElapsedMillis) {
            entries[requestId] = Entry(null, State.TIMED_OUT)
            return@synchronized reject(BeginRejection.TIMED_OUT)
        }
        if (inFlight == MAX_IN_FLIGHT) {
            entries[requestId] = Entry(null, State.REFUSED)
            return@synchronized reject(BeginRejection.IN_FLIGHT_LIMIT)
        }
        val ticket = Ticket(generation, requestId, deadline)
        entries[requestId] = Entry(ticket, State.OPEN)
        inFlight++
        BeginResult.Admitted(ticket)
    }

    fun cancel(generation: Long, requestId: Long, nowElapsedMillis: Long): CancelResult = synchronized(lock) {
        if (generation != this.generation) return@synchronized CancelResult.STALE_GENERATION
        if (closed) return@synchronized CancelResult.CLOSED
        if (requestId <= 0) return@synchronized CancelResult.INVALID_REQUEST_ID
        if (!advance(nowElapsedMillis)) return@synchronized CancelResult.INVALID_TIME

        val entry = entries[requestId]
        if (entry == null) {
            if (requestId <= retiredIdFloor) return@synchronized CancelResult.RETIRED_ID
            makeRoom()
            if (requestId <= retiredIdFloor) return@synchronized CancelResult.RETIRED_ID
            entries[requestId] = Entry(null, State.CANCELLED)
            return@synchronized CancelResult.TOMBSTONED
        }
        when (entry.state) {
            State.OPEN -> {
                entry.state = State.CANCELLED
                inFlight--
                CancelResult.CANCELLED
            }
            State.CANCELLED -> CancelResult.ALREADY_CANCELLED
            State.TIMED_OUT -> CancelResult.TIMED_OUT
            State.COMPLETED -> CancelResult.ALREADY_COMPLETED
            State.REFUSED -> CancelResult.ALREADY_REFUSED
        }
    }

    fun complete(ticket: Ticket, nowElapsedMillis: Long): CompletionResult = synchronized(lock) {
        if (ticket.generation != generation) return@synchronized CompletionResult.STALE_GENERATION
        if (closed) return@synchronized CompletionResult.CLOSED
        val entry = entries[ticket.requestId]
        // Field equality would accept tickets from another gate or a reconstructed DTO.
        if (entry == null || entry.ticket !== ticket) return@synchronized CompletionResult.INVALID_TICKET
        if (!advance(nowElapsedMillis)) return@synchronized CompletionResult.INVALID_TIME
        when (entry.state) {
            State.OPEN -> {
                entry.state = State.COMPLETED
                inFlight--
                CompletionResult.COMPLETED
            }
            State.CANCELLED -> CompletionResult.CANCELLED
            State.TIMED_OUT -> CompletionResult.TIMED_OUT
            State.COMPLETED -> CompletionResult.ALREADY_COMPLETED
            State.REFUSED -> CompletionResult.INVALID_TICKET
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            // Clearing is safe only here: closed never reopens and every ticket is denied.
            entries.clear()
            inFlight = 0
        }
    }

    /** At capacity there must be a terminal entry: active admission is capped at 32. */
    private fun makeRoom() {
        if (entries.size < MAX_REMEMBERED_IDS) return
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.state != State.OPEN) {
                retiredIdFloor = maxOf(retiredIdFloor, entry.key)
                iterator.remove()
                return
            }
        }
        error("DNS_GATE_ACTIVE_LIMIT_INVARIANT")
    }

    /** Called only under lock after envelope/identity checks; at most 1024 records. */
    private fun advance(nowElapsedMillis: Long): Boolean {
        if (nowElapsedMillis < 0 || nowElapsedMillis < lastElapsedMillis) return false
        lastElapsedMillis = nowElapsedMillis
        for (entry in entries.values) {
            if (entry.state == State.OPEN && nowElapsedMillis >= entry.ticket!!.deadlineElapsedMillis) {
                entry.state = State.TIMED_OUT
                inFlight--
            }
        }
        return true
    }
}

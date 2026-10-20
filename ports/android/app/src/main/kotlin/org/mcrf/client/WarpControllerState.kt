package org.mcrf.client

import java.util.concurrent.CancellationException

/** One linearization point for job cancellation, deletion and commit. No UI/native dependencies. */
class WarpControllerState {
    class Ticket internal constructor(val generation: Long, val previousId: String?)
    private var generation = 0L
    private var closed = false
    private var active: Ticket? = null
    @Synchronized fun begin(previousId: String?): Ticket {
        check(!closed) { "Контроллер WARP закрыт." }
        return Ticket(++generation, previousId).also { active = it }
    }
    @Synchronized fun cancelled(ticket: Ticket): Boolean = closed || generation != ticket.generation
    @Synchronized fun running(): Boolean = active != null
    @Synchronized fun current(ticket: Ticket): Boolean = !cancelled(ticket) && active === ticket
    @Synchronized fun finish(ticket: Ticket): Boolean {
        if(!current(ticket)) return false
        active = null; return true
    }
    /** Action and invalidation cannot interleave. Action must be a short local transaction. */
    @Synchronized fun <T> commit(ticket: Ticket, action: () -> T): T {
        if(!current(ticket)) throw CancellationException("Подбор WARP отменён.")
        return action().also { active = null }
    }
    @Synchronized fun cancel() { generation++; active = null }
    @Synchronized fun <T> remove(id: String, action: () -> T): T {
        check(!closed)
        require(id.isNotBlank())
        // Every catalogue mutation invalidates an in-flight result, including a new registration.
        cancel()
        return action()
    }
    @Synchronized fun close() { cancel(); closed = true }
}

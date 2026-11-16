package org.mcrf.client

/** Separate kind and capability from offline own-inline HTML.
 * No production NativeCarrier/config/UI caller enables this yet. */
internal object OpenFluxHttpVerificationPolicy {
    const val productionEnabled = false
    fun sourceCompatible(verification: Boolean, httpContract: Int) = verification && httpContract == 1
}

/** Source adapter only. Must be before all diagnostics when eventually bound.
 * The production host currently discards this entire reserved family instead. */
internal object OpenFluxHttpDispatch {
    enum class State { NOT_HTTP, CONSUMED, PENDING, STALE, REJECTED }
    class Result(val state: State, val request: OpenFluxVerificationRegistry.Request? = null) {
        override fun toString() = "OpenFluxHttpDispatch($state)"
    }
    fun receive(bytes: ByteArray, child: String, enabled: Boolean, closed: Boolean,
                registry: OpenFluxVerificationRegistry, currentUiId: String?, observedTicket: String?,
                alive: () -> Boolean, offer: (ByteArray) -> Boolean): Result {
        if (!OpenFluxHttpWire.recognized(bytes)) return Result(State.NOT_HTTP)
        if (closed) return Result(State.CONSUMED)
        if (!enabled) return Result(State.REJECTED)
        OpenFluxHttpWire.pending(bytes)?.let { pending ->
            if (!try { alive() } catch (_: Exception) { false }) return Result(State.STALE)
            if (pending.id == observedTicket && (currentUiId == null || registry.get(currentUiId) == null))
                return Result(State.STALE)
            val request = registry.registerHttp(child, pending, alive, offer) ?: return Result(State.REJECTED)
            return Result(State.PENDING, request)
        }
        val ticket = OpenFluxHttpWire.opened(bytes)?.id ?: OpenFluxHttpWire.metadata(bytes)?.id
            ?: OpenFluxHttpWire.chunk(bytes)?.use { it.id } ?: OpenFluxHttpWire.end(bytes)?.id
            ?: OpenFluxHttpWire.result(bytes)?.id ?: return Result(State.REJECTED)
        val request = currentUiId?.let(registry::get)
            ?: return Result(if (observedTicket != null) State.STALE else State.REJECTED)
        if (request.child != child) return Result(State.REJECTED)
        if (request.nativeId != ticket) return Result(State.STALE)
        if (!request.http) return Result(State.REJECTED)
        return Result(when (request.receiveHttp(child, bytes)) {
            OpenFluxHttpSession.Received.CONSUMED, OpenFluxHttpSession.Received.COMPLETE -> State.CONSUMED
            else -> State.REJECTED
        })
    }
}

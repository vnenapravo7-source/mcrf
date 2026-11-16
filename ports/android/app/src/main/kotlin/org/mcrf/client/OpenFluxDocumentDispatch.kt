package org.mcrf.client

/** Source wiring is not a capability receipt. Imported profiles cannot open this gate. */
internal object OpenFluxOwnVerificationPolicy {
    const val productionEnabled = false
    fun sourceCompatible(verification: Boolean, documentContract: Int) = verification && documentContract == 1
}

/** Must run before every diagnostic sink. Results never contain document bytes. */
internal object OpenFluxDocumentDispatch {
    enum class State { NOT_DOCUMENT, CONSUMED, PENDING, STALE, REJECTED }
    class Result(val state: State, val request: OpenFluxVerificationRegistry.Request? = null) {
        override fun toString() = "OpenFluxDocumentDispatch($state)"
    }
    fun receive(bytes: ByteArray, child: String, enabled: Boolean, closed: Boolean,
                registry: OpenFluxVerificationRegistry, currentUiId: String?, observedTicket: String?,
                alive: () -> Boolean, offer: (ByteArray) -> Boolean): Result {
        if (!OpenFluxDocumentWire.recognized(bytes)) return Result(State.NOT_DOCUMENT)
        if (closed) return Result(State.CONSUMED)
        if (!enabled) return Result(State.REJECTED)
        OpenFluxDocumentWire.pending(bytes)?.let { pending ->
            if (!alive()) return Result(State.STALE)
            // Duplicate notice cannot resurrect the last cancelled/completed ticket.
            if (pending.id == observedTicket && (currentUiId == null || registry.get(currentUiId) == null))
                return Result(State.STALE)
            val request = registry.registerInline(child, pending, alive, offer) ?: return Result(State.REJECTED)
            return Result(State.PENDING, request)
        }
        // Parsing is bounded to one frame. The temporary decoded chunk is wiped;
        // only the receiver may accumulate data, after explicit manual OPEN.
        val ticket = OpenFluxDocumentWire.metadata(bytes)?.id
            ?: OpenFluxDocumentWire.chunk(bytes)?.use { it.id }
            ?: OpenFluxDocumentWire.end(bytes)?.id
            ?: return Result(State.REJECTED)
        val request = currentUiId?.let(registry::get)
            ?: return Result(if (observedTicket != null) State.STALE else State.REJECTED)
        if (request.child != child) return Result(State.REJECTED)
        if (request.nativeId != ticket) return Result(State.STALE)
        if (!request.inline) return Result(State.REJECTED)
        return Result(when (request.receiveDocument(child, bytes)) {
            OpenFluxDocumentReceiver.Result.CONSUMED, OpenFluxDocumentReceiver.Result.COMPLETE -> State.CONSUMED
            else -> State.REJECTED
        })
    }
}

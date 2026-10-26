package org.mcrf.client

/** Exact opaque-ID events only; never reopen another browser owner or resend a queued reply. */
internal object OpenFluxVerificationUiEvents {
    const val REOFFER = "org.mcrf.client.OPENFLUX_VERIFICATION_REOFFER"
    const val CANCEL = "org.mcrf.client.OPENFLUX_VERIFICATION_CANCEL"
    val ID = Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
    enum class Result { IGNORED, REOFFERED, CANCELLED }
    fun apply(registry: OpenFluxVerificationRegistry, id: String, action: String?): Result {
        if (!id.matches(ID)) return Result.IGNORED
        if (action == REOFFER) return if (registry.reopenAfterRecreate(id)) Result.REOFFERED else Result.IGNORED
        if (action != null && action != CANCEL) return Result.IGNORED
        registry.cancel(id)
        return Result.CANCELLED // Cancel the exact notification even after native retirement.
    }
}

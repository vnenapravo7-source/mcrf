package org.mcrf.client

/** Platform adapters must prove freshness and unambiguous Android foreground ownership.
 * Connection-owner callbacks, screen state and last-seen traffic are NOT foreground signals. */
object ForegroundAppPolicy {
    enum class Mode { DEFAULT, REALTIME, ECONOMY }
    data class Signal(val app: String, val foreground: Boolean, val observedElapsed: Long, val reliable: Boolean)
    fun mode(default: Mode, signal: Signal?, nowElapsed: Long, foreground: Mode, background: Mode): Mode {
        if (signal == null || !signal.reliable || signal.app.isBlank() || nowElapsed - signal.observedElapsed !in 0..5_000) return default
        return if (signal.foreground) foreground else background
    }
    // The current SPI has no reliable foreground-app signal. Do not request new Android permissions
    // or infer foreground/background from UID traffic. Both follow the configured common policy.
    fun androidSignal(): Signal? = null
}

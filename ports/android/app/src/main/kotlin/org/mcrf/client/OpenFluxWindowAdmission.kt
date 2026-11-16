package org.mcrf.client

/** A queued manual action belongs to one foreground epoch, not merely an Activity.
 * This gate cannot revoke an IPC call already admitted after its last check. */
internal class OpenFluxWindowAdmission {
    class Permit internal constructor(internal val owner: OpenFluxWindowAdmission, internal val epoch: Long) {
        override fun toString() = "OpenFluxWindowPermit(redacted)"
    }
    private var epoch = 0L
    private var resumed = false
    private var closed = false
    @Synchronized fun resume() { if (!closed && !resumed) { epoch++; resumed = true } }
    @Synchronized fun pause() { if (resumed) { resumed = false; epoch++ } }
    @Synchronized fun capture(): Permit? = if (!closed && resumed) Permit(this, epoch) else null
    @Synchronized fun current(permit: Permit?) = permit != null && permit.owner === this &&
        !closed && resumed && permit.epoch == epoch
    @Synchronized fun close() { closed = true; resumed = false; epoch++ }
}

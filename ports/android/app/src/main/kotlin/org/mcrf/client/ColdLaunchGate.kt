package org.mcrf.client

import java.util.concurrent.atomic.AtomicBoolean

/** One decision per process, including launches where the effect is disabled. */
internal class ColdLaunchGate {
    private val consumed = AtomicBoolean(false)
    fun take(enabled: Boolean, motionAllowed: Boolean): Boolean =
        consumed.compareAndSet(false, true) && enabled && motionAllowed
}

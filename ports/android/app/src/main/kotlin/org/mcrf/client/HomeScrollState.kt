package org.mcrf.client

/** Collapse only after the primary action leaves the viewport; avoid jitter at its edge. */
object HomeScrollState {
    fun minimized(primaryBottom: Int, scrollY: Int, previous: Boolean, hysteresis: Int): Boolean {
        if (primaryBottom <= 0) return false
        return if (previous) primaryBottom < scrollY.toLong() + hysteresis.coerceAtLeast(0)
            else primaryBottom <= scrollY
    }
}

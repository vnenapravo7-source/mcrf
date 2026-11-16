package org.mcrf.client

/** A failed renderer may throw from stopLoading/setters. Never skip destruction
 * because an earlier best-effort step failed. Does not prove renderer death. */
internal object OpenFluxWebViewRetirement {
    fun retire(remove: () -> Unit, stop: () -> Unit, clearChrome: () -> Unit,
               clearClient: () -> Unit, destroy: () -> Unit) {
        for (step in listOf(remove, stop, clearChrome, clearClient, destroy)) {
            try { step() } catch (_: Exception) { /* No renderer exception/body enters diagnostics. */ }
        }
    }
}

package org.mcrf.client

import android.content.Context

/** Prepared off-UI before per-request budgets begin. Closing during startup also
 * closes a late private ingress through the run's cancellation attachment. */
internal class DiagnosticsProbeSession(private val context: Context) : AutoCloseable {
    private val token = DiagnosticCancellation(120_000)
    private val closed=java.util.concurrent.atomic.AtomicBoolean()
    fun isClosed()=closed.get()
    fun prepare(): RealServiceProbe {
        token.check()
        val current = RealServiceProbe.capture(context)
        if (current.available(DiagnosticPath.CURRENT_MCRF)) return current
        val id = McrfVpnService.selectedId.ifEmpty {
            context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("selected", "").orEmpty()
        }
        val server = Connections(context).apply { load() }.entries().firstOrNull { it.id == id } ?: return current
        return try { DiagnosticsTemporaryProxy.open(context, server, token).probe.also { token.check() } }
        catch (_: Exception) { token.check(); current }
    }
    override fun close() { closed.set(true);token.cancel() }
}

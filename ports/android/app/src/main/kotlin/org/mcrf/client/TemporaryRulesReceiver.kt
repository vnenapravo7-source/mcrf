package org.mcrf.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

class TemporaryRulesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(TemporaryRulesRuntime.EXPIRE, Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED)) return
        val startupAction = TemporaryRulesStartupPolicy.isStartupAction(intent.action)
        val pending = goAsync()
        worker.execute { try { reconcileNow(context.applicationContext, startupAction) } finally { pending.finish() } }
    }
    companion object {
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mcrf-temporary-rules").apply { isDaemon = true } }
        internal fun reconcile(context: Context) { worker.execute { reconcileNow(context.applicationContext) } }
        private fun reconcileNow(context: Context, startupAction: Boolean = false) {
            try {
                if (TemporaryRulesStartupPolicy.metadataOnly(startupAction, McrfVpnService.hasActiveSession())) {
                    if (!TemporaryRulesStartupProvider.reconcileMetadata(context)) return
                } else {
                    RoutingStore(context).load() // Full validation for active sessions and all non-startup requests.
                }
                // Recheck after housekeeping: a VPN may have started since the idle-branch decision.
                if (McrfVpnService.hasActiveSession()) context.startService(Intent(context, McrfVpnService::class.java).setAction(McrfVpnService.RELOAD_ROUTES))
            } catch (_: Exception) {
                ClientEvents.record(context, "Маршруты", "Проверка временных правил отложена. Перед сборкой маршрутов срок проверяется снова.")
            }
        }
    }
}

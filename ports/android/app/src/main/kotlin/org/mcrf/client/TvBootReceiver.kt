package org.mcrf.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TvBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !TvUi.enabled(context)) return
        if (!context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean(ENABLED, false)) return
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(launch) }
    }

    companion object {
        internal const val ENABLED = "tvOpenOnBoot"
    }
}

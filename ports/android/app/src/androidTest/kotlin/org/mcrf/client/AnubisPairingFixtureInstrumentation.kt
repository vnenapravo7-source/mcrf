package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.util.concurrent.TimeUnit

class AnubisPairingFixtureInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var activity: Activity? = null
        var outcome = "FAIL_SCOPE"
        try {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            val context = targetContext
            check(context.packageName == AnubisPairingFixtureActivity.APP_ID)
            check(context.applicationInfo.uid == Process.myUid() && BuildConfig.ANUBIS_WIRELESS_PAIRING)
            check(!ManagedAppFreezeAdb.supports(context))
            AnubisPairingFixtureActivity.result.clear()
            activity = startActivitySync(Intent(context, AnubisPairingFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            outcome = AnubisPairingFixtureActivity.result.poll(3, TimeUnit.MINUTES) ?: "FAIL_INPUT_TIMEOUT"
        } catch (_: Exception) {
            outcome = "FAIL_SCOPE"
        } finally {
            activity?.let { opened -> runOnMainSync { opened.finish() } }
            finish(if (outcome == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("anubisPairing", outcome) })
        }
    }
}

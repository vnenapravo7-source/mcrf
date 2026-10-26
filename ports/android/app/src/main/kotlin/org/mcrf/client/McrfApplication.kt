package org.mcrf.client

import android.app.Application

class McrfApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() == "$packageName:openfluxVerification") {
            OpenFluxVerificationWebProfile.ready = try {
                android.webkit.WebView.setDataDirectorySuffix("openflux-verification"); true
            } catch (_: Exception) { false } // Never fall back to VK's WebView profile.
        }
        ClientSettingsRegistry.register()
    }
}

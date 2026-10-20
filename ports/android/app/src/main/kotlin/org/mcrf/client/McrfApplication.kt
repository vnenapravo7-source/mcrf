package org.mcrf.client

import android.app.Application

class McrfApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ClientSettingsRegistry.register()
    }
}

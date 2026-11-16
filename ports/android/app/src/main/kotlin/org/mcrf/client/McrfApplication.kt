package org.mcrf.client

import android.app.Application
import android.app.Activity
import android.os.Bundle

class McrfApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() == "$packageName:openfluxVerification") {
            OpenFluxVerificationWebProfile.ready = try {
                android.webkit.WebView.setDataDirectorySuffix("openflux-verification"); true
            } catch (_: Exception) { false } // Never fall back to VK's WebView profile.
        }
        ClientSettingsRegistry.register()
        if(Application.getProcessName()==packageName) {
            runCatching {AndroidBackup.applyRestoredPreferences(this)}.onSuccess {NetworkProfilesRuntime.start(this)}
            ManagedAppFreezeUi.observe(this)
            runCatching {LauncherIcons.migrateRetired(this)}
            AppTypography.configure(this)
            registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks{
                override fun onActivityCreated(activity:Activity,state:Bundle?){
                    AppTypography.watch(activity.window.decorView)
                }
                override fun onActivityStarted(activity:Activity)=Unit
                override fun onActivityResumed(activity:Activity)=Unit
                override fun onActivityPaused(activity:Activity)=Unit
                override fun onActivityStopped(activity:Activity)=Unit
                override fun onActivitySaveInstanceState(activity:Activity,state:Bundle)=Unit
                override fun onActivityDestroyed(activity:Activity)=Unit
            })
        }
    }
}

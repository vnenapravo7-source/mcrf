package org.mcrf.client

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Prefer AOSP's exported own-package battery detail, never the global optimization list.
 * Contract: packages/apps/Settings AdvancedPowerUsageDetailActivity reads package: URI.
 * OEMs can omit it; only system handlers are eligible, then explicit own-app fallbacks.
 */
object ClientBatterySettings {
    enum class Destination { BATTERY, EXEMPTION, APP_DETAILS, UNAVAILABLE }
    fun open(activity:Activity):Destination {
        val uri=Uri.parse("package:"+activity.packageName)
        val choices=mutableListOf(Destination.BATTERY to Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL",uri))
        if(!activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName))
            choices.add(Destination.EXEMPTION to Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,uri))
        choices.add(Destination.APP_DETAILS to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,uri))
        for((destination,intent) in choices)try{
            val resolved=activity.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?:continue
            if(!resolved.exported||resolved.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM==0)continue
            intent.setClassName(resolved.packageName,resolved.name)
            activity.startActivity(intent)
            return destination
        }catch(_:android.content.ActivityNotFoundException){}catch(_:SecurityException){}
        return Destination.UNAVAILABLE
    }
}

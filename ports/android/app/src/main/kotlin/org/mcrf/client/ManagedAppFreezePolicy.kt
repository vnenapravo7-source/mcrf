package org.mcrf.client

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityManager

internal object ManagedAppFreezePolicy {
    fun automaticTargets(
        watched:Set<String>,
        vpnOnly:Set<String>,
        ownsTunnel:Boolean,
        externalVpn:Boolean
    ):Set<String> {
        val hidden=if(ownsTunnel||externalVpn)watched else emptySet()
        return if(ownsTunnel)hidden-vpnOnly else hidden+vpnOnly
    }

    fun packageBlockReason(context:Context,id:String):String? {
        val manager=context.packageManager
        val info=runCatching {manager.getApplicationInfo(id,0)}.getOrNull()?:return "приложение не найдено"
        if(id==context.packageName||id=="moe.shizuku.privileged.api"||info.uid<10_000||
            info.flags and ApplicationInfo.FLAG_PERSISTENT!=0)return "системный компонент защищён"
        val home=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        if(manager.resolveActivity(home,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName==id)
            return "активный рабочий стол"
        if(info.flags and ApplicationInfo.FLAG_SYSTEM!=0){
            val launcher=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(id)
            if(info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP==0||
                manager.queryIntentActivities(launcher,PackageManager.MATCH_DISABLED_COMPONENTS).isEmpty())
                return "системный компонент защищён"
        }
        return null
    }

    fun blockReason(context:Context,id:String):String? {
        packageBlockReason(context,id)?.let {return it}
        val keyboard=Settings.Secure.getString(context.contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        if(keyboard==id)return "активная клавиатура"
        val accessibility=context.getSystemService(AccessibilityManager::class.java)
        if(accessibility.getEnabledAccessibilityServiceList(-1).any {it.resolveInfo?.serviceInfo?.packageName==id})
            return "активная служба спецвозможностей"
        if(context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage==id||
            Telephony.Sms.getDefaultSmsPackage(context)==id)return "приложение звонков или SMS по умолчанию"
        if(context.getSystemService(android.app.admin.DevicePolicyManager::class.java)?.activeAdmins?.any {it.packageName==id}==true)
            return "администратор устройства"

        return null
    }
}

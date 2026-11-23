package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.util.UUID
import rikka.shizuku.Shizuku

/** An isolated emulator uses an unused add-on as the package-state fixture. */
class ManagedAppFreezeInstrumentation:Instrumentation() {
    private var withVpn=false
    private var restoreSingleCheck=false
    private var onlyVpnCheck=false
    private var permissionUiCheck=false
    private var systemLaunchCheck=false
    private var systemExitCheck=false
    private var configuredOnlyLaunch=false
    override fun onCreate(arguments:Bundle?){
        super.onCreate(arguments)
        withVpn=arguments?.getString("withVpn")=="true"
        restoreSingleCheck=arguments?.getString("restoreSingleCheck")=="true"
        onlyVpnCheck=arguments?.getString("onlyVpnCheck")=="true"
        permissionUiCheck=arguments?.getString("permissionUiCheck")=="true"
        systemLaunchCheck=arguments?.getString("systemLaunchCheck")=="true"
        systemExitCheck=arguments?.getString("systemExitCheck")=="true"
        configuredOnlyLaunch=arguments?.getString("configuredOnlyLaunch")=="true"
        start()
    }

    override fun onStart(){
        val result=Bundle()
        val fixture="org.mcrf.engines.xray"
        val launcher="moe.shizuku.privileged.api"
        val app=targetContext
        val prefs=app.getSharedPreferences("MainActivity",0)
        val onlyKey=AppLaunchGroupsUi.Mode.ONLY_VPN.key
        val previousOnly=prefs.getString(onlyKey,null)
        val systemKey=AppLaunchGroupsUi.Mode.WITHOUT_VPN.key
        val previousSystem=prefs.getString(systemKey,null)
        val launchKey=if(withVpn)AppLaunchGroupsUi.Mode.WITH_VPN.key else systemKey
        val previousLaunch=prefs.getString(launchKey,null)
        var configured=false
        var resultCode=Activity.RESULT_CANCELED
        var stage="prepare"
        fun waitFor(label:String,condition:()->Boolean){
            val deadline=SystemClock.elapsedRealtime()+20000
            while(!condition()){
                check(SystemClock.elapsedRealtime()<deadline){label}
                SystemClock.sleep(150)
            }
        }
        fun state()=app.packageManager.getApplicationEnabledSetting(fixture)
        try {
            if(configuredOnlyLaunch){
                check(BuildConfig.DEBUG&&app.packageName==ManagedAppFreezeAdb.UI_CLONE_PACKAGE)
                val chosen="org.mcrf.anubis.freeze.fixture"
                check(app.packageManager.getLaunchIntentForPackage(chosen)!=null)
                check(prefs.edit().putString(onlyKey,org.json.JSONArray(listOf(chosen)).toString()).commit())
                val host=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("test_no_prompts",true)) as MainActivity
                val automation=getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                try {
                    waitFor("Anubis provider did not reconnect"){ManagedAppFreezeUi.authorized()}
                    waitFor("Expected a frozen app before launch"){ManagedAppFreezeUi.isFrozen(app,chosen)}
                    runOnMainSync {AppLaunchShortcuts.requestLaunch(host,chosen)}
                    waitFor("VPN-only app did not open after VPN and restoration"){
                        McrfVpnService.ownsSystemTunnel()&&!ManagedAppFreezeUi.isFrozen(app,chosen)&&
                            automation.rootInActiveWindow?.packageName==chosen
                    }
                    result.putString("verified","Saved wireless ADB reconnected and VPN-only app restored and opened")
                    resultCode=Activity.RESULT_OK
                }finally{
                    prefs.edit().apply {if(previousOnly==null)remove(onlyKey)else putString(onlyKey,previousOnly)}.commit()
                    runOnMainSync {ManagedAppFreezeUi.observe(app)}
                    if(McrfVpnService.ownsSystemTunnel())app.startService(Intent(app,McrfVpnService::class.java)
                        .setAction(McrfVpnService.STOP))
                }
                return
            }
            check(BuildConfig.DEBUG&&android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
            waitFor("Fixture package was not restored before the test"){state()==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT}
            val journal=app.getSharedPreferences("app-freeze-journal",0)
            check(journal.getString("states","{}")=="{}")
            check(journal.getString("watched",null) in setOf(null,"[\"$fixture\"]"))
            val host=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("test_no_prompts",true)) as MainActivity
            waitFor("Shizuku binder"){Shizuku.pingBinder()}
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){
                if(permissionUiCheck){
                    runOnMainSync {
                        check(AnubisLaunchObserver.enabled(host)) {"Enable the Anubis accessibility service on the test emulator"}
                        host.navigate(ClientSettingsNavigation.ANUBIS)
                        if(ManagedAppFreezeUi.providerChosen(host))
                            requireNotNull(host.window.decorView.findViewWithTag<android.view.View>("anubis-provider")).performClick()
                    }
                    runOnMainSync {
                        val provider=android.view.inspector.WindowInspector.getGlobalWindowViews()
                            .firstNotNullOfOrNull {it.findViewById<android.widget.Button>(android.R.id.button1)}
                        check(requireNotNull(provider).performClick())
                    }
                    runOnMainSync {
                        val button=android.view.inspector.WindowInspector.getGlobalWindowViews()
                            .firstNotNullOfOrNull {it.findViewWithTag<android.widget.Button>("shizuku-request-permission")}
                        check(requireNotNull(button).performClick())
                    }
                }else runOnMainSync {Shizuku.requestPermission(42)}
                waitFor("Shizuku permission dialog was not approved"){
                    Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED
                }
            }
            if(systemLaunchCheck||systemExitCheck)waitFor("MCRF activity window"){host.hasWindowFocus()}
            else waitFor("MCRF activity window"){uiAutomation.rootInActiveWindow?.packageName==app.packageName}
            if(permissionUiCheck){
                waitFor("Connected provider status is missing"){
                    uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Shizuku · подключён")?.isNotEmpty()==true
                }
                result.putString("verified","Shizuku grant updates provider status in Anubis")
                resultCode=Activity.RESULT_OK
                return
            }
            configured=true
            if(onlyVpnCheck){
                check(prefs.edit().putString(onlyKey,org.json.JSONArray(listOf(fixture)).toString()).commit())
                runOnMainSync {ManagedAppFreezeUi.observe(app)}
                waitFor("VPN-only app was not frozen without VPN"){state()==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER}
            }else if(systemLaunchCheck){
                check(prefs.edit().putString(systemKey,org.json.JSONArray(listOf(launcher)).toString()).commit())
            }else if(!systemExitCheck) runOnMainSync {ManagedAppFreezeUi.configureAutomatic(app,setOf(fixture))}
            stage="owned VPN and freeze"
            ClientPrimaryVpnDeviceChecks.runWithCustomStop(app,File(app.cacheDir,"mcrf-test-${UUID.randomUUID()}")) {
                if(onlyVpnCheck){
                    waitFor("VPN-only app did not resume after connection"){state()==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT}
                    check(McrfVpnService.ownsSystemTunnel())
                    app.startService(Intent(app,McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                    return@runWithCustomStop
                }
                if(systemExitCheck){
                    stage="return home from app-scoped VPN"
                    check(McrfVpnService.ownsSystemTunnel())
                    runOnMainSync {
                        host.startActivity(requireNotNull(host.packageManager.getLaunchIntentForPackage(launcher)))
                        AnubisLaunchObserver.followApp(app,launcher,McrfVpnService.selectedId)
                    }
                    waitFor("Target did not leave MCRF"){!host.hasWindowFocus()}
                    SystemClock.sleep(1300)
                    val automation=getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    automation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                    waitFor("VPN did not stop when leaving the app"){!McrfVpnService.ownsSystemTunnel()}
                    return@runWithCustomStop
                }
                if(systemLaunchCheck){
                    stage="ordinary Android launch turns off VPN"
                    check(McrfVpnService.ownsSystemTunnel())
                    check(host.hasWindowFocus())
                    runOnMainSync {host.startActivity(requireNotNull(host.packageManager.getLaunchIntentForPackage(launcher)))}
                    waitFor("System launch did not turn off VPN"){!McrfVpnService.ownsSystemTunnel()}
                    waitFor("Target did not return to foreground"){
                        !host.hasWindowFocus()&&getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                            .rootInActiveWindow?.packageName==launcher
                    }
                    return@runWithCustomStop
                }
                waitFor("Package was not frozen while the owned VPN was active"){
                    state()==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                }
                check(McrfVpnService.ownsSystemTunnel())
                if(restoreSingleCheck){
                    stage="individual restore and journal"
                    val notifications=app.getSystemService(android.app.NotificationManager::class.java)
                    check(notifications.areNotificationsEnabled())
                    runOnMainSync {AppFreezeRecoveryNotice.update(app,1)}
                    waitFor("Recovery notification was not posted"){notifications.activeNotifications.any {it.tag=="app-freeze-recovery"}}
                    runOnMainSync {AppFreezeRecoveryNotice.update(app,0)}
                    waitFor("Recovery notification was not cleared"){notifications.activeNotifications.none {it.tag=="app-freeze-recovery"}}
                    runOnMainSync {check(ManagedAppFreezeUi.restoreOne(host,fixture))}
                    waitFor("Individual package restore failed"){state()==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT}
                    check(McrfVpnService.ownsSystemTunnel()&&!ManagedAppFreezeUi.selectedForFreeze(app,fixture))
                    waitFor("Missing journal events"){
                        val log=ClientEvents.read(app)
                        log.contains("Заморозка · ")&&log.contains("Восстановление · ")&&log.contains("вручную.")
                    }
                    app.startService(Intent(app,McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                    return@runWithCustomStop
                }
                stage=if(withVpn)"launch with VPN"else"launch without VPN"
                check(prefs.edit().putString(launchKey,org.json.JSONArray(listOf(launcher)).toString()).commit())
                runOnMainSync {AppLaunchShortcuts.requestLaunch(host,launcher)}
                waitFor("Selected app did not open"){uiAutomation.rootInActiveWindow?.packageName==launcher}
                check(McrfVpnService.ownsSystemTunnel()==withVpn)
                if(withVpn)app.startService(Intent(app,McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                waitFor("Frozen package was not restored"){state()==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT}
            }
            if(onlyVpnCheck){
                waitFor("VPN-only app did not freeze after stop"){state()==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER}
                check(prefs.edit().remove(onlyKey).commit())
                runOnMainSync {ManagedAppFreezeUi.observe(app)}
            }
            waitFor("Frozen package was not restored after stop"){state()==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT}
            result.putString("verified",if(onlyVpnCheck)"VPN-only freezes offline, restores on real TUN, freezes again on stop"
                else if(systemLaunchCheck)"ordinary Android launch stopped the VPN and returned to the app"
                else if(systemExitCheck)"going Home stops the app-scoped real VPN"
                else if(restoreSingleCheck)"individual restore while VPN is active and client journal"
                else "official Shizuku, real TUN, freeze and restore, app launch ${if(withVpn)"with"else"without"} VPN")
            resultCode=Activity.RESULT_OK
        }catch(error:Throwable){
            result.putString("failure","$stage: ${error.javaClass.simpleName}: ${error.message}")
        }finally{
            if(configured)runOnMainSync {
                if(onlyVpnCheck)prefs.edit().apply {if(previousOnly==null)remove(onlyKey)else putString(onlyKey,previousOnly)}.commit()
                if(systemLaunchCheck)prefs.edit().apply {if(previousSystem==null)remove(systemKey)else putString(systemKey,previousSystem)}.commit()
                prefs.edit().apply {if(previousLaunch==null)remove(launchKey)else putString(launchKey,previousLaunch)}.commit()
                ManagedAppFreezeUi.configureAutomatic(app,emptySet())
            }
            finish(resultCode,result)
        }
    }
}

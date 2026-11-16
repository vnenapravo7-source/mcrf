package org.mcrf.client

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/** Optional system launch trigger. It subscribes only to the configured package names. */
class AnubisLaunchObserver : AccessibilityService() {
    private val preferences by lazy {getSharedPreferences("MainActivity",MODE_PRIVATE)}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key==null||key=="experienceMode"||key==AppLaunchGroupsUi.Mode.WITH_VPN.key||
            key==AppLaunchGroupsUi.Mode.WITHOUT_VPN.key)refreshPackages()
    }
    private var watched=emptySet<String>()
    private var lastRequest:String?=null
    private var lastRequestAt=0L
    private val handler=Handler(Looper.getMainLooper())
    private data class Session(val target:String,val server:String,val launchedAt:Long)
    private var session:Session?=null
    private val release=Runnable {
        val current=session?:return@Runnable
        clearSession()
        if(McrfVpnService.ownsSystemTunnel()&&McrfVpnService.selectedId==current.server){
            runCatching {startService(Intent(this,McrfVpnService::class.java).setAction(McrfVpnService.STOP))}
                .onSuccess {ClientEvents.record(this,"Anubis","Отключаем VPN после выхода из приложения.")}
                .onFailure {ClientEvents.record(this,"Anubis","Не удалось отключить VPN после выхода из приложения.")}
        }
    }

    override fun onServiceConnected(){
        super.onServiceConnected()
        connected=this
        preferences.registerOnSharedPreferenceChangeListener(listener)
        refreshPackages()
    }

    private fun refreshPackages(){
        watched=if(ExperienceModeStore(this).load()==ExperienceMode.EXPERT)
            AppLaunchGroupsUi.systemLaunchPackages(this)-packageName else emptySet()
        if(ExperienceModeStore(this).load()!=ExperienceMode.EXPERT){
            session=null
            handler.removeCallbacks(release)
        }
        val configuration=serviceInfo?:return
        configuration.packageNames=if(session!=null)null else watched.ifEmpty {setOf(packageName)}.toTypedArray()
        serviceInfo=configuration
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent){
        if(event.eventType!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return
        val app=event.packageName?.toString()?:return
        val name=event.className?.toString()?:return
        val now=SystemClock.uptimeMillis()
        val current=session
        if(current!=null){
            if(!McrfVpnService.ownsSystemTunnel()||McrfVpnService.selectedId!=current.server)clearSession()
            else if(app==current.target)handler.removeCallbacks(release)
            else if(now-current.launchedAt>=650L&&(isActivity(app,name)||isHome(app))){
                if(AppLaunchGroupsUi.modeFor(this,app)?.withVpn==true){
                    session=current.copy(target=app,launchedAt=now)
                    handler.removeCallbacks(release)
                }else{
                    handler.removeCallbacks(release)
                    handler.postDelayed(release,700)
                }
            }
        }
        if(app !in watched||!isActivity(app,name))return
        val mode=AppLaunchGroupsUi.modeFor(this,app)?:return
        val active=McrfVpnService.ownsSystemTunnel()||
            preferences.getBoolean("tun",true)&&McrfVpnService.hasActiveSession()
        if(mode.withVpn==active){
            if(lastRequest==app)lastRequest=null
            return
        }
        if(lastRequest==app&&now-lastRequestAt<30_000)return
        lastRequest=app
        lastRequestAt=now
        runCatching {AppLaunchShortcuts.requestLaunch(this,app)}.onFailure {
            ClientEvents.record(this,"Anubis","Android не открыл MCRF для переключения VPN.")
        }
    }

    private fun isActivity(app:String,name:String)=runCatching {
        packageManager.getActivityInfo(ComponentName.createRelative(app,name),0)
    }.isSuccess

    private fun isHome(app:String)=packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName==app

    private fun clearSession(){
        session=null
        handler.removeCallbacks(release)
        refreshPackages()
    }

    override fun onInterrupt()=Unit

    override fun onDestroy(){
        if(connected===this)connected=null
        handler.removeCallbacks(release)
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
    }

    companion object {
        private var connected:AnubisLaunchObserver?=null

        internal fun onVpnStopped(){connected?.clearSession()}

        internal fun followApp(app:String,server:String){
            val observer=connected?:return
            observer.handler.removeCallbacks(observer.release)
            observer.session=Session(app,server,SystemClock.uptimeMillis())
            observer.refreshPackages()
        }

        internal fun openSettings(activity:MainActivity){
            val component=ComponentName(activity,AnubisLaunchObserver::class.java)
            val detail=Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME,component)
            try {
                activity.startActivity(detail)
                return
            }catch(_:android.content.ActivityNotFoundException){
                // Some Android builds expose only the main accessibility screen.
            }catch(_:SecurityException){
                // Android can reserve the details screen for system apps and the installer.
            }
            activity.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            activity.toast("В списке установленных служб выберите MCRF · Anubis.")
        }
    }
}

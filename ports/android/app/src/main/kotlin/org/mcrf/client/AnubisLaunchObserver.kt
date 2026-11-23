package org.mcrf.client

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

class AnubisWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action!="org.mcrf.client.ANUBIS_WINDOW" || !AnubisLaunchObserver.enabled(context))return
        val app=intent.getStringExtra("target") ?: return
        val window=intent.getStringExtra("window") ?: return
        AnubisLaunchObserver.onWindow(context.applicationContext,app,window)
    }
}

class AnubisWatchProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        check(callingPackage == AnubisLaunchObserver.MODULE_PACKAGE)
        val owner = requireNotNull(context)
        require(uri == AnubisLaunchObserver.watchUri(owner))
        return MatrixCursor(arrayOf("packageName")).apply {
            if (AnubisRouteAccess.allowed(owner)) {
                if (AnubisLaunchObserver.following()) addRow(arrayOf("*"))
                else AppLaunchGroupsUi.systemLaunchPackages(owner).forEach { addRow(arrayOf(it)) }
            }
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

internal object AnubisLaunchObserver {
    const val MODULE_PACKAGE="org.mcrf.anubis"
    private val component=ComponentName(MODULE_PACKAGE,"$MODULE_PACKAGE.LaunchObserver")
    private val handler=Handler(Looper.getMainLooper())
    private data class Session(val target:String,val server:String,val launchedAt:Long)
    @Volatile private var session:Session?=null
    private var sessionOwner:Context?=null

    fun following() = session != null
    fun watchUri(context: Context): Uri = Uri.parse("content://${context.packageName}.anubis-watch/windows")
    fun refreshWatch(context: Context) = context.contentResolver.notifyChange(watchUri(context),null)
    private var lastRequest:String?=null
    private var lastRequestAt=0L

    fun onWindow(context:Context,app:String,name:String){
        if(!AnubisRouteAccess.allowed(context)){
            clearSession()
            return
        }
        val preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
        val now=SystemClock.uptimeMillis()
        val current=session
        if(current!=null){
            if(!McrfVpnService.ownsSystemTunnel()||McrfVpnService.selectedId!=current.server)clearSession()
            else if(app==current.target)handler.removeCallbacksAndMessages(null)
            else if(now-current.launchedAt>=650L&&(isActivity(context,app,name)||isHome(context,app))){
                if(AppLaunchGroupsUi.modeFor(context,app)?.withVpn==true){
                    if(AppLaunchGroupsUi.keepVpnAfterExit(context,app))clearSession()
                    else {
                        session=current.copy(target=app,launchedAt=now)
                        handler.removeCallbacksAndMessages(null)
                    }
                }else{
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({
                        val pending=session ?: return@postDelayed
                        clearSession()
                        if(enabled(context) && AnubisRouteAccess.allowed(context) &&
                            !AppLaunchGroupsUi.keepVpnAfterExit(context,pending.target) &&
                            McrfVpnService.ownsSystemTunnel() && McrfVpnService.selectedId==pending.server) {
                            runCatching {context.startService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.STOP))}
                                .onFailure {ClientEvents.record(context,"Anubis","Не удалось отключить VPN после выхода из приложения.")}
                        }
                    },700)
                }
            }
        }
        if(app == context.packageName || app !in AppLaunchGroupsUi.systemLaunchPackages(context) ||!isActivity(context,app,name))return
        val mode=AppLaunchGroupsUi.modeFor(context,app)?:return
        val active=McrfVpnService.ownsSystemTunnel()&&ConnectionPresentation.connected(McrfVpnService.status)
        if(if(mode.withVpn)active else !McrfVpnService.ownsSystemTunnel()&&
            !(preferences.getBoolean("tun",true)&&ConnectionPresentation.pending(McrfVpnService.status))){
            if(lastRequest==app)lastRequest=null
            return
        }
        if(lastRequest==app&&now-lastRequestAt<30_000)return
        lastRequest=app
        lastRequestAt=now
        AppLaunchShortcuts.requestLaunch(context,app,alreadyOpen=true)
    }

    private fun isActivity(context:Context,app:String,name:String)=runCatching {
        context.packageManager.getActivityInfo(ComponentName.createRelative(app,name),0)
    }.isSuccess

    private fun isHome(context:Context,app:String)=context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName==app

    private fun clearSession(){
        session=null
        handler.removeCallbacksAndMessages(null)
        sessionOwner?.let(::refreshWatch)
        sessionOwner=null
    }

    fun onVpnStopped()=clearSession()

    fun followApp(context:Context,app:String,server:String){
        if(!enabled(context)||AppLaunchGroupsUi.keepVpnAfterExit(context,app))return
        handler.removeCallbacksAndMessages(null)
        sessionOwner=context.applicationContext
        session=Session(app,server,SystemClock.uptimeMillis())
        refreshWatch(context)
    }

    fun installed(context:Context):Boolean = runCatching {
        context.packageManager.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        context.packageManager.checkSignatures(context.packageName,MODULE_PACKAGE)==PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    fun enabled(context:Context):Boolean = installed(context) &&
        Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any {ComponentName.unflattenFromString(it)==component}

    private const val RESTRICTIONS_DONE = "anubisRestrictedSettingsStepDone"
    private const val ACCESSIBILITY_DONE = "anubisAccessibilityStepDone"

    fun setupComplete(activity: MainActivity): Boolean =
        activity.prefs.getBoolean(ACCESSIBILITY_DONE, false) && enabled(activity)

    private fun needsRestrictedSettingsStep(activity: MainActivity): Boolean =
        android.os.Build.VERSION.SDK_INT >= 33 && !activity.prefs.getBoolean(RESTRICTIONS_DONE, false)

    fun setupHint(activity: MainActivity): String =
        if (needsRestrictedSettingsStep(activity))
            "Разрешите ограниченные настройки в карточке MCRF · Anubis."
        else "Включите MCRF · Anubis в специальных возможностях Android."

    fun openSettings(activity: MainActivity): Unit = with(activity) {
        if (!installed(this)) return
        val restricted = needsRestrictedSettingsStep(this)
        val description = if (restricted)
            "Android может ограничить доступ APK, установленного отдельно. В карточке MCRF · Anubis нажмите ⋮ → «Разрешить ограниченные настройки». Вернитесь сюда и нажмите «Сделано»."
        else "В специальных возможностях Android выберите «MCRF · Anubis» и включите службу. Вернитесь сюда и нажмите «Сделано»."
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(12))
            addView(text(description, 14, Loft.MUTED), space(0, 12))
        }
        val actions = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        body.addView(actions)
        val dialog = ThemedDialogBuilder(this)
            .setTitle(if (restricted) "Разрешите ограниченные настройки" else "Включите службу Anubis")
            .setView(body)
            .create()
        fun action(label: String, accent: Boolean = false, onClick: () -> Unit) {
            val control = StandardButtons.compact(this, button(label, accent), label, accent).apply {
                setPadding(dp(2), dp(4), dp(2), dp(4))
                textSize = 12f
                setOnClickListener { onClick() }
            }
            actions.addView(control, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (actions.childCount > 0) marginStart = dp(4)
            })
        }
        action("Закрыть") { dialog.dismiss() }
        action("Перейти") {
            if (restricted) openModuleInfo(this) else openAccessibility(this)
        }
        action("Сделано", accent = true) {
            if (restricted) {
                prefs.edit().putBoolean(RESTRICTIONS_DONE, true).apply()
                dialog.dismiss()
                render()
                openSettings(this)
            } else if (enabled(this)) {
                prefs.edit().putBoolean(ACCESSIBILITY_DONE, true).apply()
                dialog.dismiss()
                render()
                promptForAnubisProvider()
            } else {
                toast("Сначала включите MCRF · Anubis в специальных возможностях.")
            }
        }
        if (!restricted && android.os.Build.VERSION.SDK_INT >= 33 && !enabled(this)) {
            body.addView(text("Android всё ещё ограничивает доступ?", 12, Loft.ACCENT).apply {
                minHeight = dp(48)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setOnClickListener {
                    prefs.edit().remove(RESTRICTIONS_DONE).apply()
                    dialog.dismiss()
                    render()
                    openSettings(this@with)
                }
            })
        }
        dialog.show()
    }

    private fun openModuleInfo(activity: MainActivity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$MODULE_PACKAGE"))
        if (runCatching { activity.startActivity(intent) }.isFailure)
            activity.toast("Откройте сведения о приложении MCRF · Anubis в настройках Android.")
    }

    private fun openAccessibility(activity:MainActivity){
        val detail=Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME,component)
        if(runCatching {activity.startActivity(detail)}.isSuccess)return
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        activity.toast("В списке установленных служб выберите MCRF · Anubis.")
    }
}
